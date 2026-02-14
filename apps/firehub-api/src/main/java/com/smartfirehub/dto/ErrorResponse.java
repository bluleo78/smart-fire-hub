package com.smartfirehub.dto;

import java.util.Map;

public record ErrorResponse(int status, String error, String message, Map<String, String> errors) {}
