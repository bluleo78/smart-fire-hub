package com.smartfirehub.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Flyway migrate 실행에 커스텀 콜백(pipeline_executor 비밀번호 동기화)을 등록한다. */
@Configuration
public class FlywayCallbackConfig {

  @Bean
  public FlywayConfigurationCustomizer pipelineExecutorPasswordSyncCustomizer(
      @Value("${app.pipeline.datasource.password}") String pipelineExecutorPassword) {
    return configuration ->
        configuration.callbacks(new PipelineExecutorPasswordSyncCallback(pipelineExecutorPassword));
  }
}
