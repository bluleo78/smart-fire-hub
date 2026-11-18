package com.smartfirehub.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.external.ExternalDbConnector;
import com.smartfirehub.dataset.storage.external.ExternalDbTarget;
import com.smartfirehub.dataset.storage.external.ExternalStorePoolFactory;
import com.smartfirehub.global.datastore.SeparateDataStoreDbConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.SQLExceptionOverride.Override;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.sql.Statement;
import java.util.List;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * HikariCP 6 에서 빠진 「문장 시간 초과 커넥션 퇴출」을 되살린 판정({@link HikariTimeoutEvictionOverride})과, 그 판정이 api 가
 * 만드는 모든 Hikari 풀에 걸리는지 확인한다(WD-791).
 */
class HikariTimeoutEvictionOverrideTest {

  private final HikariTimeoutEvictionOverride override = new HikariTimeoutEvictionOverride();

  // ── 판정 규칙 ─────────────────────────────────────────────────────────────

  @Test
  void 문장_시간_초과는_반드시_퇴출한다() {
    assertThat(override.adjudicate(new SQLTimeoutException("취소", "HY008", -12040)))
        .isEqualTo(Override.MUST_EVICT);
  }

  @Test
  void 그_밖의_예외는_Hikari_기본_판정에_맡긴다() {
    // PG 문장 취소(57014)는 일반 SQLException 이라 5.x 때처럼 퇴출하지 않는다.
    assertThat(override.adjudicate(new SQLException("취소", "57014")))
        .isEqualTo(Override.CONTINUE_EVICT);
    assertThat(override.adjudicate(new SQLTransientConnectionException("대기", "08001")))
        .isEqualTo(Override.CONTINUE_EVICT);
    assertThat(override.adjudicate(new SQLException("문법", "42601")))
        .isEqualTo(Override.CONTINUE_EVICT);
  }

  // ── 실제 HikariCP 풀에서의 동작 ──────────────────────────────────────────────

  /** 문장 실행이 늘 SQLTimeoutException 으로 끝나는 가짜 DataSource 위에 1개짜리 Hikari 풀을 만든다. */
  private static HikariDataSource poolThrowingTimeout(boolean withOverride) throws SQLException {
    Connection physical = mock(Connection.class);
    Statement statement = mock(Statement.class);
    when(physical.isValid(anyInt())).thenReturn(true);
    when(physical.getAutoCommit()).thenReturn(true);
    when(physical.createStatement()).thenReturn(statement);
    when(statement.execute(anyString())).thenThrow(new SQLTimeoutException("취소", "HY008", -12040));
    DataSource target = mock(DataSource.class);
    when(target.getConnection()).thenReturn(physical);

    HikariDataSource ds = new HikariDataSource();
    ds.setDataSource(target);
    ds.setMaximumPoolSize(1);
    ds.setPoolName("timeout-eviction-test");
    if (withOverride) {
      HikariTimeoutEvictionOverride.applyTo(ds);
    }
    return ds;
  }

  @Test
  void 판정을_건_풀은_시간_초과를_받은_커넥션을_닫는다() throws SQLException {
    try (HikariDataSource ds = poolThrowingTimeout(true);
        Connection conn = ds.getConnection()) {
      assertThatThrownBy(() -> conn.createStatement().execute("SELECT 1"))
          .isInstanceOf(SQLTimeoutException.class);
      // 퇴출된 프록시는 닫힌 커넥션으로 바뀐다 — HikariCP 5.1.0 의 기본 동작과 같다.
      assertThat(conn.isClosed()).isTrue();
    }
  }

  @Test
  void 판정이_없으면_HikariCP_6_은_시간_초과_커넥션을_풀에_남긴다() throws SQLException {
    // 판정이 왜 필요한지 보여 주는 대조군 — 6.x 기본 규칙은 SQLTimeoutException 으로 퇴출하지 않는다.
    try (HikariDataSource ds = poolThrowingTimeout(false);
        Connection conn = ds.getConnection()) {
      assertThatThrownBy(() -> conn.createStatement().execute("SELECT 1"))
          .isInstanceOf(SQLTimeoutException.class);
      assertThat(conn.isClosed()).isFalse();
    }
  }

