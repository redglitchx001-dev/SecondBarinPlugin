package me.sailex.secondbrain.chat;

import me.sailex.secondbrain.SecondBrainPlugin;
import me.sailex.secondbrain.npc.NPCData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Routes player chat to NPCs, keeps per-player conversation memory
 * (thread-safe and persisted), applies cooldowns and "focus" targets.
 */
public class ChatService {

    /** One stored message: {role, content}. */
    private record Focus(String npcId, long expiresAt) {}

    private final SecondBrainPlugin plugin;
    private final Map<String, List<String[]>> histories = new ConcurrentHashMap<>(); // playerUUID_npcId
    private final Map<UUID, Long> cooldownUntil = new ConcurrentHashMap<>();
    private final Map<UUID, Focus> focus = new ConcurrentHashMap<>();
    private final File memoriesFile;
    private volatile boolean memoriesDirty = false;

    public ChatService(SecondBrainPlugin plugin) {
        this.plugin = plugin;
        this.memoriesFile = new File(plugin.getDataFolder(), "memories.yml");
        loadMemories();
        startAutoSave();
    }

    // ============================================================
    //  Chat entry point (must be called on the main thread)
    // ============================================================

    /** @return true when at least one NPC was addressed (chat may then be hidden by the caller). */
    public boolean handleChat(Player player, Component messageComponent) {
        var cm = plugin.getConfigManager();
        if (!cm.isChatEnabled()) return false;

        String msg = PlainTextComponentSerializer.plainText().serialize(messageComponent).trim();
        if (msg.isEmpty() || msg.startsWith("/")) return false;

        List<NPCData> targets = new ArrayList<>();

        // 1) Focused NPC always gets the message first.
        NPCData focused = getFocusedNPC(player);
        if (focused != null) {
            targets.add(focused);
        } else {
            // 2) Otherwise scan for NPCs in hearing range.
            boolean nameOnlyDefault = cm.isRespondOnlyToName();
            for (NPCData npc : plugin.getNpcManager().getAllNPCs().values()) {
                if (!plugin.getNpcManager().isChatEnabled(npc)) continue;
                if (npc.getLocation() == null || npc.getLocation().getWorld() == null) continue;
                if (!npc.getLocation().getWorld().equals(player.getWorld())) continue;

                double radius = plugin.getNpcManager().getChatRadius(npc);
                if (npc.getLocation().distanceSquared(player.getLocation()) > radius * radius) continue;

                boolean nameOnly = npc.getNameOnlyRaw() != null ? npc.getNameOnlyRaw() : nameOnlyDefault;
                if (nameOnly && !msg.toLowerCase(Locale.ROOT).contains(npc.getName().toLowerCase(Locale.ROOT))) continue;

                targets.add(npc);
                if (targets.size() >= Math.max(1, cm.getMaxNpcsPerMessage())) break;
            }
        }

        if (targets.isEmpty()) return false;

        // 3) Cooldown gate.
        double cd = cm.getCooldown();
        if (cd > 0 && !player.hasPermission("secondbrain.bypass.cooldown")) {
            long until = cooldownUntil.getOrDefault(player.getUniqueId(), 0L);
            long remaining = until - System.currentTimeMillis();
            if (remaining > 0) {
                player.sendMessage(cm.msg("cooldown", "seconds", String.format(Locale.ROOT, "%.1f", remaining / 1000.0)));
                return true;
            }
            cooldownUntil.put(player.getUniqueId(), System.currentTimeMillis() + (long) (cd * 1000));
        }

        // 4) Dispatch.
        boolean any = false;
        for (NPCData npc : targets) {
            if (npc.isThinking()) {
                player.sendMessage(cm.msg("busy", "name", npc.getName()));
                continue;
            }
            dispatch(npc, player, msg);
            any = true;
        }
        return any;
    }

    private void dispatch(NPCData npc, Player player, String msg) {
        var cm = plugin.getConfigManager();
        String histKey = player.getUniqueId() + "_" + npc.getId();
        List<String[]> history = histories.computeIfAbsent(histKey, k -> new ArrayList<>());

        List<Map<String, String>> snapshot = new ArrayList<>();
        synchronized (history) {
            for (String[] m : history) {
                snapshot.add(Map.of("role", m[0], "content", m[1]));
            }
        }

        npc.setThinking(true);
        plugin.getStats().recordDispatch();

        // "thinking..." action bar for everyone who will hear the reply.
        if (cm.isTypingIndicator()) {
            String thinking = cm.msgRaw("thinking", "npc", npc.getName());
            for (Player p : nearbyPlayers(npc, broadcastRadius(npc))) {
                p.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(thinking));
            }
        }

        String userContent = player.getName() + ": " + msg;

