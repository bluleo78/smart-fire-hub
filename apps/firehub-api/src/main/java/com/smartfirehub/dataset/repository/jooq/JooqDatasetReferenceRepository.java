package com.smartfirehub.dataset.repository.jooq;

import static org.jooq.impl.DSL.*;

import com.smartfirehub.analytics.repository.jooq.JooqAnalyticsDashboardRepository;
import com.smartfirehub.dataset.dto.DatasetDetailResponse;
import com.smartfirehub.dataset.dto.DatasetReferencesResponse;
import com.smartfirehub.dataset.repository.DatasetReferenceRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.springframework.stereotype.Repository;

/**
 * DatasetReferenceRepository 의 jOOQ(PostgreSQL) 구현 — WD-485.
 *
 * <p>데이터셋을 참조하는 파이프라인·대시보드·트리거 조회 모음(WD-484).
 *
 * <p>{@code DatasetService} 의 직접 jOOQ 쿼리를 그대로 옮긴 것이다. 클래스 단위 {@code @Transactional} 을 두지 않는다 — 호출하는
 * 서비스 메서드(getDatasetById·deleteDataset·getReferences)가 모두 이미 트랜잭션이라 기존 동작(호출자 트랜잭션 사용)이 그대로다. SQL
 * 본문 대조(파서 판정)는 서비스에 남기고, 여기서는 1차로 거른 후보 행만 돌려준다.
 */
@Repository
@RequiredArgsConstructor
public class JooqDatasetReferenceRepository implements DatasetReferenceRepository {

  private final DSLContext dsl;

  /** 이 데이터셋을 출력으로 쓰는 파이프라인(상세 화면의 연결 파이프라인). */
  @Override
  public List<DatasetDetailResponse.LinkedPipelineInfo> findLinkedPipelines(Long id) {
    return dsl.selectDistinct(
            field(name("pipeline", "id"), Long.class),
            field(name("pipeline", "name"), String.class),
            field(name("pipeline", "is_active"), Boolean.class))
        .from(table(name("pipeline_step")))
        .join(table(name("pipeline")))
        .on(
            field(name("pipeline_step", "pipeline_id"), Long.class)
                .eq(field(name("pipeline", "id"), Long.class)))
        .where(field(name("pipeline_step", "output_dataset_id"), Long.class).eq(id))
        .fetch(
            r ->
                new DatasetDetailResponse.LinkedPipelineInfo(
                    r.get(field(name("pipeline", "id"), Long.class)),
                    r.get(field(name("pipeline", "name"), String.class)),
                    Boolean.TRUE.equals(
                        r.get(field(name("pipeline", "is_active"), Boolean.class)))));
  }

  /** 이 데이터셋을 출력으로 참조하는 파이프라인 스텝 수 — 삭제 전 FK 위반 사전 점검용(#126). */
  @Override
  public int countStepsByOutputDataset(Long id) {
    return dsl.fetchCount(
        table(name("pipeline_step")), field(name("output_dataset_id"), Long.class).eq(id));
  }

  /** Pipelines: output_dataset_id 또는 pipeline_step_input.dataset_id 기준으로 조회. DISTINCT 로 중복 제거. */
  @Override
  public List<DatasetReferencesResponse.ReferenceItem> findPipelinesByFk(Long datasetId) {
    return dsl.selectDistinct(
            field(name("pipeline", "id"), Long.class),
            field(name("pipeline", "name"), String.class))
        .from(table(name("pipeline")))
        .where(
            field(name("pipeline", "id"), Long.class)
                .in(
                    dsl.select(field(name("pipeline_step", "pipeline_id"), Long.class))
                        .from(table(name("pipeline_step")))
                        .where(
                            field(name("pipeline_step", "output_dataset_id"), Long.class)
                                .eq(datasetId))))
        .or(
            field(name("pipeline", "id"), Long.class)
                .in(
                    dsl.select(field(name("pipeline_step", "pipeline_id"), Long.class))
                        .from(table(name("pipeline_step")))
                        .join(table(name("pipeline_step_input")))
                        .on(
                            field(name("pipeline_step", "id"), Long.class)
                                .eq(field(name("pipeline_step_input", "step_id"), Long.class)))
                        .where(
                            field(name("pipeline_step_input", "dataset_id"), Long.class)
                                .eq(datasetId))))
        .orderBy(field(name("pipeline", "id"), Long.class))
        .fetch(
            r ->
                new DatasetReferencesResponse.ReferenceItem(
                    r.get(field(name("pipeline", "id"), Long.class)),
                    r.get(field(name("pipeline", "name"), String.class))));
  }

