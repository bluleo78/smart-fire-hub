package com.smartfirehub.dashboard.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dashboard.dto.RecentExecutionResponse;
import com.smartfirehub.dashboard.repository.DashboardStatsRepository.AuditActivityRow;
import com.smartfirehub.dashboard.repository.DashboardStatsRepository.ExecutionRow;
import com.smartfirehub.dashboard.repository.DashboardStatsRepository.FailedImportRow;
import com.smartfirehub.dashboard.repository.DashboardStatsRepository.FailedPipelineRow;
import com.smartfirehub.dashboard.repository.DashboardStatsRepository.RecentImportRow;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import com.smartfirehub.user.repository.TenantPairContractBase;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * DashboardStatsRepository 계약 테스트 — 공개 메서드 20개 전부를 구현마다 하위 클래스로 실행한다(WD-485·WD-710). jOOQ·MyBatis 는
 * PG, TiberoMyBatis 는 Tibero 실서버(두 계정 RLS)다.
 *
 * <p>무엇을: 홈 대시보드 집계는 테넌트 전체를 세므로 시험마다 새 테넌트 둘(A·B)을 만들어 값을 정확히 단언한다. 데이터셋 가시성 조각을 쓰는 12개 메서드는 모두 「안
 * 보이는 등급(rank 5) 데이터셋이 rank 2 자격의 집계에서 빠진다」를 함께 본다. 다른 테넌트(B) 행은 A 의 어떤 집계에도 들어가지 않는다.
 *
 * <p>왜 이 시나리오인가: Tibero 로 옮기며 바뀌는 곳(날짜 버킷 TRUNC, metadata JSON_VALUE, resource_id 숫자 판정, 상관 서브쿼리의
 * {@code ORDER BY id DESC LIMIT 1} → MAX(id) 조인)을 각각 갈라 내는 행을 둔다 — 같은 날 다른 시각·자정 직후 행, id 순서와 생성 시각
 * 순서가 어긋나는 실행, 숫자가 아닌·없는 데이터셋을 가리키는 감사 행, 경계 시각에 딱 걸친 행.
 *
 * <p>시각은 모두 초 단위 고정값이다(저장 TZ 벽시계 그대로 바인딩·비교).
 */
abstract class DashboardStatsRepositoryContractTest extends TenantPairContractBase {

  /** 계약 대상 리포지토리 — 구현별 하위 클래스가 제공한다. */
  protected abstract DashboardStatsRepository repository();

  /** 시험 기준 시각 — 서비스가 넘기는 경계(24시간 전·추이 시작)를 흉내 낸다. */
  private static final LocalDateTime BASE = LocalDateTime.of(2026, 3, 10, 12, 0, 0);

  private long user;

  /** 테넌트 A 의 안 보이는 등급(rank 5) — rank 2 자격에서 가려진다. */
  private long secretLevel;

  @BeforeEach
  void setUpViewer() {
    user = fx.createUser(prefix + "-u", null);
    secretLevel = fx.createSecurityLevel(tenantA, 5, prefix + "-secret");
  }

  // ---- 도우미 ----

  /** rank 2 자격(기본 등급은 보이고 rank 5 등급은 안 보인다). */
  private DatasetVisibility rank2() {
    return new DatasetVisibility(new Clearance(user, tenantA, 2, Set.of(), false, Set.of()), null);
  }

  /** 등급 없는 자격 — 어떤 데이터셋도 안 보인다(1=0). */
  private DatasetVisibility none() {
    return new DatasetVisibility(Clearance.none(user, tenantA), null);
  }

  private static final DatasetVisibility ALL = DatasetVisibility.UNRESTRICTED;

  /** 테넌트 A 의 TABLE·SOURCE 데이터셋(이름 = table_name = 접두+suffix). hidden 이면 rank 5 등급. */
  private long dataset(String suffix, boolean hidden) {
    return fx.createDataset(
        tenantA, fx.datasetCreator(), prefix + suffix, hidden ? secretLevel : null);
  }

