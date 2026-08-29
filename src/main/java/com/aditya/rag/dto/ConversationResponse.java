package com.aditya.rag.dto;

import java.time.Instant;
import java.util.UUID;

import com.aditya.rag.entity.Conversation;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Response representing a single conversation (used in the sidebar list
 * and when creating a new chat).
 */
@Data
@AllArgsConstructor
public class ConversationResponse {
    private UUID id;
    private String title;
    private Instant createdAt;

    public static ConversationResponse from(Conversation conversation) {
        return new ConversationResponse(
                conversation.getId(),
                conversation.getTitle(),
                conversation.getCreatedAt()
        );
    }
}
