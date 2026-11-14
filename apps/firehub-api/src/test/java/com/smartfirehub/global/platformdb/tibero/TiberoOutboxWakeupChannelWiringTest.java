package com.smartfirehub.global.platformdb.tibero;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.TimeZone;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Tibero 설치본에서 아웃박스 깨우기 채널 빈이 스프링 컨텍스트로 만들어지는지 본다(WD-724 새 설치 점검에서 발견).
 *
 * <p>왜: 이 클래스는 운영 생성자와 시험용 패키지 생성자 둘을 가진다. 고를 생성자를 {@code @Autowired} 로 정하지 않으면 스프링은 기본 생성자를 찾다 「No
 * default constructor found」로 Tibero 플랫폼 기동 전체를 멈춘다. 기존 시험은 생성자를 직접 불러 이것을 잡지 못했다.
 */
class TiberoOutboxWakeupChannelWiringTest {

  private final TimeZone original = TimeZone.getDefault();

  @AfterEach
  void restoreTimeZone() {
    TimeZone.setDefault(original);
  }

  @Test
  void tiberoContextBuildsWakeupChannelThroughProductionConstructor() {
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    new ApplicationContextRunner()
        .withPropertyValues("firehub.platform-db.type=tibero")
        // 권한 점검(probe)은 목 DataSource 에서 실패하지만 기동을 막지 않는다(경고 후 계속) — 여기서는 빈 생성만 본다.
        .withBean(DataSource.class, () -> mock(DataSource.class))
        .withBean(DataSourceProperties.class, DataSourceProperties::new)
        .withUserConfiguration(TiberoOutboxWakeupChannel.class)
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              assertThat(ctx).hasSingleBean(TiberoOutboxWakeupChannel.class);
            });
  }
}
