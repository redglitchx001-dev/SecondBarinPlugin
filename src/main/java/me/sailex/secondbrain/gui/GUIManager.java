package me.sailex.secondbrain.gui;

import me.sailex.secondbrain.SecondBrainPlugin;
import me.sailex.secondbrain.config.ConfigManager;
import me.sailex.secondbrain.npc.NPCData;
import me.sailex.secondbrain.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Builds and opens every SecondBrain inventory GUI. */
public class GUIManager {

    private static final LegacyComponentSerializer SER = LegacyComponentSerializer.legacySection();

    // Title markers used by GUIListener to route clicks (plain-text contains checks).
    public static final String TITLE_MAIN      = "\u00a70\u00a7l\u2726 \u00a7bSecondBrain \u00a70\u00a7l\u2726";
    public static final String TITLE_NPCS      = "\u00a70\u00a7lNPCs \u00a78- page ";
    public static final String TITLE_EDITOR    = "\u00a70\u00a7lNPC: ";
    public static final String TITLE_SETTINGS  = "\u00a70\u00a7lGlobal Settings";
    public static final String TITLE_CONFIRM   = "\u00a70\u00a7lAre you sure?";
    public static final String TITLE_ENTITY    = "\u00a70\u00a7lPick Entity Type";
    public static final String TITLE_PROFESSION= "\u00a70\u00a7lPick Profession";
    public static final String TITLE_STATS     = "\u00a70\u00a7lStatistics";
    public static final String TITLE_STATUS    = "\u00a70\u00a7lConnection";

    /** Chat-input session types started from a GUI. */
    public enum InputType { CREATE_NPC, RENAME_NPC, SET_PROMPT, SET_KEY, SET_URL, SET_MODEL }

    /** A pending chat-input request. */
    public record InputSession(InputType type, String npcId, String npcName) {}

    private final SecondBrainPlugin plugin;
    private final Map<UUID, Integer> npcListPage = new ConcurrentHashMap<>();
    private final Map<UUID, InputSession> inputs = new ConcurrentHashMap<>();
    private final Map<UUID, ConfirmAction> confirms = new ConcurrentHashMap<>();
    private final Map<UUID, NPCData> pickerTarget = new ConcurrentHashMap<>();

    public GUIManager(SecondBrainPlugin plugin) { this.plugin = plugin; }

    /** Which NPC a picker menu belongs to (set when the picker opens). */
    public NPCData takePickerTarget(Player player) { return pickerTarget.remove(player.getUniqueId()); }
    public NPCData peekPickerTarget(Player player) { return pickerTarget.get(player.getUniqueId()); }

    // ============================================================
    //  Input sessions (GUI -> "type in chat")
    // ============================================================

    public void beginInput(Player player, InputType type, NPCData npc) {
        inputs.put(player.getUniqueId(),
                new InputSession(type, npc == null ? null : npc.getId(), npc == null ? null : npc.getName()));
        player.closeInventory();
        switch (type) {
            case CREATE_NPC -> hint(player, "&fType the &eNPC name&f in chat to create it at your location.");
            case RENAME_NPC -> hint(player, "&fType the &enew name&f for &e" + npc.getName() + "&f (or &ccancel&f).");
            case SET_PROMPT -> hint(player, "&fType the &enew system prompt&f for &e" + npc.getName() + "&f (or &ccancel&f).");
            case SET_KEY -> hint(player, "&fType the new &eAPI key&f (or &ccancel&f).");
            case SET_URL -> hint(player, "&fType the new &eAPI URL&f (or &ccancel&f).");
            case SET_MODEL -> hint(player, "&fType the new &emodel name&f (or &ccancel&f).");
        }
    }

    /** @return the pending session for this player, or null. */
    public InputSession takeInput(Player player) { return inputs.remove(player.getUniqueId()); }
    public InputSession peekInput(Player player) { return inputs.get(player.getUniqueId()); }

    public int getPage(UUID playerId) { return npcListPage.getOrDefault(playerId, 0); }

    private void hint(Player p, String s) {
        p.sendMessage(plugin.getConfigManager().msgRaw("prefix") + Text.color(s));
    }

    // ============================================================
    //  Main menu
    // ============================================================