  /** 데이터셋 생성 시각을 바꾼다(신규·오래된 데이터셋 판정용). */
  private void createdAt(long datasetId, LocalDateTime at) {
    fx.setDatasetField(datasetId, "created_at", at);
  }

  /** 테넌트 A 의 감사 행(결과 SUCCESS, 메타데이터 없음). */
  private long audit(String action, String resource, String resourceId, LocalDateTime at) {
    return audit(tenantA, action, resource, resourceId, at, null);
  }

  private long audit(
      long tenant,
      String action,
      String resource,
      String resourceId,
      LocalDateTime at,
      String metadataJson) {
    return fx.insertAuditLog(
        tenant, prefix + "-u", action, resource, resourceId, "desc-" + action, at, metadataJson);
  }

  /** 데이터셋 임포트 감사 행(SUCCESS). */
  private long imported(long datasetId, LocalDateTime at) {
    return audit("IMPORT", "dataset", String.valueOf(datasetId), at);
  }

  /** 데이터셋 임포트 실패 감사 행. */
  private long importFailed(long datasetId, LocalDateTime at) {
    long id = imported(datasetId, at);
    fx.setAuditLogResult(id, "FAILURE");
    return id;
  }

  /** 상태·생성 시각을 정한 실행 한 행. */
  private long exec(long pipelineId, String status, LocalDateTime createdAt) {
    long id = fx.createExecution(pipelineId, status);
    fx.setExecutionField(id, "created_at", createdAt);
    return id;
  }

  // ---- 데이터셋 통계·표 이름 ----

  @Test
  @DisplayName("데이터셋 수·원천·파생 수와 표 이름은 보이는 데이터셋만 세고, 안 보이는 등급·문서·다른 테넌트는 빠진다")
  void datasetCounts_andTableNames_respectVisibility() {
    dataset("-src", false);
    long derived = dataset("-der", false);
    fx.setDatasetField(derived, "origin_type", "DERIVED");
    long doc = dataset("-doc", false);
    fx.setDatasetField(doc, "storage_type", "DOCUMENT");
    long temp = dataset("-tmp", false);
    fx.setDatasetField(temp, "origin_type", "TEMP");
    dataset("-hsrc", true);
    long hiddenDerived = dataset("-hder", true);
    fx.setDatasetField(hiddenDerived, "origin_type", "DERIVED");
    fx.createDataset(tenantB, fx.datasetCreator(), prefix + "-bsrc", null);

    assertThat(inA(() -> repository().countDatasets(ALL))).isEqualTo(6);
    // 묶음 카운트(WD-733) — 개별 단언이던 전체·원천·파생 값과 같아야 한다
    assertThat(inA(() -> repository().countDatasetsByKind(ALL)))
        .isEqualTo(new DashboardStatsRepository.DatasetCounts(6, 2, 2));
    assertThat(inA(() -> repository().findTableDatasetNames(ALL)))
        .containsExactlyInAnyOrder(
            prefix + "-src", prefix + "-der", prefix + "-tmp", prefix + "-hsrc", prefix + "-hder");

    assertThat(inA(() -> repository().countDatasets(rank2()))).isEqualTo(4);
    assertThat(inA(() -> repository().countDatasetsByKind(rank2())))
        .isEqualTo(new DashboardStatsRepository.DatasetCounts(4, 1, 1));
    assertThat(inA(() -> repository().findTableDatasetNames(rank2())))
        .containsExactlyInAnyOrder(prefix + "-src", prefix + "-der", prefix + "-tmp");

    assertThat(inA(() -> repository().countDatasets(none()))).isZero();
    // 행이 하나도 없어도 SUM 이 NULL 이 되지 않고 0 이다
    assertThat(inA(() -> repository().countDatasetsByKind(none())))
        .isEqualTo(new DashboardStatsRepository.DatasetCounts(0, 0, 0));
    assertThat(inA(() -> repository().findTableDatasetNames(none()))).isEmpty();

    assertThat(inB(() -> repository().countDatasets(ALL))).isEqualTo(1);
    assertThat(inB(() -> repository().countDatasetsByKind(ALL)))
        .isEqualTo(new DashboardStatsRepository.DatasetCounts(1, 1, 0));
    assertThat(inB(() -> repository().findTableDatasetNames(ALL)))
        .containsExactly(prefix + "-bsrc");
  }

