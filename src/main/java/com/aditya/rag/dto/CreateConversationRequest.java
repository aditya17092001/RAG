package com.aditya.rag.dto;

import lombok.Data;

/**
 * Request body for creating a new conversation.
 * title is optional — defaults to "New Chat" if not provided.
 */
@Data
public class CreateConversationRequest {
    private String title;
}
