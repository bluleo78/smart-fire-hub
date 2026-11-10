package com.smartfirehub.securitylevel.pythonread;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * 커밋 뒤 PYTHON 읽기 슬롯 GRANT 동기화를 돌리는 전용 단일 스레드 실행기(WD-594). PG({@link
 * PythonReadGrantSync})·Tibero({@link TiberoPythonReadGrantSync}) 두 백엔드가 같이 쓴다.
 *
 * <p><b>왜 따로 스레드인가(커넥션 1개 원칙).</b> 동기화는 REQUIRES_NEW 로 커넥션을 하나 더 잡는다. 이를 커밋 콜백(afterCommit·
 * afterCompletion)에서 호출 스레드로 바로 돌리면, Spring 은 그 콜백들을 부른 <b>뒤</b>에 바깥 커넥션을 반납하므로(spring-tx
 * AbstractPlatformTransactionManager.processCommit: triggerAfterCommit → triggerAfterCompletion →
 * finally cleanupAfterCompletion) 요청 하나가 커넥션 2개를 쥔다. 풀 크기만큼 데이터셋 생성·복제가 동시에 커밋하면 모두가 두 번째 커넥션을 기다려
 * 커넥션 타임아웃(30초)까지 멈추고 동기화도 실패한다. 실행기 스레드는 바깥 커넥션 없이 커넥션 하나만 쓰므로 서로 막지 않는다 — {@code
 * SecurityAuditRecorder} 와 같은 방식이다.
 *
 * <p><b>작업 단위 = 테넌트(잠금 공간) 하나(WD-644 리뷰).</b> 작업은 그룹(백엔드·테넌트 — 같은 동기화 잠금을 다투는 범위)마다 하나이고, 그 테넌트의 밀린
 * 테이블(항목)을 모두 들고 있다가 <b>잠금 한 번에</b> 함께 동기화한다. 테이블마다 작업을 두면, 잠금이 바쁜 테넌트의 테이블 20개가 저마다 묶인 대기(최대 수 초)를
 * 하나씩 해 단일 스레드를 수십 초씩 붙잡아 다른 테넌트를 막았다(head-of-line blocking). 이제 바쁜 테넌트 하나가 한 회차에 스레드를 붙잡는 시간은 묶인
 * 대기 한 번({@link RetryPolicy#lockWait}) + 동기화 시간으로 묶이고, 다른 테넌트의 새 작업이 기다리는 시간은 "지금 잠금이 바쁜 테넌트 수 ×
 * lockWait" 를 넘지 않는다(테이블 수와 무관).
 *
 * <p><b>잠금 다루기·재시도(WD-644).</b> 작업의 처음 몇 번({@link RetryPolicy#tryAttempts})은 잠금을 <b>시도만</b> 하고, 바쁘면
 * 아무 일도 하지 않은 채 트랜잭션을 끝내(커넥션 반납) {@link Outcome#BUSY} 를 돌려준다. 그 뒤로는 <b>묶인 대기</b>로 잠금 대기 줄에 선다 — PG
 * 의 시도형은 쥔 쪽뿐 아니라 기다리는 쪽이 줄에 있어도 실패하므로, 실행이 잇따르는 테넌트에서는 시도만으로는 끝내 잡지 못할 수 있기 때문이다. 한 번 격상한 작업은 뒤따르는
 * 실행에서도 격상을 유지한다(시도형 단계로 돌아가 다시 굶지 않게). 묶인 대기도 시간을 넘기면 BUSY 로 돌아와 다시 줄에 선다. 재시도는 같은 단일 스레드 {@link
 * ScheduledThreadPoolExecutor} 에 지연 작업으로 올리므로(0.5·1·2초, 이후 2초마다) 기다리는 동안 스레드·커넥션을 쥐지 않고, 스레드·커넥션 수도
 * 늘지 않는다(늘리면 위 커넥션 고갈이 되살아난다).
 *
 * <p><b>왜 포기하지 않는가.</b> 커밋 뒤 동기화는 GRANT 뿐 아니라 REVOKE(등급을 올린 뒤 낮은 슬롯 회수)도 한다. 버리면 회수가 빠져 슬롯이
 * <b>과권한</b>으로 남는다. 그래서 잠금이 바쁜 작업은 적용될 때까지 재시도하고 시간 상한이 없다. 일시 실패({@link Outcome#FAILED})는 같은 간격으로
 * {@link RetryPolicy#maxFailures} 번까지 다시 해 보고, 재시도해도 소용없는 실패({@link Outcome#PERMANENT_FAILURE})는
 * 곧바로 버린다. 버림 경로(포화·종료·실패 소진·영구 실패)는 모두 건수를 세고 ERROR 로 남긴다 — 조용한 버림은 없다(실행 직전·일 1회 동기화가 회복).
 *
 * <p><b>합치기·뒤따르는 실행.</b> 같은 그룹의 작업이 이미 있으면 새로 만들지 않고 그 작업의 대기 항목에 테이블을 더한다. 동기화는 실행 시점의 현재 상태를
 * 읽으므로, 아직 시도에 들어가지 않은 항목은 그 시도가 최신 커밋까지 반영한다. 지금 시도 중인 항목에 같은 테이블이 또 들어오면(시도가 이미 상태를 읽었을 수 있다) 대기
 * 항목에 남아 다음 회차에 정확히 한 번 더 돈다. 시도가 BUSY·FAILED 로 끝나면 그 시도의 항목은 대기 항목으로 되돌아간다 — 그 사이 들어온 뒤따르는 항목도
 * 지워지지 않는다.
 *
 * <p><b>포화.</b> 진행 중 작업(그룹) 수({@link #pending})에 상한을 둔다. 넘치면 호출자(커밋 콜백)를 막지 않고 버린 뒤 ERROR(누적 건수)를
 * 남긴다. 같은 테넌트의 동기화는 advisory lock 으로 직렬화되므로 순서가 바뀌어도 정합성 문제는 없다.
 *
 * <p><b>종료 순서(SmartLifecycle)와 비우기.</b> 남은 동기화를 비우려면 DataSource(Hikari 풀)가 아직 살아 있어야 한다. 이 빈은
 * DataSource 에 의존하지 않으므로 {@code @PreDestroy} 에서만 비우면 풀이 먼저(또는 비우는 도중에) 닫혀 비운 동기화가 모두 실패할 수 있다. 그래서
 * {@link SmartLifecycle#stop()} 에서 최대 10초 비운다. 비우기가 시작되는 순간부터 새 제출(합치기 포함)은 받지 않고, 남은 모든 작업(재시도
 * 대기·실행 대기· 지금 실행 중)의 다음 시도는 묶인 대기로 잡는다. 비우는 동안에는 스케줄러를 닫지 않으므로 바쁜(BUSY) 작업도 거절되지 않고 곧바로 다시 줄에 서며,
 * 대기 한 번은 비우기 제한 시간의 1/{@value #DRAIN_WAIT_DIVISOR} 로 줄여(남은 시간도 넘지 않게) 몇 번의 대기가 비우기 시간을 다 먹지 않게 한다.
 * 제한 시간을 넘겨도 남은 것만 ERROR 와 함께 버린다. 컨텍스트 종료 시 모든 라이프사이클 빈의 stop 이 어떤 빈 소멸보다도 앞서 끝나므로, 비우는 동안 커넥션 풀은
 * 아직 열려 있다. {@code @PreDestroy} 는 멱등 안전망으로만 남긴다.
 *
 * <p><b>재시작(WD-629 → WD-655).</b> stop 뒤 start 하면 옛 스케줄러가 끝났든 아니든(비우는 중, 인터럽트를 무시하는 시도) 곧바로 새 스케줄러로
 * 바꿔 제출을 다시 받아들인다 — 버리지 않는다. 새 스케줄러의 첫 작업({@link AwaitRetiredSchedulers})이 옛 스케줄러들이 모두 끝나기를 기다리므로
 * 보통은 옛·새 스케줄러의 동기화가 동시에 돌지 않는다(단일 스레드 전제). 비우기 도중의 start 는 그 비우기를 넘겨받는다: 옛 스케줄러는 새 예약을 받지 않게
 * 닫히고(이미 올라간 예약은 마저 돈다), 남은 작업의 다음 시도는 새 스케줄러로 가며, 비우던 stop 은 남은 작업을 버리지 않고 돌아온다({@link
 * #shutdown()}).
 *
 * <p><b>기다림의 상한(WD-673).</b> 인터럽트를 무시하고 멈춘 옛 시도(예: 응답 없는 JDBC 호출)가 있으면 그 기다림이 끝나지 않아 모든 테넌트의 커밋 뒤
 * 동기화가 무기한 멈췄다. 그래서 {@link #RETIRED_AWAIT_TIMEOUT} 만 기다리고, 넘기면 ERROR 를 남긴 뒤 새 스케줄러가 진행한다. 그 뒤 옛 시도와
 * 새 작업이 겹쳐 돌아도 정합성은 깨지지 않는다: 멈춘 시도의 작업은 잡 목록에 실행 중(running)으로 남아 같은 그룹 키의 새 제출은 그 작업에 합쳐질 뿐 두 번째
 * 작업이 생기지 않고, 같은 테넌트의 다른 그룹·다른 테넌트의 작업은 동시에 돌 수 있지만 테넌트 동기화 잠금이 보호한다(멈춘 시도가 잠금을 쥐면 BUSY 로 재시도할 뿐
 * 버리지 않음). 비용은 그동안 스레드·커넥션 하나가 더 쓰이는 것이다. 멈춘 시도는 버림으로 세지 않는다 — 끝나면 평소처럼 스스로 다음 회차를 현재 스케줄러에 올린다(그
 * 테넌트의 커밋 뒤 동기화는 그때까지 멈춰 있다 — 이전부터 있던 성질).
 */
@Slf4j
@Component
public class PythonReadSyncExecutor implements SmartLifecycle {

  /** 진행 중 작업(그룹, 재시도 대기 포함) 상한 — 넘치면 버리고 ERROR(호출자를 막지 않는다). */
  static final int QUEUE_CAPACITY = 10_000;

  /** 종료 시 남은 동기화를 비우는 최대 대기(운영 기본값). */
  private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(10);

  /** 비우기 중 묶인 대기 한 번의 상한 = 비우기 제한 시간 / 이 값 — 대기 몇 번이 비우기 시간을 다 먹지 않게 한다(WD-644 리뷰). */
  static final int DRAIN_WAIT_DIVISOR = 10;

  /** 묶인 대기 한 번의 운영 기본값 — 단일 스레드가 한 테넌트 잠금에 붙잡히는 시간을 회차마다 이만큼으로 묶는다. */
  static final Duration DEFAULT_LOCK_WAIT = Duration.ofSeconds(5);

  /**
   * 재시작 뒤 새 스케줄러가 옛 스케줄러들의 끝을 기다리는 최대 시간(WD-673, 운영 기본값). 정상 시도는 묶인 대기({@link #DEFAULT_LOCK_WAIT})와
   * 동기화 시간 안에 끝나지만, 테이블이 많은 묶음은 테이블마다 잠금 시간 초과(5초)를 거쳐 1분을 넘길 수도 있다 — 그런 정상 시도를 상한이 끊어도 스레드·커넥션 하나가
   * 잠시 더 쓰일 뿐 정합성은 지켜진다(클래스 설명 참고).
   */
  static final Duration RETIRED_AWAIT_TIMEOUT = Duration.ofMinutes(1);

  /**
   * 시도 한 번의 결과 — 묶음 전체에 대한 {@link Outcome}, 또는 잠금을 잡아 묶음을 돌았지만 일부 항목만 실패한 {@link
   * PartialFailure}(WD-644 리뷰).
   */
  sealed interface AttemptResult permits Outcome, PartialFailure {}

  /** 묶음 전체에 대한 시도 한 번의 결과(WD-644). */
  enum Outcome implements AttemptResult {
    /** 끝났다(동기화했다). */
    DONE,
    /** 테넌트 동기화 잠금이 바빠 아무 일도 하지 않았다 — 적용될 때까지 다시 한다. */
    BUSY,
    /** 일시 실패(연결·직렬화·교착 등) — {@link RetryPolicy#maxFailures} 번까지 다시 한다. */
    FAILED,
    /** 다시 해도 같은 실패(프로그램·스키마 오류 등) — 곧바로 버리고 건수를 세어 ERROR 로 남긴다. */
    PERMANENT_FAILURE
  }

  /**
   * 잠금을 잡아 묶음을 돌았지만 일부 항목(테이블)만 실패했다(WD-644 리뷰). 여기 없는 항목은 끝난 것이라 다시 하지 않는다 — 한 테이블의 실패로 묶음 전체를 다시
   * 하거나 버리면, 이미 커밋된 다른 테이블의 회수까지 "버림" 으로 세거나 헛돌린다. 실패한 항목만 아래 규칙으로 다룬다.
   *
   * @param transientFailed 일시 실패 항목 — 항목마다 {@link RetryPolicy#maxFailures} 번까지 대기 항목으로 되돌려 다시 한다
   * @param permanentFailed 영구 실패 항목 — 곧바로 버리고 세어 ERROR 로 남긴다
   */
  record PartialFailure(List<String> transientFailed, List<String> permanentFailed)
      implements AttemptResult {
    PartialFailure {
      transientFailed = List.copyOf(transientFailed);
      permanentFailed = List.copyOf(permanentFailed);
    }
  }

  /**
   * 그룹(테넌트) 하나의 시도 한 번 — {@code items} 테이블들을 테넌트 동기화 잠금 한 번 안에서 함께 동기화한다.
   *
   * <p>{@code lockWait} 가 0 이면 잠금을 시도만 하고, 양수면 그만큼까지 잠금 대기 줄에 서서(묶인 대기) 잡는다. 잠금 획득 문장 자체가 시간을 넘긴
   * 경우에만 BUSY 다. 일부 항목만 실패했으면 {@link PartialFailure} 로 그 항목만 알린다.
   */
  @FunctionalInterface
  interface Attempt {
    AttemptResult run(List<String> items, Duration lockWait);
  }

  /**
   * 항목을 버렸을 때 부르는 콜백(WD-701 3차) — 버림이 곧 보안 공백인 작업(확인된 회수 뒤 끊기 재시도)이 버려진 순간 막기 표식을 달 수 있게 한다. 모든 버림
   * 지점(제출 때 포화·종료, 일시 실패 소진, 영구 실패, 종료 비우기 시간 초과)에서 <b>실행기 모니터 밖에서</b> 부른다 — 콜백이 다른 잠금을 잡아도 실행기와
   * 교착하지 않게. 콜백의 예외는 ERROR 로그로 끝낸다(실행기 스레드·호출자를 죽이지 않는다).
   */
  @FunctionalInterface
  interface DropListener {
    void dropped(List<String> items);
  }

  /**
   * 재시도 규칙(WD-644) — 간격은 {@code initial} 에서 두 배씩 늘려 {@code cap} 에서 멈춘다.
   *
   * @param initial 첫 재시도 앞의 대기
   * @param cap 대기 상한(이후 이 간격으로 계속)
   * @param tryAttempts 처음 이만큼은 잠금을 시도만 하고, 그 뒤로는 묶인 대기로 줄에 선다(작업이 살아 있는 동안 격상 유지)
   * @param maxFailures 일시 실패를 이만큼까지 다시 해 본다(넘으면 ERROR 후 버림)
   * @param lockWait 묶인 대기 한 번의 상한
   */
  record RetryPolicy(
      Duration initial, Duration cap, int tryAttempts, int maxFailures, Duration lockWait) {

    /** 묶인 대기는 운영 기본값({@link #DEFAULT_LOCK_WAIT})으로 — 대기 길이를 시험하지 않는 테스트용. */
    RetryPolicy(Duration initial, Duration cap, int tryAttempts, int maxFailures) {
      this(initial, cap, tryAttempts, maxFailures, DEFAULT_LOCK_WAIT);
    }

    /** {@code retries} 번째(0부터) 재시도 앞의 대기 — min(initial × 2^min(n,16), cap). 16 으로 막아 넘침을 피한다. */
    Duration delayFor(int retries) {
      Duration d = initial.multipliedBy(1L << Math.min(retries, 16));
      return d.compareTo(cap) > 0 ? cap : d;
    }
  }

  /** 운영 재시도 규칙 — 0.5·1·2초 뒤 2초마다, 처음 3번만 시도형, 일시 실패는 5번까지, 묶인 대기는 5초. */
  static final RetryPolicy DEFAULT_RETRY_POLICY =
      new RetryPolicy(Duration.ofMillis(500), Duration.ofSeconds(2), 3, 5, DEFAULT_LOCK_WAIT);

  /**
   * 단일 스레드 스케줄러 — 첫 시도(지연 0)와 재시도(지연 n)를 모두 돌린다. 비우기는 이 스케줄러를 닫지 않은 채 진행하고(바쁜 작업의 재예약이 거절되지 않게), 다
   * 비웠거나 제한 시간을 넘긴 뒤에만 닫는다.
   *
   * <p>final 이 아닌 이유(WD-629): 컨텍스트 stop() → start() 뒤에도 커밋 뒤 동기화가 계속 돌아야 한다. 종료된 실행기는 되살릴 수 없으므로
   * {@link #start()} 만이 ({@link #replaceScheduler} 로) 새 스케줄러로 바꾼다. 교체와 예약(schedule)은 {@link
   * #lifecycleLock} 안에서 일어나고, shutdown 은 한 번 읽은 참조를 지역 변수로 쓴다 — 그래서 교체 뒤에는 옛 스케줄러에서 돌던 작업의 다음 시도도 새
   * 스케줄러로 간다(WD-655 넘겨받기).
   */
  private volatile ScheduledThreadPoolExecutor scheduler;

  /**
   * 스케줄러 교체(start)와 "현재 스케줄러 읽기 + schedule" 을 한 덩어리로 묶는 모니터(WD-629) — 예약이 교체 직전의 옛 스케줄러를 읽고 교체 뒤에
   * 넣어 거부되는 경쟁을 막는다. schedule 은 막히지 않으므로 이 모니터를 오래 쥐는 쪽이 없다. 잠금 순서는 언제나 이 모니터 → {@code this} 다.
   */
  private final Object lifecycleLock = new Object();

  /**
   * 닫혔지만 아직 끝나지 않은 옛 스케줄러들(WD-655) — 새 스케줄러의 첫 작업({@link AwaitRetiredSchedulers})이 이들이 모두 끝나기를
   * 기다린다. 직전 스케줄러 하나만이 아니라 목록인 이유: 인터럽트를 무시하는 시도 때문에 옛 스케줄러가 끝나지 않은 채 새 스케줄러가 다시 닫히면, 그다음 스케줄러는 더
   * 오래된 것도 기다려야 두 동기화가 동시에 돌지 않는다. {@link #lifecycleLock} 안에서만 읽고 쓴다.
   */
  private final List<ScheduledThreadPoolExecutor> retired = new ArrayList<>();

  /** 진행 중 작업 상한 — 운영은 {@link #QUEUE_CAPACITY}. */
  private final int capacity;

  /** 재시도 규칙 — 운영은 {@link #DEFAULT_RETRY_POLICY}, 테스트는 짧은 값을 주입해 빠르게 돈다. */
  private final RetryPolicy retryPolicy;

  /** 종료 시 남은 동기화를 비우는 최대 대기 — 테스트는 짧게 줘서 shutdownNow 경로를 재현한다(WD-629). */
  private final Duration drainTimeout;

  /** 새 스케줄러가 옛 스케줄러들의 끝을 기다리는 최대 시간 — 운영은 {@link #RETIRED_AWAIT_TIMEOUT}, 테스트는 짧게 준다(WD-673). */
  private final Duration retiredAwaitTimeout;

  /** 그룹(백엔드·테넌트) → 진행 중 작업. 합치기의 기준이다. {@code this} 모니터로 보호한다. */
  private final Map<String, Job> jobs = new HashMap<>();

  /**
   * 진행 중 작업 수 — {@link #awaitIdle} 가 기다리는 대상이자 포화 판정 기준. 작업 하나가 생기면 1 늘고, 끝나거나 버려질 때(잡 목록에서 빠질 때)
   * 정확히 한 번 1 준다. {@code this} 모니터로 보호한다.
   */
  private int pending;

  /**
   * 비우기(종료) 중인가 — stop 이 시작되는 순간 켜지고, start 가 새 스케줄러로 바꿀 때만 꺼진다(비우기 도중이어도 — WD-655 넘겨받기). 켜져 있으면 새
   * 제출(합치기 포함)은 버리고(false), 남은 작업의 다음 시도는 모두 묶인 대기로 잡는다. 쓰기는 {@code this} 모니터 안에서, 읽기는 volatile 로.
   */
  private volatile boolean closing;

  /** 비우기 마감 시각(System.nanoTime) — 비우기 중 묶인 대기를 남은 시간 안으로 줄이는 데 쓴다. {@code this} 모니터로 보호한다. */
  private long drainDeadlineNanos;

  /** 포화·종료·실패 소진·영구 실패로 버린 항목(테이블) 누적 수(ERROR 로그에 함께 남긴다). */
  private final AtomicLong dropped = new AtomicLong();

  /** 운영용 — 상한 {@link #QUEUE_CAPACITY}, 재시도 규칙 {@link #DEFAULT_RETRY_POLICY}. */
  public PythonReadSyncExecutor() {
    this(QUEUE_CAPACITY);
  }

  /** 테스트용 — 작은 상한으로 포화를 재현한다. */
  PythonReadSyncExecutor(int capacity) {
    this(capacity, DEFAULT_RETRY_POLICY, DRAIN_TIMEOUT);
  }

  /** 테스트용 — 비우기 대기를 줄여 시간 초과(shutdownNow) 경로를 재현한다(WD-629). */
  PythonReadSyncExecutor(int capacity, Duration drainTimeout) {
    this(capacity, DEFAULT_RETRY_POLICY, drainTimeout);
  }

  /** 테스트용 — 재시도 규칙을 짧게 주입해 재시도·격상·실패 경로를 빠르게 검증한다. */
  PythonReadSyncExecutor(int capacity, RetryPolicy retryPolicy) {
    this(capacity, retryPolicy, DRAIN_TIMEOUT);
  }

  /** 테스트용 — 재시도 규칙과 비우기 대기를 함께 준다. */
  PythonReadSyncExecutor(int capacity, RetryPolicy retryPolicy, Duration drainTimeout) {
    this(capacity, retryPolicy, drainTimeout, RETIRED_AWAIT_TIMEOUT);
  }

  /** 테스트용 — 옛 스케줄러 기다림의 상한을 줄여 멈춘 옛 시도를 넘어 진행하는 경로를 재현한다(WD-673). */
  PythonReadSyncExecutor(int capacity, Duration drainTimeout, Duration retiredAwaitTimeout) {
    this(capacity, DEFAULT_RETRY_POLICY, drainTimeout, retiredAwaitTimeout);
  }

  /** 공통 생성자. 스케줄러는 생성자에서 바로 만든다 — start 전에 제출하는 경로(빈 초기화 중 커밋·단위 테스트)를 그대로 받기 위해서다. */
  private PythonReadSyncExecutor(
      int capacity, RetryPolicy retryPolicy, Duration drainTimeout, Duration retiredAwaitTimeout) {
    this.capacity = capacity;
    this.retryPolicy = retryPolicy;
    this.drainTimeout = drainTimeout;
    this.retiredAwaitTimeout = retiredAwaitTimeout;
    this.scheduler = newScheduler();
  }

  /** 새 단일 스레드 스케줄러(데몬 스레드 — 종료를 막지 않는다). */
  private static ScheduledThreadPoolExecutor newScheduler() {
    return new ScheduledThreadPoolExecutor(
        1,
        r -> {
          Thread th = new Thread(r, "python-read-grant-sync");
          th.setDaemon(true);
          return th;
        });
  }

  /**
   * 항목(테이블) 하나를 그룹(테넌트) 작업에 넘긴다. 같은 그룹의 작업이 이미 있으면 그 작업의 대기 항목에 더하고(합치기 — 받아들인 것, true), 없으면 새 작업을
   * 만들어 곧바로 예약한다. 같은 그룹의 제출은 같은 백엔드·테넌트에서 오므로 {@code attempt} 는 처음 만든 작업의 것을 쓴다.
   *
   * <p>포화·종료(비우기 중 포함)면 예외를 던지지 않고 버린 뒤 ERROR — 커밋 콜백에서 불리므로 어떤 경우에도 호출자를 막거나 실패시키지 않는다. 비우기 중에는 이미
   * 있는 작업에 합치는 것도 버림(false)이다 — 그 작업이 비우기 제한 시간 안에 끝난다는 보장이 없으므로 받아들였다고 답하지 않는다. stop 뒤~start 전 제출도
   * 버림이다 — 자동으로 스케줄러를 다시 만들지 않는다(컨텍스트 종료 뒤 스레드 생성 방지).
   *
   * @param group 합치기 기준 — 같은 동기화 잠금을 다투는 범위(예: 백엔드·테넌트). 로그에도 쓴다
   * @param item 동기화할 항목(테이블 이름)
   * @return 받아들였으면(합친 경우 포함) true, 버렸으면 false
   */
  public boolean submitRetryable(String group, String item, Attempt attempt) {
    return submitRetryable(group, item, attempt, false);
  }

  /**
   * {@link #submitRetryable(String, String, Attempt)} 와 같되, {@code critical} 이면 진행 중 작업 상한(포화) 검사를
   * 건너뛴다(WD-664 1a).
   *
   * <p><b>왜 상한을 넘겨도 받는가.</b> 회수 미확인 뒷정리(좁히기를 커밋 직전에 확인하지 못한 데이터셋의 회수)와 등급 정의 변경 전체 동기화(커밋 직전 토큰 번호
   * 올리기가 실패했을 때의 유일한 대체 경로)는 과권한을 닫는 마지막 방어선이다. 이것이 포화로 버려지면 다음 실행 준비·일 1회 동기화까지 실행 중 스크립트가 자격 밖
   * 테이블을 계속 읽는다. 상한을 넘긴 만큼의 증가는 막지 않는다 — 실제 상한은 이 경로들이 생기는 빈도다. 등급 정의 변경은 테넌트 그룹으로 합쳐지지만, 회수 미확인
   * 뒷정리는 그룹에 데이터셋·슬롯·토큰 여부를 넣어 요청마다 작업 하나가 되므로 잠금 경합 중 자동 상향이 몰리면 그 수만큼 늘 수 있다(받아들인 트레이드오프 — 버리면
   * 과권한이 남는다). 받아들이면 일반 작업처럼 pending 에 세고(awaitIdle·비우기가 기다린다), 종료(비우기) 중이면 일반 작업처럼 버린다 — 종료 뒤 스레드를
   * 만들지 않는다는 규칙은 그대로다.
   *
   * @param critical 회수 필수 작업이면 true — 포화여도 버리지 않는다
   */
  public boolean submitRetryable(String group, String item, Attempt attempt, boolean critical) {
    return submitRetryable(group, item, attempt, critical, null);
  }

  /**
   * {@link #submitRetryable(String, String, Attempt, boolean)} 와 같되, 항목을 버리면 {@code onDropped} 를
   * 부른다(WD-701 3차, {@link DropListener}). 같은 그룹에 합쳐지면 처음 만든 작업의 콜백을 쓴다 — 같은 그룹은 같은 백엔드·테넌트·같은 요청(그룹
   * 이름에 요청 인자를 넣는 경로)이라 콜백도 같다.
   *
   * @param onDropped 버림 콜백(null 이면 없음)
   */
  public boolean submitRetryable(
      String group, String item, Attempt attempt, boolean critical, DropListener onDropped) {
    Job job = null;
    String dropReason = null;
    synchronized (this) {
      if (closing) {
        dropReason = "종료 중";
      } else {
        Job existing = jobs.get(group);
        if (existing != null) {
          // 시도 전이면 그 시도가 반영하고, 시도 중이면 다음 회차에 정확히 한 번 더 돈다(집합이라 여러 번 와도 한 번).
          existing.queued.add(item);
          return true;
        }
        // 회수 필수 작업은 상한을 보지 않는다(WD-664 1a — 위 Javadoc).
        if (!critical && pending >= capacity) {
          dropReason = "진행 중 작업 상한(" + capacity + ") 포화";
        } else {
          job = new Job(group, attempt, onDropped);
          job.queued.add(item);
          jobs.put(group, job);
          pending++;
        }
      }
    }
    if (job == null) {
      countDropped(1, dropReason + "(" + group + " " + item + ")");
      notifyDropped(onDropped, group, List.of(item));
      return false;
    }
    return schedule(job, Duration.ZERO);
  }

  /**
   * 작업을 지연 {@code delay} 로 현재 스케줄러에 올린다(첫 시도·재시도·뒤따르는 실행 공용). 현재 스케줄러 읽기·schedule·예약 기록을 {@link
   * #lifecycleLock} → {@code this} 안에서 한 번에 한다 — start 가 바꾼 뒤의 예약은 반드시 새 스케줄러로 가고, 작업이 돌기 시작하기 전에
   * 예약이 기록된다. 종료로 거절되거나, 종료와 겹쳐 예외 없이 취소되면({@code ScheduledThreadPoolExecutor.delayedExecute} 의 재확인
   * 경로) 버린 것으로 센다 — 스케줄러는 비우기가 끝난 뒤에만 닫히므로 이 경로는 비우기 제한 시간을 넘긴 작업뿐이다.
   */
  private boolean schedule(Job job, Duration delay) {
    try {
      ScheduledFuture<?> future;
      synchronized (lifecycleLock) {
        synchronized (this) {
          future = scheduler.schedule(job, delay.toMillis(), TimeUnit.MILLISECONDS);
          job.future = future;
        }
      }
      if (!future.isCancelled()) {
        return true;
      }
    } catch (RejectedExecutionException e) {
      // 아래에서 버림 처리.
    }
    retire(job, "종료 시 비우기 제한 시간(" + drainTimeout + ") 초과");
    return false;
  }

  /**
   * 그룹(테넌트) 하나의 진행 중 작업. 대기 항목을 모두 꺼내 한 번 시도하고, 결과에 따라 끝내거나 다음 간격 뒤로 자신을 다시 예약한다. {@code queued}·
   * {@code running}·{@code future} 는 실행기 모니터로, 나머지 계수는 단일 스레드에서만 쓴다.
   */
  private final class Job implements Runnable {

    private final String group;
    private final Attempt attempt;

    /** 버림 콜백(WD-701 3차, 없으면 null) — 실행기 모니터 밖에서만 부른다. */
    private final DropListener onDropped;

    /** 다음 시도에서 동기화할 항목(테이블) — 합치기·뒤따르는 실행·BUSY/FAILED 되돌림이 모두 여기로 모인다. 삽입 순서 유지. */
    private final Set<String> queued = new LinkedHashSet<>();

    /** 지금 시도 중인가 — 비우기 시간 초과 처리가 실행 중 작업을 건드리지 않게 가른다. */
    private boolean running;

    /** 마지막 예약 — 종료 비우기가 재시도 대기분을 찾아 꺼낼 때 쓴다. */
    private ScheduledFuture<?> future;

    /**
     * 이 작업이 산 동안의 시도 수 — 시도형/대기형 격상 기준. 뒤따르는 실행에서도 되돌리지 않는다(WD-644 리뷰): 잠금이 다투어지는 테넌트에서 시도형 단계부터 다시
     * 하면 또 굶는다.
     */
    private int attempts;

    /** 재시도 수 — {@link RetryPolicy#delayFor} 의 위치. 시도가 끝나면(DONE) 되돌린다. */
    private int retries;

    /**
     * 항목(테이블)별 연속 일시 실패 수(WD-644 리뷰) — 묶음 전체 실패면 시도한 항목 모두, 일부 실패면 그 항목만 는다. 항목별로 세야 다른 테이블의 실패가 끝난
     * 테이블의 재시도 횟수를 갉아먹지 않는다. 끝나거나 버려진 항목은 지운다. 실행기 모니터 안에서만 쓴다.
     */
    private final Map<String, Integer> failures = new HashMap<>();

    private Job(String group, Attempt attempt, DropListener onDropped) {
      this.group = group;
      this.attempt = attempt;
      this.onDropped = onDropped;
    }

    @Override
    public void run() {
      List<String> items;
      Duration lockWait;
      synchronized (PythonReadSyncExecutor.this) {
        running = true;
        future = null;
        items = List.copyOf(queued);
        queued.clear();
        lockWait = lockWaitFor(attempts);
      }
      attempts++;
      AttemptResult result = null;
      String permanentReason = "재시도해도 소용없는 실패";
      try {
        result = attempt.run(items, lockWait);
      } catch (RuntimeException e) {
        // 시도의 예외 처리(분류·로그)는 작업 쪽 책임이지만, 새어 나와도 스레드가 죽지 않게 받는다 — 무엇이 실패했는지 모르므로 영구 실패로 친다.
        log.error("PYTHON 읽기 권한 동기화 시도가 예외로 끝났다: {} {}", group, items, e);
      } catch (Error e) {
        // 치명 오류(OutOfMemoryError·AssertionError·StackOverflowError 등, WD-644 리뷰) — 시도한 항목은 버림으로
        // 세고(아래
        // finally), 오류는 다시 던진다. 스케줄러(ScheduledThreadPoolExecutor)는 작업의 throwable 을 그 작업의 future 에만
        // 담고
        // 스레드를 살려 두므로 다른 테넌트의 작업은 계속 돈다.
        log.error("PYTHON 읽기 권한 동기화 시도가 치명 오류로 끝났다 — 시도한 항목을 버린다: {} {}", group, items, e);
        permanentReason = "시도가 치명 오류(" + e.getClass().getSimpleName() + ")로 끝남";
        throw e;
      } finally {
        // 어떤 끝(정상·예외·Error)이든 실행 표시를 내리고 결과를 반영한다 — 안 하면 running=true 로 남아 이 테넌트의 커밋 뒤 동기화가 영영
        // 멈춘다. 결과가 없으면(예외·Error·null) 영구 실패로 친다: 시도한 항목은 세어 버리고, 시도 중 들어온 항목은 새 회차로 돈다.
        complete(items, result == null ? Outcome.PERMANENT_FAILURE : result, permanentReason);
      }
    }

    /**
     * 시도 한 번을 끝낸다 — 실행 표시를 내리고, 결과에 따라 항목을 되돌리거나 버림으로 세고, 할 일이 남았으면 다음 회차를 올린다(없으면 작업을 끝낸다).
     *
     * @param permanentReason 영구 실패로 버릴 때 남길 사유
     */
    private void complete(List<String> items, AttemptResult result, String permanentReason) {
      List<String> exhausted = List.of();
      List<String> lost = List.of();
      List<String> droppedNow = List.of();
      boolean finished = false;
      Duration next;
      synchronized (PythonReadSyncExecutor.this) {
        running = false;
        switch (result) {
          case Outcome.DONE -> {
            // 끝났다 — 그 사이 들어온 항목(뒤따르는 실행)이 있으면 곧바로 한 번 더. 격상(attempts)은 유지한다.
            items.forEach(failures::remove);
            retries = 0;
            next = Duration.ZERO;
          }
          case Outcome.BUSY -> {
            // 아무것도 하지 못했다 — 시도한 항목을 되돌린다(그 사이 들어온 항목은 이미 대기 항목에 있다).
            queued.addAll(items);
            next = retryDelay();
          }
          case Outcome.FAILED -> {
            // 묶음 전체의 일시 실패 — 항목마다 실패를 세고, 상한을 넘긴 항목만 버린다.
            exhausted = failTransient(items);
            next = retryDelay();
          }
          case Outcome.PERMANENT_FAILURE -> {
            // 영구 실패 — 다시 해도 같으므로 곧바로 버린다(뒤따르는 항목은 새 회차로 돈다).
            lost = dropPermanent(items);
            next = Duration.ZERO;
          }
          case PartialFailure p -> {
            // 잠금을 잡고 묶음을 돌았다 — 실패하지 않은 항목은 끝났으므로 다시 하지 않고, 실패한 항목만 규칙대로 다룬다(WD-644 리뷰).
            List<String> transientFailed = new ArrayList<>();
            List<String> permanentFailed = new ArrayList<>();
            for (String item : items) {
              if (p.permanentFailed().contains(item)) {
                permanentFailed.add(item);
              } else if (p.transientFailed().contains(item)) {
                transientFailed.add(item);
              } else {
                failures.remove(item);
              }
            }
            lost = dropPermanent(permanentFailed);
            exhausted = failTransient(transientFailed);
            if (transientFailed.size() > exhausted.size()) {
              next = retryDelay(); // 다시 할 일시 실패가 있다 — 간격을 두고
            } else {
              retries = 0;
              next = Duration.ZERO;
            }
          }
        }
        // 버림은 끝남(pending 감소)보다 먼저 센다 — awaitIdle 로 끝을 본 쪽이 버림 건수를 빠짐없이 보게.
        if (!exhausted.isEmpty()) {
          countDropped(
              exhausted.size(),
              "일시 실패가 "
                  + retryPolicy.maxFailures()
                  + "번 재시도 뒤에도 계속됨("
                  + group
                  + " "
                  + exhausted
                  + ")");
        }
        if (!lost.isEmpty()) {
          countDropped(lost.size(), permanentReason + "(" + group + " " + lost + ")");
        }
        if (!exhausted.isEmpty() || !lost.isEmpty()) {
          droppedNow = new ArrayList<>(exhausted);
          droppedNow.addAll(lost);
        }
        if (queued.isEmpty()) {
          // 할 일이 없다 — 끝남. 실행 중인 작업은 다른 경로가 빼지 않으므로 여기서 정확히 한 번 정리된다.
          // 끝남(pending 감소)은 모니터 밖에서 버림 콜백을 부른 뒤에 센다 — awaitIdle 로 끝을 본 쪽이 콜백(미확인 표식)까지 끝난 상태를 보게.
          finished = jobs.remove(group, this);
          next = null;
        }
      }
      // 버림 콜백은 모니터 밖에서(DropListener) — 재예약보다 먼저 불러, 버림 직후의 표식이 다음 시도보다 늦지 않게 한다.
      notifyDropped(onDropped, group, droppedNow);
      if (finished) {
        finish(1);
      }
      if (next != null) {
        // 잠금이 바쁘거나 일시 실패, 또는 뒤따르는 실행 — 기다리지 않고 다음 간격 뒤로 다시 올린다(그 사이 스레드는 다른 작업을 돌린다).
        schedule(this, next);
      }
    }

    /**
     * 일시 실패한 항목마다 실패를 하나 센다 — 상한 안이면 대기 항목으로 되돌리고, 넘으면 버릴 항목으로 돌려준다. 다만 시도 중 같은 항목이 다시 들어왔으면(뒤따르는
     * 실행) 그것은 대기 항목에 남아 새 회차로 돌고 버리지 않는다(세기도 처음부터). 실행기 모니터 안에서 부른다.
     */
    private List<String> failTransient(List<String> failed) {
      List<String> out = new ArrayList<>();
      for (String item : failed) {
        if (failures.merge(item, 1, Integer::sum) > retryPolicy.maxFailures()) {
          failures.remove(item);
          if (!queued.contains(item)) {
            out.add(item);
          }
        } else {
          queued.add(item);
        }
      }
      return out;
    }

    /** 영구 실패한 항목을 버린다 — 정말로 잃는 것(대기 항목에 다시 들어오지 않은 것)만 돌려준다. 실행기 모니터 안에서 부른다. */
    private List<String> dropPermanent(List<String> failed) {
      failed.forEach(failures::remove);
      return notQueued(failed);
    }

    /** 다음 재시도 앞의 대기 — 비우기 중이면 곧바로(줄 끝으로 가서 다른 테넌트와 번갈아 돈다). 실행기 모니터 안에서 부른다. */
    private Duration retryDelay() {
      return closing ? Duration.ZERO : retryPolicy.delayFor(retries++);
    }

    /** {@code items} 중 지금 대기 항목에 없는 것 — 버리면 정말로 잃는 항목. 실행기 모니터 안에서 부른다. */
    private List<String> notQueued(List<String> items) {
      List<String> out = new ArrayList<>();
      for (String item : items) {
        if (!queued.contains(item)) {
          out.add(item);
        }
      }
      return out;
    }
  }

  /**
   * 이번 시도의 잠금 대기 — 0 이면 시도형. 비우기 중이면 시도 수와 관계없이 묶인 대기로(시도형으로 바쁘면 비우기에서 빠질 수 있다), 그 길이는 비우기 제한 시간의
   * 1/{@link #DRAIN_WAIT_DIVISOR} 과 남은 시간 중 짧은 쪽(최소 1ms)이다. 실행기 모니터 안에서 부른다.
   */
  private Duration lockWaitFor(int attempts) {
    if (closing) {
      Duration perAttempt = min(retryPolicy.lockWait(), drainTimeout.dividedBy(DRAIN_WAIT_DIVISOR));
      Duration left = Duration.ofNanos(Math.max(0, drainDeadlineNanos - System.nanoTime()));
      Duration wait = min(perAttempt, left);
      return wait.compareTo(Duration.ofMillis(1)) < 0 ? Duration.ofMillis(1) : wait;
    }
    return attempts >= retryPolicy.tryAttempts() ? retryPolicy.lockWait() : Duration.ZERO;
  }

  private static Duration min(Duration a, Duration b) {
    return a.compareTo(b) <= 0 ? a : b;
  }

  /**
   * 실행 중이 아닌 작업을 잡 목록에서 빼고 pending 을 정확히 한 번 줄인 뒤, 들고 있던 항목을 버림으로 세고 ERROR 를 남긴다 — 재예약 거절·비우기 시간
   * 초과의 버림 지점. 이미 빠진 작업이면(다른 경로가 먼저 정리) 아무것도 하지 않는다.
   */
  private void retire(Job job, String dropReason) {
    List<String> items = null;
    synchronized (this) {
      if (jobs.remove(job.group, job)) {
        // 버림을 먼저 세고 끝낸다 — awaitIdle 로 끝을 본 쪽이 버림 건수를 빠짐없이 보게.
        items = List.copyOf(job.queued);
        countDropped(Math.max(1, items.size()), dropReason + "(" + job.group + " " + items + ")");
      }
    }
    if (items != null) {
      // 버림 콜백은 모니터 밖에서, 끝남(pending 감소)보다 먼저 — 위 complete 와 같은 순서.
      notifyDropped(job.onDropped, job.group, items);
      finish(1);
    }
  }

  /**
   * 버림 콜백을 부른다 — 콜백·항목이 없으면 아무것도 하지 않고, 콜백의 예외는 ERROR 로 끝낸다(DropListener). 실행기 모니터({@code this})
   * 밖에서 부른다.
   *
   * <p><b>{@link #lifecycleLock} 은 쥘 수 있다.</b> {@link #replaceScheduler}(lifecycleLock 안)가 옮긴 작업을 새
   * 스케줄러에 다시 올리다 거부되면({@link #schedule} → {@link #retire}) 이 콜백이 lifecycleLock 을 쥔 채 불린다. 그래서 콜백은
   * 짧아야 하고(지금 쓰는 콜백은 프로세스 메모리에 미확인 표식만 단다) 다른 스레드가 lifecycleLock 을 기다리게 만드는 일(다른 스레드와의 동기 대기)을 하면 안
   * 된다. 같은 스레드에서의 재제출은 모니터 재진입이라 막히지 않는다.
   */
  private static void notifyDropped(DropListener onDropped, String group, List<String> items) {
    if (onDropped == null || items.isEmpty()) {
      return;
    }
    try {
      onDropped.dropped(List.copyOf(items));
    } catch (RuntimeException e) {
      log.error("PYTHON 읽기 권한 동기화 버림 콜백 실패: {} {}", group, items, e);
    }
  }

  /** 버림 {@code count} 건을 누적하고 ERROR 로 남긴다 — 모든 버림 지점(포화·종료·실패 소진·영구 실패)의 셈과 로그를 같게 한다. */
  private void countDropped(int count, String reason) {
    if (count <= 0) {
      return;
    }
    log.error(
        "PYTHON 읽기 권한 동기화 {}건을 버렸다 — {}(누적 {}건). 회수가 빠졌을 수 있다 — 실행 직전·일 1회 동기화가 회복한다",
        count,
        reason,
        dropped.addAndGet(count));
  }

  /** 버린 누적 건수(항목 단위) — 테스트 확인용(WD-629). */
  long droppedCount() {
    return dropped.get();
  }

  /**
   * 작업 {@code count} 개가 끝났다(또는 버려졌다) — 0 이 되면 {@link #awaitIdle} 대기자를 깨운다. 음수가 되면 중복 계산 버그이므로 숨기지
   * 않고 ERROR 로 남긴다(WD-629) — 대기자는 그래도 깨워 awaitIdle 이 시간 초과까지 멈추지 않게 한다.
   */
  private synchronized void finish(int count) {
    pending -= count;
    if (pending == 0) {
      notifyAll();
    } else if (pending < 0) {
      log.error(
          "PYTHON 읽기 권한 동기화 대기 수가 음수가 됐다(pending={}, 방금 {}건 완료) — 완료를 두 번 센 버그", pending, count);
      notifyAll();
    }
  }

  /**
   * 넘긴 작업이 모두 끝날 때까지 최대 {@code timeout} 기다린다. 테스트 결정성용 seam 이자 종료 비우기의 대기 — 운영 코드에서는 {@link
   * #shutdown()} 만 부른다.
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

  /**
   * 라이프사이클 시작 — 앞선 stop 으로 비우는 중이거나 스케줄러가 종료돼 있으면 새 스케줄러로 바꿔 커밋 뒤 동기화를 다시 받아들인다(WD-629). 실행 중이면
   * 아무것도 하지 않는다(진행 중 작업·예약 유지).
   *
   * <p><b>옛 스케줄러가 아직 끝나지 않았어도 곧바로 바꾼다(WD-655).</b> 예전에는 비우기 제한 시간만큼 기다려도 옛 스케줄러가 안 끝나면(인터럽트를 무시하는
   * 시도) 바꾸지 않아, 그동안의 제출이 모두 버려졌다. 이제는 새 스케줄러의 첫 작업이 옛 스케줄러들의 끝을 기다리므로({@link #replaceScheduler})
   * 제출을 버리지 않으면서도 동기화가 동시에 둘 돌지 않는다. 비우기 도중의 start 는 그 비우기를 넘겨받는다 — 비우던 stop 은 남은 작업을 버리지 않고
   * 돌아오고({@link #awaitIdleOrRestart}·{@link #discardRemaining}), 남은 작업은 새 스케줄러에서 평소 규칙으로 마저 돈다. 교체가
   * {@link #lifecycleLock} 안이라 stop 과 겹쳐도 「켜짐인데 닫힌 스케줄러」나 「꺼짐인데 새 스케줄러」가 남지 않는다.
   */
  @Override
  public void start() {
    synchronized (lifecycleLock) {
      if (closing || scheduler.isShutdown()) {
        replaceScheduler();
      }
    }
  }

  /**
   * 현재 스케줄러를 새 단일 스레드 스케줄러로 바꾸고 비우기 상태를 끈다(WD-655). {@link #lifecycleLock} 을 쥔 채 불러야 한다 — 그래서 이
   * 메서드가 끝나기 전에는 어떤 예약도 끼어들지 못한다({@link #schedule} 도 같은 모니터를 쥔다).
   *
   * <ol>
   *   <li>옛 스케줄러에 예약만 걸려 있고 아직 돌지 않은 작업을 꺼낸다(남은 지연 기억). 옛 스케줄러에 남겨 두면, 새 스케줄러가 다시 비우기 시간 초과로 그 작업을
   *       버림으로 센 뒤에도 옛 스케줄러에서 돌아(버림·실행 이중 계산) 상태가 어긋난다. 이미 꺼내져 도는 중인 작업은 꺼내지 못하고, 끝나면 스스로 다음 시도를
   *       {@link #schedule} 로 현재(새) 스케줄러에 올린다.
   *   <li>옛 스케줄러를 닫는다(shutdown — 새 예약은 받지 않는다). 비우기 도중이면 아직 열려 있으므로 여기서 닫아야 끝날 수 있다.
   *   <li>아직 끝나지 않은 옛 스케줄러(들)가 있으면 새 스케줄러의 첫 작업으로 {@link AwaitRetiredSchedulers} 를 넣는다 — 지연 0 의 첫
   *       예약이라 뒤에 올라온 어떤 예약보다 먼저 돌고, 단일 스레드라 그동안 다른 작업은 돌지 않는다. 기다림은 {@link #retiredAwaitTimeout}
   *       으로 묶인다(WD-673).
   *   <li>새 스케줄러를 걸고 비우기 상태를 끈 뒤, 비우기를 기다리던 stop({@link #awaitIdleOrRestart})을 깨워 넘겨받았음을 알린다. 꺼낸
   *       작업은 그 뒤 새 스케줄러에 다시 올린다.
   * </ol>
   */
  private void replaceScheduler() {
    ScheduledThreadPoolExecutor old = scheduler;
    Map<Job, Duration> moved = new HashMap<>();
    synchronized (this) {
      for (Job job : jobs.values()) {
        if (!job.running && job.future != null && old.remove((Runnable) job.future)) {
          moved.put(job, Duration.ofNanos(Math.max(0, job.future.getDelay(TimeUnit.NANOSECONDS))));
          job.future = null;
        }
      }
    }
    old.shutdown();
    retired.removeIf(ScheduledThreadPoolExecutor::isTerminated);
    if (!old.isTerminated()) {
      retired.add(old);
    }
    ScheduledThreadPoolExecutor fresh = newScheduler();
    if (!retired.isEmpty()) {
      fresh.execute(new AwaitRetiredSchedulers(List.copyOf(retired), retiredAwaitTimeout));
    }
    synchronized (this) {
      scheduler = fresh;
      closing = false;
      notifyAll();
    }
    moved.forEach(this::schedule);
  }

  /**
   * 새 스케줄러의 첫 작업 — 옛 스케줄러들이 모두 끝날 때까지, 최대 {@code timeout} 기다린다(WD-655, 상한은 WD-673). 그동안 새 스케줄러에 올라온
   * 예약은 이 작업이 끝난 뒤에 돈다.
   *
   * <p><b>상한(WD-673).</b> 마감은 목록 전체에 하나다 — 옛 스케줄러마다 따로 재면 N 개면 N 배를 기다린다. 넘기면 ERROR 를 남기고 더 기다리지
   * 않는다(겹쳐 돌아도 안전한 이유는 클래스 설명). 끝나지 않은 옛 스케줄러는 {@link #retired} 에 그대로 남긴다 — 다음 재시작도 그 끝을 다시(상한까지)
   * 기다려, 그사이 끝났으면 순서 보장을 되찾고, 여전히 멈췄으면 같은 상한 뒤 진행한다(재시작은 드물어 그 지연은 감수한다).
   *
   * <p>인터럽트는 새 스케줄러의 shutdownNow({@link #discardRemaining})에서만 온다 — 그때는 남은 작업도 이미 정리했으므로 기다리기를
   * 그만두고, 인터럽트 상태를 되살린 채 끝낸다. pending 에는 세지 않는 내부 작업이다(submitRetryable 을 거치지 않는다).
   */
  private record AwaitRetiredSchedulers(
      List<ScheduledThreadPoolExecutor> previous, Duration timeout) implements Runnable {
    @Override
    public void run() {
      long deadline = System.nanoTime() + timeout.toNanos();
      for (ScheduledThreadPoolExecutor p : previous) {
        try {
          if (!p.awaitTermination(
              Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
            log.error(
                "PYTHON 읽기 권한 동기화 실행기: 이전 스케줄러가 {} 안에 끝나지 않아(인터럽트를 무시하고 멈춘 시도) 더 기다리지 않고 새"
                    + " 스케줄러의 작업을 진행한다 — 멈춘 시도는 끝나면 스스로 다음 회차를 올린다(그 테넌트의 동기화는 그때까지 멈춤, 실행 중 시도 {}개)",
                timeout,
                p.getActiveCount());
            return;
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  /**
   * 라이프사이클 종료 — DataSource 등 빈이 소멸되기 전에 남은 동기화를 제한 시간 안에 비운다. 이후 제출은 {@link #submitRetryable} 이
   * ERROR 와 함께 버린다(예외 없음). 다시 {@link #start()} 하면 새 스케줄러로 받아들인다.
   */
  @Override
  public void stop() {
    shutdown();
  }

  /**
   * 라이프사이클 상태(켜짐)를 돌려준다 — 비우기 중이 아니고 현재 스케줄러가 닫히지 않았으면 켜짐이다. 옛 스케줄러가 아직 끝나지 않았어도 start 뒤에는 true
   * 다(WD-655): SmartLifecycle 계약상 Spring 은 isRunning() 이 true 인 빈에만 stop() 을 부르므로, 옛 스케줄러의 생사를 섞으면
   * stop 이 건너뛰어진다.
   */
  @Override
  public boolean isRunning() {
    return !closing && !scheduler.isShutdown();
  }

  /**
   * 남은 동기화를 제한 시간 안에 비운다(WD-644 리뷰).
   *
   * <ol>
   *   <li>비우기 상태를 켠다 — 이 순간부터 새 제출(합치기 포함)은 버리고, 남은 모든 작업의 다음 시도는 짧은 묶인 대기로 잡는다(지금 실행 중인 시도도 다음
   *       시도부터).
   *   <li>재시도를 기다리던 작업은 예약에서 꺼내 지금 곧바로 다시 올린다(재시도 간격을 기다리지 않는다).
   *   <li>스케줄러를 닫지 않은 채 모두 끝나기를 제한 시간까지 기다린다 — 그동안 BUSY 는 거절되지 않고 줄 끝으로 다시 서서 다른 테넌트와 번갈아 돈다.
   *   <li>다 비웠으면 닫는다. 시간을 넘겼거나 인터럽트되면 즉시 멈추고 남은 작업을 세어 버린다(ERROR).
   * </ol>
   *
   * <p><b>비우는 도중 start(WD-655).</b> 기다리는 사이 다른 스레드의 start 가 새 스케줄러로 바꾸면 비우기를 넘겨받은 것이다 — 곧바로 기다리기를
   * 그만두고 남은 작업을 버리지 않은 채 돌아온다. 남은 작업은 새 스케줄러에서(옛 스케줄러가 끝난 뒤) 마저 돈다. 넘겨받은 뒤의 버림은 새 스케줄러의 다음 stop 이
   * 정한다.
   *
   * <p>{@link #stop()} 이 정상 경로이고, {@code @PreDestroy} 는 라이프사이클 stop 이 불리지 않은 경우(예: 컨텍스트 refresh
   * 실패)를 위한 안전망이다. 두 번째 호출(또는 다른 스레드가 비우는 중의 호출)은 비우기를 다시 하지 않고 그 스케줄러가 끝나기를 제한 시간까지 기다리기만 한다. 스케줄러
   * 참조는 지역 변수로 한 번 잡는다 — 비우는 동안 lifecycleLock 을 쥐지 않아 제출을 막지 않는다(WD-629).
   */
  @PreDestroy
  void shutdown() {
    ScheduledThreadPoolExecutor s;
    boolean drainHere = false;
    long deadline = System.nanoTime() + drainTimeout.toNanos();
    List<Job> waitingRetries = new ArrayList<>();
    synchronized (lifecycleLock) {
      synchronized (this) {
        s = scheduler;
        if (!closing && !s.isShutdown()) {
          drainHere = true;
          closing = true;
          drainDeadlineNanos = deadline;
          for (Job job : jobs.values()) {
            // remove 가 성공한 것만 — 그 사이 실행에 들어간 작업은 스스로 다음 시도를 올린다(이미 비우기 모드).
            if (!job.running
                && job.future != null
                && job.future.getDelay(TimeUnit.NANOSECONDS) > 0
                && s.remove((Runnable) job.future)) {
              job.future = null;
              waitingRetries.add(job);
            }
          }
        }
      }
    }
    if (!drainHere) {
      awaitTerminationQuietly(s, drainTimeout);
      return;
    }
    for (Job job : waitingRetries) {
      schedule(job, Duration.ZERO);
    }
    boolean idle;
    try {
      idle = awaitIdleOrRestart(s, deadline);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      idle = false;
    }
    if (idle) {
      s.shutdown();
      awaitTerminationQuietly(s, drainTimeout);
    } else {
      // 시간 초과·인터럽트, 또는 start 가 넘겨받음 — 넘겨받았는지는 discardRemaining 이 lifecycleLock 안에서 다시 가른다.
      discardRemaining(s);
    }
  }

  /**
   * 비우기의 기다림 — 진행 중 작업이 모두 끝나거나, start 가 {@code s} 를 새 스케줄러로 바꾸거나(넘겨받음, WD-655), {@code
   * deadlineNanos} 가 될 때까지 기다린다. 넘겨받으면 새 스케줄러로 간 작업이 pending 에 남아 있어 끝까지 기다리면 시간 초과까지 헛되이 멈추므로,
   * start 의 notifyAll 로 곧바로 깬다.
   *
   * @return 진행 중 작업이 모두 끝났으면 true(넘겨받았거나 시간을 넘겼으면 false)
   */
  private synchronized boolean awaitIdleOrRestart(ScheduledThreadPoolExecutor s, long deadlineNanos)
      throws InterruptedException {
    while (pending > 0 && scheduler == s) {
      long left = deadlineNanos - System.nanoTime();
      if (left <= 0) {
        return false;
      }
      TimeUnit.NANOSECONDS.timedWait(this, left);
    }
    return pending == 0;
  }

  /**
   * 스케줄러를 즉시 멈추고 돌지 못한 작업을 버린다(WD-629). 실행 중이 아닌 작업은 모두 다시 돌 일이 없으므로 여기서 정리하고(ERROR), 실행 중이던 작업은
   * 시도가 끝나면 스스로 정리한다(다시 예약하면 거절 → 버림). 그 시도는 비우기의 짧은 잠금 대기로 묶여 있으니 그만큼만 끝나기를 기다린다. shutdownNow 와 목록
   * 수집을 lifecycleLock 안에서 해 그 사이 start 가 새 스케줄러로 바꿔 새 작업을 이 목록에 섞지 못하게 한다. {@link #retire} 가 잡 목록
   * 제거로 한 번만 세므로 중복 계산이 없다.
   *
   * <p><b>이미 넘겨받았으면 아무것도 버리지 않는다(WD-655).</b> 그 사이 start 가 {@code s} 를 새 스케줄러로 바꿨다면 남은 작업은 새 스케줄러가
   * 이어 받는다 — 잡 목록에는 새 스케줄러의 작업도 섞여 있으므로 여기서 버리면 재시작 뒤의 제출까지 잃는다. {@code s} 는 start 가 이미 닫았고(올라간 예약은
   * 마저 돈다), 새 스케줄러의 첫 작업이 그 끝을 기다린다.
   */
  private void discardRemaining(ScheduledThreadPoolExecutor s) {
    List<Job> left = new ArrayList<>();
    synchronized (lifecycleLock) {
      if (scheduler != s) {
        log.info("PYTHON 읽기 권한 동기화 실행기: 비우는 도중 재시작돼 남은 작업을 새 스케줄러가 이어 받는다(버리지 않음)");
        return;
      }
      s.shutdownNow();
      synchronized (this) {
        for (Job job : jobs.values()) {
          if (!job.running) {
            left.add(job);
          }
        }
      }
    }
    for (Job job : left) {
      retire(job, "종료 시 비우기 제한 시간(" + drainTimeout + ") 초과");
    }
    awaitTerminationQuietly(
        s, min(retryPolicy.lockWait(), drainTimeout.dividedBy(DRAIN_WAIT_DIVISOR)));
  }

  /** 스케줄러가 끝나기를 최대 {@code timeout} 기다린다 — 인터럽트는 플래그만 되살린다. */
  private static void awaitTerminationQuietly(ScheduledThreadPoolExecutor s, Duration timeout) {
    try {
      s.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