    public void openMainMenu(Player player) {
        Inventory inv = create(TITLE_MAIN, 27);
        border(inv, 27);

        int npcCount = plugin.getNpcManager().getAllNPCs().size();
        var cm = plugin.getConfigManager();

        inv.setItem(10, new ItemBuilder(Material.VILLAGER_SPAWN_EGG)
                .name("\u00a7b\u00a7lNPCs")
                .lore("\u00a77Total: \u00a7f" + npcCount,
                        "\u00a77AI chat: " + onOff(cm.isChatEnabled()),
                        "",
                        "\u00a7aClick \u00a77to browse & manage")
                .build());

        inv.setItem(12, new ItemBuilder(Material.NAME_TAG)
                .name("\u00a7a\u00a7lCreate NPC")
                .lore("\u00a77Spawns at your location.",
                        "\u00a77You'll type the name in chat.")
                .build());

        inv.setItem(14, new ItemBuilder(Material.COMPARATOR)
                .name("\u00a7e\u00a7lGlobal Settings")
                .lore("\u00a77Toggle chat, name-only, glow,",
                        "\u00a77typing indicator, debug & more.")
                .build());

        inv.setItem(16, new ItemBuilder(Material.BOOK)
                .name("\u00a7d\u00a7lStatistics")
                .lore("\u00a77Replies, latency, uptime...")
                .build());

        inv.setItem(19, new ItemBuilder(Material.FILLED_MAP)
                .name("\u00a7b\u00a7lConnection")
                .lore("\u00a77Endpoint: \u00a7f" + cm.getApiUrl(),
                        "\u00a77Model: \u00a7f" + cm.getApiModel(),
                        "\u00a77Key: \u00a7f" + Text.maskKey(cm.getApiKey()))
                .build());

        inv.setItem(21, new ItemBuilder(Material.EMERALD)
                .name("\u00a7a\u00a7lSave")
                .lore("\u00a77Write NPCs + memories to disk.")
                .build());

        inv.setItem(23, new ItemBuilder(Material.NETHER_STAR)
                .name("\u00a76\u00a7lReload")
                .lore("\u00a77Reload config.yml.")
                .build());

        inv.setItem(25, new ItemBuilder(Material.BARRIER)
                .name("\u00a7c\u00a7lClose")
                .build());

        player.openInventory(inv);
    }

    // ============================================================
    //  NPC list (paginated)
    // ============================================================

    public void openNPCList(Player player) {
        openNPCList(player, npcListPage.getOrDefault(player.getUniqueId(), 0));
    }

    public void openNPCList(Player player, int page) {
        var all = plugin.getNpcManager().getAllNPCs().values().stream()
                .sorted(java.util.Comparator.comparing(n -> n.getName().toLowerCase(Locale.ROOT)))
                .toList();

        int perPage = 28;
        int pages = Math.max(1, (all.size() + perPage - 1) / perPage);
        page = Math.max(0, Math.min(page, pages - 1));
        npcListPage.put(player.getUniqueId(), page);

        Inventory inv = create(TITLE_NPCS + (page + 1), 54);
        fillRow(inv, 0);
        fillRow(inv, 5);
        fillSides(inv, 1, 4);

        inv.setItem(4, new ItemBuilder(Material.BOOK)
                .name("\u00a7b\u00a7l" + all.size() + " NPC(s)")
                .lore("\u00a77Page \u00a7f" + (page + 1) + "\u00a77/\u00a7f" + pages,
                        plugin.getConfigManager().isKeyConfigured()
                                ? "\u00a7aAI connection configured"
                                : "\u00a7cAPI key missing: /sb setkey <key>")
                .build());

        int slot = 10;
        int shown = 0;
        var iterator = all.listIterator(page * perPage);
        while (iterator.hasNext() && shown < perPage) {
            if (slot > 43) break;
            if (slot % 9 == 0 || slot % 9 == 8) slot++;

            NPCData d = iterator.next();
            String status = plugin.getNpcManager().isChatEnabled(d) ? "\u00a7aAI on" : "\u00a7cAI off";
            boolean admin = player.hasPermission("secondbrain.admin");

            ItemBuilder b = new ItemBuilder(eggFor(d.getEntityType()))
                    .name("\u00a7e\u00a7l" + d.getName())
                    .lore("\u00a77Type: \u00a7f" + d.getEntityType().name(),
                            "\u00a77World: \u00a7f" + worldName(d),
                            "\u00a77Status: " + status + (d.isThinking() ? " \u00a7e(thinking...)" : ""),
                            "");
            if (admin) {
                b.lore("\u00a7a[Left-Click] \u00a77Open editor",
                        "\u00a77[Shift-Click] \u00a77Teleport to NPC",
                        "\u00a7c[Drop / Q] \u00a77Delete");
            } else {
                b.lore("\u00a77Say its name in chat to talk to it.");
            }
            inv.setItem(slot, b.build());
            slot++;
            shown++;
        }

        if (all.isEmpty()) {
            inv.setItem(22, new ItemBuilder(Material.BARRIER)
                    .name("\u00a7cNo NPCs yet")
                    .lore("\u00a77Create one with the Name Tag below!")
                    .build());
        }

        inv.setItem(45, new ItemBuilder(Material.ARROW).name("\u00a7c\u00a7lBack").build());
        if (page > 0) inv.setItem(48, new ItemBuilder(Material.SPECTRAL_ARROW).name("\u00a7e\u00a7lPrevious Page").build());
        if (page < pages - 1) inv.setItem(50, new ItemBuilder(Material.SPECTRAL_ARROW).name("\u00a7a\u00a7lNext Page").build());
        inv.setItem(53, new ItemBuilder(Material.NAME_TAG).name("\u00a7a\u00a7lCreate NPC").build());

        player.openInventory(inv);
    }

