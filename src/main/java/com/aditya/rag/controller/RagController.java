package com.aditya.rag.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@Slf4j
public class RagController {

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final ChatMemory chatMemory;

    public RagController(ChatClient.Builder chatClientBuilder, VectorStore vectorStore, ChatMemory chatMemory) {
        this.chatClient = chatClientBuilder.build();
        this.vectorStore = vectorStore;
        this.chatMemory = chatMemory;
    }

    @GetMapping("/ask")
    public String ask(@RequestParam String question,
        @RequestParam UUID userId
    ) {

        String conversationId = userId.toString();

        // Step 1: Search vector store for relevant chunks
        // Public documents
        List<Document> publicDocs = vectorStore.similaritySearch(
                SearchRequest.builder()
                .query(question)
                .topK(5)
                .filterExpression("visibility == 'PUBLIC'")
                .build()
        );

        // Private documents
        List<Document> privateDocs = vectorStore.similaritySearch(
                SearchRequest.builder()
                .query(question)
                .topK(5)
                .filterExpression("owner == '" + userId + "'")
                .build()
        );

        List<Document> relevantDocs = new java.util.ArrayList<>(publicDocs);
        relevantDocs.addAll(privateDocs);


        // Step 2: Build context from retrieved documents
        String context = relevantDocs.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n"));

        log.info("Question: {} -------------> Context: {} ", question, context);

        // Step 3: Create augmented prompt with context + question
        String augmentedPrompt = """
                Context information is below:
                ---------------------
                %s
                ---------------------
                Given the context information and no prior knowledge, answer the question.
                If the answer is not in the context, just say you don't know.
                
                Question: %s
                """.formatted(context, question);

        // Chat Memoery handles history automatically
        chatMemory.add(conversationId, new UserMessage(question));

        // Step 4: Send to LLM and return response
        String answer = chatClient.prompt()
                .user(augmentedPrompt)
                .messages(chatMemory.get(conversationId))
                .call()
                .content();

        chatMemory.add(conversationId, new UserMessage(answer));

        return answer;
    }
}
