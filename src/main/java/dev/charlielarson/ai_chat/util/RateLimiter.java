package dev.charlielarson.ai_chat.util;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class RateLimiter {
    private final Map<UUID, Long> last = new ConcurrentHashMap<>();
    private final int cooldownSeconds;

    public RateLimiter(int cooldownSeconds) {
        this.cooldownSeconds = cooldownSeconds;
    }

    public boolean tryAcquire(UUID id) {
        long now = Instant.now().getEpochSecond();
        Long prev = last.get(id);
        if (prev == null || now - prev >= cooldownSeconds) {
            last.put(id, now);
            return true;
        }
        return false;
    }

    public long remaining(UUID id) {
        long now = Instant.now().getEpochSecond();
        Long prev = last.get(id);
        if (prev == null)
            return 0;
        long elapsed = now - prev;
        long remain = cooldownSeconds - elapsed;
        return Math.max(0, remain);
    }
}