package com.aditya.rag.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.aditya.rag.dto.EmbeddingQueueResult;
import com.aditya.rag.service.DataIngestionService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
public class FileUploadController {

    private final DataIngestionService dataIngestion;

    public FileUploadController(DataIngestionService dataIngestion) {
        this.dataIngestion = dataIngestion;
    }

    @PostMapping("/upload")
    public ResponseEntity<EmbeddingQueueResult> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "PUBLIC") String visibility) {

        UUID userId = UUID.fromString(
                SecurityContextHolder.getContext().getAuthentication().getName());

        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            filename = "uploaded-file";
        }
        log.info("[upload] request received: file='{}' size={} bytes visibility={} owner={}",
                filename, file.getSize(), visibility, userId);

        if (file.isEmpty()) {
            log.warn("[upload] rejected: empty file (owner={})", userId);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File is empty");
        }

        if (!visibility.equals("PUBLIC") && !visibility.equals("PRIVATE")) {
            log.warn("[upload] rejected: invalid visibility '{}' (owner={})", visibility, userId);
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Visibility must be PUBLIC or PRIVATE");
        }

        EmbeddingQueueResult result = dataIngestion.queueFile(
                file.getResource(), filename, userId, visibility);
        log.info("[upload] queued: file='{}' job={} chunks={} owner={}",
                filename, result.jobId(), result.chunks(), userId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(result);
    }
}
