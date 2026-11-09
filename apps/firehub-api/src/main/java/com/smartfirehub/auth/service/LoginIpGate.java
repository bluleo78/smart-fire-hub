package com.smartfirehub.auth.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.global.util.ClientIpExtractor;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 같은 IP 키의 로그인을 <b>로그인 트랜잭션 바깥</b>(DB 커넥션을 빌리기 전)에서 한 번에 하나씩 들여보내는 JVM 안 게이트(WD-630).
 *
 * <p>왜 필요한가: IP 기준 제한(WD-578)은 같은 IP 키의 검사·실패 기록을 DB advisory 잠금({@link
 * LoginIpThrottleService#blockedKey})으로 줄 세운다. 그 잠금은 로그인 트랜잭션 <b>안</b>에서 잡히므로, 같은 IP 키(사내 NAT·한 계정
 * 연속 로그인)의 로그인이 동시에 N 개 오면 앞 요청이 BCrypt·커밋을 마칠 때까지 나머지가 <b>각자 커넥션을 쥔 채</b> 기다렸다. Hikari 풀이 이들로 다 차면
 * 다른 모든 API 가 커넥션을 못 얻어 멈춘다. 게이트는 같은 키의 다음 로그인을 커넥션 없이 스레드만으로 기다리게 해, 한 인스턴스에서 같은 키가 쥐는 커넥션을 1개로
 * 묶는다. DB 잠금은 그대로 남아 여러 API 인스턴스 사이의 줄 세우기를 맡는다 — 그 잠금에서 커넥션을 쥔 채 기다리는 것은 인스턴스당 하나뿐이다.
 *
 * <p>사용법: 컨트롤러가 {@code @Transactional} 로그인 서비스를 이 게이트 <b>안에서</b> 부른다({@code loginIpGate.run(() ->
 * authService.login(request))}). 그러면 잠금 → 트랜잭션 시작(커넥션 대여) → 커밋·롤백(커넥션 반납) → 잠금 해제 순서가 된다. 서비스 안에서
 * 자기 호출로 감싸면 트랜잭션 프록시를 우회하므로 그렇게 하지 않는다.
 *
 * <ul>
 *   <li>키 — {@link LoginIpThrottleService#keyFor} 그대로(IPv4 주소·IPv6 /64·해석 불가 공용 키). 키가 없으면(IP 모름·제외
 *       대역) 게이트를 타지 않는다 — DB 잠금도 잡지 않는 요청이라 줄 세울 이유가 없다.
 *   <li>잠금 객체 — 키별 공정(FIFO) {@link ReentrantLock} 을 참조 수와 함께 맵에 두고, 마지막 사용자가 나가면 맵에서 지운다. 키는 공격자가
 *       고를 수 있는 입력(IP·IPv6 /64)이라 지우지 않으면 맵이 끝없이 커진다. 고정 개수로 나누는(striped) 방식은 서로 다른 키가 우연히 같은 줄에
 *       서므로 쓰지 않는다.
 *   <li>기다림 상한 — {@code app.security.login-ip-limit.gate-wait}(기본 10초). 넘으면 429({@link
 *       CodedApiException}, 코드 {@link #BUSY_CODE}, {@link #BUSY_MESSAGE})로 거절한다 — 계정 잠금·IP 제한과 코드로
 *       구분된다. 로그인 본문을 아예 부르지 않으므로 IP·계정 실패 카운터 어느 것도 오르지 않는다 — 몰린 것이지 틀린 것이 아니다. 기다리는 중 인터럽트되면 인터럽트
 *       상태를 되살리고 같은 거절을 한다.
 *   <li>키당 줄 길이 상한 — {@code app.security.login-ip-limit.gate-max-waiters}(기본 20, 잡은 요청 포함). 이미 이만큼
 *       줄 서 있으면 기다리지 않고 바로 429 로 거절한다(WD-630 리뷰). 상한이 없으면 한 IP 가 로그인을 쏟아부을 때 대기자마다 Tomcat 요청 스레드를
 *       기다림 상한까지 붙잡아, 고갈이 Hikari 풀에서 서블릿 스레드 풀로 옮겨 갈 뿐이다.
 *   <li>거절 로그 — 거절(기다림 초과·줄 초과·인터럽트)은 warn 로그로 남기되 IP 키·사유당 1분에 1건으로 합친다(폭주 때 로그가 넘치지 않게). 감사 행은
 *       남기지 않는다 — 실패 시도가 아니라 혼잡이다.
 * </ul>
 */
@Slf4j
@Component
public class LoginIpGate {

  /** 혼잡 거절 코드(429) — 웹이 계정 잠금·IP 제한(코드 없음)과 구분할 수 있게 한다. */
  public static final String BUSY_CODE = "LOGIN_BUSY";

  /** 같은 IP 키의 로그인이 몰려 들여보내지 못했을 때의 응답 메시지(429). 실패로 세지 않는다. */
  public static final String BUSY_MESSAGE = "로그인 요청이 몰려 있습니다. 잠시 후 다시 시도해 주세요.";

  private final LoginIpThrottleService loginIpThrottleService;
  private final ClientIpExtractor clientIpExtractor;

  /** 같은 IP 키의 앞 로그인을 기다리는 최대 시간. */
  private final Duration maxWait;

  /** 한 IP 키에 동시에 있을 수 있는 요청 수(잡은 요청 + 기다리는 요청). 넘으면 기다리지 않고 거절한다. */
  private final int maxWaiters;

  /** 지금 쓰이는(잡았거나 기다리는) IP 키별 잠금. 참조 수가 0 이 되면 지운다 — 키가 쌓이지 않는다. */
  private final ConcurrentHashMap<String, KeyLock> locks = new ConcurrentHashMap<>();

  /**
   * 최근 1분 안에 로그를 남긴 「사유:IP 키」. 폭주 때 거절마다 warn 을 찍지 않도록 합친다 — AuthService 의 거절 감사 합침({@code
   * recentRejections})과 같은 방식. 크기 상한을 둬 키가 공격자 입력이어도 메모리가 묶인다.
   */
  private final Cache<String, Boolean> recentRejectionLogs =
      Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(1)).maximumSize(10_000).build();

  /**
   * @param loginIpThrottleService IP 키 계산({@link LoginIpThrottleService#keyFor}) — 서비스의 DB 잠금과 같은
   *     키로 줄 세운다
   * @param clientIpExtractor 요청 IP 를 신뢰 프록시 기준으로 고른다(WD-402) — 로그인 서비스와 같은 값
   * @param maxWait 기다림 상한(기본 10초)
   * @param maxWaiters 키당 동시 요청 상한(잡은 요청 포함, 기본 20). 1 미만이면 기동을 실패시킨다 — 모든 로그인을 막는 설정을 조용히 받지 않는다
   */
  public LoginIpGate(
      LoginIpThrottleService loginIpThrottleService,
      ClientIpExtractor clientIpExtractor,
      @Value("${app.security.login-ip-limit.gate-wait:10s}") Duration maxWait,
      @Value("${app.security.login-ip-limit.gate-max-waiters:20}") int maxWaiters) {
    if (maxWaiters < 1) {
      throw new IllegalArgumentException(
          "app.security.login-ip-limit.gate-max-waiters 는 1 이상이어야 합니다: " + maxWaiters);
    }
    this.loginIpThrottleService = loginIpThrottleService;
    this.clientIpExtractor = clientIpExtractor;
    this.maxWait = maxWait;
    this.maxWaiters = maxWaiters;
  }

  /**
   * 현재 HTTP 요청의 클라이언트 IP 키로 줄을 서서 로그인을 실행한다. 요청 컨텍스트가 없으면(내부 호출) IP 가 null 이라 게이트 없이 바로 실행한다.
   *
   * @param login 로그인 서비스 호출({@code @Transactional} 경계가 이 안에서 열리고 닫혀야 한다)
   * @return 로그인 결과
   * @throws CodedApiException 429 {@link #BUSY_CODE} — 기다림 상한 초과·줄 길이 초과·인터럽트
   */
  public <T> T run(Supplier<T> login) {
    return run(clientIpExtractor.currentRequestIp(), login);
  }

  /**
   * 주어진 클라이언트 IP 의 키로 줄을 서서 로그인을 실행한다. 로그인이 성공하든 예외로 끝나든 잠금과 맵 참조를 돌려준다.
   *
   * @param clientIp 클라이언트 IP(null 가능 — 게이트 없음)
   * @param login 로그인 서비스 호출
   * @return 로그인 결과
   * @throws CodedApiException 429 {@link #BUSY_CODE} — 기다림 상한 초과·줄 길이 초과·인터럽트. 이때 {@code login} 은
   *     불리지 않는다
   */
  public <T> T run(String clientIp, Supplier<T> login) {
    Optional<String> key = loginIpThrottleService.keyFor(clientIp);
    if (key.isEmpty()) {
      return login.get();
    }
    String ipKey = key.get();
    // 참조 수 증감은 같은 키에 대한 compute 안에서만 한다 — 맵이 키 단위로 원자성을 보장해, 지우는 순간 다른 스레드가 같은 객체를 집어 가는 경쟁이 없다.
    // 줄이 이미 상한이면 참조를 늘리지 않고(admitted=false) 바로 거절한다 — 기다리며 요청 스레드를 붙잡지 않게.
    boolean[] admitted = new boolean[1];
    KeyLock entry =
        locks.compute(
            ipKey,
            (k, existing) -> {
              KeyLock lock = existing == null ? new KeyLock() : existing;
              if (lock.users >= maxWaiters) {
                return lock;
              }
              lock.users++;
              admitted[0] = true;
              return lock;
            });
    if (!admitted[0]) {
      // 참조를 늘리지 않았으므로 돌려줄 것도 없다 — 줄에 있는 다른 요청들이 나가면서 맵을 비운다.
      throw reject(ipKey, Rejection.TOO_MANY_WAITERS);
    }
    try {
      acquire(ipKey, entry.lock);
      try {
        return login.get();
      } finally {
        entry.lock.unlock();
      }
    } finally {
      // 기다리다 거절된 요청도 참조를 돌려줘야 맵에서 지워진다.
      locks.computeIfPresent(ipKey, (k, existing) -> --existing.users == 0 ? null : existing);
    }
  }

  /** 상한까지 잠금을 기다린다. 못 잡거나 인터럽트되면 429 로 거절한다(인터럽트 상태는 되살린다). */
  private void acquire(String ipKey, ReentrantLock lock) {
    boolean acquired;
    try {
      acquired = lock.tryLock(maxWait.toNanos(), TimeUnit.NANOSECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw reject(ipKey, Rejection.INTERRUPTED);
    }
    if (!acquired) {
      throw reject(ipKey, Rejection.TIMEOUT);
    }
  }

  /**
   * 거절 예외를 만들고, 같은 사유·IP 키로는 1분에 한 번만 warn 로그를 남긴다. 감사 행은 남기지 않는다(혼잡이지 실패 시도가 아니다).
   *
   * @return 던질 429 예외
   */
  private CodedApiException reject(String ipKey, Rejection reason) {
    if (recentRejectionLogs.asMap().putIfAbsent(reason.name() + ":" + ipKey, Boolean.TRUE)
        == null) {
      log.warn(
          "로그인 IP 게이트 거절(WD-630): ipKey={}, reason={}, maxWait={}, maxWaiters={} — 같은 키·사유는 1분간 생략",
          ipKey,
          reason,
          maxWait,
          maxWaiters);
    }
    return new CodedApiException(HttpStatus.TOO_MANY_REQUESTS, BUSY_CODE, BUSY_MESSAGE);
  }

  /** 맵에 남아 있는 키 수(테스트용) — 아무도 쓰지 않으면 0 이어야 한다. */
  int activeKeys() {
    return locks.size();
  }

  /** 그 IP 의 잠금을 기다리는(잡은 요청 제외) 요청 수(테스트용). 키가 없거나 맵에 없으면 0. */
  int queueLength(String clientIp) {
    return loginIpThrottleService
        .keyFor(clientIp)
        .map(locks::get)
        .map(entry -> entry.lock.getQueueLength())
        .orElse(0);
  }

  /** 게이트 거절 사유(로그용). */
  private enum Rejection {
    TIMEOUT,
    TOO_MANY_WAITERS,
    INTERRUPTED
  }

  /** 키 하나의 잠금과 그 키를 쓰는(잡았거나 기다리는) 요청 수. {@code users} 는 맵의 compute 안에서만 바꾼다. */
  private static final class KeyLock {
    /** 공정 잠금 — 먼저 온 로그인이 먼저 들어간다(뒤에 온 요청이 앞질러 앞 요청만 상한에 걸리는 일을 줄인다). */
    private final ReentrantLock lock = new ReentrantLock(true);

    private int users;
  }
}
