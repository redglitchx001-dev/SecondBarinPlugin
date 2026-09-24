package me.sailex.secondbrain.listener;

import me.sailex.secondbrain.SecondBrainPlugin;
import me.sailex.secondbrain.npc.NPCData;
import me.sailex.secondbrain.npc.NPCManager;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.*;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;

import java.util.UUID;

/**
 * Protects NPC entities from damage, death, being pushed away, and ensures
 * they respawn when their chunk loads (fixes silent chunk-forceload issue
 * in the head-turn task).
 */
public class NPCProtectionListener implements Listener {

    private final SecondBrainPlugin plugin;

    public NPCProtectionListener(SecondBrainPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------
    //  Cancel ALL damage dealt to NPCs
    // ------------------------------------------------------------
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (isNPC(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent e) {
        if (isNPC(e.getEntity())) e.setCancelled(true);
        if (isNPC(e.getDamager())) e.setCancelled(true); // don't let NPCs damage anything either
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTarget(EntityTargetEvent e) {
        if (isNPC(e.getEntity()) || isNPC(e.getTarget())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCombust(EntityCombustEvent e) {
        if (isNPC(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDismount(EntityDismountEvent e) {
        if (isNPC(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (isNPC(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(EntityDropItemEvent e) {
        if (isNPC(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPortal(EntityPortalEvent e) {
        if (isNPC(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTransform(EntityTransformEvent e) {
        if (isNPC(e.getEntity())) e.setCancelled(true); // e.g. zombie drowned conversion
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreed(EntityBreedEvent e) {
        if (isNPC(e.getMother()) || isNPC(e.getFather())) e.setCancelled(true);
    }

    // If an NPC somehow still dies, respawn it immediately instead of leaving it missing.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent e) {
        if (!isNPC(e.getEntity())) return;
        e.getDrops().clear();
        e.setDroppedExp(0);
        NPCData data = plugin.getNpcManager().findByEntity(e.getEntity().getUniqueId());
        if (data != null) {
            Bukkit.getScheduler().runTask(plugin, () -> plugin.getNpcManager().spawnEntity(data));
        }
    }

    // ------------------------------------------------------------
    //  Chunk-load respawn (no more silent force-loading)
    // ------------------------------------------------------------
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        respawnInChunk(e.getChunk());
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        // Paper 1.19+ event, fires a tick after chunk load with entities fully loaded.
        for (Entity ent : e.getEntities()) {
            if (ent.getScoreboardTags().contains(NPCManager.ENTITY_TAG)) {
                // Already exists in the world — mark it protected (just tag the NPCData accordingly by
                // not spawning a dupe). Nothing to do except anchor check; that runs in the main tick.
                return;
            }
        }
        respawnInChunk(e.getChunk());
    }

    private void respawnInChunk(Chunk chunk) {
        var nm = plugin.getNpcManager();
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (NPCData data : nm.getAllNPCs().values()) {
                Location loc = data.getLocation();
                if (loc == null || loc.getWorld() == null) continue;
                if (!loc.getWorld().equals(chunk.getWorld())) continue;
                int cx = loc.getBlockX() >> 4;
                int cz = loc.getBlockZ() >> 4;
                if (cx != chunk.getX() || cz != chunk.getZ()) continue;
                if (data.getEntityUuid() == null || Bukkit.getEntity(data.getEntityUuid()) == null
                        || !Bukkit.getEntity(data.getEntityUuid()).isValid()) {
                    nm.spawnEntity(data);
                }
            }
        });
    }

    // ------------------------------------------------------------
    //  Anchor task (runs on the main thread, only checks LOADED chunks)
    // ------------------------------------------------------------
    public void startAnchorTask() {
        // Every second (20 ticks):
        //  - Ensure NPC is at its saved location (anti-push by water/explosions/entities)
        //  - Re-apply skin helmet if it somehow got cleared
        //  - Re-apply no-AI, silence, invulnerable (in case another plugin reset them)
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            var nm = plugin.getNpcManager();
            for (NPCData data : nm.getAllNPCs().values()) {
                UUID uid = data.getEntityUuid();
                if (uid == null) continue;
                Entity e = Bukkit.getEntity(uid);
                if (e == null || !e.isValid()) {
                    // Only respawn if chunk is loaded (don't force-load!).
                    Location loc = data.getLocation();
                    if (loc != null && loc.getWorld() != null
                            && loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
                        nm.spawnEntity(data);
                    }
                    continue;
                }

                Location saved = data.getLocation();
                Location cur = e.getLocation();
                if (saved != null && saved.getWorld() != null && saved.getWorld().equals(cur.getWorld())) {
                    double dx = cur.getX() - saved.getX();
                    double dz = cur.getZ() - saved.getZ();
                    double dy = cur.getY() - saved.getY();
                    if (dx*dx + dz*dz + dy*dy > 0.09) { // drifted more than ~0.3 blocks
                        e.teleport(saved);
                    }
                }

                if (e instanceof LivingEntity le) {
                    if (le.hasAI()) le.setAI(false);
                    if (!le.isSilent()) le.setSilent(true);
                    if (le.isCollidable()) le.setCollidable(false);
                    // Periodically re-apply skin helmet in case something knocked it off.
                    nm.applySkin(data);
                }
                if (!e.isInvulnerable()) e.setInvulnerable(true);
                if (!e.isPersistent()) e.setPersistent(true);
            }
        }, 20L, 20L);
    }

    // ------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------
    private boolean isNPC(Entity e) {
        return e != null && e.getScoreboardTags().contains(NPCManager.ENTITY_TAG);
    }
}
