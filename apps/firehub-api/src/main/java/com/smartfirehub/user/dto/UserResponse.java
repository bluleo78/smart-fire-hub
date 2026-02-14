package com.smartfirehub.user.dto;

import java.time.LocalDateTime;

public record UserResponse(Long id, String username, String email, String name, boolean isActive, LocalDateTime createdAt) {}