    // ============================================================
    //  NPC editor
    // ============================================================

    public void openEditor(Player player, NPCData npc) {
        Inventory inv = create(TITLE_EDITOR + npc.getName(), 54);
        border(inv, 54);

        var nm = plugin.getNpcManager();
        boolean chat = nm.isChatEnabled(npc);
        boolean nameOnly = nm.isNameOnly(npc);
        boolean look = nm.isLookAtPlayers(npc);
        boolean showName = nm.isShowName(npc);
        boolean glow = nm.isGlow(npc);
        double radius = nm.getChatRadius(npc);
        long memories = plugin.getChatService().countMessages(npc.getId());

        inv.setItem(4, new ItemBuilder(eggFor(npc.getEntityType()))
                .name("\u00a7b\u00a7l" + npc.getName())
                .lore("\u00a77ID: \u00a7f" + npc.getId(),
                        "\u00a77Type: \u00a7f" + npc.getEntityType().name()
                                + (npc.getProfession() != null ? " \u00a77(\u00a7f" + npc.getProfession() + "\u00a77)" : ""),
                        "\u00a77World: \u00a7f" + worldName(npc),
                        "\u00a77Replies served: \u00a7f" + npc.getRepliesServed(),
                        "\u00a77Memory: \u00a7f" + memories + " messages")
                .build());

        inv.setItem(10, toggleItem(Material.PAPER, "AI Chat", chat, npc.getChatEnabledRaw() == null));
        inv.setItem(11, toggleItem(Material.NAME_TAG, "Name-Only", nameOnly, npc.getNameOnlyRaw() == null));
        inv.setItem(12, toggleItem(Material.ENDER_EYE, "Look At Players", look, npc.getLookAtPlayersRaw() == null));
        inv.setItem(19, toggleItem(Material.OAK_SIGN, "Show Name Tag", showName, npc.getShowNameRaw() == null));
        inv.setItem(20, toggleItem(Material.GLOWSTONE_DUST, "Glowing", glow, npc.getGlowRaw() == null));
        inv.setItem(21, toggleItem(Material.EGG, "Baby (ageable types)",
                Boolean.TRUE.equals(npc.getBabyRaw()), npc.getBabyRaw() == null));

        inv.setItem(13, new ItemBuilder(Material.CLOCK)
                .name("\u00a7b\u00a7lChat Radius: \u00a7f" + Text.num(radius))
                .lore(npc.getChatRadiusRaw() == null ? "\u00a78Inheriting global default" : "\u00a78Custom radius",
                        "",
                        "\u00a77Use the \u00a7a+\u00a77/\u00a7c-\u00a77 buttons below.")
                .build());
        inv.setItem(15, new ItemBuilder(Material.LIME_STAINED_GLASS_PANE).name("\u00a7a+1").build());
        inv.setItem(16, new ItemBuilder(Material.LIME_STAINED_GLASS).name("\u00a7a+5").build());
        inv.setItem(23, new ItemBuilder(Material.RED_STAINED_GLASS_PANE).name("\u00a7c-1").build());
        inv.setItem(24, new ItemBuilder(Material.RED_STAINED_GLASS).name("\u00a7c-5").build());

        inv.setItem(29, new ItemBuilder(Material.WRITABLE_BOOK)
                .name("\u00a7d\u00a7lSet Prompt")
                .lore("\u00a77Personality of this NPC.",
                        "\u00a77Click, then type it in chat.")
                .build());

        inv.setItem(31, new ItemBuilder(eggFor(npc.getEntityType()))
                .name("\u00a7e\u00a7lEntity Type")
                .lore("\u00a77Current: \u00a7f" + npc.getEntityType().name(),
                        "\u00a77Click to change.")
                .build());

        if (npc.getEntityType() == EntityType.VILLAGER) {
            inv.setItem(33, new ItemBuilder(Material.EMERALD)
                    .name("\u00a7a\u00a7lProfession")
                    .lore("\u00a77Current: \u00a7f" + (npc.getProfession() == null ? "NONE" : npc.getProfession()),
                            "\u00a77Click to change.")
                    .build());
        }

        inv.setItem(38, new ItemBuilder(Material.ANVIL)
                .name("\u00a76\u00a7lRename")
                .lore("\u00a77Click, then type the new name.")
                .build());
        inv.setItem(40, new ItemBuilder(Material.ENDER_PEARL)
                .name("\u00a75\u00a7lTeleport To NPC")
                .build());
        inv.setItem(42, new ItemBuilder(Material.COMPASS)
                .name("\u00a79\u00a7lMove NPC Here")
                .build());

        // bottom row
        inv.setItem(45, new ItemBuilder(Material.ARROW).name("\u00a7c\u00a7lBack").build());
        inv.setItem(49, new ItemBuilder(Material.MAGMA_CREAM)
                .name("\u00a7c\u00a7lClear Memory")
                .lore("\u00a77Forgets every conversation.")
                .build());
        inv.setItem(53, new ItemBuilder(Material.BARRIER)
                .name("\u00a74\u00a7lDelete NPC")
                .lore("\u00a77Removes the entity and data.")
                .build());

        player.openInventory(inv);
    }

