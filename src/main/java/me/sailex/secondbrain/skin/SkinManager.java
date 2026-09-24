package me.sailex.secondbrain.skin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.sailex.secondbrain.SecondBrainPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Fetches player-skin textures from Mojang and caches the resulting skull ItemStack.
 * Uses ONLY Paper 1.21 public API (PlayerProfile / PlayerTextures) — no NMS, no NMS, no dependencies.
 */
public class SkinManager {

    private static final String MOJANG_PROFILE = "https://api.mojang.com/users/profiles/minecraft/";
    private static final String MOJANG_SESSION = "https://sessionserver.mojang.com/session/minecraft/profile/";
    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final String STEVE_TEXTURE =
            "https://textures.minecraft.net/texture/1a4af718455d4aab528e7a61f86fa25e6a369d1768dcb13f7df319a713eb810b";

    private final SecondBrainPlugin plugin;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "SecondBrain-Skin");
        t.setDaemon(true);
        return t;
    });
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    /** skin-name lowercased -> built skull item (hot cache). */
    private final Map<String, ItemStack> cache = new ConcurrentHashMap<>();
    /** Currently in-flight fetches, de-duplicated. */
    private final Map<String, CompletableFuture<ItemStack>> pending = new ConcurrentHashMap<>();

    public SkinManager(SecondBrainPlugin plugin) {
        this.plugin = plugin;
    }

    public void shutdown() {
        pool.shutdownNow();
    }

    /** Validates a skin-name format. */
    public static boolean validName(String name) {
        return name != null && NAME_PATTERN.matcher(name).matches();
    }

    /**
     * Builds a player-head ItemStack wearing the given player's skin.
     * Returns immediately with a default skull if the skin is not cached, then
     * asynchronously fetches the real skin. Callers should re-apply the head in the callback
     * if they want the final texture.
     *
     * @param skinName Minecraft player name (case-insensitive)
     * @param onReady  called on the main thread once the real skull is ready; may be null
     * @return a skull item (either the cached texture or the default placeholder)
     */
    public ItemStack getSkull(String skinName, Runnable onReady) {
        if (skinName == null || skinName.isBlank()) {
            return defaultSkull();
        }
        String key = skinName.toLowerCase(java.util.Locale.ROOT);
        ItemStack cached = cache.get(key);
        if (cached != null) {
            return cached.clone();
        }
        // Kick off async fetch if not already running.
        pending.computeIfAbsent(key, k -> fetch(k).whenComplete((item, err) -> {
            pending.remove(k);
            if (item != null) cache.put(k, item);
            if (onReady != null) {
                Bukkit.getScheduler().runTask(plugin, onReady);
            }
        }));
        // Return a placeholder (Steve) until the fetch completes.
        return placeholderSkull(skinName);
    }

    /** Synchronously returns cached skull, or null. */
    public ItemStack getCached(String skinName) {
        if (skinName == null) return null;
        ItemStack it = cache.get(skinName.toLowerCase(java.util.Locale.ROOT));
        return it == null ? null : it.clone();
    }

    /** Invalidates the cache so a fresh fetch happens next time. */
    public void invalidate(String skinName) {
        if (skinName != null) cache.remove(skinName.toLowerCase(java.util.Locale.ROOT));
    }

    // ------------------------------------------------------------
    //  Async fetch pipeline
    // ------------------------------------------------------------

    private CompletableFuture<ItemStack> fetch(String name) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 1) Look up UUID
                HttpRequest uuidReq = HttpRequest.newBuilder()
                        .uri(URI.create(MOJANG_PROFILE + name))
                        .timeout(Duration.ofSeconds(8))
                        .build();
                HttpResponse<String> uuidResp = http.send(uuidReq, HttpResponse.BodyHandlers.ofString());
                if (uuidResp.statusCode() != 200) {
                    plugin.getLogger().warning("[Skin] Could not find player '" + name + "' (HTTP " + uuidResp.statusCode() + ")");
                    return defaultSkull();
                }
                JsonObject uuidJson = JsonParser.parseString(uuidResp.body()).getAsJsonObject();
                String id = uuidJson.get("id").getAsString();
                String actualName = uuidJson.has("name") ? uuidJson.get("name").getAsString() : name;

                // 2) Look up profile (unsigned) for textures
                HttpRequest profReq = HttpRequest.newBuilder()
                        .uri(URI.create(MOJANG_SESSION + id + "?unsigned=false"))
                        .timeout(Duration.ofSeconds(8))
                        .build();
                HttpResponse<String> profResp = http.send(profReq, HttpResponse.BodyHandlers.ofString());
                if (profResp.statusCode() != 200) return defaultSkull();

                String textureUrl = extractTextureUrl(profResp.body());
                if (textureUrl == null) textureUrl = STEVE_TEXTURE;

                // 3) Build a Bukkit PlayerProfile with the texture.
                UUID offlineId = uuidFromDashless(id);
                PlayerProfile profile = Bukkit.createPlayerProfile(offlineId, actualName);
                PlayerTextures textures = profile.getTextures();
                textures.setSkin(new URL(textureUrl), PlayerTextures.SkinModel.CLASSIC);
                profile.setTextures(textures);

                ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
                SkullMeta meta = (SkullMeta) skull.getItemMeta();
                meta.setOwnerProfile(profile);
                meta.setDisplayName("\u00a7e" + actualName);
                skull.setItemMeta(meta);
                return skull;
            } catch (Exception e) {
                if (plugin.getConfigManager().isDebug()) {
                    plugin.getLogger().warning("[Skin] Failed to fetch '" + name + "': " + e);
                }
                return defaultSkull();
            }
        }, pool);
    }

    private static String extractTextureUrl(String profileJson) {
        try {
            JsonObject obj = JsonParser.parseString(profileJson).getAsJsonObject();
            JsonArray props = obj.getAsJsonArray("properties");
            for (JsonElement el : props) {
                JsonObject p = el.getAsJsonObject();
                if (!"textures".equals(p.get("name").getAsString())) continue;
                String b64 = p.get("value").getAsString();
                String decoded = new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
                JsonObject tex = JsonParser.parseString(decoded).getAsJsonObject()
                        .getAsJsonObject("textures").getAsJsonObject("SKIN");
                return tex.get("url").getAsString();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static UUID uuidFromDashless(String s) {
        if (s == null || s.length() != 32) return UUID.randomUUID();
        String dashed = s.substring(0, 8) + "-" + s.substring(8, 12) + "-"
                + s.substring(12, 16) + "-" + s.substring(16, 20) + "-" + s.substring(20);
        return UUID.fromString(dashed);
    }

    // ------------------------------------------------------------
    //  Skull helpers
    // ------------------------------------------------------------

    private static ItemStack defaultSkull() {
        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) skull.getItemMeta();
        meta.setDisplayName("\u00a7eSteve");
        skull.setItemMeta(meta);
        return skull;
    }

    private static ItemStack placeholderSkull(String name) {
        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) skull.getItemMeta();
        meta.setDisplayName("\u00a77" + name + " \u00a78(loading...)");
        skull.setItemMeta(meta);
        return skull;
    }
}
