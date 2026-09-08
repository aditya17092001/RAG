# Kafka and Asynchronous Embeddings Guide

This document explains Kafka from the beginning and then explains exactly how Kafka is used in this RAG application.

It is written for someone who has not used Kafka before.

> **Security note:** Never put the Kafka password, database passwords, Gemini keys, JWT secrets, or other private credentials in this document or in Git. Values shown below are safe connection details or placeholders only.

## 1. What problem are we solving?

When a user uploads a document, the application must perform several operations:

1. Read the file.
2. Extract its text.
3. Split the text into smaller chunks.
4. Send every chunk to the Gemini embedding model.
5. Store the resulting vectors in Aiven PostgreSQL with pgvector.
6. Make the vectors available for RAG search.

The embedding step uses a remote API. It can be slow, rate-limited, or temporarily unavailable. If the HTTP upload request performs all of those operations directly, the user must wait for every embedding request to finish. A temporary Gemini or database failure can also make the entire upload return an error.

We use Kafka to separate **accepting the upload** from **processing the embeddings**.

```text
Upload request
    |
    | parse and split the file
    v
Kafka: embedding-jobs
    |
    | one message per chunk
    v
Embedding consumer
    |
    | call Gemini
    v
Aiven PgVector
```

### Why are we doing this?

Kafka gives us a durable queue between the upload operation and the embedding worker. The upload can return quickly after the chunks are safely queued. The worker can process chunks slowly and safely, retry temporary failures, and send permanent failures to a dead-letter topic instead of losing them.

## 2. Kafka in simple terms

Kafka is a distributed event streaming platform. For this project, the easiest way to understand it is as a durable, ordered queue.

### Producer

A **producer** sends messages to Kafka.

In this application:

```text
EmbeddingJobProducer
```

is the producer. It sends one message for each document chunk to the `embedding-jobs` topic.

### Consumer

A **consumer** reads messages from Kafka and performs work.

In this application:

```text
EmbeddingJobConsumer
```

reads chunk messages and calls `EmbeddingJobService.process(...)`.

### Topic

A **topic** is a named stream of messages. It is similar to a named queue.

This application uses exactly two topics:

| Topic | Purpose |
|---|---|
| `embedding-jobs` | Normal queue containing chunks waiting to be embedded |
| `embedding-jobs.DLT` | Dead-letter topic containing chunks that failed permanently |

### Partition

A topic is divided into partitions. A partition is an ordered log of messages.

We use one partition because:

- the Aiven free service has a limited number of partitions;
- one consumer is enough for the initial workload;
- chunks are processed sequentially;
- sequential processing makes the Gemini rate limit easier to respect.

With one partition, Kafka delivers messages in order within that partition.

### Consumer group

A consumer group identifies one logical application worker.

The normal consumer group is:

```text
rag-embedding
```

Kafka uses the group to remember which messages have already been consumed. If the application restarts, the consumer continues from its committed offset instead of starting randomly.

The DLT listener uses a separate group:

```text
rag-embedding-dlt
```

This keeps normal processing and permanent-failure processing separate.

### Offset

An offset is the position of a message in a partition. Kafka does not immediately delete a message after a consumer reads it. The consumer commits its progress after successful processing.

If processing fails before the offset is committed, Kafka can deliver the message again. This is why the consumer and database operations must tolerate redelivery.

### Key

Each message has a key. This application uses the deterministic chunk ID as the Kafka key:

```text
<documentId>:<chunkIndex>
```

The key gives related messages stable partition routing. With one partition, all messages still go to the same partition, but the key remains useful if the application later grows to multiple partitions.

## 3. Why not use a Java thread directly?

A local `ExecutorService` or `CompletableFuture` can run work in the background, but it is not a durable queue.

If the application process stops while a thread is embedding a chunk:

- the in-memory task can disappear;
- the task is not automatically available after restart;
- there is no built-in retry history;
- multiple application instances need additional coordination;
- it is harder to inspect failed work.

Kafka stores the message outside the application process. That gives us persistence, consumer offsets, retry handling, and a place to inspect failed messages.

Kafka is not automatically better for every small task. It is useful here because Gemini embedding is remote work that may be slow, rate-limited, and worth retrying independently from the upload request.

## 4. Aiven Kafka setup

The application connects to the Aiven Kafka service shown in the Aiven connection screen.

Safe connection values:

```properties
KAFKA_BOOTSTRAP=kafka-20ebbf8e-adityaudata-073b.e.aivencloud.com:15311
KAFKA_USER=avnadmin
KAFKA_PASSWORD=<store privately>
KAFKA_CA_PEM_PATH=<environment-specific path>
```

The Aiven screen shows:

- Authentication: `SASL`
- Host: `kafka-20ebbf8e-adityaudata-073b.e.aivencloud.com`
- Port: `15311`
- User: `avnadmin`

### Why use SASL?

SASL authenticates the application to Kafka. Kafka needs to know which user is connecting before it allows the application to produce or consume messages.

### Why use SASL over SSL?

The application uses:

```properties
spring.kafka.security.protocol=SASL_SSL
```

`SASL` provides authentication. `SSL` encrypts the connection and verifies the Kafka server certificate. Both are needed for a secure production connection.

### Why use SCRAM-SHA-256?

The application uses:

```properties
spring.kafka.properties.sasl.mechanism=SCRAM-SHA-256
```

SCRAM is the username/password authentication mechanism selected by the client. Aiven supports SCRAM-SHA-256. The mechanism configured in the application must also be enabled for the Aiven service.

### Why use the CA PEM file?

The CA certificate allows the Kafka client to verify that it is talking to the correct Aiven server rather than an impostor. The application configures Kafka to read it as a native PEM truststore:

```properties
spring.kafka.properties.ssl.truststore.type=PEM
spring.kafka.properties.ssl.truststore.location=${KAFKA_CA_PEM_PATH:src/main/resources/ca.pem}
```

The certificate is not a password or private key. It is still best to manage deployment files carefully and never commit private certificates or keys.

