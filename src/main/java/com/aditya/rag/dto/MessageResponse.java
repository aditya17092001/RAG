package com.aditya.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Response representing a single message in a conversation's history.
 * role is "user" or "assistant".
 */
@Data
@AllArgsConstructor
public class MessageResponse {
    private String role;
    private String content;
}
