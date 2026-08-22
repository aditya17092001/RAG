package com.aditya.rag.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.aditya.rag.constants.Constants;
import com.aditya.rag.dto.AuthRequest;
import com.aditya.rag.dto.AuthResponse;
import com.aditya.rag.entity.User;
import com.aditya.rag.repo.UserRepository;
import com.aditya.rag.service.JwtService;

import org.springframework.web.bind.annotation.RequestBody;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping(Constants.AUTH)
@RequiredArgsConstructor
public class AuthController {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;

    @PostMapping(Constants.SIGNUP)
    public AuthResponse signup(@RequestBody AuthRequest req) {
        if(userRepository.existsByEmail(req.getEmail())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "User with this email alreay exists");
        }

        User user = User.builder()
                        .email(req.getEmail())
                        .password(passwordEncoder.encode(req.getPassword()))
                        .name(req.getName())
                        .build();

        user = userRepository.save(user);

        String token = jwtService.generateToken(user.getUserId(), req.getEmail());

        return new AuthResponse(token, user.getUserId(), user.getEmail());
    }

    @PostMapping(Constants.SIGNIN)
    public AuthResponse signin(@RequestBody AuthRequest req) {
        authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(req.getEmail(), req.getPassword()));

        User user = userRepository.findByEmail(req.getEmail()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        String token = jwtService.generateToken(user.getUserId(), user.getEmail());

        return new AuthResponse(token, user.getUserId(), user.getEmail());
    }
    
}