## 5. Create the Aiven topics

Create the following two topics manually in the Aiven Kafka service:

| Topic | Partitions | Purpose |
|---|---:|---|
| `embedding-jobs` | 1 | Normal embedding queue |
| `embedding-jobs.DLT` | 1 | Permanent failures after retries |

### Why create topics manually?

The Aiven free service has a small topic and partition limit. Manual creation makes the resource usage predictable. It also prevents accidental creation of many retry topics such as:

```text
embedding-jobs-retry-0
embedding-jobs-retry-1
embedding-jobs-retry-2
```

The application therefore retries the same normal topic and sends final failures directly to the DLT.

### Why disable Kafka auto-topic creation?

The application sets:

```properties
spring.kafka.consumer.properties.allow.auto.create.topics=false
```

This is intentional. A spelling mistake in a topic name should fail visibly instead of silently creating another topic and consuming Aiven's topic quota.

## 6. The complete application flow

### Step 1: User uploads a file

The frontend sends a multipart request to:

```text
POST /upload
```

The request contains:

- the file;
- the visibility, either `PUBLIC` or `PRIVATE`;
- the authenticated user's JWT.

Relevant class:

```text
FileUploadController
```

### Why do this step?

The controller is the authenticated entry point. It identifies the owner and validates the upload before any document processing begins. The owner must travel with every chunk so private documents remain protected during RAG retrieval.

### Step 2: Extract text from the file

Relevant class:

```text
DataIngestionService
```

Apache Tika reads formats such as PDF, DOCX, TXT, and HTML. The extracted content is converted to Markdown using Flexmark.

### Why do this step?

Embedding models need text, not a raw PDF or DOCX binary. Converting to Markdown preserves useful structure such as headings, lists, and tables better than treating the entire file as an unstructured byte stream.

### Step 3: Split the text into chunks

The application uses a recursive text splitter with:

```text
Chunk size: 1000 characters
Overlap:    200 characters
```

### Why do this step?

Embedding APIs have input-size limits, and an entire document may be too large to embed as one request. Smaller chunks also improve search accuracy because each vector represents a focused piece of information.

The overlap repeats some text between neighboring chunks. This helps preserve context when an important sentence is split across a chunk boundary.

### Step 4: Create a job and chunk records

The application creates:

```text
embedding_jobs
embedding_chunks
```

The job stores document-level progress:

- job ID;
- document ID;
- owner ID;
- filename;
- visibility;
- total chunk count;
- completed count;
- failed count;
- current status;
- last error.

Each chunk stores:

- deterministic chunk ID;
- job ID;
- document ID;
- chunk index;
- chunk text;
- owner ID;
- visibility;
- attempt count;
- processing status;
- last error.

### Why store this state in the database?

Kafka knows whether a message was consumed, but Kafka alone does not provide a convenient application-level document progress view. The relational records let the UI ask whether a document is queued, processing, completed, or failed.

The chunk records also help the consumer handle duplicate delivery safely. If a chunk is already marked `EMBEDDED`, it can be skipped instead of creating another vector unnecessarily.

### Step 5: Publish one message per chunk

Relevant class:

```text
EmbeddingJobProducer
```

The producer sends one JSON message to:

```text
embedding-jobs
```

An example message shape is:

```json
{
  "jobId": "job-uuid",
  "documentId": "document-uuid",
  "chunkId": "document-uuid:0",
  "chunkIndex": 0,
  "totalChunks": 4,
  "text": "The text of this chunk...",
  "filename": "manual.pdf",
  "fileType": "pdf",
  "ownerId": "user-uuid",
  "visibility": "PRIVATE"
}
```

### Why put the text in Kafka?

The consumer must be able to process the chunk after the HTTP request ends. Sending the extracted text makes the message self-contained. Sending only a local temporary-file path would fail after a restart or on another server because that file may not exist there.

### Why return HTTP 202?

The upload endpoint returns `202 Accepted` because the request has been accepted for background processing, but embedding is not complete yet.

The response contains a `jobId`. The client uses that ID to check progress.

### Step 6: Consume a chunk

Relevant classes:

```text
EmbeddingJobConsumer
EmbeddingJobService
```

The consumer reads one message and calls:

```java
jobService.process(message)
```

The listener uses one consumer and one record per poll initially:

```properties
spring.kafka.consumer.max-poll-records=1
app.embedding.kafka.concurrency=1
```

### Why process one chunk at a time?

Gemini has request and quota limits. Processing one chunk at a time prevents the application from creating a large burst of embedding requests. It also keeps memory and database usage small on the free-tier services.

### Step 7: Rate-limit the embedding call

Relevant class:

```text
EmbeddingRateLimiter
```

The default interval is one second:

```properties
app.embedding.min-interval-ms=1000
```

### Why add an application-level rate limiter?

Kafka can deliver messages much faster than Gemini can accept them. Kafka controls message delivery, but it does not know the embedding provider's quota. The rate limiter protects Gemini from a burst of requests.

This is a conservative starting value. The actual provider quota can vary by model, account, and current provider policy. A one-second delay is approximately 60 requests per minute, before considering retries.

### Step 8: Create and store the embedding

The consumer builds a Spring AI `Document` containing:

- the deterministic chunk ID;
- the chunk text;
- source filename;
- file type;
- owner;
- visibility;
- document ID;
- job ID;
- chunk index;
- total chunk count.

Then it calls:

```java
vectorStore.add(List.of(document));
```

The configured `VectorStore` uses:

```text
Google Gemini → embedding vector → Aiven PgVector
```

### Why preserve metadata?

The vector similarity search must enforce document visibility. The existing RAG retrieval uses:

```text
visibility == 'PUBLIC'
```

for public documents and:

```text
owner == '<current user ID>'
```

for the current user's private documents. If the asynchronous consumer dropped the owner or visibility metadata, private documents could not be filtered safely.

### Step 9: Update progress

After successful vector storage:

1. the chunk becomes `EMBEDDED`;
2. the completed chunk count is recalculated;
3. the job becomes `COMPLETED` when all chunks are embedded.

