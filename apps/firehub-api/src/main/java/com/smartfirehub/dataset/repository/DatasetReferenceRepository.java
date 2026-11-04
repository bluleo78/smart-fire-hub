package com.smartfirehub.dataset.repository;

import com.smartfirehub.dataset.dto.DatasetDetailResponse;
import com.smartfirehub.dataset.dto.DatasetReferencesResponse;
import java.util.List;

/**
 * 데이터셋을 참조하는 파이프라인·대시보드·트리거 조회 모음(WD-484).
 *
 * <p>{@code DatasetService} 의 직접 jOOQ 쿼리를 그대로 옮긴 것이다. 클래스 단위 {@code @Transactional} 을 두지 않는다 — 호출하는
 * 서비스 메서드(getDatasetById·deleteDataset·getReferences)가 모두 이미 트랜잭션이라 기존 동작(호출자 트랜잭션 사용)이 그대로다. SQL
 * 본문 대조(파서 판정)는 서비스에 남기고, 여기서는 1차로 거른 후보 행만 돌려준다.
 *
 * <p>구현은 {@code JooqDatasetReferenceRepository}(jOOQ/PostgreSQL) 이다 — WD-485.
 */
public interface DatasetReferenceRepository {

  /** 참조 대시보드 한 건 — 조회자가 볼 수 있는지(본인 소유 또는 공유)를 함께 든다. 응답으로 직렬화하지 않는다(볼 수 없는 이름이 들어 있다). */
  record DashboardHit(Long id, String name, boolean visible) {}

  /** SQL 본문 대조 후보 파이프라인 한 건 — 스텝 스크립트 본문을 함께 든다. */
  record PipelineSqlCandidate(Long id, String name, String script) {}

  /** SQL 본문 대조 후보 대시보드 한 건 — 저장 쿼리 SQL 본문을 함께 든다. */
  record DashboardSqlCandidate(DashboardHit hit, String sqlText) {}

  /** 이 데이터셋을 출력으로 쓰는 파이프라인(상세 화면의 연결 파이프라인). */
  List<DatasetDetailResponse.LinkedPipelineInfo> findLinkedPipelines(Long id);

  /** 이 데이터셋을 출력으로 참조하는 파이프라인 스텝 수 — 삭제 전 FK 위반 사전 점검용(#126). */
  int countStepsByOutputDataset(Long id);

  /** Pipelines: output_dataset_id 또는 pipeline_step_input.dataset_id 기준으로 조회. DISTINCT 로 중복 제거. */
  List<DatasetReferencesResponse.ReferenceItem> findPipelinesByFk(Long datasetId);

  /** (WD-441) 이 데이터셋을 병합(MERGE) 적재 출력으로 쓰는 파이프라인 — 기본 키를 해제하면 매 실행 실패하는 대상이다. */
  List<DatasetReferencesResponse.ReferenceItem> findMergeOutputPipelines(Long datasetId);

  /**
   * SQL 스텝 본문이 이 테이블 이름을 포함하는 파이프라인 후보(WD-143). 같은 테넌트(RLS) 안에서 {@code ILIKE '%테이블명%'} 으로 값싸게 1차로
   * 거른 것뿐이라 {@code sales_2024}·문자열 리터럴·주석 안의 이름까지 걸린다 — 실제 테이블 참조인지 확정은 호출부(서비스)가 파서로 한다.
   */
  List<PipelineSqlCandidate> findPipelineSqlCandidates(String tableName);

  /**
   * Dashboards: dashboard → dashboard_widget → chart → saved_query.dataset_id 경로로 조회. 조회자 가시성(본인 소유
   * 또는 공유)을 함께 읽어, 볼 수 없는 대시보드는 호출부가 이름 대신 개수만 싣게 한다(WD-143 — 삭제 확인 창에 남의 비공개 대시보드 이름이 보이지 않게).
   */
  List<DashboardHit> findDashboardsByFk(Long datasetId, Long userId);

  /**
   * 위젯 → 차트 → 저장 쿼리 SQL 본문이 이 테이블 이름을 포함하는 대시보드 후보(WD-143). 거르기 방식은 {@link
   * #findPipelineSqlCandidates} 와 같고, 가시성은 FK 경로와 같은 조건({@code
   * JooqAnalyticsDashboardRepository#visibleTo})으로 함께 읽는다.
   */
  List<DashboardSqlCandidate> findDashboardSqlCandidates(String tableName, Long userId);

  /**
   * Triggers (#600): pipeline_trigger 는 FK가 아니라 config JSONB의 datasetIds 배열로 데이터셋을 감시한다. {@code
   * config -> 'datasetIds' @> to_jsonb(datasetId)} 로 배열 포함 여부를 검사한다 — 삭제 시 이 참조를 놓치면 트리거가 고아로 남아
   * 존재하지 않는 데이터셋을 계속 폴링한다.
   */
  List<DatasetReferencesResponse.TriggerReferenceItem> findDatasetChangeTriggers(Long datasetId);
}
