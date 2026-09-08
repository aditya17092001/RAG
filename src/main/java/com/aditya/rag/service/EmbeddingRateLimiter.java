package com.aditya.rag.service;

import java.util.concurrent.locks.LockSupport;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Limits calls made by the single embedding consumer. This is deliberately
 * application-level throttling because Kafka throughput is unrelated to the
 * Gemini provider's request-per-minute quota.
 */
@Component
@Slf4j
public class EmbeddingRateLimiter {

    private final long intervalMillis;
    private final long intervalNanos;
    private long nextAllowedNanos;

    public EmbeddingRateLimiter(
            @Value("${app.embedding.min-interval-ms:2000}") long intervalMs) {
        this.intervalMillis = Math.max(0L, intervalMs);
        this.intervalNanos = intervalMillis * 1_000_000L;
        log.info("[embedding-rate-limit] configured intervalMs={}", intervalMillis);
    }

    public synchronized void acquire() {
        long now = System.nanoTime();
        long waitNanos = nextAllowedNanos - now;
        if (waitNanos > 0) {
            long waitMillis = Math.max(1L, waitNanos / 1_000_000L);
            log.debug("[embedding-rate-limit] waiting approximately {}ms before next provider call",
                    waitMillis);
            LockSupport.parkNanos(waitNanos);
        }
        nextAllowedNanos = Math.max(System.nanoTime(), nextAllowedNanos) + intervalNanos;
        log.debug("[embedding-rate-limit] permit granted intervalMs={}", intervalMillis);
    }
}
