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
        try {
            kafkaTemplate.send(topic, message.chunkId(), message).get(30, TimeUnit.SECONDS);
            log.debug("[embedding-kafka] published job={} chunk={}/{}",
                    message.jobId(), message.chunkIndex() + 1, message.totalChunks());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing embedding job", e);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not publish embedding job to Kafka: " + e.getMessage(), e);
        }
    }
}
