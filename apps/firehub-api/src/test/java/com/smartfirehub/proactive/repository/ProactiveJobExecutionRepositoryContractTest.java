package com.smartfirehub.proactive.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.proactive.dto.ProactiveJobExecutionResponse;
import com.smartfirehub.proactive.dto.ProactiveJobExecutionSummaryResponse;
import com.smartfirehub.proactive.dto.ReportListItemResponse;
import com.smartfirehub.securitylevel.dto.SecurityLevelSummary;
import com.smartfirehub.user.repository.AccessFixtures.ProactiveExecutionStored;
import com.smartfirehub.user.repository.TenantPairContractBase;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ProactiveJobExecutionRepository 계약 테스트 — 공개 메서드 17 개 전부(WD-485·WD-709). jOOQ·MyBatis 는 PG,
 * TiberoMyBatis 는 Tibero 실서버(두 계정 RLS)다.
 *
 * <p>리뷰 초점: 「리포트」 정의(COMPLETED + htmlContent 가 있고 비어 있지 않음)와 사용자 소유권 스코핑, 긴(10KB) 본문 리포트가 목록에 나오고
 * title/summary 가 맞음(Review Focus 5), 활성 상태 조건부 갱신(CAS) — 두 트랜잭션이 겨루면 승자는 하나, 잡마다 최근 실행(같은 시각이면 id
 * 큰 쪽), 리포트 등급 올리기의 순위·작성자·진행 중 조건, 명시 테넌트 인자({@code findStaleExecutions})가 다른 테넌트 행을 섞지 않음.
 *
 * <p>정리기·하트비트 서비스·V149 마이그레이션처럼 서비스 빈(운영 jOOQ 빈)과 PG 고유 확인을 거치는 시험은 {@code
 * ProactiveExecutionSweeperIntegrationTest} 로 옮겼다 — 여기 두면 MyBatis 하위 클래스가 MyBatis 를 보지 않고 통과한다.
 */
abstract class ProactiveJobExecutionRepositoryContractTest extends TenantPairContractBase {

  /** 계약 대상 리포지토리 — 구현별 하위 클래스가 제공한다. */
  protected abstract ProactiveJobExecutionRepository repository();

  private long userA;
  private long userA2;
  private long userB;

  @BeforeEach
  void setUpUsers() {
    userA = fx.createUser(prefix + "-ua", null);
    userA2 = fx.createUser(prefix + "-ua2", null);
    userB = fx.createUser(prefix + "-ub", null);
  }

  // ---- 도우미 ----

  private long job(long tenant, long user, String name) {
    return fx.createProactiveJob(tenant, user, name + "-" + prefix);
  }

  private long createInA(long jobId) {
    return inA(() -> repository().create(jobId));
  }

  /** COMPLETED + 결과(htmlContent 포함 여부는 호출자가 정한다)인 실행 — 테넌트 A. */
  private long completedInA(long jobId, Map<String, Object> result) {
    long id = createInA(jobId);
    assertThat(
            inA(
                () ->
                    repository()
                        .updateResult(id, "COMPLETED", result, LocalDateTime.now(ZoneOffset.UTC))))
        .isTrue();
    return id;
  }

  /** COMPLETED + htmlContent 있는 실행 = 리포트(테넌트 A). */
  private long reportInA(long jobId, String title, String summary) {
    return completedInA(
        jobId, Map.of("title", title, "summary", summary, "htmlContent", "<h1>body</h1>"));
  }

  private static LocalDateTime utcNowMicros() {
    return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }

  // ---- create ----

  @Test
  @DisplayName("create 는 PENDING 실행을 만들고 하트비트를 바로 남기며 다른 테넌트에는 보이지 않는다")
  void create_pendingWithHeartbeat() {
    long jobId = job(tenantA, userA, "생성 잡");

    long id = createInA(jobId);

    ProactiveExecutionStored stored = fx.proactiveExecutionStored(id);
    assertThat(stored.jobId()).isEqualTo(jobId);
    assertThat(stored.status()).isEqualTo("PENDING");
    assertThat(stored.startedAt()).isNull();
    assertThat(stored.completedAt()).isNull();
    assertThat(stored.resultJson()).isNull();
    assertThat(Duration.between(stored.heartbeatAt(), LocalDateTime.now(ZoneOffset.UTC)).abs())
        .isLessThan(Duration.ofMinutes(5));
    assertThat(Duration.between(stored.createdAt(), fx.dbWallClockNow()).abs())
        .isLessThan(Duration.ofMinutes(5));

    ProactiveJobExecutionResponse found = inA(() -> repository().findById(id)).orElseThrow();
    assertThat(found.status()).isEqualTo("PENDING");
    assertThat(found.jobId()).isEqualTo(jobId);
    assertThat(found.result()).isNull();
    assertThat(found.deliveredChannels()).isNull();
    assertThat(found.securityLevel()).isNull();
    assertThat(inB(() -> repository().findById(id))).as("다른 테넌트").isEmpty();
    assertThat(inA(() -> repository().findById(-1L))).isEmpty();
  }

  // ---- updateStatus·updateResult·updateError (CAS) ----

  @Test
  @DisplayName("updateStatus 는 넘긴 시각만 바꾸고 활성 실행이면 true 를 돌려준다")
  void updateStatus_setsGivenTimes() {
    long id = createInA(job(tenantA, userA, "상태 잡"));
    LocalDateTime started = utcNowMicros().minusMinutes(2);
    LocalDateTime completed = utcNowMicros();

    assertThat(inA(() -> repository().updateStatus(id, "RUNNING", started, null))).isTrue();
    ProactiveExecutionStored running = fx.proactiveExecutionStored(id);
    assertThat(running.status()).isEqualTo("RUNNING");
    assertThat(running.startedAt()).isEqualTo(started);
    assertThat(running.completedAt()).isNull();

    assertThat(inA(() -> repository().updateStatus(id, "RUNNING", null, completed))).isTrue();
    ProactiveExecutionStored after = fx.proactiveExecutionStored(id);
    assertThat(after.startedAt()).as("null 은 유지").isEqualTo(started);
    assertThat(after.completedAt()).isEqualTo(completed);
    assertThat(inB(() -> repository().updateStatus(id, "RUNNING", started, null)))
        .as("다른 테넌트")
        .isFalse();
  }