Possible job statuses:

| Status | Meaning |
|---|---|
| `QUEUED` | Job and chunks were created and messages are being published |
| `PROCESSING` | At least one chunk is being processed |
| `COMPLETED` | Every chunk was embedded and stored |
| `FAILED` | At least one chunk ended in the DLT or queue publishing failed |

## 7. Retry and dead-letter behavior

The normal topic is:

```text
embedding-jobs
```

The DLT is:

```text
embedding-jobs.DLT
```

The configured defaults are:

```properties
app.embedding.retry.interval-ms=10000
app.embedding.retry.max-attempts=4
```

### What happens when Gemini temporarily fails?

Example temporary failures include:

- HTTP 429 rate limiting;
- a temporary provider outage;
- a temporary network failure;
- a temporarily unavailable vector database.

The listener throws an exception. Kafka does not commit the message. The `DefaultErrorHandler` waits ten seconds and tries again.

After the configured retry attempts are exhausted, the `DeadLetterPublishingRecoverer` publishes the message to:

```text
embedding-jobs.DLT
```

### Why retry instead of immediately failing?

Remote services can fail briefly. Retrying avoids losing a document because of one temporary network or quota error.

### Why use a DLT?

Some failures will not be fixed by retrying, such as:

- malformed JSON;
- invalid job metadata;
- a permanently invalid document state;
- an embedding request that repeatedly fails.

The DLT preserves the failed message for inspection. The DLT listener marks the corresponding chunk and job as failed so the UI can show the error instead of leaving the job stuck forever.

## 8. Check embedding progress

After uploading, save the returned `jobId` and call:

```text
GET /embedding-jobs/{jobId}
```

Example response:

```json
{
  "jobId": "job-uuid",
  "documentId": "document-uuid",
  "filename": "manual.pdf",
  "visibility": "PRIVATE",
  "totalChunks": 4,
  "completedChunks": 2,
  "failedChunks": 0,
  "status": "PROCESSING",
  "lastError": null,
  "createdAt": "2026-08-29T12:00:00Z",
  "updatedAt": "2026-08-29T12:00:20Z"
}
```

### Why protect this endpoint with ownership checks?

A user should not be able to inspect another user's filename, progress, or failure details. The endpoint checks that the job owner matches the authenticated JWT subject.

## 9. Configuration reference

### Kafka connection

```properties
spring.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP}
spring.kafka.security.protocol=SASL_SSL
spring.kafka.properties.sasl.mechanism=SCRAM-SHA-256
spring.kafka.properties.sasl.jaas.config=...
spring.kafka.properties.ssl.truststore.type=PEM
spring.kafka.properties.ssl.truststore.location=${KAFKA_CA_PEM_PATH:src/main/resources/ca.pem}
```

### Producer and consumer serialization

```properties
spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer
spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JsonSerializer
spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer
spring.kafka.consumer.value-deserializer=org.springframework.kafka.support.serializer.JsonDeserializer
```

### Queue and retry settings

```properties
app.embedding.kafka.topic=embedding-jobs
app.embedding.kafka.dlt-topic=embedding-jobs.DLT
app.embedding.kafka.concurrency=1
app.embedding.min-interval-ms=1000
app.embedding.retry.interval-ms=10000
app.embedding.retry.max-attempts=4
```

### Why use environment variables?

Connection details and secrets differ between a developer laptop and production. Environment variables let the same application image use different Kafka, database, and API credentials without putting secrets in source code.

## 10. Local versus production PEM paths

### Local JVM run

When running directly from the project folder, use:

```powershell
$env:KAFKA_CA_PEM_PATH="src/main/resources/ca.pem"
mvn spring-boot:run
```

### Production Docker run or Render

The production Dockerfile copies the resource into:

```text
/app/certs/ca.pem
```

Use:

```text
KAFKA_CA_PEM_PATH=/app/certs/ca.pem
```

### Why are the paths different?

A resource file exists in the project directory during local development. Inside the production container, the application runs in a different filesystem. `Dockerfile.prod` copies the certificate to a known absolute path so the Kafka client can read it.

## 11. How to test the implementation

### Check the application starts

Start the backend with valid environment variables. The logs should show the Kafka consumers subscribing to:

```text
embedding-jobs
embedding-jobs.DLT
```

The health endpoint is:

```text
GET /actuator/health
```

### Upload a small test file

Use the UI or a request such as:

```powershell
curl.exe -X POST "http://localhost:8080/upload?visibility=PRIVATE" `
  -H "Authorization: Bearer <JWT_TOKEN>" `
  -F "file=@.\test.txt"
```

The response should be `202 Accepted` and contain a `jobId`.

### Poll the job

```powershell
curl.exe "http://localhost:8080/embedding-jobs/<JOB_ID>" `
  -H "Authorization: Bearer <JWT_TOKEN>"
```

### Check the database

For the relational database:

```sql
SELECT id, filename, total_chunks, completed_chunks,
       failed_chunks, status, last_error
