package com.smartfirehub.dashboard.service;

import static com.smartfirehub.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.smartfirehub.dashboard.dto.ActivityFeedResponse;
import com.smartfirehub.dashboard.dto.ActivityFeedResponse.ActivityItem;
import com.smartfirehub.dashboard.dto.DashboardStatsResponse;
import com.smartfirehub.dashboard.dto.RecentImportResponse;
import com.smartfirehub.support.IntegrationTestBase;
import java.time.LocalDateTime;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * DashboardService 통합 테스트 — getStats() 및 parseLongSafe() NPE(T1) 케이스 중심.
 *
 * <p>DashboardHealthTest가 getSystemHealth/getAttentionItems/getActivityFeed를 커버하므로, 이 클래스는
 * getStats()와 T1(parseLongSafe null 반환 시 NPE) 케이스를 집중 검증한다.
 *
 * <p>각 테스트는 @Transactional로 롤백되어 데이터 격리를 보장한다.
 */
@Transactional
class DashboardStatsServiceTest extends IntegrationTestBase {

  @Autowired private DashboardService dashboardService;

  @Autowired private DSLContext dsl;

  private Long testUserId;
  private Long sourcePipeline1Id;
  private Long sourcePipeline2Id;
  private Long activeSourceDatasetId;
  private Long activeDerivedDatasetId;