  // ---- 파이프라인 수 ----

  @Test
  @DisplayName("파이프라인 전체·활성·비활성 수는 자기 테넌트 것만 센다")
  void pipelineCounts() {
    fx.createPipeline(tenantA, prefix + "-p1", true);
    fx.createPipeline(tenantA, prefix + "-p2", true);
    fx.createPipeline(tenantA, prefix + "-p3", false);
    fx.createPipeline(tenantB, prefix + "-pb", true);

    assertThat(inA(() -> repository().countPipelines())).isEqualTo(3);
    assertThat(inA(() -> repository().countPipelineStats()))
        .isEqualTo(new DashboardStatsRepository.PipelineCounts(3, 2));
    assertThat(inA(() -> repository().countDisabledPipelines())).isEqualTo(1);
    assertThat(inB(() -> repository().countPipelines())).isEqualTo(1);
    assertThat(inB(() -> repository().countPipelineStats()))
        .isEqualTo(new DashboardStatsRepository.PipelineCounts(1, 1));
    assertThat(inB(() -> repository().countDisabledPipelines())).isZero();
  }

  // ---- 마지막 실행 상태(MAX(id) 판정) ----

  @Test
  @DisplayName("활성 파이프라인의 마지막 실행은 생성 시각이 아니라 가장 큰 id 로 고르고, 실패면 마지막 실패·성공 시각과 함께 돌려준다")
  void latestStatus_andFailedActivePipelines_pickMaxId() {
    // pA: 가장 큰 id(e3)가 FAILED 인데 생성 시각은 가장 이르다 → 마지막 상태 FAILED.
    long pA = fx.createPipeline(tenantA, prefix + "-pa", true);
    exec(pA, "COMPLETED", BASE.minusHours(3));
    exec(pA, "FAILED", BASE.minusHours(1));
    exec(pA, "FAILED", BASE.minusDays(5));
    // pB: 생성 시각이 가장 늦은 것은 FAILED 지만 가장 큰 id 는 COMPLETED → 마지막 상태 COMPLETED(실패 목록에 없다).
    long pB = fx.createPipeline(tenantA, prefix + "-pb", true);
    exec(pB, "FAILED", BASE.plusHours(5));
    exec(pB, "COMPLETED", BASE.minusDays(9));
    // pC: 실행 하나(RUNNING).
    long pC = fx.createPipeline(tenantA, prefix + "-pc", true);
    exec(pC, "RUNNING", BASE);
    // pF: 성공 이력 없는 실패 → 마지막 성공 시각 null.
    long pF = fx.createPipeline(tenantA, prefix + "-pf", true);
    exec(pF, "FAILED", BASE.minusHours(2));
    // pD: 비활성(마지막 FAILED) → 빠진다. pE: 실행 없음 → 빠진다.
    long pD = fx.createPipeline(tenantA, prefix + "-pd", false);
    exec(pD, "FAILED", BASE);
    fx.createPipeline(tenantA, prefix + "-pe", true);
    // 다른 테넌트의 실패 파이프라인.
    long pX = fx.createPipeline(tenantB, prefix + "-px", true);
    exec(pX, "FAILED", BASE);

    assertThat(inA(() -> repository().findLatestStatusesOfActivePipelines()))
        .containsExactlyInAnyOrder("FAILED", "COMPLETED", "RUNNING", "FAILED");

    List<FailedPipelineRow> failed = inA(() -> repository().findFailedActivePipelines());
    assertThat(failed)
        .containsExactlyInAnyOrder(
            new FailedPipelineRow(pA, prefix + "-pa", BASE.minusHours(1), BASE.minusHours(3)),
            new FailedPipelineRow(pF, prefix + "-pf", BASE.minusHours(2), null));

    assertThat(inB(() -> repository().findLatestStatusesOfActivePipelines()))
        .containsExactly("FAILED");
    assertThat(inB(() -> repository().findFailedActivePipelines()))
        .extracting(FailedPipelineRow::pipelineId)
        .containsExactly(pX);
  }