FROM embedding_jobs
ORDER BY created_at DESC;
```

For chunk progress:

```sql
SELECT job_id, chunk_index, status, attempts, last_error
FROM embedding_chunks
ORDER BY created_at DESC, chunk_index;
```

For vectors:

```sql
SELECT count(*) FROM vector_store;
```

### Why test with a small file first?

A small file produces fewer embedding requests and makes it easier to verify each step before testing larger documents or rate-limit behavior.

## 12. Troubleshooting

### `No qualifying bean of type KafkaTemplate`

The producer configuration is missing or the application did not load the Kafka configuration class. Confirm that `EmbeddingKafkaConfig` is under the application's component-scan package and that `spring-kafka` is in `pom.xml`.

### `UnknownTopicOrPartitionException`

One or both topics are missing. Create these exact topics in Aiven:

```text
embedding-jobs
embedding-jobs.DLT
```

The application intentionally does not auto-create them.

### `NoSuchFileException: /app/certs/ca.pem`

The production path is being used outside the production container. For a local JVM run, set:

```powershell
$env:KAFKA_CA_PEM_PATH="src/main/resources/ca.pem"
```

For Docker/Render, confirm that `Dockerfile.prod` was used and that the path is:

```text
/app/certs/ca.pem
```

### SASL authentication failure

Check all of the following:

- `KAFKA_BOOTSTRAP` contains only `host:port`;
- the port is the SASL port `15311`, not the client-certificate port;
- `KAFKA_USER` is correct;
- `KAFKA_PASSWORD` is correct;
- the Aiven service has SASL enabled;
- SCRAM-SHA-256 is enabled in Aiven;
- the application uses `SASL_SSL`.

### Job remains `QUEUED`

Check the application logs for:

- Kafka connection errors;
- missing topic errors;
- producer send failures;
- invalid PEM path errors.

Also verify that a consumer is running with group ID:

```text
rag-embedding
```

### Job becomes `FAILED`

Inspect:

```text
lastError in embedding_jobs
lastError in embedding_chunks
```

Then inspect the `embedding-jobs.DLT` topic in Aiven. The message was moved there after the configured retries were exhausted.

### Vectors are not searchable

Confirm that:

- the job status is `COMPLETED`;
- `vector_store` contains new rows;
- the embedding model dimensions match the vector table dimensions;
- each vector document contains `owner` and `visibility` metadata.

## 13. Important design limitations

The current implementation makes file parsing and chunking part of the upload request. The embedding and vector-storage work is asynchronous.

```text
Synchronous:  upload → parse → split → publish Kafka messages
Asynchronous: Kafka message → Gemini → PgVector
```

This is intentional because Kafka messages contain the extracted chunk text, not a reference to an uploaded file. If parsing itself must also return immediately, the next architectural step would be to store the original file in object storage or a durable file store and enqueue a document-processing job before parsing.

The current design also processes one chunk at a time. This is slower than using multiple consumers, but it is safer for the initial Gemini quota and Aiven free-tier limits. Concurrency can be increased later after measuring provider limits and database capacity.

## 14. Source-code map

| Responsibility | Class/file |
|---|---|
| Receive authenticated upload | `FileUploadController` |
| Extract Markdown and split chunks | `DataIngestionService` |
| Store job/chunk progress | `EmbeddingJobService`, `EmbeddingJob`, `EmbeddingChunk` |
| Publish Kafka JSON messages | `EmbeddingJobProducer` |
| Kafka producer/consumer/error configuration | `EmbeddingKafkaConfig` |
| Consume normal embedding jobs | `EmbeddingJobConsumer` |
| Rate-limit Gemini calls | `EmbeddingRateLimiter` |
| Call embedding model and PgVector | `EmbeddingJobService` and `VectorStoreConfig` |
| Check owned job status | `EmbeddingJobController` |
| Store job state | `EmbeddingJobRepository`, `EmbeddingChunkRepository` |
| Configure topics and retry values | `src/main/resources/application.properties` |
| Package production PEM path | `Dockerfile.prod` |

## 15. Short summary

Kafka is being used as a durable queue between document chunking and embedding.

The important reasons are:

1. Upload requests do not wait for every Gemini request.
2. One chunk becomes one independently retryable message.
3. Kafka keeps work available if the application restarts.
4. The one-consumer design controls Gemini request rate.
5. Failed messages are preserved in `embedding-jobs.DLT`.
6. Database job and chunk records make progress visible.
7. Owner and visibility metadata remain attached to every vector.
8. Manually created topics keep the Aiven free-tier resource usage predictable.

## 16. Official reference

For Aiven-specific SASL authentication options and service configuration, see the [Aiven Kafka SASL authentication documentation](https://aiven.io/docs/products/kafka/howto/kafka-sasl-auth).

The Aiven-specific explanation in this guide was summarized and rephrased for this project; consult Aiven's documentation for the current provider settings and supported authentication mechanisms.

## 17. Complete Kafka lifecycle: from startup to final vector

This section follows one uploaded document through every important stage.

### 17.1 The complete picture

```text
User
  |
  | 1. POST /upload with a file and JWT
  v
FileUploadController
  |
  | 2. Validate owner, file, and visibility
  v
DataIngestionService
  |
  | 3. Tika extracts text, Flexmark converts to Markdown,
  |    RecursiveCharacterTextSplitter creates chunks
  v
EmbeddingJobService.createJob(...)
  |
  | 4. Save embedding_jobs and embedding_chunks
  |    before sending Kafka messages
  v
EmbeddingJobProducer
  |
  | 5. Serialize one chunk message as JSON
  v
KafkaTemplate
  |
  | 6. Connect to Aiven using SASL_SSL and publish
  v
Aiven Kafka: embedding-jobs
  |
  | 7. Store the record in the topic partition
  v
EmbeddingJobConsumer
  |
  | 8. Poll one record and deserialize JSON
  v
EmbeddingJobService.process(...)
  |
  | 9. Mark chunk PROCESSING and apply rate limit
  v
vectorStore.add(List.of(document))
  |
  | 10. Gemini creates a vector
  | 11. PgVectorStore writes text, vector, and metadata
  v
Aiven PostgreSQL: vector_store
  |
  | 12. Mark chunk EMBEDDED and update job progress
  v
Kafka offset committed
```

The most important distinction is:

```text
Kafka transports the chunk.
Gemini creates the embedding.
PgVector stores and searches the embedding.
```

Kafka does not create vectors, and PostgreSQL does not call Gemini. Each system has a separate responsibility.

### 17.2 Stage 0: application startup

When Spring Boot starts, it discovers `EmbeddingKafkaConfig` because it is a Spring `@Configuration` class.

That configuration creates:

```text
ProducerFactory
KafkaTemplate
ConsumerFactory
DefaultErrorHandler
ConcurrentKafkaListenerContainerFactory
```

The application also discovers the methods annotated with `@KafkaListener` in `EmbeddingJobConsumer`.

The two listeners are:

```java
@KafkaListener(
        topics = "${app.embedding.kafka.topic:embedding-jobs}",
        groupId = "${spring.kafka.consumer.group-id:rag-embedding}",
        containerFactory = "embeddingKafkaListenerContainerFactory")
