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
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
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
 *       롤백돼도 남아야 하므로 호출자 트랜잭션에 합류하지 않고 전용 실행기 스레드가 자기 트랜잭션으로 쓴다(아래). 접속 정보(IP·User-Agent)는 실행기 스레드에
 *       요청이 없으므로 호출 시점에 요청 스레드에서 읽어 넘긴다. 같은 키는 1분에 1건(보충 스펙 §3 폭주 방지) — 인스턴스 메모리 기준이다(다중 인스턴스면 인스턴스
 *       수만큼 중복될 수 있다).
 * </ul>
 *
 * <p><b>커넥션 1개 원칙(풀 교착 방지).</b> 바깥 트랜잭션(또는 트랜잭션 동기화 범위)이 커넥션을 쥔 채 같은 스레드에서 REQUIRES_NEW 로 커넥션을 하나 더
 * 잡으면, 풀 크기만큼 요청이 동시에 판정 경로에 들어왔을 때 모두가 두 번째 커넥션을 기다리며 서로 막힌다. 그래서 <b>호출 스레드는 감사 쓰기 커넥션을 열지 않는다</b>
 * — 쓰기는 언제나 전용 단일 스레드 실행기가 한다:
 *
 * <ul>
 *   <li>동기화 범위가 없으면(커넥션을 쥔 바깥 범위 없음) 바로 실행기로 넘긴다. 호출 스레드에서 쓰면 바깥 커넥션은 없어도, 허가(게이트) 전에 판정하는 경로(데이터셋
 *       /query 앞 판정, WD-638)의 거부 요청이 감사 쓰기마다 풀 커넥션을 잡아 — 게이트가 가득 찬 동안 거부 요청이 몰리면 허가를 쥔 실행이 쓸 커넥션까지
 *       빼앗는다(WD-682). 실행기는 스레드 하나라 감사 쓰기는 커넥션을 최대 1개만 쓴다.
 *   <li>있으면 기록 작업을 그 범위의 <b>완료 후</b>(afterCompletion, 커밋·롤백 무관)에 전용 단일 스레드 실행기로 넘긴다.
 *       afterCompletion 에서 동기로 쓰면 안 된다 — Spring 은 afterCompletion 콜백을 부른 <b>뒤</b>에 바깥 커넥션을
 *       반납하므로(spring-tx 6.2.1 AbstractPlatformTransactionManager.processCommit:
 *       triggerAfterCompletion → finally cleanupAfterCompletion) 그 자리에서 쓰면 여전히 커넥션 2개다. 실행기 스레드는
 *       커넥션을 하나만 쥐고 다른 커넥션을 기다리지 않는다.
 *   <li><b>큐 포화 정책(WD-687)</b> — 상한이 둘이다. 접근 감사는 대기 건수(실행 중 포함)가 소프트 상한({@link #QUEUE_CAPACITY})을
 *       넘으면 버린다. 거부 감사는 그 위의 여유분까지, 실행기의 유한 큐(하드 상한 = 소프트 × {@link #HARD_CAPACITY_FACTOR})가 찰 때까지
 *       받는다 — 접근 감사보다 오래 살아남지만 무제한은 아니다. 합치기 키에 사용자 입력(SQL 의 테이블 이름·사유)이 들어가고 합치기 캐시도 크기 상한에서 일찍
 *       밀려나므로, DB 가 느린 동안 서로 다른 키가 몰리면 무제한 큐는 메모리를 다 쓸 수 있다. 호출 스레드에서 동기로 쓰는 대안은 afterCompletion
 *       갈래에서 바깥 커넥션을 쥔 채 커넥션을 하나 더 잡아 위의 풀 교착을 되살리므로 쓰지 않는다.
 *   <li>버림(소프트·하드 상한, 종료 중 거절, 종료 시 비우지 못함)과 넘침(소프트 상한을 넘어 받아들인 거부)은 {@link SecurityAuditMetrics}
 *       카운터로 보이고, WARN 은 각각 1분에 1번으로 묶는다(건마다 남기면 포화 때 로그가 넘친다). 프로세스 크래시 때 큐에 있던 기록은 유실될 수 있다.
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

  /** 비동기 기록 대기의 소프트 상한 — 접근 감사가 버려지는 지점(클래스 설명의 큐 포화 정책). */
  static final int QUEUE_CAPACITY = 10_000;

  /** 하드 상한(실행기 큐 크기) = 소프트 상한 × 이 값 — 거부 감사의 여유분(클래스 설명의 큐 포화 정책). */
  static final int HARD_CAPACITY_FACTOR = 2;

  /** 버림·넘침 WARN 을 묶는 간격(1분) — 이 간격 안의 건수는 카운터로만 센다. */
  private static final long WARN_INTERVAL_NANOS = Duration.ofMinutes(1).toNanos();

  /** 종료 시 남은 기록을 비우는 최대 대기. */
  private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(10);

  /** 비동기 기록의 종류 — 어느 상한에서 버리는지(접근: 소프트, 거부: 하드)를 가르고, 메트릭 태그·로그 이름이 된다. */
  enum Kind {
    DENIAL("denial", "접근 거부"),
    ACCESS("access", "감사 등급 접근");

    /** 메트릭 태그 값. */
    final String tag;

    /** 로그에 쓰는 이름. */
    final String label;

    Kind(String tag, String label) {
      this.tag = tag;
      this.label = label;
    }
  }

  /** 감사 등급 접근의 종류(스펙 §4.6 "audit_access 등급만"). */
  public enum AccessKind {
    ROW_VIEW,
    SQL,
    PIPELINE,
    AI
  }

  /**
   * 실행기에 넘기는 기록 작업. 종류·키를 들고 있어 거절 처리기와 종료 시 미실행 작업 정리가 종류별로 세고 합치기 키를 지울 수 있다. 실행하면 테넌트 컨텍스트를 다시
   * 세워(GUC) 쓰고 대기 수를 줄인다.
   */
  private final class AuditTask implements Runnable {
    final long tenantId;
    final Runnable write;
    final List<String> keys;
    final Kind kind;

    /** 거절 처리기가 표시한다 — execute 를 부른 호출 스레드에서만 읽고 쓴다. */
    boolean rejected;

    AuditTask(long tenantId, Runnable write, List<String> keys, Kind kind) {
      this.tenantId = tenantId;
      this.write = write;
      this.keys = keys;
      this.kind = kind;
    }

    @Override
    public void run() {
      try {
        // runScoped 는 진입 전 값(워커 스레드는 null)으로 되돌린다 — 다음 작업에 테넌트가 새지 않는다.
        TenantContext.runScoped(tenantId, () -> writeNow(write, keys, kind));
      } finally {
        finishOne();
      }
    }
  }

  private final AuditLogService auditLogService;
  private final UserRepository userRepository;
  private final SecurityLevelRepository levelRepository;
  private final SecurityAuditMetrics metrics;

  /** 실행기 스레드가 기록마다 여는 트랜잭션 — 그 스레드엔 바깥 트랜잭션이 없지만 합류를 막는 안전장치로 REQUIRES_NEW 를 둔다. */
  private final TransactionTemplate ownTx;

  /** 최근 1분 안에 기록한 키(값은 의미 없음). 키에 테넌트를 넣어 테넌트 간 합치기를 막는다. */
  private final Cache<String, Boolean> recent;

  /** 모든 거부·접근 기록을 처리하는 단일 스레드 실행기(하드 상한 크기의 유한 큐). */
  private final ThreadPoolExecutor writer;

  /** 접근 감사의 소프트 상한(운영 {@link #QUEUE_CAPACITY}, 테스트는 작게). */
  private final int queueCapacity;

  /** 종료 시 남은 기록을 비우는 최대 대기(운영 {@link #DRAIN_TIMEOUT}, 테스트는 짧게). */
  private final Duration drainTimeout;

  /** 마지막 버림 WARN 시각(nanoTime). */
  private final AtomicLong lastDropWarnNanos =
      new AtomicLong(System.nanoTime() - WARN_INTERVAL_NANOS);

  /** 마지막 넘침 WARN 시각(nanoTime). */
  private final AtomicLong lastOverflowWarnNanos =
      new AtomicLong(System.nanoTime() - WARN_INTERVAL_NANOS);

  /**
   * 실행기에 넘겼으나 아직 끝나지 않은 기록 수(실행 중 포함) — 소프트 상한 판정과 {@link #awaitIdle} 가 본다. {@code this} 모니터로 보호한다.
   */
  private int pending;

  /** 운영용 — 시스템 시계로 1분 창을 잰다. */
  @Autowired
  public SecurityAuditRecorder(
      AuditLogService auditLogService,
      UserRepository userRepository,
      SecurityLevelRepository levelRepository,
      PlatformTransactionManager txManager,
      SecurityAuditMetrics metrics) {
    this(
        auditLogService,
        userRepository,
        levelRepository,
        txManager,
        metrics,
        Ticker.systemTicker(),
        QUEUE_CAPACITY,
        DRAIN_TIMEOUT);
  }

  /** 테스트용 — 시계를 주입해 1분 창을 테스트 안에서 넘기고, 대기 상한·종료 대기를 작게 줘 포화·종료 버림을 만든다. */
  SecurityAuditRecorder(
      AuditLogService auditLogService,
      UserRepository userRepository,
      SecurityLevelRepository levelRepository,
      PlatformTransactionManager txManager,
      SecurityAuditMetrics metrics,
      Ticker ticker,
      int queueCapacity,
      Duration drainTimeout) {
    this.auditLogService = auditLogService;
    this.userRepository = userRepository;
    this.levelRepository = levelRepository;
    this.metrics = metrics;
    TransactionTemplate t = new TransactionTemplate(txManager);
    t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.ownTx = t;
    this.recent =
        Caffeine.newBuilder()
            .expireAfterWrite(COALESCE_WINDOW)
            .maximumSize(100_000)
            .ticker(ticker)
            .build();
    this.queueCapacity = queueCapacity;
    this.drainTimeout = drainTimeout;
    // 하드 상한 크기의 유한 큐. 가득 찼거나 종료 중이면 거절 처리기가 호출 스레드에서 버림으로 센다
    // (CallerRuns 면 호출 스레드가 커넥션을 잡아 풀 교착을 되살린다).
    this.writer =
        new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(queueCapacity * HARD_CAPACITY_FACTOR),
            r -> {
              Thread th = new Thread(r, "security-audit-writer");
              th.setDaemon(true);
              return th;
            },
            (r, executor) -> {
              AuditTask task = (AuditTask) r;
              task.rejected = true;
              finishOne();
              drop(task.keys, task.kind, executor.isShutdown() ? "종료 중" : "하드 상한 포화");
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
        origin ->
            auditLogService.log(
                actorUserId,
                actorName(actorUserId),
                ACCESS_DENIED_ACTION,
                "dataset",
                datasetId == null ? null : String.valueOf(datasetId),
                action.name() + " 거부: " + reasonCode,
                origin.ipAddress(),
                origin.userAgent(),
                "FAILURE",
                null,
                meta),
        List.of(key),
        Kind.DENIAL);
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
    // 감사 등급 판정(findAuditedDatasetIds)도 기록 작업 안에서 한다 — 실행기 스레드의 커넥션 하나로(바깥 범위가 있으면 그 완료 후).
    dispatch(
        tenantId,
        origin -> {
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
                  origin.ipAddress(),
                  origin.userAgent(),
                  "SUCCESS",
                  null,
                  Map.of("kind", kind.name()));
            }
          }
        },
        List.copyOf(fresh.values()),
        Kind.ACCESS);
  }

  /**
   * 기록 작업을 실행기로 넘긴다 — 갈래는 넘기는 시점(바로·범위 완료 후)만 정한다. 접속 정보(IP·User-Agent, WD-394)는 실행기 스레드에 요청 속성이
   * 없으므로 여기(호출 스레드)서 한 번 읽어 기록 작업에 넘긴다.
   *
   * <p>판단 기준은 {@code isSynchronizationActive} 다 — 실제 트랜잭션이 없어도(SUPPORTS 등) 동기화 범위 안에서 jOOQ 가 얻은
   * 커넥션은 범위가 끝날 때까지 스레드에 묶여 있을 수 있으므로, 동기화 범위가 있으면 실제 트랜잭션 여부와 무관하게 완료 후로 미룬다.
   *
   * @param keys 실패·버림 시 지울 합치기 키(다음 호출이 다시 시도하게)
   * @param kind 포화 시 버릴 수 있는지 가르는 기록 종류
   */
  private void dispatch(
      long tenantId,
      Consumer<AuditLogService.RequestOrigin> writeWithOrigin,
      List<String> keys,
      Kind kind) {
    AuditLogService.RequestOrigin origin = auditLogService.currentOrigin();
    Runnable write = () -> writeWithOrigin.accept(origin);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
              // 커밋·롤백 무관 — 거부 감사는 요청이 롤백돼도 남아야 한다. 여기서 동기로 쓰지 않고 실행기로 넘긴다.
              submit(tenantId, write, keys, kind);
            }
          });
    } else {
      // 동기화 범위 없음(바깥 커넥션 없음·SYNCHRONIZATION_NEVER) — 호출 스레드는 커넥션을 열지 않고 바로 넘긴다(WD-682).
      submit(tenantId, write, keys, kind);
    }
  }

  /**
   * 실행기에 기록을 넘긴다(호출자를 막지 않는다). 소프트 상한은 대기 수({@code pending})로 정확히 판정하고, 하드 상한은 실행기 큐가 묶는다 — 넘친 거부는
   * 거절 처리기가 버림으로 센다(클래스 설명의 큐 포화 정책).
   */
  private void submit(long tenantId, Runnable write, List<String> keys, Kind kind) {
    boolean overSoftCap;
    synchronized (this) {
      overSoftCap = pending > queueCapacity;
      if (!overSoftCap || kind == Kind.DENIAL) {
        pending++;
      }
    }
    if (overSoftCap && kind == Kind.ACCESS) {
      drop(keys, kind, "큐 포화");
      return;
    }
    AuditTask task = new AuditTask(tenantId, write, keys, kind);
    writer.execute(task);
    // 넘침은 실제로 받아들인 거부만 센다(하드 상한에서 거절된 것은 처리기가 버림으로 셌다).
    if (overSoftCap && !task.rejected) {
      metrics.overflowed();
      if (shouldWarn(lastOverflowWarnNanos)) {
        log.warn(
            "접근 거부 감사가 대기 소프트 상한({}건)을 넘었다 — 접근 감사는 버리는 중이고 거부는 하드 상한({}건)까지 받는다. 넘침 누적 {}건",
            queueCapacity,
            queueCapacity * HARD_CAPACITY_FACTOR,
            metrics.overflowTotal());
      }
    }
  }

  /** 버린 기록을 센다 — 합치기 키를 지워 다음 호출이 다시 시도하게 하고 종류별 카운터를 올린다. */
  private void discard(List<String> keys, Kind kind) {
    keys.forEach(recent::invalidate);
    metrics.dropped(kind);
  }

  /** 기록 하나를 버린다(카운터 + 1분에 1번 WARN). */
  private void drop(List<String> keys, Kind kind, String reason) {
    discard(keys, kind);
    if (shouldWarn(lastDropWarnNanos)) {
      log.warn(
          "보안 감사 기록을 버렸다({}, 이번 종류 {}) — 누적 {}건. 요청 처리는 그대로 진행한다. 이후 1분간 버림은 카운터로만 센다",
          reason,
          kind.label,
          metrics.totalDropped());
    }
  }

  /** {@code last} 로부터 1분이 지났으면 지금으로 바꾸고 true(동시 호출 중 한 스레드만). */
  private static boolean shouldWarn(AtomicLong last) {
    long now = System.nanoTime();
    long prev = last.get();
    return now - prev >= WARN_INTERVAL_NANOS && last.compareAndSet(prev, now);
  }

  /** 실행기 스레드에서 기록 하나를 트랜잭션으로 쓴다. 실패하면 합치기 키를 지우고 WARN(요청은 실패시키지 않는다). */
  private void writeNow(Runnable write, List<String> keys, Kind kind) {
    try {
      ownTx.executeWithoutResult(s -> write.run());
    } catch (RuntimeException e) {
      keys.forEach(recent::invalidate);
      log.warn("{} 감사 기록 실패 — 요청 처리는 그대로 진행한다", kind.label, e);
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

  /** 애플리케이션 종료 시 큐에 남은 기록을 제한 시간 안에 비운다. 넘기거나 인터럽트되면 남은 작업을 버림으로 센다. */
  @PreDestroy
  void shutdown() {
    writer.shutdown();
    try {
      if (!writer.awaitTermination(drainTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
        discardUnrun(writer.shutdownNow());
      }
    } catch (InterruptedException e) {
      discardUnrun(writer.shutdownNow());
      Thread.currentThread().interrupt();
    }
  }

  /**
   * shutdownNow 가 돌려준 미실행 작업을 버림으로 센다. 실행되지 않아 finishOne 이 불리지 않으므로 대기 수도 한 번에 줄인다(awaitIdle 이 멈추지
   * 않게).
   */
  private void discardUnrun(List<Runnable> unrun) {
    if (unrun.isEmpty()) {
      return;
    }
    for (Runnable r : unrun) {
      AuditTask task = (AuditTask) r;
      discard(task.keys, task.kind);
    }
    synchronized (this) {
      pending -= unrun.size();
      if (pending == 0) {
        notifyAll();
      }
    }
    log.warn("종료 시 감사 기록 {}건을 비우지 못하고 버렸다 — 버림 누적 {}건", unrun.size(), metrics.totalDropped());
  }

  /** 감사 행의 사용자 이름(없으면 "unknown"). */
  private String actorName(long actorUserId) {
    return userRepository.findById(actorUserId).map(UserResponse::username).orElse("unknown");
  }
}
