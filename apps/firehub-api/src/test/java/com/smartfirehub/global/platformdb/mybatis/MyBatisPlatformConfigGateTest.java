package com.smartfirehub.global.platformdb.mybatis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gateprobe.repository.mybatis.GateProbeRepository;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * MyBatis 배선 게이트(WD-487·WD-706) — 속성이 없거나 false 면 {@link MyBatisPlatformConfig} 가 통째로 빠지고, 켜지면
 * {@link PlatformMyBatisRepository} 구현이 플랫폼 DB 종류에 맞게 등록되는지 본다.
 *
 * <p>왜: 운영(PG) 컨텍스트에는 MyBatis 설정·매퍼·구현 빈이 하나도 생기면 안 된다. 켜졌을 때는 테스트 프로필({@code
 * mybatis.enabled=true})은 구현 빈을 이름으로만 꺼내 쓰게 주입 후보에서 빼고({@code autowireCandidate=false}),
 * Tibero({@code type=tibero})는 주입 후보로 둔다. 실 DB 없이 DatabaseMetaData 만 흉내 낸 DataSource 로 도는 가벼운 컨텍스트라
 * 등록기와 조건만 본다. 시험용 구현은 운영 스캔({@code com.smartfirehub})에 걸리지 않도록 {@code gateprobe} 패키지에 두고 스캔 기준
 * 패키지를 속성으로 지정한다.
 */
class MyBatisPlatformConfigGateTest {

  private static final String PROBE_BEAN = "gateProbeRepository";

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(MyBatisPlatformConfig.class)
          .withBean(DataSource.class, MyBatisPlatformConfigGateTest::postgresDataSource)
          .withPropertyValues(
              PlatformMyBatisRepositoryRegistrar.SCAN_PACKAGE_PROPERTY + "=gateprobe");

  /** 제품명만 PostgreSQL 로 답하는 DataSource — 팩토리가 databaseId 를 정하는 데만 쓰인다. */
  private static DataSource postgresDataSource() {
    try {
      DataSource ds = mock(DataSource.class);
      Connection conn = mock(Connection.class);
      DatabaseMetaData meta = mock(DatabaseMetaData.class);
      when(ds.getConnection()).thenReturn(conn);
      when(conn.getMetaData()).thenReturn(meta);
      when(meta.getDatabaseProductName()).thenReturn("PostgreSQL");
      return ds;
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  /** 속성이 없으면(운영 기본값) MyBatis 빈이 없다. */
  @Test
  void withoutProperty_noMyBatisBeans() {
    runner.run(
        ctx -> {
          assertThat(ctx).hasNotFailed();
          assertThat(ctx).doesNotHaveBean(MyBatisPlatformConfig.class);
          assertThat(ctx).doesNotHaveBean(SqlSessionFactory.class);
          assertThat(ctx).doesNotHaveBean(SqlSessionTemplate.class);
          assertThat(ctx).doesNotHaveBean("myBatisReviewItemRepository");
          assertThat(ctx).doesNotHaveBean(PROBE_BEAN);
        });
  }

  /** false 로 명시해도 꺼진다. */
  @Test
  void propertyFalse_noMyBatisBeans() {
    runner
        .withPropertyValues(MyBatisPlatformConfig.ENABLED_PROPERTY + "=false")
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              assertThat(ctx).doesNotHaveBean(SqlSessionFactory.class);
              assertThat(ctx).doesNotHaveBean(PROBE_BEAN);
            });
  }

  /** 플랫폼 DB 종류가 postgres 로 명시돼도 mybatis.enabled 가 없으면 꺼진다. */
  @Test
  void typePostgres_noMyBatisBeans() {
    runner
        .withPropertyValues("firehub.platform-db.type=postgres")
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              assertThat(ctx).doesNotHaveBean(MyBatisPlatformConfig.class);
              assertThat(ctx).doesNotHaveBean(PROBE_BEAN);
            });
  }

  /** 테스트 프로필(mybatis.enabled=true) — 구현 빈은 있되 주입 후보가 아니다(계약 테스트가 이름으로 꺼낸다). */
  @Test
  void mybatisEnabled_beanExistsButNotAutowireCandidate() {
    runner
        .withPropertyValues(MyBatisPlatformConfig.ENABLED_PROPERTY + "=true")
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              assertThat(ctx).hasSingleBean(MyBatisPlatformConfig.class);
              assertThat(ctx).hasBean(PROBE_BEAN);
              assertThat(ctx.getBean(PROBE_BEAN)).isInstanceOf(GateProbeRepository.class);
              assertThat(ctx.getBeanFactory().getBeanDefinition(PROBE_BEAN).isAutowireCandidate())
                  .isFalse();
            });
  }

  /** Tibero 설치본(type=tibero) — mybatis.enabled 없이도 켜지고 구현 빈이 주입 후보다. */
  @Test
  void typeTibero_beanIsAutowireCandidate() {
    runner
        .withPropertyValues("firehub.platform-db.type=tibero")
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              assertThat(ctx).hasSingleBean(MyBatisPlatformConfig.class);
              assertThat(ctx).hasBean(PROBE_BEAN);
              assertThat(ctx.getBeanFactory().getBeanDefinition(PROBE_BEAN).isAutowireCandidate())
                  .isTrue();
              // 주입 후보라 클래스 타입으로도 해소된다.
              assertThat(ctx).hasSingleBean(GateProbeRepository.class);
            });
  }
}
