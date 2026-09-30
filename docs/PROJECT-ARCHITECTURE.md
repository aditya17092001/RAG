# Local RAG Project Architecture

**Status:** Current implementation reference  
**Application:** `local-rag`  
**Backend:** Spring Boot 4.1.0 / Java 21  
**Frontend:** React + Vite  
**Embedding pipeline:** Kafka + Google Gemini Embeddings + Aiven PgVector  
**Deployment:** Docker / Render backend + static frontend hosting

This document describes the architecture that is implemented in the repository. It explains the application layers, request flows, Kafka embedding pipeline, authentication, relational persistence, vector persistence, deployment, and operational failure behavior.

> **Important boundary:** relational tables are application/JPA-owned. The PgVector table and its exact SQL DDL are created by Spring AI's `PgVectorStore` because the application uses `initializeSchema(true)`; no application-owned migration script defines that schema.

---

## 1. System overview

The system is a document-question-answering application:

1. A user creates an account and signs in.
2. The user creates or selects a conversation.
3. The user uploads documents.
4. The backend parses and splits each document into chunks.
5. Chunk state is saved in the relational database.
6. One Kafka message is published for each chunk.
7. A Kafka consumer sends each chunk to Gemini to create an embedding.
8. The chunk, embedding, and metadata are stored in PgVector.
9. A user asks a question.
10. The question is embedded and used for similarity search.
11. Public documents and the current user's private documents are retrieved.
12. Retrieved text plus conversation memory is sent to the chat model.
13. The answer is returned either as a normal response or an SSE stream.

```text
                              +----------------------+
                              | React/Vite frontend  |
                              | Auth + Chat + Upload |
                              +----------+-----------+
                                         |
                              HTTP/JSON/SSE + JWT
                                         |
                                         v
+---------------------------------------------------------------------+
|                         Spring Boot backend                         |
|                                                                     |
|  Controllers -> Services -> Repositories / Spring AI integrations  |
|                                                                     |
|  Auth   Conversations   Upload   Embedding jobs   RAG chat          |
+----------+----------------------------+-----------------------------+
           |                            |
           |                            |
           v                            v
+-----------------------+       +----------------------+
| Primary relational DB |       | Kafka                |
| H2 locally            |       | embedding-jobs       |
| PostgreSQL in prod   |       | embedding-jobs.DLT  |
|                      |       +----------+-----------+
| users                |                  |
| verify_otp           |                  v
| conversations        |       +----------------------+
| embedding_jobs       |       | Kafka consumer       |
| embedding_chunks     |       | Gemini embedding    |
| spring_ai_chat_memory|       | PgVector persistence |
+-----------------------+       +----------+-----------+
                                          |
                                          v
                               +-----------------------+
                               | Separate vector DB   |
                               | Aiven PostgreSQL      |
                               | pgvector extension    |
                               | vector_store table    |
                               +-----------------------+
```

### 1.1 Main architectural decisions

| Decision | Reason |
|---|---|
| Parse files synchronously, embed asynchronously | Parsing is local and bounded; Gemini/PgVector work can be slow and quota-limited. |
| One Kafka message per chunk | A chunk can be retried and tracked independently. |
| One consumer by default | Protects Gemini free-tier request limits and keeps processing predictable. |
| Database job/chunk state | Kafka offsets alone are not a user-facing progress system. |
| Separate relational and vector databases | Login/application data and vector data have different workloads and connection requirements. |
| Manual Kafka topics | Prevents typo-created topics and preserves Aiven free-tier topic capacity. |
| Direct DLT after configured retries | Failed records remain inspectable and do not disappear. |
| Metadata filters for visibility | Public documents and private user documents must be isolated during retrieval. |

---

## 2. Repository structure

```text
local-rag/
├── frontend/
│   ├── src/
│   │   ├── main.jsx
│   │   ├── App.jsx
│   │   ├── Auth.jsx
│   │   ├── Chat.jsx
│   │   ├── api.js
│   │   └── index.css
│   ├── .env.development
│   ├── .env.production
│   ├── package.json
│   ├── vite.config.js
│   └── netlify.toml
│
├── src/main/java/com/aditya/rag/
│   ├── LocalRagApplication.java
│   ├── config/
│   ├── constants/
│   ├── controller/
│   ├── dto/
│   ├── entity/
│   ├── interceptor/
│   ├── kafka/
│   ├── model/
│   ├── repo/
│   ├── service/
│   └── util/
│
├── src/main/resources/
│   ├── application.properties
│   ├── application-prod.properties
│   ├── ca.pem
│   └── logback-spring.xml
│
├── docs/
│   ├── PROJECT-ARCHITECTURE.md
│   ├── KAFKA-EMBEDDING-GUIDE.md
│   ├── RAG-GUIDE.md
│   ├── JWT-AUTH-GUIDE.md
│   └── ...
│
├── Dockerfile
├── Dockerfile.prod
├── pom.xml
└── data/
    └── users.mv.db       # local file-backed H2 database
```

### 2.1 Backend packages

