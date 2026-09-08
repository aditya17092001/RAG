package com.aditya.rag.config;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.config.SslConfigs;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

import com.aditya.rag.kafka.EmbeddingJobMessage;

import lombok.extern.slf4j.Slf4j;

@Configuration
@EnableKafka
@Slf4j
public class EmbeddingKafkaConfig {

    @Bean
    public ProducerFactory<Object, Object> embeddingProducerFactory(Environment environment) {
        Map<String, Object> properties = kafkaProperties(environment);
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        return new DefaultKafkaProducerFactory<>(properties);
    }

    @Bean
    public KafkaTemplate<Object, Object> embeddingKafkaTemplate(
            ProducerFactory<Object, Object> embeddingProducerFactory) {
        return new KafkaTemplate<>(embeddingProducerFactory);
    }

    @Bean
    public ConsumerFactory<String, EmbeddingJobMessage> embeddingConsumerFactory(Environment environment) {
        Map<String, Object> properties = kafkaProperties(environment);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG,
                environment.getProperty("spring.kafka.consumer.group-id", "rag-embedding"));
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                environment.getProperty("spring.kafka.consumer.auto-offset-reset", "earliest"));
        properties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG,
                environment.getProperty("spring.kafka.consumer.max-poll-records", "1"));
        properties.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG,
                environment.getProperty("spring.kafka.consumer.properties.allow.auto.create.topics",
                        Boolean.class, false));
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        JsonDeserializer<EmbeddingJobMessage> valueDeserializer =
                new JsonDeserializer<>(EmbeddingJobMessage.class);
        valueDeserializer.addTrustedPackages("com.aditya.rag.kafka");

        return new DefaultKafkaConsumerFactory<>(
                properties,
                new StringDeserializer(),
                valueDeserializer);
    }

    @Bean
    public DefaultErrorHandler embeddingErrorHandler(
            KafkaTemplate<Object, Object> kafkaTemplate,
            Environment environment) {
        String dltTopic = environment.getProperty(
                "app.embedding.kafka.dlt-topic", "embedding-jobs.DLT");
        long retryInterval = environment.getProperty(
                "app.embedding.retry.interval-ms", Long.class, 60_000L);
        long retryAttempts = environment.getProperty(
                "app.embedding.retry.max-attempts", Long.class, 4L);

        // The DLT has one partition on the Aiven free tier, so always route
        // failed records to partition 0 instead of mirroring the source partition.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) -> new TopicPartition(dltTopic, 0));

        FixedBackOff backOff = new FixedBackOff(retryInterval, retryAttempts);
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
        // Bad payloads and missing job state are not transient provider failures.
        errorHandler.addNotRetryableExceptions(IllegalArgumentException.class);
        log.info("[embedding-kafka] error handler configured dltTopic={} retryIntervalMs={} retryAttempts={}",
                dltTopic, retryInterval, retryAttempts);
        return errorHandler;
    }

    @Bean(name = "embeddingKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, EmbeddingJobMessage>
            embeddingKafkaListenerContainerFactory(
                    ConsumerFactory<String, EmbeddingJobMessage> embeddingConsumerFactory,
                    DefaultErrorHandler embeddingErrorHandler,
                    Environment environment) {
        ConcurrentKafkaListenerContainerFactory<String, EmbeddingJobMessage> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(embeddingConsumerFactory);
        factory.setCommonErrorHandler(embeddingErrorHandler);
        factory.setConcurrency(environment.getProperty(
                "app.embedding.kafka.concurrency", Integer.class, 1));
        return factory;
    }

    private Map<String, Object> kafkaProperties(Environment environment) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG,
                environment.getRequiredProperty("spring.kafka.bootstrap-servers"));
        properties.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG,
                environment.getRequiredProperty("spring.kafka.security.protocol"));
        properties.put(SaslConfigs.SASL_MECHANISM,
                environment.getRequiredProperty("spring.kafka.properties.sasl.mechanism"));
        properties.put(SaslConfigs.SASL_JAAS_CONFIG,
                environment.getRequiredProperty("spring.kafka.properties.sasl.jaas.config"));
        properties.put(SslConfigs.SSL_TRUSTSTORE_TYPE_CONFIG,
                environment.getRequiredProperty("spring.kafka.properties.ssl.truststore.type"));
        properties.put(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG,
                environment.getRequiredProperty("spring.kafka.properties.ssl.truststore.location"));
        return properties;
    }
}
