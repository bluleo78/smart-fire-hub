package com.smartfirehub.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record UpdateProfileRequest(@NotBlank String name, @Email String email) {}
