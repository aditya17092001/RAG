package com.aditya.rag.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "embedding_chunks")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmbeddingChunk {

    @Id
    @Column(length = 80)
    private String id;

    @Column(nullable = false)
    private UUID jobId;

    @Column(nullable = false)
    private UUID documentId;

    @Column(nullable = false)
    private UUID ownerId;

    @Column(nullable = false)
    private int chunkIndex;

    @Column(nullable = false)
    private int totalChunks;

    @Column(name = "chunk_text", nullable = false, columnDefinition = "TEXT")
    private String text;

    @Column(nullable = false)
    private String filename;

    @Column(nullable = false, length = 32)
    private String fileType;

    @Column(nullable = false, length = 16)
    private String visibility;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EmbeddingChunkStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(length = 2000)
    private String lastError;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
