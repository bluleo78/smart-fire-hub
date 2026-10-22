package com.smartfirehub.dbexecutor.health;

import com.smartfirehub.dbexecutor.store.TiberoSession;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** Tibero JDBC 드라이버 클래스가 클래스패스에 있는지 본다. jar 는 저장소에 없으므로 빠지기 쉬운 배포 실수를 헬스와 기동 로그로 드러낸다. */
@Component("tiberoDriver")
public class TiberoDriverHealthIndicator implements HealthIndicator {

  private static final Logger log = LoggerFactory.getLogger(TiberoDriverHealthIndicator.class);

  private final String driverClass;

  @Autowired
  public TiberoDriverHealthIndicator() {
    this(TiberoSession.DRIVER_CLASS);
  }

  /** 테스트용: 확인할 드라이버 클래스 이름을 바꿔 끼운다. */
  TiberoDriverHealthIndicator(String driverClass) {
    this.driverClass = driverClass;
  }

  /** {@code Class.forName} 으로 클래스 존재를 확인한다(초기화는 하지 않는다). */
  private boolean present() {
    try {
      Class.forName(driverClass, false, getClass().getClassLoader());
      return true;
    } catch (ClassNotFoundException | LinkageError e) {
      return false;
    }
  }

  /** 기동 시 드라이버가 없으면 ERROR 를 한 번 남긴다. */
  @PostConstruct
  void logIfMissing() {
    if (!present()) {
      log.error("Tibero JDBC 드라이버({})를 찾을 수 없습니다. libs/ 에 tibero*.jar 를 넣어 다시 빌드하세요.", driverClass);
    }
  }

  @Override
  public Health health() {
    if (present()) {
      return Health.up().withDetail("driver", "present").build();
    }
    return Health.down().withDetail("driver", "missing").build();
  }
}
