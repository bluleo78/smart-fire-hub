package com.smartfirehub.ai.dto;

import java.util.List;

public record ChatRequest(String message, String sessionId, List<Long> fileIds) {}
