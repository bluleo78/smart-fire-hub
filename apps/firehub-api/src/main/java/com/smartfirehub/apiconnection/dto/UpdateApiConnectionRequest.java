package com.smartfirehub.apiconnection.dto;

import java.util.Map;

public record UpdateApiConnectionRequest(
        String name,
        String description,
        String authType,
        Map<String, String> authConfig
) {}
