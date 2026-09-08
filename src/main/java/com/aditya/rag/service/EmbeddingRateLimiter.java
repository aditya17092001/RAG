package com.aditya.rag.service;

import java.util.concurrent.locks.LockSupport;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Limits calls made by the single embedding consumer. This is deliberately
 * application-level throttling because Kafka throughput is unrelated to the
 * Gemini provider's request-per-minute quota.
 */
@Component
public class EmbeddingRateLimiter {

    private final long intervalNanos;
    private long nextAllowedNanos;

    public EmbeddingRateLimiter(
            @Value("${app.embedding.min-interval-ms:1000}") long intervalMs) {
        this.intervalNanos = Math.max(0L, intervalMs) * 1_000_000L;
    }

    public synchronized void acquire() {
        long now = System.nanoTime();
        long waitNanos = nextAllowedNanos - now;
        if (waitNanos > 0) {
            LockSupport.parkNanos(waitNanos);
        }
        nextAllowedNanos = Math.max(System.nanoTime(), nextAllowedNanos) + intervalNanos;
    }
}
