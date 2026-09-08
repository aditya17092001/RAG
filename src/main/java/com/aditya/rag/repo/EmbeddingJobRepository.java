package com.aditya.rag.repo;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.aditya.rag.entity.EmbeddingJob;

public interface EmbeddingJobRepository extends JpaRepository<EmbeddingJob, UUID> {
    Optional<EmbeddingJob> findByIdAndOwnerId(UUID id, UUID ownerId);
}
