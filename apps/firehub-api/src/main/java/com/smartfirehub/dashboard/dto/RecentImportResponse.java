package com.smartfirehub.dashboard.dto;

import java.time.LocalDateTime;

public record RecentImportResponse(
        Long id,
        String datasetName,
        String fileName,
        String status,
        LocalDateTime createdAt
) {}
