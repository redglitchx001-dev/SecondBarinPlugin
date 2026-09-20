package me.sailex.secondbrain.npc;

import me.sailex.secondbrain.SecondBrainPlugin;
import me.sailex.secondbrain.config.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Creates, persists and respawns all NPCs.
 * NPCs are plain Bukkit entities (no NMS, no dependencies) tagged with
 * "secondbrain_npc" so we can find and clean them up again.
 */
public class NPCManager {

    public static final String ENTITY_TAG = "secondbrain_npc";
    /** Entity types players may pick for their NPCs. */
    public static final EntityType[] ALLOWED_TYPES = {
            EntityType.VILLAGER, EntityType.ZOMBIE, EntityType.SKELETON,
            EntityType.WITCH, EntityType.PILLAGER, EntityType.ARMOR_STAND
    };

    private final SecondBrainPlugin plugin;
    private final Map<String, NPCData> npcs = new ConcurrentHashMap<>(); // key: lower-case name
    private final File dataFile;
    private FileConfiguration dataConfig;

    public NPCManager(SecondBrainPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "npcs.yml");
        loadAll();
        startLookTask();
        // Clean up stray tagged entities left over from crashes, once worlds are ready.
        Bukkit.getScheduler().runTask(plugin, this::removeOrphanEntities);
    }

    // ============================================================
    //  CRUD
    // ============================================================

    /** @return null on success, otherwise the error message key. */
    public String createNPC(String name, Location loc) {
        if (npcs.containsKey(name.toLowerCase(Locale.ROOT))) return "already-exists";

        String id = UUID.randomUUID().toString().substring(0, 8);
        ConfigManager cm = plugin.getConfigManager();
        String prompt = cm.getDefaultPrompt().replace("{name}", name);

        NPCData data = new NPCData(id, name, loc.clone(), prompt);
        data.setEntityType(matchType(cm.getDefaultEntityType()));
        data.setProfession(ConfigManager.parseProfession(cm.getDefaultProfession()));
        if (cm.isBaby()) data.setBaby(true);

        npcs.put(name.toLowerCase(Locale.ROOT), data);
        spawnEntity(data);
        saveAll();
        return null;
    }

    public boolean removeByName(String name) {
        NPCData data = npcs.remove(name.toLowerCase(Locale.ROOT));
        if (data == null) return false;

        if (data.getEntityUuid() != null) {
            Entity e = Bukkit.getEntity(data.getEntityUuid());
            if (e != null) e.remove();
        }
        dataConfig.set("npcs." + data.getId(), null);
        persist();
        return true;
    }

    public int removeAll() {
        int count = 0;
        for (String key : List.copyOf(npcs.keySet())) {
            if (removeByName(key)) count++;
        }
        return count;
    }

    public boolean rename(String oldName, String newName) {
        NPCData data = npcs.remove(oldName.toLowerCase(Locale.ROOT));
        if (data == null) return false;
        if (npcs.containsKey(newName.toLowerCase(Locale.ROOT))) {
            npcs.put(oldName.toLowerCase(Locale.ROOT), data); // restore
            return false;
        }
        data.setName(newName);
        npcs.put(newName.toLowerCase(Locale.ROOT), data);
        applyVisuals(data);
        saveAll();
        return true;
    }

    public boolean move(String name, Location to) {
        NPCData data = findByName(name);
        if (data == null) return false;
        data.setLocation(to.clone());
        if (data.getEntityUuid() != null) {
            Entity e = Bukkit.getEntity(data.getEntityUuid());
            if (e != null) e.teleport(to);
        }
        saveAll();
        return true;
    }

    public NPCData findByName(String name) {
        return name == null ? null : npcs.get(name.toLowerCase(Locale.ROOT));
    }

    public NPCData findById(String id) {
        if (id == null) return null;
        for (NPCData d : npcs.values()) if (d.getId().equals(id)) return d;
        return null;
    }

    public NPCData findByEntity(UUID entityUuid) {
        if (entityUuid == null) return null;
        for (NPCData d : npcs.values()) if (entityUuid.equals(d.getEntityUuid())) return d;
        return null;
    }

    public Map<String, NPCData> getAllNPCs() { return npcs; }

    public List<NPCData> getNearby(Location loc, double radius) {
        List<NPCData> out = new ArrayList<>();
        for (NPCData d : npcs.values()) {
            Location nl = d.getLocation();
            if (nl.getWorld() == null || !nl.getWorld().equals(loc.getWorld())) continue;
            if (nl.distanceSquared(loc) <= radius * radius) out.add(d);
        }
        out.sort(Comparator.comparingDouble(d -> d.getLocation().distanceSquared(loc)));
        return out;
    }

    // ============================================================
    //  Effective settings (per-NPC override -> global default)
    // ============================================================

    public boolean isChatEnabled(NPCData d)   { return d.getChatEnabledRaw()   != null ? d.getChatEnabledRaw()   : plugin.getConfigManager().isChatEnabled(); }
    public boolean isNameOnly(NPCData d)      { return d.getNameOnlyRaw()      != null ? d.getNameOnlyRaw()      : plugin.getConfigManager().isRespondOnlyToName(); }
    public boolean isLookAtPlayers(NPCData d) { return d.getLookAtPlayersRaw() != null ? d.getLookAtPlayersRaw() : plugin.getConfigManager().isLookAtPlayers(); }
    public boolean isShowName(NPCData d)      { return d.getShowNameRaw()      != null ? d.getShowNameRaw()      : plugin.getConfigManager().isShowName(); }
    public boolean isGlow(NPCData d)          { return d.getGlowRaw()          != null ? d.getGlowRaw()          : plugin.getConfigManager().isGlow(); }
    public double  getChatRadius(NPCData d)   { return d.getChatRadiusRaw()    != null ? d.getChatRadiusRaw()    : plugin.getConfigManager().getChatRadius(); }

    // ============================================================
    //  Entity spawning / visuals
    // ============================================================

    public void spawnEntity(NPCData data) {
        Location loc = data.getLocation();
        if (loc == null || loc.getWorld() == null) return;

        if (data.getEntityUuid() != null) {
            Entity old = Bukkit.getEntity(data.getEntityUuid());
            if (old != null) old.remove();
        }

        Entity e = loc.getWorld().spawnEntity(loc, data.getEntityType());
        if (e instanceof LivingEntity le) {
            le.setAI(false);
            le.setSilent(true);
            le.setCollidable(false);
        }
        e.setInvulnerable(true);
        e.setPersistent(true);
        e.setCustomName("\u00a7e\u00a7l" + data.getName());
        e.addScoreboardTag(ENTITY_TAG);

        if (e instanceof Villager v && data.getProfession() != null) {
            v.setProfession(data.getProfession());
        }
        Boolean baby = data.getBabyRaw();
        if (baby != null && e instanceof Ageable a) {
            if (baby) a.setBaby(); else a.setAdult();
        }

        data.setEntityUuid(e.getUniqueId());
        applyVisuals(data);
    }

    /** Applies name visibility + glow without respawning. */
    public void applyVisuals(NPCData data) {
        if (data.getEntityUuid() == null) return;
        Entity e = Bukkit.getEntity(data.getEntityUuid());
        if (e == null) return;
        e.setCustomName("\u00a7e\u00a7l" + data.getName());
        e.setCustomNameVisible(isShowName(data));
        e.setGlowing(isGlow(data));
        if (e instanceof ArmorStand as) {
            as.setGravity(true);
        }
    }

    /** Re-render every NPC (used after /sb reload when global defaults change). */
    public void refreshAllVisuals() {
        for (NPCData d : npcs.values()) applyVisuals(d);
    }

    private static EntityType matchType(String s) {
        if (s != null) {
            try {
                EntityType t = EntityType.valueOf(s.toUpperCase(Locale.ROOT));
                for (EntityType allowed : ALLOWED_TYPES) if (allowed == t) return t;
            } catch (IllegalArgumentException ignored) {}
        }
        return EntityType.VILLAGER;
    }

    // ============================================================
    //  Persistence
    // ============================================================

    private void loadAll() {
        if (!dataFile.exists()) {
            try { dataFile.createNewFile(); } catch (IOException ignored) {}
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        if (!dataConfig.contains("npcs")) return;

        var section = dataConfig.getConfigurationSection("npcs");
        if (section == null) return;

        for (String id : section.getKeys(false)) {
            String path = "npcs." + id + ".";
            String name = dataConfig.getString(path + "name");
            Location loc = dataConfig.getLocation(path + "location");
            String prompt = dataConfig.getString(path + "prompt");
            if (name == null || loc == null) continue;

            NPCData data = new NPCData(id, name, loc, prompt);

            // Optional per-NPC settings (v5+). Missing = inherit global.
            if (dataConfig.contains(path + "settings.chat-enabled"))
                data.setChatEnabled(dataConfig.getBoolean(path + "settings.chat-enabled"));
            if (dataConfig.contains(path + "settings.name-only"))
                data.setNameOnly(dataConfig.getBoolean(path + "settings.name-only"));
            if (dataConfig.contains(path + "settings.look-at-players"))
                data.setLookAtPlayers(dataConfig.getBoolean(path + "settings.look-at-players"));
            if (dataConfig.contains(path + "settings.show-name"))
                data.setShowName(dataConfig.getBoolean(path + "settings.show-name"));
            if (dataConfig.contains(path + "settings.glow"))
                data.setGlow(dataConfig.getBoolean(path + "settings.glow"));
            if (dataConfig.contains(path + "settings.baby"))
                data.setBaby(dataConfig.getBoolean(path + "settings.baby"));
            if (dataConfig.contains(path + "settings.chat-radius"))
                data.setChatRadius(dataConfig.getDouble(path + "settings.chat-radius"));
            String type = dataConfig.getString(path + "settings.entity-type");
            if (type != null) data.setEntityType(matchType(type));
            data.setProfession(ConfigManager.parseProfession(dataConfig.getString(path + "settings.profession")));

            npcs.put(name.toLowerCase(Locale.ROOT), data);
            spawnEntity(data);
        }
        plugin.getLogger().info("Loaded " + npcs.size() + " NPC(s) from npcs.yml");
    }

    public void saveAll() {
        for (NPCData data : npcs.values()) {
            String path = "npcs." + data.getId() + ".";
            dataConfig.set(path + "name", data.getName());
            dataConfig.set(path + "location", data.getLocation());
            dataConfig.set(path + "prompt", data.getSystemPrompt());

            String s = path + "settings.";
            setOrNull(s + "chat-enabled", data.getChatEnabledRaw());
            setOrNull(s + "name-only", data.getNameOnlyRaw());
            setOrNull(s + "look-at-players", data.getLookAtPlayersRaw());
            setOrNull(s + "show-name", data.getShowNameRaw());
            setOrNull(s + "glow", data.getGlowRaw());
            setOrNull(s + "baby", data.getBabyRaw());
            setOrNull(s + "chat-radius", data.getChatRadiusRaw());
            dataConfig.set(s + "entity-type", data.getEntityType().name());
            dataConfig.set(s + "profession", data.getProfession() == null ? null : data.getProfession().name());
        }
        persist();
    }

    private void setOrNull(String path, Object value) {
        dataConfig.set(path, value); // null removes the key
    }

    private void persist() {
        try { dataConfig.save(dataFile); } catch (IOException e) {
            plugin.getLogger().warning("Could not save npcs.yml: " + e.getMessage());
        }
    }

    /** Removes tagged entities that belong to no known NPC (crash leftovers). */
    public int removeOrphanEntities() {
        java.util.Set<UUID> known = new java.util.HashSet<>();
        for (NPCData d : npcs.values()) if (d.getEntityUuid() != null) known.add(d.getEntityUuid());

        int removed = 0;
        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntities()) {
                if (e.getScoreboardTags().contains(ENTITY_TAG) && !known.contains(e.getUniqueId())) {
                    e.remove();
                    removed++;
                }
            }
        }
        if (removed > 0) plugin.getLogger().info("Cleaned up " + removed + " orphaned NPC entit" + (removed == 1 ? "y" : "ies") + ".");
        return removed;
    }

    // ============================================================
    //  Head-turning task
    // ============================================================

    private void startLookTask() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (NPCData data : npcs.values()) {
                if (data.getLocation() == null || data.getLocation().getWorld() == null) continue;

                if (data.getEntityUuid() == null) { spawnEntity(data); continue; }
                Entity e = Bukkit.getEntity(data.getEntityUuid());
                if (e == null || !e.isValid()) { spawnEntity(data); continue; }

                if (!isLookAtPlayers(data)) continue;

                Player nearest = null;
                double minDist = 16.0 * 16.0; // only track within 16 blocks
                for (Player p : e.getWorld().getPlayers()) {
                    double d = p.getLocation().distanceSquared(e.getLocation());
                    if (d < minDist) { minDist = d; nearest = p; }
                }
                if (nearest != null) {
                    Vector dir = nearest.getEyeLocation().toVector().subtract(e.getLocation().toVector());
                    if (dir.lengthSquared() > 0.001) {
                        Location l = e.getLocation();
                        l.setDirection(dir);
                        e.setRotation(l.getYaw(), Math.max(-50, Math.min(50, l.getPitch())));
                    }
                }
            }
        }, 10L, 5L);
    }
}
