package com.aditya.rag.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    public int ingestFile(Resource fileResource, String filename, UUID userId, String visibility) {
        log.info("Starting ingestion for file: {}", filename);

        // Step 1: Read the document (Tika auto-detects format: pdf, docx, txt, html, etc.)
        TikaDocumentReader reader = new TikaDocumentReader(fileResource);
        List<Document> documents = reader.get();
        log.info("Read {} document(s) from {}", documents.size(), filename);

        // Step 2: Add source metadata to each document
        List<Document> taggedDocs = documents.stream()
                .map(d -> new Document(d.getText(), Map.of(
                        "source", filename,
                        "type", getFileExtension(filename),
                        "owner", userId.toString(),
                        "visibility", visibility
                )))
                .toList();

        for(Document d: taggedDocs) {
            log.info(d.getText());
            log.info(d.getMetadata().toString());
        }

        // Step 3: Clean text (remove wiki markup noise for better embeddings)
        List<Document> cleanedDocs = taggedDocs.stream()
                .map(d -> {
                    String clean = d.getText()
                            .replaceAll("<ref[^>]*>.*?</ref>", "")       // remove <ref>...</ref>
                            .replaceAll("<ref[^/]*/?>", "")              // remove <ref ... />
                            .replaceAll("\\{\\{[^}]*\\}\\}", "")        // remove {{templates}}
                            .replaceAll("\\[\\[([^|\\]]*\\|)?", "")     // remove [[link| prefix
                            .replaceAll("\\]\\]", "")                   // remove ]] suffix
                            .replaceAll("\\[http[^\\]]*\\]", "")        // remove [http...] links
                            .replaceAll("'''?", "")                     // remove bold/italic markup
                            .replaceAll("==+", "")                      // remove == headings ==
                            .replaceAll("\\{\\|[^}]*\\|\\}", "")        // remove {| table markup |}
                            .replaceAll("\\[\\[File:[^\\]]*\\]\\]", "") // remove [[File:...]] images
                            .replaceAll("\\s+", " ")                    // collapse whitespace
                            .trim();
                    return new Document(clean, d.getMetadata());
                })
                .filter(d -> !d.getText().isBlank())
                .toList();

        // Step 4: Split into chunks (smaller chunks to stay within embedding token limits)
        var splitter = TokenTextSplitter.builder()
                .withChunkSize(300)             // reduced from 400 to stay within limits
                .withMinChunkSizeChars(50)      // allow smaller fragments
                .withMinChunkLengthToEmbed(100) // skip very tiny chunks
                .withMaxNumChunks(500)          // allow more chunks for large documents
                .withKeepSeparator(true)
                .build();

        List<Document> chunks = splitter.apply(cleanedDocs);
        log.info("First chunk metadata AFTER split: {}", chunks.get(0).getMetadata());
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