  // ---- 최근 실행·피드 ----

  @Test
  @DisplayName("최근 실행 5건과 피드 실행은 생성 시각 내림차순(같으면 id 내림차순)이고 다른 테넌트 실행은 없다")
  void recentExecutions_andFeed_orderedByCreatedAtThenId() {
    long p = fx.createPipeline(tenantA, prefix + "-p", true);
    long e1 = exec(p, "COMPLETED", BASE.minusHours(6));
    long e2 = exec(p, "FAILED", BASE.minusHours(5));
    long e3 = exec(p, "COMPLETED", BASE.minusHours(4));
    long e4 = exec(p, "RUNNING", BASE.minusHours(2));
    long e5 = exec(p, "PENDING", BASE.minusHours(2)); // e4 와 같은 시각 → id 큰 e5 가 먼저
    long e6 = exec(p, "CANCELLED", BASE.minusHours(1));
    fx.setExecutionField(e3, "started_at", BASE.minusHours(4).plusMinutes(1));
    fx.setExecutionField(e3, "completed_at", BASE.minusHours(4).plusMinutes(7));
    long pb = fx.createPipeline(tenantB, prefix + "-pb", true);
    exec(pb, "COMPLETED", BASE);

    List<RecentExecutionResponse> recent = inA(() -> repository().findRecentExecutions());
    assertThat(recent).extracting(RecentExecutionResponse::id).containsExactly(e6, e5, e4, e3, e2);
    assertThat(recent.get(0))
        .isEqualTo(new RecentExecutionResponse(e6, prefix + "-p", "CANCELLED", BASE.minusHours(1)));

    List<ExecutionRow> feed = inA(() -> repository().findRecentExecutionsForFeed());
    assertThat(feed).extracting(ExecutionRow::id).containsExactly(e6, e5, e4, e3, e2, e1);
    assertThat(feed.get(3))
        .isEqualTo(
            new ExecutionRow(
                e3,
                p,
                prefix + "-p",
                "COMPLETED",
                BASE.minusHours(4),
                BASE.minusHours(4).plusMinutes(7),
                BASE.minusHours(4).plusMinutes(1)));
    assertThat(feed.get(0).completedAt()).isNull();
    assertThat(feed.get(0).startedAt()).isNull();

    assertThat(inB(() -> repository().findRecentExecutions())).hasSize(1);
    assertThat(inB(() -> repository().findRecentExecutionsForFeed())).hasSize(1);
  }

  // ---- 일자별 실행 수 ----

  @Test
  @DisplayName("일자별 실행 수는 날짜로 묶고(같은 날 다른 시각은 한 칸, 자정 직후는 다음 날) 시작 시각과 같은 행은 포함한다")
  void executionsByDay_bucketsByCalendarDay() {
    LocalDateTime from = LocalDateTime.of(2026, 3, 1, 0, 0, 0);
    long p = fx.createPipeline(tenantA, prefix + "-p", true);
    exec(p, "COMPLETED", from); // 경계(>=) 포함
    exec(p, "COMPLETED", LocalDateTime.of(2026, 3, 1, 10, 30, 0));
    exec(p, "FAILED", LocalDateTime.of(2026, 3, 1, 23, 59, 59));
    exec(p, "COMPLETED", LocalDateTime.of(2026, 3, 2, 0, 0, 1));
    exec(p, "COMPLETED", LocalDateTime.of(2026, 2, 28, 23, 59, 59)); // 시작 전
    long pb = fx.createPipeline(tenantB, prefix + "-pb", true);
    exec(pb, "COMPLETED", LocalDateTime.of(2026, 3, 1, 9, 0, 0));

    assertThat(inA(() -> repository().countExecutionsByDay(from)))
        .isEqualTo(Map.of(LocalDate.of(2026, 3, 1), 3, LocalDate.of(2026, 3, 2), 1));
    assertThat(inB(() -> repository().countExecutionsByDay(from)))
        .isEqualTo(Map.of(LocalDate.of(2026, 3, 1), 1));
  }

