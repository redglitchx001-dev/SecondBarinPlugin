package me.sailex.secondbrain.npc;

import me.sailex.secondbrain.SecondBrainPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.meta.Damageable;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.*;
import java.util.Base64;

/**
 * A full player-sized persistent inventory for NPCs, with:
 *  - 36 main + hotbar slots (like a player)
 *  - 4 armor slots + offhand
 *  - 2x2 personal crafting grid + result slot
 *  - 3 furnace slots (input/fuel/output) for simulated smelting when near a furnace
 *  - XP levels (used for enchanting)
 *
 * Saved to the NPC's yml via Bukkit's built-in ItemStack serialization (Base64-safe via YAML).
 * Only Paper public API used; zero NMS.
 */
public class NPCInventory implements InventoryHolder {

    private static final int MAIN_SIZE = 36;
    private static final int CRAFT_SIZE = 4; // 2x2
    private final NPCData npc;

    private final ItemStack[] storage = new ItemStack[MAIN_SIZE];
    private final ItemStack[] armor = new ItemStack[4]; // boots, leggings, chest, helmet
    private ItemStack offhand = null;
    private final ItemStack[] craftGrid = new ItemStack[CRAFT_SIZE];
    private ItemStack craftResult = null;
    private ItemStack furnaceInput = null;
    private ItemStack furnaceFuel = null;
    private ItemStack furnaceOutput = null;
    private int furnaceCookProgress = 0;
    private int xpLevels = 0;
    private float xpProgress = 0f;
    private int totalXp = 0;

    // Bukkit viewing inventory (what players see when they click "Open NPC Inventory").
    private Inventory view = null;

    public NPCInventory(NPCData npc) { this.npc = npc; }

    public NPCData getNpc() { return npc; }

    // ---- Bukkit InventoryHolder integration ----
    public Inventory getInventory() {
        if (view == null) {
            view = Bukkit.createInventory(this, 54, "\u00a78\u00a7l" + npc.getName() + "'s Inventory");
            refreshView();
        }
        return view;
    }

    /** Refreshes the viewing inventory from our stored arrays. */
    public void refreshView() {
        if (view == null) return;
        view.clear();
        for (int i = 0; i < MAIN_SIZE; i++) view.setItem(i, storage[i]);
        // Armor slots at positions 36-39 (mimic player layout)
        for (int i = 0; i < 4; i++) view.setItem(36 + i, armor[i]);
        view.setItem(40, offhand);
        // 2x2 crafting grid at 41-44, result at 45
        for (int i = 0; i < CRAFT_SIZE; i++) view.setItem(41 + i, craftGrid[i]);
        view.setItem(45, craftResult);
        // Furnace 46-48
        view.setItem(46, furnaceInput);
        view.setItem(47, furnaceFuel);
        view.setItem(48, furnaceOutput);
    }

    /** Sync changes made by a player in the open GUI back to our arrays. */
    public void syncFromView() {
        if (view == null) return;
        for (int i = 0; i < MAIN_SIZE; i++) storage[i] = view.getItem(i);
        for (int i = 0; i < 4; i++) armor[i] = view.getItem(36 + i);
        offhand = view.getItem(40);
        for (int i = 0; i < CRAFT_SIZE; i++) craftGrid[i] = view.getItem(41 + i);
        craftResult = view.getItem(45);
        furnaceInput = view.getItem(46);
        furnaceFuel = view.getItem(47);
        furnaceOutput = view.getItem(48);
        updateCrafting();
    }

    public void openTo(Player viewer) {
        refreshView();
        viewer.openInventory(getInventory());
    }

    // ---- Pickup / item management ----

    /** Tries to add an item stack to the inventory; returns what couldn't fit. */
    public ItemStack addItem(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return null;
        ItemStack remaining = stack.clone();

        // Try to stack with existing slots first.
        for (int i = 0; i < MAIN_SIZE && remaining.getAmount() > 0; i++) {
            remaining = tryMerge(storage, i, remaining);
        }
        // Try to put into empty slot.
        for (int i = 0; i < MAIN_SIZE && remaining.getAmount() > 0; i++) {
            if (storage[i] == null || storage[i].getType().isAir()) {
                storage[i] = remaining.clone();
                remaining.setAmount(0);
                break;
            }
        }
        autoEquip();
        refreshView();
        return remaining.getAmount() <= 0 ? null : remaining;
    }

