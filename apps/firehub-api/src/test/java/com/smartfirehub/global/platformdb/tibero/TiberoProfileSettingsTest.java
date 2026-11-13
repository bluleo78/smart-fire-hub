package com.smartfirehub.global.platformdb.tibero;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataset.storage.tibero.TiberoDialect;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * {@code application-tibero.yml}(WD-496) 의 값 대조 — 세션 초기화 문장·검증 쿼리는 데이터 저장소 Tibero 풀과 같아야 한다({@link
 * TiberoDialect}, 앱 풀은 맨 앞 CURRENT_SCHEMA 문장만 더한다 — WD-492). 설정 파일에 문장을 옮겨 적었으므로 두 곳이 어긋나면 여기서 잡는다.
 */
class TiberoProfileSettingsTest {

  private static PropertySource<?> profile() throws IOException {
    return new YamlPropertySourceLoader()
        .load("tibero", new ClassPathResource("application-tibero.yml"))
        .get(0);
  }

  /** 앱 풀 CURRENT_SCHEMA 에 들어가는 소유자 스키마 자리표시자(WD-492) — 런타임 계정이 한정 없는 이름을 소유자 객체로 푼다. */
  private static final String OWNER_SCHEMA_PLACEHOLDER =
      "${firehub.platform-db.tibero.owner-schema}";

  @Test
  void 앱_풀과_Flyway_연결이_데이터_저장소_풀과_같은_세션_문장을_쓴다() throws IOException {
    PropertySource<?> p = profile();

    // 앱 풀 = CURRENT_SCHEMA 문장 + 데이터 저장소 풀과 같은 NLS 블록 본문. Flyway 는 소유자가 돌려 CURRENT_SCHEMA 가 필요 없다.
    assertThat(String.valueOf(p.getProperty("spring.datasource.hikari.connection-init-sql")))
        .isEqualTo(TiberoDialect.sessionInitSql(OWNER_SCHEMA_PLACEHOLDER));
    assertThat(String.valueOf(p.getProperty("spring.flyway.init-sql")))
        .isEqualTo(TiberoDialect.PLATFORM_SESSION_INIT_SQL);
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

  /**
   * 소유자 스키마(WD-492)는 기본으로 Flyway 계정 — 기존 설치본은 따로 설정하지 않아도 같은 값이 된다. {@code spring.flyway.user} 가
   * 없으면 Boot 는 Flyway 를 datasource 계정으로 돌리므로 소유자도 그 계정으로 대체한다(없으면 기동이 자리표시자 오류로 멈췄다).
   */
  @Test
  void 소유자_스키마는_Flyway_계정을_따른다() throws IOException {
    PropertySource<?> p = profile();

    assertThat(String.valueOf(p.getProperty("firehub.platform-db.tibero.owner-schema")))
        .isEqualTo("${spring.flyway.user:${spring.datasource.username}}");
  }

  @Test
  void Flyway_계정이_없으면_소유자_스키마는_datasource_계정이다() throws IOException {
    StandardEnvironment env = environment(Map.of("spring.datasource.username", "RT_USER"));

    assertThat(env.getProperty("firehub.platform-db.tibero.owner-schema")).isEqualTo("RT_USER");
    // 앱 풀 초기화 문장 안의 중첩 자리표시자도 같은 값으로 풀린다.
    assertThat(env.getProperty("spring.datasource.hikari.connection-init-sql"))
        .contains("CURRENT_SCHEMA=RT_USER'");
  }

  @Test
  void Flyway_계정이_있으면_소유자_스키마는_Flyway_계정이다() throws IOException {
    StandardEnvironment env =
        environment(
            Map.of("spring.datasource.username", "RT_USER", "spring.flyway.user", "OWNER_USER"));

    assertThat(env.getProperty("firehub.platform-db.tibero.owner-schema")).isEqualTo("OWNER_USER");
  }

  /** 프로필 파일 + 주어진 속성(우선)으로 만든 환경 — Boot 와 같은 방식으로 자리표시자를 푼다. */
  private static StandardEnvironment environment(Map<String, Object> props) throws IOException {
    StandardEnvironment env = new StandardEnvironment();
    // 실행 환경의 시스템 속성·환경변수(SPRING_FLYWAY_USER 등)가 끼어들지 않게 뺀다.
    env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    env.getPropertySources().addFirst(profile());
    env.getPropertySources().addFirst(new MapPropertySource("test", props));
    return env;
  }

  /** 플랫폼 DB 가 Tibero 면 PG 데이터 저장소 연결을 플랫폼 연결과 분리해야 한다(WD-494). 접속 정보는 환경변수 자리로만 두고 값은 박지 않는다. */
  @Test
  void PG_데이터_저장소를_분리_모드로_켜고_접속_정보는_환경변수로_받는다() throws IOException {
    PropertySource<?> p = profile();

    assertThat(String.valueOf(p.getProperty("firehub.data-store.postgres.separate")))
        .isEqualTo("true");
    for (String key :
        new String[] {"url", "password", "owner-url", "owner-username", "owner-password"}) {
      assertThat(String.valueOf(p.getProperty("firehub.data-store.postgres." + key)))
          .as("firehub.data-store.postgres.%s 는 환경변수 자리여야 한다", key)
          .startsWith("${");
    }
  }
}
