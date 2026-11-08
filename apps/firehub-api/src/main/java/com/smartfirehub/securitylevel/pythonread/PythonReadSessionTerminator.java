package com.smartfirehub.securitylevel.pythonread;

import com.smartfirehub.global.tenant.TenantPipelineRoleProvisioner;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.ToIntBiFunction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * PG PYTHON 읽기 슬롯 롤 세션 끊기를 <b>전용 단일 스레드</b>에서 돌리고, 부르는 쪽은 정해진 시간만 기다리게 하는 장치(WD-624).
 *
 * <p><b>왜 따로 스레드인가(풀 멈춤 방지).</b> 세션 끊기는 소유자 롤 풀(크기 1, 연결 대기 상한은 Hikari 기본 30초)로만 할 수 있고, {@code
 * pg_terminate_backend(pid, 5000)} 은 세션마다 최대 5초 기다린다. 부르는 쪽이 직접 돌리면 그 시간 내내 자기 자원을 쥔 채 멈춘다 — 커밋 콜백은
 * 바깥 플랫폼 커넥션을. 이제 끊기는 플랫폼 풀을 전혀 쓰지 않는 이 스레드에서만 돌고, 부르는 쪽은 상한만큼만 기다린 뒤 실패로 처리한다(끊기 자체는 계속 돈다). 끊기
 * 스레드는 플랫폼 커넥션·동기화 잠금을 기다리지 않으므로 순환 대기가 없다. 동기화 쪽도 끊기를 기다릴 때는 커넥션·잠금을 쥐지 않는다({@link
 * PythonReadGrantSync}).
 *
 * <p><b>슬롯 단위.</b> 요청은 끊을 슬롯 번호(1~10)를 싣는다 — 데이터셋 하나의 상향을 확인하지 못했을 때는 그 테이블을 더는 읽으면 안 되는 슬롯만 끊는다(같은
 * 테넌트의 다른 실행은 그대로).
 *
 * <p><b>합치기.</b> 테넌트마다 <b>아직 시작하지 않은</b> 끊기가 큐에 있으면 새 요청은 그것에 합류해 슬롯을 보탠다 — 그 끊기는 요청 시점 이후에 시작하므로
 * 그때 접속해 있던 세션을 모두 끊는다. 이미 시작한 끊기에는 합류하지 않는다(시작 뒤 요청의 세션을 못 봤을 수 있다). 테넌트당 큐 길이는 최대 1이다.
 *
 * <p><b>미확인 표식(fail-closed).</b> 커밋 직후 끊기는 기다리기 <b>전에</b> 슬롯마다 표식을 단다({@link
 * #terminateConfirmingWithin}). 표식이 있는 슬롯은 실행 준비(JIT)가 넓히기 전에 먼저 끊는다. 표식에는 요청 순번을 적고, 끊기 작업은 시작할 때
 * 순번을 읽는다 — 끊기가 성공하면 시작 순번 이하로 적힌 표식(= 그 작업이 시작하기 전에 단 표식)만 지운다. 표식 달기와 시작 순번 읽기는 같은 모니터 안이라 "늦게 단
 * 표식이 성공한 끊기 뒤에 남는" 경합이 없다(기다림이 끝난 뒤가 아니라 기다리기 전에 단다).
 *
 * <p>표식·끊은 시각은 이 프로세스 메모리에만 있다 — 다른 API 인스턴스의 실행 준비는 이 표식을 보지 못한다(끊기 자체는 DB 단위라 모든 인스턴스의 세션을 끊는다).
 */
@Slf4j
@Component
public class PythonReadSessionTerminator {

  /** 대기 큐 상한 — 테넌트당 최대 1개라 테넌트 수보다 넉넉하면 된다. 넘치면 요청을 즉시 실패시킨다(부르는 쪽이 fail-closed 처리). */
  static final int QUEUE_CAPACITY = 1_000;

  /** 실제 끊기(테넌트, 슬롯들 → 끊은 세션 수). 운영은 프로비저너, 단위 테스트는 가짜를 넣는다. */
  private final ToIntBiFunction<Long, Set<Integer>> killer;

  /** 끊기 전용 단일 스레드(소유자 풀이 크기 1이라 더 늘려도 빨라지지 않는다). */
  private final ThreadPoolExecutor worker;

  /** 아직 시작하지 않은 끊기 — 합류한 요청의 슬롯을 모은다. */
  private static final class Pending {
    final Set<Integer> slots = Collections.synchronizedSet(new TreeSet<>());
    final CompletableFuture<Integer> future = new CompletableFuture<>();
  }

  /** 테넌트 → 아직 시작하지 않은 끊기. 작업이 시작할 때 스스로 지운다(지운 뒤에는 합류할 수 없다 — 같은 키 잠금 안의 원자 연산). */
  private final Map<Long, Pending> queued = new ConcurrentHashMap<>();

  /** 테넌트 → 마지막으로 세션을 끊은 시각(epoch ms) — 러너 실패 문구용(판정에는 쓰지 않는다). */
  private final Map<Long, Long> terminatedAt = new ConcurrentHashMap<>();

  /** 테넌트 → (슬롯 → 미확인 표식 순번). {@code this} 모니터로 보호한다. */
  private final Map<Long, Map<Integer, Long>> unconfirmed = new HashMap<>();

  /** 표식·작업 시작 순번. {@code this} 모니터로 보호한다. */
  private long sequence;

  /** 운영용 — 소유자 연결을 가진 프로비저너로 끊는다. */
  @Autowired
  public PythonReadSessionTerminator(TenantPipelineRoleProvisioner roleProvisioner) {
    this(roleProvisioner::terminatePythonReadSessions);
  }

  /** 테스트용 — 끊기 동작을 갈아 끼운다(막힘·실패 재현). */
  PythonReadSessionTerminator(ToIntBiFunction<Long, Set<Integer>> killer) {
    this.killer = killer;
    this.worker =
        new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY),
            r -> {
              Thread th = new Thread(r, "python-read-session-terminate");
              th.setDaemon(true);
              return th;
            });
  }

  /**
   * 그 테넌트의 주어진 슬롯 롤 세션 끊기를 요청하고 최대 {@code wait} 기다린다. 기다림이 끝나도 끊기는 계속 돈다. 부르는 쪽은 커넥션·잠금을 쥐지 않은 채
   * 불러야 한다(기다림 동안 남을 막지 않게).
   *
   * @return 끊은(종료 신호를 보낸) 세션 수
   * @throws IllegalStateException 시간 초과·끊기 실패·큐 포화·인터럽트 — 부르는 쪽이 fail-closed 로 처리한다
   */
  public int terminateWithin(long tenantId, Set<Integer> slots, Duration wait) {
    if (slots.isEmpty()) {
      return 0;
    }
    return await(request(tenantId, slots), tenantId, wait);
  }

  /**
   * 커밋 직후 끊기 — 기다리기 <b>전에</b> 슬롯마다 미확인 표식을 달고 요청한 뒤 최대 {@code wait} 기다린다. 끊기가 성공하면(늦게 끝나도) 표식이
   * 지워지고, 실패하면 남아 실행 준비가 넓히기 전에 먼저 끊는다(fail-closed).
   *
   * @return 상한 안에 끊었으면 true(시간 초과·실패는 false — 표식이 남는다)
   */
  public boolean terminateConfirmingWithin(long tenantId, Set<Integer> slots, Duration wait) {
    if (slots.isEmpty()) {
      return true;
    }
    markUnconfirmed(tenantId, slots);
    try {
      await(request(tenantId, slots), tenantId, wait);
      return true;
    } catch (IllegalStateException e) {
      log.error("커밋 직후 PYTHON 슬롯 롤 세션 끊기를 확인하지 못했다(tenant={} slots={})", tenantId, slots, e);
      return false;
    }
  }

  private static int await(CompletableFuture<Integer> result, long tenantId, Duration wait) {
    try {
      return result.get(wait.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      throw new IllegalStateException(
          "PYTHON 슬롯 롤 세션 끊기가 " + wait.toMillis() + "ms 안에 끝나지 않았다(tenant=" + tenantId + ")", e);
    } catch (ExecutionException e) {
      throw new IllegalStateException(
          "PYTHON 슬롯 롤 세션 끊기 실패(tenant=" + tenantId + ")", e.getCause());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("PYTHON 슬롯 롤 세션 끊기 대기 중 인터럽트(tenant=" + tenantId + ")", e);
    }
  }

  /**
   * 끊기 작업을 큐에 넣거나, 아직 시작하지 않은 같은 테넌트 작업에 합류해 슬롯을 보탠다. 합류(슬롯 보태기)와 작업 시작(큐에서 지우기)은 같은 키에 대한 {@link
   * ConcurrentHashMap} 원자 연산이라, 합류한 슬롯은 반드시 그 작업이 끊는다.
   */
  private CompletableFuture<Integer> request(long tenantId, Set<Integer> slots) {
    Pending[] created = {null};
    Pending pending =
        queued.compute(
            tenantId,
            (k, existing) -> {
              Pending p = existing != null ? existing : new Pending();
              p.slots.addAll(slots);
              if (existing == null) {
                created[0] = p;
              }
              return p;
            });
    if (created[0] != null) {
      try {
        worker.execute(() -> run(tenantId, created[0]));
      } catch (RejectedExecutionException e) {
        queued.remove(tenantId, created[0]);
        created[0].future.completeExceptionally(e);
      }
    }
    return pending.future;
  }

  /** 작업 본체 — 시작 표시(큐에서 지움)·시작 순번 → 끊기 → 끊은 시각·미확인 표식 정리. */
  private void run(long tenantId, Pending pending) {
    queued.remove(tenantId, pending); // 이후 요청은 새 작업을 만든다(이 작업이 그 요청의 세션을 못 봤을 수 있다)
    long startSeq = currentSequence();
    Set<Integer> slots;
    synchronized (pending.slots) {
      slots = new TreeSet<>(pending.slots);
    }
    try {
      int terminated = killer.applyAsInt(tenantId, slots);
      if (terminated > 0) {
        terminatedAt.put(tenantId, System.currentTimeMillis());
        log.warn(
            "등급 변경으로 실행 중인 PYTHON 슬롯 롤 세션 {}개를 끊었다(tenant={} slots={})",
            terminated,
            tenantId,
            slots);
      }
      clearUnconfirmed(tenantId, slots, startSeq);
      pending.future.complete(terminated);
    } catch (RuntimeException e) {
      pending.future.completeExceptionally(e);
    }
  }

  private synchronized long currentSequence() {
    return sequence;
  }

  /** 슬롯마다 새 순번으로 미확인 표식을 단다(이미 있으면 더 큰 순번으로). */
  private synchronized void markUnconfirmed(long tenantId, Set<Integer> slots) {
    long seq = ++sequence;
    Map<Integer, Long> marks = unconfirmed.computeIfAbsent(tenantId, k -> new HashMap<>());
    for (int slot : slots) {
      marks.put(slot, seq);
    }
  }

  /** 시작 순번 이하로 적힌(= 그 끊기가 시작하기 전에 단) 표식만 지운다. */
  private synchronized void clearUnconfirmed(long tenantId, Set<Integer> slots, long startSeq) {
    Map<Integer, Long> marks = unconfirmed.get(tenantId);
    if (marks == null) {
      return;
    }
    for (int slot : slots) {
      Long seq = marks.get(slot);
      if (seq != null && seq <= startSeq) {
        marks.remove(slot);
      }
    }
    if (marks.isEmpty()) {
      unconfirmed.remove(tenantId);
    }
  }

  /** 끊기 미확인 표식이 남은 슬롯들 — 실행 준비가 슬롯을 맞추기 전에 먼저 끊을지 정한다. */
  synchronized Set<Integer> unconfirmedSlots(long tenantId) {
    Map<Integer, Long> marks = unconfirmed.get(tenantId);
    return marks == null ? Set.of() : Set.copyOf(marks.keySet());
  }

  /** sinceMillis 이후 이 테넌트의 슬롯 롤 세션을 끊었는가 — 러너가 실패 문구를 고르는 데만 쓴다. */
  boolean terminatedSince(long tenantId, long sinceMillis) {
    Long at = terminatedAt.get(tenantId);
    return at != null && at >= sinceMillis;
  }

  /** 종료 — 남은 끊기는 버린다(기다리던 쪽은 시간 초과로 fail-closed 처리된다). 데몬 스레드라 종료를 막지 않는다. */
  @PreDestroy
  void shutdown() {
    worker.shutdownNow();
  }
}