    private ItemStack tryMerge(ItemStack[] arr, int slot, ItemStack incoming) {
        ItemStack existing = arr[slot];
        if (existing == null || existing.getType().isAir()) return incoming;
        if (!existing.isSimilar(incoming)) return incoming;
        int space = existing.getMaxStackSize() - existing.getAmount();
        if (space <= 0) return incoming;
        int add = Math.min(space, incoming.getAmount());
        existing.setAmount(existing.getAmount() + add);
        incoming.setAmount(incoming.getAmount() - add);
        return incoming;
    }

    /** Auto-equip better weapons/armor/offhand when picked up. */
    private void autoEquip() {
        // Very simple logic: if a held armor slot is empty or worse, swap.
        // Armor slots: 0=boots, 1=leggings, 2=chest, 3=helmet
        for (int slot = 0; slot < MAIN_SIZE; slot++) {
            ItemStack it = storage[slot];
            if (it == null) continue;
            String type = it.getType().name();
            Integer armorSlot = null;
            if (type.endsWith("_BOOTS")) armorSlot = 0;
            else if (type.endsWith("_LEGGINGS")) armorSlot = 1;
            else if (type.endsWith("_CHESTPLATE") || type.endsWith("_ELYTRA")) armorSlot = 2;
            else if (type.endsWith("_HELMET") || it.getType() == Material.CARVED_PUMPKIN || it.getType() == Material.PLAYER_HEAD) armorSlot = 3;
            if (armorSlot != null && (armor[armorSlot] == null || armor[armorSlot].getType().isAir())) {
                armor[armorSlot] = it.clone();
                storage[slot] = null;
            }
            if ((type.endsWith("_SWORD") || type.endsWith("_AXE") || type.equals("MACE") || type.equals("TRIDENT"))
                    && (getMainHand() == null || getMainHand().getType().isAir())) {
                // Put sword in main hand slot (we treat hotbar slot 0 as main hand).
                // Handled by applyEquipment which reads NPCData.mainHand; but also keep in inventory.
            }
            if (type.equals("SHIELD") && (offhand == null || offhand.getType().isAir())) {
                offhand = it.clone();
                storage[slot] = null;
            }
        }
    }

    public ItemStack getMainHand() { return storage[0]; }

    public ItemStack[] getArmor() { return armor; }
    public ItemStack getOffhand() { return offhand; }

    /** Drops all items at a location (used on NPC death/removal). */
    public void dropAll(org.bukkit.Location loc) {
        if (loc == null || loc.getWorld() == null) return;
        for (ItemStack it : storage) drop(loc, it);
        for (ItemStack it : armor) drop(loc, it);
        drop(loc, offhand);
        for (ItemStack it : craftGrid) drop(loc, it);
        drop(loc, craftResult);
        drop(loc, furnaceInput); drop(loc, furnaceFuel); drop(loc, furnaceOutput);
        Arrays.fill(storage, null); Arrays.fill(armor, null);
        offhand = null; craftResult = null;
        Arrays.fill(craftGrid, null);
        furnaceInput = furnaceFuel = furnaceOutput = null;
    }

    private void drop(org.bukkit.Location loc, ItemStack it) {
        if (it == null || it.getType().isAir()) return;
        loc.getWorld().dropItemNaturally(loc, it.clone());
    }

    // ---- Crafting (2x2 personal grid) ----

    /** Re-check recipes and update the result slot. */
    public void updateCrafting() {
        craftResult = null;
        boolean empty = true;
        for (ItemStack g : craftGrid) if (g != null && !g.getType().isAir()) { empty = false; break; }
        if (empty) { refreshView(); return; }

        for (Recipe r : Bukkit.recipeIterator()) {
            // We only check shaped/shapeless recipes for the 2x2 grid.
            if (r instanceof org.bukkit.inventory.ShapedRecipe shaped) {
                if (matchesShaped(shaped)) { craftResult = r.getResult().clone(); break; }
            } else if (r instanceof org.bukkit.inventory.ShapelessRecipe shapeless) {
                if (matchesShapeless(shapeless)) { craftResult = r.getResult().clone(); break; }
            }
        }
        refreshView();
    }