  // ---- 최근 임포트 ----

  @Test
  @DisplayName("최근 임포트 5건은 LIMIT 전에 안 보이는 데이터셋을 거르고, 파일명은 metadata.fileName, 같은 시각은 id 내림차순이다")
  void recentImports_filterBeforeLimit_andReadFileName() {
    long v = dataset("-v", false);
    long h = dataset("-h", true);
    // 안 보이는 데이터셋의 더 새로운 임포트 5건 — 거르지 않고 자르면 보이는 행이 밀려난다.
    for (int i = 0; i < 5; i++) {
      imported(h, BASE.plusHours(10 + i));
    }
    long older =
        audit(
            tenantA,
            "IMPORT",
            "dataset",
            String.valueOf(v),
            BASE.minusHours(1),
            "{\"fileName\":\"a.csv\",\"rows\":3}");
    long tieLow = importFailed(v, BASE);
    long tieHigh = imported(v, BASE); // 같은 시각 → id 큰 것이 먼저
    audit("CREATE", "dataset", String.valueOf(v), BASE.plusHours(1)); // 임포트 아님
    audit("IMPORT", "pipeline", String.valueOf(v), BASE.plusHours(1)); // 데이터셋 아님
    audit("IMPORT", "dataset", "999999999999", BASE.plusHours(1)); // 없는 데이터셋(내부 조인)
    audit("IMPORT", "dataset", "abc", BASE.plusHours(1)); // 숫자 아님
    long b = fx.createDataset(tenantB, fx.datasetCreator(), prefix + "-b", null);
    audit(tenantB, "IMPORT", "dataset", String.valueOf(b), BASE.plusHours(30), null);

    List<RecentImportRow> visible = inA(() -> repository().findRecentImports(rank2()));
    assertThat(visible).extracting(RecentImportRow::id).containsExactly(tieHigh, tieLow, older);
    assertThat(visible.get(1))
        .isEqualTo(new RecentImportRow(tieLow, prefix + "-v", null, "FAILURE", BASE));
    assertThat(visible.get(2))
        .isEqualTo(
            new RecentImportRow(older, prefix + "-v", "a.csv", "SUCCESS", BASE.minusHours(1)));

    List<RecentImportRow> all = inA(() -> repository().findRecentImports(ALL));
    assertThat(all).hasSize(5).extracting(RecentImportRow::datasetName).containsOnly(prefix + "-h");

    assertThat(inA(() -> repository().findRecentImports(none()))).isEmpty();
    assertThat(inB(() -> repository().findRecentImports(ALL)))
        .extracting(RecentImportRow::datasetName)
        .containsExactly(prefix + "-b");
  }

  // ---- 임포트 실패 ----

