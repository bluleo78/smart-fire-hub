package com.smartfirehub.dataimport.repository;

import com.smartfirehub.dataimport.dto.ImportResponse;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.jooq.impl.DSL.*;

@Repository
public class DataImportRepository {

    private final DSLContext dsl;

    // Table and field constants
    private static final Table<?> DATA_IMPORT = table(name("data_import"));
    private static final Field<Long> DI_ID = field(name("data_import", "id"), Long.class);
    private static final Field<Long> DI_DATASET_ID = field(name("data_import", "dataset_id"), Long.class);
    private static final Field<String> DI_FILE_NAME = field(name("data_import", "file_name"), String.class);
    private static final Field<Long> DI_FILE_SIZE = field(name("data_import", "file_size"), Long.class);
    private static final Field<String> DI_FILE_TYPE = field(name("data_import", "file_type"), String.class);
    private static final Field<String> DI_STATUS = field(name("data_import", "status"), String.class);
    private static final Field<Integer> DI_TOTAL_ROWS = field(name("data_import", "total_rows"), Integer.class);
    private static final Field<Integer> DI_SUCCESS_ROWS = field(name("data_import", "success_rows"), Integer.class);
    private static final Field<Integer> DI_ERROR_ROWS = field(name("data_import", "error_rows"), Integer.class);
    private static final Field<Object> DI_ERROR_DETAILS = field(name("data_import", "error_details"), Object.class);
    private static final Field<Long> DI_IMPORTED_BY = field(name("data_import", "imported_by"), Long.class);
    private static final Field<LocalDateTime> DI_STARTED_AT = field(name("data_import", "started_at"), LocalDateTime.class);
    private static final Field<LocalDateTime> DI_COMPLETED_AT = field(name("data_import", "completed_at"), LocalDateTime.class);
    private static final Field<LocalDateTime> DI_CREATED_AT = field(name("data_import", "created_at"), LocalDateTime.class);

    private static final Table<?> USER_TABLE = table(name("user"));
    private static final Field<Long> U_ID = field(name("user", "id"), Long.class);
    private static final Field<String> U_NAME = field(name("user", "name"), String.class);

    public DataImportRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    private ImportResponse mapToImportResponse(Record r) {
        return new ImportResponse(
                r.get(DI_ID),
                r.get(DI_DATASET_ID),
                r.get(DI_FILE_NAME),
                r.get(DI_FILE_SIZE),
                r.get(DI_FILE_TYPE),
                r.get(DI_STATUS),
                r.get(DI_TOTAL_ROWS),
                r.get(DI_SUCCESS_ROWS),
                r.get(DI_ERROR_ROWS),
                r.get(DI_ERROR_DETAILS),
                r.get(U_NAME) != null ? r.get(U_NAME) : String.valueOf(r.get(DI_IMPORTED_BY)),
                r.get(DI_STARTED_AT),
                r.get(DI_COMPLETED_AT),
                r.get(DI_CREATED_AT)
        );
    }

    public ImportResponse save(Long datasetId, String fileName, Long fileSize, String fileType, Long importedBy) {
        var record = dsl.insertInto(DATA_IMPORT)
                .set(DI_DATASET_ID, datasetId)
                .set(DI_FILE_NAME, fileName)
                .set(DI_FILE_SIZE, fileSize)
                .set(DI_FILE_TYPE, fileType)
                .set(DI_STATUS, "PENDING")
                .set(DI_IMPORTED_BY, importedBy)
                .returning(DI_ID, DI_DATASET_ID, DI_FILE_NAME, DI_FILE_SIZE, DI_FILE_TYPE, DI_STATUS,
                        DI_TOTAL_ROWS, DI_SUCCESS_ROWS, DI_ERROR_ROWS, DI_ERROR_DETAILS, DI_IMPORTED_BY,
                        DI_STARTED_AT, DI_COMPLETED_AT, DI_CREATED_AT)
                .fetchOne();

        String importedByName = dsl.select(U_NAME)
                .from(USER_TABLE)
                .where(U_ID.eq(importedBy))
                .fetchOptional(r -> r.get(U_NAME))
                .orElse(String.valueOf(importedBy));

        return new ImportResponse(
                record.get(DI_ID),
                record.get(DI_DATASET_ID),
                record.get(DI_FILE_NAME),
                record.get(DI_FILE_SIZE),
                record.get(DI_FILE_TYPE),
                record.get(DI_STATUS),
                record.get(DI_TOTAL_ROWS),
                record.get(DI_SUCCESS_ROWS),
                record.get(DI_ERROR_ROWS),
                record.get(DI_ERROR_DETAILS),
                importedByName,
                record.get(DI_STARTED_AT),
                record.get(DI_COMPLETED_AT),
                record.get(DI_CREATED_AT)
        );
    }

    public Optional<ImportResponse> findById(Long id) {
        return dsl.select(DI_ID, DI_DATASET_ID, DI_FILE_NAME, DI_FILE_SIZE, DI_FILE_TYPE, DI_STATUS,
                        DI_TOTAL_ROWS, DI_SUCCESS_ROWS, DI_ERROR_ROWS, DI_ERROR_DETAILS, DI_IMPORTED_BY,
                        DI_STARTED_AT, DI_COMPLETED_AT, DI_CREATED_AT, U_NAME)
                .from(DATA_IMPORT)
                .leftJoin(USER_TABLE).on(DI_IMPORTED_BY.eq(U_ID))
                .where(DI_ID.eq(id))
                .fetchOptional(this::mapToImportResponse);
    }

    public List<ImportResponse> findByDatasetId(Long datasetId) {
        return dsl.select(DI_ID, DI_DATASET_ID, DI_FILE_NAME, DI_FILE_SIZE, DI_FILE_TYPE, DI_STATUS,
                        DI_TOTAL_ROWS, DI_SUCCESS_ROWS, DI_ERROR_ROWS, DI_ERROR_DETAILS, DI_IMPORTED_BY,
                        DI_STARTED_AT, DI_COMPLETED_AT, DI_CREATED_AT, U_NAME)
                .from(DATA_IMPORT)
                .leftJoin(USER_TABLE).on(DI_IMPORTED_BY.eq(U_ID))
                .where(DI_DATASET_ID.eq(datasetId))
                .orderBy(DI_CREATED_AT.desc())
                .fetch(this::mapToImportResponse);
    }

    public void updateStatus(Long id, String status, Integer totalRows, Integer successRows,
                            Integer errorRows, String errorDetails, LocalDateTime startedAt,
                            LocalDateTime completedAt) {
        dsl.update(DATA_IMPORT)
                .set(DI_STATUS, status)
                .set(DI_TOTAL_ROWS, totalRows)
                .set(DI_SUCCESS_ROWS, successRows)
                .set(DI_ERROR_ROWS, errorRows)
                .set(DI_ERROR_DETAILS, errorDetails != null ? errorDetails : (Object) null)
                .set(DI_STARTED_AT, startedAt)
                .set(DI_COMPLETED_AT, completedAt)
                .where(DI_ID.eq(id))
                .execute();
    }

    public void updateStarted(Long id) {
        dsl.update(DATA_IMPORT)
                .set(DI_STATUS, "PROCESSING")
                .set(DI_STARTED_AT, currentLocalDateTime())
                .where(DI_ID.eq(id))
                .execute();
    }
}
