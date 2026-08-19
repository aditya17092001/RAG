package com.aditya.rag.service;

import java.util.List;
import java.util.Map;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class DataIngestionService {

    private final VectorStore vectorStore;

    public DataIngestionService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * Ingests any file (pdf, txt, docx, html, etc.) into the vector store.
     * Tika handles format detection automatically.
     *
     * @param fileResource the uploaded file as a Spring Resource
     * @param filename     original filename (used as metadata for filtering)
     * @return number of chunks stored
     */
    public int ingestFile(Resource fileResource, String filename) {
        log.info("Starting ingestion for file: {}", filename);

        // Step 1: Read the document (Tika auto-detects format: pdf, docx, txt, html, etc.)
        TikaDocumentReader reader = new TikaDocumentReader(fileResource);
        List<Document> documents = reader.get();
        log.info("Read {} document(s) from {}", documents.size(), filename);

        // Step 2: Add source metadata to each document
        List<Document> taggedDocs = documents.stream()
                .map(d -> new Document(d.getText(), Map.of(
                        "source", filename,
                        "type", getFileExtension(filename)
                )))
                .toList();

        // Step 3: Clean text (remove common noise)
        List<Document> cleanedDocs = taggedDocs.stream()
                .map(d -> {
                    String clean = d.getText()
                            .replaceAll("<ref[^>]*>.*?</ref>", "")
                            .replaceAll("<ref[^/]*/?>", "")
                            .replaceAll("\\{\\{[^}]*\\}\\}", "")
                            .replaceAll("\\[\\[([^|\\]]*\\|)?", "")
                            .replaceAll("\\]\\]", "")
                            .replaceAll("\\[http[^\\]]*\\]", "")
                            .replaceAll("'''?", "")
                            .replaceAll("\\s+", " ")
                            .trim();
                    return new Document(clean, d.getMetadata());
                })
                .filter(d -> !d.getText().isBlank())
                .toList();

        // Step 4: Split into chunks
        var splitter = TokenTextSplitter.builder()
                .withChunkSize(400)
                .withMinChunkSizeChars(100)
                .withMinChunkLengthToEmbed(200)
                .withMaxNumChunks(100)
                .withKeepSeparator(true)
                .build();

        List<Document> chunks = splitter.apply(cleanedDocs);
        log.info("Split into {} chunks for {}", chunks.size(), filename);

        // Step 5: Store in vector store (embeds via Ollama + persists in ChromaDB)
        vectorStore.add(chunks);
        log.info("Ingestion complete. {} chunks stored for file: {}", chunks.size(), filename);

        return chunks.size();
    }

    private String getFileExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        return lastDot > 0 ? filename.substring(lastDot + 1).toLowerCase() : "unknown";
    }
}