  @Test
  @DisplayName("기준 시각 이후(경계 제외) 임포트 실패만 최신순으로, 안 보이는 데이터셋·성공·없는 데이터셋은 빠진다")
  void failedImportsSince() {
    long v = dataset("-v", false);
    long h = dataset("-h", true);
    importFailed(v, BASE); // 경계(>) 제외
    importFailed(v, BASE.minusHours(1)); // 이전
    importFailed(v, BASE.plusHours(1));
    importFailed(v, BASE.plusHours(2));
    imported(v, BASE.plusHours(3)); // 성공
    importFailed(h, BASE.plusHours(4)); // 안 보이는 등급
    long orphan = audit("IMPORT", "dataset", "999999999999", BASE.plusHours(5));
    fx.setAuditLogResult(orphan, "FAILURE");
    long b = fx.createDataset(tenantB, fx.datasetCreator(), prefix + "-b", null);
    long bFail = audit(tenantB, "IMPORT", "dataset", String.valueOf(b), BASE.plusHours(6), null);
    fx.setAuditLogResult(bFail, "FAILURE");

    assertThat(inA(() -> repository().findFailedImportsSince(rank2(), BASE)))
        .containsExactly(
            new FailedImportRow(v, prefix + "-v", BASE.plusHours(2)),
            new FailedImportRow(v, prefix + "-v", BASE.plusHours(1)));
    assertThat(inA(() -> repository().findFailedImportsSince(ALL, BASE)))
        .extracting(FailedImportRow::occurredAt)
        .containsExactly(BASE.plusHours(4), BASE.plusHours(2), BASE.plusHours(1));
    assertThat(inA(() -> repository().findFailedImportsSince(none(), BASE))).isEmpty();
    assertThat(inB(() -> repository().findFailedImportsSince(ALL, BASE)))
        .containsExactly(new FailedImportRow(b, prefix + "-b", BASE.plusHours(6)));
  }

  // ---- 데이터셋 건강도(fresh·stale) ----

