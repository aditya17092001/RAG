package com.aditya.rag.dto;

import lombok.Data;

/**
 * Request body for initiating a password reset (sends an OTP to the email).
 */
@Data
public class ForgotPasswordDTO {
    private String email;
}
