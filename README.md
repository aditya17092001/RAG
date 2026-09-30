# RAG-Aditya

A full-stack **Retrieval-Augmented Generation (RAG)** application: upload documents, then chat with an LLM that answers grounded in your document content. Built with **Spring Boot 4.1**, **Spring AI 2.0**, and a **React (Vite)** frontend, with an asynchronous **Kafka-driven embedding pipeline** and JWT authentication.

## Live links

| Link | What it is |
|------|------------|
| **https://rag-aditya.netlify.app/** | The live RAG application (frontend) |
| **https://rag-aditya-hld.netlify.app/** | Interactive architecture (HLD) diagram |

## Highlights

- **Document ingestion:** upload `.pdf`, `.docx`, `.txt`, and more (Apache Tika) → converted to Markdown → chunked → embedded.
- **Asynchronous embedding:** uploads return `202 Accepted` immediately; chunks are embedded in the background via **Kafka**, with retries and a dead-letter topic.
- **Grounded chat:** each question runs a vector similarity search (public + your private docs) and streams a context-augmented answer token-by-token over **SSE**.
- **Per-user isolation:** documents are tagged `PUBLIC` or `PRIVATE`; private docs are only retrievable by their owner.
- **Auth:** JWT-based signup/signin with optional email **OTP** verification and password reset.
- **Persistent chat memory:** conversation history is retained per conversation.

## Architecture at a glance

```
React SPA ──HTTPS+JWT──> Spring Security (JwtAuthFilter)
                              │
              ┌───────────────┼────────────────────┐
              ▼               ▼                     ▼
        RagController   FileUploadController   AuthController
              │               │                     │
   similaritySearch      queueFile             User PostgreSQL
     (pgvector)               │                (users, OTP)
              │        DataIngestionService
      embed query        (Tika→Markdown→chunk)
      (Gemini)                │
              │        EmbeddingJobService ──persist──> Aiven PostgreSQL
     stream tokens (SSE)      │                          (jobs, chunks)
              ▼               ├──publish──> Kafka (embedding-jobs / .DLT)
     OpenRouter                            │
   NVIDIA Nemotron          consume (async, rate-limited)
     (free)                       │
                            embed chunk (Gemini 768-dim)
                                  │
                            vectorStore.add ──> Aiven pgvector (vector_store)
```

