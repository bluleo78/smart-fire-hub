package com.smartfirehub.role.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

public record SetPermissionsRequest(@NotNull List<Long> permissionIds) {}
