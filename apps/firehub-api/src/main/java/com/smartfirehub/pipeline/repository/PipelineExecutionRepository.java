package com.smartfirehub.pipeline.repository;

import com.smartfirehub.pipeline.dto.ExecutionDetailResponse;
import com.smartfirehub.pipeline.dto.PipelineExecutionResponse;
import com.smartfirehub.pipeline.dto.StepExecutionResponse;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.jooq.impl.DSL.*;

@Repository
public class PipelineExecutionRepository {

    private final DSLContext dsl;

    // Table constants
    private static final Table<?> PIPELINE_EXECUTION = table(name("pipeline_execution"));
    private static final Field<Long> PE_ID = field(name("pipeline_execution", "id"), Long.class);
    private static final Field<Long> PE_PIPELINE_ID = field(name("pipeline_execution", "pipeline_id"), Long.class);
    private static final Field<String> PE_STATUS = field(name("pipeline_execution", "status"), String.class);
    private static final Field<Long> PE_EXECUTED_BY = field(name("pipeline_execution", "executed_by"), Long.class);
    private static final Field<LocalDateTime> PE_STARTED_AT = field(name("pipeline_execution", "started_at"), LocalDateTime.class);
    private static final Field<LocalDateTime> PE_COMPLETED_AT = field(name("pipeline_execution", "completed_at"), LocalDateTime.class);
    private static final Field<LocalDateTime> PE_CREATED_AT = field(name("pipeline_execution", "created_at"), LocalDateTime.class);
    private static final Field<String> PE_TRIGGERED_BY = field(name("pipeline_execution", "triggered_by"), String.class);
    private static final Field<Long> PE_TRIGGER_ID = field(name("pipeline_execution", "trigger_id"), Long.class);

    private static final Table<?> PIPELINE_TRIGGER = table(name("pipeline_trigger"));
    private static final Field<Long> PT_ID = field(name("pipeline_trigger", "id"), Long.class);
    private static final Field<String> PT_NAME = field(name("pipeline_trigger", "name"), String.class);

    private static final Table<?> PIPELINE_STEP_EXECUTION = table(name("pipeline_step_execution"));
    private static final Field<Long> PSE_ID = field(name("pipeline_step_execution", "id"), Long.class);
    private static final Field<Long> PSE_EXECUTION_ID = field(name("pipeline_step_execution", "execution_id"), Long.class);
    private static final Field<Long> PSE_STEP_ID = field(name("pipeline_step_execution", "step_id"), Long.class);
    private static final Field<String> PSE_STATUS = field(name("pipeline_step_execution", "status"), String.class);
    private static final Field<Integer> PSE_OUTPUT_ROWS = field(name("pipeline_step_execution", "output_rows"), Integer.class);
    private static final Field<String> PSE_LOG = field(name("pipeline_step_execution", "log"), String.class);
    private static final Field<String> PSE_ERROR_MESSAGE = field(name("pipeline_step_execution", "error_message"), String.class);
    private static final Field<LocalDateTime> PSE_STARTED_AT = field(name("pipeline_step_execution", "started_at"), LocalDateTime.class);
    private static final Field<LocalDateTime> PSE_COMPLETED_AT = field(name("pipeline_step_execution", "completed_at"), LocalDateTime.class);

    private static final Table<?> USER_TABLE = table(name("user"));
    private static final Field<Long> U_ID = field(name("user", "id"), Long.class);
    private static final Field<String> U_NAME = field(name("user", "name"), String.class);

    private static final Table<?> PIPELINE = table(name("pipeline"));
    private static final Field<Long> P_ID = field(name("pipeline", "id"), Long.class);
    private static final Field<String> P_NAME = field(name("pipeline", "name"), String.class);

    private static final Table<?> PIPELINE_STEP = table(name("pipeline_step"));
    private static final Field<Long> PS_ID = field(name("pipeline_step", "id"), Long.class);
    private static final Field<String> PS_NAME = field(name("pipeline_step", "name"), String.class);

    public PipelineExecutionRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Long createExecution(Long pipelineId, Long executedBy) {
        return createExecution(pipelineId, executedBy, "MANUAL", null);
    }

    public Long createExecution(Long pipelineId, Long executedBy, String triggeredBy, Long triggerId) {
        var query = dsl.insertInto(PIPELINE_EXECUTION)
                .set(PE_PIPELINE_ID, pipelineId)
                .set(PE_STATUS, "PENDING")
                .set(PE_EXECUTED_BY, executedBy)
                .set(PE_TRIGGERED_BY, triggeredBy != null ? triggeredBy : "MANUAL");

        if (triggerId != null) {
            query = query.set(PE_TRIGGER_ID, triggerId);
        }

        return query.returning(PE_ID)
                .fetchOne(r -> r.get(PE_ID));
    }

    public void updateExecutionStatus(Long executionId, String status, LocalDateTime startedAt, LocalDateTime completedAt) {
        var query = dsl.update(PIPELINE_EXECUTION)
                .set(PE_STATUS, status);

        if (startedAt != null) {
            query = query.set(PE_STARTED_AT, startedAt);
        }

        if (completedAt != null) {
            query = query.set(PE_COMPLETED_AT, completedAt);
        }

        query.where(PE_ID.eq(executionId)).execute();
    }

