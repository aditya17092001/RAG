package com.aditya.rag.config;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wires the vector store to a SEPARATE Aiven PostgreSQL database (DB #2),
 * independent of the primary datasource used by JPA for login/relational
 * data (DB #1).
 *
 * <p>Because the vectors live in a different database, we cannot rely on
 * Spring AI's PgVector auto-configuration (which binds to the primary
 * {@code JdbcTemplate}). It is excluded in {@code LocalRagApplication}, and we
 * build the {@link PgVectorStore} by hand against a dedicated datasource here.</p>
 */
@Configuration
public class VectorStoreConfig {

    /**
     * Primary datasource (DB #1) for JPA/login/relational data. Declaring a
     * second DataSource bean below removes Spring Boot's automatic primary, so
     * we re-declare the main one explicitly from {@code spring.datasource.*}.
     * Binding url/username/password/driver-class-name via @Value ensures the
     * URL reaches Hikari's jdbcUrl (a bare DataSourceBuilder under
     * @ConfigurationProperties failed with "jdbcUrl is required"). Keeps the
     * profile-based H2 (local) / Postgres (prod) switch working.
     */
    @Bean
    @Primary
    public DataSource dataSource(
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username:}") String username,
            @Value("${spring.datasource.password:}") String password,
            @Value("${spring.datasource.driver-class-name}") String driverClassName) {
        HikariDataSource ds = DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .driverClassName(driverClassName)
                .url(url)
                .username(username)
                .password(password)
                .build();
        applyPoolResilience(ds);
        return ds;
    }

    /**
     * Applies pool settings suited to a small cloud instance + slow (free-tier)
     * remote DB: a generous connection timeout, no fail-fast at startup so a
     * cold DB doesn't abort boot, and a small pool.
     */
    private void applyPoolResilience(HikariDataSource ds) {
        ds.setConnectionTimeout(60000);        // wait up to 60s for a connection
        ds.setInitializationFailTimeout(-1);   // don't fail startup if DB is slow to wake
        ds.setMaximumPoolSize(3);              // small instance, keep the pool lean
    }

    /**
     * Dedicated datasource for the vector database (DB #2).
     * Intentionally NOT {@code @Primary} so JPA keeps using DB #1.
     */
    @Bean
    public DataSource vectorDataSource(
            @Value("${vector.datasource.url}") String url,
            @Value("${vector.datasource.username}") String username,
            @Value("${vector.datasource.password}") String password) {
        HikariDataSource ds = DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .driverClassName("org.postgresql.Driver")
                .url(url)
                .username(username)
                .password(password)
                .build();
        applyPoolResilience(ds);
        return ds;
    }

    /**
     * Primary JdbcTemplate bound to the LOGIN datasource (DB #1). This is what
     * Spring AI's JDBC chat-memory repository must use. Without an explicit
     * @Primary JdbcTemplate, auto-config could pick up the vector JdbcTemplate
     * (DB #2), causing chat-memory SQL to run against the vector database where
     * the spring_ai_chat_memory table does not exist ("relation does not exist").
     */
    @Bean
    @Primary
    public JdbcTemplate jdbcTemplate(@Qualifier("dataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /** JdbcTemplate bound to the vector datasource (DB #2). Non-primary. */
    @Bean
    public JdbcTemplate vectorJdbcTemplate(@Qualifier("vectorDataSource") DataSource vectorDataSource) {
        return new JdbcTemplate(vectorDataSource);
    }

    /**
     * The PgVector-backed {@link VectorStore}. Uses the injected embedding model
     * (Google Gemini text-embedding-004, 768 dims) and auto-creates the schema
     * + vector extension on the vector database.
     */
    @Bean
    public VectorStore vectorStore(
            @Qualifier("vectorJdbcTemplate") JdbcTemplate vectorJdbcTemplate,
            @Qualifier("googleGenAiTextEmbedding") EmbeddingModel embeddingModel) {
        return PgVectorStore.builder(vectorJdbcTemplate, embeddingModel)
                .dimensions(768) // must match the embedding model's output size
                .initializeSchema(true) // create vector_store table if absent
                .build();
    }
}
