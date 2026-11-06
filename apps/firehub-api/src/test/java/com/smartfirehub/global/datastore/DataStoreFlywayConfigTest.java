package com.smartfirehub.global.datastore;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.support.DataStorePostgresTestContainer;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.PostgresTestContainer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.NestedTestConfiguration;
import org.springframework.test.context.NestedTestConfiguration.EnclosingConfiguration;

/**
 * 데이터 저장소 Flyway({@code dataStoreFlyway}) 배선 검증(WD-494 스펙 4절).
 *
 * <p>두 모드가 다른 스프링 컨텍스트라 중첩 클래스로 나눈다. 기본 모드(PG 설치본)에서는 빈이 없어야 하고 플랫폼 DB 에 이력 테이블을 하나 더 만들지 않아야 한다.
 * 분리 모드에서는 플랫폼 Flyway 뒤에 돌아 두 번째 컨테이너에 기준선(V1)을 남겨야 한다.
 */
class DataStoreFlywayConfigTest {

  /** 기본 모드 — 분리하지 않는 PG 설치본. */
  @Nested
  @NestedTestConfiguration(EnclosingConfiguration.OVERRIDE)
  class NotSeparate extends IntegrationTestBase {

    @Autowired private ApplicationContext context;

    @Test
    void datastoreFlywayAbsentWhenNotSeparate() throws SQLException {
      assertThat(context.containsBean(DataStoreFlywayConfig.BEAN_NAME)).isFalse();
      // 플랫폼 DB 에는 플랫폼 이력 테이블 하나뿐 — 데이터 저장소 기준선이 플랫폼 DB 에 돌지 않았다.
      try (Connection c =
              DriverManager.getConnection(
                  PostgresTestContainer.INSTANCE.getJdbcUrl(),
                  PostgresTestContainer.INSTANCE.getUsername(),
                  PostgresTestContainer.INSTANCE.getPassword());
          Statement st = c.createStatement();
          ResultSet rs =
              st.executeQuery(
                  "SELECT table_schema || '.' || table_name FROM information_schema.tables"
                      + " WHERE table_name LIKE '%schema_history%'")) {
        List<String> tables = new ArrayList<>();
        while (rs.next()) {
          tables.add(rs.getString(1));
        }
        assertThat(tables).containsExactly("public.flyway_schema_history");
      }
    }
  }

  /** 분리 모드 — 데이터 저장소가 두 번째 컨테이너. */
  @Nested
  @NestedTestConfiguration(EnclosingConfiguration.OVERRIDE)
  class Separate extends IntegrationTestBase {

    @DynamicPropertySource
    static void separateDataStoreProperties(DynamicPropertyRegistry registry) {
      DataStorePostgresTestContainer.registerSeparateDataStoreProperties(registry);
    }

    @Autowired private ConfigurableListableBeanFactory beanFactory;

    @Test
    void datastoreFlywayRunsAfterPlatform() throws SQLException {
      // 순서는 빈 의존으로 고정한다: 플랫폼 Flyway → 데이터 저장소 Flyway → 데이터 저장소 풀.
      assertThat(beanFactory.getBeanDefinition(DataStoreFlywayConfig.BEAN_NAME).getDependsOn())
          .contains("flywayInitializer");
      assertThat(beanFactory.getBeanDefinition("dataStoreDataSource").getDependsOn())
          .contains(DataStoreFlywayConfig.BEAN_NAME);

      try (Connection c = DataStorePostgresTestContainer.openSuperuserConnection();
          Statement st = c.createStatement();
          ResultSet rs =
              st.executeQuery(
                  "SELECT version, success FROM flyway_schema_history"
                      + " WHERE version IS NOT NULL ORDER BY installed_rank")) {
        assertThat(rs.next()).as("데이터 저장소 이력에 V1 이 있어야 한다").isTrue();
        assertThat(rs.getString("version")).isEqualTo("1");
        assertThat(rs.getBoolean("success")).isTrue();
      }
    }
  }
}
