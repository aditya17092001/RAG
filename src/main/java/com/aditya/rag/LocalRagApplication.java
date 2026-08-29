package com.aditya.rag;

import org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// Exclude PgVector auto-config: it registers its own 'vectorStore' bean bound
// to the primary datasource, which collides with the manual PgVectorStore bean
// in VectorStoreConfig (wired to the separate vector database, DB #2).
@SpringBootApplication(exclude = PgVectorStoreAutoConfiguration.class)
public class LocalRagApplication {

	public static void main(String[] args) {
		SpringApplication.run(LocalRagApplication.class, args);
	}

}
