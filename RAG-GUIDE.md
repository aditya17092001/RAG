# Building a Local RAG Application with Spring AI + Ollama + ChromaDB

A step-by-step guide to building a Retrieval-Augmented Generation (RAG) system that runs entirely on your machine using Spring Boot, Spring AI, Ollama, and ChromaDB for persistent vector storage.

---

## What is RAG?

RAG combines two things:
1. **Retrieval** — find relevant chunks of your own documents based on a user's question
2. **Generation** — feed those chunks as context to an LLM so it answers using *your* data, not just its training knowledge

```
User Question
     |
     v
[Embed the question] --> [Search ChromaDB] --> [Get top-K relevant chunks]
                                                        |
                                                        v
                                           [Build prompt with context + question]
                                                        |
                                                        v
                                                 [LLM generates answer]
```

---

## Prerequisites

- **Java 21** installed
- **Python** installed (for running ChromaDB server)
- **Ollama** installed and running locally (https://ollama.com)
- Pull the models you'll use:
  ```bash
  ollama pull llama3.2
  ollama pull nomic-embed-text
  ```
  - `llama3.2` — the chat/generation model
  - `nomic-embed-text` — the embedding model (converts text to vectors)

---

## Step 1: Install and Run ChromaDB

ChromaDB is an open-source vector database. It runs as a standalone server and persists data to disk.

### 1.1 Install ChromaDB

```bash
pip install chromadb
```

### 1.2 Start ChromaDB server

```bash
chroma run --host localhost --port 8000 --path ./chroma-data
```

- `--host localhost` — only accessible from your machine
- `--port 8000` — default port Spring AI expects
- `--path ./chroma-data` — where vectors are stored on disk (persists across restarts)

You should see output like:
```
Starting Chroma server...
Running Chroma server on http://localhost:8000
```

> **Keep this terminal open.** ChromaDB needs to be running while your Spring app is running.

### 1.3 Verify it's running

Open a browser: http://localhost:8000/api/v1/heartbeat

You should see something like: `{"nanosecond heartbeat": 1234567890}`

---

## Step 2: Understand the Project Structure

Your project already has:
```
local-rag/
├── pom.xml                          <-- Maven dependencies
├── src/main/java/com/aditya/rag/
│   └── LocalRagApplication.java     <-- Spring Boot entry point
├── src/main/resources/
│   ├── application.properties       <-- Configuration
│   └── DSA.txt                      <-- Your knowledge document
```

We will add:
```
src/main/java/com/aditya/rag/
├── service/
│   └── DataIngestionService.java    <-- Loads & chunks documents into ChromaDB
└── controller/
    └── RagController.java           <-- REST API endpoint
```

No manual `VectorStore` bean config needed — the ChromaDB starter auto-configures it.

---

## Step 3: Add Dependencies to `pom.xml`

Add these inside `<dependencies>`:

```xml
<!-- ChromaDB vector store (persistent vector storage) -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-vector-store-chroma</artifactId>
</dependency>

<!-- Tika document reader (reads .txt, .pdf, .docx, etc.) -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-tika-document-reader</artifactId>
</dependency>

<!-- QuestionAnswerAdvisor for RAG (separate module in Spring AI 2.0) -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-vector-store-advisor</artifactId>
</dependency>
```

### What each dependency does:

| Dependency | Purpose |
|-----------|---------|
| `spring-ai-starter-model-ollama` | Connects to Ollama for chat + embeddings |
| `spring-ai-starter-vector-store-chroma` | Connects to ChromaDB for persistent vector storage |
| `spring-ai-tika-document-reader` | Reads documents (txt, pdf, docx) into Spring AI's `Document` format |
| `spring-ai-vector-store-advisor` | Provides `QuestionAnswerAdvisor` for RAG (separate module in Spring AI 2.0) |

### Why ChromaDB?

| Feature | SimpleVectorStore | ChromaDB |
|---------|-------------------|----------|
| Persistence | In-memory only (lost on restart) | Stored on disk (survives restarts) |
| Setup | None | `pip install chromadb` + run server |
| Scalability | Small datasets | Handles large collections |
| Metadata filtering | Basic | Full metadata-based filtering |
| Built for | Prototyping | Production vector search |

---

## Step 4: Configure `application.properties`

```properties
spring.application.name=local-rag

# Ollama connection
spring.ai.ollama.base-url=http://localhost:11434

# Chat model configuration
spring.ai.ollama.chat.model=llama3.2

# Embedding model configuration
spring.ai.ollama.embedding.model=nomic-embed-text

# ChromaDB connection
spring.ai.vectorstore.chroma.client.host=http://localhost
spring.ai.vectorstore.chroma.client.port=8000

# Collection name (like a "table" in ChromaDB)
spring.ai.vectorstore.chroma.collection-name=dsa-knowledge

# Auto-create collection if it doesn't exist
spring.ai.vectorstore.chroma.initialize-schema=true
```

### What each property does:

| Property | Purpose |
|----------|---------|
| `spring.ai.ollama.base-url` | Where Ollama is running |
| `spring.ai.ollama.chat.model` | Which model generates answers |
| `spring.ai.ollama.embedding.model` | Which model creates embeddings |
| `spring.ai.vectorstore.chroma.client.host` | ChromaDB server host |
| `spring.ai.vectorstore.chroma.client.port` | ChromaDB server port |
| `spring.ai.vectorstore.chroma.collection-name` | Name of the collection (like a table) |
| `spring.ai.vectorstore.chroma.initialize-schema` | Auto-creates collection on first run |

### ChromaDB data model:
```
ChromaDB Server
  └── Tenant: SpringAiTenant (default)
       └── Database: SpringAiDatabase (default)
            └── Collection: "dsa-knowledge"
                 ├── Document 1: { id, content, embedding, metadata }
                 ├── Document 2: { id, content, embedding, metadata }
                 └── ...
```

---

## Step 5: Create the Data Ingestion Service

File: `src/main/java/com/aditya/rag/service/DataIngestionService.java`

```java
package com.aditya.rag.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DataIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DataIngestionService.class);

    private final VectorStore vectorStore;

    @Value("classpath:DSA.txt")
    private Resource dsaDocument;

    public DataIngestionService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @PostConstruct
    public void ingestDocuments() {
        // Check if data already exists in ChromaDB (no need to re-ingest on every restart)
        List<Document> existing = vectorStore.similaritySearch(
                SearchRequest.builder().query("data structure").topK(1).build()
        );
        if (!existing.isEmpty()) {
            log.info("ChromaDB already has data. Skipping ingestion.");
            return;
        }

        log.info("Starting document ingestion...");

        // Step A: Read the document
        TikaDocumentReader reader = new TikaDocumentReader(dsaDocument);
        List<Document> documents = reader.get();
        log.info("Read {} document(s)", documents.size());

        // Step B: Split into smaller chunks
        TextSplitter splitter = new TokenTextSplitter();
        List<Document> chunks = splitter.apply(documents);
        log.info("Split into {} chunks", chunks.size());

        // Step C: Add to vector store (embeds each chunk via Ollama and persists to ChromaDB)
        vectorStore.add(chunks);
        log.info("Ingestion complete. {} chunks stored in ChromaDB.", chunks.size());
    }
}
```

### What's happening here (the "pipeline"):

```
DSA.txt  -->  [Tika Reader]  -->  [Text Splitter]  -->  [ChromaDB]
                  |                     |                      |
          Reads raw text      Breaks into ~800         Embeds each chunk via
          into Document       token chunks             Ollama, stores in ChromaDB
          objects
```

1. **Skip check** — queries ChromaDB first. If data exists, skips ingestion. This is the persistence benefit — you only embed once.
2. **Reading** — `TikaDocumentReader` reads your file into Spring AI's `Document` object (text + metadata)
3. **Chunking** — `TokenTextSplitter` breaks the text into smaller pieces. Why?
   - Embeddings work better on focused, smaller text
   - LLMs have context limits; you want to send only relevant pieces
   - Default: ~800 tokens per chunk with some overlap
4. **Storing** — `vectorStore.add(chunks)` sends each chunk to Ollama for embedding, then persists to ChromaDB

`@PostConstruct` means this runs automatically when the app starts.

---

## Step 6: Create the RAG Controller

File: `src/main/java/com/aditya/rag/controller/RagController.java`

```java
package com.aditya.rag.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.QuestionAnswerAdvisor;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RagController {

    private final ChatClient chatClient;

    public RagController(ChatClient.Builder chatClientBuilder, VectorStore vectorStore) {
        // The QuestionAnswerAdvisor automatically:
        // 1. Takes the user question
        // 2. Searches ChromaDB for relevant chunks
        // 3. Adds those chunks to the prompt as context
        // 4. Sends the enriched prompt to the LLM
        this.chatClient = chatClientBuilder
                .defaultAdvisors(
                        QuestionAnswerAdvisor.builder(vectorStore)
                                .searchRequest(SearchRequest.builder().topK(5).build())
                                .build()
                )
                .build();
    }

    @GetMapping("/ask")
    public String ask(@RequestParam String question) {
        return chatClient.prompt()
                .user(question)
                .call()
                .content();
    }
}
```

### What's happening here:

```
GET /ask?question="What is a hash table?"
           |
           v
   [QuestionAnswerAdvisor]
           |
           ├── 1. Embeds "What is a hash table?" via Ollama (nomic-embed-text)
           ├── 2. Searches ChromaDB (top 5 similar chunks)
           ├── 3. Builds prompt:
           |       "Context: {retrieved chunks}
           |        Question: What is a hash table?
           |        Answer based on the context above."
           └── 4. Sends to Ollama (llama3.2)
                    |
                    v
              [Generated Answer]
```

- `ChatClient` — Spring AI's fluent API for talking to LLMs
- `QuestionAnswerAdvisor` — a built-in "advisor" that intercepts your prompt and injects RAG logic
- `SearchRequest.builder().topK(5)` — retrieve the 5 most similar chunks from ChromaDB
- `.call().content()` — send to LLM and get the text response

---

## Step 7: Run and Test

### Start everything (3 terminals):

**Terminal 1 — Ollama:**
```bash
ollama serve
```

**Terminal 2 — ChromaDB:**
```bash
chroma run --host localhost --port 8000 --path ./chroma-data
```

**Terminal 3 — Your Spring Boot app:**
```bash
./mvnw spring-boot:run
```

### First run — watch the ingestion logs:
```
Starting document ingestion...
Read 1 document(s)
Split into X chunks
Ingestion complete. X chunks stored in ChromaDB.
```

### Second run — ingestion is skipped (data persists!):
```
ChromaDB already has data. Skipping ingestion.
```

### Test with curl:
```bash
curl "http://localhost:8080/ask?question=What is a data structure?"
```

```bash
curl "http://localhost:8080/ask?question=What is the difference between an array and a linked list?"
```

```bash
curl "http://localhost:8080/ask?question=What are hash tables used for?"
```

---

## How Everything Connects (The Full Picture)

```
┌─────────────────────────────────────────────────────────────────┐
│                    FILE UPLOAD                                    │
├─────────────────────────────────────────────────────────────────┤
│                                                                   │
│  POST /upload?userId=aditya&visibility=PRIVATE                   │
│  Body: file=resume.pdf                                           │
│       │                                                           │
│       ▼                                                           │
│  [Tika Reader] ──> [Clean Text] ──> [Chunk] ──> [ChromaDB]      │
│                                                       │          │
│                                         metadata on each chunk:  │
│                                         {                        │
│                                           "source": "resume.pdf",│
│                                           "owner": "aditya",     │
│                                           "visibility": "PRIVATE"│
│                                         }                        │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                        QUERY TIME                                 │
├─────────────────────────────────────────────────────────────────┤
│                                                                   │
│  GET /ask?question=...&userId=aditya                             │
│       │                                                           │
│       ▼                                                           │
│  Filter: visibility=='PUBLIC' OR owner=='aditya'                 │
│       │                                                           │
│       ▼                                                           │
│  [Embed Question] ──> [Search ChromaDB with filter]              │
│                              │                                    │
│                        Top-K Chunks (only PUBLIC + own PRIVATE)   │
│                              │                                    │
│                    [Build Augmented Prompt]                       │
│                              │                                    │
│                        [Ollama: llama3.2]                         │
│                              │                                    │
│                        Final Answer                               │
└─────────────────────────────────────────────────────────────────┘
```

---

## Key Concepts Recap

| Concept | What it means |
|---------|---------------|
| **Embedding** | Converting text into a fixed-size vector of numbers that captures meaning |
| **ChromaDB** | Open-source vector database that stores embeddings on disk |
| **Collection** | A group of related vectors in ChromaDB (like a table in SQL) |
| **Chunking** | Breaking large documents into smaller pieces for better retrieval |
| **Cosine Similarity** | A measure of how "similar" two vectors are (1.0 = identical, 0 = unrelated) |
| **Top-K** | How many relevant chunks to retrieve (higher = more context, but slower) |
| **Advisor** | Spring AI's pattern for intercepting and enriching prompts |
| **Persistence** | Data stored in ChromaDB's `./chroma-data` folder survives app restarts |

---

## Complete `pom.xml` Dependencies Section

For reference, here's what your full dependencies block should look like:

```xml
<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-webmvc</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-starter-model-ollama</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-starter-vector-store-chroma</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-tika-document-reader</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-vector-store-advisor</artifactId>
    </dependency>

    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-devtools</artifactId>
        <scope>runtime</scope>
        <optional>true</optional>
    </dependency>
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <optional>true</optional>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-webmvc-test</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

---

## Next Steps (Things to Explore)

1. **Add more documents** — put multiple files in resources and ingest them all
2. **Tune chunk size** — experiment with `TokenTextSplitter` parameters (smaller chunks = more precise, larger = more context)
3. **Add metadata filtering** — tag chunks with source info and filter during search
4. **Stream responses** — use `.stream()` instead of `.call()` for real-time token streaming
5. **Build a UI** — add a simple Thymeleaf or React frontend
6. **Add a re-ingestion endpoint** — a POST endpoint to trigger re-ingestion when documents change
7. **Try different embedding models** — `mxbai-embed-large` for higher quality, `all-minilm` for speed

---

## Troubleshooting

| Problem | Solution |
|---------|----------|
| `Connection refused` to Ollama | Make sure Ollama is running: `ollama serve` |
| Model not found | Pull the model: `ollama pull llama3.2` and `ollama pull nomic-embed-text` |
| `Connection refused` to ChromaDB | Make sure ChromaDB is running: `chroma run --host localhost --port 8000 --path ./chroma-data` |
| `pip: command not found` | Install Python first, then `pip install chromadb` |
| `chroma: command not found` | After pip install, try `python -m chromadb run ...` or add Python Scripts to PATH |
| Slow first response | First call warms up the model in Ollama. Subsequent calls are faster. |
| Out of memory | Use a smaller model like `llama3.2:1b` or reduce chunk count |
| Empty/irrelevant answers | Your document might not contain relevant info, or try increasing topK |
| Data not persisting | Make sure you use the same `--path` when restarting ChromaDB |
| Collection already exists error | This is fine — `initialize-schema=true` won't recreate existing collections |
