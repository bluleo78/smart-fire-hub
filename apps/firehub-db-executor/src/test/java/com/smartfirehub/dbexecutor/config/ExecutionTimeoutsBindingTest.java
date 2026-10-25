package com.smartfirehub.dbexecutor.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 선행 문장 시간 제한 바인딩 테스트(WD-236). 운영자가 api 와 같은 환경변수({@code
 * DB_EXECUTOR_PRE_STATEMENT_TIMEOUT_SECONDS})로 두 쪽을 함께 맞출 수 있어야, api 가 먼저 포기한 뒤 실행기가 커밋하는 어긋남이 생기지
 * 않는다.
 */
class ExecutionTimeoutsBindingTest {

  /** 실제 application.yml 을 읽는 컨텍스트 러너. */
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withInitializer(new ConfigDataApplicationContextInitializer())
          .withUserConfiguration(Config.class);

  @EnableConfigurationProperties(ExecutionTimeouts.class)
  static class Config {}

  @Test
  void preStatementTimeout_defaultsTo1800() {
    runner.run(
        ctx ->
            assertThat(ctx.getBean(ExecutionTimeouts.class).sql().preStatementTimeoutSeconds())
                .isEqualTo(1800));
  }

  @Test
  void preStatementTimeout_followsSharedEnvName() {
    runner
        .withPropertyValues("DB_EXECUTOR_PRE_STATEMENT_TIMEOUT_SECONDS=600")
        .run(
            ctx ->
                assertThat(ctx.getBean(ExecutionTimeouts.class).sql().preStatementTimeoutSeconds())
                    .isEqualTo(600));
  }
}
