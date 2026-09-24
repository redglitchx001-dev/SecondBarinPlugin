package me.sailex.secondbrain.npc;

import me.sailex.secondbrain.SecondBrainPlugin;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Builds a short text "sight snapshot" of what an NPC can see around it.
 * Fed into the AI prompt so the NPC knows its surroundings without the player
 * having to describe them. Uses ray-tracing for line-of-sight.
 */
public final class Sight {

    private Sight() {}

    /** Max distance the NPC can "see" blocks/entities, in blocks. */
    private static final double RANGE = 12.0;
    /** Max entities/blocks reported per category to keep context small. */
    private static final int MAX_ENTITIES = 4;
    private static final int MAX_BLOCKS = 6;

    public static String snapshot(SecondBrainPlugin plugin, NPCData npc) {
        if (npc.getEntityUuid() == null) return "(no entity)";
        Entity e = Bukkit.getEntity(npc.getEntityUuid());
        if (!(e instanceof LivingEntity self)) return "(not a living entity)";
        Location loc = self.getEyeLocation();
        if (loc.getWorld() == null) return "(no world)";

        StringBuilder sb = new StringBuilder();
        sb.append("You are at ").append(loc.getBlockX()).append(",").append(loc.getBlockY())
          .append(",").append(loc.getBlockZ()).append(" facing ")
          .append(facing(self)).append(". ");

        // Ground material under feet
        Block ground = loc.clone().subtract(0, 1, 0).getBlock();
        sb.append("Ground under you: ").append(friendly(ground.getType())).append(". ");

        // Nearby players
        List<String> players = new ArrayList<>();
        for (Player p : loc.getWorld().getPlayers()) {
            if (p.isDead() || !p.getWorld().equals(loc.getWorld())) continue;
            double d = p.getLocation().distanceSquared(loc);
            if (d > RANGE * RANGE) continue;
            if (!hasLineOfSight(self, p.getEyeLocation())) continue;
            double dist = Math.sqrt(d);
            players.add(p.getName() + " (" + directionName(self, p.getLocation()) + ", " + ((int) dist) + "m)");
            if (players.size() >= MAX_ENTITIES) break;
        }
        if (!players.isEmpty()) sb.append("Players in sight: ").append(String.join(", ", players)).append(". ");
        else sb.append("No players in sight. ");

        // Blocks the NPC is looking at (simple cone scan)
        List<String> blocksSeen = new ArrayList<>();
        Location cursor = loc.clone();
        Vector dir = loc.getDirection().normalize();
        for (double r = 1; r <= RANGE && blocksSeen.size() < MAX_BLOCKS; r += 1.0) {
            Block b = cursor.clone().add(dir.clone().multiply(r)).getBlock();
            if (b.getType().isAir()) continue;
            String name = friendly(b.getType());
            String entry = name + " " + ((int) r) + "m " + directionName(self, b.getLocation().add(0.5, 0.5, 0.5));
            if (!blocksSeen.contains(entry)) blocksSeen.add(entry);
        }
        if (!blocksSeen.isEmpty()) sb.append("Looking at: ").append(String.join(", ", blocksSeen)).append(". ");

        // Nearby interactive blocks within 4 blocks (so NPC knows about tables/furnaces).
        List<String> stations = new ArrayList<>();
        for (int dx = -4; dx <= 4; dx++)
            for (int dy = -2; dy <= 2; dy++)
                for (int dz = -4; dz <= 4; dz++) {
                    Material m = loc.clone().add(dx, dy, dz).getBlock().getType();
                    String label = stationLabel(m);
                    if (label != null && !stations.contains(label)) stations.add(label);
                }
        if (!stations.isEmpty()) sb.append("Nearby stations: ").append(String.join(", ", stations)).append(". ");

        // Mention time/weather briefly
        boolean storm = loc.getWorld().hasStorm();
        boolean night = loc.getWorld().getTime() > 13000 && loc.getWorld().getTime() < 23000;
        sb.append("It is ").append(night ? "night" : "day");
        if (storm) sb.append(" and raining");
        sb.append(".");
        return sb.toString();
    }

    private static String stationLabel(Material m) {
        return switch (m) {
            case CRAFTING_TABLE -> "crafting table";
            case FURNACE, BLAST_FURNACE, SMOKER -> "furnace";
            case ENCHANTING_TABLE -> "enchanting table";
            case ANVIL, CHIPPED_ANVIL, DAMAGED_ANVIL -> "anvil";
            case CHEST, BARREL -> "chest";
            case BREWING_STAND -> "brewing stand";
            case SMITHING_TABLE -> "smithing table";
            case GRINDSTONE -> "grindstone";
            case STONECUTTER -> "stonecutter";
            case LECTERN -> "lectern";
            case LOOM -> "loom";
            case CARTOGRAPHY_TABLE -> "cartography table";
            case FLETCHING_TABLE -> "fletching table";
            default -> null;
        };
    }

        return sb.toString();
    }

    private static String facing(LivingEntity e) {
        float yaw = (e.getLocation().getYaw() + 360) % 360;
        if (yaw < 45 || yaw >= 315) return "south";
        if (yaw < 135) return "west";
        if (yaw < 225) return "north";
        return "east";
    }

    private static String directionName(LivingEntity from, Location to) {
        Vector dir = to.toVector().subtract(from.getLocation().toVector()).setY(0).normalize();
        float yaw = from.getLocation().getYaw();
        // Convert direction-of-to into NPC-local forward/right/left/behind.
        Vector forward = from.getLocation().getDirection().setY(0).normalize();
        Vector right = new Vector(-forward.getZ(), 0, forward.getX());
        double f = dir.dot(forward);
        double r = dir.dot(right);
        String fwd = f > 0.5 ? "in front" : (f < -0.5 ? "behind" : "");
        String sd = r > 0.5 ? "to your right" : (r < -0.5 ? "to your left" : "");
        if (fwd.isEmpty() && sd.isEmpty()) return "nearby";
        return (fwd + " " + sd).trim();
    }

    private static boolean hasLineOfSight(LivingEntity from, Location to) {
        // LivingEntity#hasLineOfSight(Entity) exists but we want to test line of sight to a Location,
        // so we approximate by checking the block at the target against air-passthrough.
        org.bukkit.util.Vector dir = to.toVector().subtract(from.getEyeLocation().toVector());
        double dist = dir.length();
        if (dist < 0.01) return true;
        RayTraceResult r = from.getWorld().rayTraceBlocks(from.getEyeLocation(), dir.normalize(), dist,
                FluidCollisionMode.NEVER, false); // ignore passable blocks like grass/tallgrass
        return r == null || r.getHitBlock() == null;
    }

    private static String friendly(Material m) {
        return m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
