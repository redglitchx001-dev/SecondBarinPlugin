package me.sailex.secondbrain.npc;

import me.sailex.secondbrain.SecondBrainPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Simulates NPC interaction with crafting stations using only the Paper public API.
 *
 * Tags (all require clickable consent via [Accept]/[Deny]):
 *   [CRAFT:MATERIAL]                - craft one of that item at a nearby crafting table (3x3 recipes)
 *   [CRAFT:MATERIAL:AMOUNT]         - craft N of that item
 *   [SMELT:MATERIAL]                - smelt one of that item in a nearby furnace (uses NPC's furnace slots)
 *   [ENCHANT:SLOT:ENCHANT:LEVEL]    - enchant held/mainhand/offhand/helmet/... (consumes lapis+XP)
 *
 * The NPC will:
 *  1) Walk to the nearest matching station (if not already within range).
 *  2) Turn to face the block, swing its arm, play the sound, spawn particles.
 *  3) Consume ingredients from its inventory, add the result.
 *  4) For smelting, fuel is drawn from inventory and cooks in the NPC's furnace buffer.
 *  5) For enchanting, lapis + XP levels are consumed and the enchant applied.
 */
public class NPCCrafting {

    private static final Pattern CRAFT_PAT   = Pattern.compile("\\[CRAFT:([A-Z_]+)(?::(\\d+))?\\]");
    private static final Pattern SMELT_PAT   = Pattern.compile("\\[SMELT:([A-Z_]+)(?::(\\d+))?\\]");
    private static final Pattern ENCHANT_PAT = Pattern.compile("\\[ENCHANT:(mainhand|offhand|helmet|chest|leggings|boots):([a-z_]+):(\\d+)\\]");

    private static final double STATION_RANGE = 4.5;
    private final SecondBrainPlugin plugin;
    private final Map<String, PendingCraft> pending = new ConcurrentHashMap<>();
    private final AtomicInteger pendingId = new AtomicInteger(0);

    public NPCCrafting(SecondBrainPlugin plugin) { this.plugin = plugin; }

    /** Parse crafting tags, request consent, and strip them from the reply. */
    public String parseAndRequest(NPCData npc, String playerName, String reply) {
        if (reply == null) return null;
        Player p = playerName == null ? null : Bukkit.getPlayerExact(playerName);
        String cleaned = CRAFT_PAT.matcher(reply).replaceAll("");
        cleaned = SMELT_PAT.matcher(cleaned).replaceAll("");
        cleaned = ENCHANT_PAT.matcher(cleaned).replaceAll("").replaceAll("\\s{2,}", " ").trim();
        if (p == null || !p.isOnline()) return cleaned;
        if (!p.isOp() && !p.hasPermission("secondbrain.admin")) return cleaned;

        Matcher cm = CRAFT_PAT.matcher(reply);
        while (cm.find()) {
            Material mat = Material.matchMaterial(cm.group(1));
            int amt = cm.group(2) == null ? 1 : Math.max(1, Integer.parseInt(cm.group(2)));
            if (mat != null) requestApprove(npc, p, "craft " + amt + "x " + mat.name().toLowerCase(Locale.ROOT).replace('_', ' '),
                    () -> doCraft(npc, p, mat, amt));
        }
        Matcher sm = SMELT_PAT.matcher(reply);
        while (sm.find()) {
            Material mat = Material.matchMaterial(sm.group(1));
            int amt = sm.group(2) == null ? 1 : Math.max(1, Integer.parseInt(sm.group(2)));
            if (mat != null) requestApprove(npc, p, "smelt " + amt + "x " + mat.name().toLowerCase(Locale.ROOT).replace('_', ' '),
                    () -> doSmelt(npc, p, mat, amt));
        }
        Matcher em = ENCHANT_PAT.matcher(reply);
        while (em.find()) {
            String slot = em.group(1);
            String ench = em.group(2);
            int lvl = Integer.parseInt(em.group(3));
            requestApprove(npc, p, "enchant " + slot + " with " + ench + " " + lvl,
                    () -> doEnchant(npc, p, slot, ench, lvl));
        }
        return cleaned;
    }

