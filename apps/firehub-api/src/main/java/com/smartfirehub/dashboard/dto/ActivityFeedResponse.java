package com.smartfirehub.dashboard.dto;

import java.time.LocalDateTime;
import java.util.List;

public record ActivityFeedResponse(List<ActivityItem> items, int totalCount, boolean hasMore) {

  public record ActivityItem(
      Long id,
      String eventType, // PIPELINE_COMPLETED, PIPELINE_FAILED, IMPORT_COMPLETED,
      // IMPORT_FAILED, DATASET_CREATED. DASHBOARD_CREATED 는 WD-464 에서 뺐다
      // (가시성 판정 없이 비공개 대시보드 생성이 모두에게 보이게 되므로)
      String title,
      String description,
      String severity, // INFO, WARNING, CRITICAL
      String entityType, // PIPELINE, DATASET
      Long entityId,
      LocalDateTime occurredAt,
      boolean isResolved // 실패 후 복구된 경우 true
      ) {}
}
