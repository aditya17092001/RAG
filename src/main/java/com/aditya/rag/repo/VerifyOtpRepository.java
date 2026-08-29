package com.aditya.rag.repo;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.aditya.rag.entity.VerifyOtp;

public interface VerifyOtpRepository extends JpaRepository<VerifyOtp, String> {
    Optional<VerifyOtp> findByEmail(String email);
    void deleteByEmail(String email);
}