| Package | Responsibility |
|---|---|
| `config` | Spring beans for security, Kafka, data sources, PgVector, chat memory, CORS, and startup diagnostics. |
| `controller` | HTTP API endpoints. Controllers authenticate the request and delegate to services. |
| `service` | Application behavior: authentication, OTP, ingestion, Kafka production/consumption, embedding processing, and JWT handling. |
| `entity` | JPA models that map to relational tables. |
| `repo` | Spring Data JPA repositories. |
| `dto` | HTTP request/response objects. |
| `kafka` | Kafka message contracts. |
| `interceptor` | Request ID and logging context. |
| `model` / `util` | Email models and OTP utilities. |

The application entry point is:

```text
src/main/java/com/aditya/rag/LocalRagApplication.java
```

The application explicitly excludes Spring AI's automatic PgVector configuration because it manually creates a vector store using a separate datasource.

---

## 3. Frontend architecture

The frontend is a Vite-built React single-page application.

### 3.1 Frontend components

| File | Responsibility |
|---|---|
| `frontend/src/main.jsx` | React bootstrap and `StrictMode`. |
| `frontend/src/App.jsx` | Chooses authentication or chat UI based on the token in `localStorage`. |
| `frontend/src/Auth.jsx` | Sign-up, sign-in, and OTP verification screens. |
| `frontend/src/Chat.jsx` | Conversation sidebar, messages, streaming answer display, and file upload controls. |
| `frontend/src/api.js` | Central fetch client, JWT headers, JSON calls, multipart upload, and SSE parsing. |
| `frontend/src/index.css` | Frontend styling. |

### 3.2 API client behavior

`frontend/src/api.js` determines the backend URL from:

```text
VITE_API_BASE_URL
```

The development default is localhost. The production value is injected at frontend build time through `.env.production`.

For authenticated calls:

```text
Authorization: Bearer <JWT>
```

JSON requests use `Content-Type: application/json`. File uploads use `FormData`; the browser sets the multipart boundary automatically.

The frontend calls these backend operations:

```text
POST /auth/signup
POST /auth/verify-otp
POST /auth/signin
POST /auth/forgot-password
POST /auth/reset-password

POST /api/v1/conversations
GET  /api/v1/conversations
GET  /api/v1/conversations/{conversationId}/messages
DELETE /api/v1/conversations/{conversationId}

POST /upload
GET  /ask
GET  /ask/stream
```

The frontend currently does not poll `GET /embedding-jobs/{jobId}` after upload. Therefore, the upload UI knows that the job was queued but does not currently show live embedding progress.

### 3.3 Streaming answers

The frontend uses `fetch` rather than `EventSource` because it must send the JWT header.

The backend emits Server-Sent Events. Each answer token is Base64-encoded before being placed in the SSE `data:` field. The frontend:

1. Reads the response as a `ReadableStream`.
2. Splits data into SSE frames.
3. Reads `data:` lines.
4. Base64-decodes each token.
5. Converts bytes to UTF-8.
6. Appends the token to the current assistant message.

Base64 is used to preserve whitespace that can otherwise be changed by SSE line framing.

---

## 4. Backend API architecture

The backend is a stateless Spring MVC application. Authentication state is represented by a signed JWT rather than an HTTP session.

### 4.1 Controllers

#### `AuthController`

Mapped under `/auth`.

Responsibilities:

- User registration.
- OTP verification.
- OTP resend.
- Sign-in.
- Forgot-password OTP.
- Password reset.

#### `ConversationController`

Mapped under `/api/v1/conversations`.

Responsibilities:

- Create conversations.
- List conversations belonging to the current user.
- Load messages from chat memory.
- Delete conversations.
- Clear chat memory when a conversation is deleted.

#### `FileUploadController`

Mapped under `/upload`.

Responsibilities:

- Read the authenticated user ID.
- Validate the uploaded file.
- Validate `PUBLIC` or `PRIVATE` visibility.
- Call `DataIngestionService.queueFile()`.
- Return HTTP `202 Accepted` with an embedding job ID.

#### `EmbeddingJobController`

Mapped under `/embedding-jobs/{jobId}`.

Responsibilities:

- Load an embedding job by ID.
- Enforce owner scoping using `findByIdAndOwnerId`.
- Return job status and progress.

#### `RagController`

Mapped under `/ask` and `/ask/stream`.

Responsibilities:

- Verify conversation ownership.
- Search public and private vectors.
- Build the augmented prompt.
- Use chat memory.
- Call the chat model.
- Return a normal answer or an SSE stream.

---

## 5. Authentication and authorization

### 5.1 Sign-up flow

```text
Frontend submits email/password/name
        |
        v
AuthController.signup()
        |
        v
Check UserRepository.existsByEmail()
        |
        v
Hash password with BCrypt
        |
        v
Save User(emailVerified=false)
        |
        +-- OTP enabled --> OtpService.generateAndSendOtp()
        |
        +-- OTP disabled --> account is auto-verified
```

### 5.2 Sign-in flow

```text
Frontend sends email/password
        |
        v
AuthenticationManager authenticates credentials
        |
        v
Load User
        |
        v
Reject if emailVerified=false
        |
        v
JwtService.generateToken(userId, email)
        |
        v
Frontend stores JWT in localStorage
```

