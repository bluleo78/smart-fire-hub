package com.smartfirehub.dbexecutor.health;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Status;

/** 드라이버 유무에 따른 health 상태와 기동 시 ERROR 로그. 실제 Class.forName 경로를 클래스 이름 주입으로 검증한다. */
class TiberoDriverHealthIndicatorTest {

  private final Logger logger = (Logger) LoggerFactory.getLogger(TiberoDriverHealthIndicator.class);
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

  @BeforeEach
  void attach() {
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void detach() {
    logger.detachAppender(appender);
  }

  @Test
  void missingDriver_down_andSingleErrorLogAtStartup() {
    var indicator = new TiberoDriverHealthIndicator("com.example.DoesNotExist");
    indicator.logIfMissing();
    var h = indicator.health();
    indicator.health(); // health 호출은 로그를 늘리지 않는다.
    assertThat(h.getStatus()).isEqualTo(Status.DOWN);
    assertThat(h.getDetails()).containsEntry("driver", "missing");
    assertThat(appender.list).hasSize(1);
    assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.ERROR);
  }

  @Test
  void presentDriver_up_noErrorLog() {
    var indicator = new TiberoDriverHealthIndicator("java.lang.String");
    indicator.logIfMissing();
    assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    assertThat(appender.list).isEmpty();
  }

  @Test
  void defaultDriverClass_matchesClasspath() {
    // 기본 생성자는 Tibero 드라이버를 본다: jar 유무에 맞는 상태여야 한다.
    boolean onClasspath;
    try {
      Class.forName("com.tmax.tibero.jdbc.TbDriver", false, getClass().getClassLoader());
      onClasspath = true;
    } catch (ClassNotFoundException e) {
      onClasspath = false;
    }
    assertThat(new TiberoDriverHealthIndicator().health().getStatus())
        .isEqualTo(onClasspath ? Status.UP : Status.DOWN);
  }
}
