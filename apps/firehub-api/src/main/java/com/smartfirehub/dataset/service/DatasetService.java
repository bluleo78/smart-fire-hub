package com.smartfirehub.dataset.service;

import com.smartfirehub.dataset.dto.*;
import com.smartfirehub.dataset.exception.CategoryNotFoundException;
import com.smartfirehub.dataset.exception.ColumnModificationException;
import com.smartfirehub.dataset.exception.DatasetNotFoundException;
import com.smartfirehub.dataset.exception.DuplicateDatasetNameException;
import com.smartfirehub.dataset.repository.DatasetCategoryRepository;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.dataset.repository.DatasetFavoriteRepository;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.repository.DatasetTagRepository;
import com.smartfirehub.dataset.repository.QueryHistoryRepository;
import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class DatasetService {

    private static final Set<String> VALID_DATA_TYPES = Set.of(
            "TEXT", "VARCHAR", "INTEGER", "DECIMAL", "BOOLEAN", "DATE", "TIMESTAMP"
    );

    private static final Set<String> VALID_STATUSES = Set.of("NONE", "CERTIFIED", "DEPRECATED");

    private final DatasetRepository datasetRepository;
    private final DatasetColumnRepository columnRepository;
    private final DatasetCategoryRepository categoryRepository;
    private final DataTableService dataTableService;
    private final UserRepository userRepository;
    private final DatasetFavoriteRepository favoriteRepository;
    private final DatasetTagRepository tagRepository;
    private final QueryHistoryRepository queryHistoryRepository;

    public DatasetService(DatasetRepository datasetRepository,
                          DatasetColumnRepository columnRepository,
                          DatasetCategoryRepository categoryRepository,
                          DataTableService dataTableService,
                          UserRepository userRepository,
                          DatasetFavoriteRepository favoriteRepository,
                          DatasetTagRepository tagRepository,
                          QueryHistoryRepository queryHistoryRepository) {
        this.datasetRepository = datasetRepository;
        this.columnRepository = columnRepository;
        this.categoryRepository = categoryRepository;
        this.dataTableService = dataTableService;
        this.userRepository = userRepository;
        this.favoriteRepository = favoriteRepository;
        this.tagRepository = tagRepository;
        this.queryHistoryRepository = queryHistoryRepository;
    }

    @Transactional
    public DatasetDetailResponse createDataset(CreateDatasetRequest request, Long userId) {
        dataTableService.validateName(request.tableName());

        for (DatasetColumnRequest col : request.columns()) {
            dataTableService.validateName(col.columnName());
        }

        // Validate PK columns: must be NOT NULL
        for (DatasetColumnRequest col : request.columns()) {
            if (col.isPrimaryKey() && col.isNullable()) {
                throw new ColumnModificationException(
                        "Primary key column '" + col.columnName() + "' cannot be nullable");
            }
        }

        if (datasetRepository.existsByName(request.name())) {
            throw new DuplicateDatasetNameException("Dataset name already exists: " + request.name());
        }
        if (datasetRepository.existsByTableName(request.tableName())) {
            throw new DuplicateDatasetNameException("Table name already exists: " + request.tableName());
        }

        if (request.categoryId() != null) {
            categoryRepository.findById(request.categoryId())
                    .orElseThrow(() -> new CategoryNotFoundException("Category not found: " + request.categoryId()));
        }

        DatasetResponse dataset = datasetRepository.save(request, userId);
        columnRepository.saveBatch(dataset.id(), request.columns());
        dataTableService.createTable(request.tableName(), request.columns());

        return getDatasetById(dataset.id());
    }

    public PageResponse<DatasetResponse> getDatasets(Long categoryId, String datasetType, String search, int page, int size) {
        return getDatasets(categoryId, datasetType, search, page, size, null, null, false);
    }

    public PageResponse<DatasetResponse> getDatasets(Long categoryId, String datasetType, String search, int page, int size,
                                                      Long currentUserId, String status, boolean favoriteOnly) {
        List<DatasetResponse> content = datasetRepository.findAll(categoryId, datasetType, search, page, size, currentUserId, status, favoriteOnly);
        long totalElements = datasetRepository.count(categoryId, datasetType, search, currentUserId, status, favoriteOnly);
        int totalPages = (int) Math.ceil((double) totalElements / size);
        return new PageResponse<>(content, page, size, totalElements, totalPages);
    }

    public DatasetDetailResponse getDatasetById(Long id) {
        return getDatasetById(id, null);
    }

    public DatasetDetailResponse getDatasetById(Long id, Long currentUserId) {
        DatasetResponse dataset = datasetRepository.findById(id, currentUserId)
                .orElseThrow(() -> new DatasetNotFoundException("Dataset not found: " + id));

        List<DatasetColumnResponse> columns = columnRepository.findByDatasetId(id);
        long rowCount = dataTableService.countRows(dataset.tableName());

        String createdByUsername = datasetRepository.findCreatedByById(id)
                .flatMap(userRepository::findById)
                .map(user -> user.name())
                .orElse("unknown");

        var updatedAt = datasetRepository.findUpdatedAtById(id).orElse(null);

        String updatedByUsername = datasetRepository.findUpdatedByById(id)
                .flatMap(userRepository::findById)
                .map(user -> user.name())
                .orElse(null);

        return new DatasetDetailResponse(
                dataset.id(),
                dataset.name(),
                dataset.tableName(),
                dataset.description(),
                dataset.category(),
                dataset.datasetType(),
                createdByUsername,
                columns,
                rowCount,
                dataset.createdAt(),
                updatedAt,
                updatedByUsername,
                dataset.isFavorite(),
                dataset.tags(),
                dataset.status(),
                dataset.statusNote(),
                dataset.statusUpdatedBy(),
                dataset.statusUpdatedAt()
        );
    }

    @Transactional
    public void updateDataset(Long id, UpdateDatasetRequest request, Long userId) {
        datasetRepository.findById(id)
                .orElseThrow(() -> new DatasetNotFoundException("Dataset not found: " + id));

        if (request.categoryId() != null) {
            categoryRepository.findById(request.categoryId())
                    .orElseThrow(() -> new CategoryNotFoundException("Category not found: " + request.categoryId()));
        }

        datasetRepository.update(id, request, userId);
    }

    @Transactional
    public void deleteDataset(Long id) {
        DatasetResponse dataset = datasetRepository.findById(id)
                .orElseThrow(() -> new DatasetNotFoundException("Dataset not found: " + id));

        dataTableService.dropTable(dataset.tableName());
        columnRepository.deleteByDatasetId(id);
        datasetRepository.deleteById(id);
    }

    @Transactional
    public DatasetColumnResponse addColumn(Long datasetId, AddColumnRequest request) {
        DatasetResponse dataset = datasetRepository.findById(datasetId)
                .orElseThrow(() -> new DatasetNotFoundException("Dataset not found: " + datasetId));

        dataTableService.validateName(request.columnName());

        long rowCount = dataTableService.countRows(dataset.tableName());
        if (rowCount > 0 && !request.isNullable()) {
            throw new ColumnModificationException("Cannot add non-nullable column to dataset with existing data");
        }

        // PK validation
        if (request.isPrimaryKey() && rowCount > 0) {
            throw new ColumnModificationException(
                    "Cannot add primary key column to dataset with existing data. " +
                    "Add the column first, populate it, then designate it as primary key.");
        }
        if (request.isPrimaryKey() && request.isNullable()) {
            throw new ColumnModificationException("Primary key column cannot be nullable");
        }

        int nextOrder = columnRepository.getMaxOrder(datasetId) + 1;

        DatasetColumnRequest colRequest = new DatasetColumnRequest(
                request.columnName(),
                request.displayName(),
                request.dataType(),
                request.maxLength(),
                request.isNullable(),
                request.isIndexed(),
                request.description(),
                request.isPrimaryKey()
        );
        DatasetColumnResponse column = columnRepository.save(datasetId, colRequest, nextOrder);
        dataTableService.addColumn(dataset.tableName(), colRequest);

        if (request.isPrimaryKey()) {
            List<DatasetColumnResponse> allColumns = columnRepository.findByDatasetId(datasetId);
            List<String> pkColumnNames = allColumns.stream()
                    .filter(DatasetColumnResponse::isPrimaryKey)
                    .map(DatasetColumnResponse::columnName)
                    .toList();
            dataTableService.recreatePrimaryKeyIndex(dataset.tableName(), pkColumnNames);
        }

        return column;
    }

    @Transactional
    public void updateColumn(Long datasetId, Long columnId, UpdateColumnRequest request) {
        DatasetResponse dataset = datasetRepository.findById(datasetId)
                .orElseThrow(() -> new DatasetNotFoundException("Dataset not found: " + datasetId));

        DatasetColumnResponse column = columnRepository.findById(columnId)
                .orElseThrow(() -> new IllegalArgumentException("Column not found: " + columnId));

        Long columnDatasetId = columnRepository.findDatasetIdByColumnId(columnId)
                .orElseThrow(() -> new IllegalArgumentException("Column not found: " + columnId));

        if (!columnDatasetId.equals(datasetId)) {
            throw new IllegalArgumentException("Column does not belong to this dataset");
        }

        if (request.dataType() != null && !VALID_DATA_TYPES.contains(request.dataType())) {
            throw new ColumnModificationException("Invalid data type: " + request.dataType());
        }

        long rowCount = dataTableService.countRows(dataset.tableName());

        if (request.columnName() != null && !request.columnName().equals(column.columnName())) {
            if (rowCount > 0) {
                throw new ColumnModificationException("Cannot rename column when dataset has data");
            }
            dataTableService.validateName(request.columnName());
            if (column.isIndexed()) {
                dataTableService.renameIndex(dataset.tableName(), column.columnName(), request.columnName());
            }
            dataTableService.renameColumn(dataset.tableName(), column.columnName(), request.columnName());
        }

        if (request.dataType() != null && !request.dataType().equals(column.dataType())) {
            if (rowCount > 0) {
                throw new ColumnModificationException("Cannot change data type when dataset has data");
            }
            String currentColName = request.columnName() != null ? request.columnName() : column.columnName();
            dataTableService.alterColumnType(dataset.tableName(), currentColName, request.dataType(), request.maxLength());
        } else if (request.dataType() != null && "VARCHAR".equals(request.dataType()) && request.maxLength() != null && !request.maxLength().equals(column.maxLength())) {
            if (rowCount > 0) {
                throw new ColumnModificationException("Cannot change column size when dataset has data");
            }
            String currentColName = request.columnName() != null ? request.columnName() : column.columnName();
            dataTableService.alterColumnType(dataset.tableName(), currentColName, request.dataType(), request.maxLength());
        }

        if (request.isNullable() != null && request.isNullable() != column.isNullable()) {
            if (rowCount > 0) {
                throw new ColumnModificationException("Cannot change nullable constraint when dataset has data");
            }
            String currentColName = request.columnName() != null ? request.columnName() : column.columnName();
            dataTableService.setColumnNullable(dataset.tableName(), currentColName, request.isNullable());
        }

        if (request.isIndexed() != null && request.isIndexed() != column.isIndexed()) {
            String currentColName = request.columnName() != null ? request.columnName() : column.columnName();
            dataTableService.setColumnIndex(dataset.tableName(), currentColName, request.isIndexed());
        }

        // Prevent making PK column nullable
        if (request.isNullable() != null && request.isNullable() &&
                (request.isPrimaryKey() != null ? request.isPrimaryKey() : column.isPrimaryKey())) {
            throw new ColumnModificationException("Primary key column cannot be nullable");
        }

        // Handle primary key changes
        if (request.isPrimaryKey() != null && request.isPrimaryKey() != column.isPrimaryKey()) {
            if (request.isPrimaryKey()) {
                // Setting as PK: validate NOT NULL
                boolean nullable = request.isNullable() != null ? request.isNullable() : column.isNullable();
                if (nullable) {
                    throw new ColumnModificationException("Primary key column cannot be nullable");
                }
                // Check data uniqueness if dataset has data
                if (rowCount > 0) {
                    List<DatasetColumnResponse> allColumns = columnRepository.findByDatasetId(datasetId);
                    String colName = request.columnName() != null ? request.columnName() : column.columnName();
                    List<String> pkColNames = new java.util.ArrayList<>();
                    for (DatasetColumnResponse c : allColumns) {
                        if (c.isPrimaryKey() || c.id().equals(columnId)) {
                            String name = c.id().equals(columnId) ? colName : c.columnName();
                            if (!pkColNames.contains(name)) {
                                pkColNames.add(name);
                            }
                        }
                    }
                    if (!pkColNames.contains(colName)) pkColNames.add(colName);

                    if (!dataTableService.checkDataUniqueness(dataset.tableName(), pkColNames)) {
                        throw new ColumnModificationException(
                                "Cannot set as primary key: duplicate values exist in the data");
                    }
                }
            }
        }

        columnRepository.update(columnId, request);

        // Recreate PK index if PK flag changed
        if (request.isPrimaryKey() != null && request.isPrimaryKey() != column.isPrimaryKey()) {
            List<DatasetColumnResponse> updatedColumns = columnRepository.findByDatasetId(datasetId);
            List<String> pkColumnNames = updatedColumns.stream()
                    .filter(DatasetColumnResponse::isPrimaryKey)
                    .map(DatasetColumnResponse::columnName)
                    .toList();
            if (rowCount > 0 && request.isPrimaryKey()) {
                // Use regular (non-concurrent) index creation here because updateColumn() is @Transactional.
                // CREATE INDEX CONCURRENTLY cannot run inside a transaction block.
                // The regular CREATE UNIQUE INDEX will briefly lock the table but is safe within the transaction.
                dataTableService.recreatePrimaryKeyIndex(dataset.tableName(), pkColumnNames);
            } else {
                dataTableService.recreatePrimaryKeyIndex(dataset.tableName(), pkColumnNames);
            }
        }
    }

    @Transactional
    public void deleteColumn(Long datasetId, Long columnId) {
        DatasetResponse dataset = datasetRepository.findById(datasetId)
                .orElseThrow(() -> new DatasetNotFoundException("Dataset not found: " + datasetId));

        DatasetColumnResponse column = columnRepository.findById(columnId)
                .orElseThrow(() -> new IllegalArgumentException("Column not found: " + columnId));

        Long columnDatasetId = columnRepository.findDatasetIdByColumnId(columnId)
                .orElseThrow(() -> new IllegalArgumentException("Column not found: " + columnId));

        if (!columnDatasetId.equals(datasetId)) {
            throw new IllegalArgumentException("Column does not belong to this dataset");
        }

        List<DatasetColumnResponse> columns = columnRepository.findByDatasetId(datasetId);
        if (columns.size() <= 1) {
            throw new ColumnModificationException("Cannot delete the last column of a dataset");
        }

        dataTableService.dropColumn(dataset.tableName(), column.columnName());
        columnRepository.deleteById(columnId);

        // Recreate PK index if deleted column was a PK column
        if (column.isPrimaryKey()) {
            List<DatasetColumnResponse> remainingColumns = columnRepository.findByDatasetId(datasetId);
            List<String> pkColumnNames = remainingColumns.stream()
                    .filter(DatasetColumnResponse::isPrimaryKey)
                    .map(DatasetColumnResponse::columnName)
                    .toList();
            dataTableService.recreatePrimaryKeyIndex(dataset.tableName(), pkColumnNames);
        }
    }

    @Transactional(readOnly = true)
    public List<ColumnStatsResponse> getDatasetStats(Long datasetId) {
        DatasetResponse dataset = datasetRepository.findById(datasetId)
                .orElseThrow(() -> new DatasetNotFoundException("Dataset not found: " + datasetId));

        long rowCount = dataTableService.countRows(dataset.tableName());
        if (rowCount == 0) {
            return List.of();
        }

        List<DatasetColumnResponse> columns = columnRepository.findByDatasetId(datasetId);
        return dataTableService.getColumnStats(dataset.tableName(), columns);
    }

    @Transactional
    public void reorderColumns(Long datasetId, ReorderColumnsRequest request) {
        datasetRepository.findById(datasetId)
                .orElseThrow(() -> new DatasetNotFoundException("Dataset not found: " + datasetId));

        List<DatasetColumnResponse> existingColumns = columnRepository.findByDatasetId(datasetId);
        Set<Long> existingIds = existingColumns.stream()
                .map(DatasetColumnResponse::id)
                .collect(java.util.stream.Collectors.toSet());
        Set<Long> requestIds = new java.util.HashSet<>(request.columnIds());

        if (requestIds.size() != request.columnIds().size()) {
            throw new IllegalArgumentException("Duplicate column IDs in request");
        }
        if (!existingIds.equals(requestIds)) {
            throw new IllegalArgumentException("Column IDs must match exactly with dataset columns");
        }

        columnRepository.updateOrders(datasetId, request.columnIds());
    }

    @Transactional
    public DataDeleteResponse deleteDataRows(Long datasetId, List<Long> rowIds) {
        DatasetResponse dataset = datasetRepository.findById(datasetId)
                .orElseThrow(() -> new DatasetNotFoundException("Dataset not found: " + datasetId));
        int deletedCount = dataTableService.deleteRows(dataset.tableName(), rowIds);
        return new DataDeleteResponse(deletedCount);
    }

    public DataQueryResponse getDatasetData(Long datasetId, String search, int page, int size) {
        return getDatasetData(datasetId, search, page, size, null, "ASC", true);
    }

    public DataQueryResponse getDatasetData(Long datasetId, String search, int page, int size, String sortBy, String sortDir, boolean includeTotalCount) {
        DatasetResponse dataset = datasetRepository.findById(datasetId)
                .orElseThrow(() -> new DatasetNotFoundException("Dataset not found: " + datasetId));

        List<DatasetColumnResponse> columns = columnRepository.findByDatasetId(datasetId);
        List<String> columnNames = columns.stream().map(DatasetColumnResponse::columnName).toList();

        if (sortBy != null && !sortBy.isBlank()) {
            if (!columnNames.contains(sortBy)) {
                throw new IllegalArgumentException("정렬할 수 없는 컬럼입니다: " + sortBy);
            }
        } else {
            sortBy = null;
        }

        List<Map<String, Object>> rows = dataTableService.queryData(dataset.tableName(), columnNames, search, page, size, sortBy, sortDir);

        long totalElements = -1;
        int totalPages = -1;
        if (includeTotalCount) {
            totalElements = dataTableService.countRows(dataset.tableName(), columnNames, search);
            totalPages = (int) Math.ceil((double) totalElements / size);
        }

        return new DataQueryResponse(columns, rows, page, size, totalElements, totalPages);
    }

    // --- Phase 3-3: Favorites ---

    @Transactional
    public FavoriteToggleResponse toggleFavorite(Long datasetId, Long userId) {
        datasetRepository.findById(datasetId)
                .orElseThrow(() -> new DatasetNotFoundException("Dataset not found: " + datasetId));

        if (favoriteRepository.existsByUserIdAndDatasetId(userId, datasetId)) {
            favoriteRepository.delete(userId, datasetId);
            return new FavoriteToggleResponse(false);
        } else {
            favoriteRepository.insert(userId, datasetId);
            return new FavoriteToggleResponse(true);
        }
    }

    // --- Phase 4-3: Tags ---

    @Transactional
    public void addTag(Long datasetId, String tagName, Long userId) {
        datasetRepository.findById(datasetId)
                .orElseThrow(() -> new DatasetNotFoundException("Dataset not found: " + datasetId));

        boolean alreadyExists = tagRepository.findByDatasetId(datasetId).contains(tagName);
        if (alreadyExists) {
            throw new IllegalStateException("Tag already exists: " + tagName);
        }
        tagRepository.insert(datasetId, tagName, userId);
    }

    @Transactional
    public void deleteTag(Long datasetId, String tagName) {
        datasetRepository.findById(datasetId)
                .orElseThrow(() -> new DatasetNotFoundException("Dataset not found: " + datasetId));
        tagRepository.delete(datasetId, tagName);
    }

    public List<String> getAllDistinctTags() {
        return tagRepository.findAllDistinctTags();
    }

    // --- Phase 6-1: Status ---

    @Transactional
    public DatasetDetailResponse updateStatus(Long id, UpdateStatusRequest request, Long userId) {
        if (request.status() == null || !VALID_STATUSES.contains(request.status())) {
            throw new IllegalArgumentException("Invalid status. Must be one of: NONE, CERTIFIED, DEPRECATED");
        }

        datasetRepository.findById(id)
                .orElseThrow(() -> new DatasetNotFoundException("Dataset not found: " + id));

        datasetRepository.updateStatus(id, request.status(), request.note(), userId);
        return getDatasetById(id, userId);
    }

    // --- Phase 6-4: DERIVED Column Description Propagation ---

    @Transactional
    public void propagateDescriptions(Long datasetId) {
        datasetRepository.findById(datasetId)
                .orElseThrow(() -> new DatasetNotFoundException("Dataset not found: " + datasetId));

        List<DatasetColumnResponse> targetColumns = columnRepository.findByDatasetId(datasetId);

        // Collect column names that have empty descriptions
        List<String> emptyDescCols = targetColumns.stream()
                .filter(c -> c.description() == null || c.description().isBlank())
                .map(DatasetColumnResponse::columnName)
                .toList();

        if (emptyDescCols.isEmpty()) {
            return;
        }

        // Find matching column names from ALL other datasets that have descriptions
        for (DatasetColumnResponse col : targetColumns) {
            if (col.description() != null && !col.description().isBlank()) {
                continue;
            }
            // Look up source descriptions from other datasets
            String sourceDesc = columnRepository.findFirstDescriptionByColumnName(col.columnName(), datasetId);
            if (sourceDesc != null && !sourceDesc.isBlank()) {
                columnRepository.updateDescription(col.id(), sourceDesc);
            }
        }
    }

    // =========================================================================
    // Phase 1: SQL Query (Ad-hoc)
    // =========================================================================

    @Transactional
    public SqlQueryResponse executeQuery(Long datasetId, SqlQueryRequest request, Long userId) {
        datasetRepository.findById(datasetId)
                .orElseThrow(() -> new DatasetNotFoundException("Dataset not found: " + datasetId));

        SqlQueryResponse response = dataTableService.executeQuery(request.sql(), request.maxRows());

        // Save to query history
        boolean success = response.error() == null;
        queryHistoryRepository.save(
                datasetId, userId, request.sql(), response.queryType(),
                response.affectedRows(), response.executionTimeMs(),
                success, response.error()
        );

        // Invalidate caches on DML success (row count may have changed)
        // No action needed here — dataset detail queries recalculate row count on demand

        return response;
    }

    public PageResponse<QueryHistoryResponse> getQueryHistory(Long datasetId, int page, int size) {
        datasetRepository.findById(datasetId)
                .orElseThrow(() -> new DatasetNotFoundException("Dataset not found: " + datasetId));

        List<QueryHistoryResponse> content = queryHistoryRepository.findByDatasetId(datasetId, page, size);
        long totalElements = queryHistoryRepository.countByDatasetId(datasetId);
        int totalPages = (int) Math.ceil((double) totalElements / size);
        return new PageResponse<>(content, page, size, totalElements, totalPages);
    }

    // =========================================================================
    // Phase 2: Manual Row Entry
    // =========================================================================

    @Transactional
    public RowDataResponse addRow(Long datasetId, RowDataRequest request) {
        DatasetResponse dataset = datasetRepository.findById(datasetId)
                .orElseThrow(() -> new DatasetNotFoundException("Dataset not found: " + datasetId));

        List<DatasetColumnResponse> columns = columnRepository.findByDatasetId(datasetId);
        Map<String, Object> validatedData = validateAndConvertRowData(columns, request.data());

        List<String> columnNames = columns.stream().map(DatasetColumnResponse::columnName).toList();
        Long newId = dataTableService.insertRow(dataset.tableName(), columnNames, validatedData);

        // Return the newly inserted row
        Map<String, Object> rowData = dataTableService.getRow(dataset.tableName(), columnNames, newId);
        Map<String, Object> data = new LinkedHashMap<>();
        LocalDateTime createdAt = null;
        for (var entry : rowData.entrySet()) {
            if ("id".equals(entry.getKey()) || "import_id".equals(entry.getKey())) {
                continue;
            }
            if ("created_at".equals(entry.getKey())) {
                if (entry.getValue() instanceof LocalDateTime ldt) {
                    createdAt = ldt;
                }
                continue;
            }
            data.put(entry.getKey(), entry.getValue());
        }

        return new RowDataResponse(newId, data, createdAt);
    }

    @Transactional
    public void updateRow(Long datasetId, Long rowId, RowDataRequest request) {
        DatasetResponse dataset = datasetRepository.findById(datasetId)
                .orElseThrow(() -> new DatasetNotFoundException("Dataset not found: " + datasetId));

        List<DatasetColumnResponse> columns = columnRepository.findByDatasetId(datasetId);
        Map<String, Object> validatedData = validateAndConvertRowData(columns, request.data());

        List<String> columnNames = columns.stream().map(DatasetColumnResponse::columnName).toList();
        dataTableService.updateRow(dataset.tableName(), rowId, columnNames, validatedData);
    }

    public RowDataResponse getRow(Long datasetId, Long rowId) {
        DatasetResponse dataset = datasetRepository.findById(datasetId)
                .orElseThrow(() -> new DatasetNotFoundException("Dataset not found: " + datasetId));

        List<DatasetColumnResponse> columns = columnRepository.findByDatasetId(datasetId);
        List<String> columnNames = columns.stream().map(DatasetColumnResponse::columnName).toList();

        Map<String, Object> rowData = dataTableService.getRow(dataset.tableName(), columnNames, rowId);
        Map<String, Object> data = new LinkedHashMap<>();
        Long id = null;
        LocalDateTime createdAt = null;
        for (var entry : rowData.entrySet()) {
            if ("id".equals(entry.getKey())) {
                if (entry.getValue() instanceof Number n) {
                    id = n.longValue();
                }
                continue;
            }
            if ("import_id".equals(entry.getKey())) {
                continue;
            }
            if ("created_at".equals(entry.getKey())) {
                if (entry.getValue() instanceof LocalDateTime ldt) {
                    createdAt = ldt;
                }
                continue;
            }
            data.put(entry.getKey(), entry.getValue());
        }

        return new RowDataResponse(id, data, createdAt);
    }

    private Map<String, Object> validateAndConvertRowData(List<DatasetColumnResponse> columns, Map<String, Object> data) {
        Map<String, Object> result = new HashMap<>();
        List<String> errors = new ArrayList<>();

        for (DatasetColumnResponse col : columns) {
            Object value = data.get(col.columnName());

            if (value == null) {
                if (!col.isNullable()) {
                    errors.add("Column '" + col.columnName() + "' cannot be null");
                }
                result.put(col.columnName(), null);
                continue;
            }

            try {
                Object converted = convertValue(col, value);
                result.put(col.columnName(), converted);
            } catch (Exception e) {
                errors.add("Column '" + col.columnName() + "': " + e.getMessage());
            }
        }

        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Validation errors: " + String.join("; ", errors));
        }

        return result;
    }

    private Object convertValue(DatasetColumnResponse col, Object value) {
        return switch (col.dataType()) {
            case "TEXT", "VARCHAR" -> {
                if (!(value instanceof String s)) {
                    throw new IllegalArgumentException("Expected string value");
                }
                if ("VARCHAR".equals(col.dataType()) && col.maxLength() != null && s.length() > col.maxLength()) {
                    throw new IllegalArgumentException(
                            "Value exceeds max length " + col.maxLength() + " (actual: " + s.length() + ")");
                }
                yield s;
            }
            case "INTEGER" -> {
                if (value instanceof Number n) {
                    yield n.longValue();
                }
                throw new IllegalArgumentException("Expected numeric value");
            }
            case "DECIMAL" -> {
                if (value instanceof Number n) {
                    yield new BigDecimal(n.toString());
                }
                throw new IllegalArgumentException("Expected numeric value");
            }
            case "BOOLEAN" -> {
                if (value instanceof Boolean b) {
                    yield b;
                }
                throw new IllegalArgumentException("Expected boolean value");
            }
            case "DATE" -> {
                if (value instanceof String s) {
                    try {
                        yield LocalDate.parse(s);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("Expected date format yyyy-MM-dd");
                    }
                }
                throw new IllegalArgumentException("Expected date string in yyyy-MM-dd format");
            }
            case "TIMESTAMP" -> {
                if (value instanceof String s) {
                    try {
                        yield LocalDateTime.parse(s);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("Expected timestamp format yyyy-MM-ddTHH:mm:ss");
                    }
                }
                throw new IllegalArgumentException("Expected timestamp string");
            }
            default -> throw new IllegalArgumentException("Unknown data type: " + col.dataType());
        };
    }

    // =========================================================================
    // Phase 3: Clone/Copy Dataset
    // =========================================================================

    @Transactional
    public DatasetDetailResponse cloneDataset(Long sourceId, CloneDatasetRequest request, Long userId) {
        // 1. Fetch source dataset with columns
        DatasetResponse sourceDataset = datasetRepository.findById(sourceId)
                .orElseThrow(() -> new DatasetNotFoundException("Dataset not found: " + sourceId));

        List<DatasetColumnResponse> sourceColumns = columnRepository.findByDatasetId(sourceId);

        // 2. Validate new names don't already exist
        dataTableService.validateName(request.tableName());
        if (datasetRepository.existsByName(request.name())) {
            throw new DuplicateDatasetNameException("Dataset name already exists: " + request.name());
        }
        if (datasetRepository.existsByTableName(request.tableName())) {
            throw new DuplicateDatasetNameException("Table name already exists: " + request.tableName());
        }

        // 3. Create new dataset record
        String description = request.description() != null ? request.description() : sourceDataset.description();
        CreateDatasetRequest createRequest = new CreateDatasetRequest(
                request.name(),
                request.tableName(),
                description,
                sourceDataset.category() != null ? sourceDataset.category().id() : null,
                sourceDataset.datasetType(),
                List.of() // columns will be added separately
        );
        DatasetResponse newDataset = datasetRepository.save(createRequest, userId);

        // 4. Copy column definitions
        List<DatasetColumnRequest> columnRequests = new ArrayList<>();
        for (DatasetColumnResponse col : sourceColumns) {
            DatasetColumnRequest colReq = new DatasetColumnRequest(
                    col.columnName(),
                    col.displayName(),
                    col.dataType(),
                    col.maxLength(),
                    col.isNullable(),
                    col.isIndexed(),
                    col.description(),
                    col.isPrimaryKey()
            );
            columnRequests.add(colReq);
        }
        columnRepository.saveBatch(newDataset.id(), columnRequests);

        // 5. Clone table data or create empty schema
        List<String> userColumnNames = sourceColumns.stream()
                .map(DatasetColumnResponse::columnName)
                .toList();

        if (request.includeData()) {
            dataTableService.cloneTable(sourceDataset.tableName(), request.tableName(), userColumnNames, sourceColumns);

            // Recreate indexes
            for (DatasetColumnResponse col : sourceColumns) {
                if (col.isIndexed()) {
                    dataTableService.setColumnIndex(request.tableName(), col.columnName(), true);
                }
            }

            // Recreate PK unique index
            List<String> pkColumnNames = sourceColumns.stream()
                    .filter(DatasetColumnResponse::isPrimaryKey)
                    .map(DatasetColumnResponse::columnName)
                    .toList();
            if (!pkColumnNames.isEmpty()) {
                dataTableService.recreatePrimaryKeyIndex(request.tableName(), pkColumnNames);
            }
        } else {
            dataTableService.createTable(request.tableName(), columnRequests);
        }

        // 6. Copy tags if requested
        if (request.includeTags()) {
            List<String> sourceTags = tagRepository.findByDatasetId(sourceId);
            for (String tag : sourceTags) {
                tagRepository.insert(newDataset.id(), tag, userId);
            }
        }

        // 7. Return full detail response
        return getDatasetById(newDataset.id(), userId);
    }
}
