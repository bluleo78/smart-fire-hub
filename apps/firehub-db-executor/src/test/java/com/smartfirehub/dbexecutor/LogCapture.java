package com.smartfirehub.dbexecutor;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.slf4j.LoggerFactory;

/**
 * 테스트용 로그 캡처 — 한 클래스의 로거에 logback {@link ListAppender} 를 붙여 남긴 이벤트를 모은다. 표준 출력 캡처와 달리 이벤트별
 * 수준·메시지·예외 첨부 여부({@link ILoggingEvent#getThrowableProxy()})를 단언할 수 있어, "예외 객체를 로거에 넘기지 않았다"(원인 체인의
 * 비밀번호가 가려지지 않은 채 찍히지 않는다)를 직접 확인한다.
 */
public final class LogCapture implements AutoCloseable {

  private final Logger logger;
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

  private LogCapture(Class<?> type) {
    this.logger = (Logger) LoggerFactory.getLogger(type);
    appender.start();
    logger.addAppender(appender);
  }

  /** {@code type} 의 로거를 캡처하기 시작한다. try-with-resources 로 닫으면 떼어 낸다. */
  public static LogCapture of(Class<?> type) {
    return new LogCapture(type);
  }

  /** 지금까지 남은 이벤트(복사본). */
  public List<ILoggingEvent> events() {
    return List.copyOf(appender.list);
  }

  @Override
  public void close() {
    logger.detachAppender(appender);
    appender.stop();
  }
}