public void consume(EmbeddingJobMessage message) { ... }
```

and:

```java
@KafkaListener(
        topics = "${app.embedding.kafka.dlt-topic:embedding-jobs.DLT}",
        groupId = "${app.embedding.kafka.dlt-group:rag-embedding-dlt}",
        containerFactory = "embeddingKafkaListenerContainerFactory")
public void consumeDeadLetter(EmbeddingJobMessage message) { ... }
```

### Why do listeners start during application startup?

A Kafka consumer must be connected and waiting before a message can be processed. Starting the listeners with the application means the service is ready to process queued work immediately after startup.

The application creates two consumer groups:

```text
rag-embedding       → normal chunk processing
rag-embedding-dlt   → permanent failure handling
```

### 17.3 Stage 1: Kafka client reads connection settings

The producer and consumer both use these settings:

```properties
spring.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP}
spring.kafka.security.protocol=SASL_SSL
spring.kafka.properties.sasl.mechanism=SCRAM-SHA-256
spring.kafka.properties.sasl.jaas.config=...
spring.kafka.properties.ssl.truststore.type=PEM
spring.kafka.properties.ssl.truststore.location=${KAFKA_CA_PEM_PATH:src/main/resources/ca.pem}
```

From the Aiven connection screen, the safe connection values are:

```text
Host: kafka-20ebbf8e-adityaudata-073b.e.aivencloud.com
Port: 15311
User: avnadmin
Authentication: SASL
```

The application uses the combined bootstrap value:

```text
kafka-20ebbf8e-adityaudata-073b.e.aivencloud.com:15311
```

The password is loaded from an environment variable and must not be written into this guide.

### Why does the client need the bootstrap server?

The bootstrap server is the initial address used to contact the Kafka cluster. After connecting, Kafka provides the metadata needed to locate the requested topic and partition.

### Why does the client need the CA certificate?

The CA certificate allows the Java Kafka client to verify the TLS certificate presented by Aiven. Without certificate verification, the client could not safely confirm the identity of the Kafka server.

### 17.4 Stage 2: TLS and SASL authentication

The connection is established in two security layers:

```text
1. TLS/SSL establishes an encrypted connection and verifies Aiven.
2. SASL/SCRAM authenticates the Kafka username and password.
```

The order is conceptually:

```text
Application
    |
    | TLS handshake using CA certificate
    v
Aiven Kafka server identity verified
    |
    | SASL SCRAM username/password authentication
    v
Kafka connection authorized
```

### Why use both TLS and SASL?

SASL answers:

> Who is this client?

TLS answers:

> Is this really the Aiven Kafka server, and is the traffic encrypted?

Using only a username and password without TLS would expose credentials over the network. Using TLS without SASL would encrypt the connection but would not identify the Kafka user in the intended way.

### 17.5 Stage 3: user uploads a document

The user sends:

```text
POST /upload
```

The request contains a file and the JWT access token. `FileUploadController` obtains the user ID from the authenticated security context.

It validates:

- the file is not empty;
- the filename is usable;
- visibility is `PUBLIC` or `PRIVATE`.

Then it calls:

```java
dataIngestion.queueFile(
        file.getResource(),
        filename,
        userId,
        visibility);
```

### Why validate before publishing?

Invalid data should not enter Kafka. Validation at the HTTP boundary prevents the consumer from receiving messages that can never be processed successfully.

### 17.6 Stage 4: file parsing and chunking

`DataIngestionService` performs the synchronous preparation work:

```text
Multipart file
    ↓
Apache Tika
    ↓
Extracted HTML/text
    ↓
Flexmark
    ↓
Markdown
    ↓
RecursiveCharacterTextSplitter
    ↓
List<Document>
```

This happens before Kafka because the Kafka message contains the extracted chunk text. The consumer does not need access to the original uploaded file.

### Important timing detail

The current design is:

```text
Synchronous:  upload → parse → split → publish Kafka messages
Asynchronous: Kafka → Gemini → PgVector
```

The upload request is not completely asynchronous. It still waits for parsing, chunking, database job creation, and Kafka publishing. It does not wait for Gemini embedding or vector storage.

### Why send extracted text instead of a file path?

A local file path is not reliable in a distributed system. The consumer may run:

- after the upload request has finished;
- after the application has restarted;
- inside a different container;
- on a different machine.

The extracted chunk text is self-contained and can be processed anywhere that can reach Gemini and PostgreSQL.

### 17.7 Stage 5: create the database job state

`EmbeddingJobService.createJob(...)` creates a document ID and a job record.

The job starts as:

```text
status = QUEUED
completedChunks = 0
failedChunks = 0
```

For every chunk, it creates an `EmbeddingChunk` record with:

```text
status = QUEUED
attempts = 0
```

For a document with three chunks, the records look conceptually like:

```text
embedding_jobs
---------------
job_id       total_chunks   completed   failed   status
job-123      3              0           0        QUEUED

embedding_chunks
----------------
chunk_id       index   status   attempts
job-123:0      0       QUEUED   0
job-123:1      1       QUEUED   0
job-123:2      2       QUEUED   0
```

### Why save the database state first?

The consumer can begin processing as soon as Kafka receives a message. Saving the job and chunks first ensures that the consumer can find the corresponding database records.

The `createJob(...)` method is transactional. Its database changes are committed before `publish(...)` sends the messages.

### Important limitation: database and Kafka are separate systems

The database transaction and Kafka publish are not one atomic transaction in this implementation.

The current sequence is:

```text
1. Save job and chunks
2. Commit database transaction
3. Publish chunk 0
4. Publish chunk 1
5. Publish chunk 2
```

If publishing chunk 2 fails after chunks 0 and 1 were published, those earlier Kafka messages remain available but the upload operation marks the job as failed.

This is acceptable for the current simple implementation, but a production system could use an outbox pattern to make database state and message publication more reliable as one workflow.

### 17.8 Stage 6: build the Kafka message

For every chunk, the application creates an `EmbeddingJobMessage`:

```java
messages.add(new EmbeddingJobMessage(
        job.getId(),
        documentId,
        chunkId,
        index,
        documents.size(),
        text,
        filename,
        fileType,
        ownerId,
        visibility));
