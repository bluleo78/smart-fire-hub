package com.smartfirehub.securitylevel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.securitylevel.access.AccessDenialAction;
import com.smartfirehub.securitylevel.repository.SecurityLevelRepository;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import com.smartfirehub.user.repository.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 스펙 §4.6·보충 스펙 §3 — 접근 거부는 요청이 실패(롤백)해도 남아야 하고, 같은 거부는 1분에 1건으로 합쳐진다. 감사 등급(audit_access)의 접근만
 * DATASET_ACCESS 로 남는다.
 */
class SecurityAuditRecorderTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;
  @Autowired private AuditLogService auditLogService;
  @Autowired private UserRepository userRepository;
  @Autowired private SecurityLevelRepository levelRepository;
  @Autowired private PlatformTransactionManager txManager;
  @Autowired private DataSource dataSource;

  private SecurityFixture fx;
  private long actor;
  private long sensitive;
  private long open;

  /** 가짜 시계(나노초) — Caffeine Ticker 로 주입해 1분 창을 테스트 안에서 넘긴다. */
  private final AtomicLong nanos = new AtomicLong(1);

  private SecurityAuditRecorder recorder;

  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    actor = fx.createUser("sar");
    sensitive = fx.createDatasetRow("sar_s_" + System.nanoTime(), fx.levelId("민감"), actor);
    open = fx.createDatasetRow("sar_o_" + System.nanoTime(), fx.levelId("공개"), actor);
    // 테스트마다 새 기록기 — 합치기 캐시가 테스트 사이에 새지 않게 한다.
    recorder = newRecorder(auditLogService, levelRepository);
  }

  /** 테스트용 기록기 — 가짜 시계, 운영 대기 상한, 10초 종료 대기, 격리된 메트릭. */
  private SecurityAuditRecorder newRecorder(AuditLogService audit, SecurityLevelRepository levels) {
    return newRecorder(
        audit,
        levels,
        new SimpleMeterRegistry(),
        SecurityAuditRecorder.QUEUE_CAPACITY,
        Duration.ofSeconds(10));
  }

  /** 테스트용 기록기 — 메트릭 레지스트리·대기 상한·종료 대기를 정해 포화·종료 버림을 만든다. */
  private SecurityAuditRecorder newRecorder(
      AuditLogService audit,
      SecurityLevelRepository levels,
      MeterRegistry registry,
      int queueCapacity,
      Duration drainTimeout) {
    return new SecurityAuditRecorder(
        audit,
        userRepository,
        levels,
        txManager,
        new SecurityAuditMetrics(registry),
        nanos::get,
        queueCapacity,
        drainTimeout);
  }

  @AfterEach
  void tearDown() throws Exception {
    // 이 테스트가 만든 기록기의 비동기 기록을 다 쓴 뒤 정리하고, 실행기 스레드를 닫는다(테스트마다 새 기록기라 스레드가 쌓이지 않게).
    assertThat(recorder.awaitIdle(Duration.ofSeconds(30))).isTrue();
    recorder.shutdown();
    inTenantFixture(() -> dsl.execute("DELETE FROM audit_log WHERE user_id = ?", actor));
    fx.deleteDatasetRow(sensitive);
    fx.deleteDatasetRow(open);
    fx.deleteUser(actor);
  }

  /** 이 테스트 사용자의 해당 action_type 감사 행 수(비동기 기록이 끝난 뒤). */
  private int count(String action) {
    awaitRecorder();
    return inTenantFixture(
        () ->
            dsl.fetchOne(
                    "SELECT count(*) FROM audit_log WHERE user_id = ? AND action_type = ?",
                    actor,
                    action)
                .get(0, Integer.class));
  }

  /** 호출자 트랜잭션이 롤백돼도 거부 감사는 REQUIRES_NEW 로 이미 커밋돼 남아야 한다(403/404 요청 경로). */
  @Test
  void denial_survivesCallerRollback() {
    TransactionTemplate caller = new TransactionTemplate(txManager);
    caller.executeWithoutResult(
        s -> {
          recorder.recordDenial(
              actor, AccessDenialAction.VIEW, "CLEARANCE_INSUFFICIENT", sensitive, null);
          s.setRollbackOnly();
        });
    assertThat(count("DATASET_ACCESS_DENIED")).isEqualTo(1);
    var row =
        inTenantFixture(
            () ->
                dsl.fetchOne(
                    "SELECT resource, resource_id, result, metadata->>'action' a, metadata->>'reason' r,"
                        + " tenant_id FROM audit_log WHERE user_id = ? AND action_type = 'DATASET_ACCESS_DENIED'",
                    actor));
    assertThat(row.get("resource", String.class)).isEqualTo("dataset");
    assertThat(row.get("resource_id", String.class)).isEqualTo(String.valueOf(sensitive));
    assertThat(row.get("result", String.class)).isEqualTo("FAILURE");
    assertThat(row.get("a", String.class)).isEqualTo("VIEW");
    assertThat(row.get("r", String.class)).isEqualTo("CLEARANCE_INSUFFICIENT");
    assertThat(row.get("tenant_id", Long.class)).isEqualTo(DEFAULT_TEST_TENANT_ID);
  }

  /** 같은 키는 1분 창 안에서 1건, 창이 지나면 다시 1건. */
  @Test
  void sameDenialWithinOneMinute_isCoalesced_andRecordedAgainAfterWindow() {
    recorder.recordDenial(actor, AccessDenialAction.VIEW, "NOT_ON_ALLOWLIST", sensitive, null);
    nanos.addAndGet(Duration.ofSeconds(59).toNanos());
    recorder.recordDenial(actor, AccessDenialAction.VIEW, "NOT_ON_ALLOWLIST", sensitive, null);
    assertThat(count("DATASET_ACCESS_DENIED")).isEqualTo(1);
    nanos.addAndGet(Duration.ofSeconds(2).toNanos());
    recorder.recordDenial(actor, AccessDenialAction.VIEW, "NOT_ON_ALLOWLIST", sensitive, null);
    assertThat(count("DATASET_ACCESS_DENIED")).isEqualTo(2);
  }

  /** 사유나 동작이 다르면 다른 키다 — 합치지 않는다. */
  @Test
  void differentReasonOrAction_isNotCoalesced() {
    recorder.recordDenial(actor, AccessDenialAction.VIEW, "NOT_ON_ALLOWLIST", sensitive, null);
    recorder.recordDenial(
        actor, AccessDenialAction.VIEW, "CLEARANCE_INSUFFICIENT", sensitive, null);
    recorder.recordDenial(actor, AccessDenialAction.EXPORT, "NOT_ON_ALLOWLIST", sensitive, null);
    assertThat(count("DATASET_ACCESS_DENIED")).isEqualTo(3);
  }

  /** 접근 기록은 audit_access 등급('민감')만 남고 '공개'는 남지 않으며, 1분 안 반복은 합쳐진다. */
  @Test
  void access_isRecordedOnlyForAuditAccessLevels_andCoalesced() {
    recorder.recordAccess(
        actor, SecurityAuditRecorder.AccessKind.ROW_VIEW, List.of(sensitive, open));
    recorder.recordAccess(actor, SecurityAuditRecorder.AccessKind.ROW_VIEW, List.of(sensitive));
    assertThat(count("DATASET_ACCESS")).isEqualTo(1);
    String rid =
        inTenantFixture(
            () ->
                dsl.fetchOne(
                        "SELECT resource_id FROM audit_log WHERE user_id = ? AND action_type = 'DATASET_ACCESS'",
                        actor)
                    .get(0, String.class));
    assertThat(rid).isEqualTo(String.valueOf(sensitive));
  }

  /** 이 테스트의 기록기(빈이 아니라 직접 만든 것)가 실행기로 넘긴 기록을 모두 쓸 때까지 기다린다. */
  private void awaitRecorder() {
    try {
      assertThat(recorder.awaitIdle(Duration.ofSeconds(30))).isTrue();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }

  /**
   * 풀 교착 방지(code-review 2) — 바깥 트랜잭션이 커넥션을 쥔 채 판정 경로가 감사를 부를 때 같은 스레드에서 커넥션을 하나 더 잡지 않는다. 테스트 풀은 최대
   * 2개(application-test.yml)라, 바깥 트랜잭션 1개 + 여기서 직접 쥔 1개로 풀을 다 채운 뒤 감사를 부른다. 두 번째 커넥션을 기다리는 구현(예전
   * REQUIRES_NEW 동기 기록)이면 Hikari connection-timeout(기본 30초)까지 막힌다. 기록은 바깥 트랜잭션이 끝난 뒤(롤백이어도) 실행기가
   * 쓴다.
   */
  @Test
  void auditInsideOuterTransaction_doesNotWaitForSecondConnection_andIsWrittenAfterCompletion()
      throws Exception {
    TransactionTemplate caller = new TransactionTemplate(txManager);
    long[] elapsedMs = new long[1];
    try (Connection held = dataSource.getConnection()) {
      caller.executeWithoutResult(
          s -> {
            long t0 = System.nanoTime();
            recorder.recordDenial(
                actor, AccessDenialAction.VIEW, "CLEARANCE_INSUFFICIENT", sensitive, null);
            recorder.recordAccess(
                actor, SecurityAuditRecorder.AccessKind.SQL, List.of(sensitive, open));
            elapsedMs[0] = (System.nanoTime() - t0) / 1_000_000;
            s.setRollbackOnly();
          });
      assertThat(held.isValid(1)).isTrue();
    }
    // 커넥션을 더 기다렸다면 수 초~30초가 걸린다. 호출 스레드는 큐에 넣기만 한다.
    assertThat(elapsedMs[0]).isLessThan(3_000);
    assertThat(count("DATASET_ACCESS_DENIED")).isEqualTo(1);
    assertThat(count("DATASET_ACCESS")).isEqualTo(1);
  }

  /**
   * WD-682 — 바깥 트랜잭션·동기화 범위가 없는 호출(데이터셋 /query 의 허가 전 앞 판정이 거부하는 경로)에서도 쓰기는 호출 스레드가 아니라
   * security-audit-writer 실행기 스레드가 한다 — 호출 스레드는 감사 쓰기 커넥션을 열지 않는다. 쓰기 대역(AuditLogService mock)이 실제로
   * 불린 스레드를 기록해 결정적으로 본다(벽시계·풀 고갈 없이). 호출 스레드에서 자기 트랜잭션으로 쓰던 구현이면 기록된 스레드가 테스트 스레드다.
   */
  @Test
  void auditWithoutOuterTransaction_isWrittenOnWriterThread_notCallerThread() throws Exception {
    AuditLogService auditMock = mock(AuditLogService.class);
    when(auditMock.currentOrigin()).thenReturn(AuditLogService.RequestOrigin.NONE);
    List<String> writeThreads = new CopyOnWriteArrayList<>();
    doAnswer(
            inv -> {
              writeThreads.add(Thread.currentThread().getName());
              return 1L;
            })
        .when(auditMock)
        .log(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    SecurityAuditRecorder isolated = newRecorder(auditMock, levelRepository);
    try {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      isolated.recordDenial(
          actor, AccessDenialAction.VIEW, "CLEARANCE_INSUFFICIENT", sensitive, null);
      isolated.recordAccess(actor, SecurityAuditRecorder.AccessKind.SQL, List.of(sensitive));
      assertThat(isolated.awaitIdle(Duration.ofSeconds(30))).isTrue();
    } finally {
      isolated.shutdown();
    }
    // 거부 1건 + 감사 등급(민감) 접근 1건, 둘 다 실행기 스레드에서.
    assertThat(writeThreads)
        .hasSize(2)
        .allMatch("security-audit-writer"::equals)
        .doesNotContain(Thread.currentThread().getName());
  }

  /**
   * WD-682 리뷰 — 실행기 스레드에는 요청 속성이 없으므로, 거부·접근 감사 행의 IP·User-Agent(WD-394)는 호출 시점에 요청 스레드에서 읽어 넘겨야
   * 한다. 바깥 범위 없는 갈래(바로 실행기)와 바깥 트랜잭션 완료 후 갈래(afterCompletion → 실행기) 모두 요청의 IP·UA 가 남아야 한다.
   */
  @Test
  void asyncAuditRows_keepRequestIpAndUserAgent() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("203.0.113.7");
    request.addHeader("User-Agent", "wd682-agent/1.0");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    try {
      // 바깥 범위 없음 — 바로 실행기로.
      recorder.recordDenial(actor, AccessDenialAction.VIEW, "NOT_ON_ALLOWLIST", sensitive, null);
      recorder.recordAccess(actor, SecurityAuditRecorder.AccessKind.SQL, List.of(sensitive));
      // 바깥 트랜잭션 — 완료 후 실행기로(사유가 달라 합치지 않는다).
      new TransactionTemplate(txManager)
          .executeWithoutResult(
              s ->
                  recorder.recordDenial(
                      actor, AccessDenialAction.VIEW, "CLEARANCE_INSUFFICIENT", sensitive, null));
    } finally {
      RequestContextHolder.resetRequestAttributes();
    }
    assertThat(count("DATASET_ACCESS_DENIED")).isEqualTo(2);
    assertThat(count("DATASET_ACCESS")).isEqualTo(1);
    var rows =
        inTenantFixture(
            () ->
                dsl.fetch(
                    "SELECT ip_address, user_agent FROM audit_log WHERE user_id = ?"
                        + " AND action_type IN ('DATASET_ACCESS_DENIED', 'DATASET_ACCESS')",
                    actor));
    assertThat(rows).hasSize(3);
    rows.forEach(
        r -> {
          assertThat(r.get("ip_address", String.class)).isEqualTo("203.0.113.7");
          assertThat(r.get("user_agent", String.class)).isEqualTo("wd682-agent/1.0");
        });
  }

  /** 첫 log 호출에서 실행기 스레드를 붙잡는 감사 쓰기 대역 — 그동안 들어온 기록은 큐에 쌓인다. 쓴 기록은 "action:resourceId" 로 모은다. */
  private static final class HoldingAudit {
    final AuditLogService mock = mock(AuditLogService.class);
    final List<String> written = new CopyOnWriteArrayList<>();
    final CountDownLatch writerHeld = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    private final AtomicBoolean first = new AtomicBoolean(true);

    HoldingAudit() {
      when(mock.currentOrigin()).thenReturn(AuditLogService.RequestOrigin.NONE);
      doAnswer(
              inv -> {
                if (first.compareAndSet(true, false)) {
                  writerHeld.countDown();
                  try {
                    release.await(30, TimeUnit.SECONDS);
                  } catch (InterruptedException e) {
                    // 종료(shutdownNow)의 인터럽트 — 붙잡은 기록만 마저 쓰고 끝낸다.
                    Thread.currentThread().interrupt();
                  }
                }
                written.add(inv.getArgument(2) + ":" + inv.getArgument(4));
                return 1L;
              })
          .when(mock)
          .log(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    void awaitHeld() throws InterruptedException {
      assertThat(writerHeld.await(30, TimeUnit.SECONDS)).isTrue();
    }
  }

  /** 모든 데이터셋을 감사 등급으로 보는 등급 저장소 대역 — 버리지 않은 접근은 실제로 쓰여야 단언이 의미 있다. */
  private static SecurityLevelRepository allAudited() {
    SecurityLevelRepository levels = mock(SecurityLevelRepository.class);
    when(levels.findAuditedDatasetIds(any()))
        .thenAnswer(inv -> Set.copyOf(inv.<Collection<Long>>getArgument(0)));
    return levels;
  }

  /** 종류별 버림 카운터 값. */
  private static double dropped(MeterRegistry registry, String kind) {
    return registry.counter(SecurityAuditMetrics.DROPPED, "kind", kind).count();
  }

  /** 넘침 카운터 값. */
  private static double overflowed(MeterRegistry registry) {
    return registry.counter(SecurityAuditMetrics.OVERFLOW).count();
  }

  /**
   * WD-687 — 큐 포화 정책. 소프트 상한 2(하드 상한 4)에서: 접근 감사는 소프트 상한에서 버리고 {@code
   * security_audit_dropped_total{kind=access}} 를 올린다. 거부 감사는 하드 상한까지 받고({@code
   * security_audit_overflow_total}), 하드 상한이 차면 거부도 버리고 {@code dropped{kind=denial}} 을 올린다(무제한 큐로
   * 메모리를 다 쓰지 않게). 실행기 스레드를 첫 기록에서 붙잡아 큐를 결정적으로 채운다. 버린 기록은 합치기 키가 지워져 다음 호출에서 다시 기록된다.
   */
  @Test
  void queueFull_accessDroppedAtSoftCap_denialAcceptedUntilHardCap_thenDropped() throws Exception {
    HoldingAudit audit = new HoldingAudit();
    MeterRegistry registry = new SimpleMeterRegistry();
    SecurityAuditRecorder small =
        newRecorder(audit.mock, allAudited(), registry, 2, Duration.ofSeconds(10));
    try {
      // 1) 거부 1건이 실행기 스레드를 붙잡는다(큐는 비어 있음).
      small.recordDenial(actor, AccessDenialAction.VIEW, "R0", sensitive, null);
      audit.awaitHeld();
      // 2) 접근 2건으로 큐를 소프트 상한(2)까지 채운다.
      small.recordAccess(actor, SecurityAuditRecorder.AccessKind.SQL, List.of(101L));
      small.recordAccess(actor, SecurityAuditRecorder.AccessKind.SQL, List.of(102L));
      // 3) 소프트 상한 — 접근은 버린다.
      small.recordAccess(actor, SecurityAuditRecorder.AccessKind.SQL, List.of(103L));
      // 4) 소프트 상한 위 — 거부는 하드 상한(4)까지 받는다.
      small.recordDenial(actor, AccessDenialAction.VIEW, "R1", sensitive, null);
      small.recordDenial(actor, AccessDenialAction.VIEW, "R2", sensitive, null);
      // 5) 하드 상한 — 거부도 버린다.
      small.recordDenial(actor, AccessDenialAction.VIEW, "R3", sensitive, null);

      assertThat(dropped(registry, "access")).isEqualTo(1.0);
      assertThat(overflowed(registry)).isEqualTo(2.0);
      assertThat(dropped(registry, "denial")).isEqualTo(1.0);

      audit.release.countDown();
      assertThat(small.awaitIdle(Duration.ofSeconds(30))).isTrue();
      assertThat(audit.written)
          .containsExactlyInAnyOrder(
              "DATASET_ACCESS_DENIED:" + sensitive,
              "DATASET_ACCESS:101",
              "DATASET_ACCESS:102",
              "DATASET_ACCESS_DENIED:" + sensitive,
              "DATASET_ACCESS_DENIED:" + sensitive);

      // 버린 접근(103)·거부(R3)는 합치기 키가 지워졌다 — 큐가 비면 같은 1분 안이어도 다시 기록된다.
      small.recordAccess(actor, SecurityAuditRecorder.AccessKind.SQL, List.of(103L));
      small.recordDenial(actor, AccessDenialAction.VIEW, "R3", sensitive, null);
      assertThat(small.awaitIdle(Duration.ofSeconds(30))).isTrue();
      assertThat(audit.written).contains("DATASET_ACCESS:103").hasSize(7);
    } finally {
      audit.release.countDown();
      small.shutdown();
    }
  }

  /**
   * WD-687 — 종료 시 비우지 못한 기록(shutdownNow 가 돌려준 미실행 작업)도 종류별 버림 카운터에 들어가고, 대기 수가 정리돼 awaitIdle 이 멈추지
   * 않는다. 실행기를 붙잡은 채 종료 대기를 짧게 줘 비우기 시한을 넘긴다.
   */
  @Test
  void shutdownDiscards_areCountedPerKind() throws Exception {
    HoldingAudit audit = new HoldingAudit();
    MeterRegistry registry = new SimpleMeterRegistry();
    SecurityAuditRecorder small =
        newRecorder(
            audit.mock,
            allAudited(),
            registry,
            SecurityAuditRecorder.QUEUE_CAPACITY,
            Duration.ofMillis(100));
    try {
      // 실행 중(붙잡힘) 거부 1 + 대기 중 거부 1·접근 2.
      small.recordDenial(actor, AccessDenialAction.VIEW, "S0", sensitive, null);
      audit.awaitHeld();
      small.recordDenial(actor, AccessDenialAction.VIEW, "S1", sensitive, null);
      small.recordAccess(actor, SecurityAuditRecorder.AccessKind.SQL, List.of(201L));
      small.recordAccess(actor, SecurityAuditRecorder.AccessKind.SQL, List.of(202L));

      small.shutdown();

      assertThat(dropped(registry, "denial")).isEqualTo(1.0);
      assertThat(dropped(registry, "access")).isEqualTo(2.0);
      // 붙잡힌 기록은 인터럽트로 풀려 끝난다 — 버린 3건은 대기 수에서 빠져 awaitIdle 이 끝난다.
      assertThat(small.awaitIdle(Duration.ofSeconds(30))).isTrue();
      assertThat(audit.written).containsExactly("DATASET_ACCESS_DENIED:" + sensitive);
    } finally {
      audit.release.countDown();
      small.shutdown();
    }
  }
}
