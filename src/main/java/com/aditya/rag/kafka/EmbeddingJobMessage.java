package com.aditya.rag.kafka;

import java.util.UUID;

/**
 * JSON payload sent once for every document chunk. It is intentionally
 * self-contained so a consumer does not need access to uploaded files.
 */
public record EmbeddingJobMessage(
        UUID jobId,
        UUID documentId,
        String chunkId,
        int chunkIndex,
        int totalChunks,
        String text,
        String filename,
        String fileType,
        UUID ownerId,
        String visibility) {
}
