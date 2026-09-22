package com.aditya.rag.service;

import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.aditya.rag.kafka.EmbeddingJobMessage;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class EmbeddingJobProducer {

    private final KafkaTemplate<Object, Object> kafkaTemplate;
    private final String topic;

    public EmbeddingJobProducer(
            KafkaTemplate<Object, Object> kafkaTemplate,
            @Value("${app.embedding.kafka.topic:embedding-jobs}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    public void publish(EmbeddingJobMessage message) {
        long startedAt = System.currentTimeMillis();
        log.debug("[embedding-kafka] publish start topic={} job={} chunk={}/{}",
                topic, message.jobId(), message.chunkIndex() + 1, message.totalChunks());
        try {
            kafkaTemplate.send(topic, message.chunkId(), message).get(30, TimeUnit.SECONDS);
            log.debug("[embedding-kafka] published topic={} job={} chunk={}/{} took={}ms",
                    topic, message.jobId(), message.chunkIndex() + 1, message.totalChunks(),
                    System.currentTimeMillis() - startedAt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[embedding-kafka] publish interrupted topic={} job={} chunk={}/{}",
                    topic, message.jobId(), message.chunkIndex() + 1, message.totalChunks());
            throw new IllegalStateException("Interrupted while publishing embedding job", e);
        } catch (Exception e) {
            log.error("[embedding-kafka] publish failed topic={} job={} chunk={}/{} took={}ms errorType={} reason={}",
                    topic, message.jobId(), message.chunkIndex() + 1, message.totalChunks(),
                    System.currentTimeMillis() - startedAt, e.getClass().getSimpleName(),
                    shortError(e), e);
            throw new IllegalStateException(
                    "Could not publish embedding job to Kafka: " + e.getMessage(), e);
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
