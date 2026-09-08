package com.aditya.rag.dto;

import java.util.UUID;

public record EmbeddingQueueResult(
        UUID jobId,
        UUID documentId,
        String filename,
        int chunks,
        String visibility,
        UUID owner,
        String status) {
}
