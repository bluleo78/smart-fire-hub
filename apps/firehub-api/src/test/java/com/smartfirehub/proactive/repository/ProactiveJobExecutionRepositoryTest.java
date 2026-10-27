package com.smartfirehub.proactive.repository;

import static com.smartfirehub.jooq.Tables.USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.proactive.dto.ProactiveJobExecutionSummaryResponse;
import com.smartfirehub.proactive.dto.ReportListItemResponse;
import com.smartfirehub.proactive.service.ProactiveExecutionRegistry;
import com.smartfirehub.proactive.service.ProactiveExecutionSweeper;
import com.smartfirehub.proactive.service.ProactiveHeartbeatService;
import com.smartfirehub.support.IntegrationTestBase;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * ProactiveJobExecutionRepository.findReportsByUserId 통합 테스트.
 *
 * <p>"리포트"의 정의(COMPLETED + htmlContent 존재)와 사용자 소유권 스코핑이 쿼리 수준에서 지켜지는지 검증한다.
 */
@Transactional
class ProactiveJobExecutionRepositoryTest extends IntegrationTestBase {

  @Autowired private ProactiveJobExecutionRepository repository;
  @Autowired private ProactiveJobRepository jobRepository;
  @Autowired private ProactiveExecutionSweeper sweeper;
  @Autowired private ProactiveExecutionRegistry executionRegistry;
  @Autowired private ProactiveHeartbeatService heartbeatService;
  @Autowired private DSLContext dsl;

  private Long userId;
  private Long otherUserId;

  @BeforeEach
  void setUp() {
    userId = createUser("pjerepo");
    otherUserId = createUser("pjerepo_other");
  }

  private Long createUser(String prefix) {
    String unique = prefix + "_" + System.nanoTime();
    return dsl.insertInto(USER)
        .set(USER.USERNAME, unique)
        .set(USER.PASSWORD, "password")
        .set(USER.NAME, "PJE Repo Tester")
        .set(USER.EMAIL, unique + "@example.com")
        .returning(USER.ID)
        .fetchOne()
        .getId();
  }

  private Long createJob(Long ownerId, String name) {
    return jobRepository.create(
        ownerId, name, "prompt", null, "0 0 9 * * *", "Asia/Seoul", true, null, Map.of());
  }

  /** COMPLETED + htmlContent 있는 실행 = 리포트 */
  private Long createReport(Long jobId, String title, String summary) {
    Long execId = repository.create(jobId);
    repository.updateResult(
        execId,
        "COMPLETED",
        Map.of("title", title, "summary", summary, "htmlContent", "<h1>body</h1>"),
        LocalDateTime.now());
    return execId;
  }

  @Test
  void findReportsByUserId_returnsCompletedExecutionsWithHtmlContent() {
    Long jobId = createJob(userId, "월간 화재 통계");
    createReport(jobId, "8월 리포트", "요약문");

    List<ReportListItemResponse> reports = repository.findReportsByUserId(userId, 20, 0);

    assertThat(reports).hasSize(1);
    assertThat(reports.get(0).title()).isEqualTo("8월 리포트");
    assertThat(reports.get(0).summary()).isEqualTo("요약문");
    assertThat(reports.get(0).jobName()).isEqualTo("월간 화재 통계");
    assertThat(reports.get(0).jobId()).isEqualTo(jobId);
  }

  @Test
  void findReportsByUserId_excludesExecutionsWithoutHtmlContent() {
    Long jobId = createJob(userId, "구버전 잡");
    Long execId = repository.create(jobId);
    // htmlContent 없는 구버전 sections 기반 결과 — 뷰어가 404를 내므로 목록에서 제외되어야 한다
    repository.updateResult(
        execId, "COMPLETED", Map.of("title", "구버전", "sections", List.of()), LocalDateTime.now());

    assertThat(repository.findReportsByUserId(userId, 20, 0)).isEmpty();
  }

