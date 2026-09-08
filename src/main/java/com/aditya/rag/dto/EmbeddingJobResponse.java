package com.aditya.rag.dto;

import java.time.Instant;
import java.util.UUID;

import com.aditya.rag.entity.EmbeddingJob;

public record EmbeddingJobResponse(
        UUID jobId,
        UUID documentId,
        String filename,
        String visibility,
        int totalChunks,
        int completedChunks,
        int failedChunks,
        String status,
        String lastError,
        Instant createdAt,
        Instant updatedAt) {

    public static EmbeddingJobResponse from(EmbeddingJob job) {
        return new EmbeddingJobResponse(
                job.getId(),
                job.getDocumentId(),
                job.getFilename(),
                job.getVisibility(),
                job.getTotalChunks(),
                job.getCompletedChunks(),
                job.getFailedChunks(),
                job.getStatus().name(),
                job.getLastError(),
                job.getCreatedAt(),
                job.getUpdatedAt());
    }
}
