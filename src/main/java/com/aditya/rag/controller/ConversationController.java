package com.aditya.rag.controller;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.aditya.rag.constants.Constants;
import com.aditya.rag.dto.ConversationResponse;
import com.aditya.rag.dto.CreateConversationRequest;
import com.aditya.rag.dto.MessageResponse;
import com.aditya.rag.entity.Conversation;
import com.aditya.rag.repo.ConversationRepository;

@RestController
@RequestMapping(Constants.API_V1)
public class ConversationController {

    private final ConversationRepository conversationRepository;
    private final ChatMemory chatMemory;

    public ConversationController(ConversationRepository conversationRepository, ChatMemory chatMemory) {
        this.conversationRepository = conversationRepository;
        this.chatMemory = chatMemory;
    }

    @PostMapping(Constants.CONVERSATIONS)
    public ConversationResponse createConversation(@RequestBody(required = false) CreateConversationRequest req) {
        UUID userId = getCurrentUserId();

        String title = (req != null && req.getTitle() != null && !req.getTitle().isBlank())
                ? req.getTitle()
                : null;

        Conversation conversation = Conversation.builder()
                .ownerId(userId)
                .title(title)
                .createdAt(Instant.now())
                .build();

        conversation = conversationRepository.save(conversation);
        return ConversationResponse.from(conversation);
    }

    @GetMapping(Constants.CONVERSATIONS)
    public List<ConversationResponse> listConversations() {
        UUID userId = getCurrentUserId();
        return conversationRepository.findByOwnerIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(ConversationResponse::from)
                .toList();
    }

    @GetMapping(Constants.CONVERSATIONS + "/{id}/messages")
    public List<MessageResponse> getMessages(@PathVariable UUID id) {
        UUID userId = getCurrentUserId();
        verifyOwnership(id, userId);

        List<Message> messages = chatMemory.get(id.toString());
        return messages.stream()
                .map(m -> new MessageResponse(
                        m.getMessageType() == MessageType.USER ? "user" : "assistant",
                        m.getText()
                ))
                .toList();
    }

    @DeleteMapping(Constants.CONVERSATIONS + "/{id}")
    public void deleteConversation(@PathVariable UUID id) {
        UUID userId = getCurrentUserId();
        verifyOwnership(id, userId);

        chatMemory.clear(id.toString());
        conversationRepository.deleteById(id);
    }


    private UUID getCurrentUserId() {
        return UUID.fromString(
                SecurityContextHolder.getContext().getAuthentication().getName()
        );
    }

    private void verifyOwnership(UUID conversationId, UUID userId) {
        Conversation conv = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));
        if (!conv.getOwnerId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your conversation");
        }
    }
}
