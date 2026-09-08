package com.aditya.rag.controller;

import java.util.UUID;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aditya.rag.dto.EmbeddingJobResponse;
import com.aditya.rag.service.EmbeddingJobService;

@RestController
@RequestMapping("/embedding-jobs")
public class EmbeddingJobController {

    private final EmbeddingJobService jobService;

    public EmbeddingJobController(EmbeddingJobService jobService) {
        this.jobService = jobService;
    }

    @GetMapping("/{jobId}")
    public EmbeddingJobResponse getJob(@PathVariable UUID jobId) {
        UUID ownerId = UUID.fromString(
                SecurityContextHolder.getContext().getAuthentication().getName());
        return EmbeddingJobResponse.from(jobService.getOwnedJob(jobId, ownerId));
    }
}
