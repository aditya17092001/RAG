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
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class DataIngestionService {

    private final VectorStore vectorStore;
    private final FlexmarkHtmlConverter htmlToMarkdown = FlexmarkHtmlConverter.builder().build();

    public DataIngestionService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * Ingests any file (pdf, txt, docx, html, etc.) by:
     * 1. Extracting structure-preserving HTML with Tika
     * 2. Converting the HTML to Markdown (keeps tables, headings, lists)
     * 3. Chunking and embedding into the vector store
     *
     * @param fileResource the uploaded file as a Spring Resource
     * @param filename     original filename (used as metadata for filtering)
     * @return number of chunks stored
     */
    public int ingestFile(Resource fileResource, String filename, UUID userId, String visibility) {
        log.info("Starting ingestion for file: {}", filename);

        // Step 1: Extract as structure-preserving HTML, then convert to Markdown
        String markdown = convertToMarkdown(fileResource, filename);
        log.info("Converted {} to Markdown ({} chars)", filename, markdown.length());

        if (markdown.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No readable content found in file");
        }

        // Step 2: Wrap Markdown in a Document with metadata
        Document mdDoc = new Document(markdown, Map.of(
                "source", filename,
                "type", getFileExtension(filename),
                "owner", userId.toString(),
                "visibility", visibility
        ));

        // Step 3: Split into chunks using the recursive character text splitter.
        // It splits on paragraph breaks first, then line breaks, spaces, and
        // finally characters, keeping related text together and carrying an
        // overlap between consecutive chunks to preserve context.
        var splitter = new RecursiveCharacterTextSplitter(1000, 200);

        List<Document> chunks = splitter.apply(List.of(mdDoc));
        log.info("Split into {} chunks for {}", chunks.size(), filename);

        // Step 4: Store in vector store (embeds via Ollama + persists in ChromaDB)
        vectorStore.add(chunks);
        log.info("Ingestion complete. {} chunks stored for file: {}", chunks.size(), filename);

        return chunks.size();
    }

    /**
     * Converts any file to Markdown using Tika (extract HTML) + Flexmark (HTML to MD).
     */
    private String convertToMarkdown(Resource fileResource, String filename) {
        try (InputStream in = fileResource.getInputStream()) {
            // ToXMLContentHandler preserves structure (tables, headings) as XHTML
            ToXMLContentHandler handler = new ToXMLContentHandler();
            AutoDetectParser parser = new AutoDetectParser();
            Metadata metadata = new Metadata();

            parser.parse(in, handler, metadata, new ParseContext());
            String html = handler.toString();

            // Convert the extracted HTML to Markdown
            return htmlToMarkdown.convert(html).trim();
        } catch (Exception e) {
            log.error("Failed to convert {} to Markdown", filename, e);
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Could not process file: " + e.getMessage()
            );
        }
    }

    private String getFileExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        return lastDot > 0 ? filename.substring(lastDot + 1).toLowerCase() : "unknown";
    }
}
