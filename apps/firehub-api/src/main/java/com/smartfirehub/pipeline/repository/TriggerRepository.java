package com.smartfirehub.pipeline.repository;

import com.smartfirehub.pipeline.dto.CreateTriggerRequest;
import com.smartfirehub.pipeline.dto.TriggerResponse;
import com.smartfirehub.pipeline.dto.UpdateTriggerRequest;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.jooq.impl.DSL.*;

@Repository
public class TriggerRepository {

    private final DSLContext dsl;
    private final ObjectMapper objectMapper;

    private static final Table<?> PIPELINE_TRIGGER = table(name("pipeline_trigger"));
    private static final Field<Long> T_ID = field(name("pipeline_trigger", "id"), Long.class);
    private static final Field<Long> T_PIPELINE_ID = field(name("pipeline_trigger", "pipeline_id"), Long.class);
    private static final Field<String> T_TRIGGER_TYPE = field(name("pipeline_trigger", "trigger_type"), String.class);
    private static final Field<String> T_NAME = field(name("pipeline_trigger", "name"), String.class);
    private static final Field<String> T_DESCRIPTION = field(name("pipeline_trigger", "description"), String.class);
    private static final Field<Boolean> T_IS_ENABLED = field(name("pipeline_trigger", "is_enabled"), Boolean.class);
    private static final Field<JSONB> T_CONFIG = field(name("pipeline_trigger", "config"), JSONB.class);
    private static final Field<JSONB> T_TRIGGER_STATE = field(name("pipeline_trigger", "trigger_state"), JSONB.class);
    private static final Field<Long> T_CREATED_BY = field(name("pipeline_trigger", "created_by"), Long.class);
    private static final Field<Long> T_UPDATED_BY = field(name("pipeline_trigger", "updated_by"), Long.class);
    private static final Field<LocalDateTime> T_CREATED_AT = field(name("pipeline_trigger", "created_at"), LocalDateTime.class);
    private static final Field<LocalDateTime> T_UPDATED_AT = field(name("pipeline_trigger", "updated_at"), LocalDateTime.class);

    public TriggerRepository(DSLContext dsl, ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.objectMapper = objectMapper;
    }

