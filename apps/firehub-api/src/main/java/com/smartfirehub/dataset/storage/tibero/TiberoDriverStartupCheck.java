package com.smartfirehub.dataset.storage.tibero;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Tibero 저장소 기능이 켜져 있는데 JDBC 드라이버가 클래스패스에 없는 배포 결함을 기동 직후 한 번 알린다(WD-77).
 *
 * <p>api 이미지는 과거에 {@code libs/} 를 복사하지 않아 드라이버가 빠져 있었다. 그 상태로 기동하면 Tibero 테넌트를 처음 쓸 때야 접속 실패로
 * 드러나므로, 운영자가 로그에서 바로 원인을 찾게 ERROR 를 남긴다. 기동 자체는 막지 않는다 — PostgreSQL 테넌트는 드라이버와 무관하게 동작해야 하기 때문이다.
 */
@Component
public class TiberoDriverStartupCheck {

  private static final Logger log = LoggerFactory.getLogger(TiberoDriverStartupCheck.class);

  private final boolean tiberoEnabled;
  private final String driverClassName;

  /** 운영용 생성자 — 드라이버 클래스명은 {@link TiberoDialect#DRIVER_CLASS} 로 고정한다. */
  @Autowired
  public TiberoDriverStartupCheck(
      @Value("${app.data-store.tibero-enabled:false}") boolean tiberoEnabled) {
    this(tiberoEnabled, TiberoDialect.DRIVER_CLASS);
  }

  /** 테스트용 — 존재하지 않는 클래스명을 주입해 드라이버 누락 상황을 재현한다. */
  TiberoDriverStartupCheck(boolean tiberoEnabled, String driverClassName) {
    this.tiberoEnabled = tiberoEnabled;
    this.driverClassName = driverClassName;
  }

  /** 애플리케이션이 준비되면 한 번 실행한다. 기능이 꺼져 있으면 아무것도 하지 않는다. */
  @EventListener(ApplicationReadyEvent.class)
  public void check() {
    if (!tiberoEnabled) {
      return;
    }
    // 외부 DB 접속과 같은 판정(TiberoDialect.loadDriver)으로 본다 — 여기서 통과하면 첫 접속에서도 드라이버를 찾는다.
    Throwable e = TiberoDialect.loadDriver(driverClassName);
    if (e != null) {
      log.error(
          "Tibero 저장소가 켜져 있지만 JDBC 드라이버({})가 없습니다 — 이미지의 libs/ 에 tibero jar 를 넣어야 합니다. (원인: {}: {})",
          driverClassName,
          e.getClass().getSimpleName(),
          e.getMessage());
    }
  }
}
