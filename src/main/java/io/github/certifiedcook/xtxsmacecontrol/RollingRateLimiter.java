package io.github.certifiedcook.xtxsmacecontrol;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class RollingRateLimiter {
    private final Map<UUID, Deque<Long>> hits = new HashMap<>();

    public synchronized boolean allow(UUID player, int maxPerMinute) {
        if (maxPerMinute < 0) return true;
        long now = System.currentTimeMillis();
        long cutoff = now - 60_000L;
        Deque<Long> queue = hits.computeIfAbsent(player, ignored -> new ArrayDeque<>());
        while (!queue.isEmpty() && queue.peekFirst() < cutoff) queue.removeFirst();
        if (queue.size() >= maxPerMinute) return false;
        queue.addLast(now);
        return true;
    }

    public synchronized int current(UUID player) {
        Deque<Long> queue = hits.get(player);
        return queue == null ? 0 : queue.size();
    }
}
