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
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
    recorder =
        new SecurityAuditRecorder(
            auditLogService, userRepository, levelRepository, txManager, nanos::get);
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
    SecurityAuditRecorder isolated =
        new SecurityAuditRecorder(
            auditMock, userRepository, levelRepository, txManager, nanos::get);
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
}
