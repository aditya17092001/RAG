package com.aditya.rag.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import com.aditya.rag.model.Email;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender javaMailSender;

    @Value("${spring.mail.username}")
    private String sender;

    /**
     * Sends a plain-text email. Returns true on success, false on failure.
     */
    public boolean sendEmail(Email email) {
        try {
            SimpleMailMessage mail = new SimpleMailMessage();
            mail.setFrom(sender);
            mail.setTo(email.getRecipient());
            mail.setSubject(email.getSubject());
            mail.setText(email.getMsgBody());

            javaMailSender.send(mail);
            log.info("Email sent successfully to {}", email.getRecipient());
            return true;
        } catch (Exception e) {
            log.error("Failed to send email to {}: {}", email.getRecipient(), e.getMessage());
            return false;
        }
    }
}