    // ============================================================
    //  Global settings
    // ============================================================

    public void openSettings(Player player) {
        Inventory inv = create(TITLE_SETTINGS, 45);
        border(inv, 45);
        var cm = plugin.getConfigManager();

        inv.setItem(10, toggleItem(Material.PAPER, "Master Chat Switch", cm.isChatEnabled(), false, false));
        inv.setItem(11, toggleItem(Material.NAME_TAG, "Respond Only To Name", cm.isRespondOnlyToName(), false, false));
        inv.setItem(12, toggleItem(Material.ENDER_EYE, "Look At Players", cm.isLookAtPlayers(), false, false));
        inv.setItem(13, toggleItem(Material.OAK_SIGN, "Show Name Tags", cm.isShowName(), false, false));
        inv.setItem(14, toggleItem(Material.GLOWSTONE_DUST, "Glowing NPCs", cm.isGlow(), false, false));
        inv.setItem(15, toggleItem(Material.CLOCK, "Typing Indicator", cm.isTypingIndicator(), false, false));
        inv.setItem(16, toggleItem(Material.REDSTONE_TORCH, "Debug Logging", cm.isDebug(), false, false));

        inv.setItem(21, new ItemBuilder(Material.TRIPWIRE_HOOK)
                .name("\u00a7b\u00a7lAPI Key")
                .lore("\u00a77Current: \u00a7f" + Text.maskKey(cm.getApiKey()),
                        "\u00a77Click to change in chat.")
                .build());
        inv.setItem(23, new ItemBuilder(Material.FILLED_MAP)
                .name("\u00a7b\u00a7lAPI URL")
                .lore("\u00a77Current: \u00a7f" + cm.getApiUrl(),
                        "\u00a77Click to change in chat.")
                .build());
        inv.setItem(25, new ItemBuilder(Material.BOOK)
                .name("\u00a7b\u00a7lModel")
                .lore("\u00a77Current: \u00a7f" + cm.getApiModel(),
                        "\u00a77Click to change in chat.")
                .build());

        inv.setItem(40, new ItemBuilder(Material.ARROW).name("\u00a7c\u00a7lBack").build());
        player.openInventory(inv);
    }

    // ============================================================
    //  Entity / profession pickers
    // ============================================================

