package com.smartfirehub.global.platformdb.tibero.flyway;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JobRunr 재시도 시험용 작업(WD-488). 같은 키로 처음 부르면 실패하고 두 번째부터 성공한다.
 *
 * <p>왜 별도 public 클래스인가: JobRunr 워커가 리플렉션으로 작업 메서드를 부르므로 선언 클래스가 public 이어야 한다(패키지 전용 테스트 클래스의
 * static 메서드는 {@code IllegalAccessException} 으로 실패했다 — 실측).
 */
public final class TiberoPocFlakyJob {

  /** 키별 실행 횟수. */
  static final ConcurrentHashMap<String, AtomicInteger> ATTEMPTS = new ConcurrentHashMap<>();

  private TiberoPocFlakyJob() {}

  /** 첫 시도는 실패, 두 번째는 성공. */
  public static void run(String key) {
    run(key, 0);
  }

  /** 첫 시도는 {@code delayMillis} 기다린 뒤 실패, 두 번째는 성공 — 서버 기동 직후 경합인지 가르기 위한 변형. */
  public static void run(String key, long delayMillis) {
    int n = ATTEMPTS.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
    if (n == 1) {
      if (delayMillis > 0) {
        try {
          Thread.sleep(delayMillis);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      throw new IllegalStateException("WD-488 의도한 첫 실패");
    }
  }
}