  /** 생성 직후 정리된 행을 러너의 늦은 RUNNING 전환이 되살리지 않는다. */
  @Test
  @DisplayName("updateStatus 는 이미 닫힌 실행을 다시 RUNNING 으로 되살리지 않는다")
  void updateStatus_doesNotRestartInterruptedExecution() {
    long id = createInA(job(tenantA, userA, "시작 전 중단 잡"));
    runA(() -> repository().updateError(id, "중단됨"));

    assertThat(inA(() -> repository().updateStatus(id, "RUNNING", utcNowMicros(), null))).isFalse();
    assertThat(fx.proactiveExecutionStored(id).status()).isEqualTo("FAILED");
  }

  @Test
  @DisplayName("updateResult 는 결과 JSON 을 왕복시키고 결과가 null 이면 기존 결과를 유지한다")
  void updateResult_roundTripAndNullKeeps() {
    long id = createInA(job(tenantA, userA, "결과 잡"));
    Map<String, Object> result =
        Map.of(
            "title",
            "제목",
            "htmlContent",
            "<p>본문</p>",
            "n",
            3,
            "sections",
            List.of(Map.of("k", "v")));
    LocalDateTime first = utcNowMicros().minusMinutes(1);
    LocalDateTime second = utcNowMicros();

    assertThat(inA(() -> repository().updateResult(id, "RUNNING", result, first))).isTrue();
    assertThat(inA(() -> repository().updateResult(id, "COMPLETED", null, second))).isTrue();

    ProactiveJobExecutionResponse found = inA(() -> repository().findById(id)).orElseThrow();
    assertThat(found.status()).isEqualTo("COMPLETED");
    assertThat(found.result()).isEqualTo(result);
    assertThat(found.completedAt()).isEqualTo(second);
  }

  /** 정리기가 먼저 닫은 실행을 늦게 돌아온 러너의 성공 결과가 되살리면 안 된다(WD-258). */
  @Test
  @DisplayName("updateResult 는 정리기가 먼저 닫은 실행의 상태·결과를 되살리지 않는다")
  void updateResult_doesNotOverwriteInterruptedExecution() {
    long id = createInA(job(tenantA, userA, "중단된 잡"));
    runA(() -> repository().updateError(id, "api 가 재시작되어 실행이 중단되었습니다"));

    assertThat(
            inA(
                () ->
                    repository()
                        .updateResult(
                            id, "COMPLETED", Map.of("htmlContent", "late"), utcNowMicros())))
        .isFalse();

    ProactiveJobExecutionResponse execution = inA(() -> repository().findById(id)).orElseThrow();
    assertThat(execution.status()).isEqualTo("FAILED");
    assertThat(execution.errorMessage()).isEqualTo("api 가 재시작되어 실행이 중단되었습니다");
    assertThat(execution.result()).isNull();
  }

  @Test
  @DisplayName("updateError 는 FAILED·오류 메시지·완료 시각을 기록한다")
  void updateError_marksFailed() {
    long id = createInA(job(tenantA, userA, "실패 잡"));

    assertThat(inA(() -> repository().updateError(id, "오류 발생"))).isTrue();

    ProactiveExecutionStored stored = fx.proactiveExecutionStored(id);
    assertThat(stored.status()).isEqualTo("FAILED");
    assertThat(stored.errorMessage()).isEqualTo("오류 발생");
    assertThat(Duration.between(stored.completedAt(), LocalDateTime.now(ZoneOffset.UTC)).abs())
        .isLessThan(Duration.ofMinutes(5));
  }

  /** 성공으로 닫힌 실행을 늦은 실패 쓰기가 덮으면 목록과 보고서가 사라진다(WD-258). */
  @Test
  @DisplayName("updateError 는 이미 완료된 실행을 덮지 않는다")
  void updateError_doesNotOverwriteCompletedExecution() {
    long id = reportInA(job(tenantA, userA, "완료된 잡"), "제목", "요약");

    assertThat(inA(() -> repository().updateError(id, "늦은 오류"))).isFalse();

    ProactiveJobExecutionResponse execution = inA(() -> repository().findById(id)).orElseThrow();
    assertThat(execution.status()).isEqualTo("COMPLETED");
    assertThat(execution.errorMessage()).isNull();
    assertThat(execution.result()).containsEntry("htmlContent", "<h1>body</h1>");
  }

  /** 시스템이 쓰는 오류 열은 Tibero 바이트 한도 때문에 잘릴 수 있다 — 계약은 「원문의 앞부분」이다(jOOQ 는 자르지 않는다). */
  @Test
  @DisplayName("updateError 의 긴 한글 오류 메시지는 원문의 앞부분으로 저장된다")
  void updateError_longMessageIsPrefix() {
    long id = createInA(job(tenantA, userA, "긴 오류 잡"));
    String message = "오류".repeat(10_000);

    assertThat(inA(() -> repository().updateError(id, message))).isTrue();

    String stored = fx.proactiveExecutionStored(id).errorMessage();
    assertThat(message).startsWith(stored);
    assertThat(stored.length()).isGreaterThanOrEqualTo(16_000);
    assertThat(inA(() -> repository().findById(id)).orElseThrow().errorMessage()).isEqualTo(stored);
  }

