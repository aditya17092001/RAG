package com.aditya.rag.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * Stores the one-time password (OTP) sent to a user's email,
 * used for both email verification and password reset.
 * Keyed by email so each email has at most one active OTP.
 */
@Data
@Entity
@Table(name = "verify_otp")
public class VerifyOtp {

    @Id
    @Column(nullable = false, unique = true, length = 255)
    private String email;

    private String otp;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime otpCreatedAt = LocalDateTime.now();
}