The JWT contains:

- `sub`: UUID of the user.
- `email`: user email.
- Issued-at timestamp.
- Expiration timestamp.

### 5.3 Request authorization flow

```text
HTTP request
   |
   v
JwtAuthFilter
   |
   | Extract Bearer token
   | Validate signature and expiration
   | Load user by email
   v
SecurityContextHolder
   |
   v
Controller reads authenticated user UUID
```

`SecurityConfig` uses stateless sessions. CSRF is disabled for the API, CORS is configured, authentication endpoints are public, and all other application endpoints require authentication.

### 5.4 OTP behavior

`OtpService` stores one current OTP per email. A new OTP replaces the old one.

OTP behavior:

- Expiration: 10 minutes.
- OTP is deleted after successful validation.
- OTP email failures are logged/returned by the mail service.
- When `OTP_ENABLED=false`, generation is skipped and validation succeeds automatically.

The OTP bypass mode is useful in environments where SMTP is unavailable, but it should be used deliberately.

---

## 6. Upload and asynchronous embedding architecture

### 6.1 Upload flow

```text
POST /upload
  |
  v
FileUploadController.upload()
  |
  v
DataIngestionService.queueFile()
  |
  +-- Apache Tika parses the file
  |
  +-- Flexmark converts extracted HTML to Markdown
  |
  +-- RecursiveCharacterTextSplitter creates chunks
  |
  v
EmbeddingJobService.createJob()
  |
  +-- Save EmbeddingJob
  +-- Save EmbeddingChunk rows
  +-- Build EmbeddingJobMessage objects
  |
  v
EmbeddingJobService.publish()
  |
  v
EmbeddingJobProducer.publish() for every chunk
  |
  v
Kafka topic: embedding-jobs
  |
  v
HTTP 202 Accepted
```

The request performs parsing, chunking, relational persistence, and Kafka publication. It does **not** call Gemini or PgVector directly.

### 6.2 File parsing

`DataIngestionService.queueFile()`:

1. Reads the multipart `Resource`.
2. Uses Apache Tika `AutoDetectParser` to extract content.
3. Converts Tika output to Markdown with Flexmark.
4. Rejects blank extracted content.
5. Adds source metadata.
6. Splits content with `RecursiveCharacterTextSplitter(1000, 200)`.

The splitter targets a maximum of approximately 1,000 characters with approximately 200 characters of overlap. Overlap preserves context across chunk boundaries.

### 6.3 Kafka message contents

Each `EmbeddingJobMessage` is self-contained and includes:

```text
jobId
 documentId
chunkId
chunkIndex
totalChunks
text
filename
fileType
ownerId
visibility
```

The consumer does not depend on:

- The original HTTP request.
- A temporary file path.
- The local filesystem of the producer process.

### 6.4 Embedding consumer flow

```text
Kafka record received
        |
        v
EmbeddingJobConsumer.consume()
        |
        v
EmbeddingJobService.process()
        |
        +-- Load EmbeddingChunk
        +-- Skip if already EMBEDDED
        +-- Increment attempts
        +-- Set PROCESSING
        +-- Mark job PROCESSING
        +-- Wait for EmbeddingRateLimiter
        +-- Create valid UUID vector document ID
        +-- Call vectorStore.add()
        +-- Set chunk EMBEDDED
        +-- Recalculate job progress
        v
Listener returns successfully
        |
        v
Kafka record can be acknowledged/offset committed
```

`vectorStore.add(List.of(document))` causes Spring AI to:

1. Send the chunk text to Gemini Embeddings.
2. Receive the numeric embedding vector.
3. Insert or update the vector-store row through PgVector.

The application uses one vector-store operation per Kafka chunk.

### 6.5 Stable vector IDs

The relational chunk key has this form:

```text
<document UUID>:<chunk index>
```

That is not itself a valid PostgreSQL UUID. PgVectorStore expects `Document.id` to be a UUID, so the consumer derives a stable UUID:

```java
UUID.nameUUIDFromBytes(
    message.chunkId().getBytes(StandardCharsets.UTF_8)
).toString()
```

The original chunk key is retained in metadata as `chunkId`.

The stable ID is important for at-least-once delivery. If Kafka redelivers a chunk, the same chunk produces the same vector ID.

---

## 7. Kafka architecture

### 7.1 Topics

| Topic | Purpose | Partitions |
|---|---|---:|
| `embedding-jobs` | Normal chunk-processing queue. | 1 initially |
| `embedding-jobs.DLT` | Terminal failures after retry exhaustion. | 1 initially |

Topics are manually created in Aiven. Automatic topic creation is disabled to prevent typos and accidental topic creation.

### 7.2 Producer configuration

Defined in `EmbeddingKafkaConfig.embeddingProducerFactory()`:

- `acks=all`: wait for durable broker acknowledgment.
- Idempotence enabled: reduce duplicate writes during producer retries.
- String key serializer: chunk ID is the key.
- JSON value serializer: `EmbeddingJobMessage` is stored as JSON.
- SASL/SCRAM authentication.
- SSL encryption and PEM CA verification.

