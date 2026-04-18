package com.smartfirehub.notification.inbound;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * SlackInboundAsyncConfig 통합 테스트.
 *
 * <p>Spring 컨텍스트 로드 후 slackInboundExecutor bean이 정상 등록되고
 * 설정값(core/max/queue)이 플랜 스펙과 일치하는지 확인한다.
 */
class SlackInboundAsyncConfigTest extends IntegrationTestBase {

    @Autowired
    @Qualifier("slackInboundExecutor")
    private ThreadPoolTaskExecutor executor;

    @Test
    void slackInboundExecutor_hasExpectedSizing() {
        // core=3, max=5, queue=20 — SlackInboundAsyncConfig 스펙 확인
        assertThat(executor.getCorePoolSize()).isEqualTo(3);
        assertThat(executor.getMaxPoolSize()).isEqualTo(5);
        assertThat(executor.getQueueCapacity()).isEqualTo(20);
        assertThat(executor.getThreadNamePrefix()).isEqualTo("slack-inbound-");
    }
}