  @Test
  void findReportsByUserId_excludesBlankHtmlContent() {
    Long jobId = createJob(userId, "빈 본문 잡");
    Long execId = repository.create(jobId);
    repository.updateResult(
        execId, "COMPLETED", Map.of("title", "빈 본문", "htmlContent", ""), LocalDateTime.now());

    assertThat(repository.findReportsByUserId(userId, 20, 0)).isEmpty();
  }

  @Test
  void findReportsByUserId_excludesFailedExecutions() {
    Long jobId = createJob(userId, "실패 잡");
    Long execId = repository.create(jobId);
    repository.updateResult(
        execId, "FAILED", Map.of("htmlContent", "<h1>x</h1>"), LocalDateTime.now());

    assertThat(repository.findReportsByUserId(userId, 20, 0)).isEmpty();
  }

  @Test
  void findReportsByUserId_excludesOtherUsersReports() {
    Long otherJobId = createJob(otherUserId, "남의 잡");
    createReport(otherJobId, "남의 리포트", "남의 요약");

    assertThat(repository.findReportsByUserId(userId, 20, 0)).isEmpty();
  }

  @Test
  void findReportsByUserId_fallsBackToJobNameWhenTitleBlank() {
    Long jobId = createJob(userId, "제목없는잡");
    Long execId = repository.create(jobId);
    repository.updateResult(
        execId, "COMPLETED", Map.of("title", "  ", "htmlContent", "<p>x</p>"), LocalDateTime.now());

    List<ReportListItemResponse> reports = repository.findReportsByUserId(userId, 20, 0);

    assertThat(reports).hasSize(1);
    assertThat(reports.get(0).title()).isEqualTo("제목없는잡");
  }

  @Test
  void findReportsByUserId_appliesLimitAndOffset() {
    Long jobId = createJob(userId, "다건 잡");
    createReport(jobId, "첫번째", "s1");
    createReport(jobId, "두번째", "s2");
    createReport(jobId, "세번째", "s3");

    assertThat(repository.findReportsByUserId(userId, 2, 0)).hasSize(2);
    assertThat(repository.findReportsByUserId(userId, 2, 2)).hasSize(1);
  }

  /**
   * findSummariesByJobId 통합 테스트 (#604).
   *
   * <p>목록 endpoint 전용 경량 뷰가 리포트 본문(result)을 아예 담지 않으면서도 상태/시간 등 요약 정보와 limit/offset 페이징은
   * findByJobId와 동일하게 동작하는지 검증한다.
   */
  @Test
  void findSummariesByJobId_excludesResultButKeepsSummaryFields() {
    Long jobId = createJob(userId, "요약 뷰 잡");
    Long execId = createReport(jobId, "리포트 제목", "리포트 요약");
    repository.updateDeliveredChannels(execId, List.of("CHAT", "EMAIL"));

    List<ProactiveJobExecutionSummaryResponse> summaries =
        repository.findSummariesByJobId(jobId, 20, 0);

    assertThat(summaries).hasSize(1);
    ProactiveJobExecutionSummaryResponse summary = summaries.get(0);
    assertThat(summary.id()).isEqualTo(execId);
    assertThat(summary.jobId()).isEqualTo(jobId);
    assertThat(summary.status()).isEqualTo("COMPLETED");
    assertThat(summary.deliveredChannels()).containsExactly("CHAT", "EMAIL");
    // ProactiveJobExecutionSummaryResponse에는 result 필드 자체가 없다 — 리포트 본문 미포함을 타입 레벨로 보장
  }

  @Test
  void findSummariesByJobId_appliesLimitAndOffsetOrderedByIdDesc() {
    Long jobId = createJob(userId, "요약 페이징 잡");
    Long first = createReport(jobId, "첫번째", "s1");
    Long second = createReport(jobId, "두번째", "s2");
    Long third = createReport(jobId, "세번째", "s3");

    List<ProactiveJobExecutionSummaryResponse> page1 = repository.findSummariesByJobId(jobId, 2, 0);
    List<ProactiveJobExecutionSummaryResponse> page2 = repository.findSummariesByJobId(jobId, 2, 2);

    assertThat(page1)
        .extracting(ProactiveJobExecutionSummaryResponse::id)
        .containsExactly(third, second);
    assertThat(page2).extracting(ProactiveJobExecutionSummaryResponse::id).containsExactly(first);
  }

