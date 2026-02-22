package com.smartfirehub.dataset.dto;

import jakarta.validation.constraints.NotNull;
import java.util.Map;

public record RowDataRequest(@NotNull Map<String, Object> data) {}
