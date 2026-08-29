package com.aditya.rag.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.aditya.rag.entity.Conversation;
import com.aditya.rag.repo.ConversationRepository;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@Slf4j
public class RagController {

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final ChatMemory chatMemory;
    private final ConversationRepository conversationRepository;

    public RagController(ChatClient.Builder chatClientBuilder, VectorStore vectorStore,
            ChatMemory chatMemory, ConversationRepository conversationRepository) {
        this.chatClient = chatClientBuilder.build();
        this.vectorStore = vectorStore;
        this.chatMemory = chatMemory;
        this.conversationRepository = conversationRepository;
    }

    // Non-streaming endpoint (kept for compatibility)
    @GetMapping("/ask")
    public String ask(@RequestParam String question, @RequestParam UUID conversationId) {
        UUID userId = currentUserId();
        Conversation conv = verifyConversation(conversationId, userId);
        String memoryKey = conversationId.toString();

        String augmentedPrompt = buildAugmentedPrompt(question, userId);
        chatMemory.add(memoryKey, new UserMessage(question));

        String answer = chatClient.prompt()
                .user(augmentedPrompt)
                .messages(chatMemory.get(memoryKey))
                .call()
                .content();

        log.info("Answer {}", answer);
        chatMemory.add(memoryKey, new AssistantMessage(answer));
        maybeAutoTitle(conv, question);
        return answer;
    }

    // Streaming endpoint: emits tokens as the LLM generates them (SSE).
    // Each token is Base64-encoded so that leading/trailing whitespace is
    // preserved exactly (SSE line framing otherwise strips whitespace).
    @GetMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> askStream(@RequestParam String question, @RequestParam UUID conversationId) {
        UUID userId = currentUserId();
        Conversation conv = verifyConversation(conversationId, userId);
        String memoryKey = conversationId.toString();

        String augmentedPrompt = buildAugmentedPrompt(question, userId);
        chatMemory.add(memoryKey, new UserMessage(question));

        log.info("Streaming answer for conversation {} | Question: {}", conversationId, question);

        // Accumulate the full answer as tokens stream, so we can save it + title at the end
        StringBuilder full = new StringBuilder();

        return chatClient.prompt()
                .user(augmentedPrompt)
                .messages(chatMemory.get(memoryKey))
                .stream()
                .content()
                .doOnNext(full::append)
                .map(token -> Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8)))
                .doOnComplete(() -> {
                    chatMemory.add(memoryKey, new AssistantMessage(full.toString()));
                    maybeAutoTitle(conv, question);
                });
    }

    // --- helpers ---

    private UUID currentUserId() {
        return UUID.fromString(SecurityContextHolder.getContext().getAuthentication().getName());
    }

    private Conversation verifyConversation(UUID conversationId, UUID userId) {
        Conversation conv = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));
        if (!conv.getOwnerId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your conversation");
        }
        return conv;
    }

    private String buildAugmentedPrompt(String question, UUID userId) {
        List<Document> publicDocs = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(question)
                        .topK(5)
                        .filterExpression("visibility == 'PUBLIC'")
                        .build());

        List<Document> privateDocs = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(question)
                        .topK(5)
                        .filterExpression("owner == '" + userId + "'")
                        .build());

        List<Document> relevantDocs = new java.util.ArrayList<>(publicDocs);
        relevantDocs.addAll(privateDocs);

        String context = relevantDocs.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n"));

        return """
                Context information is below:
                ---------------------
                %s
                ---------------------
                Given the context information and no prior knowledge, answer the question.
                If the answer is not in the context, just say you don't know.

                Question: %s
                """.formatted(context, question);
    }

    private void maybeAutoTitle(Conversation conv, String question) {
        if (conv.getTitle() == null || conv.getTitle().isBlank()) {
            conv.setTitle(buildTitleFromQuestion(question));
            conversationRepository.save(conv);
        }
    }

    private String buildTitleFromQuestion(String question) {
        String trimmed = question.strip();
        String[] words = trimmed.split("\\s+");
        int wordLimit = 6;
        if (words.length <= wordLimit) {
            return trimmed;
        }
        String firstWords = String.join(" ", java.util.Arrays.copyOfRange(words, 0, wordLimit));
        return firstWords + "...";
    }
}