`EmbeddingJobProducer.publish()` waits up to 30 seconds for the Kafka send result. This means the upload request waits for message publication, but not for Gemini embedding.

### 7.3 Consumer configuration

Defined in `EmbeddingKafkaConfig.embeddingConsumerFactory()`:

- Group ID: `rag-embedding`.
- Offset reset: `earliest` for a new group.
- Maximum records per poll: `1`.
- Auto topic creation: disabled.
- Auto commit: disabled.
- JSON deserializer for `EmbeddingJobMessage`.
- Only `com.aditya.rag.kafka` is trusted for JSON deserialization.

The listener container uses concurrency `1` by default. This intentionally limits provider pressure.

### 7.4 Offset behavior

The listener uses record acknowledgment behavior:

```properties
spring.kafka.listener.ack-mode=record
```

A successful listener return allows the record to be acknowledged. If processing throws, the record is handled by the error handler instead.

This prevents a failed embedding message from being acknowledged before the vector is stored.

### 7.5 Retry and DLT flow

```text
EmbeddingJobConsumer.consume()
        |
        | throws exception
        v
DefaultErrorHandler
        |
        +-- Fixed delay: 60 seconds by default
        +-- Retry up to configured attempts
        |
        +-- Success: record completes
        |
        +-- Failure after retries
                    |
                    v
        DeadLetterPublishingRecoverer
                    |
                    v
        embedding-jobs.DLT partition 0
                    |
                    v
        consumeDeadLetter()
                    |
                    v
        markDltFailure()
```

Current default configuration:

```properties
app.embedding.retry.interval-ms=60000
app.embedding.retry.max-attempts=4
```

`IllegalArgumentException` is configured as non-retryable because errors such as missing state or invalid data will not be fixed by repeating the same message.

The DLT listener does not rethrow after recording failure. Rethrowing there could cause a DLT record to be published back to the DLT.

### 7.6 Rate limiting

`EmbeddingRateLimiter.acquire()` serializes calls and enforces the minimum interval between provider calls.

Current default:

```properties
app.embedding.min-interval-ms=2000
```

That is approximately 30 calls per minute for one consumer, before considering provider behavior and retries.

The limiter protects the per-minute request rate. It cannot prevent a daily quota from eventually being exhausted.

---

## 8. RAG query architecture

### 8.1 Query flow

```text
User asks a question
        |
        v
GET /ask/stream
        |
        v
Verify JWT and conversation ownership
        |
        v
buildAugmentedPrompt()
        |
        +-- Embed question through Gemini
        +-- Search public vectors, top 5
        +-- Search current user's vectors, top 5
        +-- Combine retrieved text
        +-- Build context-only prompt
        |
        v
Add user question to ChatMemory
        |
        v
ChatClient calls configured chat provider
        |
        v
Stream answer tokens as Base64 SSE
        |
        v
Save complete assistant response to ChatMemory
```

### 8.2 Visibility filtering

The controller performs two searches:

```text
Public search:
visibility == 'PUBLIC'

Private search:
owner == '<current user UUID>'
```

The private search does not return another user's private documents. The combined context can contain up to ten retrieved documents: five public and five belonging to the current user.

### 8.3 Prompt construction

The prompt contains:

- Retrieved document text.
- An instruction to answer from the supplied context.
- An instruction to say it does not know if the context does not contain the answer.
- The user's question.

There is currently no explicit similarity-score threshold or deduplication step.

### 8.4 Chat memory

`MessageWindowChatMemory` keeps at most ten messages per conversation memory key. The key is the conversation UUID string.

Chat memory is stored through Spring AI's JDBC chat-memory repository in the primary relational database. It is separate from the application-owned `conversations` table:

```text
conversations row     = ownership, title, creation metadata
spring_ai_chat_memory = user/assistant message history
```

The conversation controller clears chat memory when a conversation is deleted.

### 8.5 Streaming behavior

The streaming endpoint uses `Flux<String>` and Server-Sent Events. The backend accumulates the complete response so it can save the assistant message after successful completion. It also creates a conversation title from the first six words when the conversation has no title.

If the stream fails or is cancelled before completion, the current implementation may not save the complete assistant response or auto-title.

---

## 9. Database architecture

The application intentionally uses two database connections.

```text
+-------------------------------+
| Primary application database  |
| Local: file-backed H2         |
| Production: PostgreSQL        |
|                               |
| users                         |
| verify_otp                    |
| conversations                 |
| embedding_jobs                |
| embedding_chunks              |
| spring_ai_chat_memory         |
+-------------------------------+

+-------------------------------+
| Separate vector database      |
| Aiven PostgreSQL + pgvector   |
|                               |
| vector_store                  |
| pgvector extension            |
+-------------------------------+
```

### 9.1 Why two databases?

The application data and vector workload have different requirements:

- User and conversation data are relational application data.
- Embeddings are large vector values and are searched using pgvector.
- The vector database may be hosted and scaled independently.
- Separate `JdbcTemplate` beans prevent chat-memory SQL from being sent to the vector database.