  /** 정리기가 먼저 닫은 실행을 늦게 돌아온 러너의 성공 결과가 되살리면 안 된다(WD-258). */
  @Test
  void updateResult_doesNotOverwriteInterruptedExecution() {
    Long executionId = repository.create(createJob(userId, "중단된 잡"));
    repository.updateError(executionId, "api 가 재시작되어 실행이 중단되었습니다");

    repository.updateResult(
        executionId, "COMPLETED", Map.of("htmlContent", "late"), LocalDateTime.now());

    var execution = repository.findById(executionId).orElseThrow();
    assertThat(execution.status()).isEqualTo("FAILED");
    assertThat(execution.errorMessage()).isEqualTo("api 가 재시작되어 실행이 중단되었습니다");
    assertThat(execution.result()).isNull();
  }

  /** 성공으로 닫힌 실행을 늦은 실패 쓰기가 덮으면 목록과 보고서가 사라진다(WD-258). */
  @Test
  void updateError_doesNotOverwriteCompletedExecution() {
    Long executionId = createReport(createJob(userId, "완료된 잡"), "제목", "요약");

    repository.updateError(executionId, "늦은 오류");

    var execution = repository.findById(executionId).orElseThrow();
    assertThat(execution.status()).isEqualTo("COMPLETED");
    assertThat(execution.errorMessage()).isNull();
    assertThat(execution.result()).containsEntry("htmlContent", "<h1>body</h1>");
  }

  /** 생성 직후 정리된 행을 러너의 늦은 RUNNING 전환이 되살리지 않는다. */
  @Test
  void updateStatus_doesNotRestartInterruptedExecution() {
    Long executionId = repository.create(createJob(userId, "시작 전 중단 잡"));
    repository.updateError(executionId, "중단됨");

    assertThat(repository.updateStatus(executionId, "RUNNING", LocalDateTime.now(), null))
        .isFalse();
    assertThat(repository.findById(executionId).orElseThrow().status()).isEqualTo("FAILED");
  }

  /** 하트비트가 오래됐고 아직 활성인 행만 찾는다. 시작 시각보다 최신인 하트비트가 우선한다. */
  @Test
  void findStaleExecutions_usesHeartbeatAndActiveStatus() {
    Long jobId = createJob(userId, "하트비트 잡");
    Long stale = repository.create(jobId);
    Long live = repository.create(jobId);
    Long finished = repository.create(jobId);
    LocalDateTime old = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(30);
    LocalDateTime recent = LocalDateTime.now(ZoneOffset.UTC);
    var heartbeat = field(name("heartbeat_at"), LocalDateTime.class);
    var started = field(name("started_at"), LocalDateTime.class);
    var id = field(name("id"), Long.class);
    dsl.update(table(name("proactive_job_execution")))
        .set(heartbeat, old)
        .set(started, old)
        .where(id.in(stale, live, finished))
        .execute();
    dsl.update(table(name("proactive_job_execution")))
        .set(heartbeat, recent)
        .where(id.eq(live))
        .execute();
    repository.updateError(finished, "완료됨");

    assertThat(repository.findStaleExecutions(DEFAULT_TEST_TENANT_ID, recent.minusMinutes(5)))
        .containsExactly(stale);
  }

  /** 하트비트는 완료 행을 건드리지 않아야 정리된 실행을 다시 활성으로 오인하지 않는다. */
  @Test
  void touchHeartbeat_updatesActiveOnly() {
    Long jobId = createJob(userId, "하트비트 갱신 잡");
    Long active = repository.create(jobId);
    Long finished = repository.create(jobId);
    repository.updateError(finished, "끝남");
    LocalDateTime beatAt = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(5);

    assertThat(repository.touchHeartbeat(List.of(active, finished), beatAt)).isEqualTo(1);

    var heartbeat = field(name("heartbeat_at"), LocalDateTime.class);
    var id = field(name("id"), Long.class);
    var executions = table(name("proactive_job_execution"));
    assertThat(dsl.select(heartbeat).from(executions).where(id.eq(active)).fetchOne(heartbeat))
        .isEqualTo(beatAt);
    assertThat(dsl.select(heartbeat).from(executions).where(id.eq(finished)).fetchOne(heartbeat))
        .isNotEqualTo(beatAt);
  }