    /** Called when the player takes the result item: consumes one set of ingredients. */
    public void takeCraftResult() {
        if (craftResult == null) return;
        for (int i = 0; i < CRAFT_SIZE; i++) {
            if (craftGrid[i] != null && !craftGrid[i].getType().isAir()) {
                craftGrid[i].setAmount(craftGrid[i].getAmount() - 1);
                if (craftGrid[i].getAmount() <= 0) craftGrid[i] = null;
            }
        }
        updateCrafting();
    }

    private boolean matchesShapeless(org.bukkit.inventory.ShapelessRecipe r) {
        List<ItemStack> needed = new ArrayList<>(r.getIngredientList());
        List<ItemStack> have = new ArrayList<>();
        for (ItemStack g : craftGrid) if (g != null && !g.getType().isAir()) have.add(g);
        if (needed.size() != have.size()) return false;
        for (ItemStack h : have) {
            boolean found = false;
            for (Iterator<ItemStack> it = needed.iterator(); it.hasNext();) {
                ItemStack n = it.next();
                if (n != null && h.isSimilar(n)) { it.remove(); found = true; break; }
            }
            if (!found) return false;
        }
        return needed.isEmpty();
    }

    private boolean matchesShaped(org.bukkit.inventory.ShapedRecipe r) {
        String[] shape = r.getShape();
        Map<Character, ItemStack> map = r.getIngredientMap();
        if (shape.length > 2) return false; // 2x2 only
        for (String row : shape) if (row.length() > 2) return false;
        // Brute-force try placing the shape in all 4 offsets of the 2x2 grid.
        for (int offY = 0; offY <= 1; offY++) {
            for (int offX = 0; offX <= 1; offX++) {
                if (tryShape(shape, map, offX, offY)) return true;
            }
        }
        return false;
    }

