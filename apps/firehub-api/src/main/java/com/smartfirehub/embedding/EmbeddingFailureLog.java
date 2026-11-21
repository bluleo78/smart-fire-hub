package com.smartfirehub.embedding;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;

/**
 * 임베딩 실패의 로그 출력을 한 곳에 모은다(WD-873). 원문은 {@link EmbeddingException#logDetail()} 로 가린 한 줄만 남는다.
 *
 * <p>같은 provider 가 계속 실패하면(예: 재임베딩 작업이 문서마다 실패) 줄이 쏟아지므로 provider 별 60초에 한 번만 남기고, 창이 다시 열릴 때 그 사이
 * 생략한 건수를 덧붙인다. 생략되는 호출에서는 상세 문자열을 만들지도 않는다.
 */
@Slf4j
public final class EmbeddingFailureLog {

  /** 같은 provider 의 경고를 다시 남기기까지의 간격(ms). */
  private static final long WINDOW_MS = 60_000L;

  private static final class State {
    long lastLoggedAt;
    int skipped;
  }

  private static final Map<String, State> STATES = new HashMap<>();

  // 벽시계(currentTimeMillis)는 뒤로 갈 수 있어 그만큼 모든 줄이 생략된다 — 단조 시계(nanoTime)를 ms 로 쓴다(WD-873 리뷰 후속).
  private static volatile LongSupplier clock = EmbeddingFailureLog::monotonicMillis;

  private EmbeddingFailureLog() {}

  /** provider 별 60초 창에서 한 번만 WARN 을 남긴다. 남기면 예외에 「이미 로그함」을 표시한다. */
  static void warnThrottled(String provider, EmbeddingException e) {
    int skipped;
    synchronized (STATES) {
      long now = clock.getAsLong();
      State s = STATES.get(provider);
      if (s != null && now - s.lastLoggedAt < WINDOW_MS) {
        s.skipped++;
        return;
      }
      if (s == null) {
        s = new State();
        STATES.put(provider, s);
      }
      skipped = s.skipped;
      s.skipped = 0;
      s.lastLoggedAt = now;
    }
    write(provider, e, skipped > 0 ? " (지난 60초 생략 " + skipped + "건)" : "");
  }

  /** 빈도 제한 없이 남긴다(사용자가 직접 누른 연결 테스트 등). 남기면 예외에 표시한다. */
  public static void warn(String context, EmbeddingException e) {
    write(context, e, "");
  }

  /**
   * 호출부가 실패를 다시 찍을 때 넘길 throwable — failure() 가 만든 임베딩 실패(원 예외를 따로 든 것)는 상세를 이미 여기서 가려 남겼으므로
   * null(스택 없이 문장만), 그 밖(평범한 임베딩 예외·cause 를 단 임베딩 예외·다른 예외)은 여기서 남기지 않았거나 원인이 스택에 있어 그대로 돌려준다(던진 곳을
   * 찾을 수 있게 — WD-873 리뷰 후속). SLF4J 는 마지막 인자가 null 이면 무시한다.
   */
  public static Throwable stackOrNull(Throwable e) {
    return e instanceof EmbeddingException ee && ee.isRedacted() ? null : e;
  }

  /** 한 줄을 남기고 예외에 「이미 로그함」을 표시한다. */
  private static void write(String context, EmbeddingException e, String suffix) {
    // 창을 이미 썼으니 상세 만들기가 실패해도 줄은 남긴다 — 줄 없이 창만 소모되면 60초 동안 원인이 보이지 않는다.
    log.warn("임베딩 호출 실패({}): {}{}", context, safeDetail(e), suffix);
    e.markLogged();
  }

  /** 가린 상세 — 만들다 실패하면 사용자 문장(이미 가린 문장)으로 대신한다. */
  private static String safeDetail(EmbeddingException e) {
    try {
      return e.logDetail();
    } catch (RuntimeException ex) {
      return e.getMessage();
    }
  }

  /** 단조 증가하는 ms — 창 간격 계산에만 쓴다(절대 시각이 아니다). */
  private static long monotonicMillis() {
    return System.nanoTime() / 1_000_000L;
  }

  /** 시험용 — 시각 공급자를 바꾼다. */
  static void setClock(LongSupplier millis) {
    clock = millis;
  }

  /** 시험용 — 상태와 시각 공급자를 원래대로 돌린다. */
  static void reset() {
    synchronized (STATES) {
      STATES.clear();
    }
    clock = EmbeddingFailureLog::monotonicMillis;
  }
}