  /** 오래된 실행이 실제 저장소·정리기를 거쳐 FAILED 로 바뀌고 늦은 결과 쓰기를 거부한다. */
  @Test
  void sweeper_closesOrphanAndRejectsLateResult() {
    Long executionId = repository.create(createJob(userId, "고아 실행 잡"));
    dsl.update(table(name("proactive_job_execution")))
        .set(
            field(name("heartbeat_at"), LocalDateTime.class),
            LocalDateTime.now(ZoneOffset.UTC).minusMinutes(30))
        .where(field(name("id"), Long.class).eq(executionId))
        .execute();

    assertThat(sweeper.sweepTenant(DEFAULT_TEST_TENANT_ID)).isGreaterThanOrEqualTo(1);

    var execution = repository.findById(executionId).orElseThrow();
    assertThat(execution.status()).isEqualTo("FAILED");
    assertThat(execution.errorMessage()).isEqualTo("api 가 재시작되어 실행이 중단되었습니다");
    assertThat(
            repository.updateResult(
                executionId,
                "COMPLETED",
                Map.of("htmlContent", "late"),
                LocalDateTime.now(ZoneOffset.UTC)))
        .isFalse();
    assertThat(repository.findById(executionId).orElseThrow().status()).isEqualTo("FAILED");
  }

  /** 테스트용: 실행 행의 하트비트 시각을 직접 바꾼다(null 이면 비움). */
  private void setHeartbeat(Long executionId, LocalDateTime at) {
    dsl.update(table(name("proactive_job_execution")))
        .set(field(name("heartbeat_at"), LocalDateTime.class), at)
        .where(field(name("id"), Long.class).eq(executionId))
        .execute();
  }

  private LocalDateTime heartbeatOf(Long executionId) {
    var heartbeat = field(name("heartbeat_at"), LocalDateTime.class);
    return dsl.select(heartbeat)
        .from(table(name("proactive_job_execution")))
        .where(field(name("id"), Long.class).eq(executionId))
        .fetchOne(heartbeat);
  }

  /**
   * 리뷰 I-6(a): DB 하트비트가 오래됐어도 이 JVM 등록부에 있는 실행(AI 호출에 막힌 워커)은 실제 저장소·정리기를 거쳐도 RUNNING 으로 남는다. 등록부는
   * 싱글턴 빈이라 finally 에서 반드시 해제한다(다른 테스트로 새지 않게).
   */
  @Test
  void sweeper_keepsRegisteredExecutionRunningEvenIfHeartbeatStale() {
    Long executionId = repository.create(createJob(userId, "막힌 워커 잡"));
    repository.updateStatus(executionId, "RUNNING", LocalDateTime.now(ZoneOffset.UTC), null);
    setHeartbeat(executionId, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(30));
    executionRegistry.register(executionId, DEFAULT_TEST_TENANT_ID);
    try {
      sweeper.sweepTenant(DEFAULT_TEST_TENANT_ID);
    } finally {
      executionRegistry.unregister(executionId);
    }

    var execution = repository.findById(executionId).orElseThrow();
    assertThat(execution.status()).isEqualTo("RUNNING");
    assertThat(execution.errorMessage()).isNull();
  }

