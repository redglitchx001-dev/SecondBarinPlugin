package me.sailex.secondbrain.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.sailex.secondbrain.SecondBrainPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Talks to any OpenAI-compatible /chat/completions endpoint. Zero dependencies. */
public class LLMClient {

    /** One finished AI request. */
    public record LLMResult(String reply, boolean error, long latencyMs) {}

    private static final int MAX_USER_MESSAGE_LENGTH = 500;

    private final SecondBrainPlugin plugin;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "SecondBrain-LLM");
        t.setDaemon(true);
        return t;
    });
    private HttpClient http;

    public LLMClient(SecondBrainPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        int timeout = Math.max(5, plugin.getConfigManager().getTimeout());
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(Math.min(15, timeout)))
                .build();
    }

    public void shutdown() {
        pool.shutdownNow();
    }

    /** Sends one chat request asynchronously. Never throws; errors come back inside the result. */
    public CompletableFuture<LLMResult> chat(String systemPrompt, String playerName, String message,
                                             List<Map<String, String>> history) {
        long start = System.currentTimeMillis();
        return CompletableFuture.supplyAsync(() -> {
            try {
                var cm = plugin.getConfigManager();
                if (!cm.isKeyConfigured()) {
                    return new LLMResult(cm.msgRaw("no-key"), true, 0);
                }

                String apiUrl = cm.getApiUrl().replaceAll("/+$", "") + "/chat/completions";

                JsonObject payload = new JsonObject();
                payload.addProperty("model", cm.getApiModel());
                payload.addProperty("max_tokens", cm.getMaxTokens());
                payload.addProperty("temperature", cm.getTemperature());

                JsonArray msgs = new JsonArray();
                msgs.add(makeMsg("system", systemPrompt));
                for (Map<String, String> m : history) {
                    msgs.add(makeMsg(m.get("role"), m.get("content")));
                }
                String userContent = playerName + ": " + truncate(message);
                msgs.add(makeMsg("user", userContent));
                payload.add("messages", msgs);

                if (cm.isDebug()) {
                    plugin.getLogger().info("[AI ->] " + apiUrl + " model=" + cm.getApiModel()
                            + " msgs=" + msgs.size());
                }

                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(apiUrl))
                        .timeout(Duration.ofSeconds(cm.getTimeout()))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + cm.getApiKey())
                        .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                        .build();

                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                long latency = System.currentTimeMillis() - start;

                if (resp.statusCode() == 200) {
                    String reply = extractReply(resp.body());
                    if (reply == null) {
                        return new LLMResult("\u00a7c[SecondBrain] Empty reply from AI.", true, latency);
                    }
                    if (cm.isDebug()) {
                        plugin.getLogger().info("[AI <-] (" + latency + "ms) " + reply);
                    }
                    return new LLMResult(reply, false, latency);
                }

                if (cm.isDebug()) {
                    plugin.getLogger().warning("[AI] HTTP " + resp.statusCode() + ": " + abbreviate(resp.body(), 200));
                }
                String hint = switch (resp.statusCode()) {
                    case 401, 403 -> "Bad or missing API key.";
                    case 404 -> "Model not found - check /sb setmodel.";
                    case 429 -> "Rate limited - try again shortly.";
                    default -> "Endpoint rejected the request.";
                };
                return new LLMResult("\u00a7c[AI Error " + resp.statusCode() + "] " + hint, true, latency);

            } catch (java.net.http.HttpTimeoutException e) {
                return new LLMResult("\u00a7c[SecondBrain] AI request timed out.", true,
                        System.currentTimeMillis() - start);
            } catch (Exception e) {
                if (plugin.getConfigManager().isDebug()) {
                    plugin.getLogger().warning("[AI] Exception: " + e);
                }
                return new LLMResult("\u00a7c[SecondBrain] Network error reaching the AI server.", true,
                        System.currentTimeMillis() - start);
            }
        }, pool);
    }

    /** Blocking connectivity probe used by /sb status. Returns null when OK, else an error. */
    public String testConnection() {
        try {
            var cm = plugin.getConfigManager();
            if (!cm.isKeyConfigured()) return "API key not configured";

            String apiUrl = cm.getApiUrl().replaceAll("/+$", "") + "/chat/completions";
            JsonObject payload = new JsonObject();
            payload.addProperty("model", cm.getApiModel());
            payload.addProperty("max_tokens", 5);
            JsonArray msgs = new JsonArray();
            msgs.add(makeMsg("user", "ping"));
            payload.add("messages", msgs);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(Math.min(10, cm.getTimeout())))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + cm.getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200 ? null : "HTTP " + resp.statusCode();
        } catch (Exception e) {
            return e.getClass().getSimpleName();
        }
    }

    private static String extractReply(String body) {
        try {
            JsonElement el = JsonParser.parseString(body).getAsJsonObject()
                    .getAsJsonArray("choices").get(0).getAsJsonObject()
                    .getAsJsonObject("message").get("content");
            return el == null || el.isJsonNull() ? null : el.getAsString().trim();
        } catch (Exception e) {
            return null;
        }
    }

    private static JsonObject makeMsg(String role, String content) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("content", content);
        return o;
    }

    private static String truncate(String s) {
        return s.length() <= MAX_USER_MESSAGE_LENGTH ? s : s.substring(0, MAX_USER_MESSAGE_LENGTH);
    }

    private static String abbreviate(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max) + "...");
    }
}