  /**
   * 두 트랜잭션이 같은 실행을 닫으려 겨루면 승자는 하나다 — 첫 트랜잭션(updateError)이 커밋 전에 멈춘 사이 두 번째(updateResult)는 행 잠금에서
   * 기다렸다가, 첫 쪽이 커밋하면 활성 조건을 다시 보고 0행(false)이 된다.
   */
  @Test
  @DisplayName("같은 실행을 동시에 닫는 두 트랜잭션 중 승자는 하나다")
  void concurrentClose_hasExactlyOneWinner() throws Exception {
    long id = createInA(job(tenantA, userA, "경합 잡"));
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch firstDone = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try {
      Future<Boolean> first =
          pool.submit(
              () ->
                  fx.inTenant(
                      tenantA,
                      () -> {
                        boolean r = repository().updateError(id, "먼저 닫음");
                        firstDone.countDown();
                        awaitQuietly(release);
                        return r;
                      }));
      assertThat(firstDone.await(30, TimeUnit.SECONDS)).isTrue();

      Future<Boolean> second =
          pool.submit(
              () ->
                  fx.inTenant(
                      tenantA,
                      () ->
                          repository()
                              .updateResult(
                                  id,
                                  "COMPLETED",
                                  Map.of("htmlContent", "late"),
                                  LocalDateTime.now(ZoneOffset.UTC))));
      assertThatThrownBy(() -> second.get(1500, TimeUnit.MILLISECONDS))
          .as("두 번째 쓰기는 첫 트랜잭션이 끝날 때까지 기다린다")
          .isInstanceOf(TimeoutException.class);

      release.countDown();
      boolean r1 = first.get(30, TimeUnit.SECONDS);
      boolean r2 = second.get(30, TimeUnit.SECONDS);

      assertThat(r1).isTrue();
      assertThat(r2).as("뒤따른 트랜잭션은 활성 조건에 걸려 0행").isFalse();
      ProactiveExecutionStored stored = fx.proactiveExecutionStored(id);
      assertThat(stored.status()).isEqualTo("FAILED");
      assertThat(stored.resultJson()).isNull();
    } finally {
      release.countDown();
      pool.shutdown();
      pool.awaitTermination(30, TimeUnit.SECONDS);
    }
  }

  // ---- touchHeartbeat·tenantIdsWithActiveExecutions·findStaleExecutions ----

  /** 하트비트는 완료 행을 건드리지 않아야 정리된 실행을 다시 활성으로 오인하지 않는다. */
  @Test
  @DisplayName("touchHeartbeat 는 활성 실행만 갱신하고 다른 테넌트의 실행은 건드리지 않는다")
  void touchHeartbeat_updatesActiveOnly() {
    long jobId = job(tenantA, userA, "하트비트 갱신 잡");
    long active = createInA(jobId);
    long finished = createInA(jobId);
    runA(() -> repository().updateError(finished, "끝남"));
    // DB timestamp 는 마이크로초까지만 저장한다 — 비교가 맞도록 기준 시각을 마이크로초로 자른다(WD-474).
    LocalDateTime beatAt = utcNowMicros().minusSeconds(5);

    assertThat(inA(() -> repository().touchHeartbeat(List.of(active, finished), beatAt)))
        .isEqualTo(1);
    assertThat(fx.proactiveExecutionStored(active).heartbeatAt()).isEqualTo(beatAt);
    assertThat(fx.proactiveExecutionStored(finished).heartbeatAt()).isNotEqualTo(beatAt);

    LocalDateTime other = beatAt.minusMinutes(1);
    assertThat(inB(() -> repository().touchHeartbeat(List.of(active), other)))
        .as("다른 테넌트")
        .isZero();
    assertThat(fx.proactiveExecutionStored(active).heartbeatAt()).isEqualTo(beatAt);
    assertThat(inA(() -> repository().touchHeartbeat(List.of(), other))).isZero();
  }

  @Test
  @DisplayName("touchHeartbeat 는 500 개를 넘는 id 목록도 처리한다")
  void touchHeartbeat_manyIds() {
    long active = createInA(job(tenantA, userA, "다건 하트비트 잡"));
    List<Long> ids = new ArrayList<>();
    for (long i = 1; i <= 1200; i++) {
      ids.add(-i);
    }
    ids.add(active);
    LocalDateTime beatAt = utcNowMicros().minusSeconds(3);

    assertThat(inA(() -> repository().touchHeartbeat(ids, beatAt))).isEqualTo(1);
    assertThat(fx.proactiveExecutionStored(active).heartbeatAt()).isEqualTo(beatAt);
  }

  /**
   * 정리기가 도는 테넌트 목록은 정책을 넘는 함수(PG V149 definer, Tibero FH_TENANT 커서)가 실행 표에서 유도한다 — 테넌트 컨텍스트 없이 불러도
   * 활성 실행이 있는 테넌트가 나오고, 닫히면 빠진다. 공유 DB 라 다른 테넌트의 값이 섞여 있어 contains 로만 본다.
   */
  @Test
  @DisplayName("tenantIdsWithActiveExecutions 는 테넌트 없이도 활성 실행이 있는 테넌트만 돌려준다")
  void tenantIdsWithActiveExecutions_listsActiveTenants() {
    long id = createInA(job(tenantA, userA, "활성 테넌트 잡"));

    List<Long> active = fx.inTenant(null, () -> repository().tenantIdsWithActiveExecutions());
    assertThat(active).contains(tenantA).doesNotContain(tenantB);

    runA(() -> repository().updateError(id, "닫힘"));
    assertThat(fx.inTenant(null, () -> repository().tenantIdsWithActiveExecutions()))
        .doesNotContain(tenantA);
  }

