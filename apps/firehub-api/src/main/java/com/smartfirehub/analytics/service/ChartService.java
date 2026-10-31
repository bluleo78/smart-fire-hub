package com.smartfirehub.analytics.service;

import com.smartfirehub.analytics.dto.ChartDataResponse;
import com.smartfirehub.analytics.dto.ChartResponse;
import com.smartfirehub.analytics.dto.CreateChartRequest;
import com.smartfirehub.analytics.dto.DeleteImpactResponse;
import com.smartfirehub.analytics.dto.UpdateChartRequest;
import com.smartfirehub.analytics.exception.ChartNotFoundException;
import com.smartfirehub.analytics.exception.SavedQueryNotFoundException;
import com.smartfirehub.analytics.repository.AnalyticsDashboardRepository;
import com.smartfirehub.analytics.repository.ChartRepository;
import com.smartfirehub.analytics.repository.SavedQueryRepository;
import com.smartfirehub.global.config.TransactionTemplateConfig;
import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.global.exception.EditConflictException;
import com.smartfirehub.pipeline.service.PipelineDataSchema;
import com.smartfirehub.pipeline.service.StoreTransactions;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.securitylevel.sql.GuardedSqlExecutor;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class ChartService {

  private final ChartRepository chartRepository;
  private final SavedQueryRepository savedQueryRepository;
  private final GuardedSqlExecutor guardedSqlExecutor;
  private final ClearanceResolver clearanceResolver;

  /** 삭제 영향 범위(WD-86) — 차트를 지우면 CASCADE 로 함께 사라지는 대시보드 위젯을 센다. */
  private final AnalyticsDashboardRepository dashboardRepository;

  /** 테넌트 저장소 판정 — Tibero 인지에 따라 차트 데이터 실행 구간을 나눈다(WD-207). */
  private final PipelineDataSchema pipelineDataSchema;

  /**
   * RLS GUC 가 필요한 조회 구간을 여는 readOnly 트랜잭션 템플릿 — 기존 {@code @Transactional(readOnly = true)} 와 같은
   * 값이다. 공용 빈({@link TransactionTemplateConfig#READ_ONLY})을 주입받는다.
   */
  @Qualifier(TransactionTemplateConfig.READ_ONLY)
  private final TransactionTemplate readOnlyTx;

  /** 작성자 본인의 생성·수정·삭제·공유 변경 감사(WD-464). */
  private final AnalyticsAuditRecorder auditRecorder;

  /**
   * 수정 감사의 바뀐 항목 키 → 설명용 라벨(WD-464). 공유 여부는 따로 STATUS_CHANGE 로 남기므로 여기 없다. 차트 설정(config)은 무엇이 바뀌었든
   * "config" 하나로만 남긴다 — 설정 값(원본 컬럼명 등)은 감사에 싣지 않는다.
   */
  private static final Map<String, String> AUDIT_FIELD_LABELS =
      Map.of("name", "이름", "description", "설명", "chartType", "차트 종류", "config", "차트 설정");

  /**
   * List charts with optional filters and pagination.
   *
   * <p>mineOnly=true 면 내가 만든 차트만(「내 차트」 탭, WD-328), sharedOnly=true 면 공유 차트만, 둘 다 없으면 내 차트 + 공유 차트.
   */
  // RLS 가 걸린 chart 를 읽는다 — 트랜잭션이 없으면 GUC 미설정으로 조용히 0행이 된다.
  @Transactional(readOnly = true)
  public PageResponse<ChartResponse> list(
      String search,
      String chartType,
      Long savedQueryId,
      Boolean sharedOnly,
      Boolean mineOnly,
      Long userId,
      int page,
      int size) {
    // 저장 쿼리를 통과하지 못하는 조회자에게는 config(원본 컬럼명)를 가린다(WD-31②).
    List<ChartResponse> content =
        withholdDenied(
            chartRepository.findAll(
                search, chartType, savedQueryId, sharedOnly, mineOnly, userId, page, size),
            userId);
    long total =
        chartRepository.countAll(search, chartType, savedQueryId, sharedOnly, mineOnly, userId);
    int totalPages = (int) Math.ceil((double) total / size);
    return new PageResponse<>(content, page, size, total, totalPages);
  }

  /** Create a new chart. Validates that the referenced saved query is accessible. */
  @Transactional
  public ChartResponse create(CreateChartRequest req, Long userId) {
    // 저장 쿼리 접근 확인만 한다(연결 데이터셋 이름은 쓰지 않아 가시성 조건이 필요 없다).
    savedQueryRepository
        .findById(req.savedQueryId(), userId, DSL.trueCondition())
        .orElseThrow(() -> new SavedQueryNotFoundException());
    if ("MAP".equals(req.chartType())) {
      Object spatialColumn = req.config() != null ? req.config().get("spatialColumn") : null;
      if (spatialColumn == null || spatialColumn.toString().isBlank()) {
        throw new IllegalArgumentException("MAP 차트는 config에 spatialColumn이 필요합니다");
      }
    }
    Long id = chartRepository.insert(req, userId);
    ChartResponse created =
        chartRepository
            .findById(id, userId)
            .orElseThrow(() -> ChartNotFoundException.reloadFailed());
    // 감사(WD-464) — 검증·저장이 끝난 뒤 같은 트랜잭션에서 남긴다. 이름·공유 여부만 싣고 설정 값은 싣지 않는다.
    auditRecorder.created(
        userId, AnalyticsAssetKind.CHART, id, created.name(), created.isShared(), null);
    return withholdDenied(created, userId);
  }

  /** Get a single chart — owner or any shared chart. */
  // RLS 가 걸린 chart 를 읽는다 — 트랜잭션이 없으면 GUC 미설정으로 조용히 0행이 된다.
  // 참고: getChartData 는 이 메서드를 부르지 않는다 — 리포지토리 원본을 직접 읽는 loadChart 를 readOnly 템플릿 구간 안에서
  // 부른다(WD-207). 이 어노테이션은 컨트롤러 등 외부 호출 경로용이다.
  @Transactional(readOnly = true)
  public ChartResponse getById(Long id, Long userId) {
    return withholdDenied(
        chartRepository.findById(id, userId).orElseThrow(() -> new ChartNotFoundException()),
        userId);
  }

  /** 편집 충돌 안내 문구(WD-364) — 웹은 코드로 충돌 창을 띄우고, 코드를 모르는 호출부는 이 문구를 그대로 보인다. */
  static final String EDIT_CONFLICT_MESSAGE = "이 차트는 편집을 시작한 뒤 다른 곳에서 변경되었습니다. 다시 불러온 뒤 저장하세요.";

  /**
   * Update a chart (owner only).
   *
   * <p>응답의 {@code updatedAt} 은 저장 뒤 같은 트랜잭션에서 다시 읽은 DB 값이다 — 웹이 다음 저장의 기준 버전으로 쓴다(WD-364).
   */
  @Transactional
  public ChartResponse update(Long id, UpdateChartRequest req, Long userId) {
    ChartResponse existing =
        chartRepository
            .findByIdForOwner(id, userId)
            .orElseThrow(() -> new ChartNotFoundException());
    // 낙관적 잠금(WD-364) — 소유자 확인(404) 뒤에 한다: 먼저 하면 남의 차트에 대한 낡은 요청이 409 를 받아 존재가 드러난다.
    // 행 잠금을 잡고 수정 시각을 읽어 편집 기준 버전과 비교한다. 잠금은 트랜잭션 끝까지 유지되어 동시 저장 둘이 같은 버전으로 통과하지 못한다.
    // MAP 검증보다 먼저 하는 이유: 낡은 화면에서 저장하면 낡은 내용으로 계산한 검증 오류 대신 "다른 곳에서 바뀌었다"를 먼저 알려야 한다.
    LocalDateTime currentUpdatedAt =
        chartRepository
            .lockUpdatedAtById(id)
            .orElseThrow(() -> new ChartNotFoundException())
            .value1();
    EditConflictException.check(req.expectedUpdatedAt(), currentUpdatedAt, EDIT_CONFLICT_MESSAGE);
    // 감사 비교용 저장 전 값(WD-464) — 행 잠금 뒤에 다시 읽는다. 잠금 전 값은 기준 버전 없이 들어온 저장(AI 도구)과 동시 저장이 겹치면
    // 남의 변경이 이 저장의 바뀐 항목으로 잡힌다.
    ChartResponse auditBefore =
        chartRepository
            .findByIdForOwner(id, userId)
            .orElseThrow(() -> new ChartNotFoundException());
    String effectiveType = req.chartType() != null ? req.chartType() : existing.chartType();
    java.util.Map<String, Object> effectiveConfig =
        req.config() != null ? req.config() : existing.config();
    if ("MAP".equals(effectiveType)) {
      Object spatialColumn = effectiveConfig != null ? effectiveConfig.get("spatialColumn") : null;
      if (spatialColumn == null || spatialColumn.toString().isBlank()) {
        throw new IllegalArgumentException("MAP 차트는 config에 spatialColumn이 필요합니다");
      }
    }
    chartRepository.update(id, req, userId);
    // 감사 비교는 가리기(withholdDenied) 전 원본으로 한다 — 가린 응답은 config 가 null 이라 바뀌지 않은 설정이 바뀐 것으로 보인다(WD-464).
    ChartResponse updated =
        chartRepository
            .findByIdForOwner(id, userId)
            .orElseThrow(() -> new ChartNotFoundException());
    auditUpdate(userId, auditBefore, updated);
    return withholdDenied(updated, userId);
  }

  /**
   * 수정 감사(WD-464). 저장 전후 DB 값을 비교해 바뀐 항목 이름만 모은다 — 공유 외 항목은 UPDATE 1행, 공유 여부는 STATUS_CHANGE 1행. 둘 다
   * 바뀌지 않았으면 아무것도 남기지 않는다. config 는 DB 에서 다시 읽은 JSON 끼리 비교한다(요청 값과 비교하면 숫자 타입·키 순서 차이로 오탐한다).
   */
  private void auditUpdate(Long userId, ChartResponse before, ChartResponse after) {
    List<String> changed = new ArrayList<>();
    if (!Objects.equals(before.name(), after.name())) changed.add("name");
    if (!Objects.equals(before.description(), after.description())) {
      changed.add("description");
    }
    if (!Objects.equals(before.chartType(), after.chartType())) changed.add("chartType");
    if (!Objects.equals(before.config(), after.config())) changed.add("config");
    auditRecorder.updated(
        userId, AnalyticsAssetKind.CHART, after.id(), after.name(), changed, AUDIT_FIELD_LABELS);
    if (before.isShared() != after.isShared()) {
      auditRecorder.shareChanged(
          userId, AnalyticsAssetKind.CHART, after.id(), after.name(), after.isShared(), null, null);
    }
  }

  /** Delete a chart (owner only). */
  @Transactional
  public void delete(Long id, Long userId) {
    // 감사에 남길 이름·공유 여부는 지우기 전에 읽어 둔다(WD-464).
    ChartResponse target =
        chartRepository
            .findByIdForOwner(id, userId)
            .orElseThrow(() -> new ChartNotFoundException());
    boolean deleted = chartRepository.deleteById(id, userId);
    if (!deleted) {
      throw new ChartNotFoundException();
    }
    // 감사(WD-464) — CASCADE 로 함께 사라지는 위젯은 따로 남기지 않는다(관리자 삭제 감사와 같은 단위).
    auditRecorder.deleted(userId, AnalyticsAssetKind.CHART, id, target.name(), target.isShared());
  }

  /**
   * 차트 삭제 시 함께 사라지는 대시보드 위젯(WD-86). 삭제와 같은 관문(소유자만, 남의 것·없는 것은 같은 404)을 쓴다 — 이유는 {@link
   * SavedQueryService#getDeleteImpact} 와 같다. 차트 자체가 삭제 대상이라 charts 는 항상 빈 목록이다.
   */
  // RLS 가 걸린 chart/dashboard/dashboard_widget 을 읽는다 — 트랜잭션이 없으면 조용히 0행("연결 없음"으로 위장)이 된다.
  @Transactional(readOnly = true)
  public DeleteImpactResponse getDeleteImpact(Long id, Long userId) {
    chartRepository.findByIdForOwner(id, userId).orElseThrow(() -> new ChartNotFoundException());
    return DeleteImpactResponse.forChart(
        dashboardRepository.findImpactByChartIds(List.of(id), userId));
  }

  /**
   * Get a single chart without throwing — used for dashboard data loading.
   *
   * <p>주의: config 를 가리지 않은 <b>원본</b>이다(WD-31②). 저장 쿼리를 볼 수 없는 조회자에게 config 가 새지 않도록, 이 결과를 응답에 직접
   * 쓰지 말고 withholdDenied/deniedData 를 거쳐 내보낸다.
   */
  // RLS 가 걸린 chart 를 읽는다 — 트랜잭션이 없으면 GUC 미설정으로 조용히 0행이 된다.
  @Transactional(readOnly = true)
  public java.util.Optional<com.smartfirehub.analytics.dto.ChartResponse> getByIdOptional(
      Long id, Long userId) {
    return chartRepository.findById(id, userId);
  }

  /**
   * 조회자 자격으로 판정하고 실행한다(빈 SQL 은 빈 결과). readOnly=false 는 차트 데이터와 같은 기존 동작이다(쓰기 집합도 관문이 VIEW·하향 규칙으로
   * 판정한다 — 판단 사항 8).
   */
  public com.smartfirehub.analytics.dto.AnalyticsQueryResponse executeQueryForCache(
      Clearance viewer, String sql) {
    if (sql == null || sql.isBlank()) {
      return new com.smartfirehub.analytics.dto.AnalyticsQueryResponse(
          "SELECT", java.util.List.of(), java.util.List.of(), 0, 0L, 0, false, null);
    }
    return executeJudged(judge(viewer, sql));
  }

  /**
   * 조회자 기준 차트 SQL 판정(실행하지 않음) — 대시보드 일괄 경로가 캐시를 읽기 전에 매 요청 부른다. 거부는 예외가 아니라 {@link
   * GuardedSqlExecutor.AnalyticsJudgment#denied()} 값이다(위젯 하나 때문에 화면 전체가 403 이 되지 않게).
   */
  public GuardedSqlExecutor.AnalyticsJudgment judge(Clearance viewer, String sqlText) {
    return guardedSqlExecutor.judgeAnalytics(viewer, sqlText);
  }

  /**
   * 판정 토큰을 차트 데이터 조건(최대 1000행, readOnly=false)으로 실행한다 — 판정한 바로 그 정규화본이 실행되고 다시 판정하지 않는다. 대시보드 캐시
   * 채움(캐시 키는 saved_query_id + 판정한 SQL 원문)과 단건 차트 데이터가 같이 쓴다.
   */
  public com.smartfirehub.analytics.dto.AnalyticsQueryResponse executeJudged(
      GuardedSqlExecutor.AnalyticsJudgment judgment) {
    return guardedSqlExecutor.executeJudgedAnalytics(judgment, 1000, false);
  }

  /**
   * Execute the chart's linked saved query and return combined chart + query result. The user must
   * have access to the chart (owner or shared).
   */
  public ChartDataResponse getChartData(Long id, Long userId) {
    // PG 테넌트: 세 단계를 readOnly 트랜잭션 하나에서 차례로 실행한다.
    // Tibero 실행(최대 35초) 동안 PG 연결을 쥐지 않으려고 구간을 나눈다(WD-207).
    // 1) RLS GUC 가 필요한 차트·SQL 조회만 readOnly 트랜잭션, 2) 판정·실행은 트랜잭션 밖(판정은 자체 트랜잭션 리포지토리를 쓴다),
    // 3) 응답 조립은 DB 읽기가 없어 템플릿이 필요 없다.
    return StoreTransactions.loadThenExecute(
        pipelineDataSchema,
        readOnlyTx,
        readOnlyTx,
        () -> loadChart(id, userId),
        load -> assembleChartData(executeChart(load, userId)));
  }

  /** 1단계에서 읽은 차트 응답과 저장 쿼리 SQL 원문. */
  private record ChartLoad(ChartResponse chart, String sqlText) {}

  /** 2단계 결과 — 판정이 거부면 result 는 null(실행하지 않았다). */
  private record ChartExecution(
      ChartResponse chart,
      GuardedSqlExecutor.AnalyticsJudgment judgment,
      com.smartfirehub.analytics.dto.AnalyticsQueryResponse result) {}

  /** 1단계: 차트와 저장 쿼리 SQL 을 읽는다. RLS 가 걸려 있어 반드시 트랜잭션 안에서 불러야 한다. */
  private ChartLoad loadChart(Long id, Long userId) {
    // getById 가 아니라 리포지토리 원본을 쓴다 — getById 의 config 가림 판정과 아래 판정이 같은 저장 쿼리를 두 번 판정하지 않게. 통과하면
    // 조회자가 데이터를 보므로 config 를 그대로 주고, 거부면 deniedData 가 config 를 빈 맵으로 지운다.
    ChartResponse chart =
        chartRepository.findById(id, userId).orElseThrow(() -> new ChartNotFoundException());
    String sqlText =
        chartRepository
            .findSavedQuerySqlText(id, userId)
            .orElseThrow(() -> SavedQueryNotFoundException.usedByChart());
    return new ChartLoad(chart, sqlText);
  }

  /** 2단계: 조회자 기준으로 판정하고, 통과하면 실행한다. Tibero 에서는 트랜잭션 밖에서 불린다. */
  private ChartExecution executeChart(ChartLoad load, Long userId) {
    // 보안 등급(S2): 조회자 기준 판정 — 위반이면 실행하지 않고 200 + denied. 단건 위젯 경로(DashboardWidgetCard→useChartData)도
    // 대시보드 일괄 경로와 같은 계약이어야 위젯 하나가 화면 전체를 오류로 만들지 않는다(스펙 §4.2 4행).
    // 판정은 한 번 — 통과한 토큰의 정규화본을 그대로 실행하므로 판정과 실행 사이에 다른 판정이 끼지 않는다.
    GuardedSqlExecutor.AnalyticsJudgment judgment =
        judge(clearanceResolver.resolve(userId), load.sqlText());
    if (judgment.denied()) {
      return new ChartExecution(load.chart(), judgment, null);
    }
    return new ChartExecution(load.chart(), judgment, executeJudged(judgment));
  }

  /** 3단계: 응답 조립(DB 읽기 없음). 거부는 denied 응답, 통과는 실행 결과와 조회자별 내보내기 플래그. */
  private ChartDataResponse assembleChartData(ChartExecution exec) {
    if (exec.judgment().denied()) {
      return deniedData(exec.chart());
    }
    // 내보내기 플래그는 조회자 판정 토큰에서 계산한다(설계 결정 5 — 결과 캐시와 무관하게 조회자별).
    return new ChartDataResponse(
        exec.chart(), exec.result(), false, exec.judgment().exportAllowedFor());
  }

  /**
   * 조회자가 차트의 저장 쿼리를 SQL 판정으로 통과하지 못하면 config 를 가린다(WD-31②, 스펙 §5.1). 저장 쿼리 id 단위로 요청 범위 캐시 — 목록에서
   * 같은 쿼리를 쓰는 차트마다 다시 판정하지 않는다. 빈 SQL·사라진 저장 쿼리는 가릴 대상이 없다.
   */
  private List<ChartResponse> withholdDenied(List<ChartResponse> charts, Long userId) {
    if (charts.isEmpty()) {
      return charts;
    }
    Clearance viewer = clearanceResolver.resolve(userId);
    Map<Long, Boolean> deniedByQuery = new HashMap<>();
    return charts.stream()
        .map(
            c -> {
              // computeIfAbsent 는 null 키를 허용하지 않는 구현이 있어(HashMap 은 허용) 저장 쿼리 없는 차트는 먼저 걸러낸다.
              if (c.savedQueryId() == null) {
                return c;
              }
              boolean denied =
                  deniedByQuery.computeIfAbsent(c.savedQueryId(), q -> isDenied(viewer, q));
              return denied ? c.withConfigWithheld() : c;
            })
        .toList();
  }

  private ChartResponse withholdDenied(ChartResponse chart, Long userId) {
    return withholdDenied(List.of(chart), userId).get(0);
  }

  /**
   * 저장 쿼리 SQL 을 조회자 자격으로 판정만 한다(실행 X). 파싱 불가 SQL 은 거부가 아니다(기존 200 + error 계약, 판정 토큰의 denied=false).
   */
  private boolean isDenied(Clearance viewer, Long savedQueryId) {
    return chartRepository
        .findSavedQuerySqlTextById(savedQueryId)
        .filter(sql -> !sql.isBlank())
        .map(sql -> judge(viewer, sql).denied())
        .orElse(false);
  }

  /**
   * denied 위젯 응답 — queryResult 는 null 이 아닌 빈 결과(null 이면 차트 빌더 등 다른 소비자가 깨진다, 판단 사항 7). 거부 코드·원본 이름은
   * 싣지 않는다. chart 는 웹 잠금 상태가 쓰는 최소 메타만 남긴다 — config(원본 테이블의 컬럼명이 들어 있다)는 빈 맵, savedQueryName 은
   * null. 빈 맵인 이유: config 를 순회하는 소비자(웹 ChartRenderer·ai-agent)가 null 에서 깨지지 않게. 데이터 응답이라
   * configWithheld=true 로 표시한다. GET/목록/저장 응답의 가림은 {@link #withholdDenied} 가 config=null 로 한다(그쪽은
   * 재저장 시 "유지" 계약 때문에 null).
   */
  public static ChartDataResponse deniedData(ChartResponse chart) {
    ChartResponse minimal =
        new ChartResponse(
            chart.id(),
            chart.name(),
            chart.description(),
            chart.savedQueryId(),
            null,
            chart.chartType(),
            Map.of(),
            chart.isShared(),
            chart.createdByName(),
            chart.createdBy(),
            chart.createdAt(),
            chart.updatedAt(),
            chart.dashboardCount(),
            true);
    return new ChartDataResponse(
        minimal,
        new com.smartfirehub.analytics.dto.AnalyticsQueryResponse(
            "SELECT", List.of(), List.of(), 0, 0L, 0, false, null),
        true);
  }
}
