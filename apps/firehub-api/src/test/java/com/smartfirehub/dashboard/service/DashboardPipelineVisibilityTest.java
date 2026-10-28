package com.smartfirehub.dashboard.service;

import static com.smartfirehub.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dashboard.dto.ActivityFeedResponse;
import com.smartfirehub.dashboard.dto.ActivityFeedResponse.ActivityItem;
import com.smartfirehub.dashboard.dto.AttentionItemResponse;
import com.smartfirehub.dashboard.dto.DashboardStatsResponse;
import com.smartfirehub.dashboard.dto.SystemHealthResponse.PipelineHealth;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.time.LocalDateTime;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

/**
 * 홈 대시보드의 파이프라인 가시성(WD-272) — 파이프라인 조회 권한(pipeline:read)이 없는 조회자에게 홈 API 4개가 파이프라인 이름·실행 결과·개수를 싣지
 * 않는지 검증한다. 파이프라인 목록·실행 이력 API 는 같은 사람에게 403 이므로 홈도 같은 정보를 보여 주면 안 된다.
 *
 * <p>데이터셋 항목은 권한과 무관하게 그대로 실리는지, 권한이 있으면 파이프라인이 예전처럼 실리는지, 비요청 경로(Clearance 형태 — proactive 리포트)는
 * 바뀌지 않았는지도 함께 본다. 각 테스트는 @Transactional 로 롤백된다.
 */
@Transactional
class DashboardPipelineVisibilityTest extends IntegrationTestBase {

  @Autowired private DashboardService dashboardService;

  @Autowired private DSLContext dsl;

  @Autowired private ClearanceResolver clearanceResolver;

  private Long userId;
  private String pipelineName;
  private String datasetName;