  /** 하트비트가 오래됐고 아직 활성인 행만 찾는다. 시작 시각보다 최신인 하트비트가 우선한다. */
  @Test
  @DisplayName("findStaleExecutions 는 하트비트가 오래된 활성 실행만 찾고 다른 테넌트 행은 섞지 않는다")
  void findStaleExecutions_usesHeartbeatAndActiveStatus() {
    long jobId = job(tenantA, userA, "하트비트 잡");
    long stale = createInA(jobId);
    long live = createInA(jobId);
    long finished = createInA(jobId);
    LocalDateTime old = utcNowMicros().minusMinutes(30);
    LocalDateTime recent = utcNowMicros();
    for (long id : List.of(stale, live, finished)) {
      fx.setProactiveExecutionField(id, "heartbeat_at", old);
      fx.setProactiveExecutionField(id, "started_at", old);
    }
    fx.setProactiveExecutionField(live, "heartbeat_at", recent);
    runA(() -> repository().updateError(finished, "완료됨"));

    long staleB = fx.createProactiveJobExecution(tenantB, job(tenantB, userB, "B 잡"), "RUNNING");
    fx.setProactiveExecutionField(staleB, "heartbeat_at", old);

    LocalDateTime threshold = recent.minusMinutes(5);
    assertThat(inA(() -> repository().findStaleExecutions(tenantA, threshold)))
        .containsExactly(stale);
    assertThat(inA(() -> repository().findStaleExecutions(tenantB, threshold)))
        .as("테넌트 A 범위에서 B 를 물어도 섞이지 않는다")
        .isEmpty();
    assertThat(inB(() -> repository().findStaleExecutions(tenantB, threshold)))
        .containsExactly(staleB);
  }

  @Test
  @DisplayName("findStaleExecutions 는 하트비트가 없으면 시작 시각, 그것도 없으면 생성 시각으로 판정한다")
  void findStaleExecutions_fallsBackToStartedThenCreated() {
    long jobId = job(tenantA, userA, "대체 시각 잡");
    long byStarted = createInA(jobId);
    long byCreated = createInA(jobId);
    LocalDateTime old = utcNowMicros().minusHours(2);
    fx.setProactiveExecutionField(byStarted, "heartbeat_at", null);
    fx.setProactiveExecutionField(byStarted, "started_at", old);
    fx.setProactiveExecutionField(byCreated, "heartbeat_at", null);
    fx.setProactiveExecutionField(byCreated, "created_at", old);

    assertThat(inA(() -> repository().findStaleExecutions(tenantA, utcNowMicros().minusHours(1))))
        .containsExactlyInAnyOrder(byStarted, byCreated);
  }

  // ---- 조회: findByJobId·findSummariesByJobId·findLatestByJobIds·findById ----

  @Test
  @DisplayName("findByJobId 는 그 작업의 실행을 id 내림차순으로 결과·등급과 함께 페이지로 돌려준다")
  void findByJobId_newestFirstWithResultAndLevel() {
    long level = fx.createSecurityLevel(tenantA, 13, "대외비-" + prefix);
    long jobId = job(tenantA, userA, "목록 잡");
    long otherJob = job(tenantA, userA, "다른 잡");
    long first = reportInA(jobId, "첫번째", "s1");
    long second = createInA(jobId);
    long third = reportInA(jobId, "세번째", "s3");
    createInA(otherJob);
    fx.setProactiveExecutionField(third, "security_level_id", level);

    List<ProactiveJobExecutionResponse> page1 = inA(() -> repository().findByJobId(jobId, 2, 0));
    List<ProactiveJobExecutionResponse> page2 = inA(() -> repository().findByJobId(jobId, 2, 2));

    assertThat(page1).extracting(ProactiveJobExecutionResponse::id).containsExactly(third, second);
    assertThat(page2).extracting(ProactiveJobExecutionResponse::id).containsExactly(first);
    assertThat(page1.get(0).result()).containsEntry("title", "세번째");
    assertThat(page1.get(0).securityLevel().id()).isEqualTo(level);
    assertThat(page1.get(0).securityLevel().name()).isEqualTo("대외비-" + prefix);
    assertThat(page1.get(0).securityLevel().rank()).isEqualTo(13);
    assertThat(page1.get(1).result()).isNull();
    assertThat(page1.get(1).securityLevel()).isNull();
    assertThat(inB(() -> repository().findByJobId(jobId, 20, 0))).as("다른 테넌트").isEmpty();
  }

  /**
   * findSummariesByJobId 통합 테스트 (#604) — 목록 endpoint 전용 경량 뷰가 리포트 본문(result)을 아예 담지 않으면서도 상태/시간 등
   * 요약 정보와 limit/offset 페이징은 findByJobId와 동일하게 동작하는지 검증한다.
   */
  @Test
  @DisplayName("findSummariesByJobId 는 본문 없이 요약 필드·전달 채널·등급을 돌려준다")
  void findSummariesByJobId_excludesResultButKeepsSummaryFields() {
    long level = fx.createSecurityLevel(tenantA, 12, "내부-" + prefix);
    long jobId = job(tenantA, userA, "요약 뷰 잡");
    long execId = reportInA(jobId, "리포트 제목", "리포트 요약");
    runA(() -> repository().updateDeliveredChannels(execId, List.of("CHAT", "EMAIL")));
    fx.setProactiveExecutionField(execId, "security_level_id", level);

    List<ProactiveJobExecutionSummaryResponse> summaries =
        inA(() -> repository().findSummariesByJobId(jobId, 20, 0));

    assertThat(summaries).hasSize(1);
    ProactiveJobExecutionSummaryResponse summary = summaries.get(0);
    assertThat(summary.id()).isEqualTo(execId);
    assertThat(summary.jobId()).isEqualTo(jobId);
    assertThat(summary.status()).isEqualTo("COMPLETED");
    assertThat(summary.deliveredChannels()).containsExactly("CHAT", "EMAIL");
    assertThat(summary.securityLevel().id()).isEqualTo(level);
    assertThat(summary.completedAt()).isNotNull();
    // ProactiveJobExecutionSummaryResponse에는 result 필드 자체가 없다 — 리포트 본문 미포함을 타입 레벨로 보장
  }

