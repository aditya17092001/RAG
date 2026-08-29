package com.aditya.rag.controller;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.aditya.rag.service.DataIngestionService;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import lombok.extern.slf4j.Slf4j;


@Slf4j
@RestController
public class FileUploadController {

    private final DataIngestionService dataIngestion;

    public FileUploadController(DataIngestionService dataIngestion) {
        this.dataIngestion = dataIngestion;
    }

    @PostMapping("/upload")
    public Map<String, Object> upload(
        @RequestParam("file") MultipartFile file,
        @RequestParam(defaultValue = "PUBLIC") String visibility) {

        // Get the authenticated user's ID from the JWT token (set by JwtAuthFilter)
        UUID userId = UUID.fromString(
                SecurityContextHolder.getContext().getAuthentication().getName()
        );

        String filename = file.getOriginalFilename();
        log.info("[upload] request received: file='{}' size={} bytes visibility={} owner={}",
                filename, file.getSize(), visibility, userId);

        if(file.isEmpty()) {
            log.warn("[upload] rejected: empty file (owner={})", userId);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File is empty");
        }

        if(!visibility.equals("PUBLIC") && !visibility.equals("PRIVATE")) {
            log.warn("[upload] rejected: invalid visibility '{}' (owner={})", visibility, userId);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Visibility must be PUBLIC or PRIVATE");
        }

        // Ingest
        int chunks = dataIngestion.ingestFile(file.getResource(), filename, userId, visibility);

        log.info("[upload] success: file='{}' chunks={} owner={}", filename, chunks, userId);
        return Map.of(
            "filename", filename,
            "chunks", chunks,
            "visibility", visibility,
            "owner", userId
        );
    }

}
