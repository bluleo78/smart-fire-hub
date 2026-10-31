package com.smartfirehub.dataset.dto;

import java.util.List;

/**
 * 데이터셋을 참조하는 자원 집계 응답. 삭제 전 영향 범위 확인 용도로 사용한다.
 *
 * <p>대시보드는 조회자가 볼 수 있는 것(본인 소유 또는 공유 — 대시보드 목록과 같은 규칙)만 이름을 싣고, 볼 수 없는 것은 {@code
 * hiddenDashboardCount} 로 개수만 알린다(WD-143 — 분석 쿼리·차트 삭제 영향 WD-86 과 같은 선). 삭제의 영향은 가시성과 무관하므로 {@code
 * totalCount} 는 볼 수 없는 대시보드까지 센다.
 *
 * @param mergeOutputPipelines 이 데이터셋을 병합(MERGE) 적재 출력으로 쓰는 파이프라인(WD-441) — {@code pipelines} 의
 *     부분집합이라 {@code totalCount} 에는 더하지 않는다. 기본 키를 해제하면 이 파이프라인들이 매 실행 실패하므로 기본 키 변경 화면이 미리 알리는 데
 *     쓴다.
 * @param dashboards 이 데이터셋을 쓰는 대시보드 중 조회자가 볼 수 있는 것
 * @param hiddenDashboardCount 이 데이터셋을 쓰는 대시보드 중 조회자가 볼 수 없는 것의 개수
 * @param totalCount 파이프라인 + 대시보드(볼 수 없는 것 포함) + 스마트 작업 + 트리거 수
 */
public record DatasetReferencesResponse(
    long datasetId,
    List<ReferenceItem> pipelines,
    List<ReferenceItem> mergeOutputPipelines,
    List<ReferenceItem> dashboards,
    int hiddenDashboardCount,
    List<ReferenceItem> proactiveJobs,
    List<TriggerReferenceItem> triggers,
    int totalCount) {

  public record ReferenceItem(long id, String name) {}

  /**
   * DATASET_CHANGE 트리거 참조 항목. 트리거는 FK가 아니라 {@code pipeline_trigger.config} JSONB의 {@code
   * datasetIds} 배열로 데이터셋을 감시하므로, 소속 파이프라인 정보를 함께 노출해야 사용자가 어떤 파이프라인의 트리거가 영향받는지 식별할 수 있다.
   */
  public record TriggerReferenceItem(long id, String name, long pipelineId, String pipelineName) {}
}