  @Test
  @DisplayName("findSummariesByJobId 는 id 내림차순으로 limit·offset 을 적용한다")
  void findSummariesByJobId_appliesLimitAndOffsetOrderedByIdDesc() {
    long jobId = job(tenantA, userA, "요약 페이징 잡");
    long first = reportInA(jobId, "첫번째", "s1");
    long second = reportInA(jobId, "두번째", "s2");
    long third = reportInA(jobId, "세번째", "s3");

    assertThat(inA(() -> repository().findSummariesByJobId(jobId, 2, 0)))
        .extracting(ProactiveJobExecutionSummaryResponse::id)
        .containsExactly(third, second);
    assertThat(inA(() -> repository().findSummariesByJobId(jobId, 2, 2)))
        .extracting(ProactiveJobExecutionSummaryResponse::id)
        .containsExactly(first);
  }

  @Test
  @DisplayName("findLatestByJobIds 는 작업마다 생성 시각이 가장 늦은(같으면 id 큰) 실행 하나를 본문 없이 돌려준다")
  void findLatestByJobIds_latestPerJob() {
    long level = fx.createSecurityLevel(tenantA, 14, "기밀-" + prefix);
    long job1 = job(tenantA, userA, "최근1");
    long job2 = job(tenantA, userA, "최근2");
    long job3 = job(tenantA, userA, "실행없음");
    LocalDateTime base = utcNowMicros().minusHours(3);

    // job1: 같은 생성 시각 → id 큰 쪽
    long j1a = reportInA(job1, "a", "a");
    long j1b = reportInA(job1, "b", "b");
    fx.setProactiveExecutionField(j1a, "created_at", base);
    fx.setProactiveExecutionField(j1b, "created_at", base);
    fx.setProactiveExecutionField(j1b, "security_level_id", level);
    // job2: 생성 시각이 늦은 쪽이 id 가 작아도 이긴다
    long j2old = createInA(job2);
    long j2new = createInA(job2);
    fx.setProactiveExecutionField(j2old, "created_at", base.plusHours(1));
    fx.setProactiveExecutionField(j2new, "created_at", base);
    runA(() -> repository().updateDeliveredChannels(j2old, List.of("CHAT")));

    Map<Long, ProactiveJobExecutionResponse> latest =
        inA(() -> repository().findLatestByJobIds(List.of(job1, job2, job3)));

    assertThat(latest).containsOnlyKeys(job1, job2);
    assertThat(latest.get(job1).id()).isEqualTo(j1b);
    assertThat(latest.get(job1).result()).as("본문은 읽지 않는다").isNull();
    assertThat(latest.get(job1).status()).isEqualTo("COMPLETED");
    assertThat(latest.get(job1).securityLevel().id()).isEqualTo(level);
    assertThat(latest.get(job2).id()).isEqualTo(j2old);
    assertThat(latest.get(job2).deliveredChannels()).containsExactly("CHAT");
    assertThat(latest.get(job2).securityLevel()).isNull();
    assertThat(latest.get(job2).createdAt()).isEqualTo(base.plusHours(1));
    assertThat(inA(() -> repository().findLatestByJobIds(List.of()))).isEmpty();
    assertThat(inB(() -> repository().findLatestByJobIds(List.of(job1, job2))))
        .as("다른 테넌트")
        .isEmpty();
  }

  @Test
  @DisplayName("findById 는 저장된 모든 필드와 등급 정책 요약을 돌려준다")
  void findById_fullRowWithLevel() {
    long level = fx.createSecurityLevel(tenantA, 15, "극비-" + prefix);
    long jobId = job(tenantA, userA, "단건 잡");
    long id = createInA(jobId);
    LocalDateTime started = utcNowMicros().minusMinutes(3);
    runA(() -> repository().updateStatus(id, "RUNNING", started, null));
    runA(() -> repository().updateError(id, "실패 사유"));
    fx.setProactiveExecutionField(id, "security_level_id", level);

    ProactiveJobExecutionResponse found = inA(() -> repository().findById(id)).orElseThrow();

    ProactiveExecutionStored stored = fx.proactiveExecutionStored(id);
    assertThat(found.id()).isEqualTo(id);
    assertThat(found.jobId()).isEqualTo(jobId);
    assertThat(found.status()).isEqualTo("FAILED");
    assertThat(found.startedAt()).isEqualTo(started);
    assertThat(found.completedAt()).isEqualTo(stored.completedAt());
    assertThat(found.createdAt()).isEqualTo(stored.createdAt());
    assertThat(found.errorMessage()).isEqualTo("실패 사유");
    SecurityLevelSummary sl = found.securityLevel();
    assertThat(sl.id()).isEqualTo(level);
    assertThat(sl.name()).isEqualTo("극비-" + prefix);
    assertThat(sl.rank()).isEqualTo(15);
    assertThat(sl.exportPolicy()).isNotBlank();
    assertThat(sl.aiPolicy()).isNotBlank();
    assertThat(sl.sharePolicy()).isNotBlank();
  }

  // ---- updateDeliveredChannels ----

  @Test
  @DisplayName("updateDeliveredChannels 는 쉼표로 이어 저장하고 빈 목록·null 은 NULL 로 저장한다")
  void updateDeliveredChannels_joinsAndEmptyIsNull() {
    long id = createInA(job(tenantA, userA, "채널 잡"));

    runA(() -> repository().updateDeliveredChannels(id, List.of("CHAT", "EMAIL")));
    assertThat(fx.proactiveExecutionStored(id).deliveredChannels()).isEqualTo("CHAT,EMAIL");
    assertThat(inA(() -> repository().findById(id)).orElseThrow().deliveredChannels())
        .containsExactly("CHAT", "EMAIL");

    runA(() -> repository().updateDeliveredChannels(id, List.of()));
    assertThat(fx.proactiveExecutionStored(id).deliveredChannels()).isNull();
    assertThat(inA(() -> repository().findById(id)).orElseThrow().deliveredChannels()).isNull();

    runA(() -> repository().updateDeliveredChannels(id, List.of("SLACK")));
    runA(() -> repository().updateDeliveredChannels(id, null));
    assertThat(fx.proactiveExecutionStored(id).deliveredChannels()).isNull();
  }

