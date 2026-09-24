package me.sailex.secondbrain.npc;

import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Villager;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Everything we know about one NPC.
 * Settings persist to npcs.yml; the entity itself is (re)spawned from these.
 */
public class NPCData {

    private final String id;
    private String name;
    private Location location;
    private String systemPrompt;
    private UUID entityUuid; // runtime: UUID of the spawned Bukkit entity

    // ---- per-NPC settings (null = inherit global default) ----
    private Boolean chatEnabled;      // answers chat at all
    private Boolean nameOnly;         // only answer when name is mentioned
    private Boolean lookAtPlayers;    // turn head toward players
    private Boolean showName;         // name tag visible
    private Boolean glow;             // glowing effect
    private Double chatRadius;        // hearing radius override
    private EntityType entityType;    // VILLAGER by default
    private Villager.Profession profession; // villagers only
    private Boolean baby;
    private String skinName;          // Minecraft player name whose skin is applied as a helmet, null = no skin
    private Boolean canExecuteCommands;// true = this NPC can run commands (only when talker is OP)
    private Boolean consoleExecutor;   // true = trusted OP-NPC: commands dispatch as console (bypasses protections)
    private Boolean hostile;           // true = attacks nearby players in combat range
    private String mainHand;          // Bukkit Material name for held item, null = nothing (e.g. DIAMOND_SWORD, MACE)
    private Boolean canPickupItems;   // true = attracts and picks up nearby dropped items (default true)
    private NPCInventory inventory;   // full player-like inv: 36+armor+offhand+craft+furnace

    // ---- runtime counters ----
    private final AtomicLong repliesServed = new AtomicLong();
    private volatile boolean thinking = false;

    public NPCData(String id, String name, Location location, String systemPrompt) {
        this.id = id;
        this.name = name;
        this.location = location;
        this.systemPrompt = systemPrompt;
    }

    // ---- basic ----
    public String getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Location getLocation() { return location; }
    public void setLocation(Location location) { this.location = location; }
    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
    public UUID getEntityUuid() { return entityUuid; }
    public void setEntityUuid(UUID entityUuid) { this.entityUuid = entityUuid; }

    // ---- settings with global-default fallbacks handled by NPCManager ----
    public Boolean getChatEnabledRaw() { return chatEnabled; }
    public void setChatEnabled(Boolean v) { this.chatEnabled = v; }
    public Boolean getNameOnlyRaw() { return nameOnly; }
    public void setNameOnly(Boolean v) { this.nameOnly = v; }
    public Boolean getLookAtPlayersRaw() { return lookAtPlayers; }
    public void setLookAtPlayers(Boolean v) { this.lookAtPlayers = v; }
    public Boolean getShowNameRaw() { return showName; }
    public void setShowName(Boolean v) { this.showName = v; }
    public Boolean getGlowRaw() { return glow; }
    public void setGlow(Boolean v) { this.glow = v; }
    public Double getChatRadiusRaw() { return chatRadius; }
    public void setChatRadius(Double v) { this.chatRadius = v; }
    public EntityType getEntityType() { return entityType == null ? EntityType.VILLAGER : entityType; }
    public void setEntityType(EntityType entityType) { this.entityType = entityType; }
    public Villager.Profession getProfession() { return profession; }
    public void setProfession(Villager.Profession profession) { this.profession = profession; }
    public Boolean getBabyRaw() { return baby; }
    public void setBaby(Boolean baby) { this.baby = baby; }

    public String getSkinName() { return skinName; }
    public void setSkinName(String skinName) {
        this.skinName = (skinName == null || skinName.isBlank()) ? null : skinName;
    }
    public boolean hasSkin() { return skinName != null && !skinName.isBlank(); }

    public Boolean getCanExecuteCommandsRaw() { return canExecuteCommands; }
    public void setCanExecuteCommands(Boolean v) { this.canExecuteCommands = v; }
    public boolean canExecuteCommands() { return Boolean.TRUE.equals(canExecuteCommands); }

    public Boolean getConsoleExecutorRaw() { return consoleExecutor; }
    public void setConsoleExecutor(Boolean v) { this.consoleExecutor = v; }
    public boolean isConsoleExecutor() { return Boolean.TRUE.equals(consoleExecutor); }

    public Boolean getHostileRaw() { return hostile; }
    public void setHostile(Boolean v) { this.hostile = v; }
    public boolean isHostile() { return Boolean.TRUE.equals(hostile); }

    public String getMainHand() { return mainHand; }
    public void setMainHand(String mainHand) { this.mainHand = mainHand; }

    /** Full player-like inventory (lazy; instantiated on first access). */
    public NPCInventory getInventory() {
        if (inventory == null) inventory = new NPCInventory(this);
        return inventory;
    }
    public boolean hasInventory() { return inventory != null; }

    public Boolean getCanPickupItemsRaw() { return canPickupItems; }
    public void setCanPickupItems(Boolean v) { this.canPickupItems = v; }
    public boolean canPickupItems() { return canPickupItems == null || canPickupItems; }

    // ---- runtime ----
    public long incrementReplies() { return repliesServed.incrementAndGet(); }
    public long getRepliesServed() { return repliesServed.get(); }
    public boolean isThinking() { return thinking; }
    public void setThinking(boolean thinking) { this.thinking = thinking; }

    /** One-line status for /sb list */
    public String statusString(boolean chatEnabledEffective) {
        if (!chatEnabledEffective) return "\u00a7cAI off";
        return thinking ? "\u00a7ethinking..." : "\u00a7aonline";
    }
}