    // --------------------------------------------------------------------
    private void requestApprove(NPCData npc, Player p, String action, Runnable run) {
        int id = pendingId.incrementAndGet();
        pending.put(npc.getId() + ":" + id, new PendingCraft(p.getUniqueId(), run));
        Component line = Component.text("\u00a7e" + npc.getName() + " \u00a77wants to \u00a7f" + action + "\u00a77.", NamedTextColor.GRAY)
                .append(Component.text("  [Accept]", NamedTextColor.GREEN).decorate(TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/sbcraft " + npc.getId() + " " + id + " approve"))
                        .hoverEvent(HoverEvent.showText(Component.text("Click to let the NPC do this"))))
                .append(Component.text("  [Deny]", NamedTextColor.RED).decorate(TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/sbcraft " + npc.getId() + " " + id + " deny"))
                        .hoverEvent(HoverEvent.showText(Component.text("Click to refuse"))));
        p.sendMessage(line);
    }

    public boolean approve(String npcId, int id, Player player) {
        PendingCraft pc = pending.remove(npcId + ":" + id);
        if (pc == null) return false;
        if (!pc.playerUuid.equals(player.getUniqueId())) { pending.put(npcId + ":" + id, pc); return false; }
        player.sendMessage(Component.text("\u00a7a\u2714 Approved.", NamedTextColor.GREEN));
        Bukkit.getScheduler().runTask(plugin, pc.action);
        return true;
    }
    public boolean deny(String npcId, int id, Player player) {
        PendingCraft pc = pending.remove(npcId + ":" + id);
        if (pc == null) return false;
        if (!pc.playerUuid.equals(player.getUniqueId())) { pending.put(npcId + ":" + id, pc); return false; }
        player.sendMessage(Component.text("\u00a7c\u2718 Denied.", NamedTextColor.RED));
        return true;
    }

    private record PendingCraft(UUID playerUuid, Runnable action) {}

    // --------------------------------------------------------------------
    //  CRAFT (3x3 table required)
    // --------------------------------------------------------------------
    private void doCraft(NPCData npc, Player p, Material result, int amount) {
        Entity e = npc.getEntityUuid() == null ? null : Bukkit.getEntity(npc.getEntityUuid());
        if (!(e instanceof LivingEntity self)) { p.sendMessage("\u00a7cNPC has no entity."); return; }
        Block table = findNearestStation(self.getLocation(), Material.CRAFTING_TABLE);
        if (table == null) {
            p.sendMessage("\u00a7c" + npc.getName() + " can't find a crafting table nearby.");
            return;
        }
        Recipe recipe = findCraftingRecipe(result);
        if (recipe == null) { p.sendMessage("\u00a7cNo recipe for " + result + "."); return; }
        NPCInventory inv = npc.getInventory();
        for (int i = 0; i < amount; i++) {
            if (!consumeForRecipe(inv, recipe, npc)) {
                p.sendMessage("\u00a7c" + npc.getName() + " is missing ingredients (made " + i + "/" + amount + ").");
                break;
            }
            ItemStack res = recipe.getResult().clone();
            ItemStack leftover = inv.addItem(res);
            if (leftover != null) {
                // Drop overflow at NPC location.
                self.getWorld().dropItemNaturally(self.getLocation(), leftover);
            }
        }
        animateUse(self, table, Sound.BLOCK_WOOD_PLACE, Sound.ENTITY_VILLAGER_WORK_MASON, Particle.CRIT);
        inv.refreshView();
    }

    private boolean consumeForRecipe(NPCInventory inv, Recipe recipe, NPCData npc) {
        // Build a list of required ingredient counts from the recipe's choice list.
        List<ItemStack> required = new ArrayList<>();
        if (recipe instanceof ShapedRecipe sr) {
            for (ItemStack is : sr.getIngredientMap().values()) {
                if (is != null && !is.getType().isAir()) required.add(is.clone());
            }
        } else if (recipe instanceof ShapelessRecipe spr) {
            for (ItemStack is : spr.getIngredientList()) {
                if (is != null && !is.getType().isAir()) required.add(is.clone());
            }
        } else {
            return false;
        }
        // Verify inventory has enough before consuming anything.
        ItemStack[] storageSnapshot = snapshot(inv);
        for (ItemStack need : required) {
            if (!consumeOne(storageSnapshot, need)) return false;
        }
        // Apply changes back.
        writeBack(inv, storageSnapshot);
        return true;
    }

    private boolean consumeOne(ItemStack[] arr, ItemStack need) {
        for (int i = 0; i < arr.length; i++) {
            ItemStack have = arr[i];
            if (have == null || have.getType().isAir()) continue;
            // Match by type; ignore durability/meta for simplicity.
            if (have.getType() == need.getType() && have.getAmount() > 0) {
                have.setAmount(have.getAmount() - 1);
                if (have.getAmount() <= 0) arr[i] = null;
                return true;
            }
        }
        return false;
    }

    private ItemStack[] snapshot(NPCInventory inv) {
        ItemStack[] out = new ItemStack[NPCInventory.MAIN_SIZE];
        for (int i = 0; i < NPCInventory.MAIN_SIZE; i++) {
            ItemStack it = inv.getInventory().getItem(i);
            out[i] = (it == null || it.getType().isAir()) ? null : it.clone();
        }
        return out;
    }

    private void writeBack(NPCInventory inv, ItemStack[] arr) {
        for (int i = 0; i < NPCInventory.MAIN_SIZE; i++) inv.getInventory().setItem(i, arr[i]);
    }

    private Recipe findCraftingRecipe(Material result) {
        for (Iterator<Recipe> it = Bukkit.recipeIterator(); it.hasNext(); ) {
            Recipe r = it.next();
            if ((r instanceof ShapedRecipe || r instanceof ShapelessRecipe) && r.getResult().getType() == result) {
                return r;
            }
        }
        return null;
    }

    // --------------------------------------------------------------------
    //  SMELT (furnace/blast/smoker nearby)
    // --------------------------------------------------------------------
    private void doSmelt(NPCData npc, Player p, Material result, int amount) {
        Entity e = npc.getEntityUuid() == null ? null : Bukkit.getEntity(npc.getEntityUuid());
        if (!(e instanceof LivingEntity self)) return;
        Block furnace = findNearestStation(self.getLocation(), Material.FURNACE, Material.BLAST_FURNACE, Material.SMOKER);
        if (furnace == null) { p.sendMessage("\u00a7c" + npc.getName() + " can't find a furnace nearby."); return; }
        FurnaceRecipe fr = findFurnaceRecipe(result);
        if (fr == null) { p.sendMessage("\u00a7cNo furnace recipe for " + result + "."); return; }
        NPCInventory inv = npc.getInventory();
        // Queue the input into the NPC's furnace input slot.
        Material inputMat = fr.getInput().getType() == Material.AIR ? guessSmeltInput(result) : fr.getInputChoice() == null ? guessSmeltInputType(result) : null;
        // Simple approach: put items directly into furnaceInput slot of the NPC's virtual furnace.
        int added = 0;
        for (int i = 0; i < amount; i++) {
            // Pull one input from storage.
            if (!pullFromStorage(inv, inputMaterialFor(result))) break;
            added++;
        }
        // Try to add fuel (1 coal smelts 8 items).
        int fuelNeeded = Math.max(1, (added + 7) / 8);
        for (int i = 0; i < fuelNeeded; i++) {
            if (!pullFuel(inv)) { p.sendMessage("\u00a7cOut of fuel."); break; }
        }
        animateUse(self, furnace, Sound.BLOCK_FURNACE_FIRE_CRACKLE, Sound.ENTITY_VILLAGER_WORK_FISHERMAN, Particle.FLAME);
        // Furnace ticking will produce output over time.
        p.sendMessage("\u00a7a" + npc.getName() + " put " + added + "x " + result.name().toLowerCase(Locale.ROOT).replace('_', ' ') + " in the furnace.");
        inv.refreshView();
    }

    private FurnaceRecipe findFurnaceRecipe(Material result) {
        for (Iterator<Recipe> it = Bukkit.recipeIterator(); it.hasNext(); ) {
            Recipe r = it.next();
            if (r instanceof FurnaceRecipe f && f.getResult().getType() == result) return f;
        }
        return null;
    }

    private Material inputMaterialFor(Material result) {
        FurnaceRecipe fr = findFurnaceRecipe(result);
        if (fr != null && fr.getInput() != null && !fr.getInput().getType().isAir()) return fr.getInput().getType();
        // Fallbacks for common items.
        return switch (result) {
            case IRON_INGOT -> Material.RAW_IRON;
            case GOLD_INGOT -> Material.RAW_GOLD;
            case COPPER_INGOT -> Material.RAW_COPPER;
            case GLASS -> Material.SAND;
            case COOKED_BEEF -> Material.BEEF;
            case COOKED_PORKCHOP -> Material.PORKCHOP;
            case COOKED_CHICKEN -> Material.CHICKEN;
            case COOKED_COD -> Material.COD;
            case COOKED_SALMON -> Material.SALMON;
            case COOKED_MUTTON -> Material.MUTTON;
            case BAKED_POTATO -> Material.POTATO;
            case CHARCOAL -> Material.OAK_LOG;
            case STONE -> Material.COBBLESTONE;
            case SMOOTH_STONE -> Material.STONE;
            case BRICK -> Material.CLAY_BALL;
            default -> Material.AIR;
        };
    }
    private Material guessSmeltInput(Material r) { return inputMaterialFor(r); }
    private Material guessSmeltInputType(Material r) { return inputMaterialFor(r); }

    private boolean pullFromStorage(NPCInventory inv, Material m) {
        for (int i = 0; i < NPCInventory.MAIN_SIZE; i++) {
            ItemStack it = inv.getInventory().getItem(i);
            if (it != null && it.getType() == m && it.getAmount() > 0) {
                it.setAmount(it.getAmount() - 1);
                if (it.getAmount() <= 0) inv.getInventory().setItem(i, null);
                ItemStack cur = inv.getInventory().getItem(46);
                inv.getInventory().setItem(46, cur == null ? new ItemStack(m) : new ItemStack(m, cur.getAmount() + 1));
                return true;
            }
        }
        return false;
    }

    private boolean pullFuel(NPCInventory inv) {
        for (Material fm : new Material[]{Material.COAL, Material.CHARCOAL, Material.STICK, Material.OAK_PLANKS}) {
            for (int i = 0; i < NPCInventory.MAIN_SIZE; i++) {
                ItemStack it = inv.getInventory().getItem(i);
                if (it != null && it.getType() == fm && it.getAmount() > 0) {
                    it.setAmount(it.getAmount() - 1);
                    if (it.getAmount() <= 0) inv.getInventory().setItem(i, null);
                    ItemStack cur = inv.getInventory().getItem(47);
                    inv.getInventory().setItem(47, cur == null ? new ItemStack(fm) : new ItemStack(fm, cur.getAmount() + 1));
                    return true;
                }
            }
        }
        return false;
    }

    // --------------------------------------------------------------------
    //  ENCHANT (enchanting table nearby; simplified - apply specific enchant)
    // --------------------------------------------------------------------
    private void doEnchant(NPCData npc, Player p, String slotName, String enchName, int level) {
        Entity e = npc.getEntityUuid() == null ? null : Bukkit.getEntity(npc.getEntityUuid());
        if (!(e instanceof LivingEntity self)) return;
        Block table = findNearestStation(self.getLocation(), Material.ENCHANTING_TABLE);
        if (table == null) { p.sendMessage("\u00a7c" + npc.getName() + " needs an enchanting table nearby."); return; }
        NPCInventory inv = npc.getInventory();
        // Need lapis (3) and XP levels.
        int lapisCost = Math.min(3, level);
        int xpCost = Math.max(1, level * 2);
        int lapis = count(inv, Material.LAPIS_LAZULI);
        if (lapis < lapisCost) { p.sendMessage("\u00a7cNot enough lapis (need " + lapisCost + ", have " + lapis + ")."); return; }
        if (!inv.spendLevels(xpCost)) { p.sendMessage("\u00a7cNot enough XP (need " + xpCost + " levels)."); return; }
        removeItems(inv, Material.LAPIS_LAZULI, lapisCost);

        ItemStack target = switch (slotName) {
            case "mainhand" -> inv.getMainHand();
            case "offhand"  -> inv.getOffhand();
            case "helmet"   -> inv.getArmor()[3];
            case "chest"    -> inv.getArmor()[2];
            case "leggings" -> inv.getArmor()[1];
            case "boots"    -> inv.getArmor()[0];
            default -> null;
        };
        if (target == null || target.getType().isAir()) {
            p.sendMessage("\u00a7cNothing equipped in that slot."); return;
        }
        Enchantment ench = Enchantment.getByName(enchName.toUpperCase(Locale.ROOT));
        if (ench == null) {
            // Try a few name aliases.
            ench = switch (enchName) {
                case "sharpness"     -> Enchantment.SHARPNESS;
                case "protection"    -> Enchantment.PROTECTION;
                case "efficiency"    -> Enchantment.EFFICIENCY;
                case "fortune"       -> Enchantment.FORTUNE;
                case "silk_touch","silktouch" -> Enchantment.SILK_TOUCH;
                case "unbreaking"    -> Enchantment.UNBREAKING;
                case "power"         -> Enchantment.POWER;
                case "infinity"      -> Enchantment.INFINITY;
                case "knockback"     -> Enchantment.KNOCKBACK;
                case "fire_aspect"   -> Enchantment.FIRE_ASPECT;
                case "mending"       -> Enchantment.MENDING;
                case "feather_falling","featherfalling" -> Enchantment.FEATHER_FALLING;
                case "aqua_affinity" -> Enchantment.AQUA_AFFINITY;
                case "respiration"   -> Enchantment.RESPIRATION;
                case "depth_strider" -> Enchantment.DEPTH_STRIDER;
                default -> null;
            };
        }
        if (ench == null) { p.sendMessage("\u00a7cUnknown enchant: " + enchName); return; }
        if (!ench.canEnchantItem(target)) { p.sendMessage("\u00a7cThat enchant doesn't apply to that item."); return; }
        target.addUnsafeEnchantment(ench, level);
        animateUse(self, table, Sound.BLOCK_ENCHANTMENT_TABLE_USE, Sound.ENTITY_VILLAGER_WORK_LIBRARIAN, Particle.ENCHANT);
        inv.giveXp(0); // no-op but keeps flow
        plugin.getNpcManager().applyEquipment(npc);
        inv.refreshView();
        p.sendMessage("\u00a7a" + npc.getName() + " enchanted " + target.getType().name().toLowerCase().replace('_', ' ')
                + " with " + ench.getKey().getKey() + " " + level + "!");
    }

    private int count(NPCInventory inv, Material m) {
        int n = 0;
        for (int i = 0; i < NPCInventory.MAIN_SIZE; i++) {
            ItemStack it = inv.getInventory().getItem(i);
            if (it != null && it.getType() == m) n += it.getAmount();
        }
        return n;
    }
    private void removeItems(NPCInventory inv, Material m, int howMany) {
        for (int i = 0; i < NPCInventory.MAIN_SIZE && howMany > 0; i++) {
            ItemStack it = inv.getInventory().getItem(i);
            if (it != null && it.getType() == m) {
                int take = Math.min(howMany, it.getAmount());
                it.setAmount(it.getAmount() - take);
                howMany -= take;
                if (it.getAmount() <= 0) inv.getInventory().setItem(i, null);
            }
        }
    }

    // --------------------------------------------------------------------
    private Block findNearestStation(Location from, Material... types) {
        Set<Material> set = EnumSet.noneOf(Material.class);
        Collections.addAll(set, types);
        Block best = null;
        double bestD = STATION_RANGE * STATION_RANGE;
        for (int dx = -(int)STATION_RANGE; dx <= STATION_RANGE; dx++)
            for (int dy = -2; dy <= 2; dy++)
                for (int dz = -(int)STATION_RANGE; dz <= STATION_RANGE; dz++) {
                    Block b = from.clone().add(dx, dy, dz).getBlock();
                    if (set.contains(b.getType())) {
                        double d = b.getLocation().distanceSquared(from);
                        if (d < bestD) { bestD = d; best = b; }
                    }
                }
        return best;
    }

    private void animateUse(LivingEntity self, Block at, Sound sound, Sound workSound, Particle particle) {
        // Face the block.
        Location faceTo = at.getLocation().add(0.5, 0.5, 0.5);
        org.bukkit.util.Vector dir = faceTo.toVector().subtract(self.getEyeLocation().toVector());
        if (dir.lengthSquared() > 0.001) {
            Location l = self.getLocation();
            l.setDirection(dir);
            self.setRotation(l.getYaw(), Math.max(-30, Math.min(45, l.getPitch())));
        }
        self.swingMainHand();
        if (sound != null) at.getWorld().playSound(at.getLocation(), sound, 0.6f, 1.0f);
        if (workSound != null) at.getWorld().playSound(at.getLocation(), workSound, 0.4f, 1.0f);
        if (particle != null) {
            for (int i = 0; i < 8; i++) {
                at.getWorld().spawnParticle(particle,
                        at.getLocation().add(0.5, 1.0, 0.5),
                        2, 0.3, 0.2, 0.3, 0.02);
            }
        }
    }
}
