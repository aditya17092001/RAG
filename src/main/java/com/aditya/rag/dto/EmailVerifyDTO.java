package com.aditya.rag.dto;

import lombok.Data;

/**
 * Request body for verifying an OTP sent to an email.
 */
@Data
public class EmailVerifyDTO {
    private String email;
    private String otp;
}
