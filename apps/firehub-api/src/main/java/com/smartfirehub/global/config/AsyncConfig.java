package com.smartfirehub.global.config;

import com.smartfirehub.global.tenant.TenantContextTaskDecorator;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
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
 * <p><b>한정자 없는 {@code @Async}</b>(예: {@code TriggerEventService.onPipelineCompleted},
 * {@code NotificationService.onPipelineCompleted})는 이름이 {@code taskExecutor} 인 {@link Executor}
 * 빈을 찾는다. 그 이름의 빈이 없으면 Spring 은 데코레이터가 없는 {@code SimpleAsyncTaskExecutor} 로
 * 폴백해 스레드에 테넌트가 승계되지 않는다(P2-b: {@link #taskExecutor()} 로 그 간극을 메운다).
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

  /**
   * 한정자 없는 @Async 가 쓰는 기본 풀. 이 빈이 없으면 Spring 은 데코레이터가 없는
   * SimpleAsyncTaskExecutor 로 폴백하고, 그 스레드에는 테넌트가 승계되지 않아
   * RLS 하에서 조용히 무동작이 된다(TriggerEventService.onPipelineCompleted 등).
   */
  @Bean(name = "taskExecutor")
  public Executor taskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    // 짧은 이벤트 처리 전용이라 스레드는 적게 두고, 대신 큐를 넉넉히 잡는다.
    executor.setCorePoolSize(2);
    executor.setMaxPoolSize(4);
    executor.setQueueCapacity(500);
    executor.setThreadNamePrefix("async-");
    // 이 빈이 대체하는 SimpleAsyncTaskExecutor 는 큐가 무제한이었다. 유한 큐로 바꾸면서
    // 기본 AbortPolicy 를 그대로 두면, 파이프라인이 한꺼번에 완료될 때 체인 트리거와 알림이
    // TaskRejectedException 으로 유실된다. CallerRuns 로 호출자 스레드에서 처리해 유실을 막는다
    // (호출자는 이미 테넌트 컨텍스트를 갖고 있으므로 격리도 그대로 유지된다).
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    // setTaskDecorator 는 반드시 initialize() 앞이어야 한다 — 뒤에 두면 조용히 무효가 된다.
    executor.setTaskDecorator(new TenantContextTaskDecorator());
    executor.initialize();
    return executor;
  }
}