    private boolean tryShape(String[] shape, Map<Character, ItemStack> map, int offX, int offY) {
        boolean[][] used = new boolean[2][2];
        for (int y = 0; y < shape.length; y++) {
            for (int x = 0; x < shape[y].length(); x++) {
                char c = shape[y].charAt(x);
                if (c == ' ') continue;
                int gx = offX + x, gy = offY + y;
                if (gx > 1 || gy > 1) return false;
                int idx = gy * 2 + gx;
                ItemStack slot = craftGrid[idx];
                ItemStack need = map.get(c);
                if (need == null) { if (slot != null) return false; continue; }
                if (slot == null || !slot.isSimilar(need)) return false;
                used[gy][gx] = true;
            }
        }
        // All unused slots must be empty.
        for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) {
            int idx = y*2 + x;
            if (!used[y][x] && craftGrid[idx] != null && !craftGrid[idx].getType().isAir()) return false;
        }
        return true;
    }

    // ---- Furnace simulation ----

    /** Called every tick; returns true if smelting is happening (used for particles/exp). */
    public boolean tickFurnace(SecondBrainPlugin plugin, boolean nearFurnace) {
        if (!nearFurnace) { furnaceCookProgress = 0; return false; }
        if (furnaceInput == null || furnaceInput.getType().isAir()) return false;
        // Find a smelting recipe.
        Recipe smelt = null;
        Iterator<Recipe> it = Bukkit.recipeIterator();
        while (it.hasNext()) {
            Recipe r = it.next();
            if (r instanceof org.bukkit.inventory.FurnaceRecipe fr && fr.getInputChoice().test(furnaceInput)) { smelt = r; break; }
        }
        if (smelt == null) return false;
        // Consume fuel if needed.
        int fuelTime = fuelTime(furnaceFuel);
        if (fuelTime <= 0) return false;
        furnaceCookProgress++;
        if (furnaceCookProgress >= 200) { // ~10s like vanilla
            // Produce output.
            ItemStack result = ((org.bukkit.inventory.FurnaceRecipe) smelt).getResult();
            if (furnaceOutput == null || furnaceOutput.getType().isAir()) {
                furnaceOutput = result.clone();
            } else if (furnaceOutput.isSimilar(result) && furnaceOutput.getAmount() < furnaceOutput.getMaxStackSize()) {
                furnaceOutput.setAmount(furnaceOutput.getAmount() + 1);
            } else {
                return true;
            }
            furnaceInput.setAmount(furnaceInput.getAmount() - 1);
            if (furnaceInput.getAmount() <= 0) furnaceInput = null;
            // Consume one fuel item.
            furnaceFuel.setAmount(furnaceFuel.getAmount() - 1);
            if (furnaceFuel.getAmount() <= 0) furnaceFuel = null;
            furnaceCookProgress = 0;
            xpLevels += 1; // simple xp gain
        }
        refreshView();
        return true;
    }

    private int fuelTime(ItemStack fuel) {
        if (fuel == null) return 0;
        if (fuel.getType() == Material.COAL || fuel.getType() == Material.CHARCOAL) return 1600;
        if (fuel.getType() == Material.STICK || fuel.getType() == Material.WOODEN_SWORD) return 100;
        if (fuel.getType().name().endsWith("_LOG") || fuel.getType().name().endsWith("_PLANKS")) return 300;
        return 0;
    }

    // ---- XP / levels ----
    public int getXpLevels() { return xpLevels; }
    public void giveXp(int levels) { xpLevels += levels; }
    public boolean spendLevels(int levels) {
        if (xpLevels < levels) return false;
        xpLevels -= levels; return true;
    }

    // ---- Persistence ----

    public void save(ConfigurationSection sec) {
        sec.set("storage",   stacksToBase64(storage));
        sec.set("armor",     stacksToBase64(armor));
        sec.set("offhand",   stackToBase64(offhand));
        sec.set("craft",     stacksToBase64(craftGrid));
        sec.set("craft-result", stackToBase64(craftResult));
        sec.set("furnace-in", stackToBase64(furnaceInput));
        sec.set("furnace-fuel", stackToBase64(furnaceFuel));
        sec.set("furnace-out", stackToBase64(furnaceOutput));
        sec.set("xp-levels", xpLevels);
    }

    public void load(ConfigurationSection sec) {
        if (sec == null) return;
        base64ToStacks(sec.getString("storage"), storage, MAIN_SIZE);
        base64ToStacks(sec.getString("armor"), armor, 4);
        offhand = base64ToStack(sec.getString("offhand"));
        base64ToStacks(sec.getString("craft"), craftGrid, CRAFT_SIZE);
        craftResult = base64ToStack(sec.getString("craft-result"));
        furnaceInput = base64ToStack(sec.getString("furnace-in"));
        furnaceFuel = base64ToStack(sec.getString("furnace-fuel"));
        furnaceOutput = base64ToStack(sec.getString("furnace-out"));
        xpLevels = sec.getInt("xp-levels", 0);
        refreshView();
    }

    private static String stacksToBase64(ItemStack[] arr) {
        YamlConfiguration yaml = new YamlConfiguration();
        for (int i = 0; i < arr.length; i++) yaml.set("s" + i, arr[i]);
        StringWriter sw = new StringWriter();
        try { yaml.save(sw); } catch (Exception ignored) {}
        return Base64.getEncoder().encodeToString(sw.toString().getBytes());
    }
    private static String stackToBase64(ItemStack s) {
        if (s == null) return null;
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("s", s);
        StringWriter sw = new StringWriter();
        try { yaml.save(sw); } catch (Exception ignored) {}
        return Base64.getEncoder().encodeToString(sw.toString().getBytes());
    }
    private static void base64ToStacks(String b64, ItemStack[] into, int size) {
        Arrays.fill(into, null);
        if (b64 == null || b64.isBlank()) return;
        try {
            String yml = new String(Base64.getDecoder().decode(b64));
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(new StringReader(yml));
            for (int i = 0; i < size; i++) into[i] = yaml.getItemStack("s" + i);
        } catch (Exception ignored) {}
    }
    private static ItemStack base64ToStack(String b64) {
        if (b64 == null || b64.isBlank()) return null;
        try {
            String yml = new String(Base64.getDecoder().decode(b64));
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(new StringReader(yml));
            return yaml.getItemStack("s");
        } catch (Exception e) { return null; }
    }
}
