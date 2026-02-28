package com.smartfirehub.global.dto;

import java.util.Map;

public record ErrorResponse(
    int status, String error, String message, Map<String, String> errors,
    String timestamp, String path) {}
