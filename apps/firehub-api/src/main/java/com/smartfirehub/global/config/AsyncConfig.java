package com.smartfirehub.global.config;

import com.smartfirehub.global.tenant.TenantContextTaskDecorator;
import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 비동기 실행자 정의.
 *
 * <p>여기 정의한 세 풀에 {@link TenantContextTaskDecorator} 를 붙인다 — 붙이지 않으면 그 풀에서 도는
 * 작업이 테넌트 없이 실행돼 RLS 가 전부 차단하고 예외 없이 0행이 된다. 데코레이터는 반드시
 * {@code initialize()} <b>앞</b>에 설정해야 한다(뒤에 두면 이미 만들어진 풀에 반영되지 않는다).
 *
 * <p><b>한정자 없는 {@code @Async} 는 이 세 풀 중 어느 것도 쓰지 않는다.</b> 이 클래스는
 * {@code AsyncConfigurer} 를 구현하지 않고, 세 빈 모두 이름이 {@code taskExecutor} 가 아니며 타입이
 * {@code Executor} 라 부트의 기본 실행자 자동설정도 물러난다. 결과적으로 한정자 없는
 * {@code @Async} 는 데코레이터가 없는 {@code SimpleAsyncTaskExecutor} 로 떨어진다.
 * 현재 해당하는 곳은 {@code TriggerEventService.onPipelineCompleted}(pipeline_trigger)와
 * {@code NotificationService}(notification_outbox) 두 곳이며, 두 테이블 모두 P2-a 에서 RLS 를 걸지
 * 않으므로 지금은 무해하다. <b>그 테이블에 RLS 를 걸 때(P2-b) 한정자를 붙이거나 데코레이터가 달린
 * {@code taskExecutor} 빈을 등록해야 한다</b> — 안 하면 조용히 무동작이 된다.
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

  @Bean(name = "pipelineExecutor")
  public Executor pipelineExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(5);
    executor.setMaxPoolSize(10);
    executor.setQueueCapacity(25);
    executor.setThreadNamePrefix("pipeline-exec-");
    executor.setTaskDecorator(new TenantContextTaskDecorator());
    executor.initialize();
    return executor;
  }

  @Bean(name = "exportExecutor")
  public Executor exportExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(3);
    executor.setMaxPoolSize(5);
    executor.setQueueCapacity(10);
    executor.setThreadNamePrefix("data-export-");
    executor.setTaskDecorator(new TenantContextTaskDecorator());
    executor.initialize();
    return executor;
  }

  /** 데이터셋 재인덱싱(임베딩 생성) 전용 풀 — 메인 요청 스레드와 격리해 쓰기 경로를 막지 않는다. */
  @Bean(name = "indexExecutor")
  public Executor indexExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(2);
    executor.setMaxPoolSize(4);
    executor.setQueueCapacity(500);
    executor.setThreadNamePrefix("dataset-index-");
    executor.setTaskDecorator(new TenantContextTaskDecorator());
    executor.initialize();
    return executor;
  }
}