  /** 실패한 활성 파이프라인 하나(이름이 홈 곳곳에 실린다)와 임포트 이력이 있는 데이터셋 하나를 만든다. */
  @BeforeEach
  void setUp() {
    // 공유 테스트 DB 에 다른 테스트가 커밋한 실행·감사 이력이 섞이지 않게 비운다(롤백되므로 영속 데이터엔 영향 없음).
    dsl.deleteFrom(PIPELINE_STEP_EXECUTION).execute();
    dsl.deleteFrom(TRIGGER_EVENT).execute();
    dsl.deleteFrom(PIPELINE_EXECUTION).execute();
    dsl.deleteFrom(AUDIT_LOG).execute();

    long nano = System.nanoTime();
    userId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "wd272_" + nano)
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "WD-272 User")
            .set(USER.EMAIL, "wd272_" + nano + "@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();
    // 기본 USER 역할 멤버 — 데이터셋 가시성(보안 등급)은 이 자격으로 판정된다
    TenantRlsTestSupport.insertActiveMembership(dsl, userId, DEFAULT_TEST_TENANT_ID);
    dsl.execute(
        "insert into user_role (user_id, role_id) select ?, id from role where name = 'USER'",
        userId);

    pipelineName = "인사 급여 비밀 집계 파이프라인 " + nano;
    Long pipelineId =
        dsl.insertInto(PIPELINE)
            .set(PIPELINE.NAME, pipelineName)
            .set(PIPELINE.IS_ACTIVE, true)
            .set(PIPELINE.CREATED_BY, userId)
            .returning(PIPELINE.ID)
            .fetchOne()
            .getId();
    dsl.insertInto(PIPELINE_EXECUTION)
        .set(PIPELINE_EXECUTION.PIPELINE_ID, pipelineId)
        .set(PIPELINE_EXECUTION.STATUS, "FAILED")
        .set(PIPELINE_EXECUTION.EXECUTED_BY, userId)
        .set(PIPELINE_EXECUTION.CREATED_AT, LocalDateTime.now().minusMinutes(10))
        .set(PIPELINE_EXECUTION.STARTED_AT, LocalDateTime.now().minusMinutes(10))
        .execute();

    datasetName = "WD-272 데이터셋 " + nano;
    Long datasetId =
        dsl.insertInto(DATASET)
            .set(DATASET.NAME, datasetName)
            .set(DATASET.TABLE_NAME, "wd272_ds_" + nano)
            .set(DATASET.STORAGE_TYPE, "TABLE")
            .set(DATASET.ORIGIN_TYPE, "SOURCE")
            .set(DATASET.CREATED_BY, userId)
            .returning(DATASET.ID)
            .fetchOne()
            .getId();
    dsl.insertInto(AUDIT_LOG)
        .set(AUDIT_LOG.USER_ID, userId)
        .set(AUDIT_LOG.USERNAME, "wd272")
        .set(AUDIT_LOG.ACTION_TYPE, "IMPORT")
        .set(AUDIT_LOG.RESOURCE, "dataset")
        .set(AUDIT_LOG.RESOURCE_ID, datasetId.toString())
        .set(AUDIT_LOG.RESULT, "SUCCESS")
        .set(AUDIT_LOG.ACTION_TIME, LocalDateTime.now().minusMinutes(5))
        .execute();
  }

  @AfterEach
  void clearAuth() {
    SecurityContextHolder.clearContext();
  }

  /** 요청 사용자를 주어진 권한 목록으로 인증 주체에 세운다 — PermissionInterceptor 가 보는 것과 같은 출처다. */
  private void authenticateWith(String... permissions) {
    List<GrantedAuthority> authorities =
        java.util.Arrays.stream(permissions)
            .<GrantedAuthority>map(SimpleGrantedAuthority::new)
            .toList();
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(userId, null, authorities));
  }

  /** 통계: 권한이 없으면 파이프라인 개수 0·최근 실행 없음, 데이터셋 개수·최근 임포트는 그대로. */
  @Test
  void stats_withoutPipelineRead_hidesPipelines() {
    authenticateWith("dataset:read");

    DashboardStatsResponse stats = dashboardService.getStats();

    assertThat(stats.totalPipelines()).isZero();
    assertThat(stats.activePipelines()).isZero();
    assertThat(stats.recentExecutions()).isEmpty();
    assertThat(stats.recentImports()).extracting("datasetName").contains(datasetName);
  }

  /** 통계: 권한이 있으면 예전처럼 파이프라인 개수·최근 실행이 실린다. */
  @Test
  void stats_withPipelineRead_includesPipelines() {
    authenticateWith("dataset:read", "pipeline:read");

    DashboardStatsResponse stats = dashboardService.getStats();

    assertThat(stats.totalPipelines()).isGreaterThanOrEqualTo(1);
    assertThat(stats.recentExecutions()).extracting("pipelineName").contains(pipelineName);
  }

  /** 건강 상태: 권한이 없으면 파이프라인 건강도가 모두 0(추이 7일 0), 데이터셋 건강도는 그대로 계산된다. */
  @Test
  void health_withoutPipelineRead_zeroesPipelineHealth() {
    authenticateWith("dataset:read");

    var health = dashboardService.getSystemHealth();

    PipelineHealth ph = health.pipelineHealth();
    assertThat(List.of(ph.total(), ph.healthy(), ph.failing(), ph.running(), ph.disabled()))
        .containsOnly(0);
    assertThat(ph.trend()).hasSize(7).containsOnly(0);
    assertThat(health.datasetHealth().total()).isGreaterThanOrEqualTo(1);
  }

  /** 건강 상태: 권한이 있으면 실패 파이프라인이 집계된다. */
  @Test
  void health_withPipelineRead_countsPipelines() {
    authenticateWith("dataset:read", "pipeline:read");

    PipelineHealth ph = dashboardService.getSystemHealth().pipelineHealth();

    assertThat(ph.total()).isGreaterThanOrEqualTo(1);
    assertThat(ph.failing()).isGreaterThanOrEqualTo(1);
  }

  /** 주의 항목: 권한이 없으면 파이프라인 실패 항목(제목에 이름이 실림)이 없다. */
  @Test
  void attention_withoutPipelineRead_hidesPipelineFailures() {
    authenticateWith("dataset:read");

    List<AttentionItemResponse> items = dashboardService.getAttentionItems();

    assertThat(items).extracting(AttentionItemResponse::type).doesNotContain("PIPELINE_FAILED");
    assertThat(items)
        .extracting(AttentionItemResponse::title)
        .noneMatch(t -> t.contains(pipelineName));
  }

  /** 주의 항목: 권한이 있으면 실패 파이프라인 항목이 실린다. */
  @Test
  void attention_withPipelineRead_includesPipelineFailures() {
    authenticateWith("dataset:read", "pipeline:read");

    List<AttentionItemResponse> items = dashboardService.getAttentionItems();

    assertThat(items)
        .extracting(AttentionItemResponse::title)
        .anyMatch(t -> t.contains(pipelineName));
  }

  /** 활동 피드: 권한이 없으면 파이프라인 이벤트가 없고(「파이프라인」 필터도 빈 결과), 데이터셋 임포트 이벤트는 그대로 실린다. */
  @Test
  void activity_withoutPipelineRead_hidesPipelineEvents() {
    authenticateWith("dataset:read");

    ActivityFeedResponse all = dashboardService.getActivityFeed(null, null, 0, 100);
    ActivityFeedResponse pipelineOnly = dashboardService.getActivityFeed("PIPELINE", null, 0, 100);

    assertThat(all.items()).extracting(ActivityItem::entityType).doesNotContain("PIPELINE");
    assertThat(all.items()).extracting(ActivityItem::title).anyMatch(t -> t.contains(datasetName));
    assertThat(pipelineOnly.items()).isEmpty();
    assertThat(pipelineOnly.totalCount()).isZero();
  }

  /** 활동 피드: 권한이 있으면 파이프라인 이벤트가 실린다. */
  @Test
  void activity_withPipelineRead_includesPipelineEvents() {
    authenticateWith("dataset:read", "pipeline:read");

    ActivityFeedResponse all = dashboardService.getActivityFeed(null, null, 0, 100);

    assertThat(all.items()).extracting(ActivityItem::title).anyMatch(t -> t.contains(pipelineName));
  }

  /** 비요청 경로(Clearance 형태 — proactive 리포트 컨텍스트)는 요청 권한 목록이 없어 예전처럼 파이프라인을 싣는다. */
  @Test
  void clearanceVariant_unchanged_includesPipelines() {
    var viewer = clearanceResolver.resolve(userId);

    assertThat(dashboardService.getStats(viewer).recentExecutions())
        .extracting("pipelineName")
        .contains(pipelineName);
    assertThat(dashboardService.getSystemHealth(viewer).pipelineHealth().total())
        .isGreaterThanOrEqualTo(1);
    assertThat(dashboardService.getAttentionItems(viewer))
        .extracting(AttentionItemResponse::title)
        .anyMatch(t -> t.contains(pipelineName));
    assertThat(dashboardService.getActivityFeed(null, null, 0, 100, viewer).items())
        .extracting(ActivityItem::title)
        .anyMatch(t -> t.contains(pipelineName));
  }
}