```

The message contains everything the consumer needs:

| Field | Reason |
|---|---|
| `jobId` | Update the document-level job state |
| `documentId` | Identify the source document |
| `chunkId` | Idempotency key and vector ID |
| `chunkIndex` | Know the chunk's position |
| `totalChunks` | Calculate progress |
| `text` | Text to send to Gemini |
| `filename` | Preserve source metadata |
| `fileType` | Preserve file metadata |
| `ownerId` | Enforce private-document retrieval |
| `visibility` | Apply public/private filtering |

### Why include metadata in the message?

Kafka messages should be self-contained. The consumer should not need to guess the owner, look up a temporary file, or reconstruct the document from incomplete information.

Missing `ownerId` or `visibility` would be a security problem because the vector search filters depend on those values.

### 17.9 Stage 7: serialize the message to JSON

The producer is configured with:

```properties
spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer
spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JsonSerializer
```

The producer sends:

```java
kafkaTemplate.send(topic, message.chunkId(), message)
```

The Kafka key is:

```text
message.chunkId()
```

The Kafka value is the complete `EmbeddingJobMessage` object.

Conceptually, Java turns this object:

```java
EmbeddingJobMessage(...)
```

into a JSON record similar to:

```json
{
  "jobId": "job-123",
  "documentId": "document-456",
  "chunkId": "document-456:0",
  "chunkIndex": 0,
  "totalChunks": 3,
  "text": "Text from the document chunk",
  "filename": "manual.pdf",
  "fileType": "pdf",
  "ownerId": "user-789",
  "visibility": "PRIVATE"
}
```

### Why use JSON?

JSON is human-readable, easy to inspect, and supported by Spring Kafka's `JsonSerializer` and `JsonDeserializer`. It also allows the producer and consumer to exchange a structured message instead of a manually concatenated string.

### 17.10 Stage 8: producer waits for Kafka acknowledgment

The producer uses:

```java
kafkaTemplate.send(topic, message.chunkId(), message)
        .get(30, TimeUnit.SECONDS);
```

`send(...)` starts the Kafka send operation. The returned future represents that operation. Calling `.get(...)` makes the producer wait until one of these happens:

```text
Success: Kafka acknowledges the record
Failure: Kafka reports an error
Timeout: 30 seconds pass without completion
```

### Why wait for acknowledgment?

The upload should not return `202 Accepted` while the application has no confirmation that the chunk was accepted by Kafka. Waiting gives the application a clear success or failure result for each published message.

### Why use `acks=all`?

The producer is configured with:

```java
properties.put(ProducerConfig.ACKS_CONFIG, "all");
```

This asks Kafka to acknowledge the record only after the required in-sync replicas accept it. It provides stronger durability than acknowledging immediately after a single broker receives the record.

### Why enable idempotence?

The producer is configured with:

```java
properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
```

Idempotence helps prevent duplicate records caused by producer retries within a producer session. It does not make the entire upload workflow exactly-once, so the application still uses deterministic chunk IDs and checks completed chunks.

### 17.11 Stage 9: Kafka appends the record

After authentication and serialization, Kafka receives a record containing:

```text
Topic:     embedding-jobs
Key:       document-456:0
Value:     JSON EmbeddingJobMessage
```

Kafka appends the record to the topic's partition. With one partition, the record receives a monotonically increasing offset:

```text
Offset 0: document-456:0
Offset 1: document-456:1
Offset 2: document-456:2
```

A record also has other Kafka information such as:

- topic name;
- partition number;
- offset;
- timestamp;
- headers;
- key;
- serialized value.

### Why is Kafka called a log?

Kafka writes records to an ordered log instead of removing them immediately when a consumer reads them. Consumers track their position using offsets. This allows a consumer to restart and continue from its previous position.

Retention controls how long records remain available. The Aiven topic's default retention is used in this project.

### 17.12 Stage 10: the consumer polls Kafka

Spring Kafka starts a listener container for `EmbeddingJobConsumer`.

The container repeatedly performs the conceptual operation:

```text
poll Kafka for records
    ↓
receive up to max.poll.records records
    ↓
call the listener method
```

The application configures:

```properties
spring.kafka.consumer.max-poll-records=1
app.embedding.kafka.concurrency=1
```

Therefore, the initial worker receives one chunk at a time.

### Why poll one record?

One record means one chunk and one embedding operation. This makes the rate limit predictable and reduces the chance of sending a sudden batch of requests to Gemini.

### 17.13 Stage 11: deserialize JSON into Java

The consumer is configured with:

```properties
spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer
spring.kafka.consumer.value-deserializer=org.springframework.kafka.support.serializer.JsonDeserializer
spring.kafka.consumer.properties.spring.json.trusted.packages=com.aditya.rag.kafka
spring.kafka.consumer.properties.spring.json.value.default.type=com.aditya.rag.kafka.EmbeddingJobMessage
```

The JSON value becomes:

```java
EmbeddingJobMessage message
```

The listener method can therefore receive:

```java
public void consume(EmbeddingJobMessage message)
```

### Why restrict trusted packages?

JSON deserialization can be dangerous if arbitrary classes are allowed. Restricting trusted packages tells Spring Kafka that only the application's message class package is allowed for this deserialization path.

### 17.14 Stage 12: mark the chunk as processing

`EmbeddingJobService.process(...)` first loads the chunk record using its deterministic ID:

```java
EmbeddingChunk chunk = chunkRepository.findById(message.chunkId())
        .orElseThrow(...);
