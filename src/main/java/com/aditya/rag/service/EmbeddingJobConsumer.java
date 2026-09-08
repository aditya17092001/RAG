package com.aditya.rag.service;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.aditya.rag.kafka.EmbeddingJobMessage;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class EmbeddingJobConsumer {

    private final EmbeddingJobService jobService;

    public EmbeddingJobConsumer(EmbeddingJobService jobService) {
        this.jobService = jobService;
    }

    @KafkaListener(
            topics = "${app.embedding.kafka.topic:embedding-jobs}",
            groupId = "${spring.kafka.consumer.group-id:rag-embedding}",
            containerFactory = "embeddingKafkaListenerContainerFactory")
    public void consume(EmbeddingJobMessage message) {
        long startedAt = System.currentTimeMillis();
        log.debug("[embedding-kafka] received job={} chunk={}/{} thread={}",
                message.jobId(), message.chunkIndex() + 1, message.totalChunks(),
                Thread.currentThread().getName());
        try {
            jobService.process(message);
            log.debug("[embedding-kafka] processed job={} chunk={}/{} took={}ms",
                    message.jobId(), message.chunkIndex() + 1, message.totalChunks(),
                    System.currentTimeMillis() - startedAt);
        } catch (RuntimeException e) {
            log.warn("[embedding-kafka] processing failed job={} chunk={}/{} took={}ms errorType={} reason={}",
                    message.jobId(), message.chunkIndex() + 1, message.totalChunks(),
                    System.currentTimeMillis() - startedAt, e.getClass().getSimpleName(),
                    shortError(e));
            jobService.markRetryableFailure(message, e);
            throw e;
        }
    }

    @KafkaListener(
            topics = "${app.embedding.kafka.dlt-topic:embedding-jobs.DLT}",
            groupId = "${app.embedding.kafka.dlt-group:rag-embedding-dlt}",
            containerFactory = "embeddingKafkaListenerContainerFactory")
    public void consumeDeadLetter(EmbeddingJobMessage message) {
        // The DefaultErrorHandler has already exhausted the configured retries.
        // Do not throw here, otherwise the DLT would be published back to itself.
        log.error("[embedding-dlt] received terminal record job={} chunk={}/{}; marking FAILED",
                message.jobId(), message.chunkIndex() + 1, message.totalChunks());
        try {
            jobService.markDltFailure(message,
                    new IllegalStateException("Embedding failed after Kafka retries"));
            log.info("[embedding-dlt] persisted terminal failure job={} chunk={}/{}",
                    message.jobId(), message.chunkIndex() + 1, message.totalChunks());
        } catch (RuntimeException e) {
            log.error("[embedding-dlt] could not persist terminal failure for job={} chunk={} errorType={} reason={}",
                    message.jobId(), message.chunkIndex() + 1, e.getClass().getSimpleName(),
                    shortError(e), e);
        }
    }

    private String shortError(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            message = error.getClass().getSimpleName();
        }
        return message.length() > 300 ? message.substring(0, 300) : message;
    }
}