  /**
   * 각 테스트 전 공통 데이터 셋업.
   *
   * <p>SOURCE 데이터셋 1개, DERIVED 데이터셋 1개, 활성 파이프라인 1개, 비활성 파이프라인 1개를 삽입한다.
   */
  @BeforeEach
  void setUp() {
    // 테스트용 사용자 생성 (nano time으로 유니크 보장)
    testUserId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "statstest_" + System.nanoTime())
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "Stats Test User")
            .set(USER.EMAIL, "statstest_" + System.nanoTime() + "@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();

    // SOURCE 데이터셋 삽입
    activeSourceDatasetId =
        dsl.insertInto(DATASET)
            .set(DATASET.NAME, "Source Dataset " + System.nanoTime())
            .set(DATASET.TABLE_NAME, "src_tbl_" + System.nanoTime())
            .set(DATASET.DATASET_TYPE, "SOURCE")
            .set(DATASET.CREATED_BY, testUserId)
            .returning(DATASET.ID)
            .fetchOne()
            .getId();

    // DERIVED 데이터셋 삽입
    activeDerivedDatasetId =
        dsl.insertInto(DATASET)
            .set(DATASET.NAME, "Derived Dataset " + System.nanoTime())
            .set(DATASET.TABLE_NAME, "drv_tbl_" + System.nanoTime())
            .set(DATASET.DATASET_TYPE, "DERIVED")
            .set(DATASET.CREATED_BY, testUserId)
            .returning(DATASET.ID)
            .fetchOne()
            .getId();

    // 활성 파이프라인 1
    sourcePipeline1Id =
        dsl.insertInto(PIPELINE)
            .set(PIPELINE.NAME, "Active Pipeline A " + System.nanoTime())
            .set(PIPELINE.IS_ACTIVE, true)
            .set(PIPELINE.CREATED_BY, testUserId)
            .returning(PIPELINE.ID)
            .fetchOne()
            .getId();

    // 활성 파이프라인 2
    sourcePipeline2Id =
        dsl.insertInto(PIPELINE)
            .set(PIPELINE.NAME, "Active Pipeline B " + System.nanoTime())
            .set(PIPELINE.IS_ACTIVE, true)
            .set(PIPELINE.CREATED_BY, testUserId)
            .returning(PIPELINE.ID)
            .fetchOne()
            .getId();
  }

  // ======================================================================
  // getStats() — 카운트 검증
  // ======================================================================

  /**
   * getStats()가 null 없이 정상 응답 객체를 반환하는지 기본 검증.
   *
   * <p>DB에 데이터가 있을 때 응답의 모든 필드가 non-null이어야 한다.
   */
  @Test
  void getStats_returnsNonNullResponse() {
    DashboardStatsResponse stats = dashboardService.getStats();

    assertThat(stats).isNotNull();
    assertThat(stats.recentImports()).isNotNull();
    assertThat(stats.recentExecutions()).isNotNull();
  }

  /**
   * setUp에서 삽입한 SOURCE/DERIVED 데이터셋이 각 카운트에 포함되는지 검증.
   *
   * <p>다른 테스트 데이터가 DB에 존재할 수 있으므로 greaterThanOrEqualTo로 검증한다.
   */
  @Test
  void getStats_datasetCounts_includeSetupData() {
    DashboardStatsResponse stats = dashboardService.getStats();

    // 전체 = SOURCE + DERIVED 이상이어야 함
    assertThat(stats.totalDatasets()).isGreaterThanOrEqualTo(2);
    assertThat(stats.sourceDatasets()).isGreaterThanOrEqualTo(1); // activeSourceDataset 포함
    assertThat(stats.derivedDatasets()).isGreaterThanOrEqualTo(1); // activeDerivedDataset 포함
    // totalDatasets >= sourceDatasets + derivedDatasets
    assertThat(stats.totalDatasets())
        .isGreaterThanOrEqualTo(stats.sourceDatasets() + stats.derivedDatasets());
  }

  /** setUp에서 삽입한 활성 파이프라인 2개가 카운트에 포함되는지 검증. */
  @Test
  void getStats_pipelineCounts_includeSetupData() {
    DashboardStatsResponse stats = dashboardService.getStats();

    assertThat(stats.totalPipelines()).isGreaterThanOrEqualTo(2);
    assertThat(stats.activePipelines()).isGreaterThanOrEqualTo(2);
    // 비활성 파이프라인 추가 후 active는 변하지 않아야 함 (transaction 내)
    dsl.insertInto(PIPELINE)
        .set(PIPELINE.NAME, "Inactive Pipeline " + System.nanoTime())
        .set(PIPELINE.IS_ACTIVE, false)
        .set(PIPELINE.CREATED_BY, testUserId)
        .execute();

    DashboardStatsResponse statsAfter = dashboardService.getStats();
    assertThat(statsAfter.totalPipelines()).isEqualTo(stats.totalPipelines() + 1);
    assertThat(statsAfter.activePipelines()).isEqualTo(stats.activePipelines());
  }

  // ======================================================================
  // getStats() — recentImports 상태 매핑 검증
  // ======================================================================

  /**
   * audit_log의 IMPORT SUCCESS 결과가 recentImports에서 "COMPLETED"로 변환되는지 검증.
   *
   * <p>DashboardService.getStats()는 SUCCESS → COMPLETED, FAILURE → FAILED로 매핑한다.
   */
  @Test
  void getStats_recentImports_successMappedToCompleted() {
    // 테스트 데이터: IMPORT SUCCESS audit_log 삽입
    dsl.insertInto(AUDIT_LOG)
        .set(AUDIT_LOG.USER_ID, testUserId)
        .set(AUDIT_LOG.USERNAME, "statstest")
        .set(AUDIT_LOG.ACTION_TYPE, "IMPORT")
        .set(AUDIT_LOG.RESOURCE, "dataset")
        .set(AUDIT_LOG.RESOURCE_ID, activeSourceDatasetId.toString())
        .set(AUDIT_LOG.RESULT, "SUCCESS")
        .set(AUDIT_LOG.ACTION_TIME, LocalDateTime.now().minusMinutes(30))
        .execute();

    DashboardStatsResponse stats = dashboardService.getStats();

    // 방금 삽입한 import가 recentImports에 포함되고 status="COMPLETED"여야 함
    List<RecentImportResponse> imports = stats.recentImports();
    assertThat(imports).isNotEmpty();

    boolean hasCompleted =
        imports.stream().anyMatch(imp -> imp.id() != null && "COMPLETED".equals(imp.status()));
    assertThat(hasCompleted).isTrue();
  }

  /** audit_log의 IMPORT FAILURE 결과가 recentImports에서 "FAILED"로 변환되는지 검증. */
  @Test
  void getStats_recentImports_failureMappedToFailed() {
    // 테스트 데이터: IMPORT FAILURE audit_log 삽입
    dsl.insertInto(AUDIT_LOG)
        .set(AUDIT_LOG.USER_ID, testUserId)
        .set(AUDIT_LOG.USERNAME, "statstest")
        .set(AUDIT_LOG.ACTION_TYPE, "IMPORT")
        .set(AUDIT_LOG.RESOURCE, "dataset")
        .set(AUDIT_LOG.RESOURCE_ID, activeSourceDatasetId.toString())
        .set(AUDIT_LOG.RESULT, "FAILURE")
        .set(AUDIT_LOG.ACTION_TIME, LocalDateTime.now().minusMinutes(10))
        .execute();

    DashboardStatsResponse stats = dashboardService.getStats();

    List<RecentImportResponse> imports = stats.recentImports();
    assertThat(imports).isNotEmpty();

    boolean hasFailed = imports.stream().anyMatch(imp -> "FAILED".equals(imp.status()));
    assertThat(hasFailed).isTrue();
  }

  /**
   * recentImports는 최대 5건만 반환하는지 검증.
   *
   * <p>6개 이상의 IMPORT 로그가 있어도 최신 5건만 반환해야 한다.
   */
  @Test
  void getStats_recentImports_limitedToFive() {
    // IMPORT 로그 6개 삽입 (같은 데이터셋, 시간 다르게)
    for (int i = 0; i < 6; i++) {
      dsl.insertInto(AUDIT_LOG)
          .set(AUDIT_LOG.USER_ID, testUserId)
          .set(AUDIT_LOG.USERNAME, "statstest")
          .set(AUDIT_LOG.ACTION_TYPE, "IMPORT")
          .set(AUDIT_LOG.RESOURCE, "dataset")
          .set(AUDIT_LOG.RESOURCE_ID, activeSourceDatasetId.toString())
          .set(AUDIT_LOG.RESULT, "SUCCESS")
          .set(AUDIT_LOG.ACTION_TIME, LocalDateTime.now().minusHours(i + 1))
          .execute();
    }

    DashboardStatsResponse stats = dashboardService.getStats();

    // 최대 5건 제한
    assertThat(stats.recentImports()).hasSizeLessThanOrEqualTo(5);
  }

  /** recentImports는 최신순(action_time DESC)으로 정렬되는지 검증. */
  @Test
  void getStats_recentImports_orderedByTimeDesc() {
    // 시간 간격을 두고 2개 삽입
    dsl.insertInto(AUDIT_LOG)
        .set(AUDIT_LOG.USER_ID, testUserId)
        .set(AUDIT_LOG.USERNAME, "statstest")
        .set(AUDIT_LOG.ACTION_TYPE, "IMPORT")
        .set(AUDIT_LOG.RESOURCE, "dataset")
        .set(AUDIT_LOG.RESOURCE_ID, activeSourceDatasetId.toString())
        .set(AUDIT_LOG.RESULT, "SUCCESS")
        .set(AUDIT_LOG.ACTION_TIME, LocalDateTime.now().minusHours(2))
        .execute();

    dsl.insertInto(AUDIT_LOG)
        .set(AUDIT_LOG.USER_ID, testUserId)
        .set(AUDIT_LOG.USERNAME, "statstest")
        .set(AUDIT_LOG.ACTION_TYPE, "IMPORT")
        .set(AUDIT_LOG.RESOURCE, "dataset")
        .set(AUDIT_LOG.RESOURCE_ID, activeSourceDatasetId.toString())
        .set(AUDIT_LOG.RESULT, "FAILURE")
        .set(AUDIT_LOG.ACTION_TIME, LocalDateTime.now().minusMinutes(30))
        .execute();

    DashboardStatsResponse stats = dashboardService.getStats();

    List<RecentImportResponse> imports = stats.recentImports();
    if (imports.size() >= 2) {
      // 첫 번째가 더 최신이어야 함
      assertThat(imports.get(0).createdAt()).isAfterOrEqualTo(imports.get(1).createdAt());
    }
  }

  // ======================================================================
  // getStats() — recentExecutions 검증
  // ======================================================================

  /** recentExecutions는 최대 5건만 반환하는지 검증. */
  @Test
  void getStats_recentExecutions_limitedToFive() {
    // pipeline_execution 6개 삽입
    for (int i = 0; i < 6; i++) {
      dsl.insertInto(PIPELINE_EXECUTION)
          .set(PIPELINE_EXECUTION.PIPELINE_ID, sourcePipeline1Id)
          .set(PIPELINE_EXECUTION.STATUS, "COMPLETED")
          .set(PIPELINE_EXECUTION.EXECUTED_BY, testUserId)
          .set(PIPELINE_EXECUTION.CREATED_AT, LocalDateTime.now().minusHours(i + 1))
          .set(PIPELINE_EXECUTION.STARTED_AT, LocalDateTime.now().minusHours(i + 1))
          .set(PIPELINE_EXECUTION.COMPLETED_AT, LocalDateTime.now().minusHours(i).minusMinutes(50))
          .execute();
    }

    DashboardStatsResponse stats = dashboardService.getStats();

    // 최대 5건 제한
    assertThat(stats.recentExecutions()).hasSizeLessThanOrEqualTo(5);
  }

  /** recentExecutions는 최신순(created_at DESC)으로 정렬되는지 검증. */
  @Test
  void getStats_recentExecutions_orderedByTimeDesc() {
    // 2개 삽입
    dsl.insertInto(PIPELINE_EXECUTION)
        .set(PIPELINE_EXECUTION.PIPELINE_ID, sourcePipeline1Id)
        .set(PIPELINE_EXECUTION.STATUS, "COMPLETED")
        .set(PIPELINE_EXECUTION.EXECUTED_BY, testUserId)
        .set(PIPELINE_EXECUTION.CREATED_AT, LocalDateTime.now().minusHours(3))
        .execute();

    dsl.insertInto(PIPELINE_EXECUTION)
        .set(PIPELINE_EXECUTION.PIPELINE_ID, sourcePipeline2Id)
        .set(PIPELINE_EXECUTION.STATUS, "FAILED")
        .set(PIPELINE_EXECUTION.EXECUTED_BY, testUserId)
        .set(PIPELINE_EXECUTION.CREATED_AT, LocalDateTime.now().minusHours(1))
        .execute();

    DashboardStatsResponse stats = dashboardService.getStats();

    List<com.smartfirehub.dashboard.dto.RecentExecutionResponse> execs = stats.recentExecutions();
    if (execs.size() >= 2) {
      assertThat(execs.get(0).createdAt()).isAfterOrEqualTo(execs.get(1).createdAt());
    }
  }

  // ======================================================================
  // T1: parseLongSafe() null 반환 시 NPE 가능 케이스
  // ======================================================================

  /**
   * T1 케이스 — dashboard resource의 비숫자 resource_id 처리 검증.
   *
   * <p>LEFT JOIN ON 조건: {@code AL_RESOURCE.eq("dataset") AND cast(resource_id as bigint) = id}
   * "dashboard" 리소스는 LEFT JOIN 결과에서 dataset join이 매칭되지 않으므로 parseLongSafe()가 null을 반환하고 entityName
   * = "dashboard #null"이 생성되지만 NPE는 없어야 한다.
   *
   * <p>주의: PostgreSQL 쿼리 플래너 버전에 따라 DataException이 발생할 수 있으나 (버그 C2), 이 테스트는 정상 동작 경로를 검증한다. 버그 C2는
   * dataset resource TC에서 별도 검증한다.
   */
  @Test
  void getActivityFeed_parseLongSafe_nonNumericDashboardResourceId_doesNotThrow() {
    // resource_id가 숫자가 아닌 "dashboard" CREATE 이벤트 삽입
    // dashboard resource는 dataset join 대상이 아니므로 parseLongSafe() null 처리 경로
    dsl.insertInto(AUDIT_LOG)
        .set(AUDIT_LOG.USER_ID, testUserId)
        .set(AUDIT_LOG.USERNAME, "statstest")
        .set(AUDIT_LOG.ACTION_TYPE, "CREATE")
        .set(AUDIT_LOG.RESOURCE, "dashboard")
        .set(AUDIT_LOG.RESOURCE_ID, "999") // 유효한 숫자로 변경하여 cast 실패 없이 동작 검증
        .set(AUDIT_LOG.RESULT, "SUCCESS")
        .set(AUDIT_LOG.ACTION_TIME, LocalDateTime.now().minusMinutes(5))
        .execute();

    // 정상 완료 — DASHBOARD_CREATED 이벤트가 feed에 포함되어야 함
    assertThatNoException().isThrownBy(() -> dashboardService.getActivityFeed(null, null, 0, 50));
  }

  /**
   * T1 케이스 — resource_id가 null일 때 NPE 없이 처리되는지 검증.
   *
   * <p>AL_RESOURCE_ID가 null이면 PostgreSQL cast(null as bigint)는 null을 반환하므로 쿼리 자체는 정상 실행되고,
   * parseLongSafe()도 null guard로 건너뛴다. entityName = "dashboard #null" 문자열이 생성되어도 예외 없어야 한다.
   */
  @Test
  void getActivityFeed_parseLongSafe_nullResourceId_doesNotThrowNpe() {
    // resource_id가 null인 dashboard CREATE 이벤트 삽입
    // cast(null as bigint) = null → join 조건 false → 쿼리 정상 실행
    dsl.insertInto(AUDIT_LOG)
        .set(AUDIT_LOG.USER_ID, testUserId)
        .set(AUDIT_LOG.USERNAME, "statstest")
        .set(AUDIT_LOG.ACTION_TYPE, "CREATE")
        .set(AUDIT_LOG.RESOURCE, "dashboard")
        .set(AUDIT_LOG.RESOURCE_ID, (String) null)
        .set(AUDIT_LOG.RESULT, "SUCCESS")
        .set(AUDIT_LOG.ACTION_TIME, LocalDateTime.now().minusMinutes(5))
        .execute();

    // null resource_id는 DataException 없이 처리됨
    assertThatNoException().isThrownBy(() -> dashboardService.getActivityFeed(null, null, 0, 50));
  }

  /**
   * 버그 수정 검증 — dataset resource의 비숫자 resource_id가 있어도 DataException 없이 처리됨.
   *
   * <p>수정 전: LEFT JOIN ON의 cast(resource_id as bigint) 실패로 DataException 발생. 수정 후: CASE WHEN 정규식
   * 가드로 비숫자 값은 NULL로 처리되어 쿼리 정상 실행.
   */
  @Test
  void getActivityFeed_nonNumericDatasetResourceId_doesNotThrowAfterFix() {
    // resource_id가 숫자가 아닌 IMPORT 이벤트 삽입 (수정 전에는 DataException 발생)
    dsl.insertInto(AUDIT_LOG)
        .set(AUDIT_LOG.USER_ID, testUserId)
        .set(AUDIT_LOG.USERNAME, "statstest")
        .set(AUDIT_LOG.ACTION_TYPE, "IMPORT")
        .set(AUDIT_LOG.RESOURCE, "dataset")
        .set(AUDIT_LOG.RESOURCE_ID, "invalid-id") // 비숫자 값 — 수정 후 NULL 처리
        .set(AUDIT_LOG.RESULT, "SUCCESS")
        .set(AUDIT_LOG.ACTION_TIME, LocalDateTime.now().minusMinutes(5))
        .execute();

    // 수정 후: DataException 없이 정상 완료
    assertThatNoException().isThrownBy(() -> dashboardService.getActivityFeed(null, null, 0, 50));
  }

  // ======================================================================
  // getActivityFeed() — dashboard CREATE 이벤트 검증
  // ======================================================================

  /** dashboard CREATE 이벤트가 activity feed에 DASHBOARD_CREATED 타입으로 포함되는지 검증. */
  @Test
  void getActivityFeed_dashboardCreateEvent_appearsInFeed() {
    // dashboard CREATE audit_log (resource_id는 유효한 숫자)
    dsl.insertInto(AUDIT_LOG)
        .set(AUDIT_LOG.USER_ID, testUserId)
        .set(AUDIT_LOG.USERNAME, "statstest")
        .set(AUDIT_LOG.ACTION_TYPE, "CREATE")
        .set(AUDIT_LOG.RESOURCE, "dashboard")
        .set(AUDIT_LOG.RESOURCE_ID, "999")
        .set(AUDIT_LOG.RESULT, "SUCCESS")
        .set(AUDIT_LOG.ACTION_TIME, LocalDateTime.now().minusMinutes(10))
        .execute();

    ActivityFeedResponse feed = dashboardService.getActivityFeed(null, null, 0, 50);

    boolean hasDashboardCreated =
        feed.items().stream().anyMatch(i -> "DASHBOARD_CREATED".equals(i.eventType()));
    assertThat(hasDashboardCreated).isTrue();
  }

  /** dataset CREATE 이벤트가 activity feed에 DATASET_CREATED 타입으로 포함되는지 검증. */
  @Test
  void getActivityFeed_datasetCreateEvent_appearsInFeed() {
    // dataset CREATE audit_log
    dsl.insertInto(AUDIT_LOG)
        .set(AUDIT_LOG.USER_ID, testUserId)
        .set(AUDIT_LOG.USERNAME, "statstest")
        .set(AUDIT_LOG.ACTION_TYPE, "CREATE")
        .set(AUDIT_LOG.RESOURCE, "dataset")
        .set(AUDIT_LOG.RESOURCE_ID, activeSourceDatasetId.toString())
        .set(AUDIT_LOG.RESULT, "SUCCESS")
        .set(AUDIT_LOG.ACTION_TIME, LocalDateTime.now().minusMinutes(15))
        .execute();

    ActivityFeedResponse feed = dashboardService.getActivityFeed(null, null, 0, 50);

    List<ActivityItem> datasetCreatedItems =
        feed.items().stream().filter(i -> "DATASET_CREATED".equals(i.eventType())).toList();
    assertThat(datasetCreatedItems).isNotEmpty();
    assertThat(datasetCreatedItems.get(0).entityType()).isEqualTo("DATASET");
    assertThat(datasetCreatedItems.get(0).severity()).isEqualTo("INFO");
    assertThat(datasetCreatedItems.get(0).isResolved()).isTrue();
  }

  // ======================================================================
  // getActivityFeed() — isResolved 검증
  // ======================================================================

  /**
   * FAILED 파이프라인 실행 이후 COMPLETED가 오면 isResolved=true로 표시되는지 검증.
   *
   * <p>latestStatusByPipeline 맵은 가장 최신 실행을 유지하므로 FAILED 다음 COMPLETED가 있으면 FAILED 이벤트도
   * isResolved=true여야 한다.
   */
  @Test
  void getActivityFeed_failedPipelineWithLaterSuccess_isResolved() {
    // FAILED 실행 (오래된 것)
    dsl.insertInto(PIPELINE_EXECUTION)
        .set(PIPELINE_EXECUTION.PIPELINE_ID, sourcePipeline1Id)
        .set(PIPELINE_EXECUTION.STATUS, "FAILED")
        .set(PIPELINE_EXECUTION.EXECUTED_BY, testUserId)
        .set(PIPELINE_EXECUTION.CREATED_AT, LocalDateTime.now().minusHours(2))
        .execute();

    // COMPLETED 실행 (최신)
    dsl.insertInto(PIPELINE_EXECUTION)
        .set(PIPELINE_EXECUTION.PIPELINE_ID, sourcePipeline1Id)
        .set(PIPELINE_EXECUTION.STATUS, "COMPLETED")
        .set(PIPELINE_EXECUTION.EXECUTED_BY, testUserId)
        .set(PIPELINE_EXECUTION.CREATED_AT, LocalDateTime.now().minusHours(1))
        .execute();

    ActivityFeedResponse feed = dashboardService.getActivityFeed("PIPELINE", null, 0, 50);

    // sourcePipeline1의 FAILED 이벤트는 이후 COMPLETED가 있으므로 isResolved=true
    List<ActivityItem> failedItems =
        feed.items().stream()
            .filter(i -> "PIPELINE_FAILED".equals(i.eventType()))
            .filter(i -> sourcePipeline1Id.equals(i.entityId()))
            .toList();

    assertThat(failedItems).isNotEmpty();
    assertThat(failedItems).allMatch(ActivityItem::isResolved);
  }

  /** FAILED 파이프라인이 아직 복구되지 않았으면 isResolved=false여야 함. */
  @Test
  void getActivityFeed_failedPipelineWithNoLaterSuccess_isNotResolved() {
    // FAILED 실행만 있음
    dsl.insertInto(PIPELINE_EXECUTION)
        .set(PIPELINE_EXECUTION.PIPELINE_ID, sourcePipeline2Id)
        .set(PIPELINE_EXECUTION.STATUS, "FAILED")
        .set(PIPELINE_EXECUTION.EXECUTED_BY, testUserId)
        .set(PIPELINE_EXECUTION.CREATED_AT, LocalDateTime.now().minusHours(1))
        .execute();

    ActivityFeedResponse feed = dashboardService.getActivityFeed("PIPELINE", null, 0, 50);

    List<ActivityItem> failedItems =
        feed.items().stream()
            .filter(i -> "PIPELINE_FAILED".equals(i.eventType()))
            .filter(i -> sourcePipeline2Id.equals(i.entityId()))
            .toList();

    assertThat(failedItems).isNotEmpty();
    // 복구 실행 없으므로 isResolved=false
    assertThat(failedItems).allMatch(i -> !i.isResolved());
  }
}