```

If the chunk is already complete:

```java
if (chunk.getStatus() == EmbeddingChunkStatus.EMBEDDED) {
    return;
}
```

Otherwise it changes the state:

```text
QUEUED → PROCESSING
```

and increases the attempt counter:

```text
attempts = attempts + 1
```

The job itself also changes to:

```text
PROCESSING
```

### Why check for `EMBEDDED` first?

Kafka delivery is at least once. A message can be delivered again after a consumer restart or failure near the acknowledgment boundary. The status check prevents normal redelivery from embedding an already-completed chunk again.

### 17.15 Stage 13: rate limiter controls Gemini requests

Before calling the vector store, the consumer runs:

```java
rateLimiter.acquire();
```

The default interval is:

```properties
app.embedding.min-interval-ms=1000
```

The single consumer therefore waits approximately one second between embedding calls.

### Why rate-limit in the application instead of Kafka?

Kafka controls message transport. It does not know Gemini's API quota. The application must control the speed at which it invokes the external embedding provider.

The one-second interval is approximately 60 requests per minute before retries and network timing. It is a starting point, not a guarantee of the current provider quota.

### 17.16 Stage 14: create the Spring AI `Document`

The Kafka message is converted into a Spring AI document:

```java
Document document = new Document(
        message.chunkId(),
        message.text(),
        Map.of(
                "source", message.filename(),
                "type", message.fileType(),
                "owner", message.ownerId().toString(),
                "visibility", message.visibility(),
                "documentId", message.documentId().toString(),
                "jobId", message.jobId().toString(),
                "chunkIndex", message.chunkIndex(),
                "totalChunks", message.totalChunks()));
```

This object contains the text and the metadata needed later for filtering and debugging.

### 17.17 Stage 15: generate the embedding and write PgVector

The consumer calls:

```java
vectorStore.add(List.of(document));
```

Under the hood, the configured `PgVectorStore` performs this conceptual flow:

```text
Document text
    ↓
EmbeddingModel.embed(text)
    ↓
Gemini returns a 768-dimensional float vector
    ↓
PgVectorStore builds an INSERT statement
    ↓
Aiven PostgreSQL stores content, embedding, ID, and metadata
```

The line is synchronous for the Kafka consumer thread. It does not return until the embedding and vector-store operation succeeds or throws an exception.

### Why use `VectorStore` instead of manually calling Gemini?

`VectorStore` gives the application one abstraction for:

- embedding the text;
- storing the original content;
- storing metadata;
- writing the vector;
- later performing similarity search.

The actual embedding model is injected into the `PgVectorStore` builder in `VectorStoreConfig`.

### What is stored?

Conceptually, one vector row contains:

```text
id         = document-456:0
content    = original chunk text
embedding  = 768-number vector
metadata   = owner, visibility, filename, job ID, etc.
```

The `vector_store` table is in the separate Aiven pgvector database, not in the primary login database.

### 17.18 Stage 16: update database progress after success

When `vectorStore.add(...)` returns successfully:

```java
chunk.setStatus(EmbeddingChunkStatus.EMBEDDED);
chunkRepository.save(chunk);
updateJobProgress(message.jobId(), null);
```

The progress method counts embedded and failed chunks. It then sets the job status:

```text
If failed chunks > 0       → FAILED
Else if completed == total → COMPLETED
Else                       → PROCESSING
```

For a three-chunk document:

```text
After chunk 0:
completed = 1, total = 3, status = PROCESSING

After chunk 1:
completed = 2, total = 3, status = PROCESSING

After chunk 2:
completed = 3, total = 3, status = COMPLETED
```

### 17.19 Stage 17: Kafka commits the offset

The listener container uses:

```properties
spring.kafka.listener.ack-mode=record
```

The normal listener returns successfully only after `EmbeddingJobService.process(...)` finishes. Once the record is successfully handled, Spring Kafka commits the offset for that record.

Conceptually:

```text
listener returns normally
    ↓
record considered processed
    ↓
offset committed
    ↓
Kafka will not normally deliver that offset again to this group
```

If `process(...)` throws an exception:

```text
listener does not complete successfully
    ↓
offset is not treated as successfully processed
    ↓
DefaultErrorHandler handles the failure
```

### Why commit after processing instead of before?

If the offset were committed before Gemini and PgVector completed, a process crash could cause Kafka to believe the chunk was finished even though no vector was stored. Committing after processing reduces that loss risk.

### 17.20 Stage 18: temporary failure and retry

When `vectorStore.add(...)` or another processing operation throws, `EmbeddingJobConsumer` does two things:

```java
jobService.markRetryableFailure(message, e);
throw e;
```

The database state is updated for observability:

```text
chunk status = QUEUED
lastError    = error message
```

The exception is then rethrown so the Spring Kafka error handler knows processing failed.

`EmbeddingKafkaConfig` creates:

```java
FixedBackOff backOff = new FixedBackOff(
        retryInterval,
        retryAttempts);
```

The current defaults are:

```properties
app.embedding.retry.interval-ms=10000
app.embedding.retry.max-attempts=4
```

`FixedBackOff` interprets `4` as up to four retries after the initial delivery. Therefore, the normal path can have:

```text
initial attempt + up to 4 retry attempts = up to 5 deliveries
```

### Why retry the same Kafka record?

A failed record has not completed its business operation. Retrying the same record preserves the original chunk data and metadata without creating extra retry topics.

### 17.21 Stage 19: move permanent failures to the DLT

After the retry limit is exhausted, `DeadLetterPublishingRecoverer` sends the record to:

```text
embedding-jobs.DLT
```

The custom destination resolver always uses partition 0:

```java
(record, exception) -> new TopicPartition(dltTopic, 0)
```

### Why explicitly use partition 0?

The DLT has one partition on the Aiven free service. Sending to a matching source partition could fail if the source topic later has a partition number that does not exist on the DLT. Explicit partition 0 matches the manually created DLT.

### What is in the DLT record?

The original `EmbeddingJobMessage` remains available. Kafka/Spring error handling also adds failure-related headers, such as information about the exception and the original record.

### 17.22 Stage 20: DLT listener marks the job failed

The second listener consumes the DLT message:

```java
public void consumeDeadLetter(EmbeddingJobMessage message) {
    jobService.markDltFailure(message, ...);
}
```

It changes the chunk to:

```text
FAILED
```

and changes the job to:

```text
FAILED
```

The DLT listener deliberately catches its own database error and does not throw it again. This prevents a failed DLT handler from repeatedly publishing the same DLT message back to the same DLT.

### Why consume the DLT instead of leaving it untouched?

The DLT is useful for inspection, but the application also needs to update its job status. Otherwise, the UI could show a job stuck in `PROCESSING` forever even though Kafka has already declared the record permanently failed.

### 17.23 Stage 21: restart behavior

Suppose the application stops while a chunk is being processed.

Kafka retains the record and the consumer group retains its committed offset. When the application starts again:

```text
consumer reconnects
    ↓
