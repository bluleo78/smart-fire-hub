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

    private static final Table<?> DATA_IMPORT = table(name("data_import"));
    private static final Field<Long> DI_ID = field(name("data_import", "id"), Long.class);
    private static final Field<Long> DI_DATASET_ID = field(name("data_import", "dataset_id"), Long.class);
    private static final Field<String> DI_FILE_NAME = field(name("data_import", "file_name"), String.class);
    private static final Field<String> DI_STATUS = field(name("data_import", "status"), String.class);
    private static final Field<LocalDateTime> DI_CREATED_AT = field(name("data_import", "created_at"), LocalDateTime.class);

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

        // Get recent imports (top 5)
        List<RecentImportResponse> recentImports = dsl.select(
                        DI_ID,
                        D_NAME,
                        DI_FILE_NAME,
                        DI_STATUS,
                        DI_CREATED_AT
                )
                .from(DATA_IMPORT)
                .join(DATASET).on(DI_DATASET_ID.eq(D_ID))
                .orderBy(DI_CREATED_AT.desc())
                .limit(5)
                .fetch(r -> new RecentImportResponse(
                        r.get(DI_ID),
                        r.get(D_NAME),
                        r.get(DI_FILE_NAME),
                        r.get(DI_STATUS),
                        r.get(DI_CREATED_AT)
                ));

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
