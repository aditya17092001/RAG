package com.aditya.rag.repo;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.aditya.rag.entity.EmbeddingChunk;
import com.aditya.rag.entity.EmbeddingChunkStatus;

public interface EmbeddingChunkRepository extends JpaRepository<EmbeddingChunk, String> {
    long countByJobIdAndStatus(UUID jobId, EmbeddingChunkStatus status);
}
