package com.smartfirehub.global.platformdb.tibero.flyway;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.internal.database.base.BaseDatabaseType;
import org.junit.jupiter.api.Test;

/**
 * Tibero Flyway 플러그인의 등록·판별 규칙(WD-488). DB 없이 돈다 — 일반 PG 회귀에서도 항상 실행돼 플러그인이 PostgreSQL 연결을 가로채지 않음을
 * 지킨다.
 */
class TiberoFlywayDatabaseTypeTest {

  private final TiberoFlywayDatabaseType type = new TiberoFlywayDatabaseType();

  /** ServiceLoader(META-INF/services) 로 Flyway 플러그인 목록에 들어간다 — Spring Boot 자동 구성도 같은 경로로 읽는다. */
  @Test
  void isRegisteredAsFlywayPlugin() {
    FluentConfiguration cfg = Flyway.configure();
    assertThat(cfg.getPluginRegister().getPlugins(BaseDatabaseType.class))
        .anyMatch(p -> p instanceof TiberoFlywayDatabaseType);
  }

  /** Tibero URL·제품명만 맡고, PostgreSQL·Oracle 은 맡지 않는다. */
  @Test
  void handlesOnlyTibero() {
    assertThat(type.handlesJDBCUrl("jdbc:tibero:thin:@h:8629:tibero")).isTrue();
    assertThat(type.handlesJDBCUrl("jdbc:postgresql://h:5432/db")).isFalse();
    assertThat(type.handlesJDBCUrl("jdbc:oracle:thin:@h:1521:x")).isFalse();
    assertThat(type.handlesDatabaseProductNameAndVersion("Tibero", "7", null)).isTrue();
    assertThat(type.handlesDatabaseProductNameAndVersion("PostgreSQL", "16.4", null)).isFalse();
    assertThat(type.handlesDatabaseProductNameAndVersion("Oracle", "19", null)).isFalse();
    assertThat(type.getDriverClass("jdbc:tibero:thin:@h:8629:tibero", null))
        .isEqualTo("com.tmax.tibero.jdbc.TbDriver");
  }
}