  @Test
  @DisplayName("신선·오래됨 판정은 원천 데이터셋의 SUCCESS 임포트와 생성 시각 경계로 세고, 안 보이는 등급은 rank 2 에서 빠진다")
  void freshAndStaleCounts() {
    LocalDateTime fresh = BASE; // 24시간 전에 해당
    LocalDateTime stale = BASE.minusDays(7); // 7일 전에 해당
    // 임포트가 있는 원천 데이터셋(생성 시각은 지금 — 「임포트 없음」 집계와 무관).
    long d1 = dataset("-d1", false);
    imported(d1, fresh.plusHours(1));
    imported(d1, fresh.plusHours(2)); // 같은 데이터셋 두 번 → 한 번만 센다
    long d2 = dataset("-d2", false);
    imported(d2, fresh); // 경계(>) — 신선 아님, stale 이후라 오래됨도 아님
    long d3 = dataset("-d3", false);
    imported(d3, stale.minusDays(1)); // 오래됨
    long d4 = dataset("-d4", false);
    imported(d4, stale); // stale 경계 — 그 이후(>) 성공이 없어 오래됨
    long d5 = dataset("-d5", false);
    importFailed(d5, fresh.plusHours(1)); // 실패뿐 — 신선·오래됨 아님, 임포트 이력은 있음
    long d10 = dataset("-d10", false);
    fx.setDatasetField(d10, "origin_type", "DERIVED");
    imported(d10, fresh.plusHours(1)); // 파생 — 원천만 센다
    // 임포트가 없는 원천 데이터셋(생성 시각으로 판정).
    long d6 = dataset("-d6", false);
    createdAt(d6, fresh.plusHours(1)); // 새로 만든 것
    audit("IMPORT", "pipeline", String.valueOf(d6), fresh.plusHours(2)); // 데이터셋 감사 아님
    long d7 = dataset("-d7", false);
    createdAt(d7, fresh); // 경계(>) — 새것 아님, stale 이후라 오래됨도 아님
    long d8 = dataset("-d8", false);
    createdAt(d8, stale); // 경계(<=) — 오래됨
    long d9 = dataset("-d9", false);
    createdAt(d9, stale.minusDays(1)); // 오래됨
    // resource_id 문자열 비교: 앞자리 0 은 같은 데이터셋으로 보지 않는다(jOOQ 의 문자열 조인과 같다).
    audit("IMPORT", "dataset", "0" + d9, fresh.plusHours(1));
    long d11 = dataset("-d11", false);
    fx.setDatasetField(d11, "storage_type", "DOCUMENT");
    createdAt(d11, stale.minusDays(1)); // 문서 — 원천 표가 아니다
    // 안 보이는 등급 — 각 집계에 하나씩.
    long h1 = dataset("-h1", true);
    imported(h1, fresh.plusHours(1));
    long h2 = dataset("-h2", true);
    createdAt(h2, fresh.plusHours(1));
    long h3 = dataset("-h3", true);
    imported(h3, stale.minusDays(1));
    long h4 = dataset("-h4", true);
    createdAt(h4, stale.minusDays(1));
    // 다른 테넌트.
    long b = fx.createDataset(tenantB, fx.datasetCreator(), prefix + "-b", null);
    audit(tenantB, "IMPORT", "dataset", String.valueOf(b), fresh.plusHours(1), null);

    assertThat(inA(() -> repository().countFreshImportedDatasets(rank2(), fresh))).isEqualTo(1);
    assertThat(inA(() -> repository().countNewSourceDatasetsWithoutImport(rank2(), fresh)))
        .isEqualTo(1);
    assertThat(inA(() -> repository().countStaleImportedDatasets(rank2(), stale))).isEqualTo(2);
    assertThat(inA(() -> repository().countStaleSourceDatasetsWithoutImport(rank2(), stale)))
        .isEqualTo(2);

    assertThat(inA(() -> repository().countFreshImportedDatasets(ALL, fresh))).isEqualTo(2);
    assertThat(inA(() -> repository().countNewSourceDatasetsWithoutImport(ALL, fresh)))
        .isEqualTo(2);
    assertThat(inA(() -> repository().countStaleImportedDatasets(ALL, stale))).isEqualTo(3);
    assertThat(inA(() -> repository().countStaleSourceDatasetsWithoutImport(ALL, stale)))
        .isEqualTo(3);

    assertThat(inA(() -> repository().countFreshImportedDatasets(none(), fresh))).isZero();
    assertThat(inA(() -> repository().countNewSourceDatasetsWithoutImport(none(), fresh))).isZero();
    assertThat(inA(() -> repository().countStaleImportedDatasets(none(), stale))).isZero();
    assertThat(inA(() -> repository().countStaleSourceDatasetsWithoutImport(none(), stale)))
        .isZero();

    assertThat(inB(() -> repository().countFreshImportedDatasets(ALL, fresh))).isEqualTo(1);
    assertThat(inB(() -> repository().countStaleImportedDatasets(ALL, stale))).isZero();
  }

  // ---- 일자별 임포트 수 ----

  @Test
  @DisplayName("일자별 임포트 수는 날짜로 묶고 안 보이는 데이터셋은 빼되, 데이터셋 행이 없는 이력(삭제·숫자 아님)은 센다")
  void importsByDay_bucketsAndKeepsOrphans() {
    LocalDateTime from = LocalDateTime.of(2026, 3, 1, 0, 0, 0);
    long v = dataset("-v", false);
    long h = dataset("-h", true);
    imported(v, from); // 경계(>=) 포함
    imported(v, LocalDateTime.of(2026, 3, 1, 23, 59, 59));
    importFailed(v, LocalDateTime.of(2026, 3, 2, 0, 0, 1)); // 결과와 무관하게 센다
    imported(h, LocalDateTime.of(2026, 3, 2, 10, 0, 0)); // 안 보이는 등급
    audit("IMPORT", "dataset", "999999999999", LocalDateTime.of(2026, 3, 2, 11, 0, 0));
    audit("IMPORT", "dataset", "abc", LocalDateTime.of(2026, 3, 2, 12, 0, 0));
    audit("CREATE", "dataset", String.valueOf(v), LocalDateTime.of(2026, 3, 2, 13, 0, 0));
    audit("IMPORT", "pipeline", String.valueOf(v), LocalDateTime.of(2026, 3, 2, 13, 0, 0));
    imported(v, LocalDateTime.of(2026, 2, 28, 23, 59, 59)); // 시작 전
    long b = fx.createDataset(tenantB, fx.datasetCreator(), prefix + "-b", null);
    audit(tenantB, "IMPORT", "dataset", String.valueOf(b), from.plusHours(1), null);

    LocalDate mar1 = LocalDate.of(2026, 3, 1);
    LocalDate mar2 = LocalDate.of(2026, 3, 2);
    assertThat(inA(() -> repository().countImportsByDay(rank2(), from)))
        .isEqualTo(Map.of(mar1, 2, mar2, 3));
    assertThat(inA(() -> repository().countImportsByDay(ALL, from)))
        .isEqualTo(Map.of(mar1, 2, mar2, 4));
    assertThat(inA(() -> repository().countImportsByDay(none(), from))).isEqualTo(Map.of(mar2, 2));
    assertThat(inB(() -> repository().countImportsByDay(ALL, from))).isEqualTo(Map.of(mar1, 1));
  }