  // ── 풀마다 판정이 걸렸는지 ─────────────────────────────────────────────────────

  @Test
  void application_yml_의_spring_datasource_hikari_바인딩에_판정이_실린다() {
    // 플랫폼 기본 풀(PipelineSandboxDataSourceConfig)·JobRunr 풀이 이 키로 바인딩된다.
    new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .run(
            ctx -> {
              try (HikariDataSource ds = new HikariDataSource()) {
                Binder.get(ctx.getEnvironment())
                    .bind("spring.datasource.hikari", Bindable.ofInstance(ds));
                assertThat(ds.getExceptionOverrideClassName())
                    .isEqualTo(HikariTimeoutEvictionOverride.class.getName());
                assertThat(ds.getExceptionOverride())
                    .isInstanceOf(HikariTimeoutEvictionOverride.class);
              }
            });
  }

  @Test
  void 외부_저장소_소유_풀에_판정이_걸린다() {
    ExternalStorePoolFactory factory =
        new ExternalStorePoolFactory(mock(ExternalDbConnector.class));
    ExternalDbTarget target =
        new ExternalDbTarget(DataStoreBackend.TIBERO, "localhost", 8629, "tibero");
    try (HikariDataSource ds = (HikariDataSource) factory.create(7L, target, "GENIA_T7", "x")) {
      assertThat(ds.getExceptionOverride()).isInstanceOf(HikariTimeoutEvictionOverride.class);
    }
  }

  @Test
  void 스키마_소유자_풀에_판정이_걸린다() {
    try (HikariDataSource ds =
        (HikariDataSource)
            new SchemaOwnerDataSourceConfig()
                .schemaOwnerDataSource("jdbc:postgresql://127.0.0.1:1/none", "u", "p")) {
      assertThat(ds.getExceptionOverride()).isInstanceOf(HikariTimeoutEvictionOverride.class);
    }
  }

  @Test
  void 분리_데이터_저장소_풀에_판정이_걸린다() {
    try (HikariDataSource ds =
        (HikariDataSource)
            new SeparateDataStoreDbConfig()
                .dataStoreDataSource(
                    "jdbc:postgresql://127.0.0.1:1/none", "p", 2, 1_000L, 0, 600_000L)) {
      assertThat(ds.getExceptionOverride()).isInstanceOf(HikariTimeoutEvictionOverride.class);
    }
  }

  /**
   * 코드로 Hikari 풀을 만드는 모든 소스 파일이 판정을 거는지 소스로 확인한다 — 위에서 직접 만들어 보기 어려운 풀(테넌트 파이프라인 풀, 증분 단계 잠금 풀)과
   * 앞으로 새로 생길 풀까지 덮는다.
   */
  @Test
  void 코드로_Hikari_풀을_만드는_파일은_모두_판정을_건다() throws IOException {
    Path main = Path.of("src/main/java");
    List<Path> builders;
    try (Stream<Path> files = Files.walk(main)) {
      builders =
          files
              .filter(p -> p.toString().endsWith(".java"))
              .filter(p -> containsAny(p, "new HikariDataSource(", "new HikariConfig("))
              .toList();
    }
    assertThat(builders).isNotEmpty();
    assertThat(builders)
        .allSatisfy(
            p ->
                assertThat(Files.readString(p))
                    .as("%s 는 HikariTimeoutEvictionOverride.applyTo 를 불러야 한다", p)
                    .contains("HikariTimeoutEvictionOverride.applyTo("));
  }

  private static boolean containsAny(Path p, String... needles) {
    try {
      String src = Files.readString(p);
      return Stream.of(needles).anyMatch(src::contains);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