  /** (WD-441) 이 데이터셋을 병합(MERGE) 적재 출력으로 쓰는 파이프라인 — 기본 키를 해제하면 매 실행 실패하는 대상이다. */
  @Override
  public List<DatasetReferencesResponse.ReferenceItem> findMergeOutputPipelines(Long datasetId) {
    return dsl.selectDistinct(
            field(name("pipeline", "id"), Long.class),
            field(name("pipeline", "name"), String.class))
        .from(table(name("pipeline")))
        .join(table(name("pipeline_step")))
        .on(
            field(name("pipeline_step", "pipeline_id"), Long.class)
                .eq(field(name("pipeline", "id"), Long.class)))
        .where(field(name("pipeline_step", "output_dataset_id"), Long.class).eq(datasetId))
        .and(field(name("pipeline_step", "load_strategy"), String.class).eq("MERGE"))
        .orderBy(field(name("pipeline", "id"), Long.class))
        .fetch(
            r ->
                new DatasetReferencesResponse.ReferenceItem(
                    r.get(field(name("pipeline", "id"), Long.class)),
                    r.get(field(name("pipeline", "name"), String.class))));
  }

  /**
   * SQL 스텝 본문이 이 테이블 이름을 포함하는 파이프라인 후보(WD-143). 같은 테넌트(RLS) 안에서 {@code ILIKE '%테이블명%'} 으로 값싸게 1차로
   * 거른 것뿐이라 {@code sales_2024}·문자열 리터럴·주석 안의 이름까지 걸린다 — 실제 테이블 참조인지 확정은 호출부(서비스)가 파서로 한다.
   */
  @Override
  public List<PipelineSqlCandidate> findPipelineSqlCandidates(String tableName) {
    Field<Long> pipelineId = field(name("pipeline", "id"), Long.class);
    Field<String> pipelineName = field(name("pipeline", "name"), String.class);
    Field<String> script = field(name("pipeline_step", "script_content"), String.class);
    return dsl.select(pipelineId, pipelineName, script)
        .from(table(name("pipeline")))
        .join(table(name("pipeline_step")))
        .on(pipelineId.eq(field(name("pipeline_step", "pipeline_id"), Long.class)))
        .where(field(name("pipeline_step", "script_type"), String.class).eq("SQL"))
        .and(script.likeIgnoreCase(containsPattern(tableName), '\\'))
        .fetch()
        .map(r -> new PipelineSqlCandidate(r.get(pipelineId), r.get(pipelineName), r.get(script)));
  }

  /**
   * Dashboards: dashboard → dashboard_widget → chart → saved_query.dataset_id 경로로 조회. 조회자 가시성(본인 소유
   * 또는 공유)을 함께 읽어, 볼 수 없는 대시보드는 호출부가 이름 대신 개수만 싣게 한다(WD-143 — 삭제 확인 창에 남의 비공개 대시보드 이름이 보이지 않게).
   */
  @Override
  public List<DashboardHit> findDashboardsByFk(Long datasetId, Long userId) {
    Field<Boolean> dashboardVisible = field(JooqAnalyticsDashboardRepository.visibleTo(userId));
    return dsl.selectDistinct(
            field(name("dashboard", "id"), Long.class),
            field(name("dashboard", "name"), String.class),
            dashboardVisible)
        .from(table(name("dashboard")))
        .join(table(name("dashboard_widget")))
        .on(
            field(name("dashboard", "id"), Long.class)
                .eq(field(name("dashboard_widget", "dashboard_id"), Long.class)))
        .join(table(name("chart")))
        .on(
            field(name("dashboard_widget", "chart_id"), Long.class)
                .eq(field(name("chart", "id"), Long.class)))
        .join(table(name("saved_query")))
        .on(
            field(name("chart", "saved_query_id"), Long.class)
                .eq(field(name("saved_query", "id"), Long.class)))
        .where(field(name("saved_query", "dataset_id"), Long.class).eq(datasetId))
        .fetch(
            r ->
                new DashboardHit(
                    r.get(field(name("dashboard", "id"), Long.class)),
                    r.get(field(name("dashboard", "name"), String.class)),
                    Boolean.TRUE.equals(r.get(dashboardVisible))));
  }

