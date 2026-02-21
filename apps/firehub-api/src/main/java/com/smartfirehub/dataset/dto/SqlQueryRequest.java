package com.smartfirehub.dataset.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record SqlQueryRequest(
    @NotBlank String sql,
    @Min(1) @Max(1000) Integer maxRows
) {
    public SqlQueryRequest {
        if (maxRows == null) maxRows = 1000;
    }
}
