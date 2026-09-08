package com.aditya.rag.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.aditya.rag.dto.EmbeddingQueueResult;
import com.aditya.rag.entity.EmbeddingChunk;
import com.aditya.rag.entity.EmbeddingChunkStatus;
import com.aditya.rag.entity.EmbeddingJob;
import com.aditya.rag.entity.EmbeddingJobStatus;
import com.aditya.rag.kafka.EmbeddingJobMessage;
import com.aditya.rag.repo.EmbeddingChunkRepository;
import com.aditya.rag.repo.EmbeddingJobRepository;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class EmbeddingJobService {

    private final EmbeddingJobRepository jobRepository;
    private final EmbeddingChunkRepository chunkRepository;
    private final EmbeddingJobProducer producer;
    private final VectorStore vectorStore;
    private final EmbeddingRateLimiter rateLimiter;

    public EmbeddingJobService(
            EmbeddingJobRepository jobRepository,
            EmbeddingChunkRepository chunkRepository,
            EmbeddingJobProducer producer,
            VectorStore vectorStore,
            EmbeddingRateLimiter rateLimiter) {
        this.jobRepository = jobRepository;
        this.chunkRepository = chunkRepository;
        this.producer = producer;
        this.vectorStore = vectorStore;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Persists the job and all chunks before Kafka messages are published. This
     * prevents a consumer from seeing a message before its relational state is
     * committed.
     */
    @Transactional
    public QueuedJob createJob(
            String filename,
            String fileType,
            UUID ownerId,
            String visibility,
            List<Document> documents) {
        UUID documentId = UUID.randomUUID();
        EmbeddingJob job = jobRepository.save(EmbeddingJob.builder()
                .documentId(documentId)
                .ownerId(ownerId)
                .filename(filename)
                .fileType(fileType)
                .visibility(visibility)
                .totalChunks(documents.size())
                .completedChunks(0)
                .failedChunks(0)
                .status(EmbeddingJobStatus.QUEUED)
                .build());

        List<EmbeddingJobMessage> messages = new ArrayList<>(documents.size());
        List<EmbeddingChunk> chunks = new ArrayList<>(documents.size());
        for (int index = 0; index < documents.size(); index++) {
            String chunkId = documentId + ":" + index;
            String text = documents.get(index).getText();
            chunks.add(EmbeddingChunk.builder()
                    .id(chunkId)
                    .jobId(job.getId())
                    .documentId(documentId)
                    .ownerId(ownerId)
                    .chunkIndex(index)
                    .totalChunks(documents.size())
                    .text(text)
                    .filename(filename)
                    .fileType(fileType)
                    .visibility(visibility)
                    .status(EmbeddingChunkStatus.QUEUED)
                    .attempts(0)
                    .build());
            messages.add(new EmbeddingJobMessage(
                    job.getId(), documentId, chunkId, index, documents.size(), text,
                    filename, fileType, ownerId, visibility));
        }
        chunkRepository.saveAll(chunks);
        return new QueuedJob(
                new EmbeddingQueueResult(
                        job.getId(), documentId, filename, documents.size(), visibility, ownerId, "QUEUED"),
                messages);
    }

    public void publish(QueuedJob queuedJob) {
        try {
            for (EmbeddingJobMessage message : queuedJob.messages()) {
                producer.publish(message);
            }
            log.info("[embedding-kafka] queued job={} document={} chunks={}",
                    queuedJob.result().jobId(), queuedJob.result().documentId(), queuedJob.messages().size());
        } catch (RuntimeException e) {
            markJobFailed(queuedJob.result().jobId(), e);
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Embedding queue is unavailable; please retry the upload", e);
        }
    }

    @Transactional
    public void process(EmbeddingJobMessage message) {
        EmbeddingChunk chunk = chunkRepository.findById(message.chunkId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Embedding chunk does not exist: " + message.chunkId()));

        // Kafka is at-least-once. A redelivered completed message is safe to skip.
        if (chunk.getStatus() == EmbeddingChunkStatus.EMBEDDED) {
            return;
        }

        chunk.setStatus(EmbeddingChunkStatus.PROCESSING);
        chunk.setAttempts(chunk.getAttempts() + 1);
        chunk.setLastError(null);
        chunkRepository.save(chunk);

        EmbeddingJob job = jobRepository.findById(message.jobId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Embedding job does not exist: " + message.jobId()));
        job.setStatus(EmbeddingJobStatus.PROCESSING);
        jobRepository.save(job);

        // One message contains one chunk, so this results in one embedding call.
        rateLimiter.acquire();
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
        vectorStore.add(List.of(document));

        chunk.setStatus(EmbeddingChunkStatus.EMBEDDED);
        chunk.setLastError(null);
        chunkRepository.save(chunk);
        updateJobProgress(message.jobId(), null);

        log.info("[embedding] embedded job={} chunk={}/{}",
                message.jobId(), message.chunkIndex() + 1, message.totalChunks());
    }

    @Transactional
    public void markRetryableFailure(EmbeddingJobMessage message, Throwable error) {
        chunkRepository.findById(message.chunkId()).ifPresent(chunk -> {
            chunk.setStatus(EmbeddingChunkStatus.QUEUED);
            chunk.setLastError(errorMessage(error));
            chunkRepository.save(chunk);
        });
        updateJobProgress(message.jobId(), errorMessage(error));
        log.warn("[embedding] retry scheduled job={} chunk={} reason={}",
                message.jobId(), message.chunkIndex(), errorMessage(error));
    }

    @Transactional
    public void markDltFailure(EmbeddingJobMessage message, Throwable error) {
        chunkRepository.findById(message.chunkId()).ifPresent(chunk -> {
            chunk.setStatus(EmbeddingChunkStatus.FAILED);
            chunk.setLastError(errorMessage(error));
            chunkRepository.save(chunk);
        });
        EmbeddingJob job = jobRepository.findById(message.jobId()).orElse(null);
        if (job != null) {
            job.setStatus(EmbeddingJobStatus.FAILED);
            job.setLastError(errorMessage(error));
            updateJobProgress(job, errorMessage(error));
        }
        log.error("[embedding-dlt] permanently failed job={} chunk={} reason={}",
                message.jobId(), message.chunkIndex(), errorMessage(error));
    }

    @Transactional
    public void markJobFailed(UUID jobId, Throwable error) {
        jobRepository.findById(jobId).ifPresent(job -> {
            job.setStatus(EmbeddingJobStatus.FAILED);
            job.setLastError(errorMessage(error));
            jobRepository.save(job);
        });
    }

    @Transactional(readOnly = true)
    public EmbeddingJob getOwnedJob(UUID jobId, UUID ownerId) {
        return jobRepository.findByIdAndOwnerId(jobId, ownerId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Embedding job not found"));
    }

    private void updateJobProgress(UUID jobId, String error) {
        jobRepository.findById(jobId).ifPresent(job -> updateJobProgress(job, error));
    }

    private void updateJobProgress(EmbeddingJob job, String error) {
        long completed = chunkRepository.countByJobIdAndStatus(job.getId(), EmbeddingChunkStatus.EMBEDDED);
        long failed = chunkRepository.countByJobIdAndStatus(job.getId(), EmbeddingChunkStatus.FAILED);
        job.setCompletedChunks((int) completed);
        job.setFailedChunks((int) failed);
        if (error != null) {
            job.setLastError(error);
        }
        if (failed > 0) {
            job.setStatus(EmbeddingJobStatus.FAILED);
        } else if (completed >= job.getTotalChunks()) {
            job.setStatus(EmbeddingJobStatus.COMPLETED);
        } else {
            job.setStatus(EmbeddingJobStatus.PROCESSING);
        }
        jobRepository.save(job);
    }

    private String errorMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            message = error.getClass().getSimpleName();
        }
        return message.length() > 1900 ? message.substring(0, 1900) : message;
    }

    public record QueuedJob(EmbeddingQueueResult result, List<EmbeddingJobMessage> messages) {
    }
}