    public Long createStepExecution(Long executionId, Long stepId) {
        return dsl.insertInto(PIPELINE_STEP_EXECUTION)
                .set(PSE_EXECUTION_ID, executionId)
                .set(PSE_STEP_ID, stepId)
                .set(PSE_STATUS, "PENDING")
                .returning(PSE_ID)
                .fetchOne(r -> r.get(PSE_ID));
    }

    public void updateStepExecution(Long stepExecId, String status, Integer outputRows, String log, String errorMessage, LocalDateTime startedAt, LocalDateTime completedAt) {
        var query = dsl.update(PIPELINE_STEP_EXECUTION)
                .set(PSE_STATUS, status);

        if (outputRows != null) {
            query = query.set(PSE_OUTPUT_ROWS, outputRows);
        }

        if (log != null) {
            query = query.set(PSE_LOG, log);
        }

        if (errorMessage != null) {
            query = query.set(PSE_ERROR_MESSAGE, errorMessage);
        }

        if (startedAt != null) {
            query = query.set(PSE_STARTED_AT, startedAt);
        }

        if (completedAt != null) {
            query = query.set(PSE_COMPLETED_AT, completedAt);
        }

        query.where(PSE_ID.eq(stepExecId)).execute();
    }

    public List<PipelineExecutionResponse> findExecutionsByPipelineId(Long pipelineId) {
        return dsl.select(
                        PE_ID,
                        PE_PIPELINE_ID,
                        PE_STATUS,
                        PE_EXECUTED_BY,
                        PE_STARTED_AT,
                        PE_COMPLETED_AT,
                        PE_CREATED_AT,
                        U_NAME,
                        PE_TRIGGERED_BY,
                        PT_NAME
                )
                .from(PIPELINE_EXECUTION)
                .leftJoin(USER_TABLE).on(PE_EXECUTED_BY.eq(U_ID))
                .leftJoin(PIPELINE_TRIGGER).on(PE_TRIGGER_ID.eq(PT_ID))
                .where(PE_PIPELINE_ID.eq(pipelineId))
                .orderBy(PE_ID.desc())
                .fetch(r -> new PipelineExecutionResponse(
                        r.get(PE_ID),
                        r.get(PE_PIPELINE_ID),
                        r.get(PE_STATUS),
                        r.get(U_NAME) != null ? r.get(U_NAME) : String.valueOf(r.get(PE_EXECUTED_BY)),
                        r.get(PE_STARTED_AT),
                        r.get(PE_COMPLETED_AT),
                        r.get(PE_CREATED_AT),
                        r.get(PE_TRIGGERED_BY),
                        r.get(PT_NAME)
                ));
    }

    public Optional<ExecutionDetailResponse> findExecutionById(Long executionId) {
        // Get execution info
        var executionOpt = dsl.select(
                        PE_ID,
                        PE_PIPELINE_ID,
                        PE_STATUS,
                        PE_EXECUTED_BY,
                        PE_STARTED_AT,
                        PE_COMPLETED_AT,
                        PE_CREATED_AT,
                        P_NAME,
                        U_NAME
                )
                .from(PIPELINE_EXECUTION)
                .join(PIPELINE).on(PE_PIPELINE_ID.eq(P_ID))
                .leftJoin(USER_TABLE).on(PE_EXECUTED_BY.eq(U_ID))
                .where(PE_ID.eq(executionId))
                .fetchOptional();

        if (executionOpt.isEmpty()) {
            return Optional.empty();
        }

        var exec = executionOpt.get();

        // Get step executions
        List<StepExecutionResponse> stepExecutions = findStepExecutionsByExecutionId(executionId);

        return Optional.of(new ExecutionDetailResponse(
                exec.get(PE_ID),
                exec.get(PE_PIPELINE_ID),
                exec.get(P_NAME),
                exec.get(PE_STATUS),
                exec.get(U_NAME) != null ? exec.get(U_NAME) : String.valueOf(exec.get(PE_EXECUTED_BY)),
                stepExecutions,
                exec.get(PE_STARTED_AT),
                exec.get(PE_COMPLETED_AT),
                exec.get(PE_CREATED_AT)
        ));
    }

    public List<StepExecutionResponse> findStepExecutionsByExecutionId(Long executionId) {
        return dsl.select(
                        PSE_ID,
                        PSE_STEP_ID,
                        PS_NAME,
                        PSE_STATUS,
                        PSE_OUTPUT_ROWS,
                        PSE_LOG,
                        PSE_ERROR_MESSAGE,
                        PSE_STARTED_AT,
                        PSE_COMPLETED_AT
                )
                .from(PIPELINE_STEP_EXECUTION)
                .join(PIPELINE_STEP).on(PSE_STEP_ID.eq(PS_ID))
                .where(PSE_EXECUTION_ID.eq(executionId))
                .orderBy(PSE_ID.asc())
                .fetch(r -> new StepExecutionResponse(
                        r.get(PSE_ID),
                        r.get(PSE_STEP_ID),
                        r.get(PS_NAME),
                        r.get(PSE_STATUS),
                        r.get(PSE_OUTPUT_ROWS),
                        r.get(PSE_LOG),
                        r.get(PSE_ERROR_MESSAGE),
                        r.get(PSE_STARTED_AT),
                        r.get(PSE_COMPLETED_AT)
                ));
    }
}
