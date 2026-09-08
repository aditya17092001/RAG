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
        try {
            jobService.process(message);
        } catch (RuntimeException e) {
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
        try {
            jobService.markDltFailure(message,
                    new IllegalStateException("Embedding failed after Kafka retries"));
        } catch (RuntimeException e) {
            log.error("[embedding-dlt] could not persist terminal failure for job={} chunk={}",
                    message.jobId(), message.chunkIndex(), e);
        }
    }
}
