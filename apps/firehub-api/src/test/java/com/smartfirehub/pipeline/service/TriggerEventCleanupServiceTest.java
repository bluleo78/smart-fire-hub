package com.smartfirehub.pipeline.service;

import com.smartfirehub.pipeline.dto.*;
import com.smartfirehub.pipeline.repository.TriggerEventRepository;
import com.smartfirehub.support.IntegrationTestBase;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static com.smartfirehub.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.*;

@Transactional
class TriggerEventCleanupServiceTest extends IntegrationTestBase {

    @Autowired
    private TriggerEventCleanupService cleanupService;

    @Autowired
    private TriggerEventRepository triggerEventRepository;

    @Autowired
    private TriggerService triggerService;

    @Autowired
    private PipelineService pipelineService;

    @Autowired
    private DSLContext dsl;

    private Long testUserId;
    private Long pipelineId;
    private Long triggerId;

    @BeforeEach
    void setUp() {
        testUserId = dsl.insertInto(USER)
                .set(USER.USERNAME, "cleanup_test_user")
                .set(USER.PASSWORD, "password")
                .set(USER.NAME, "Cleanup Test User")
                .set(USER.EMAIL, "cleanup_test@example.com")
                .returning(USER.ID)
                .fetchOne()
                .getId();

        PipelineDetailResponse pipeline = pipelineService.createPipeline(
                new CreatePipelineRequest("Cleanup Test Pipeline", "Description", List.of()), testUserId);
        pipelineId = pipeline.id();

        TriggerResponse trigger = triggerService.createTrigger(pipelineId, new CreateTriggerRequest(
                "Cleanup Test Trigger", TriggerType.API, null, Map.of()), testUserId);
        triggerId = trigger.id();
    }

    @Test
    void cleanupOldEvents_deletesEventsOlderThan90Days() {
        // Insert an old event (100 days ago) directly via SQL
        dsl.insertInto(table(name("trigger_event")))
                .set(field(name("trigger_event", "trigger_id"), Long.class), triggerId)
                .set(field(name("trigger_event", "pipeline_id"), Long.class), pipelineId)
                .set(field(name("trigger_event", "event_type"), String.class), "FIRED")
                .set(field(name("trigger_event", "created_at"), LocalDateTime.class), LocalDateTime.now().minusDays(100))
                .execute();

        // Insert a recent event (10 days ago)
        dsl.insertInto(table(name("trigger_event")))
                .set(field(name("trigger_event", "trigger_id"), Long.class), triggerId)
                .set(field(name("trigger_event", "pipeline_id"), Long.class), pipelineId)
                .set(field(name("trigger_event", "event_type"), String.class), "FIRED")
                .set(field(name("trigger_event", "created_at"), LocalDateTime.class), LocalDateTime.now().minusDays(10))
                .execute();

        // Verify both events exist
        List<TriggerEventResponse> before = triggerEventRepository.findByPipelineId(pipelineId, 100);
        assertThat(before).hasSize(2);

        // Run cleanup
        cleanupService.cleanupOldEvents();

        // Verify old event was deleted, recent event remains
        List<TriggerEventResponse> after = triggerEventRepository.findByPipelineId(pipelineId, 100);
        assertThat(after).hasSize(1);
    }

    @Test
    void cleanupOldEvents_withNoOldEvents_deletesNothing() {
        // Insert only a recent event
        triggerEventRepository.create(triggerId, pipelineId, null, "FIRED", Map.of("test", "recent"));

        List<TriggerEventResponse> before = triggerEventRepository.findByPipelineId(pipelineId, 100);
        assertThat(before).hasSize(1);

        // Run cleanup
        cleanupService.cleanupOldEvents();

        // Verify nothing was deleted
        List<TriggerEventResponse> after = triggerEventRepository.findByPipelineId(pipelineId, 100);
        assertThat(after).hasSize(1);
    }

    @Test
    void cleanupOldEvents_withNoEvents_completesWithoutError() {
        // No events exist — should not throw
        cleanupService.cleanupOldEvents();
    }
}
