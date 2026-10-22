package com.smartfirehub.dataset.storage.tibero;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * {@link TiberoDriverStartupCheck} 단위 테스트(WD-77) — 로그를 캡처해 드라이버 누락 시 ERROR 1건, 꺼짐/존재 시 무로그를 검증한다.
 */
class TiberoDriverStartupCheckTest {

  private final Logger logger = (Logger) LoggerFactory.getLogger(TiberoDriverStartupCheck.class);
  private ListAppender<ILoggingEvent> appender;

  @BeforeEach
  void attach() {
    appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void detach() {
    logger.detachAppender(appender);
  }

  @Test
  void 기능이_켜져_있고_드라이버가_없으면_ERROR_로그_1건() {
    new TiberoDriverStartupCheck(true, "no.such.TiberoDriver").check();

    assertThat(appender.list).hasSize(1);
    ILoggingEvent event = appender.list.get(0);
    assertThat(event.getLevel()).isEqualTo(Level.ERROR);
    assertThat(event.getFormattedMessage()).contains("no.such.TiberoDriver").contains("libs/");
  }

  @Test
  void 기능이_꺼져_있으면_드라이버가_없어도_로그가_없다() {
    new TiberoDriverStartupCheck(false, "no.such.TiberoDriver").check();

    assertThat(appender.list).isEmpty();
  }

  @Test
  void 기능이_켜져_있고_드라이버가_있으면_로그가_없다() {
    new TiberoDriverStartupCheck(true, "java.lang.String").check();

    assertThat(appender.list).isEmpty();
  }
}
