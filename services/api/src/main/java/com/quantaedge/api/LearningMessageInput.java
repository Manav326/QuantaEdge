package com.quantaedge.api;

/** Client-generated retry identity and plain-text content; sender identity is server-derived. */
public record LearningMessageInput(String clientMessageId, String body, String messageType) {}
