package com.smartfirehub.dataset.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

public record BatchRowDataRequest(
    @NotNull @Size(min = 1, max = 100) List<Map<String, Object>> rows) {}