`VectorStoreConfig` marks the application datasource and its `JdbcTemplate` as `@Primary`. The vector datasource and vector `JdbcTemplate` are explicitly qualified.

---

## 10. Primary relational database

### 10.1 Local database

The default configuration uses file-backed H2:

```properties
spring.datasource.url=jdbc:h2:file:./data/users
```

The database files are under:

```text
data/users.mv.db
data/users.trace.db
```

This is convenient for local development. A disposable production container should not use file-backed H2 as its durable application database unless a persistent volume is explicitly configured.

### 10.2 Production database

The `prod` profile overrides the primary datasource with environment variables:

```properties
spring.datasource.url=${DB_URL}
spring.datasource.username=${DB_USERNAME}
spring.datasource.password=${DB_PASSWORD}
spring.datasource.driver-class-name=org.postgresql.Driver
```

Hibernate uses `ddl-auto=update` for the production profile. There are no application-owned Flyway or Liquibase migrations in the repository.

### 10.3 `users` table

Entity:

```text
src/main/java/com/aditya/rag/entity/User.java
```

Repository:

```text
src/main/java/com/aditya/rag/repo/UserRepository.java
```

Purpose: authentication and account identity.

Logical columns:

| Column | Purpose |
|---|---|
| `user_id` | UUID primary key. |
| `email` | Unique login identifier. |
| `password` | BCrypt hash, never plaintext. |
| `name` | Display name. |
| `email_verified` | Controls whether sign-in is allowed. |
|

The entity implements Spring Security's `UserDetails` and grants `ROLE_USER`.

### 10.4 `verify_otp` table

Entity:

```text
src/main/java/com/aditya/rag/entity/VerifyOtp.java
```

Repository:

```text
src/main/java/com/aditya/rag/repo/VerifyOtpRepository.java
```

Purpose: temporary OTP state for verification and password reset.

Logical columns:

| Column | Purpose |
|---|---|
| `email` | Primary/unique lookup key. |
| `otp` | Current verification code. |
| `created_at` | Creation timestamp. |
| `otp_timestamp` | Used for expiry checks. |

A new OTP replaces the old one for the same email. Validated OTP records are deleted.

### 10.5 `conversations` table

Entity:

```text
src/main/java/com/aditya/rag/entity/Conversation.java
```

Repository:

```text
src/main/java/com/aditya/rag/repo/ConversationRepository.java
```

Purpose: application-level conversation ownership and titles.

Logical columns:

| Column | Purpose |
|---|---|
| `id` | Conversation UUID primary key. |
| `owner_id` | UUID of the owning user. |
| `title` | Optional conversation title. |
| `created_at` | Creation timestamp. |

The repository lists conversations by owner. The application checks ownership in controllers; there is no JPA `@ManyToOne` relationship or explicit foreign-key mapping to `users`.

### 10.6 `embedding_jobs` table

Entity:

```text
src/main/java/com/aditya/rag/entity/EmbeddingJob.java
```

Repository:

```text
src/main/java/com/aditya/rag/repo/EmbeddingJobRepository.java
```

Purpose: one user-visible record for an uploaded document's asynchronous embedding job.

Logical columns:

| Column | Purpose |
|---|---|
| `id` | Job UUID primary key. |
| `document_id` | UUID shared by all chunks of one uploaded document. |
| `owner_id` | User who uploaded the document. |
| `filename` | Original filename. |
| `file_type` | Extension/type derived during ingestion. |
| `visibility` | `PUBLIC` or `PRIVATE`. |
| `total_chunks` | Number of chunks created. |
| `completed_chunks` | Number of chunks with `EMBEDDED` status. |
| `failed_chunks` | Number of chunks with `FAILED` status. |
| `status` | `QUEUED`, `PROCESSING`, `COMPLETED`, or `FAILED`. |
| `last_error` | Most recent summarized error. |
| `created_at` | Creation timestamp. |
| `updated_at` | Last update timestamp. |

The job is saved before Kafka publication starts. If publishing fails, the service marks the job failed, although messages already published before the failure may still be consumed.

### 10.7 `embedding_chunks` table

Entity:

```text
src/main/java/com/aditya/rag/entity/EmbeddingChunk.java
```

Repository:

```text
src/main/java/com/aditya/rag/repo/EmbeddingChunkRepository.java
```

Purpose: durable state for every individual chunk.

Logical columns:

| Column | Purpose |
|---|---|
| `id` | String primary key in the form `<document UUID>:<chunk index>`. |
| `job_id` | Owning embedding job UUID. |
| `document_id` | Parent uploaded-document UUID. |
| `owner_id` | Uploading user UUID. |
| `chunk_index` | Zero-based chunk position. |
| `total_chunks` | Total chunks in the document. |
| `chunk_text` | Chunk content used for embedding. |
| `filename` | Original filename. |
| `file_type` | File extension/type. |
| `visibility` | `PUBLIC` or `PRIVATE`. |
| `status` | `QUEUED`, `PROCESSING`, `EMBEDDED`, or `FAILED`. |
| `attempts` | Number of processing attempts. |
| `last_error` | Latest error text, truncated by the service. |
| `created_at` | Creation timestamp. |
| `updated_at` | Last update timestamp. |

