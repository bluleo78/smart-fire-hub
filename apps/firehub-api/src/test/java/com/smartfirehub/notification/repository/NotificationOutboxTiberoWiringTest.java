package com.smartfirehub.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartfirehub.global.platformdb.PlatformDbErrors;
import com.smartfirehub.global.platformdb.PlatformTenantSession;
import com.smartfirehub.global.platformdb.mybatis.MyBatisPlatformConfig;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepositoryRegistrar;
import com.smartfirehub.global.platformdb.tibero.TiberoPlatformDbErrors;
import com.smartfirehub.notification.repository.mybatis.MyBatisNotificationOutboxRepository;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Tibero 설치본({@code firehub.platform-db.type=tibero})에서 알림 아웃박스 저장소 빈이 MyBatis 구현으로 뽑히는지
 * 본다(WD-709).
 *
 * <p>왜: 아웃박스는 {@code FOR UPDATE SKIP LOCKED} 를 쓰는 유일한 곳이다. MyBatis 구현(Tibero 는 행 수 제한 없이 SKIP
 * LOCKED + RowBounds)과 jOOQ 쪽 {@code @ConditionalOnPostgresPlatformDb} 는 이미 있지만, 둘이 함께 있을 때 Tibero
 * 컨텍스트가 실제로 MyBatis 쪽을 주입하는지는 따로 확인한 적이 없다 — ADR 0001 의 「운영 빈 전환은 WD-491」 을 닫는 근거 시험이다.
 *
 * <p>실 DB 없이 제품명만 "Tibero" 로 답하는 DataSource 로 도는 가벼운 컨텍스트다. jOOQ 구현은 운영처럼 컴포넌트 스캔으로 후보에 올려 조건이 걸러
 * 내는지 보고, MyBatis 구현은 등록기 스캔 범위를 알림 패키지로 좁혀 올린다(다른 도메인 구현의 의존 빈을 피한다). 해석기 구현이 받는 테넌트 세션·트랜잭션 매니저는
 * 목으로 채운다.
 */
class NotificationOutboxTiberoWiringTest {

  /** 운영과 같은 컴포넌트 스캔으로 jOOQ 구현을 후보에 올린다 — Tibero 에서는 조건이 빼야 한다. */
  @Configuration(proxyBeanMethods = false)
  @ComponentScan("com.smartfirehub.notification.repository.jooq")
  static class JooqNotificationRepositoriesScan {}

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(
              MyBatisPlatformConfig.class, JooqNotificationRepositoriesScan.class)
          .withBean(DataSource.class, NotificationOutboxTiberoWiringTest::tiberoDataSource)
          .withBean(PlatformDbErrors.class, TiberoPlatformDbErrors::new)
          .withBean(PlatformTenantSession.class, () -> mock(PlatformTenantSession.class))
          .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
          .withPropertyValues(
              "firehub.platform-db.type=tibero",
              PlatformMyBatisRepositoryRegistrar.SCAN_PACKAGE_PROPERTY
                  + "=com.smartfirehub.notification");

  /** 제품명만 Tibero 로 답하는 DataSource — 팩토리가 databaseId 를 정하는 데만 쓰인다. */
  private static DataSource tiberoDataSource() {
    try {
      DataSource ds = mock(DataSource.class);
      Connection conn = mock(Connection.class);
      DatabaseMetaData meta = mock(DatabaseMetaData.class);
      when(ds.getConnection()).thenReturn(conn);
      when(conn.getMetaData()).thenReturn(meta);
      when(meta.getDatabaseProductName()).thenReturn("Tibero");
      return ds;
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  @DisplayName(
      "Tibero 컨텍스트에서 NotificationOutboxRepository 빈은 MyBatisNotificationOutboxRepository 하나다")
  void tiberoContext_injectsMyBatisOutbox() {
    runner.run(
        ctx -> {
          assertThat(ctx).hasNotFailed();
          assertThat(ctx).hasSingleBean(NotificationOutboxRepository.class);
          Object bean = ctx.getBean(NotificationOutboxRepository.class);
          assertThat(AopUtils.getTargetClass(bean))
              .isEqualTo(MyBatisNotificationOutboxRepository.class);
          assertThat(ctx).doesNotHaveBean("jooqNotificationOutboxRepository");
        });
  }
}
