package me.sailex.secondbrain.npc;

import me.sailex.secondbrain.SecondBrainPlugin;
import me.sailex.secondbrain.config.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
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
        if (npcs.containsKey(plainKey(name))) return "already-exists";
        if (!me.sailex.secondbrain.util.Text.validNpcName(name)) return "invalid-name";

        String id = UUID.randomUUID().toString().substring(0, 8);
        ConfigManager cm = plugin.getConfigManager();
        String plainName = me.sailex.secondbrain.util.Text.stripColors(name);
        String prompt = cm.getDefaultPrompt().replace("{name}", plainName);

        NPCData data = new NPCData(id, name, loc.clone(), prompt);
        data.setEntityType(matchType(cm.getDefaultEntityType()));
        data.setProfession(ConfigManager.parseProfession(cm.getDefaultProfession()));
        if (cm.isBaby()) data.setBaby(true);

        npcs.put(plainKey(name), data);
        spawnEntity(data);
        saveAll();
        return null;
    }

    /** Duplicates an NPC at a new location with a new name (copies settings + skin + prompt). */
    public String cloneNPC(NPCData source, String newName, Location loc) {
        if (npcs.containsKey(plainKey(newName))) return "already-exists";
        if (!me.sailex.secondbrain.util.Text.validNpcName(newName)) return "invalid-name";

        String id = UUID.randomUUID().toString().substring(0, 8);
        String plainNew = me.sailex.secondbrain.util.Text.stripColors(newName);
        String prompt = source.getSystemPrompt()
                .replace(me.sailex.secondbrain.util.Text.stripColors(source.getName()), plainNew);
        NPCData copy = new NPCData(id, newName, loc.clone(), prompt);
        copy.setEntityType(source.getEntityType());
        copy.setProfession(source.getProfession());
        copy.setChatEnabled(source.getChatEnabledRaw());
        copy.setNameOnly(source.getNameOnlyRaw());
        copy.setLookAtPlayers(source.getLookAtPlayersRaw());
        copy.setShowName(source.getShowNameRaw());
        copy.setGlow(source.getGlowRaw());
        copy.setBaby(source.getBabyRaw());
        copy.setChatRadius(source.getChatRadiusRaw());
        copy.setSkinName(source.getSkinName());
        copy.setCanExecuteCommands(source.getCanExecuteCommandsRaw());
        copy.setConsoleExecutor(source.getConsoleExecutorRaw());
        copy.setHostile(source.getHostileRaw());
        copy.setMainHand(source.getMainHand());

        npcs.put(plainKey(newName), copy);
        spawnEntity(copy);
        applyEquipment(copy);
        applySkin(copy);
        saveAll();
        return null;
    }

    /** Returns the clone NPCData on success, or null on failure (sets an "already-exists" message sent by caller). */
    public NPCData cloneNPCData(NPCData source, String newName, Location loc) {
        String err = cloneNPC(source, newName, loc);
        if (err != null) return null;
        return findByName(newName);
    }

    private static String plainKey(String name) {
        return me.sailex.secondbrain.util.Text.stripColors(name).toLowerCase(Locale.ROOT);
    }

    public boolean removeByName(String name) {
        NPCData data = npcs.remove(plainKey(name));
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
        String oldKey = plainKey(oldName);
        NPCData data = npcs.remove(oldKey);
        if (data == null) return false;
        if (!me.sailex.secondbrain.util.Text.validNpcName(newName)) {
            npcs.put(oldKey, data);
            return false;
        }
        if (npcs.containsKey(plainKey(newName))) {
            npcs.put(oldKey, data); // restore
            return false;
        }
        data.setName(newName);
        npcs.put(plainKey(newName), data);
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
        return name == null ? null : npcs.get(plainKey(name));
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
    public boolean canExecuteCommands(NPCData d) { return d.canExecuteCommands(); }
    public boolean isHostile(NPCData d)         { return d.isHostile(); }

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
            le.setSilent(true);
            if (le instanceof Mob m) {
                m.setAI(false);
                m.setCollidable(false);
            }
        }
        e.setInvulnerable(true);
        e.setPersistent(true);
        e.addScoreboardTag(ENTITY_TAG);
        // Name is set by applyVisuals() below (handles & color codes).

        if (e instanceof Villager v && data.getProfession() != null) {
            v.setProfession(data.getProfession());
        }
        if (e instanceof ArmorStand as) {
            as.setGravity(true);
            as.setVisible(true);
            as.setArms(true);           // so skin head + hand items look right
            as.setBasePlate(true);
            as.setMarker(false);
            as.setSmall(Boolean.TRUE.equals(data.getBabyRaw()));
        }
        Boolean baby = data.getBabyRaw();
        if (baby != null && e instanceof Ageable a) {
            if (baby) a.setBaby(); else a.setAdult();
        }

        // Disable default equipment drops / pickup for living entities so our skin helmet stays.
        if (e instanceof Mob m) {
            m.setCanPickupItems(false);
        }

        data.setEntityUuid(e.getUniqueId());
        applyVisuals(data);
        applyEquipment(data);
        applySkin(data);
        // Hostile NPCs get their AI + pathfinding configured in NPCCombat.
        plugin.getNpcCombat().configure(data, e);
    }

    /** Re-render every NPC (used after /sb reload when global defaults change). */
    public void refreshAllVisuals() {
        for (NPCData d : npcs.values()) {
            applyVisuals(d);
            applyEquipment(d);
            applySkin(d);
        }
    }

    /** Applies name visibility + glow without respawning. */
    public void applyVisuals(NPCData data) {
        if (data.getEntityUuid() == null) return;
        Entity e = Bukkit.getEntity(data.getEntityUuid());
        if (e == null) return;
        String colored;
        if (data.getName().contains("&") || data.getName().contains("\u00a7")) {
            colored = me.sailex.secondbrain.util.Text.color(data.getName());
        } else {
            colored = "\u00a7e\u00a7l" + data.getName();
        }
        e.setCustomName(colored);
        e.setCustomNameVisible(isShowName(data));
        e.setGlowing(isGlow(data));
        if (e instanceof ArmorStand as) as.setGravity(true);
    }

    /** Equips the NPC's main-hand item from data.getMainHand() (Material name, e.g. DIAMOND_SWORD). */
    public void applyEquipment(NPCData data) {
        if (data.getEntityUuid() == null) return;
        Entity e = Bukkit.getEntity(data.getEntityUuid());
        if (!(e instanceof LivingEntity living)) return;
        EntityEquipment eq = living.getEquipment();
        if (eq == null) return;
        eq.setItemInMainHandDropChance(0f);
        if (data.getMainHand() == null || data.getMainHand().isBlank()) {
            eq.setItemInMainHand(null, true);
            return;
        }
        Material m = Material.matchMaterial(data.getMainHand().toUpperCase(Locale.ROOT));
        if (m == null) { eq.setItemInMainHand(null, true); return; }
        eq.setItemInMainHand(new ItemStack(m), true);
    }

    /** Equips the NPC's helmet slot with the skin-skull if configured. */
    public void applySkin(NPCData data) {
        if (data.getEntityUuid() == null) return;
        Entity e = Bukkit.getEntity(data.getEntityUuid());
        if (!(e instanceof LivingEntity living)) return;

        EntityEquipment eq = living.getEquipment();
        if (eq == null) return;

        if (!data.hasSkin()) {
            // Clear helmet only if we previously set one (i.e. empty slot check is skipped; we always clean up).
            eq.setHelmet(null, true);
            eq.setHelmetDropChance(0f);
            return;
        }

        // If we already have a cached skull, apply it now. Otherwise put placeholder and fetch async.
        plugin.getSkinManager().getSkull(data.getSkinName(), () -> {
            // Re-apply on main thread once fetch completes.
            applySkin(data);
        });
        ItemStack skull = plugin.getSkinManager().getCached(data.getSkinName());
        if (skull == null) {
            // Placeholder will be applied until async fetch completes; the callback above re-applies.
            return;
        }
        eq.setHelmet(skull, true);
        eq.setHelmetDropChance(0f);
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
            data.setSkinName(dataConfig.getString(path + "settings.skin"));
            if (dataConfig.contains(path + "settings.commands"))
                data.setCanExecuteCommands(dataConfig.getBoolean(path + "settings.commands"));
            if (dataConfig.contains(path + "settings.console-executor"))
                data.setConsoleExecutor(dataConfig.getBoolean(path + "settings.console-executor"));
            if (dataConfig.contains(path + "settings.hostile"))
                data.setHostile(dataConfig.getBoolean(path + "settings.hostile"));
            data.setMainHand(dataConfig.getString(path + "settings.main-hand"));

            npcs.put(plainKey(name), data);
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
            dataConfig.set(s + "skin", data.getSkinName());
            setOrNull(s + "commands", data.getCanExecuteCommandsRaw());
            setOrNull(s + "console-executor", data.getConsoleExecutorRaw());
            setOrNull(s + "hostile", data.getHostileRaw());
            dataConfig.set(s + "main-hand", data.getMainHand());
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
                Location loc = data.getLocation();
                if (loc == null || loc.getWorld() == null) continue;

                // Only tick NPCs in loaded chunks — never force-load chunks!
                if (!loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) continue;

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
