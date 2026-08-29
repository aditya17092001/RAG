package com.aditya.rag.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.aditya.rag.constants.Constants;
import com.aditya.rag.dto.AuthRequest;
import com.aditya.rag.dto.AuthResponse;
import com.aditya.rag.dto.EmailVerifyDTO;
import com.aditya.rag.dto.ForgotPasswordDTO;
import com.aditya.rag.dto.ResetPasswordDTO;
import com.aditya.rag.entity.User;
import com.aditya.rag.repo.UserRepository;
import com.aditya.rag.service.JwtService;
import com.aditya.rag.service.OtpService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping(Constants.AUTH)
@RequiredArgsConstructor
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final OtpService otpService;

    // ---- SIGNUP: create user (unverified) + send OTP ----
    @PostMapping(Constants.SIGNUP)
    public Map<String, String> signup(@RequestBody AuthRequest req) {
        if (userRepository.existsByEmail(req.getEmail())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "User with this email already exists");
        }

        // When OTP is disabled (e.g. SMTP blocked in the cloud), auto-verify the
        // account at signup so the user can sign in without an email step.
        boolean otpEnabled = otpService.isOtpEnabled();

        User user = User.builder()
                .email(req.getEmail())
                .password(passwordEncoder.encode(req.getPassword()))
                .name(req.getName())
                .emailVerified(!otpEnabled)
                .build();

        userRepository.save(user);

        if (otpEnabled) {
            otpService.generateAndSendOtp(
                    req.getEmail(),
                    "Verify your email",
                    "Welcome! Please verify your email to activate your account."
            );
            return Map.of("message", "Signup successful. Please check your email for the OTP to verify your account.");
        }

        return Map.of("message", "Signup successful. You can sign in now.");
    }

    // ---- VERIFY OTP: mark email verified ----
    @PostMapping(Constants.VERIFY_OTP)
    public Map<String, String> verifyOtp(@RequestBody EmailVerifyDTO req) {
        boolean valid = otpService.validateOtp(req.getEmail(), req.getOtp());
        if (!valid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired OTP");
        }

        User user = userRepository.findByEmail(req.getEmail())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        user.setEmailVerified(true);
        userRepository.save(user);

        return Map.of("message", "Email verified successfully. You can now sign in.");
    }

    // ---- RESEND OTP ----
    @PostMapping(Constants.RESEND_OTP)
    public Map<String, String> resendOtp(@RequestBody ForgotPasswordDTO req) {
        if (!userRepository.existsByEmail(req.getEmail())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }
        otpService.generateAndSendOtp(
                req.getEmail(),
                "Your new OTP",
                "Here is your new verification code."
        );
        return Map.of("message", "A new OTP has been sent to your email.");
    }

    // ---- SIGNIN: block if email not verified ----
    @PostMapping(Constants.SIGNIN)
    public AuthResponse signin(@RequestBody AuthRequest req) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(req.getEmail(), req.getPassword()));

        User user = userRepository.findByEmail(req.getEmail())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        if (!user.isEmailVerified()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Email not verified. Please verify your email first.");
        }

        String token = jwtService.generateToken(user.getUserId(), user.getEmail());
        return new AuthResponse(token, user.getUserId(), user.getEmail());
    }

    // ---- FORGOT PASSWORD: send OTP for reset ----
    @PostMapping(Constants.FORGOT_PASSWORD)
    public Map<String, String> forgotPassword(@RequestBody ForgotPasswordDTO req) {
        // Only send an OTP if the user exists, but always return the same message
        // so attackers can't tell which emails are registered.
        if (userRepository.existsByEmail(req.getEmail())) {
            otpService.generateAndSendOtp(
                    req.getEmail(),
                    "Reset your password",
                    "Use the code below to reset your password. If you didn't request this, ignore this email."
            );
        }
        return Map.of("message", "If that email is registered, a password reset OTP has been sent.");
    }

    // ---- RESET PASSWORD: verify OTP + set new password ----
    @PostMapping(Constants.RESET_PASSWORD)
    public Map<String, String> resetPassword(@RequestBody ResetPasswordDTO req) {
        boolean valid = otpService.validateOtp(req.getEmail(), req.getOtp());
        if (!valid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired OTP");
        }

        User user = userRepository.findByEmail(req.getEmail())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        user.setPassword(passwordEncoder.encode(req.getNewPassword()));
        userRepository.save(user);

        return Map.of("message", "Password reset successful. You can now sign in with your new password.");
    }
}