    public void openEntityPicker(Player player, NPCData npc) {
        pickerTarget.put(player.getUniqueId(), npc);
        Inventory inv = create(TITLE_ENTITY, 27);
        border(inv, 27);
        int slot = 10;
        for (EntityType type : me.sailex.secondbrain.npc.NPCManager.ALLOWED_TYPES) {
            ItemBuilder b = new ItemBuilder(eggFor(type)).name("\u00a7e\u00a7l" + type.name());
            if (type == npc.getEntityType()) b.lore("\u00a7aCurrent selection");
            inv.setItem(slot++, b.build());
            if (slot % 9 == 8) slot += 2;
        }
        inv.setItem(22, new ItemBuilder(Material.ARROW).name("\u00a7c\u00a7lBack").build());
        player.openInventory(inv);
    }

    public void openProfessionPicker(Player player, NPCData npc) {
        pickerTarget.put(player.getUniqueId(), npc);
        Inventory inv = create(TITLE_PROFESSION, 54);
        fillRow(inv, 0);
        fillRow(inv, 5);
        fillSides(inv, 1, 4);
        Villager.Profession[] professions = Villager.Profession.values();
        int slot = 10;
        for (Villager.Profession p : professions) {
            ItemBuilder b = new ItemBuilder(Material.EMERALD).name("\u00a7a\u00a7l" + p.name());
            if (p == (npc.getProfession() == null ? Villager.Profession.NONE : npc.getProfession())) {
                b.lore("\u00a7aCurrent selection");
            }
            inv.setItem(slot, b.build());
            slot++;
            if (slot % 9 == 8) slot += 2;
            if (slot > 43) break;
        }
        inv.setItem(45, new ItemBuilder(Material.ARROW).name("\u00a7c\u00a7lBack").build());
        player.openInventory(inv);
    }

    // ============================================================
    //  Stats + status panels
    // ============================================================

    public void openStats(Player player) {
        Inventory inv = create(TITLE_STATS, 27);
        border(inv, 27);
        var stats = plugin.getStats();

        inv.setItem(10, new ItemBuilder(Material.VILLAGER_SPAWN_EGG)
                .name("\u00a7b\u00a7lNPCs")
                .lore("\u00a77Alive: \u00a7f" + plugin.getNpcManager().getAllNPCs().size())
                .build());
        inv.setItem(11, new ItemBuilder(Material.WRITABLE_BOOK)
                .name("\u00a7d\u00a7lConversations")
                .lore("\u00a77Stored: \u00a7f" + plugin.getChatService().countConversations())
                .build());
        inv.setItem(12, new ItemBuilder(Material.PAPER)
                .name("\u00a7e\u00a7lAI Requests")
                .lore("\u00a77Sent: \u00a7f" + stats.getRequests(),
                        "\u00a77Replies: \u00a7f" + stats.getReplies(),
                        "\u00a77Errors: \u00a7c" + stats.getErrors())
                .build());
        inv.setItem(14, new ItemBuilder(Material.CLOCK)
                .name("\u00a76\u00a7lLatency")
                .lore("\u00a77Average reply: \u00a7f" + stats.avgLatency())
                .build());
        inv.setItem(15, new ItemBuilder(Material.SUNFLOWER)
                .name("\u00a7a\u00a7lUptime")
                .lore("\u00a77Session: \u00a7f" + Text.uptime(stats.getUptimeMillis()))
                .build());
        inv.setItem(16, new ItemBuilder(Material.FILLED_MAP)
                .name("\u00a7b\u00a7lConnection")
                .lore("\u00a77Click to view + test.")
                .build());

        inv.setItem(22, new ItemBuilder(Material.ARROW).name("\u00a7c\u00a7lBack").build());
        player.openInventory(inv);
    }

    public void openStatus(Player player) {
        Inventory inv = create(TITLE_STATUS, 27);
        border(inv, 27);
        var cm = plugin.getConfigManager();

        inv.setItem(10, new ItemBuilder(Material.FILLED_MAP)
                .name("\u00a7b\u00a7lEndpoint")
                .lore("\u00a7f" + cm.getApiUrl(),
                        "\u00a77Timeout: \u00a7f" + cm.getTimeout() + "s")
                .build());
        inv.setItem(12, new ItemBuilder(Material.BOOK)
                .name("\u00a7e\u00a7lModel")
                .lore("\u00a7f" + cm.getApiModel(),
                        "\u00a77Max tokens: \u00a7f" + cm.getMaxTokens(),
                        "\u00a77Temperature: \u00a7f" + Text.num(cm.getTemperature()))
                .build());
        inv.setItem(14, new ItemBuilder(Material.TRIPWIRE_HOOK)
                .name("\u00a7d\u00a7lAPI Key")
                .lore("\u00a7f" + Text.maskKey(cm.getApiKey()),
                        cm.isKeyConfigured() ? "\u00a7aConfigured" : "\u00a7cNot configured")
                .build());
        inv.setItem(16, new ItemBuilder(Material.SLIME_BALL)
                .name("\u00a7a\u00a7lTest Connection")
                .lore("\u00a77Sends a ping to the AI endpoint.")
                .build());

        inv.setItem(22, new ItemBuilder(Material.ARROW).name("\u00a7c\u00a7lBack").build());
        player.openInventory(inv);
    }

