package com.aditya.rag.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Logs a concise summary of the effective runtime configuration once the
 * application is fully started. Useful for confirming (in Render/Docker logs)
 * which port, profile, model providers, and health path are actually active.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupInfoLogger {

    private final Environment env;

    @Value("${server.port:8080}")
    private String serverPort;

    @Value("${spring.ai.model.chat:unknown}")
    private String chatProvider;

    @Value("${spring.ai.model.embedding.text:unknown}")
    private String embeddingProvider;

    @Value("${spring.ai.google.genai.embedding.text.model:unset}")
    private String embeddingModel;

    @Value("${app.otp.enabled:true}")
    private boolean otpEnabled;

    @EventListener(ApplicationReadyEvent.class)
    public void logStartupSummary() {
        String[] profiles = env.getActiveProfiles();
        String profileStr = profiles.length == 0 ? "default" : String.join(",", profiles);

        log.info("========================================================");
        log.info("[startup] Application READY");
        log.info("[startup]   active profile : {}", profileStr);
        log.info("[startup]   server port    : {}", serverPort);
        log.info("[startup]   chat provider  : {}", chatProvider);
        log.info("[startup]   embed provider : {} (model={})", embeddingProvider, embeddingModel);
        log.info("[startup]   OTP enabled    : {}", otpEnabled);
        log.info("[startup]   health check   : GET /actuator/health");
        log.info("========================================================");
    }
}
