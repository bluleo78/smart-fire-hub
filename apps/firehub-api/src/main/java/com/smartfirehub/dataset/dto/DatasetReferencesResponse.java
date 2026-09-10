package com.smartfirehub.dataset.dto;

import java.util.List;

/** 데이터셋을 참조하는 자원 집계 응답. 삭제 전 영향 범위 확인 용도로 사용한다. */
public record DatasetReferencesResponse(
    long datasetId,
    List<ReferenceItem> pipelines,
    List<ReferenceItem> dashboards,
    List<ReferenceItem> proactiveJobs,
    List<TriggerReferenceItem> triggers,
    int totalCount) {

  public record ReferenceItem(long id, String name) {}

  /**
   * DATASET_CHANGE 트리거 참조 항목. 트리거는 FK가 아니라 {@code pipeline_trigger.config}
   * JSONB의 {@code datasetIds} 배열로 데이터셋을 감시하므로, 소속 파이프라인 정보를 함께 노출해야
   * 사용자가 어떤 파이프라인의 트리거가 영향받는지 식별할 수 있다.
   */
  public record TriggerReferenceItem(long id, String name, long pipelineId, String pipelineName) {}
}