  /**
   * 리뷰 I-6(b): 실제 하트비트 서비스를 실제 DB 로 돌리면 등록된 실행의 오래된 {@code heartbeat_at} 이 갱신되어 오래된 실행 조회에서 빠진다 —
   * 등록부가 비어도(다른 JVM 이 정리하는 경우) 정리되지 않는다.
   */
  @Test
  void heartbeatService_refreshesStaleHeartbeat_soExecutionLeavesStaleQuery() {
    Long executionId = repository.create(createJob(userId, "하트비트 서비스 잡"));
    LocalDateTime old = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(30);
    setHeartbeat(executionId, old);
    LocalDateTime threshold = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5);
    assertThat(repository.findStaleExecutions(DEFAULT_TEST_TENANT_ID, threshold))
        .contains(executionId);

    executionRegistry.register(executionId, DEFAULT_TEST_TENANT_ID);
    try {
      heartbeatService.beat();
    } finally {
      executionRegistry.unregister(executionId);
      // 하트비트 루프가 테넌트 컨텍스트를 바꿨을 수 있어 이어지는 조회를 위해 다시 세운다.
      TenantContext.set(DEFAULT_TEST_TENANT_ID);
    }

    assertThat(heartbeatOf(executionId)).isAfter(old.plusMinutes(20));
    assertThat(repository.findStaleExecutions(DEFAULT_TEST_TENANT_ID, threshold))
        .doesNotContain(executionId);
  }

  /**
   * WD-258 simplify(M): 정리기가 도는 테넌트 목록은 V149 definer 함수가 실행 표에서 유도한다 — 활성 실행이 있으면 그 테넌트가 나온다. 런타임
   * 계정(app_tenant)에 EXECUTE 가 있고 RLS 를 넘어 정수 목록만 돌려주는지, 활성 행 부분 색인이 만들어졌는지도 함께 본다.
   */
  @Test
  void tenantIdsWithActiveExecutions_listsTenantOfActiveExecution() {
    Long jobId = createJob(userId, "활성 테넌트 잡");
    repository.create(jobId);

    assertThat(repository.tenantIdsWithActiveExecutions()).contains(DEFAULT_TEST_TENANT_ID);
    assertThat(
            dsl.fetch(
                "select 1 from pg_indexes where indexname ="
                    + " 'idx_proactive_execution_active_tenant'"))
        .hasSize(1);
  }

  /**
   * 리뷰 I-6(c): V149 백필은 활성(PENDING·RUNNING) 행만 UTC 현재 시각으로 채우고, 끝난 행은 비워 둔다. Flyway 가 부팅 때 이미
   * 적용했으므로 백필 문장(UPDATE)만 마이그레이션 파일에서 떼어 이 테스트 트랜잭션(테넌트 RLS) 안에서 다시 실행한다 — ALTER 는 런타임 계정이 소유자가 아니라
   * 재생하지 않는다.
   */
  @Test
  void v149Backfill_fillsActiveRowsWithUtcNowOnly() throws Exception {
    Long jobId = createJob(userId, "백필 잡");
    Long pending = repository.create(jobId);
    Long running = repository.create(jobId);
    repository.updateStatus(running, "RUNNING", LocalDateTime.now(ZoneOffset.UTC), null);
    Long completed = createReport(jobId, "완료", "요약");
    for (Long id : List.of(pending, running, completed)) {
      setHeartbeat(id, null);
    }

    String migration;
    try (var in =
        getClass().getResourceAsStream("/db/migration/V149__proactive_execution_heartbeat.sql")) {
      assertThat(in).as("V149 마이그레이션 파일").isNotNull();
      migration = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    String backfill =
        migration.substring(migration.indexOf("UPDATE proactive_job_execution")).trim();
    // NOW() 는 테스트 트랜잭션 시작 시각이라 여유를 1분 둔다(시간대 어긋남은 시간 단위라 그래도 잡힌다).
    LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1);
    dsl.execute(backfill.endsWith(";") ? backfill.substring(0, backfill.length() - 1) : backfill);
    LocalDateTime after = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(5);

    for (Long id : List.of(pending, running)) {
      // UTC 로 채워졌는지 — 세션 시간대(예: Asia/Seoul)였다면 9시간 어긋나 이 창을 벗어난다.
      assertThat(heartbeatOf(id)).isBetween(before, after);
    }
    assertThat(heartbeatOf(completed)).isNull();
  }
}
