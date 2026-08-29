package com.aditya.rag.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import com.aditya.rag.entity.VerifyOtp;
import com.aditya.rag.model.Email;
import com.aditya.rag.repo.VerifyOtpRepository;
import com.aditya.rag.util.GenerateOTP;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class OtpService {

    private static final int OTP_VALIDITY_MINUTES = 10;

    private final VerifyOtpRepository verifyOtpRepository;
    private final EmailService emailService;

    /**
     * Generates a fresh OTP for the given email, stores it, and emails it.
     */
    public boolean generateAndSendOtp(String email, String subject, String intro) {
        String otp = GenerateOTP.generateOtp();

        // Upsert the OTP entry keyed by email
        VerifyOtp entry = verifyOtpRepository.findByEmail(email).orElseGet(VerifyOtp::new);
        entry.setEmail(email);
        entry.setOtp(otp);
        entry.setOtpCreatedAt(LocalDateTime.now());
        if (entry.getCreatedAt() == null) {
            entry.setCreatedAt(LocalDateTime.now());
        }
        verifyOtpRepository.save(entry);

        String body = intro + "\n\nYour OTP is: " + otp
                + "\n\nThis code expires in " + OTP_VALIDITY_MINUTES + " minutes.";

        Email mail = new Email(email, body, subject, otp);
        boolean sent = emailService.sendEmail(mail);

        // For local dev without real SMTP: log the OTP so you can still test
        log.info("OTP for {} is {} (emailSent={})", email, otp, sent);

        return sent;
    }

    /**
     * Validates the OTP for an email. Returns true if valid and not expired.
     * Deletes the OTP entry on success (one-time use).
     */
    public boolean validateOtp(String email, String otp) {
        VerifyOtp entry = verifyOtpRepository.findByEmail(email).orElse(null);
        if (entry == null) {
            log.debug("No OTP found for {}", email);
            return false;
        }

        boolean expired = LocalDateTime.now()
                .isAfter(entry.getOtpCreatedAt().plusMinutes(OTP_VALIDITY_MINUTES));
        if (expired) {
            log.debug("OTP for {} is expired", email);
            return false;
        }

        if (!entry.getOtp().equals(otp)) {
            log.debug("OTP mismatch for {}", email);
            return false;
        }

        // Success -> consume the OTP
        verifyOtpRepository.delete(entry);
        return true;
    }
}
