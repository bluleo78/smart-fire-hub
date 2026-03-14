package com.smartfirehub.file.dto;

import java.time.Instant;

public record FileUploadResponse(
    Long id,
    String originalName,
    String mimeType,
    Long fileSize,
    String fileCategory,
    Instant createdAt) {}
