package com.smartfirehub.proactive.service;

import static com.smartfirehub.jooq.Tables.USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.proactive.repository.ProactiveJobExecutionRepository;
import com.smartfirehub.proactive.repository.ProactiveJobRepository;
import com.smartfirehub.support.IntegrationTestBase;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * 선제 실행 정리기·하트비트 서비스·V149 마이그레이션의 PG 통합 테스트(WD-258).
 *
 * <p>왜 계약 테스트에서 떼어 냈나(WD-709): 이 시험들은 실제 서비스 빈(정리기·등록부·하트비트)과 PG 마이그레이션 파일·{@code pg_indexes} 를
 * 거친다. 서비스 빈은 PG 테스트 프로필에서 운영 빈(jOOQ 구현)을 주입받으므로, 리포지토리 계약 테스트의 MyBatis 하위 클래스에 두면 MyBatis 를 전혀 보지
 * 않고 통과한다. 그래서 운영 빈 기준의 PG 전용 시험으로 내용 그대로 옮겼다 — 리포지토리 메서드의 의미는 {@code
 * ProactiveJobExecutionRepositoryContractTest} 가 구현마다 본다.
 */
@Transactional
class ProactiveExecutionSweeperIntegrationTest extends IntegrationTestBase {

  @Autowired private ProactiveJobExecutionRepository repository;
  @Autowired private ProactiveJobRepository jobRepository;
  @Autowired private ProactiveExecutionSweeper sweeper;
  @Autowired private ProactiveExecutionRegistry executionRegistry;
  @Autowired private ProactiveHeartbeatService heartbeatService;
  @Autowired private DSLContext dsl;

  private Long userId;

  @BeforeEach
  void setUp() {
    String unique = "pjesweep_" + System.nanoTime();
    userId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, unique)
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "PJE Sweeper Tester")
            .set(USER.EMAIL, unique + "@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();
  }

  private Long createJob(String name) {
    return jobRepository.create(
        userId, name, "prompt", null, "0 0 9 * * *", "Asia/Seoul", true, null, Map.of());
  }

  /** COMPLETED + htmlContent 있는 실행 = 리포트. */
  private Long createReport(Long jobId) {
    Long execId = repository.create(jobId);
    repository.updateResult(
        execId,
        "COMPLETED",
        Map.of("title", "완료", "summary", "요약", "htmlContent", "<h1>body</h1>"),
        LocalDateTime.now());
    return execId;
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

  /** 오래된 실행이 실제 저장소·정리기를 거쳐 FAILED 로 바뀌고 늦은 결과 쓰기를 거부한다. */
  @Test
  @DisplayName("정리기는 오래된 고아 실행을 FAILED 로 닫고 늦은 결과 쓰기를 거부한다")
  void sweeper_closesOrphanAndRejectsLateResult() {
    Long executionId = repository.create(createJob("고아 실행 잡"));
    setHeartbeat(executionId, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(30));

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

  /**
   * 리뷰 I-6(a): DB 하트비트가 오래됐어도 이 JVM 등록부에 있는 실행(AI 호출에 막힌 워커)은 실제 저장소·정리기를 거쳐도 RUNNING 으로 남는다. 등록부는
   * 싱글턴 빈이라 finally 에서 반드시 해제한다(다른 테스트로 새지 않게).
   */
  @Test
  @DisplayName("등록부에 있는 실행은 하트비트가 오래돼도 정리기가 닫지 않는다")
  void sweeper_keepsRegisteredExecutionRunningEvenIfHeartbeatStale() {
    Long executionId = repository.create(createJob("막힌 워커 잡"));
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
  @DisplayName("하트비트 서비스는 등록된 실행의 오래된 하트비트를 갱신해 정리 대상에서 뺀다")
  void heartbeatService_refreshesStaleHeartbeat_soExecutionLeavesStaleQuery() {
    Long executionId = repository.create(createJob("하트비트 서비스 잡"));
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

  /** WD-258 simplify(M): 활성 실행 테넌트 목록 함수와 함께 활성 행 부분 색인이 만들어졌는지 본다(PG 마이그레이션 확인). */
  @Test
  @DisplayName("활성 실행 테넌트 목록에 테넌트가 나오고 활성 행 부분 색인이 있다")
  void tenantIdsWithActiveExecutions_andActiveIndexExists() {
    repository.create(createJob("활성 테넌트 잡"));

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
  @DisplayName("V149 백필은 활성 행만 UTC 현재 시각으로 채운다")
  void v149Backfill_fillsActiveRowsWithUtcNowOnly() throws Exception {
    Long jobId = createJob("백필 잡");
    Long pending = repository.create(jobId);
    Long running = repository.create(jobId);
    repository.updateStatus(running, "RUNNING", LocalDateTime.now(ZoneOffset.UTC), null);
    Long completed = createReport(jobId);
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
