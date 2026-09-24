package me.sailex.secondbrain.npc;

import me.sailex.secondbrain.SecondBrainPlugin;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles hostile NPCs chasing players and basic melee combat.
 * Zero dependencies, uses only Bukkit AI pathfinder overrides + melee hits.
 *
 * Because NPCs normally have AI set to false, hostile NPCs need AI turned ON
 * and a target set. The anchor task still clamps them to their saved location
 * if they wander further than their leash radius.
 */
public class NPCCombat {

    /** How far an NPC will chase a target before being teleported home. */
    private static final double LEASH_RADIUS = 24.0;
    /** Attack cooldown ticks per hit (sword speed ≈ 1.6s). */
    private static final int ATTACK_COOLDOWN_TICKS = 20;
    /** Attack range in blocks (melee). */
    private static final double ATTACK_RANGE = 2.2;
    /** Base melee damage. */
    private static final double BASE_DAMAGE = 6.0;

    private final SecondBrainPlugin plugin;
    private final Map<UUID, Integer> lastAttack = new ConcurrentHashMap<>();

    public NPCCombat(SecondBrainPlugin plugin) {
        this.plugin = plugin;
        startCombatTask();
    }

    /** Called by NPCManager.spawnEntity() to configure hostile NPC AI. */
    public void configure(NPCData data, Entity e) {
        if (!(e instanceof Mob mob)) return;
        if (data.isHostile()) {
            mob.setAI(true);
            mob.setAware(true);
            // Disable default ambient sounds so they don't make random noise.
            mob.setSilent(true);
            // Give them reasonable attack speed/damage attributes.
            if (mob.getAttribute(Attribute.ATTACK_DAMAGE) != null) {
                mob.getAttribute(Attribute.ATTACK_DAMAGE).setBaseValue(BASE_DAMAGE);
            }
            if (mob.getAttribute(Attribute.FOLLOW_RANGE) != null) {
                mob.getAttribute(Attribute.FOLLOW_RANGE).setBaseValue(LEASH_RADIUS);
            }
        } else {
            mob.setAI(false);
            mob.setAware(false);
        }
    }

    private void startCombatTask() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (NPCData data : plugin.getNpcManager().getAllNPCs().values()) {
                if (!data.isHostile()) continue;
                Entity e = data.getEntityUuid() == null ? null : Bukkit.getEntity(data.getEntityUuid());
                if (!(e instanceof Mob mob) || !mob.isValid()) continue;
                if (data.getLocation() == null || data.getLocation().getWorld() == null) continue;
                if (!data.getLocation().getWorld().isChunkLoaded(
                        data.getLocation().getBlockX() >> 4, data.getLocation().getBlockZ() >> 4)) continue;

                Location home = data.getLocation();
                Player target = findNearestPlayer(mob, home);
                if (target == null) {
                    // Wander back to home.
                    if (mob.getLocation().distanceSquared(home) > 2.0 * 2.0) {
                        mob.getPathfinder().moveTo(home);
                    } else {
                        mob.getPathfinder().stopPathfinding();
                    }
                    return;
                }
                // If too far from home, reset target.
                if (mob.getLocation().distanceSquared(home) > LEASH_RADIUS * LEASH_RADIUS) {
                    mob.teleport(home);
                    return;
                }
                mob.setTarget(target);
                // Face the target and try a melee hit if in range.
                face(mob, target);
                if (mob.getLocation().distanceSquared(target.getLocation()) <= ATTACK_RANGE * ATTACK_RANGE) {
                    int now = Bukkit.getCurrentTick();
                    int last = lastAttack.getOrDefault(data.getEntityUuid(), 0);
                    if (now - last >= ATTACK_COOLDOWN_TICKS) {
                        lastAttack.put(data.getEntityUuid(), now);
                        double dmg = BASE_DAMAGE;
                        // Add sharpness bonus if the NPC holds an enchanted sword (from equipment).
                        var held = mob.getEquipment() == null ? null : mob.getEquipment().getItemInMainHand();
                        if (held != null && held.containsEnchantment(Enchantment.SHARPNESS)) {
                            dmg += 1.25 * held.getEnchantmentLevel(Enchantment.SHARPNESS);
                        }
                        target.damage(dmg, mob);
                        // Knockback
                        Vector kb = target.getLocation().toVector().subtract(mob.getLocation().toVector())
                                .normalize().multiply(0.4).setY(0.35);
                        target.setVelocity(target.getVelocity().add(kb));
                    }
                }
            }
        }, 20L, 5L);
    }

    private Player findNearestPlayer(Mob mob, Location home) {
        Player best = null;
        double bestD = LEASH_RADIUS * LEASH_RADIUS;
        for (Player p : mob.getWorld().getPlayers()) {
            if (!p.isOnline() || p.isDead() || p.isInvulnerable()) continue;
            // Don't attack creative/spectator players.
            if (p.getGameMode() == org.bukkit.GameMode.CREATIVE
                    || p.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
            double d1 = p.getLocation().distanceSquared(mob.getLocation());
            double d2 = p.getLocation().distanceSquared(home);
            if (d1 <= bestD && d2 <= LEASH_RADIUS * LEASH_RADIUS) {
                bestD = d1; best = p;
            }
        }
        return best;
    }

    private void face(LivingEntity from, LivingEntity to) {
        Vector dir = to.getEyeLocation().toVector().subtract(from.getLocation().toVector());
        if (dir.lengthSquared() > 0.001) {
            Location l = from.getLocation();
            l.setDirection(dir);
            from.setRotation(l.getYaw(), Math.max(-20, Math.min(20, l.getPitch())));
        }
    }
}
