package com.smartfirehub.dataset.dto;

import java.util.List;

/** 데이터셋을 참조하는 자원 집계 응답. 삭제 전 영향 범위 확인 용도로 사용한다. */
public record DatasetReferencesResponse(
    long datasetId,
    List<ReferenceItem> pipelines,
    List<ReferenceItem> dashboards,
    List<ReferenceItem> proactiveJobs,
    int totalCount) {

  public record ReferenceItem(long id, String name) {}
}
