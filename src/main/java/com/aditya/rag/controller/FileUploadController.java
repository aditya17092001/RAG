package com.aditya.rag.controller;

import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.aditya.rag.service.DataIngestionService;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;


@RestController
public class FileUploadController {

    private final DataIngestionService dataIngestion;

    public FileUploadController(DataIngestionService dataIngestion) {
        this.dataIngestion = dataIngestion;
    }

    @PostMapping("/upload")
    public Map<String, Object> upload(
        @RequestParam("file") MultipartFile file,
        @RequestParam UUID userId,
        @RequestParam(defaultValue = "PUBLIC") String visibility) {
        if(file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File is empty");
        }

        if(!visibility.equals("PUBLIC") && !visibility.equals("PRIVATE")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Visibility must be PUBLIC or PRIVATE");
        }

        // Ingest
        int chunks = dataIngestion.ingestFile(file.getResource(), file.getOriginalFilename(), userId, visibility);
        
        return Map.of(
            "filename", file.getOriginalFilename(),
            "chunks", chunks,
            "visibility", visibility,
            "owner", userId
        );
    }
    
}
