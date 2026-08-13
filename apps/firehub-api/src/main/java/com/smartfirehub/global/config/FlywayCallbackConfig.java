package com.smartfirehub.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Flyway migrate 실행에 커스텀 콜백(pipeline_executor, app_tenant 비밀번호 동기화)을 등록한다.
 *
 * <p>{@code FlywayConfigurationCustomizer} 빈을 두 개 등록하면 안 된다 — Flyway 설정 커스터마이저는
 * 순서가 보장되지 않고, {@code configuration.callbacks(...)} 는 "추가"가 아니라 "교체"이므로
 * 나중에 실행되는 커스터마이저가 먼저 등록한 콜백을 통째로 지워버린다. 그래서 두 콜백을 반드시
 * 하나의 커스터마이저 안에서 같은 {@code callbacks(...)} 호출로 함께 등록한다.
 */
@Configuration
public class FlywayCallbackConfig {

  @Bean
  public FlywayConfigurationCustomizer passwordSyncCustomizer(
      @Value("${app.pipeline.datasource.password}") String pipelineExecutorPassword,
      @Value("${spring.datasource.password}") String appTenantPassword) {
    return configuration ->
        configuration.callbacks(
            new RolePasswordSyncCallback("pipeline_executor", pipelineExecutorPassword),
            new RolePasswordSyncCallback("app_tenant", appTenantPassword));
  }
}
