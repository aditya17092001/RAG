package com.aditya.rag.dto;

import lombok.Data;

/**
 * Request body for completing a password reset using the OTP.
 */
@Data
public class ResetPasswordDTO {
    private String email;
    private String otp;
    private String newPassword;
}
