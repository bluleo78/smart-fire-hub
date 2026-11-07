package com.smartfirehub.global.jobrunr;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * JobRunr 저장소 전용 DataSource(WD-555).
 *
 * <p>업무용 {@code @Primary} DataSource 와 접속 정보(url·계정·드라이버)와 Hikari 공통 설정(세션 초기화 SQL·검증 쿼리 등, Tibero
 * 포함)은 그대로 따르되 <b>풀은 별개</b>로 만든다. 풀 크기·타임아웃만 {@link JobRunrDataSourceProperties} 로 덮어쓴다. 이 빈은
 * {@code @Primary} 가 아니므로 업무 코드의 DataSource 주입에는 끼어들지 않고, {@code
 * org.jobrunr.database.datasource=jobRunrDataSource} 설정으로 JobRunr 저장소(Postgres 자동 설정·Tibero 수동 설정
 * 모두 이 키를 읽는다)에만 연결된다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JobRunrDataSourceProperties.class)
public class JobRunrDataSourceConfig {

  /** JobRunr 전용 풀 빈 이름 — {@code org.jobrunr.database.datasource} 에 같은 이름을 적는다. */
  public static final String BEAN_NAME = "jobRunrDataSource";

  @Bean(name = BEAN_NAME, destroyMethod = "close")
  public HikariDataSource jobRunrDataSource(
      DataSourceProperties properties, Environment environment, JobRunrDataSourceProperties pool) {
    HikariDataSource ds =
        properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    // 업무 풀과 같은 Hikari 공통 설정(connection-init-sql 등)을 먼저 깔고, 아래에서 전용 값으로 덮어쓴다.
    Binder.get(environment).bind("spring.datasource.hikari", Bindable.ofInstance(ds));
    ds.setPoolName("jobrunr");
    ds.setMaximumPoolSize(pool.maxPoolSize());
    ds.setMinimumIdle(Math.min(pool.minimumIdle(), pool.maxPoolSize()));
    ds.setConnectionTimeout(pool.connectionTimeoutMs());
    return ds;
  }
}
