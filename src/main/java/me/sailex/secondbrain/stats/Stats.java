package me.sailex.secondbrain.stats;

import java.util.concurrent.atomic.AtomicLong;

/** Simple in-memory session statistics. */
public class Stats {

    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong replies = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final AtomicLong totalLatencyMs = new AtomicLong();
    private final long startedAt = System.currentTimeMillis();

    public void recordDispatch() { requests.incrementAndGet(); }

    public void recordReply(long latencyMs) {
        replies.incrementAndGet();
        totalLatencyMs.addAndGet(Math.max(0, latencyMs));
    }

    public void recordError() {
        errors.incrementAndGet();
    }

    public long getRequests() { return requests.get(); }
    public long getReplies() { return replies.get(); }
    public long getErrors() { return errors.get(); }
    public long getUptimeMillis() { return System.currentTimeMillis() - startedAt; }

    /** Average reply latency formatted, or "-" when nothing was served yet. */
    public String avgLatency() {
        long n = replies.get();
        if (n == 0) return "-";
        long avg = totalLatencyMs.get() / n;
        return avg < 1000 ? avg + "ms" : String.format("%.2fs", avg / 1000.0);
    }
}