  // ---- 활동 피드 감사 이벤트 ----

  @Test
  @DisplayName("피드 감사 이벤트는 데이터셋 임포트·생성만 최신순(같으면 id 내림차순)으로, 안 보이는 데이터셋은 빼고 데이터셋 없는 이력은 이름 없이 남긴다")
  void datasetAuditActivities() {
    long v = dataset("-v", false);
    long h = dataset("-h", true);
    long vImport =
        audit(tenantA, "IMPORT", "dataset", String.valueOf(v), BASE, "{\"fileName\":\"x.csv\"}");
    long vCreate = audit("CREATE", "dataset", String.valueOf(v), BASE); // 같은 시각 → id 큰 것이 먼저
    long hImport = imported(h, BASE.plusHours(1));
    long orphan = audit("CREATE", "dataset", "999999999999", BASE.plusHours(2));
    long nonNumeric = audit("IMPORT", "dataset", "abc", BASE.plusHours(3));
    audit("UPDATE", "dataset", String.valueOf(v), BASE.plusHours(4)); // 임포트·생성 아님
    audit("CREATE", "dashboard", String.valueOf(v), BASE.plusHours(4)); // 데이터셋 아님(WD-464)
    long b = fx.createDataset(tenantB, fx.datasetCreator(), prefix + "-b", null);
    audit(tenantB, "IMPORT", "dataset", String.valueOf(b), BASE.plusHours(9), null);

    List<AuditActivityRow> visible = inA(() -> repository().findDatasetAuditActivities(rank2()));
    assertThat(visible)
        .extracting(AuditActivityRow::id)
        .containsExactly(nonNumeric, orphan, vCreate, vImport);
    assertThat(visible.get(2))
        .isEqualTo(
            new AuditActivityRow(
                vCreate,
                "CREATE",
                "dataset",
                String.valueOf(v),
                "SUCCESS",
                BASE,
                "desc-CREATE",
                prefix + "-v",
                v));
    assertThat(visible.get(1))
        .isEqualTo(
            new AuditActivityRow(
                orphan,
                "CREATE",
                "dataset",
                "999999999999",
                "SUCCESS",
                BASE.plusHours(2),
                "desc-CREATE",
                null,
                null));
    assertThat(visible.get(0).datasetId()).isNull();

    assertThat(inA(() -> repository().findDatasetAuditActivities(ALL)))
        .extracting(AuditActivityRow::id)
        .containsExactly(nonNumeric, orphan, hImport, vCreate, vImport);
    assertThat(inA(() -> repository().findDatasetAuditActivities(none())))
        .extracting(AuditActivityRow::id)
        .containsExactly(nonNumeric, orphan);
    assertThat(inB(() -> repository().findDatasetAuditActivities(ALL)))
        .extracting(AuditActivityRow::datasetName)
        .containsExactly(prefix + "-b");
  }
}
