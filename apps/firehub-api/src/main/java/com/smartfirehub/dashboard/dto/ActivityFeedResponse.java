package com.smartfirehub.dashboard.dto;

import java.time.LocalDateTime;
import java.util.List;

public record ActivityFeedResponse(List<ActivityItem> items, int totalCount, boolean hasMore) {

  public record ActivityItem(
      Long id,
      String eventType, // PIPELINE_COMPLETED, PIPELINE_FAILED, IMPORT_COMPLETED,
      // IMPORT_FAILED, DATASET_CREATED, DASHBOARD_CREATED
      String title,
      String description,
      String severity, // INFO, WARNING, CRITICAL
      String entityType, // PIPELINE, DATASET, DASHBOARD
      Long entityId,
      LocalDateTime occurredAt,
      boolean isResolved // 실패 후 복구된 경우 true
      ) {}
}
