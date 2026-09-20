package me.sailex.secondbrain.util;

/** Small string/formatting helpers shared across the plugin. */
public final class Text {

    private Text() {}

    /** Translates & color codes to § codes. */
    public static String color(String s) {
        return s == null ? "" : s.replace("&", "\u00a7");
    }

    /** Masks an API key for safe display: sk-abc123xyz -> sk-a***xyz */
    public static String maskKey(String key) {
        if (key == null || key.isBlank()) return "(empty)";
        if (key.equals("PUT_YOUR_API_KEY_HERE")) return "(not set)";
        if (key.length() <= 8) return "***";
        return key.substring(0, 4) + "***" + key.substring(key.length() - 3);
    }

    /** Formats milliseconds as e.g. "1h 4m 12s". */
    public static String uptime(long millis) {
        long sec = millis / 1000L;
        long d = sec / 86400; sec %= 86400;
        long h = sec / 3600;  sec %= 3600;
        long m = sec / 60;    long s = sec % 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("d ");
        if (h > 0) sb.append(h).append("h ");
        if (m > 0) sb.append(m).append("m ");
        sb.append(s).append("s");
        return sb.toString();
    }

    /** Rough compass direction from one yaw to a target. */
    public static String direction(double playerYaw, double dx, double dz) {
        double angle = Math.toDegrees(Math.atan2(dz, dx)); // -180..180, 0 = +X
        double rel = ((angle - (-playerYaw)) % 360 + 360) % 360;
        // Minecraft yaw: 0 = +Z(south), 90 = -X(west)... keep it simple:
        if (rel < 22.5 || rel >= 337.5) return "\u2191";   // up arrow
        if (rel < 67.5)  return "\u2197";
        if (rel < 112.5) return "\u2192";
        if (rel < 157.5) return "\u2198";
        if (rel < 202.5) return "\u2193";
        if (rel < 247.5) return "\u2199";
        if (rel < 292.5) return "\u2190";
        return "\u2196";
    }

    /** Parses a boolean-ish word; returns null when unrecognized. */
    public static Boolean parseBool(String s) {
        if (s == null) return null;
        switch (s.toLowerCase()) {
            case "true", "on", "yes", "1", "enable", "enabled" -> { return true; }
            case "false", "off", "no", "0", "disable", "disabled" -> { return false; }
            default -> { return null; }
        }
    }

    /** Formats a double nicely (drops trailing .0). */
    public static String num(double d) {
        if (d == Math.floor(d) && !Double.isInfinite(d)) return String.valueOf((long) d);
        return String.format("%.1f", d);
    }
}
