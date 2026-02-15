package com.smartfirehub.pipeline.repository;

import com.smartfirehub.pipeline.dto.PipelineResponse;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.jooq.impl.DSL.*;

@Repository
public class PipelineRepository {

    private final DSLContext dsl;

    // Table constants
    private static final Table<?> PIPELINE = table(name("pipeline"));
    private static final Field<Long> P_ID = field(name("pipeline", "id"), Long.class);
    private static final Field<String> P_NAME = field(name("pipeline", "name"), String.class);
    private static final Field<String> P_DESCRIPTION = field(name("pipeline", "description"), String.class);
    private static final Field<Boolean> P_IS_ACTIVE = field(name("pipeline", "is_active"), Boolean.class);
    private static final Field<Long> P_CREATED_BY = field(name("pipeline", "created_by"), Long.class);
    private static final Field<LocalDateTime> P_CREATED_AT = field(name("pipeline", "created_at"), LocalDateTime.class);
    private static final Field<LocalDateTime> P_UPDATED_AT = field(name("pipeline", "updated_at"), LocalDateTime.class);

    private static final Table<?> USER_TABLE = table(name("user"));
    private static final Field<Long> U_ID = field(name("user", "id"), Long.class);
    private static final Field<String> U_NAME = field(name("user", "name"), String.class);

    private static final Table<?> PIPELINE_STEP = table(name("pipeline_step"));
    private static final Field<Long> PS_ID = field(name("pipeline_step", "id"), Long.class);
    private static final Field<Long> PS_PIPELINE_ID = field(name("pipeline_step", "pipeline_id"), Long.class);

    public PipelineRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public List<PipelineResponse> findAll(int page, int size) {
        Field<Integer> stepCountField = selectCount()
                .from(PIPELINE_STEP)
                .where(PS_PIPELINE_ID.eq(P_ID))
                .asField("step_count");

        return dsl.select(
                        P_ID,
                        P_NAME,
                        P_DESCRIPTION,
                        P_IS_ACTIVE,
                        P_CREATED_BY,
                        P_CREATED_AT,
                        stepCountField,
                        U_NAME
                )
                .from(PIPELINE)
                .leftJoin(USER_TABLE).on(P_CREATED_BY.eq(U_ID))
                .orderBy(P_ID.desc())
                .limit(size)
                .offset(page * size)
                .fetch(r -> new PipelineResponse(
                        r.get(P_ID),
                        r.get(P_NAME),
                        r.get(P_DESCRIPTION),
                        r.get(P_IS_ACTIVE),
                        r.get(U_NAME) != null ? r.get(U_NAME) : String.valueOf(r.get(P_CREATED_BY)),
                        r.get(stepCountField),
                        r.get(P_CREATED_AT)
                ));
    }

    public long count() {
        return dsl.selectCount()
                .from(PIPELINE)
                .fetchOne(0, Long.class);
    }

    public Optional<PipelineResponse> findById(Long id) {
        Field<Integer> stepCountField = selectCount()
                .from(PIPELINE_STEP)
                .where(PS_PIPELINE_ID.eq(P_ID))
                .asField("step_count");

        return dsl.select(
                        P_ID,
                        P_NAME,
                        P_DESCRIPTION,
                        P_IS_ACTIVE,
                        P_CREATED_BY,
                        P_CREATED_AT,
                        stepCountField,
                        U_NAME
                )
                .from(PIPELINE)
                .leftJoin(USER_TABLE).on(P_CREATED_BY.eq(U_ID))
                .where(P_ID.eq(id))
                .fetchOptional(r -> new PipelineResponse(
                        r.get(P_ID),
                        r.get(P_NAME),
                        r.get(P_DESCRIPTION),
                        r.get(P_IS_ACTIVE),
                        r.get(U_NAME) != null ? r.get(U_NAME) : String.valueOf(r.get(P_CREATED_BY)),
                        r.get(stepCountField),
                        r.get(P_CREATED_AT)
                ));
    }

    public PipelineResponse save(String name, String description, Long createdBy) {
        var record = dsl.insertInto(PIPELINE)
                .set(P_NAME, name)
                .set(P_DESCRIPTION, description)
                .set(P_IS_ACTIVE, true)
                .set(P_CREATED_BY, createdBy)
                .returning(P_ID, P_NAME, P_DESCRIPTION, P_IS_ACTIVE, P_CREATED_BY, P_CREATED_AT)
                .fetchOne();

        String createdByName = dsl.select(U_NAME)
                .from(USER_TABLE)
                .where(U_ID.eq(createdBy))
                .fetchOptional(r -> r.get(U_NAME))
                .orElse(String.valueOf(createdBy));

        return new PipelineResponse(
                record.get(P_ID),
                record.get(P_NAME),
                record.get(P_DESCRIPTION),
                record.get(P_IS_ACTIVE),
                createdByName,
                0,
                record.get(P_CREATED_AT)
        );
    }

    public void update(Long id, String name, String description, Boolean isActive) {
        var query = dsl.update(PIPELINE)
                .set(P_NAME, name)
                .set(P_DESCRIPTION, description)
                .set(P_UPDATED_AT, LocalDateTime.now());

        if (isActive != null) {
            query = query.set(P_IS_ACTIVE, isActive);
        }

        query.where(P_ID.eq(id)).execute();
    }

    public void deleteById(Long id) {
        dsl.deleteFrom(PIPELINE)
                .where(P_ID.eq(id))
                .execute();
    }

    public boolean existsByName(String name) {
        return dsl.fetchExists(
                dsl.selectOne().from(PIPELINE).where(P_NAME.eq(name))
        );
    }

    public Optional<String> findNameById(Long id) {
        return dsl.select(P_NAME)
                .from(PIPELINE)
                .where(P_ID.eq(id))
                .fetchOptional(r -> r.get(P_NAME));
    }

    public Optional<LocalDateTime> findUpdatedAtById(Long id) {
        return dsl.select(P_UPDATED_AT)
                .from(PIPELINE)
                .where(P_ID.eq(id))
                .fetchOptional(r -> r.get(P_UPDATED_AT));
    }
}