        plugin.getLlmClient().chat(npc.getSystemPrompt(), player.getName(), msg, snapshot)
                .thenAccept(result -> Bukkit.getScheduler().runTask(plugin, () -> {
                    npc.setThinking(false);
                    clearActionBar(npc);

                    if (result.error()) {
                        plugin.getStats().recordError();
                        player.sendMessage(result.reply());
                        return;
                    }

                    synchronized (history) {
                        history.add(new String[]{"user", userContent});
                        history.add(new String[]{"assistant", result.reply()});
                        int max = Math.max(2, cm.getMaxHistory());
                        while (history.size() > max) history.remove(0);
                    }
                    memoriesDirty = true;
                    npc.incrementReplies();
                    plugin.getStats().recordReply(result.latencyMs());

                    broadcastReply(npc, player, result.reply());
                }));
    }

    /** Directly addresses one NPC (used by /sb test and future scripting). No radius or cooldown checks. */
    public void sendDirect(NPCData npc, Player player, String msg) {
        if (npc.isThinking()) {
            player.sendMessage(plugin.getConfigManager().msg("busy", "name", npc.getName()));
            return;
        }
        dispatch(npc, player, msg);
    }

    private void broadcastReply(NPCData npc, Player asker, String reply) {
        var cm = plugin.getConfigManager();
        String line = cm.getChatFormat()
                .replace("{npc}", npc.getName())
                .replace("{msg}", reply);
        Component out = LegacyComponentSerializer.legacySection().deserialize(line);

        boolean askerDelivered = false;
        for (Player p : nearbyPlayers(npc, broadcastRadius(npc))) {
            p.sendMessage(out);
            if (asker != null && p.getUniqueId().equals(asker.getUniqueId())) askerDelivered = true;
        }
        // The player who asked always gets the answer, even from far away.
        if (!askerDelivered && asker != null && asker.isOnline()) asker.sendMessage(out);
    }

    private void clearActionBar(NPCData npc) {
        for (Player p : nearbyPlayers(npc, broadcastRadius(npc))) {
            p.sendActionBar(Component.empty());
        }
    }

    private double broadcastRadius(NPCData npc) {
        return plugin.getNpcManager().getChatRadius(npc) * plugin.getConfigManager().getBroadcastMultiplier();
    }

    private List<Player> nearbyPlayers(NPCData npc, double radius) {
        List<Player> out = new ArrayList<>();
        Location loc = npc.getLocation();
        if (loc == null || loc.getWorld() == null) return out;
        double r2 = radius * radius;
        for (Player p : loc.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(loc) <= r2) out.add(p);
        }
        return out;
    }

    // ============================================================
    //  Focus (right-click / /sb focus)
    // ============================================================

    public void setFocus(Player player, NPCData npc) {
        int seconds = plugin.getConfigManager().getFocusDuration();
        focus.put(player.getUniqueId(), new Focus(npc.getId(), System.currentTimeMillis() + seconds * 1000L));
    }

    public void clearFocus(Player player) { focus.remove(player.getUniqueId()); }

    public NPCData getFocusedNPC(Player player) {
        Focus f = focus.get(player.getUniqueId());
        if (f == null) return null;
        if (System.currentTimeMillis() > f.expiresAt()) { focus.remove(player.getUniqueId()); return null; }
        for (NPCData d : plugin.getNpcManager().getAllNPCs().values()) {
            if (d.getId().equals(f.npcId())) return d;
        }
        focus.remove(player.getUniqueId());
        return null;
    }

    // ============================================================
    //  Memory management
    // ============================================================

    public void clearMemory(String npcId) {
        histories.keySet().removeIf(k -> k.endsWith("_" + npcId));
        memoriesDirty = true;
    }

    public void clearAllMemories() {
        histories.clear();
        memoriesDirty = true;
    }

    public int countConversations() { return histories.size(); }

    public long countMessages(String npcId) {
        long total = 0;
        for (Map.Entry<String, List<String[]>> e : histories.entrySet()) {
            if (e.getKey().endsWith("_" + npcId)) {
                synchronized (e.getValue()) { total += e.getValue().size(); }
            }
        }
        return total;
    }

    // ============================================================
    //  Persistence (memories.yml)
    // ============================================================

    private void loadMemories() {
        if (!memoriesFile.exists()) return;
        FileConfiguration yml = YamlConfiguration.loadConfiguration(memoriesFile);
        var section = yml.getConfigurationSection("memories");
        if (section == null) return;

        var npcManager = plugin.getNpcManager();
        int loaded = 0;
        for (String key : section.getKeys(false)) {
            String npcId = key.substring(key.lastIndexOf('_') + 1);
            boolean known = npcManager.getAllNPCs().values().stream().anyMatch(d -> d.getId().equals(npcId));
            if (!known) continue; // drop memories of deleted NPCs

            List<String[]> list = new ArrayList<>();
            for (Map<?, ?> m : yml.getMapList("memories." + key)) {
                Object role = m.get("role");
                Object content = m.get("content");
                if (role != null && content != null) list.add(new String[]{role.toString(), content.toString()});
            }
            if (!list.isEmpty()) {
                histories.put(key, list);
                loaded++;
            }
        }
        if (loaded > 0) plugin.getLogger().info("Loaded " + loaded + " conversation(s) from memories.yml");
    }

    /** Snapshots memory on the main thread, writes the file asynchronously. */
    public void saveMemoriesAsync() {
        if (!memoriesDirty && memoriesFile.exists()) return;
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<String, List<String[]>> e : histories.entrySet()) {
            List<Map<String, String>> copy = new ArrayList<>();
            synchronized (e.getValue()) {
                for (String[] m : e.getValue()) copy.add(Map.of("role", m[0], "content", m[1]));
            }
            yml.set("memories." + e.getKey(), copy);
        }
        memoriesDirty = false;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try { yml.save(memoriesFile); } catch (IOException ex) {
                plugin.getLogger().warning("Could not save memories.yml: " + ex.getMessage());
            }
        });
    }

    /** Blocking save used on shutdown. */
    public void saveMemoriesNow() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<String, List<String[]>> e : histories.entrySet()) {
            List<Map<String, String>> copy = new ArrayList<>();
            synchronized (e.getValue()) {
                for (String[] m : e.getValue()) copy.add(Map.of("role", m[0], "content", m[1]));
            }
            yml.set("memories." + e.getKey(), copy);
        }
        try { yml.save(memoriesFile); } catch (IOException ex) {
            plugin.getLogger().warning("Could not save memories.yml: " + ex.getMessage());
        }
    }

    private void startAutoSave() {
        // Every 5 minutes.
        Bukkit.getScheduler().runTaskTimer(plugin, this::saveMemoriesAsync, 6000L, 6000L);
    }
}
