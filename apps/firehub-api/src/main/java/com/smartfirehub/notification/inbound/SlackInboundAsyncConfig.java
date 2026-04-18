package com.smartfirehub.notification.inbound;

import java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Slack inbound 처리 전용 비동기 executor.
 *
 * <p>Slack Events API는 3초 내 200 ack 의무이므로 컨트롤러는 즉시 반환하고
 * 실제 AI 호출·DB 조회는 이 executor에서 비동기 처리한다.
 *
 * <p>사이징: core=3(동시 처리 3개), max=5(버스트), queue=20.
 * 큐 초과 시 CallerRunsPolicy — 트래픽 몰릴 때 컨트롤러 스레드가 동기 처리해 fail-fast 대신 스로틀.
 */
@Configuration
public class SlackInboundAsyncConfig {

    @Bean("slackInboundExecutor")
    public ThreadPoolTaskExecutor slackInboundExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(3);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("slack-inbound-");
        executor.setRejectedExecutionHandler(new CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