  // ---- findReportsByUserId (Review Focus 5) ----

  @Test
  @DisplayName("findReportsByUserId 는 COMPLETED 이면서 htmlContent 가 있는 실행을 리포트로 돌려준다")
  void findReportsByUserId_returnsCompletedExecutionsWithHtmlContent() {
    long jobId = job(tenantA, userA, "월간 화재 통계");
    long id = reportInA(jobId, "8월 리포트", "요약문");

    List<ReportListItemResponse> reports =
        inA(() -> repository().findReportsByUserId(userA, 20, 0));

    assertThat(reports).hasSize(1);
    ReportListItemResponse r = reports.get(0);
    assertThat(r.executionId()).isEqualTo(id);
    assertThat(r.title()).isEqualTo("8월 리포트");
    assertThat(r.summary()).isEqualTo("요약문");
    assertThat(r.jobName()).isEqualTo("월간 화재 통계-" + prefix);
    assertThat(r.jobId()).isEqualTo(jobId);
    assertThat(r.completedAt()).isEqualTo(fx.proactiveExecutionStored(id).completedAt());
    assertThat(r.securityLevel()).isNull();
    assertThat(inB(() -> repository().findReportsByUserId(userA, 20, 0))).as("다른 테넌트").isEmpty();
  }

  /** Review Focus 5 — 본문이 4000 바이트를 크게 넘어도(Tibero JSON_VALUE 한도) 리포트로 나오고 제목·요약이 맞다. */
  @Test
  @DisplayName("htmlContent 가 10KB 를 넘는 리포트도 목록에 나오고 제목·요약·등급이 맞다")
  void findReportsByUserId_longHtmlContent() {
    long level = fx.createSecurityLevel(tenantA, 12, "긴본문-" + prefix);
    long jobId = job(tenantA, userA, "긴 리포트 잡");
    String html = "<div>" + "화재통계".repeat(1_000) + "</div>";
    assertThat(html.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isGreaterThan(10_000);
    long id = completedInA(jobId, Map.of("title", "긴 리포트", "summary", "긴 요약", "htmlContent", html));
    fx.setProactiveExecutionField(id, "security_level_id", level);

    List<ReportListItemResponse> reports =
        inA(() -> repository().findReportsByUserId(userA, 20, 0));

    assertThat(reports).hasSize(1);
    assertThat(reports.get(0).executionId()).isEqualTo(id);
    assertThat(reports.get(0).title()).isEqualTo("긴 리포트");
    assertThat(reports.get(0).summary()).isEqualTo("긴 요약");
    assertThat(reports.get(0).securityLevel().id()).isEqualTo(level);
  }

  @Test
  @DisplayName("htmlContent 가 없는 실행은 리포트가 아니다")
  void findReportsByUserId_excludesExecutionsWithoutHtmlContent() {
    // htmlContent 없는 구버전 sections 기반 결과 — 뷰어가 404를 내므로 목록에서 제외되어야 한다
    completedInA(job(tenantA, userA, "구버전 잡"), Map.of("title", "구버전", "sections", List.of()));

    assertThat(inA(() -> repository().findReportsByUserId(userA, 20, 0))).isEmpty();
  }

  @Test
  @DisplayName("htmlContent 가 빈 문자열이거나 JSON null 인 실행은 리포트가 아니다")
  void findReportsByUserId_excludesBlankOrNullHtmlContent() {
    long jobId = job(tenantA, userA, "빈 본문 잡");
    completedInA(jobId, Map.of("title", "빈 본문", "htmlContent", ""));
    Map<String, Object> nullHtml = new HashMap<>();
    nullHtml.put("title", "널 본문");
    nullHtml.put("htmlContent", null);
    completedInA(jobId, nullHtml);

    assertThat(inA(() -> repository().findReportsByUserId(userA, 20, 0))).isEmpty();
  }

  @Test
  @DisplayName("COMPLETED 가 아닌 실행은 리포트가 아니다")
  void findReportsByUserId_excludesFailedExecutions() {
    long id = createInA(job(tenantA, userA, "실패 잡"));
    runA(() -> repository().updateResult(id, "FAILED", Map.of("htmlContent", "<h1>x</h1>"), null));

    assertThat(inA(() -> repository().findReportsByUserId(userA, 20, 0))).isEmpty();
  }

  @Test
  @DisplayName("다른 사용자의 작업이 만든 리포트는 나오지 않는다")
  void findReportsByUserId_excludesOtherUsersReports() {
    reportInA(job(tenantA, userA2, "남의 잡"), "남의 리포트", "남의 요약");

    assertThat(inA(() -> repository().findReportsByUserId(userA, 20, 0))).isEmpty();
    assertThat(inA(() -> repository().findReportsByUserId(userA2, 20, 0))).hasSize(1);
  }

  @Test
  @DisplayName("제목이 공백뿐이거나 없으면 작업 이름을, 있으면 앞뒤 공백을 뗀 제목을 쓴다")
  void findReportsByUserId_titleFallbackAndTrim() {
    long blank = job(tenantA, userA, "제목없는잡");
    completedInA(blank, Map.of("title", "  ", "htmlContent", "<p>x</p>"));
    long missing = job(tenantA, userA, "제목키없음");
    completedInA(missing, Map.of("htmlContent", "<p>y</p>"));
    long padded = job(tenantA, userA, "공백제목");
    completedInA(padded, Map.of("title", "  보고서  ", "htmlContent", "<p>z</p>"));

    Map<Long, ReportListItemResponse> byJob = new HashMap<>();
    for (ReportListItemResponse r : inA(() -> repository().findReportsByUserId(userA, 20, 0))) {
      byJob.put(r.jobId(), r);
    }

    assertThat(byJob.get(blank).title()).isEqualTo("제목없는잡-" + prefix);
    assertThat(byJob.get(missing).title()).isEqualTo("제목키없음-" + prefix);
    assertThat(byJob.get(missing).summary()).isNull();
    assertThat(byJob.get(padded).title()).isEqualTo("보고서");
  }

  @Test
  @DisplayName("findReportsByUserId 는 완료 시각 내림차순으로 limit·offset 을 적용한다")
  void findReportsByUserId_appliesLimitAndOffset() {
    long jobId = job(tenantA, userA, "다건 잡");
    long first = reportInA(jobId, "첫번째", "s1");
    long second = reportInA(jobId, "두번째", "s2");
    long third = reportInA(jobId, "세번째", "s3");
    LocalDateTime base = utcNowMicros().minusHours(1);
    fx.setProactiveExecutionField(first, "completed_at", base);
    fx.setProactiveExecutionField(second, "completed_at", base.plusMinutes(1));
    fx.setProactiveExecutionField(third, "completed_at", base.plusMinutes(2));

    assertThat(inA(() -> repository().findReportsByUserId(userA, 2, 0)))
        .extracting(ReportListItemResponse::executionId)
        .containsExactly(third, second);
    assertThat(inA(() -> repository().findReportsByUserId(userA, 2, 2)))
        .extracting(ReportListItemResponse::executionId)
        .containsExactly(first);
  }

  /** 빈 본문 실행이 사이에 끼어도 페이지는 「리포트」만 센다 — 판정을 페이지 뒤에 하면 자리가 밀린다. */
  @Test
  @DisplayName("빈 본문 실행이 사이에 있어도 페이지는 리포트만 세어 나눈다")
  void findReportsByUserId_pagingSkipsNonReportsBetween() {
    long jobId = job(tenantA, userA, "섞인 잡");
    long older = reportInA(jobId, "옛 리포트", "s1");
    long blank = completedInA(jobId, Map.of("title", "빈", "htmlContent", ""));
    long newer = reportInA(jobId, "새 리포트", "s2");
    LocalDateTime base = utcNowMicros().minusHours(1);
    fx.setProactiveExecutionField(older, "completed_at", base);
    fx.setProactiveExecutionField(blank, "completed_at", base.plusMinutes(1));
    fx.setProactiveExecutionField(newer, "completed_at", base.plusMinutes(2));

    assertThat(inA(() -> repository().findReportsByUserId(userA, 1, 0)))
        .extracting(ReportListItemResponse::executionId)
        .containsExactly(newer);
    assertThat(inA(() -> repository().findReportsByUserId(userA, 1, 1)))
        .extracting(ReportListItemResponse::executionId)
        .containsExactly(older);
    assertThat(inA(() -> repository().findReportsByUserId(userA, 5, 2))).isEmpty();
  }

  /** PG {@code ->>} 는 최상위 배열에 NULL 을 준다 — 배열 안 객체에 htmlContent 가 있어도 리포트가 아니다(WD-709 fix C3). */
  @Test
  @DisplayName("result 가 최상위 배열이면 원소에 htmlContent 가 있어도 리포트가 아니다")
  void findReportsByUserId_excludesTopLevelArrayResult() {
    long jobId = job(tenantA, userA, "배열 결과 잡");
    long id = completedInA(jobId, Map.of("htmlContent", "<p>원래</p>"));
    fx.setProactiveExecutionResultJson(id, "[{\"htmlContent\": \"<p>x</p>\", \"title\": \"배열\"}]");

    assertThat(inA(() -> repository().findReportsByUserId(userA, 20, 0))).isEmpty();
  }

  /**
   * 리포트가 아닌 실행(최상위 배열·빈 본문)이 사이에 끼어도 limit 1 의 offset 0·1·2 페이지가 jOOQ 와 같다 — 판정과 페이지를 한 SQL 에서 하므로
   * 페이지는 리포트만 센다(WD-709 fix C1).
   */
  @Test
  @DisplayName("리포트가 아닌 실행이 사이에 있어도 limit 1 페이지는 offset 0·1·2 에서 리포트만 차례로 준다")
  void findReportsByUserId_singleRowPagesSkipExcludedRows() {
    long jobId = job(tenantA, userA, "한 줄 페이지 잡");
    long older = reportInA(jobId, "옛 리포트", "s1");
    long array = completedInA(jobId, Map.of("htmlContent", "<p>a</p>"));
    fx.setProactiveExecutionResultJson(array, "[{\"htmlContent\": \"<p>a</p>\"}]");
    long blank = completedInA(jobId, Map.of("title", "빈", "htmlContent", ""));
    long newer = reportInA(jobId, "새 리포트", "s2");
    LocalDateTime base = utcNowMicros().minusHours(1);
    fx.setProactiveExecutionField(older, "completed_at", base);
    fx.setProactiveExecutionField(array, "completed_at", base.plusMinutes(1));
    fx.setProactiveExecutionField(blank, "completed_at", base.plusMinutes(2));
    fx.setProactiveExecutionField(newer, "completed_at", base.plusMinutes(3));

    assertThat(inA(() -> repository().findReportsByUserId(userA, 1, 0)))
        .extracting(ReportListItemResponse::executionId)
        .containsExactly(newer);
    assertThat(inA(() -> repository().findReportsByUserId(userA, 1, 1)))
        .extracting(ReportListItemResponse::executionId)
        .containsExactly(older);
    assertThat(inA(() -> repository().findReportsByUserId(userA, 1, 2))).isEmpty();
  }

  // ---- 등급: raiseSecurityLevel·findDatasetLevelIds·countByLevel·moveLevel ----

  @Test
  @DisplayName("raiseSecurityLevel 은 등급이 없거나 더 낮을 때만 올리고 작성자가 아니면 바꾸지 않는다")
  void raiseSecurityLevel_onlyRaisesForOwner() {
    long low = fx.createSecurityLevel(tenantA, 11, "낮음-" + prefix);
    long high = fx.createSecurityLevel(tenantA, 15, "높음-" + prefix);
    long levelB = fx.createSecurityLevel(tenantB, 19, "B등급-" + prefix);
    long id = createInA(job(tenantA, userA, "등급 잡"));

    assertThat(inA(() -> repository().raiseSecurityLevel(id, userA, low, null))).isEqualTo(1);
    assertThat(fx.proactiveExecutionStored(id).securityLevelId()).isEqualTo(low);
    assertThat(inA(() -> repository().raiseSecurityLevel(id, userA2, high, null)))
        .as("작성자가 아님")
        .isZero();
    assertThat(inA(() -> repository().raiseSecurityLevel(id, userA, high, null))).isEqualTo(1);
    assertThat(inA(() -> repository().raiseSecurityLevel(id, userA, low, null)))
        .as("더 낮은 등급으로는 내리지 않는다")
        .isZero();
    assertThat(inA(() -> repository().raiseSecurityLevel(id, userA, levelB, null)))
        .as("다른 테넌트의 등급")
        .isZero();
    assertThat(inA(() -> repository().raiseSecurityLevel(id, userA, -1L, null)))
        .as("없는 등급")
        .isZero();
    assertThat(fx.proactiveExecutionStored(id).securityLevelId()).isEqualTo(high);
  }

  @Test
  @DisplayName("raiseSecurityLevel 은 runningSince 가 있으면 그때 진행 중이던 실행만 올린다")
  void raiseSecurityLevel_runningSinceGuard() {
    long level = fx.createSecurityLevel(tenantA, 13, "진행-" + prefix);
    long jobId = job(tenantA, userA, "진행 조건 잡");
    LocalDateTime since = utcNowMicros().minusMinutes(10);
    long endedBefore = reportInA(jobId, "전", "s");
    fx.setProactiveExecutionField(endedBefore, "completed_at", since.minusMinutes(5));
    long endedAfter = reportInA(jobId, "후", "s");
    fx.setProactiveExecutionField(endedAfter, "completed_at", since.plusMinutes(1));
    long running = createInA(jobId);
    runA(() -> repository().updateStatus(running, "RUNNING", since, null));
    long pending = createInA(jobId);

    assertThat(inA(() -> repository().raiseSecurityLevel(endedBefore, userA, level, since)))
        .isZero();
    assertThat(inA(() -> repository().raiseSecurityLevel(endedAfter, userA, level, since)))
        .isEqualTo(1);
    assertThat(inA(() -> repository().raiseSecurityLevel(running, userA, level, since)))
        .isEqualTo(1);
    assertThat(inA(() -> repository().raiseSecurityLevel(pending, userA, level, since)))
        .as("PENDING 이고 완료 시각 없음")
        .isZero();
  }

  @Test
  @DisplayName("findDatasetLevelIds 는 보이는 데이터셋의 등급 id 를 중복 없이 돌려준다")
  void findDatasetLevelIds_distinctVisible() {
    long low = fx.createSecurityLevel(tenantA, 11, "d낮음-" + prefix);
    long high = fx.createSecurityLevel(tenantA, 14, "d높음-" + prefix);
    long levelB = fx.createSecurityLevel(tenantB, 12, "dB-" + prefix);
    long d1 = fx.createDataset(tenantA, userA, prefix + "_d1", low);
    long d2 = fx.createDataset(tenantA, userA, prefix + "_d2", low);
    long d3 = fx.createDataset(tenantA, userA, prefix + "_d3", high);
    long dB = fx.createDataset(tenantB, userB, prefix + "_db", levelB);

    Set<Long> levels = inA(() -> repository().findDatasetLevelIds(List.of(d1, d2, d3, dB)));

    assertThat(levels).containsExactlyInAnyOrder(low, high);
    assertThat(inA(() -> repository().findDatasetLevelIds(List.of()))).isEmpty();
    assertThat(inB(() -> repository().findDatasetLevelIds(List.of(d1, dB))))
        .containsExactly(levelB);
  }

  @Test
  @DisplayName("countByLevel 은 등급별 실행 수를, moveLevel 은 옮긴 행 수를 돌려준다")
  void countByLevel_andMoveLevel() {
    long low = fx.createSecurityLevel(tenantA, 11, "c낮음-" + prefix);
    long high = fx.createSecurityLevel(tenantA, 14, "c높음-" + prefix);
    long jobId = job(tenantA, userA, "집계 잡");
    long e1 = createInA(jobId);
    long e2 = createInA(jobId);
    long e3 = createInA(jobId);
    createInA(jobId);
    fx.setProactiveExecutionField(e1, "security_level_id", low);
    fx.setProactiveExecutionField(e2, "security_level_id", low);
    fx.setProactiveExecutionField(e3, "security_level_id", high);

    assertThat(inA(() -> repository().countByLevel()))
        .containsExactlyInAnyOrderEntriesOf(Map.of(low, 2L, high, 1L));
    assertThat(inB(() -> repository().countByLevel())).as("다른 테넌트").isEmpty();

    assertThat(inB(() -> repository().moveLevel(low, high))).as("다른 테넌트").isZero();
    assertThat(inA(() -> repository().moveLevel(low, high))).isEqualTo(2);
    assertThat(inA(() -> repository().countByLevel()))
        .containsExactlyInAnyOrderEntriesOf(Map.of(high, 3L));
    assertThat(fx.proactiveExecutionStored(e1).securityLevelId()).isEqualTo(high);
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await(30, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
