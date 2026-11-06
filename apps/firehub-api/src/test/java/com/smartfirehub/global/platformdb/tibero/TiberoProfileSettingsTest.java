package com.smartfirehub.global.platformdb.tibero;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataset.storage.tibero.TiberoDialect;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * {@code application-tibero.yml}(WD-496) 의 값 대조 — 세션 초기화 문장·검증 쿼리는 데이터 저장소 Tibero 풀과 같아야 한다({@link
 * TiberoDialect}). 설정 파일에 문장을 옮겨 적었으므로 두 곳이 어긋나면 여기서 잡는다.
 */
class TiberoProfileSettingsTest {

  private static PropertySource<?> profile() throws IOException {
    return new YamlPropertySourceLoader()
        .load("tibero", new ClassPathResource("application-tibero.yml"))
        .get(0);
  }

  @Test
  void 앱_풀과_Flyway_연결이_데이터_저장소_풀과_같은_세션_문장을_쓴다() throws IOException {
    PropertySource<?> p = profile();

    assertThat(String.valueOf(p.getProperty("spring.datasource.hikari.connection-init-sql")))
        .isEqualTo(TiberoDialect.SESSION_INIT_SQL);
    assertThat(String.valueOf(p.getProperty("spring.flyway.init-sql")))
        .isEqualTo(TiberoDialect.SESSION_INIT_SQL);
    assertThat(String.valueOf(p.getProperty("spring.datasource.hikari.connection-test-query")))
        .isEqualTo(TiberoDialect.VALIDATION_QUERY);
  }

  @Test
  void 플랫폼_DB_종류와_Tibero_전용_마이그레이션_위치를_고른다() throws IOException {
    PropertySource<?> p = profile();

    assertThat(String.valueOf(p.getProperty("firehub.platform-db.type"))).isEqualTo("tibero");
    assertThat(String.valueOf(p.getProperty("spring.flyway.locations")))
        .isEqualTo("classpath:db/migration-tibero");
    assertThat(String.valueOf(p.getProperty("spring.flyway.fail-on-missing-locations")))
        .isEqualTo("true");
    // 기본 설정의 baseline-on-migrate(PG 용)를 물려받으면 기준선이 건너뛰어질 수 있다(WD-490).
    assertThat(String.valueOf(p.getProperty("spring.flyway.baseline-on-migrate")))
        .isEqualTo("false");
    assertThat(String.valueOf(p.getProperty("spring.datasource.driver-class-name")))
        .isEqualTo(TiberoDialect.DRIVER_CLASS);
  }
}
