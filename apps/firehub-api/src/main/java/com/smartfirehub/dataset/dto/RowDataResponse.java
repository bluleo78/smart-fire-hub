package com.smartfirehub.dataset.dto;

import java.time.LocalDateTime;
import java.util.Map;

public record RowDataResponse(Long id, Map<String, Object> data, LocalDateTime createdAt) {}
