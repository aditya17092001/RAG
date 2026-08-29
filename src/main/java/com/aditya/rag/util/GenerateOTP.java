package com.aditya.rag.util;

import java.security.SecureRandom;

/**
 * Generates a random 6-digit OTP.
 */
public class GenerateOTP {
    private static final SecureRandom random = new SecureRandom();

    public static String generateOtp() {
        return String.format("%06d", random.nextInt(1_000_000));
    }
}
