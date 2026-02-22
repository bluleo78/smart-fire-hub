package com.smartfirehub.apiconnection.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

public record UpdateApiConnectionRequest(
    @NotBlank String name,
    String description,
    @NotBlank String authType,
    Map<String, String> authConfig) {}
