package com.aditya.rag.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Simple holder for the pieces of an outgoing email.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Email {
    private String recipient;
    private String msgBody;
    private String subject;
    private String otp;
}
