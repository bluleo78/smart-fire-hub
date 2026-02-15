package com.smartfirehub.dashboard.service;

import com.smartfirehub.dashboard.dto.DashboardStatsResponse;
import com.smartfirehub.dashboard.dto.RecentExecutionResponse;
import com.smartfirehub.dashboard.dto.RecentImportResponse;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

import static org.jooq.impl.DSL.*;

@Service
public class DashboardService {

    private final DSLContext dsl;

    // Table constants
    private static final Table<?> DATASET = table(name("dataset"));
    private static final Field<Long> D_ID = field(name("dataset", "id"), Long.class);
    private static final Field<String> D_NAME = field(name("dataset", "name"), String.class);
    private static final Field<String> D_DATASET_TYPE = field(name("dataset", "dataset_type"), String.class);

    private static final Table<?> PIPELINE = table(name("pipeline"));
    private static final Field<Long> P_ID = field(name("pipeline", "id"), Long.class);
    private static final Field<String> P_NAME = field(name("pipeline", "name"), String.class);
    private static final Field<Boolean> P_IS_ACTIVE = field(name("pipeline", "is_active"), Boolean.class);

    // audit_log constants
    private static final Table<?> AUDIT_LOG = table(name("audit_log"));
    private static final Field<Long> AL_ID = field(name("audit_log", "id"), Long.class);
    private static final Field<String> AL_ACTION_TYPE = field(name("audit_log", "action_type"), String.class);
    private static final Field<String> AL_RESOURCE = field(name("audit_log", "resource"), String.class);
    private static final Field<String> AL_RESOURCE_ID = field(name("audit_log", "resource_id"), String.class);
    private static final Field<String> AL_RESULT = field(name("audit_log", "result"), String.class);
    private static final Field<LocalDateTime> AL_ACTION_TIME = field(name("audit_log", "action_time"), LocalDateTime.class);

    private static final Table<?> PIPELINE_EXECUTION = table(name("pipeline_execution"));
    private static final Field<Long> PE_ID = field(name("pipeline_execution", "id"), Long.class);
    private static final Field<Long> PE_PIPELINE_ID = field(name("pipeline_execution", "pipeline_id"), Long.class);
    private static final Field<String> PE_STATUS = field(name("pipeline_execution", "status"), String.class);
    private static final Field<LocalDateTime> PE_CREATED_AT = field(name("pipeline_execution", "created_at"), LocalDateTime.class);

    public DashboardService(DSLContext dsl) {
        this.dsl = dsl;
    }

    public DashboardStatsResponse getStats() {
        // Count total datasets
        long totalDatasets = dsl.selectCount()
                .from(DATASET)
                .fetchOne(0, Long.class);

        // Count source datasets
        long sourceDatasets = dsl.selectCount()
                .from(DATASET)
                .where(D_DATASET_TYPE.eq("SOURCE"))
                .fetchOne(0, Long.class);

        // Count derived datasets
        long derivedDatasets = dsl.selectCount()
                .from(DATASET)
                .where(D_DATASET_TYPE.eq("DERIVED"))
                .fetchOne(0, Long.class);

        // Count total pipelines
        long totalPipelines = dsl.selectCount()
                .from(PIPELINE)
                .fetchOne(0, Long.class);

        // Count active pipelines
        long activePipelines = dsl.selectCount()
                .from(PIPELINE)
                .where(P_IS_ACTIVE.eq(true))
                .fetchOne(0, Long.class);

        // Get recent imports from audit_log (top 5)
        Field<String> metadataFileName = field("audit_log.metadata->>'fileName'", String.class);

        List<RecentImportResponse> recentImports = dsl.select(
                        AL_ID,
                        D_NAME,
                        metadataFileName,
                        AL_RESULT,
                        AL_ACTION_TIME
                )
                .from(AUDIT_LOG)
                .join(DATASET).on(AL_RESOURCE_ID.cast(Long.class).eq(D_ID))
                .where(AL_ACTION_TYPE.eq("IMPORT").and(AL_RESOURCE.eq("dataset")))
                .orderBy(AL_ACTION_TIME.desc())
                .limit(5)
                .fetch(r -> {
                    String status = switch (r.get(AL_RESULT)) {
                        case "SUCCESS" -> "COMPLETED";
                        case "FAILURE" -> "FAILED";
                        default -> r.get(AL_RESULT);
                    };
                    return new RecentImportResponse(
                            r.get(AL_ID),
                            r.get(D_NAME),
                            r.get(metadataFileName),
                            status,
                            r.get(AL_ACTION_TIME)
                    );
                });

        // Get recent executions (top 5)
        List<RecentExecutionResponse> recentExecutions = dsl.select(
                        PE_ID,
                        P_NAME,
                        PE_STATUS,
                        PE_CREATED_AT
                )
                .from(PIPELINE_EXECUTION)
                .join(PIPELINE).on(PE_PIPELINE_ID.eq(P_ID))
                .orderBy(PE_CREATED_AT.desc())
                .limit(5)
                .fetch(r -> new RecentExecutionResponse(
                        r.get(PE_ID),
                        r.get(P_NAME),
                        r.get(PE_STATUS),
                        r.get(PE_CREATED_AT)
                ));

        return new DashboardStatsResponse(
                totalDatasets,
                sourceDatasets,
                derivedDatasets,
                totalPipelines,
                activePipelines,
                recentImports,
                recentExecutions
        );
    }
}