There are no JPA relationship annotations between `EmbeddingChunk` and `EmbeddingJob`; the service uses scalar UUIDs and repository lookups.

### 10.8 Chat memory tables

Spring AI's JDBC chat-memory repository initializes its own table(s), including the configured `SPRING_AI_CHAT_MEMORY` schema/table expected by the library version.

This data is separate from the `conversations` entity. A conversation row identifies the owner and title, while chat memory stores the actual user/assistant message window.

The primary `JdbcTemplate` must point to this database. Accidentally routing the chat-memory repository to the vector datasource causes missing-table errors.

### 10.9 Relational status transitions

#### Job status

```text
QUEUED
  |
  v
PROCESSING
  |
  +--> COMPLETED   when all chunks are EMBEDDED
  |
  +--> FAILED      when any chunk is FAILED
```

#### Chunk status

```text
QUEUED
  |
  v
PROCESSING
  |
  +--> EMBEDDED
  |
  +--> QUEUED      when a retry is scheduled
  |
  +--> FAILED      when the message is handled by the DLT listener
```

The service recalculates job counts by querying chunk counts by status. A single failed chunk makes the job status `FAILED`.

### 10.10 Conceptual relationships

The current model has logical relationships but no JPA foreign-key mappings:

```text
User.user_id
   ├── Conversation.owner_id
   ├── EmbeddingJob.owner_id
   ├── EmbeddingChunk.owner_id
   └── vector_store.metadata.owner

EmbeddingJob.id
   └── EmbeddingChunk.job_id

EmbeddingJob.document_id
   └── EmbeddingChunk.document_id

Conversation.id
   └── ChatMemory conversation/memory key
```

Ownership is enforced by service/controller queries and vector metadata filters rather than database foreign keys.

---

## 11. Vector database and PgVector

### 11.1 Connection

The vector database is configured separately:

```properties
vector.datasource.url=${VECTOR_DB_URL}
vector.datasource.username=${VECTOR_DB_USERNAME}
vector.datasource.password=${VECTOR_DB_PASSWORD}
```

The vector datasource is not primary. It is used to construct:

```text
vectorJdbcTemplate
PgVectorStore
```

### 11.2 PgVectorStore configuration

`VectorStoreConfig.vectorStore()` creates the store with:

```java
PgVectorStore.builder(vectorJdbcTemplate, embeddingModel)
    .dimensions(768)
    .initializeSchema(true)
    .build();
```

This means:

- Gemini is the embedding model.
- Every vector must have 768 dimensions.
- Spring AI initializes the vector schema if needed.
- The application expects PostgreSQL's `vector` extension.

### 11.3 `vector_store` logical content

The exact table definition is framework-managed, but logically the vector store contains:

| Logical value | Purpose |
|---|---|
| `id` | UUID document/vector ID. |
| `content` | Original chunk text. |
| `metadata` | JSON metadata for filtering and traceability. |
| `embedding` | `vector(768)` numeric embedding. |

Application metadata includes:

```text
source
 type
owner
visibility
documentId
jobId
chunkId
chunkIndex
totalChunks
```

### 11.4 Why metadata is important

The query layer uses metadata filters:

```text
visibility == 'PUBLIC'
owner == '<current user UUID>'
```

If a vector is missing owner or visibility metadata, authorization filtering can produce incorrect retrieval results.

### 11.5 Embedding request usage

Gemini Embeddings is used in two places:

1. During upload, once per document chunk.
2. During RAG search, to embed the user's question before similarity search.

The chat model is separate from the embedding model. The chat model receives the question and retrieved context to generate the answer.

---

## 12. Configuration and environment variables

The repository uses `application.properties` as the base configuration and `application-prod.properties` for production overrides.

### 12.1 Application/server

```text
PORT
SPRING_PROFILES_ACTIVE
CORS_ALLOWED_ORIGINS
OTP_ENABLED
```

### 12.2 Authentication and mail

```text
JWT_SECRET
JWT_EXPIRY
MAIL_USERNAME
MAIL_PASSWORD
```

### 12.3 Relational database

Production profile:

```text
DB_URL
DB_USERNAME
DB_PASSWORD
```

Local default:

```text
jdbc:h2:file:./data/users
```

### 12.4 Vector database

```text
VECTOR_DB_URL
VECTOR_DB_USERNAME
VECTOR_DB_PASSWORD
```

### 12.5 Gemini

```text
GEMINI_API_KEY
```

The source config currently uses:

```text
gemini-embedding-001
768 dimensions
```

### 12.6 Chat provider

Local default:

```text
Ollama
http://localhost:11434
llama3.2
```

Production profile:

```text
OPENROUTER_API_KEY
OPENROUTER_MODEL
```

The OpenRouter base URL includes `/v1` because the OpenAI-compatible client appends `/chat/completions`.

### 12.7 Kafka

