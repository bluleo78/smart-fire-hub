package com.smartfirehub.user.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;

public record SetRolesRequest(@NotNull List<Long> roleIds) {}
