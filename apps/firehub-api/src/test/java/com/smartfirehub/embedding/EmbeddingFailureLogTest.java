package com.smartfirehub.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** EmbeddingFailureLog 단위 테스트(WD-873) — provider 별 60초 빈도 제한과 생략 건수 보고를 고정한다. */
class EmbeddingFailureLogTest {

  private final Logger logger = (Logger) LoggerFactory.getLogger(EmbeddingFailureLog.class);
  private final AtomicLong now = new AtomicLong(1_000_000L);
  private ListAppender<ILoggingEvent> appender;

  @BeforeEach
  void attach() {
    appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    EmbeddingFailureLog.reset();
    EmbeddingFailureLog.setClock(now::get);
  }

  @AfterEach
  void detach() {
    logger.detachAppender(appender);
    EmbeddingFailureLog.reset();
  }

  private static EmbeddingException ex(Throwable raw) {
    return EmbeddingException.redacted("x", raw);
  }

  @Test
  void throttledLogsOncePerWindowThenReportsSkipped() {
    long t0 = now.get();
    EmbeddingFailureLog.warnThrottled("Ollama", ex(new RuntimeException("a")));
    now.set(t0 + 10_000);
    EmbeddingFailureLog.warnThrottled("Ollama", ex(new RuntimeException("b")));
    now.set(t0 + 20_000);
    EmbeddingFailureLog.warnThrottled("Ollama", ex(new RuntimeException("c")));
    assertThat(appender.list).hasSize(1);
    assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
    assertThat(appender.list.get(0).getFormattedMessage()).doesNotContain("생략");

    now.set(t0 + 61_000);
    EmbeddingFailureLog.warnThrottled("Ollama", ex(new RuntimeException("d")));
    assertThat(appender.list).hasSize(2);
    assertThat(appender.list.get(1).getFormattedMessage()).contains("지난 60초 생략 2건");
  }

  @Test
  void throttleIsPerProvider() {
    EmbeddingFailureLog.warnThrottled("Ollama", ex(new RuntimeException("a")));
    EmbeddingFailureLog.warnThrottled("OpenAI", ex(new RuntimeException("b")));
    assertThat(appender.list).hasSize(2);
  }

  @Test
  void skippedFailureDoesNotBuildDetail() {
    AtomicInteger calls = new AtomicInteger();
    RuntimeException counting =
        new RuntimeException("m") {
          @Override
          public String getMessage() {
            calls.incrementAndGet();
            return "m";
          }
        };
    EmbeddingException first = ex(new RuntimeException("first"));
    EmbeddingException skipped = ex(counting);
    EmbeddingFailureLog.warnThrottled("Ollama", first);
    EmbeddingFailureLog.warnThrottled("Ollama", skipped);
    assertThat(calls.get()).isZero();
    assertThat(appender.list).hasSize(1);
    // 연결 테스트가 기대는 계약 — 남긴 실패만 표시되고, 생략된 실패는 표시되지 않아 나중에 남길 수 있다(WD-873).
    assertThat(first.isLogged()).isTrue();
    assertThat(skipped.isLogged()).isFalse();
  }

  @Test
  void detailFailureStillWritesLine() {
    // 상세 만들기가 터져도 창을 쓴 줄은 남는다 — 사용자 문장으로 대신한다.
    RuntimeException broken =
        new RuntimeException("b") {
          @Override
          public String getMessage() {
            throw new IllegalStateException("boom");
          }
        };
    EmbeddingException e = EmbeddingException.redacted("문장", broken);
    EmbeddingFailureLog.warnThrottled("Ollama", e);
    assertThat(appender.list).hasSize(1);
    assertThat(appender.list.get(0).getFormattedMessage()).contains("문장");
    assertThat(e.isLogged()).isTrue();
  }

  @Test
  void warnAlwaysLogsAndMarks() {
    EmbeddingException e = ex(new RuntimeException("a"));
    assertThat(e.isLogged()).isFalse();
    EmbeddingFailureLog.warn("연결 테스트", e);
    EmbeddingFailureLog.warn("연결 테스트", e);
    assertThat(appender.list).hasSize(2);
    assertThat(e.isLogged()).isTrue();
  }
}