```text
KAFKA_BOOTSTRAP
KAFKA_USER
KAFKA_PASSWORD
KAFKA_CA_PEM_PATH
```

The production Docker image uses:

```text
/app/certs/ca.pem
```

### 12.8 Embedding operations

```text
EMBEDDING_KAFKA_CONCURRENCY
EMBEDDING_MIN_INTERVAL_MS
EMBEDDING_RETRY_INTERVAL_MS
EMBEDDING_RETRY_MAX_ATTEMPTS
```

Current source defaults:

```text
concurrency = 1
minimum interval = 2000 ms
retry interval = 60000 ms
retry attempts = 4
```

Secrets must remain in local `.env` files or deployment secret storage and must not be written into this document.

---

## 13. Docker and deployment

### 13.1 Local Dockerfile

`Dockerfile` is intended for local development. It supports the local chat/Ollama setup and uses the local application configuration.

A container's file-backed H2 database is not automatically durable. Use a volume if local container data must survive container replacement.

### 13.2 Production Dockerfile

`Dockerfile.prod`:

1. Builds the application with Maven and Temurin 21.
2. Copies the packaged JAR into a slim Temurin runtime image.
3. Copies `ca.pem` to `/app/certs/ca.pem`.
4. Runs as non-root `appuser`.
5. Activates the `prod` Spring profile.
6. Exposes port 8080, while Spring uses Render's injected `PORT` value when present.

Render should use `Dockerfile.prod`, not the local Ollama Dockerfile.

### 13.3 Render health check

Configured health path:

```text
/actuator/health
```

Only the health endpoint is exposed through Actuator. Database and mail health indicators are disabled so slow external dependencies do not prevent the service from being marked live.

A green health check proves application liveness, not that Kafka, Gemini, OpenRouter, SMTP, or PgVector are fully operational.

### 13.4 Frontend deployment

The frontend is built with Vite and can be deployed as static files. `VITE_API_BASE_URL` is resolved at build time. A backend URL change requires a new frontend build.

---

## 14. Logging and observability

The application logs its own packages at DEBUG by default:

```properties
logging.level.com.aditya.rag=DEBUG
```

Important log markers:

| Marker | Meaning |
|---|---|
| `[startup]` | Effective profile, providers, port, and embedding path. |
| `[upload]` | HTTP upload request and queued job. |
| `[ingest]` | Parsing, Markdown conversion, and chunking. |
| `[embedding-job]` | Relational job creation and progress. |
| `[embedding-kafka]` | Kafka publication, reception, and processing. |
| `[embedding-rate-limit]` | Provider call spacing. |
| `[embedding]` | Vector-store processing and embedding timing. |
| `[embedding-dlt]` | Terminal DLT failures. |

The diagnostic logs intentionally avoid logging:

- Chunk contents.
- Gemini keys.
- Kafka passwords.
- Database passwords.
- Private certificates.

Useful startup checks:

```text
[startup] Application READY
[startup] embedding path : Kafka async (...)
```

Useful Kafka checks:

```text
embedding-jobs partitions assigned
embedding-jobs.DLT partitions assigned
```

Useful successful embedding checks:

```text
[embedding] vector store call complete
[embedding] embedded ...
```

---

## 15. Failure modes and recovery

### 15.1 Missing Kafka topic

Symptom:

```text
UNKNOWN_TOPIC_OR_PARTITION
```

Cause: a manually required topic does not exist or the application is connected to the wrong cluster.

Fix:

- Create `embedding-jobs` and `embedding-jobs.DLT` in the same Aiven service.
- Use one partition initially.
- Verify the Render bootstrap address.
- Restart the service after topic creation.

### 15.2 Gemini quota exhaustion

Symptoms:

```text
429
Quota exceeded
EmbedContentRequestsPerMinute
EmbedContentRequestsPerDay
```

Impact:

- Upload embedding messages fail or go to the DLT after retries.
- RAG queries fail during query embedding before the chat model is called.

Recovery:

- Wait for the provider quota reset.
- Reduce document/request volume.
- Increase quota or enable billing where appropriate.
- Use a different embedding provider/model with compatible dimensions.
- Do not assume a 48-second retry hint resets a daily quota.

### 15.3 PgVector UUID error

Symptom:

```text
UUID string too large
```

Cause: a non-UUID chunk key was used as `Document.id`.

Current fix: derive a deterministic UUID from the durable chunk key and retain the original key in metadata.

### 15.4 Vector dimension mismatch

Cause: changing the embedding model or output dimensions without migrating the vector table.

Current expectation:

```text
vector(768)
```

The embedding model, PgVector configuration, and stored data must use compatible dimensions.

### 15.5 Kafka publish failure during upload

The job and chunk rows are created before the message batch is fully published. If publication fails part way through:

- The job is marked failed.
- Messages already published may still be consumed.
- There is no compensation transaction that deletes already-published Kafka messages.

Inspect job status and Kafka logs before retrying the upload.

### 15.6 Application restart during embedding

Kafka uses at-least-once delivery. If the consumer stops before the listener successfully returns:

