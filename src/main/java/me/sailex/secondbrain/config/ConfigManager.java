package me.sailex.secondbrain.config;

import me.sailex.secondbrain.SecondBrainPlugin;
import me.sailex.secondbrain.util.Text;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Villager;

/** Typed access to config.yml plus persistent in-game setters. */
public class ConfigManager {

    private final SecondBrainPlugin plugin;
    private FileConfiguration cfg;

    public ConfigManager(SecondBrainPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.reloadConfig();
        cfg = plugin.getConfig();
        cfg.options().copyDefaults(true);
    }

    // ---------------- LLM ----------------
    public String getApiUrl()     { return cfg.getString("llm.url", "https://ai.xnetwork.ro/v1"); }
    public String getApiModel()   { return cfg.getString("llm.model", "gpt-oss-20b"); }
    public String getApiKey()     { return cfg.getString("llm.api-key", "PUT_YOUR_API_KEY_HERE"); }
    public int    getTimeout()    { return cfg.getInt("llm.timeout", 30); }
    public int    getMaxHistory() { return cfg.getInt("llm.max-history", 20); }
    public int    getMaxTokens()  { return cfg.getInt("llm.max-tokens", 250); }
    public double getTemperature(){ return cfg.getDouble("llm.temperature", 0.8); }

    public void setApiUrl(String url)     { cfg.set("llm.url", url); plugin.saveConfig(); }
    public void setApiModel(String model) { cfg.set("llm.model", model); plugin.saveConfig(); }
    public void setApiKey(String key)     { cfg.set("llm.api-key", key); plugin.saveConfig(); }

    public boolean isKeyConfigured() {
        String k = getApiKey();
        return k != null && !k.isBlank() && !k.equals("PUT_YOUR_API_KEY_HERE");
    }

    // ---------------- NPC defaults / global toggles ----------------
    public String  getDefaultPrompt()     { return cfg.getString("npc.default-prompt", "You are {name}, a friendly AI in Minecraft."); }
    public double  getChatRadius()        { return cfg.getDouble("npc.chat-radius", 15.0); }
    public double  getBroadcastMultiplier(){ return cfg.getDouble("npc.broadcast-multiplier", 3.0); }
    public int     getMaxNpcsPerMessage() { return cfg.getInt("npc.max-npcs-per-message", 2); }

    public boolean isRespondOnlyToName()  { return cfg.getBoolean("npc.respond-only-to-name", true); }
    public void setRespondOnlyToName(boolean v) { cfg.set("npc.respond-only-to-name", v); plugin.saveConfig(); }

    public boolean isChatEnabled()        { return cfg.getBoolean("npc.chat-enabled", true); }
    public void setChatEnabled(boolean v) { cfg.set("npc.chat-enabled", v); plugin.saveConfig(); }

    public boolean isLookAtPlayers()      { return cfg.getBoolean("npc.look-at-players", true); }
    public void setLookAtPlayers(boolean v) { cfg.set("npc.look-at-players", v); plugin.saveConfig(); }

    public boolean isShowName()           { return cfg.getBoolean("npc.show-name", true); }
    public void setShowName(boolean v)    { cfg.set("npc.show-name", v); plugin.saveConfig(); }

    public boolean isGlow()               { return cfg.getBoolean("npc.glow", false); }
    public void setGlow(boolean v)        { cfg.set("npc.glow", v); plugin.saveConfig(); }

    public String getDefaultEntityType()  { return cfg.getString("npc.default-entity-type", "VILLAGER"); }
    public String getDefaultProfession()  { return cfg.getString("npc.default-profession", "NONE"); }
    public boolean isBaby()               { return cfg.getBoolean("npc.baby", false); }

    // ---------------- Chat behaviour ----------------
    public String  getChatFormat()       { return Text.color(cfg.getString("chat.format", "&8<&e{npc}&8> &f{msg}")); }
    public double  getCooldown()         { return cfg.getDouble("chat.cooldown", 2.0); }
    public boolean isTypingIndicator()   { return cfg.getBoolean("chat.typing-indicator", true); }
    public void setTypingIndicator(boolean v) { cfg.set("chat.typing-indicator", v); plugin.saveConfig(); }
    public int     getFocusDuration()    { return cfg.getInt("chat.focus-duration", 60); }

    public boolean isDebug()             { return cfg.getBoolean("debug", false); }
    public void setDebug(boolean v)      { cfg.set("debug", v); plugin.saveConfig(); }

    /** GUI filler material, falls back safely. */
    public Material getGuiFiller() {
        Material m = Material.matchMaterial(cfg.getString("gui.filler", "BLACK_STAINED_GLASS_PANE"));
        return m == null ? Material.BLACK_STAINED_GLASS_PANE : m;
    }

    // ---------------- Messages ----------------
    public String msg(String key) {
        String prefix = Text.color(cfg.getString("messages.prefix", "&8[&bSecondBrain&8] &r"));
        return prefix + msgRaw(key);
    }

    public String msgRaw(String key) {
        return Text.color(cfg.getString("messages." + key, "&cMissing message: " + key));
    }

    /** message with prefix + {placeholders} replaced pairwise. */
    public String msg(String key, String... replacements) {
        return apply(msg(key), replacements);
    }

    public String msgRaw(String key, String... replacements) {
        return apply(msgRaw(key), replacements);
    }

    private static String apply(String s, String... replacements) {
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            s = s.replace("{" + replacements[i] + "}", replacements[i + 1]);
        }
        return s;
    }

    /** Safe villager profession lookup. */
    public static Villager.Profession parseProfession(String s) {
        if (s == null) return null;
        try {
            return Villager.Profession.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
