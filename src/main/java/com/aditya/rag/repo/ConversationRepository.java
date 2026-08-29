package com.aditya.rag.repo;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import com.aditya.rag.entity.Conversation;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {
    List<Conversation> findByOwnerIdOrderByCreatedAtDesc(UUID ownerId);
}

