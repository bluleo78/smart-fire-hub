package com.smartfirehub.job.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.job.dto.AsyncJobStatusResponse;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.Table;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

@Repository
public class AsyncJobRepository {

    private static final Logger log = LoggerFactory.getLogger(AsyncJobRepository.class);

    private final DSLContext dsl;
    private final ObjectMapper objectMapper;

    private static final Table<?> ASYNC_JOB = table(name("async_job"));
    private static final Field<String>        AJ_ID            = field(name("async_job", "id"),            String.class);
    private static final Field<String>        AJ_JOB_TYPE      = field(name("async_job", "job_type"),      String.class);
    private static final Field<String>        AJ_RESOURCE      = field(name("async_job", "resource"),      String.class);
    private static final Field<String>        AJ_RESOURCE_ID   = field(name("async_job", "resource_id"),   String.class);
    private static final Field<Long>          AJ_USER_ID       = field(name("async_job", "user_id"),       Long.class);
    private static final Field<String>        AJ_STAGE         = field(name("async_job", "stage"),         String.class);
    private static final Field<Integer>       AJ_PROGRESS      = field(name("async_job", "progress"),      Integer.class);
    private static final Field<String>        AJ_MESSAGE       = field(name("async_job", "message"),       String.class);
    private static final Field<JSONB>         AJ_METADATA      = field(name("async_job", "metadata"),      JSONB.class);
    private static final Field<String>        AJ_ERROR_MESSAGE = field(name("async_job", "error_message"), String.class);
    private static final Field<LocalDateTime> AJ_CREATED_AT    = field(name("async_job", "created_at"),   LocalDateTime.class);
    private static final Field<LocalDateTime> AJ_UPDATED_AT    = field(name("async_job", "updated_at"),   LocalDateTime.class);

    public AsyncJobRepository(DSLContext dsl, ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.objectMapper = objectMapper;
    }

    private AsyncJobStatusResponse mapToResponse(Record r) {
        JSONB jsonb = r.get(AJ_METADATA);
        Map<String, Object> metadata = parseMetadata(jsonb);
        return new AsyncJobStatusResponse(
                r.get(AJ_ID),
                r.get(AJ_JOB_TYPE),
                r.get(AJ_STAGE),
                r.get(AJ_PROGRESS),
                r.get(AJ_MESSAGE),
                metadata,
                r.get(AJ_ERROR_MESSAGE),
                r.get(AJ_CREATED_AT),
                r.get(AJ_UPDATED_AT),
                r.get(AJ_USER_ID)
        );
    }

    private Map<String, Object> parseMetadata(JSONB jsonb) {
        if (jsonb == null || jsonb.data() == null) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(jsonb.data(), new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse async_job metadata JSON: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    private JSONB toJsonb(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return JSONB.valueOf("{}");
        }
        try {
            return JSONB.valueOf(objectMapper.writeValueAsString(metadata));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize async_job metadata: {}", e.getMessage());
            return JSONB.valueOf("{}");
        }
    }

    public void insert(String id, String jobType, String resource, String resourceId,
                       Long userId, Map<String, Object> metadata) {
        dsl.insertInto(ASYNC_JOB)
                .set(AJ_ID, id)
                .set(AJ_JOB_TYPE, jobType)
                .set(AJ_RESOURCE, resource)
                .set(AJ_RESOURCE_ID, resourceId)
                .set(AJ_USER_ID, userId)
                .set(AJ_STAGE, "PENDING")
                .set(AJ_PROGRESS, 0)
                .set(AJ_METADATA, toJsonb(metadata))
                .execute();
    }

    public Optional<AsyncJobStatusResponse> findById(String id) {
        return dsl.select(AJ_ID, AJ_JOB_TYPE, AJ_RESOURCE, AJ_RESOURCE_ID, AJ_USER_ID,
                        AJ_STAGE, AJ_PROGRESS, AJ_MESSAGE, AJ_METADATA,
                        AJ_ERROR_MESSAGE, AJ_CREATED_AT, AJ_UPDATED_AT)
                .from(ASYNC_JOB)
                .where(AJ_ID.eq(id))
                .fetchOptional(this::mapToResponse);
    }

    public void updateStageAndProgress(String id, String stage, int progress,
                                       String message, Map<String, Object> metadata) {
        dsl.update(ASYNC_JOB)
                .set(AJ_STAGE, stage)
                .set(AJ_PROGRESS, progress)
                .set(AJ_MESSAGE, message)
                .set(AJ_METADATA, toJsonb(metadata))
                .set(AJ_UPDATED_AT, LocalDateTime.now())
                .where(AJ_ID.eq(id))
                .execute();
    }

    public void updateStageAndError(String id, String stage, String errorMessage) {
        dsl.update(ASYNC_JOB)
                .set(AJ_STAGE, stage)
                .set(AJ_ERROR_MESSAGE, errorMessage)
                .set(AJ_UPDATED_AT, LocalDateTime.now())
                .where(AJ_ID.eq(id))
                .execute();
    }

    public List<AsyncJobStatusResponse> findActiveByResource(String jobType, String resource, String resourceId) {
        return dsl.select(AJ_ID, AJ_JOB_TYPE, AJ_RESOURCE, AJ_RESOURCE_ID, AJ_USER_ID,
                        AJ_STAGE, AJ_PROGRESS, AJ_MESSAGE, AJ_METADATA,
                        AJ_ERROR_MESSAGE, AJ_CREATED_AT, AJ_UPDATED_AT)
                .from(ASYNC_JOB)
                .where(AJ_JOB_TYPE.eq(jobType)
                        .and(AJ_RESOURCE.eq(resource))
                        .and(AJ_RESOURCE_ID.eq(resourceId))
                        .and(AJ_STAGE.notIn("COMPLETED", "FAILED")))
                .fetch(this::mapToResponse);
    }

    public List<AsyncJobStatusResponse> findStaleJobs(LocalDateTime before) {
        return dsl.select(AJ_ID, AJ_JOB_TYPE, AJ_RESOURCE, AJ_RESOURCE_ID, AJ_USER_ID,
                        AJ_STAGE, AJ_PROGRESS, AJ_MESSAGE, AJ_METADATA,
                        AJ_ERROR_MESSAGE, AJ_CREATED_AT, AJ_UPDATED_AT)
                .from(ASYNC_JOB)
                .where(AJ_UPDATED_AT.lt(before)
                        .and(AJ_STAGE.notIn("COMPLETED", "FAILED")))
                .fetch(this::mapToResponse);
    }

    public int deleteOlderThan(LocalDateTime before) {
        return dsl.deleteFrom(ASYNC_JOB)
                .where(AJ_CREATED_AT.lt(before)
                        .and(AJ_STAGE.in("COMPLETED", "FAILED")))
                .execute();
    }

    public Optional<Long> findOwnerById(String id) {
        return dsl.select(AJ_USER_ID)
                .from(ASYNC_JOB)
                .where(AJ_ID.eq(id))
                .fetchOptional(AJ_USER_ID);
    }
}