consumer group resumes from its committed offset
    ↓
any uncommitted record can be delivered again
    ↓
chunk status is checked
    ↓
processing continues or completed work is skipped
```

If the chunk was already saved as `EMBEDDED`, the consumer returns early:

```java
if (chunk.getStatus() == EmbeddingChunkStatus.EMBEDDED) {
    return;
}
```

### Important at-least-once limitation

The current system is designed for at-least-once processing, not strict exactly-once processing.

There is a small failure window:

```text
1. Gemini succeeds
2. PgVector insert succeeds
3. Application crashes before chunk status is saved as EMBEDDED
4. Kafka redelivers the message
5. The vector may be inserted again
```

The deterministic chunk ID reduces ambiguity and allows completed chunks to be skipped after the database status is saved, but a fully atomic vector-store/database/Kafka transaction is not implemented.

A later improvement could add vector-store upsert behavior or an explicit idempotency check before inserting.

### 17.24 Stage 22: RAG search uses the stored vectors

After all chunks are embedded, a user asks a question.

The retrieval flow is:

```text
Question
    ↓
Gemini creates an embedding for the question
    ↓
PgVector compares question vector with stored chunk vectors
    ↓
Most similar chunks are returned
    ↓
Owner and visibility filters are applied
    ↓
Chunks are added to the LLM prompt
```

The stored metadata is important here:

```text
visibility == 'PUBLIC'
owner == '<current user ID>'
```

Kafka is no longer involved in this search request. Kafka was used to prepare the vector data asynchronously.

## 18. Message and acknowledgment timeline

For one successful chunk:

```text
T0  Upload service creates job/chunk records
T1  Producer serializes EmbeddingJobMessage to JSON
T2  Producer sends record to embedding-jobs
T3  Kafka appends record and acknowledges it
T4  Upload can return 202 after all chunk sends complete
T5  Consumer polls the record
T6  JSON becomes EmbeddingJobMessage
T7  Chunk becomes PROCESSING
T8  Rate limiter allows the request
T9  Gemini creates the embedding
T10 PgVectorStore stores the vector
T11 Chunk becomes EMBEDDED
T12 Job progress is updated
T13 Listener returns normally
T14 Kafka offset is committed
```

For a failed chunk:

```text
T0  Consumer polls the record
T1  Gemini or PgVector operation throws
T2  Chunk error is saved and status returns to QUEUED
T3  Exception reaches DefaultErrorHandler
T4  Wait 10 seconds
T5  Redeliver the same record
T6  Repeat up to the configured retry count
T7  Publish the record to embedding-jobs.DLT
T8  DLT listener marks chunk/job FAILED
T9  DLT listener completes normally
```

## 19. What each component owns

| Component | Owns | Does not own |
|---|---|---|
| `FileUploadController` | HTTP request, JWT owner, validation | Kafka retry logic |
| `DataIngestionService` | File parsing and chunking | Gemini calls |
| `EmbeddingJobService.createJob` | Job/chunk records and message construction | Kafka broker storage |
| `EmbeddingJobProducer` | Sending JSON records to Kafka | Creating embeddings |
| Aiven Kafka | Durable message storage and offsets | Gemini or PostgreSQL vectors |
| `EmbeddingJobConsumer` | Receiving records and invoking processing | File parsing |
| `EmbeddingRateLimiter` | Delay between embedding calls | Kafka persistence |
| `EmbeddingJobService.process` | Chunk state, Document creation, vector-store call | Kafka serialization |
| Gemini embedding model | Text-to-vector conversion | Kafka message delivery |
| `PgVectorStore` | Vector persistence and similarity search | Kafka retries |
| `EmbeddingJobConsumer` DLT listener | Marking terminal failures | Reprocessing successful chunks |

## 20. Complete flow in plain English

1. The user uploads a file.
2. The application authenticates the user and validates the file.
3. Apache Tika extracts the file's text.
4. The application converts the extracted content to Markdown.
5. The application splits the Markdown into smaller chunks.
6. The application creates a job record and one chunk record per chunk.
7. The application creates one JSON message per chunk.
8. The Kafka producer authenticates to Aiven with SASL over TLS.
9. Kafka stores each message in the `embedding-jobs` partition.
10. The upload endpoint returns HTTP 202 after the messages are acknowledged.
11. The Kafka consumer polls one message.
12. Spring Kafka converts the JSON value into `EmbeddingJobMessage`.
13. The application marks the chunk as `PROCESSING`.
14. The rate limiter waits if the previous embedding request was too recent.
15. The application creates a Spring AI `Document`.
16. `PgVectorStore` sends the document text to Gemini through the configured embedding model.
17. Gemini returns a 768-dimensional vector.
18. `PgVectorStore` stores the text, vector, ID, and metadata in Aiven PostgreSQL.
19. The application marks the chunk as `EMBEDDED`.
20. The application updates the job progress.
21. The listener returns normally.
22. Spring Kafka commits the record's offset.
23. If the operation fails, the record is retried.
24. If all retries fail, the record is sent to `embedding-jobs.DLT`.
25. The DLT listener marks the chunk and job as `FAILED`.
26. A later RAG question searches the stored vectors; Kafka is not involved in that search operation.