    // ============================================================
    //  Confirm dialog
    // ============================================================

    public void confirm(Player player, String description, Runnable onConfirm, Runnable onCancel) {
        confirms.put(player.getUniqueId(), new ConfirmAction(description, onConfirm, onCancel));
        Inventory inv = create(TITLE_CONFIRM, 27);
        border(inv, 27);
        inv.setItem(11, new ItemBuilder(Material.LIME_CONCRETE).name("\u00a7a\u00a7lConfirm").build());
        inv.setItem(13, new ItemBuilder(Material.PAPER).name("\u00a7f" + description).build());
        inv.setItem(15, new ItemBuilder(Material.RED_CONCRETE).name("\u00a7c\u00a7lCancel").build());
        player.openInventory(inv);
    }

    public ConfirmAction takeConfirm(Player player) {
        ConfirmAction a = confirms.remove(player.getUniqueId());
        return (a != null && !a.isExpired()) ? a : null;
    }

    // ============================================================
    //  Helpers
    // ============================================================

    private Inventory create(String title, int size) {
        return Bukkit.createInventory(null, size, SER.deserialize(title));
    }

    private void border(Inventory inv, int size) {
        ItemStack glass = new ItemBuilder(plugin.getConfigManager().getGuiFiller()).name("\u00a7r").build();
        for (int i = 0; i < size; i++) {
            if (i < 9 || i >= size - 9 || i % 9 == 0 || i % 9 == 8) inv.setItem(i, glass);
        }
    }

    private void fillRow(Inventory inv, int row) {
        ItemStack glass = new ItemBuilder(plugin.getConfigManager().getGuiFiller()).name("\u00a7r").build();
        for (int i = row * 9; i < row * 9 + 9; i++) inv.setItem(i, glass);
    }

    private void fillSides(Inventory inv, int fromRow, int toRow) {
        ItemStack glass = new ItemBuilder(plugin.getConfigManager().getGuiFiller()).name("\u00a7r").build();
        for (int row = fromRow; row <= toRow; row++) {
            inv.setItem(row * 9, glass);
            inv.setItem(row * 9 + 8, glass);
        }
    }

    private ItemStack toggleItem(Material icon, String label, boolean on, boolean inherited) {
        return toggleItem(icon, label, on, inherited, true);
    }

    private ItemStack toggleItem(Material icon, String label, boolean on, boolean inherited, boolean showResetHint) {
        ItemBuilder b = new ItemBuilder(on ? Material.LIME_DYE : Material.GRAY_DYE)
                .name((on ? "\u00a7a\u2714 " : "\u00a7c\u2718 ") + "\u00a7f" + label + (on ? "\u00a7a ON" : "\u00a7c OFF"));
        if (inherited) b.lore("\u00a78Inheriting global default");
        b.lore("", "\u00a77Left-click: toggle");
        if (showResetHint) {
            if (inherited) b.lore("\u00a78(already using global)");
            else b.lore("\u00a77Right-click: reset to global");
        }
        return b.build();
    }

    /** Spawn-egg material matching an entity type. */
    public static Material eggFor(EntityType type) {
        return switch (type) {
            case ZOMBIE -> Material.ZOMBIE_SPAWN_EGG;
            case SKELETON -> Material.SKELETON_SPAWN_EGG;
            case WITCH -> Material.WITCH_SPAWN_EGG;
            case PILLAGER -> Material.PILLAGER_SPAWN_EGG;
            case ARMOR_STAND -> Material.ARMOR_STAND;
            default -> Material.VILLAGER_SPAWN_EGG;
        };
    }

    private String worldName(NPCData npc) {
        Location l = npc.getLocation();
        return l == null || l.getWorld() == null ? "none" : l.getWorld().getName();
    }

    private String onOff(boolean b) { return b ? "\u00a7aon" : "\u00a7coff"; }
}
