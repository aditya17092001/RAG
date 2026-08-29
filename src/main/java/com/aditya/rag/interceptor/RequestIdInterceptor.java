package com.aditya.rag.interceptor;

import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Assigns a unique request ID to every incoming HTTP request.
 * The ID is:
 *  - stored in the SLF4J MDC so it appears in every log line for this request
 *    (see the [request_id=...] token in logback-spring.xml)
 *  - returned to the client in the X-Request-ID response header
 */
@Component
public class RequestIdInterceptor implements HandlerInterceptor {

    private static final String REQUEST_ID = "request_id";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String requestId = UUID.randomUUID().toString();
        MDC.put(REQUEST_ID, requestId);
        response.setHeader("X-Request-ID", requestId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // Always clear the MDC so the request ID doesn't leak into other threads
        MDC.remove(REQUEST_ID);
    }
}
