package com.aditya.rag.service;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.ToXMLContentHandler;
import org.springframework.ai.document.Document;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.aditya.rag.dto.EmbeddingQueueResult;
import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class DataIngestionService {

    private final EmbeddingJobService embeddingJobService;
    private final FlexmarkHtmlConverter htmlToMarkdown = FlexmarkHtmlConverter.builder().build();

    public DataIngestionService(EmbeddingJobService embeddingJobService) {
        this.embeddingJobService = embeddingJobService;
    }

    /**
     * Parses and chunks the upload synchronously, then queues one Kafka message
     * per chunk. Gemini embedding and PgVector persistence happen asynchronously
     * in EmbeddingJobConsumer.
     */
    public EmbeddingQueueResult queueFile(
            Resource fileResource,
            String filename,
            UUID userId,
            String visibility) {
        long startedAt = System.currentTimeMillis();
        log.info("[ingest] START file='{}' owner={} visibility={}", filename, userId, visibility);

        String markdown = convertToMarkdown(fileResource, filename);
        log.info("[ingest] converted '{}' to Markdown ({} chars)", filename, markdown.length());

        if (markdown.isBlank()) {
            log.warn("[ingest] no readable content in '{}'", filename);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No readable content found in file");
        }

        String fileType = getFileExtension(filename);
        Document markdownDocument = new Document(markdown, Map.of(
                "source", filename,
                "type", fileType,
                "owner", userId.toString(),
                "visibility", visibility));

        var splitter = new RecursiveCharacterTextSplitter(1000, 200);
        List<Document> chunks = splitter.apply(List.of(markdownDocument));
        if (chunks.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No readable chunks found in file");
        }
        int totalChunkChars = chunks.stream()
                .mapToInt(chunk -> chunk.getText().length())
                .sum();
        log.info("[ingest] split '{}' into {} chunks", filename, chunks.size());
        log.debug("[ingest] chunk summary file='{}' chunks={} totalChunkChars={} averageChunkChars={}",
                filename, chunks.size(), totalChunkChars, totalChunkChars / chunks.size());

        EmbeddingJobService.QueuedJob queuedJob = embeddingJobService.createJob(
                filename, fileType, userId, visibility, chunks);
        embeddingJobService.publish(queuedJob);

        long tookMs = System.currentTimeMillis() - startedAt;
        log.info("[ingest] QUEUED file='{}' job={} chunks={} took={}ms",
                filename, queuedJob.result().jobId(), chunks.size(), tookMs);
        return queuedJob.result();
    }

    private String convertToMarkdown(Resource fileResource, String filename) {
        try (InputStream in = fileResource.getInputStream()) {
            ToXMLContentHandler handler = new ToXMLContentHandler();
            AutoDetectParser parser = new AutoDetectParser();
            Metadata metadata = new Metadata();

            parser.parse(in, handler, metadata, new ParseContext());
            String html = handler.toString();
            return htmlToMarkdown.convert(html).trim();
        } catch (Exception e) {
            log.error("Failed to convert {} to Markdown", filename, e);
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Could not process file: " + e.getMessage());
        }
    }

    private String getFileExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        return lastDot > 0 ? filename.substring(lastDot + 1).toLowerCase() : "unknown";
    }
}