See the [interactive diagram](https://rag-aditya-hld.netlify.app/) or [`docs/diagrams/`](docs/diagrams/) for the detailed runtime view, and [`docs/PROJECT-ARCHITECTURE.md`](docs/PROJECT-ARCHITECTURE.md) for a deeper write-up.

## Tech stack

**Backend**
- Java 21, Spring Boot 4.1, Spring AI 2.0
- Spring Web MVC, Spring Security, Spring Data JPA, Spring Kafka, Spring Mail
- Spring AI: OpenAI-compatible chat starter (OpenRouter), Google GenAI embeddings, pgvector vector store, Tika document reader, JDBC chat-memory
- jjwt 0.12.6 (JWT), Flexmark (HTML→Markdown), springdoc-openapi (Swagger UI), Lombok

**Frontend**
- React + Vite (`frontend/`)

**Data & infrastructure**
- **User PostgreSQL** — users, conversations, chat memory (`SPRING_AI_CHAT_MEMORY`)
- **Aiven PostgreSQL** — embedding job/chunk state (`embedding_jobs`, `embedding_chunks`)
- **Aiven PostgreSQL + pgvector** — embeddings (`vector_store`, 768-dim)
- **Aiven Kafka** — async embedding queue (SASL_SSL / SCRAM-SHA-256)

> Note: the `default` profile is set up for local development (Ollama chat + file-based H2). The deployed configuration uses the `prod` profile — **OpenRouter (NVIDIA Nemotron, free)** for chat and **cloud PostgreSQL** for all relational data.

**AI providers**
- **Chat:** OpenRouter — `nvidia/nemotron-3-super-120b-a12b:free` (streaming)
- **Embeddings:** Google Gemini — `gemini-embedding-001` (768-dim), both profiles

## Project structure

```
.
├── src/main/java/com/aditya/rag/
│   ├── config/          # Security, VectorStore (two datasources), Kafka, ChatMemory, Web
│   ├── controller/      # Auth, Rag (chat), Conversation, FileUpload, EmbeddingJob
│   ├── service/         # Ingestion, EmbeddingJob (producer/consumer/orchestrator), JWT, OTP, Email
│   ├── entity/          # User, Conversation, EmbeddingJob, EmbeddingChunk, VerifyOtp
│   ├── repo/            # Spring Data JPA repositories
│   └── kafka/           # EmbeddingJobMessage (Kafka payload)
├── src/main/resources/  # application.properties, ca.pem (Kafka CA)
├── frontend/            # React + Vite SPA
├── docs/                # Guides + architecture diagrams
├── Dockerfile / Dockerfile.prod
└── pom.xml
```

## Getting started

### Prerequisites
- **JDK 21**
- **Node.js 18+** (for the frontend)
- API keys / services: Google Gemini (embeddings), and for the deployed setup: OpenRouter, cloud PostgreSQL (×2, one with pgvector), Aiven Kafka, a Gmail App Password (if OTP is enabled)

### 1. Configure environment
Copy the example env file and fill in real values:

```bash
cp .env.example .env
```

Key variables (see [`.env.example`](.env.example) for the full list):

| Variable | Purpose |
|----------|---------|
| `JWT_SECRET`, `JWT_EXPIRY` | JWT signing secret and token lifetime (ms) |
| `GEMINI_API_KEY` | Google Gemini embeddings |
| `OPENROUTER_API_KEY`, `OPENROUTER_MODEL` | OpenRouter chat (prod profile) |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | User/relational PostgreSQL (prod) |
| `VECTOR_DB_URL`, `VECTOR_DB_USERNAME`, `VECTOR_DB_PASSWORD` | pgvector PostgreSQL |
| `KAFKA_BOOTSTRAP`, `KAFKA_USER`, `KAFKA_PASSWORD` | Aiven Kafka |
| `MAIL_USERNAME`, `MAIL_PASSWORD`, `OTP_ENABLED` | OTP email (Gmail SMTP) |
| `CORS_ALLOWED_ORIGINS` | Allowed frontend origins |

`.env` is gitignored — never commit real secrets.

### 2. Run the backend

```bash
# Local dev (default profile)
./mvnw spring-boot:run

# Deployed-style config (prod profile)
./mvnw spring-boot:run -Dspring-boot.run.profiles=prod
```

Backend starts on `http://localhost:8080`. Swagger UI: `http://localhost:8080/swagger-ui.html`.

> On Windows use `mvnw.cmd` instead of `./mvnw`.

### 3. Run the frontend

```bash
cd frontend
npm install
npm run dev
```

The SPA runs on `http://localhost:5173` and talks to the backend via `VITE_API_BASE_URL` (see `frontend/.env.development`).

## API overview

All endpoints except `/auth/**`, health, and Swagger require a `Bearer <JWT>` token.

| Method | Path | Description |
|--------|------|-------------|
| POST | `/auth/signup` | Register (sends OTP if enabled) |
| POST | `/auth/verify-otp` | Verify email OTP |
| POST | `/auth/signin` | Authenticate, returns JWT |
| POST | `/auth/forgot-password` / `/auth/reset-password` | Password reset via OTP |
| POST | `/upload` | Upload a document (multipart `file` + `visibility`) → `202` with job id |
| GET | `/embedding-jobs/{jobId}` | Poll async embedding progress |
| GET | `/ask?question=&conversationId=` | Ask (non-streaming) |
| GET | `/ask/stream?question=&conversationId=` | Ask (streaming SSE, Base64 tokens) |
| POST | `/api/v1/conversations` | Create a conversation |
| GET | `/api/v1/conversations` | List your conversations |
| GET | `/api/v1/conversations/{id}/messages` | Get conversation messages |
| DELETE | `/api/v1/conversations/{id}` | Delete a conversation |

## How it works

**Upload → embed (async):**
`/upload` → Tika parse → Flexmark HTML→Markdown → recursive chunking (1000 chars, 200 overlap) → job + chunks persisted (`QUEUED`) → one Kafka message per chunk. A background consumer embeds each chunk via Gemini (rate-limited) and writes the 768-dim vector to pgvector. Transient failures retry 4×60s, then route to the dead-letter topic. Progress is pollable via `/embedding-jobs/{jobId}`.

**Chat → retrieve → answer:**
`/ask/stream` verifies conversation ownership, embeds the question, runs two similarity searches (PUBLIC docs + your PRIVATE docs, topK=5), builds a context-augmented prompt, and streams the LLM answer token-by-token over SSE. The full answer and conversation title are persisted on completion.

## Docker

```bash
# Local image
docker build -t rag-aditya .

# Production image (includes Kafka CA)
docker build -f Dockerfile.prod -t rag-aditya:prod .
```

## Documentation

- [`docs/HOW-TO-RUN.md`](docs/HOW-TO-RUN.md) — run instructions
- [`docs/PROJECT-ARCHITECTURE.md`](docs/PROJECT-ARCHITECTURE.md) — architecture deep-dive
- [`docs/RAG-GUIDE.md`](docs/RAG-GUIDE.md) — RAG pipeline
- [`docs/KAFKA-EMBEDDING-GUIDE.md`](docs/KAFKA-EMBEDDING-GUIDE.md) — async embedding pipeline
- [`docs/JWT-AUTH-GUIDE.md`](docs/JWT-AUTH-GUIDE.md) / [`docs/SPRING-SECURITY-LEARN.md`](docs/SPRING-SECURITY-LEARN.md) — auth & security
- [`docs/diagrams/`](docs/diagrams/) — interactive architecture diagram
