package me.sailex.secondbrain.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import me.sailex.secondbrain.SecondBrainPlugin;
import me.sailex.secondbrain.config.ConfigManager;
import me.sailex.secondbrain.gui.GUIManager;
import me.sailex.secondbrain.npc.NPCData;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.regex.Pattern;

/**
 * Handles chat-input sessions started from GUIs/commands and routes
 * ordinary chat to nearby NPCs.
 */
public class ChatListener implements Listener {

    private static final Pattern NPC_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private final SecondBrainPlugin plugin;

    public ChatListener(SecondBrainPlugin plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        String msg = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();

        // 1) Pending chat-input session (create/rename/prompt/key/...) eats the message.
        GUIManager.InputSession session = plugin.getGuiManager().peekInput(player);
        if (session != null) {
            event.setCancelled(true);
            plugin.getGuiManager().takeInput(player);
            Bukkit.getScheduler().runTask(plugin, () -> applyInput(player, session, msg));
            return;
        }

        // 2) Normal chat -> NPCs (runs synchronously for safe Bukkit access).
        if (!player.hasPermission("secondbrain.use")) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                plugin.getChatService().handleChat(player, event.message());
            }
        });
    }

    private void applyInput(Player player, GUIManager.InputSession session, String msg) {
        var cm = plugin.getConfigManager();
        var nm = plugin.getNpcManager();

        if (session.type() != GUIManager.InputType.CREATE_NPC && msg.equalsIgnoreCase("cancel")) {
            player.sendMessage(cm.msgRaw("prefix") + "\u00a77Cancelled.");
            return;
        }
        if (msg.isEmpty()) {
            player.sendMessage(cm.msgRaw("prefix") + "\u00a77Empty input - nothing changed.");
            return;
        }

        switch (session.type()) {
            case CREATE_NPC -> {
                if (!player.hasPermission("secondbrain.admin")) {
                    player.sendMessage(cm.msg("no-perm"));
                    return;
                }
                if (!NPC_NAME.matcher(msg).matches()) {
                    player.sendMessage(cm.msgRaw("prefix") + "\u00a7cNames: 1-16 chars, letters/numbers/underscore only.");
                    return;
                }
                String err = nm.createNPC(msg, player.getLocation());
                if (err != null) player.sendMessage(cm.msg(err, "name", msg));
                else player.sendMessage(cm.msg("created", "name", msg));
            }
            case RENAME_NPC -> {
                NPCData npc = nm.findById(session.npcId());
                if (npc == null) { player.sendMessage(cm.msg("not-found", "name", session.npcName())); return; }
                if (!NPC_NAME.matcher(msg).matches()) {
                    player.sendMessage(cm.msgRaw("prefix") + "\u00a7cNames: 1-16 chars, letters/numbers/underscore only.");
                    return;
                }
                if (nm.rename(npc.getName(), msg)) {
                    plugin.getChatService().clearMemory(npc.getId()); // identity changed; old context is confusing
                    player.sendMessage(cm.msg("renamed", "old", session.npcName(), "new", msg));
                } else {
                    player.sendMessage(cm.msg("already-exists", "name", msg));
                }
            }
            case SET_PROMPT -> {
                NPCData npc = nm.findById(session.npcId());
                if (npc == null) { player.sendMessage(cm.msg("not-found", "name", session.npcName())); return; }
                npc.setSystemPrompt(msg);
                nm.saveAll();
                player.sendMessage(cm.msg("prompt-set", "name", npc.getName()));
            }
            case SET_KEY -> {
                cm.setApiKey(msg);
                plugin.getLlmClient().reload();
                player.sendMessage(cm.msg("key-saved"));
            }
            case SET_URL -> {
                if (!msg.startsWith("http")) {
                    player.sendMessage(cm.msgRaw("prefix") + "\u00a7cURL must start with http(s).");
                    return;
                }
                cm.setApiUrl(msg);
                plugin.getLlmClient().reload();
                player.sendMessage(cm.msg("url-saved", "url", msg));
            }
            case SET_MODEL -> {
                cm.setApiModel(msg);
                player.sendMessage(cm.msg("model-saved", "model", msg));
            }
        }
    }
}