    private Map<String, Object> parseJsonb(JSONB jsonb) {
        if (jsonb == null || jsonb.data() == null) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(jsonb.data(), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    private JSONB toJsonb(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return JSONB.valueOf("{}");
        }
        try {
            return JSONB.valueOf(objectMapper.writeValueAsString(map));
        } catch (Exception e) {
            return JSONB.valueOf("{}");
        }
    }

    private TriggerResponse mapToResponse(org.jooq.Record r) {
        return new TriggerResponse(
                r.get(T_ID),
                r.get(T_PIPELINE_ID),
                r.get(T_TRIGGER_TYPE),
                r.get(T_NAME),
                r.get(T_DESCRIPTION),
                r.get(T_IS_ENABLED),
                parseJsonb(r.get(T_CONFIG)),
                parseJsonb(r.get(T_TRIGGER_STATE)),
                r.get(T_CREATED_BY),
                r.get(T_CREATED_AT)
        );
    }

    public List<TriggerResponse> findByPipelineId(Long pipelineId) {
        return dsl.select(T_ID, T_PIPELINE_ID, T_TRIGGER_TYPE, T_NAME, T_DESCRIPTION, T_IS_ENABLED, T_CONFIG, T_TRIGGER_STATE, T_CREATED_BY, T_CREATED_AT)
                .from(PIPELINE_TRIGGER)
                .where(T_PIPELINE_ID.eq(pipelineId))
                .orderBy(T_ID.asc())
                .fetch(this::mapToResponse);
    }

    public Optional<TriggerResponse> findById(Long id) {
        return dsl.select(T_ID, T_PIPELINE_ID, T_TRIGGER_TYPE, T_NAME, T_DESCRIPTION, T_IS_ENABLED, T_CONFIG, T_TRIGGER_STATE, T_CREATED_BY, T_CREATED_AT)
                .from(PIPELINE_TRIGGER)
                .where(T_ID.eq(id))
                .fetchOptional(this::mapToResponse);
    }

    public List<TriggerResponse> findEnabledByType(String triggerType) {
        return dsl.select(T_ID, T_PIPELINE_ID, T_TRIGGER_TYPE, T_NAME, T_DESCRIPTION, T_IS_ENABLED, T_CONFIG, T_TRIGGER_STATE, T_CREATED_BY, T_CREATED_AT)
                .from(PIPELINE_TRIGGER)
                .where(T_TRIGGER_TYPE.eq(triggerType))
                .and(T_IS_ENABLED.eq(true))
                .fetch(this::mapToResponse);
    }

    public List<TriggerResponse> findEnabledChainTriggersByUpstreamId(Long upstreamPipelineId) {
        return dsl.select(T_ID, T_PIPELINE_ID, T_TRIGGER_TYPE, T_NAME, T_DESCRIPTION, T_IS_ENABLED, T_CONFIG, T_TRIGGER_STATE, T_CREATED_BY, T_CREATED_AT)
                .from(PIPELINE_TRIGGER)
                .where(T_TRIGGER_TYPE.eq("PIPELINE_CHAIN"))
                .and(T_IS_ENABLED.eq(true))
                .and(field("({0}->>'upstreamPipelineId')::bigint", Long.class, T_CONFIG).eq(upstreamPipelineId))
                .fetch(this::mapToResponse);
    }

    public Long create(Long pipelineId, CreateTriggerRequest req, Map<String, Object> config, Long userId) {
        return dsl.insertInto(PIPELINE_TRIGGER)
                .set(T_PIPELINE_ID, pipelineId)
                .set(T_TRIGGER_TYPE, req.triggerType().name())
                .set(T_NAME, req.name())
                .set(T_DESCRIPTION, req.description())
                .set(T_CONFIG, toJsonb(config))
                .set(T_CREATED_BY, userId)
                .returning(T_ID)
                .fetchOne(r -> r.get(T_ID));
    }

    public void update(Long id, UpdateTriggerRequest req, Long userId) {
        var query = dsl.update(PIPELINE_TRIGGER)
                .set(T_UPDATED_AT, LocalDateTime.now())
                .set(T_UPDATED_BY, userId);

        if (req.name() != null) {
            query = query.set(T_NAME, req.name());
        }
        if (req.description() != null) {
            query = query.set(T_DESCRIPTION, req.description());
        }
        if (req.isEnabled() != null) {
            query = query.set(T_IS_ENABLED, req.isEnabled());
        }
        if (req.config() != null) {
            query = query.set(T_CONFIG, toJsonb(req.config()));
        }

        query.where(T_ID.eq(id)).execute();
    }

    public void delete(Long id) {
        dsl.deleteFrom(PIPELINE_TRIGGER)
                .where(T_ID.eq(id))
                .execute();
    }

    public void updateEnabled(Long id, boolean enabled) {
        dsl.update(PIPELINE_TRIGGER)
                .set(T_IS_ENABLED, enabled)
                .set(T_UPDATED_AT, LocalDateTime.now())
                .where(T_ID.eq(id))
                .execute();
    }

    public void updateTriggerState(Long id, Map<String, Object> state) {
        dsl.update(PIPELINE_TRIGGER)
                .set(T_TRIGGER_STATE, toJsonb(state))
                .set(T_UPDATED_AT, LocalDateTime.now())
                .where(T_ID.eq(id))
                .execute();
    }

    public int disableByUpstreamPipelineId(Long upstreamPipelineId) {
        return dsl.update(PIPELINE_TRIGGER)
                .set(T_IS_ENABLED, false)
                .set(T_UPDATED_AT, LocalDateTime.now())
                .where(T_TRIGGER_TYPE.eq("PIPELINE_CHAIN"))
                .and(field("({0}->>'upstreamPipelineId')::bigint", Long.class, T_CONFIG).eq(upstreamPipelineId))
                .execute();
    }

    /**
     * Find API trigger by token hash.
     */
    public Optional<TriggerResponse> findByTokenHash(String tokenHash) {
        return dsl.select(T_ID, T_PIPELINE_ID, T_TRIGGER_TYPE, T_NAME, T_DESCRIPTION, T_IS_ENABLED, T_CONFIG, T_TRIGGER_STATE, T_CREATED_BY, T_CREATED_AT)
                .from(PIPELINE_TRIGGER)
                .where(T_TRIGGER_TYPE.eq("API"))
                .and(T_IS_ENABLED.eq(true))
                .and(field("{0}->>'tokenHash'", String.class, T_CONFIG).eq(tokenHash))
                .fetchOptional(this::mapToResponse);
    }

    /**
     * Find webhook trigger by webhookId.
     */
    public Optional<TriggerResponse> findByWebhookId(String webhookId) {
        return dsl.select(T_ID, T_PIPELINE_ID, T_TRIGGER_TYPE, T_NAME, T_DESCRIPTION, T_IS_ENABLED, T_CONFIG, T_TRIGGER_STATE, T_CREATED_BY, T_CREATED_AT)
                .from(PIPELINE_TRIGGER)
                .where(T_TRIGGER_TYPE.eq("WEBHOOK"))
                .and(field("{0}->>'webhookId'", String.class, T_CONFIG).eq(webhookId))
                .fetchOptional(this::mapToResponse);
    }

    /**
     * Check if there is a running execution for the given pipeline.
     */
    public boolean hasRunningExecution(Long pipelineId) {
        return dsl.fetchExists(
                dsl.selectOne()
                        .from(table(name("pipeline_execution")))
                        .where(field(name("pipeline_execution", "pipeline_id"), Long.class).eq(pipelineId))
                        .and(field(name("pipeline_execution", "status"), String.class).in("PENDING", "RUNNING"))
        );
    }

    /**
     * Check if pipeline is active.
     */
    public boolean isPipelineActive(Long pipelineId) {
        return dsl.select(field(name("pipeline", "is_active"), Boolean.class))
                .from(table(name("pipeline")))
                .where(field(name("pipeline", "id"), Long.class).eq(pipelineId))
                .fetchOptional(r -> r.get(field(name("pipeline", "is_active"), Boolean.class)))
                .orElse(false);
    }
}