  /**
   * 위젯 → 차트 → 저장 쿼리 SQL 본문이 이 테이블 이름을 포함하는 대시보드 후보(WD-143). 거르기 방식은 {@link
   * #findPipelineSqlCandidates} 와 같고, 가시성은 FK 경로와 같은 조건({@link
   * JooqAnalyticsDashboardRepository#visibleTo})으로 함께 읽는다.
   */
  @Override
  public List<DashboardSqlCandidate> findDashboardSqlCandidates(String tableName, Long userId) {
    Field<Boolean> visible = field(JooqAnalyticsDashboardRepository.visibleTo(userId));
    Field<Long> dashboardId = field(name("dashboard", "id"), Long.class);
    Field<String> dashboardName = field(name("dashboard", "name"), String.class);
    Field<String> sqlText = field(name("saved_query", "sql_text"), String.class);
    return dsl.selectDistinct(dashboardId, dashboardName, visible, sqlText)
        .from(table(name("dashboard")))
        .join(table(name("dashboard_widget")))
        .on(dashboardId.eq(field(name("dashboard_widget", "dashboard_id"), Long.class)))
        .join(table(name("chart")))
        .on(
            field(name("dashboard_widget", "chart_id"), Long.class)
                .eq(field(name("chart", "id"), Long.class)))
        .join(table(name("saved_query")))
        .on(
            field(name("chart", "saved_query_id"), Long.class)
                .eq(field(name("saved_query", "id"), Long.class)))
        .where(sqlText.likeIgnoreCase(containsPattern(tableName), '\\'))
        .fetch()
        .map(
            r ->
                new DashboardSqlCandidate(
                    new DashboardHit(
                        r.get(dashboardId),
                        r.get(dashboardName),
                        Boolean.TRUE.equals(r.get(visible))),
                    r.get(sqlText)));
  }

  /**
   * Triggers (#600): pipeline_trigger 는 FK가 아니라 config JSONB의 datasetIds 배열로 데이터셋을 감시한다. {@code
   * config -> 'datasetIds' @> to_jsonb(datasetId)} 로 배열 포함 여부를 검사한다 — 삭제 시 이 참조를 놓치면 트리거가 고아로 남아
   * 존재하지 않는 데이터셋을 계속 폴링한다.
   */
  @Override
  public List<DatasetReferencesResponse.TriggerReferenceItem> findDatasetChangeTriggers(
      Long datasetId) {
    return dsl.selectDistinct(
            field(name("pipeline_trigger", "id"), Long.class),
            field(name("pipeline_trigger", "name"), String.class),
            field(name("pipeline", "id"), Long.class),
            field(name("pipeline", "name"), String.class))
        .from(table(name("pipeline_trigger")))
        .join(table(name("pipeline")))
        .on(
            field(name("pipeline_trigger", "pipeline_id"), Long.class)
                .eq(field(name("pipeline", "id"), Long.class)))
        .where(field(name("pipeline_trigger", "trigger_type"), String.class).eq("DATASET_CHANGE"))
        .and(
            condition(
                "(pipeline_trigger.config -> 'datasetIds') @> to_jsonb({0}::bigint)", datasetId))
        .orderBy(field(name("pipeline_trigger", "id"), Long.class))
        .fetch(
            r ->
                new DatasetReferencesResponse.TriggerReferenceItem(
                    r.get(field(name("pipeline_trigger", "id"), Long.class)),
                    r.get(field(name("pipeline_trigger", "name"), String.class)),
                    r.get(field(name("pipeline", "id"), Long.class)),
                    r.get(field(name("pipeline", "name"), String.class))));
  }

  /**
   * {@code ILIKE} 부분 일치 패턴 — 테이블 이름의 {@code _} 는 LIKE 와일드카드라 이스케이프한다(안 하면 1차 거르기가 넓어질 뿐 결과는 같다).
   */
  private static String containsPattern(String tableName) {
    String escaped = tableName.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    return "%" + escaped + "%";
  }
}
