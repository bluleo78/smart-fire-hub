package com.smartfirehub.securitylevel.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.securitylevel.access.AccessDenialAction;
import com.smartfirehub.securitylevel.repository.SecurityLevelRepository;
import com.smartfirehub.user.dto.UserResponse;
import com.smartfirehub.user.repository.UserRepository;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 보안 등급 감사 기록의 단일 진입점(스펙 §4.6). 흐름 B 소유 — 다른 흐름은 감사 호출을 직접 넣지 않고 가드·게이트를 거친다.
 *
 * <ul>
 *   <li>{@link #record} — 관리 작업(등급 변경·허용 목록 변경 등). 호출자 트랜잭션에 합류해 변경과 감사가 함께 커밋/롤백된다.
 *   <li>{@link #recordDenial}·{@link #recordAccess} — 접근 거부와 감사 등급 접근. 요청이 403/404 로 끝나 호출자 트랜잭션이
 *       롤백돼도 남아야 하므로 호출자 트랜잭션과 <b>분리된</b> 자기 트랜잭션으로 쓴다. 같은 키는 1분에 1건(보충 스펙 §3 폭주 방지) — 인스턴스 메모리
 *       기준이다(다중 인스턴스면 인스턴스 수만큼 중복될 수 있다).
 * </ul>
 *
 * <p><b>커넥션 1개 원칙(풀 교착 방지).</b> 바깥 트랜잭션(또는 트랜잭션 동기화 범위)이 커넥션을 쥔 채 같은 스레드에서 REQUIRES_NEW 로 커넥션을 하나 더
 * 잡으면, 풀 크기만큼 요청이 동시에 판정 경로에 들어왔을 때 모두가 두 번째 커넥션을 기다리며 서로 막힌다. 그래서:
 *
 * <ul>
 *   <li>동기화 범위가 없으면(커넥션을 쥔 바깥 범위 없음) 호출 스레드에서 즉시 자기 트랜잭션으로 쓴다 — 커넥션 1개.
 *   <li>있으면 기록 작업을 그 범위의 <b>완료 후</b>(afterCompletion, 커밋·롤백 무관)에 전용 단일 스레드 실행기로 넘긴다.
 *       afterCompletion 에서 동기로 쓰면 안 된다 — Spring 은 afterCompletion 콜백을 부른 <b>뒤</b>에 바깥 커넥션을
 *       반납하므로(spring-tx 6.2.1 AbstractPlatformTransactionManager.processCommit:
 *       triggerAfterCompletion → finally cleanupAfterCompletion) 그 자리에서 쓰면 여전히 커넥션 2개다. 실행기 스레드는
 *       커넥션을 하나만 쥐고 다른 커넥션을 기다리지 않는다.
 *   <li>실행기 큐가 차거나(유한 큐) 종료 중이면 호출자를 막지 않고 버린다(WARN + 누적 건수). 프로세스 크래시 때 큐에 있던 기록은 유실될 수 있다.
 * </ul>
 *
 * <p>거부·접근 감사 실패는 요청을 실패시키지 않는다(경고 로그만) — 감사는 판정이 아니라 기록이다. 실패·버림이면 합치기 키를 지워 다음 호출이 다시 시도한다.
 */
@Component
@Slf4j
public class SecurityAuditRecorder {

  /** 접근 거부 감사 action_type. */
  public static final String ACCESS_DENIED_ACTION = "DATASET_ACCESS_DENIED";

  /** 감사 등급(audit_access) 접근 기록 action_type. */
  public static final String ACCESS_ACTION = "DATASET_ACCESS";

  /** 같은 거부·접근을 하나로 합치는 창. */
  static final Duration COALESCE_WINDOW = Duration.ofMinutes(1);

  /** 비동기 기록 큐 상한 — 넘치면 버리고 WARN(호출자 요청을 막지 않는다). */
  static final int QUEUE_CAPACITY = 10_000;

  /** 종료 시 남은 기록을 비우는 최대 대기. */
  private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(10);

  /** 감사 등급 접근의 종류(스펙 §4.6 "audit_access 등급만"). */
  public enum AccessKind {
    ROW_VIEW,
    SQL,
    PIPELINE,
    AI
  }

  private final AuditLogService auditLogService;
  private final UserRepository userRepository;
  private final SecurityLevelRepository levelRepository;

  /** 호출자 트랜잭션과 분리된 새 트랜잭션 — 호출자 롤백에 감사 행이 휩쓸리지 않게 한다. 바깥 커넥션이 없는 스레드에서만 연다. */
  private final TransactionTemplate ownTx;

  /** 최근 1분 안에 기록한 키(값은 의미 없음). 키에 테넌트를 넣어 테넌트 간 합치기를 막는다. */
  private final Cache<String, Boolean> recent;

  /** 바깥 범위 완료 후의 기록을 처리하는 단일 스레드 실행기(유한 큐, 포화 시 예외 → 버림). */
  private final ThreadPoolExecutor writer;

  /** 실행기에 넘겼으나 아직 끝나지 않은 기록 수 — {@link #awaitIdle} 가 기다리는 대상. {@code this} 모니터로 보호한다. */
  private int pending;

  /** 큐 포화·종료로 버린 기록 누적 수(WARN 로그에 함께 남긴다). */
  private final AtomicLong dropped = new AtomicLong();

  /** 운영용 — 시스템 시계로 1분 창을 잰다. */
  @Autowired
  public SecurityAuditRecorder(
      AuditLogService auditLogService,
      UserRepository userRepository,
      SecurityLevelRepository levelRepository,
      PlatformTransactionManager txManager) {
    this(auditLogService, userRepository, levelRepository, txManager, Ticker.systemTicker());
  }

  /** 테스트용 — 시계를 주입해 1분 창을 테스트 안에서 넘긴다. */
  SecurityAuditRecorder(
      AuditLogService auditLogService,
      UserRepository userRepository,
      SecurityLevelRepository levelRepository,
      PlatformTransactionManager txManager,
      Ticker ticker) {
    this.auditLogService = auditLogService;
    this.userRepository = userRepository;
    this.levelRepository = levelRepository;
    TransactionTemplate t = new TransactionTemplate(txManager);
    t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.ownTx = t;
    this.recent =
        Caffeine.newBuilder()
            .expireAfterWrite(COALESCE_WINDOW)
            .maximumSize(100_000)
            .ticker(ticker)
            .build();
    // AbortPolicy(기본) — 포화 시 RejectedExecutionException 을 받아 버림 처리한다(CallerRuns 면 호출자를 다시 막는다).
    this.writer =
        new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY),
            r -> {
              Thread th = new Thread(r, "security-audit-writer");
              th.setDaemon(true);
              return th;
            });
  }

  /** 관리 작업 감사. 호출자 트랜잭션에 합류하므로 변경과 감사가 함께 커밋/롤백된다(기존 계약 그대로). */
  public void record(
      long actorUserId,
      String action,
      String resource,
      String resourceId,
      String description,
      Map<String, Object> metadata) {
    auditLogService.log(
        actorUserId,
        actorName(actorUserId),
        action,
        resource,
        resourceId,
        description,
        null,
        null,
        "SUCCESS",
        null,
        metadata);
  }

  /**
   * 접근 거부 1건. 실제 사유(404 로 가려진 경우에도)를 metadata 에 남긴다 — 감사 화면은 관리자 전용이라 테이블명을 실어도 된다.
   *
   * <p>테넌트 컨텍스트가 없으면 기록하지 않는다 — tenant_id DEFAULT 가 GUC 에서 오므로 쓸 곳이 없다.
   *
   * @param datasetId 거부된 데이터셋(매핑 없는 테이블·다른 스키마면 null)
   * @param tableName SQL 경로에서 거부를 일으킨 테이블 이름(그 외 null)
   */
  public void recordDenial(
      long actorUserId,
      AccessDenialAction action,
      String reasonCode,
      Long datasetId,
      String tableName) {
    Long tenantId = TenantContext.get();
    if (tenantId == null || actorUserId <= 0) {
      return;
    }
    // 합치기 키: (테넌트, 사용자, 동작, 데이터셋, 테이블, 사유)
    String key =
        String.join(
            "|",
            "D",
            tenantId.toString(),
            Long.toString(actorUserId),
            action.name(),
            String.valueOf(datasetId),
            String.valueOf(tableName),
            String.valueOf(reasonCode));
    if (recent.asMap().putIfAbsent(key, Boolean.TRUE) != null) {
      return;
    }
    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("action", action.name());
    meta.put("reason", reasonCode);
    if (tableName != null) {
      meta.put("tableName", tableName);
    }
    dispatch(
        tenantId,
        () ->
            auditLogService.log(
                actorUserId,
                actorName(actorUserId),
                ACCESS_DENIED_ACTION,
                "dataset",
                datasetId == null ? null : String.valueOf(datasetId),
                action.name() + " 거부: " + reasonCode,
                null,
                null,
                "FAILURE",
                null,
                meta),
        List.of(key),
        "접근 거부");
  }

  /**
   * 감사 등급(audit_access) 데이터셋 접근 기록. 등급 조회 전에 합치기 키를 먼저 본다 — 같은 1분 안의 반복 접근은 DB 를 다시 보지 않는다(감사 등급이
   * 아닌 데이터셋도 1분간 건너뛴다. 그 1분 안에 감사 등급으로 바뀐 데이터셋의 첫 접근은 빠질 수 있다).
   */
  public void recordAccess(long actorUserId, AccessKind kind, Collection<Long> datasetIds) {
    Long tenantId = TenantContext.get();
    if (tenantId == null || actorUserId <= 0 || datasetIds.isEmpty()) {
      return;
    }
    // 1분 안에 처음 보는 데이터셋만 추린다(id → 합치기 키).
    Map<Long, String> fresh = new LinkedHashMap<>();
    for (Long id : datasetIds) {
      if (id == null) {
        continue;
      }
      String key =
          String.join(
              "|",
              "A",
              tenantId.toString(),
              Long.toString(actorUserId),
              kind.name(),
              id.toString());
      if (recent.asMap().putIfAbsent(key, Boolean.TRUE) == null) {
        fresh.put(id, key);
      }
    }
    if (fresh.isEmpty()) {
      return;
    }
    // 감사 등급 판정(findAuditedDatasetIds)도 기록 작업 안에서 한다 — 바깥 범위가 있으면 그 완료 후 실행기 스레드의 커넥션 하나로.
    dispatch(
        tenantId,
        () -> {
          Set<Long> audited = levelRepository.findAuditedDatasetIds(fresh.keySet());
          String name = actorName(actorUserId);
          for (Long id : fresh.keySet()) {
            // 감사 등급인 데이터셋만 남긴다(스펙 §4.6).
            if (audited.contains(id)) {
              auditLogService.log(
                  actorUserId,
                  name,
                  ACCESS_ACTION,
                  "dataset",
                  String.valueOf(id),
                  kind.name(),
                  null,
                  null,
                  "SUCCESS",
                  null,
                  Map.of("kind", kind.name()));
            }
          }
        },
        List.copyOf(fresh.values()),
        "감사 등급 접근");
  }

  /**
   * 기록 작업을 커넥션 1개 원칙에 맞게 실행한다(클래스 설명 참고).
   *
   * <p>판단 기준은 {@code isSynchronizationActive} 다 — 실제 트랜잭션이 없어도(SUPPORTS 등) 동기화 범위 안에서 jOOQ 가 얻은
   * 커넥션은 범위가 끝날 때까지 스레드에 묶여 있을 수 있으므로, 동기화 범위가 있으면 실제 트랜잭션 여부와 무관하게 완료 후로 미룬다.
   *
   * @param keys 실패·버림 시 지울 합치기 키(다음 호출이 다시 시도하게)
   */
  private void dispatch(long tenantId, Runnable write, List<String> keys, String what) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
              // 커밋·롤백 무관 — 거부 감사는 요청이 롤백돼도 남아야 한다. 여기서 동기로 쓰지 않고 실행기로 넘긴다.
              submit(tenantId, write, keys, what);
            }
          });
    } else if (TransactionSynchronizationManager.isActualTransactionActive()) {
      // 동기화 없는 실제 트랜잭션(SYNCHRONIZATION_NEVER) — 완료 시점을 알 수 없으니 바로 실행기로 넘긴다(이 스레드는 기다리지 않는다).
      submit(tenantId, write, keys, what);
    } else {
      // 바깥 커넥션이 없다 — 호출 스레드(테넌트 컨텍스트가 이미 서 있음)에서 즉시 자기 트랜잭션으로 쓴다.
      writeNow(write, keys, what);
    }
  }

  /** 실행기에 기록을 넘긴다. 큐 포화·종료면 버리고 WARN(호출자를 막지 않는다). 워커는 테넌트 컨텍스트를 다시 세워 GUC 가 심기게 한다. */
  private void submit(long tenantId, Runnable write, List<String> keys, String what) {
    synchronized (this) {
      pending++;
    }
    try {
      writer.execute(
          () -> {
            try {
              // runScoped 는 진입 전 값(워커 스레드는 null)으로 되돌린다 — 다음 작업에 테넌트가 새지 않는다.
              TenantContext.runScoped(tenantId, () -> writeNow(write, keys, what));
            } finally {
              finishOne();
            }
          });
    } catch (RejectedExecutionException e) {
      finishOne();
      keys.forEach(recent::invalidate);
      log.warn(
          "{} 감사 기록을 버렸다 — 큐 포화 또는 종료 중(누적 {}건). 요청 처리는 그대로 진행한다", what, dropped.incrementAndGet());
    }
  }

  /** 자기 트랜잭션으로 쓴다. 실패하면 합치기 키를 지우고 WARN(요청은 실패시키지 않는다). */
  private void writeNow(Runnable write, List<String> keys, String what) {
    try {
      ownTx.executeWithoutResult(s -> write.run());
    } catch (RuntimeException e) {
      keys.forEach(recent::invalidate);
      log.warn("{} 감사 기록 실패 — 요청 처리는 그대로 진행한다", what, e);
    }
  }

  private synchronized void finishOne() {
    pending--;
    if (pending == 0) {
      notifyAll();
    }
  }

  /**
   * 실행기에 넘긴 기록이 모두 끝날 때까지 최대 {@code timeout} 기다린다. <b>테스트 결정성용 seam</b> — 감사 행을 단언하는 테스트가 단언 전에 부른다
   * (IntegrationTestBase.awaitSecurityAudit). 운영 코드는 부르지 않는다.
   *
   * @return 시간 안에 모두 끝났으면 true
   */
  public synchronized boolean awaitIdle(Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (pending > 0) {
      long left = deadline - System.nanoTime();
      if (left <= 0) {
        return false;
      }
      TimeUnit.NANOSECONDS.timedWait(this, left);
    }
    return true;
  }

  /** 애플리케이션 종료 시 큐에 남은 기록을 제한 시간 안에 비운다. 넘기면 남은 작업을 버리고 WARN. */
  @PreDestroy
  void shutdown() {
    writer.shutdown();
    try {
      if (!writer.awaitTermination(DRAIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
        int left = writer.shutdownNow().size();
        log.warn("종료 시 감사 기록 {}건을 비우지 못하고 버렸다", left);
      }
    } catch (InterruptedException e) {
      writer.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  /** 감사 행의 사용자 이름(없으면 "unknown"). */
  private String actorName(long actorUserId) {
    return userRepository.findById(actorUserId).map(UserResponse::username).orElse("unknown");
  }
}
