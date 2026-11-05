package com.smartfirehub.global.platformdb.mybatis;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.support.IntegrationTestBase;
import java.time.Instant;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.type.InstantTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/** MyBatis 배선(WD-487) — 플랫폼 SqlSessionFactory 가 PG 에 붙어 databaseId·매핑 설정을 갖는지 확인한다. */
class MyBatisPlatformConfigTest extends IntegrationTestBase {

  @Autowired
  @Qualifier("platformSqlSessionFactory")
  private SqlSessionFactory factory;

  /** PG 에 연결된 팩토리의 databaseId 는 postgresql 이다. */
  @Test
  void databaseId_isPostgresql() {
    assertThat(factory.getConfiguration().getDatabaseId()).isEqualTo("postgresql");
  }

  /** snake_case 컬럼을 camelCase 필드로 자동 매핑한다. */
  @Test
  void mapUnderscoreToCamelCase_isEnabled() {
    assertThat(factory.getConfiguration().isMapUnderscoreToCamelCase()).isTrue();
  }

  /** Tibero 전용 전역 설정(null 바인드 VARCHAR·UTC Instant 핸들러)이 PG 팩토리에는 붙지 않는다 — PG 는 MyBatis 기본값 그대로. */
  @Test
  void postgresFactory_keepsMyBatisDefaults() {
    var configuration = factory.getConfiguration();
    assertThat(configuration.getJdbcTypeForNull()).isEqualTo(JdbcType.OTHER);
    assertThat(configuration.getTypeHandlerRegistry().getTypeHandler(Instant.class))
        .isInstanceOf(InstantTypeHandler.class);
  }
}
