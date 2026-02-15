package com.smartfirehub.dashboard.dto;

import java.util.List;

public record DashboardStatsResponse(
        long totalDatasets,
        long sourceDatasets,
        long derivedDatasets,
        long totalPipelines,
        long activePipelines,
        List<RecentImportResponse> recentImports,
        List<RecentExecutionResponse> recentExecutions
) {}
