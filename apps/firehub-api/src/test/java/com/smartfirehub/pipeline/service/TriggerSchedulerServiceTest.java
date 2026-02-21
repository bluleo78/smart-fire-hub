package com.smartfirehub.pipeline.service;

import com.smartfirehub.pipeline.dto.*;
import com.smartfirehub.support.IntegrationTestBase;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static com.smartfirehub.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class TriggerSchedulerServiceTest extends IntegrationTestBase {

    @Autowired
    private TriggerService triggerService;

    @Autowired
    private TriggerSchedulerService schedulerService;

    @Autowired
    private PipelineService pipelineService;

    @Autowired
    private DSLContext dsl;

    private Long testUserId;
    private Long pipelineId;

    @BeforeEach
    void setUp() {
        testUserId = dsl.insertInto(USER)
                .set(USER.USERNAME, "scheduler_test_user")
                .set(USER.PASSWORD, "password")
                .set(USER.NAME, "Scheduler Test User")
                .set(USER.EMAIL, "scheduler_test@example.com")
                .returning(USER.ID)
                .fetchOne()
                .getId();

        PipelineDetailResponse pipeline = pipelineService.createPipeline(
                new CreatePipelineRequest("Scheduler Test Pipeline", "Description", List.of()), testUserId);
        pipelineId = pipeline.id();
    }

    @Test
    void registerSchedule_validCron_succeeds() {
        Map<String, Object> config = Map.of(
                "cron", "0 0 * * *",
                "timezone", "Asia/Seoul",
                "concurrencyPolicy", "SKIP"
        );

        // Should not throw
        schedulerService.registerSchedule(999L, config);

        // Cleanup
        schedulerService.unregisterSchedule(999L);
    }

    @Test
    void unregisterSchedule_cancelsExistingTask() {
        Map<String, Object> config = Map.of(
                "cron", "0 0 * * *",
                "timezone", "Asia/Seoul"
        );

        schedulerService.registerSchedule(998L, config);
        // Should not throw
        schedulerService.unregisterSchedule(998L);
        // Second unregister should also not throw
        schedulerService.unregisterSchedule(998L);
    }

    @Test
    void createScheduleTrigger_withSkipPolicy_setsConfigCorrectly() {
        CreateTriggerRequest request = new CreateTriggerRequest(
                "SKIP Policy Test",
                TriggerType.SCHEDULE,
                "Test SKIP concurrency",
                Map.of("cron", "0 9 * * *", "concurrencyPolicy", "SKIP")
        );

        TriggerResponse response = triggerService.createTrigger(pipelineId, request, testUserId);

        assertThat(response.config().get("concurrencyPolicy")).isEqualTo("SKIP");
        assertThat(response.config().get("timezone")).isEqualTo("Asia/Seoul"); // default
    }

    @Test
    void createScheduleTrigger_withAllowPolicy_setsConfigCorrectly() {
        CreateTriggerRequest request = new CreateTriggerRequest(
                "ALLOW Policy Test",
                TriggerType.SCHEDULE,
                "Test ALLOW concurrency",
                Map.of("cron", "0 9 * * *", "concurrencyPolicy", "ALLOW")
        );

        TriggerResponse response = triggerService.createTrigger(pipelineId, request, testUserId);

        assertThat(response.config().get("concurrencyPolicy")).isEqualTo("ALLOW");
    }
}
