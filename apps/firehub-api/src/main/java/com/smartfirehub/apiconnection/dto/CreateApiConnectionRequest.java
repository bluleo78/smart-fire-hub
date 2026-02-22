package com.smartfirehub.apiconnection.dto;

import java.util.Map;

public record CreateApiConnectionRequest(
        String name,
        String description,
        String authType,
        Map<String, String> authConfig
) {}