- The offset may not be committed.
- The message can be redelivered.
- The durable chunk status can be inspected.
- The deterministic vector ID helps keep retries stable.

### 15.7 RAG stream failure

A streaming response can fail after the HTTP stream has begun. In that case, the client may receive an incomplete answer and the backend may not execute `doOnComplete`, so the assistant message may not be saved.

---

## 16. Operational checklist

### Startup

- Confirm active profile is `prod` in Render.
- Confirm chat provider is OpenRouter/OpenAI-compatible in production.
- Confirm embedding provider and model.
- Confirm `[startup] Application READY`.
- Confirm the server uses Render's `PORT`.

### Kafka

- Confirm `embedding-jobs` exists.
- Confirm `embedding-jobs.DLT` exists.
- Confirm both listeners receive partition assignments.
- Confirm `KAFKA_CA_PEM_PATH=/app/certs/ca.pem` in production.
- Confirm `concurrency=1` while using Gemini free-tier limits.

### Upload

- Confirm upload returns HTTP 202.
- Save the returned job ID.
- Confirm job status starts as `QUEUED` or `PROCESSING`.
- Confirm Kafka receives the expected number of chunks.

### Embedding

- Confirm `[embedding] vector store call start`.
- Confirm `[embedding] vector store call complete`.
- Confirm chunk progress increases.
- Inspect `lastError` and the DLT for failures.

### Query

- Confirm vectors contain owner and visibility metadata.
- Confirm public/private filters return expected documents.
- Remember that each similarity search embeds the user question through Gemini.
- Confirm the chat provider is available separately from the embedding provider.

---

## 17. Source map

### Application entry and configuration

```text
src/main/java/com/aditya/rag/LocalRagApplication.java
src/main/java/com/aditya/rag/config/SecurityConfig.java
src/main/java/com/aditya/rag/config/EmbeddingKafkaConfig.java
src/main/java/com/aditya/rag/config/VectorStoreConfig.java
src/main/java/com/aditya/rag/config/ChatMemoryConfig.java
src/main/java/com/aditya/rag/config/WebConfig.java
src/main/java/com/aditya/rag/config/StartupInfoLogger.java
```

### Controllers

```text
src/main/java/com/aditya/rag/controller/AuthController.java
src/main/java/com/aditya/rag/controller/ConversationController.java
src/main/java/com/aditya/rag/controller/FileUploadController.java
src/main/java/com/aditya/rag/controller/EmbeddingJobController.java
src/main/java/com/aditya/rag/controller/RagController.java
```

### Embedding pipeline

```text
src/main/java/com/aditya/rag/service/DataIngestionService.java
src/main/java/com/aditya/rag/service/RecursiveCharacterTextSplitter.java
src/main/java/com/aditya/rag/service/EmbeddingJobService.java
src/main/java/com/aditya/rag/service/EmbeddingJobProducer.java
src/main/java/com/aditya/rag/service/EmbeddingJobConsumer.java
src/main/java/com/aditya/rag/service/EmbeddingRateLimiter.java
src/main/java/com/aditya/rag/kafka/EmbeddingJobMessage.java
```

### Persistence

```text
src/main/java/com/aditya/rag/entity/User.java
src/main/java/com/aditya/rag/entity/VerifyOtp.java
src/main/java/com/aditya/rag/entity/Conversation.java
src/main/java/com/aditya/rag/entity/EmbeddingJob.java
src/main/java/com/aditya/rag/entity/EmbeddingChunk.java
src/main/java/com/aditya/rag/repo/UserRepository.java
src/main/java/com/aditya/rag/repo/VerifyOtpRepository.java
src/main/java/com/aditya/rag/repo/ConversationRepository.java
src/main/java/com/aditya/rag/repo/EmbeddingJobRepository.java
src/main/java/com/aditya/rag/repo/EmbeddingChunkRepository.java
```

### Frontend

```text
frontend/src/main.jsx
frontend/src/App.jsx
frontend/src/Auth.jsx
frontend/src/Chat.jsx
frontend/src/api.js
frontend/src/index.css
```

---

## 18. Confirmed versus provider-managed behavior

### Confirmed by application source

- HTTP endpoint paths.
- JWT claims and authentication filter behavior.
- OTP expiration and bypass mode.
- File parsing and chunking.
- Job/chunk database fields and state transitions.
- Kafka topics, groups, serializers, retries, and DLT routing.
- Gemini embedding integration.
- Vector metadata and deterministic vector IDs.
- Public/private retrieval filters.
- Chat memory usage and streaming format.
- Separate relational and vector datasource wiring.
- Docker production certificate path and health endpoint.

### Not defined directly by application source

- Exact PgVector SQL DDL emitted by the Spring AI version.
- Exact PgVector duplicate/upsert behavior for repeated IDs.
- Kafka retention and broker-level topic policies.
- Aiven PostgreSQL/Kafka provisioning and backups.
- Render service settings and restart policy.
- Provider quota reset timing.
- Database migration history; the project uses Hibernate schema update and framework initialization instead of application-owned migration scripts.

These items must be verified in the deployed framework/provider environment rather than inferred as application code behavior.
