package com.smartfirehub.securitylevel.pythonread;

import com.smartfirehub.global.tenant.TenantPipelineRoleProvisioner;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
 * <p><b>미확인 표식(fail-closed).</b> 커밋 직후 끊기는 요청하기 <b>전에</b> 슬롯마다 표식을 단다({@link #markAndRequest}). 표식이
 * 있는 슬롯은 실행 준비(JIT)가 넓히기 전에 먼저 끊는다. 표식에는 요청 순번을 적고, 끊기 작업은 시작할 때 순번을 읽는다 — 끊기가 성공하면 시작 순번 이하로 적힌
 * 표식(= 그 작업이 시작하기 전에 단 표식)만 지운다. 표식 달기와 시작 순번 읽기는 같은 모니터 안이라 "늦게 단 표식이 성공한 끊기 뒤에 남는" 경합이 없다(요청이 끝난
 * 뒤가 아니라 요청하기 전에 단다).
 *
 * <p><b>커밋 콜백은 기다리지 않는다(WD-664 9).</b> 커밋 콜백은 바깥 플랫폼 커넥션을 쥔 채 돈다 — 예전처럼 3초씩 기다리면 동시 커밋 N 개가 플랫폼 풀
 * 커넥션 N 개를 그만큼 쥐었다. 이제 커밋 콜백은 {@link #markAndRequest}·{@link #requestTermination} 으로 표식을 달고 요청만 넣은
 * 뒤 곧바로 돌아온다. 끊기는 이 전용 스레드에서 몇 초 안에 이어지고, 실패하면 표식이 남아 실행 준비가 막는다(fail-closed). 대신 커밋 응답 시점에 끊기가
 * 끝났다는 보장은 없다(설계 결정 Q2 A 에서 받아들인 값).
 *
 * <p><b>메모리 표식은 대비책이다(WD-701 4차).</b> 표식의 원본은 이제 데이터 저장소 표({@link PythonReadUnconfirmedMarks}) — 모든
 * API 인스턴스의 실행 준비가 본다. 이 메모리 표식은 그 표에 쓰지 못했을 때(데이터 저장소 장애 — 대체 경로가 바로 그런 때 돈다)만 단다. 메모리 표식은 이
 * 프로세스에만 보이고, 실행 준비는 이것을 "시작 시각과 무관하게 그 슬롯 세션 전부" 로 다룬다(가장 보수적).
 *
 * <p><b>시작 시각 기준 끊기(WD-701 4차).</b> 요청은 슬롯마다 기준 시각을 실을 수 있다 — 그 시각 전에 시작한 세션만 끊는다(null 이면 전부). 같은
 * 슬롯의 요청이 합쳐지면 더 넓은 쪽(전부 &gt; 더 늦은 기준)을 쓴다 — 덜 끊는 쪽으로 합치지 않는다. 기준 시각으로 끊은 슬롯은 메모리 표식을 지우지 않는다(메모리
 * 표식은 "전부" 를 요구한다).
 *
 * <p>끊은 시각(러너 문구용)은 이 프로세스 메모리에만 있다.
 */
@Slf4j
@Component
public class PythonReadSessionTerminator {

  /** 대기 큐 상한 — 테넌트당 최대 1개라 테넌트 수보다 넉넉하면 된다. 넘치면 요청을 즉시 실패시킨다(부르는 쪽이 fail-closed 처리). */
  static final int QUEUE_CAPACITY = 1_000;

  /**
   * 실제 끊기 — 테넌트와 (슬롯 → 기준 시각, null 이면 전부)을 받아 끊은 세션 수를 돌려준다(WD-701 4차). 운영은 프로비저너, 단위 테스트는 가짜를 넣는다.
   */
  @FunctionalInterface
  interface Killer {
    int kill(long tenantId, Map<Integer, OffsetDateTime> startedBefore);
  }

  /** 실제 끊기. */
  private final Killer killer;

  /** 끊기 전용 단일 스레드(소유자 풀이 크기 1이라 더 늘려도 빨라지지 않는다). */
  private final ThreadPoolExecutor worker;

  /** 아직 시작하지 않은 끊기 — 합류한 요청의 슬롯과 기준 시각을 모은다. {@code scope} 는 자기 모니터로 보호한다(값 null = 그 슬롯 세션 전부). */
  private static final class Pending {
    final Map<Integer, OffsetDateTime> scope = new TreeMap<>();
    final CompletableFuture<Integer> future = new CompletableFuture<>();

    /** 요청 하나를 합친다 — 같은 슬롯이면 더 넓게 끊는 쪽(전부 &gt; 더 늦은 기준)을 남긴다. */
    void merge(Map<Integer, OffsetDateTime> more) {
      synchronized (scope) {
        more.forEach(
            (slot, cutoff) -> {
              if (!scope.containsKey(slot)) {
                scope.put(slot, cutoff);
                return;
              }
              OffsetDateTime have = scope.get(slot);
              scope.put(
                  slot,
                  have == null || cutoff == null ? null : (cutoff.isAfter(have) ? cutoff : have));
            });
      }
    }
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
    this((Killer) roleProvisioner::terminatePythonReadSessions);
  }

  /** 테스트용 — 슬롯만 보는 끊기 동작을 갈아 끼운다(막힘·실패 재현). 기준 시각은 무시한다. */
  PythonReadSessionTerminator(ToIntBiFunction<Long, Set<Integer>> slotKiller) {
    this((Killer) (tenantId, scope) -> slotKiller.applyAsInt(tenantId, scope.keySet()));
  }

  /**
   * 테스트용 — 기준 시각까지 보는 끊기 동작을 갈아 끼운다. 생성자가 아니라 팩터리인 이유: 슬롯만 보는 생성자와 람다 모양(인자 둘)이 같아 생성자로 두면 호출부 람다가
   * 모호해진다.
   */
  static PythonReadSessionTerminator withKiller(Killer killer) {
    return new PythonReadSessionTerminator(killer);
  }

  private PythonReadSessionTerminator(Killer killer) {
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
    return terminateWithin(tenantId, allSessions(slots), wait);
  }

  /**
   * {@link #terminateWithin(long, Set, Duration)} 와 같되 슬롯마다 기준 시각 전에 시작한 세션만 끊는다(WD-701 4차, 값 null
   * 이면 그 슬롯 세션 전부).
   */
  public int terminateWithin(
      long tenantId, Map<Integer, OffsetDateTime> startedBefore, Duration wait) {
    if (startedBefore.isEmpty()) {
      return 0;
    }
    return await(request(tenantId, startedBefore), tenantId, wait);
  }

  /** 슬롯 집합 → "슬롯마다 세션 전부" 범위. */
  static Map<Integer, OffsetDateTime> allSessions(Set<Integer> slots) {
    Map<Integer, OffsetDateTime> scope = new TreeMap<>();
    for (int slot : slots) {
      scope.put(slot, null);
    }
    return scope;
  }

  /**
   * 커밋 콜백용 끊기(WD-664 9, 회수 미확인 대체 경로·등급 정의 변경) — 슬롯마다 미확인 표식을 <b>먼저</b> 달고 끊기를 요청한 뒤 <b>기다리지 않고</b>
   * 돌아온다. 끊기가 성공하면(시작 순번 규칙대로) 표식이 지워지고, 실패하면 남아 실행 준비가 넓히기 전에 먼저 끊는다(fail-closed). 실패는 ERROR 로그와
   * 돌려준 결과로만 알린다 — 던지지 않는다.
   *
   * <p>왜 기다리지 않는가: 부르는 쪽(커밋 콜백)은 바깥 플랫폼 커넥션을 쥐고 있다. 표식이 이미 fail-closed 를 보장하므로 기다림이 지키는 것은 "커밋 응답
   * 시점에 끊김" 뿐인데, 그 값으로 풀 커넥션을 쥐는 비용을 치르지 않기로 했다(설계 결정 Q2 A).
   *
   * @return 끊은 세션 수로 완료되는 결과(빈 슬롯이면 0 으로 이미 완료)
   */
  public CompletableFuture<Integer> markAndRequest(long tenantId, Set<Integer> slots) {
    if (slots.isEmpty()) {
      return CompletableFuture.completedFuture(0);
    }
    markUnconfirmed(tenantId, slots);
    CompletableFuture<Integer> result = request(tenantId, allSessions(slots));
    result.whenComplete(
        (n, e) -> {
          if (e != null) {
            log.error(
                "커밋 직후 PYTHON 슬롯 롤 세션 끊기 실패 — 미확인 표식이 남아 실행 준비가 먼저 끊는다(tenant={} slots={})",
                tenantId,
                slots,
                e);
          }
        });
    return result;
  }

  /**
   * 표식 없이 끊기만 요청하고 기다리지 않는다(WD-664 7, 확인된 좁히기 뒤) — 회수가 이미 커밋돼 새 실행은 옳은 권한으로 붙으므로 실행 준비를 막을 표식이 필요
   * 없다. 회수 전에 시작한 긴 쿼리·커서만 끊으면 된다. 실패 처리(재시도)는 부르는 쪽 몫이다.
   *
   * @return 끊은 세션 수로 완료되는 결과(빈 슬롯이면 0 으로 이미 완료, 큐 포화·종료면 실패로 완료)
   */
  public CompletableFuture<Integer> requestTermination(long tenantId, Set<Integer> slots) {
    if (slots.isEmpty()) {
      return CompletableFuture.completedFuture(0);
    }
    return request(tenantId, allSessions(slots));
  }

  /**
   * 끊기 스레드가 지금까지 받은 요청을 모두 처리할 때까지 최대 {@code timeout} 기다린다 — 통합 테스트가 커밋 뒤 비동기 끊기를 기다린 다음 단언하는 데
   * 쓴다(운영 경로는 부르지 않는다). 큐 순서대로 도는 단일 스레드라 빈 작업 하나가 끝나면 그 앞의 요청은 모두 끝났다.
   *
   * @return 시간 안에 비었으면 true(종료된 뒤면 false)
   */
  public boolean awaitIdle(Duration timeout) throws InterruptedException {
    CompletableFuture<Void> marker = new CompletableFuture<>();
    try {
      worker.execute(() -> marker.complete(null));
    } catch (RejectedExecutionException e) {
      return false;
    }
    try {
      marker.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      return true;
    } catch (TimeoutException | ExecutionException e) {
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
  private CompletableFuture<Integer> request(
      long tenantId, Map<Integer, OffsetDateTime> startedBefore) {
    Pending[] created = {null};
    Pending pending =
        queued.compute(
            tenantId,
            (k, existing) -> {
              Pending p = existing != null ? existing : new Pending();
              p.merge(startedBefore);
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
    Map<Integer, OffsetDateTime> scope;
    synchronized (pending.scope) {
      scope = new TreeMap<>(pending.scope);
    }
    Set<Integer> slots = scope.keySet();
    try {
      int terminated = killer.kill(tenantId, scope);
      if (terminated > 0) {
        terminatedAt.put(tenantId, System.currentTimeMillis());
        log.warn(
            "등급 변경으로 실행 중인 PYTHON 슬롯 롤 세션 {}개를 끊었다(tenant={} slots={})",
            terminated,
            tenantId,
            slots);
      }
      // 메모리 표식은 "그 슬롯 세션 전부" 를 요구한다 — 기준 시각으로 끊은 슬롯은 지우지 않는다.
      Set<Integer> killedAll = new TreeSet<>();
      scope.forEach(
          (slot, cutoff) -> {
            if (cutoff == null) {
              killedAll.add(slot);
            }
          });
      clearUnconfirmed(tenantId, killedAll, startSeq);
      pending.future.complete(terminated);
    } catch (RuntimeException e) {
      pending.future.completeExceptionally(e);
    }
  }

  private synchronized long currentSequence() {
    return sequence;
  }

  /**
   * 슬롯마다 새 순번으로 미확인 표식을 단다(이미 있으면 더 큰 순번으로). {@link #markAndRequest} 와, 확인된 회수 뒤 끊기 재시도가 모두 버려졌을
   * 때(WD-701 3차, {@link PythonReadGrantSync#markSlotsUnconfirmed})가 부른다 — 표식이 있는 슬롯은 실행 준비가 먼저 끊고,
   * 못 끊으면 실행을 거부한다.
   */
  synchronized void markUnconfirmed(long tenantId, Set<Integer> slots) {
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

  /** 메모리 표식(슬롯 → 순번) 사본 — 실행 준비가 읽은 순번으로만 지우게({@link #clearUnconfirmedIfUnchanged}) 순번까지 준다. */
  synchronized Map<Integer, Long> unconfirmedMarks(long tenantId) {
    Map<Integer, Long> marks = unconfirmed.get(tenantId);
    return marks == null ? Map.of() : Map.copyOf(marks);
  }

  /** 실행 준비가 그 슬롯의 세션이 없음을 확인하고 맞춘 뒤 메모리 표식을 지운다(WD-701 4차) — 읽은 뒤 다시 달린(순번이 바뀐) 표식은 지우지 않는다. */
  synchronized void clearUnconfirmedIfUnchanged(long tenantId, int slot, long seq) {
    Map<Integer, Long> marks = unconfirmed.get(tenantId);
    if (marks == null) {
      return;
    }
    marks.remove(slot, seq);
    if (marks.isEmpty()) {
      unconfirmed.remove(tenantId);
    }
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
