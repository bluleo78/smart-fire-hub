package com.smartfirehub.dataset.service;

import com.smartfirehub.dataset.dto.*;
import com.smartfirehub.dataset.exception.CategoryNotFoundException;
import com.smartfirehub.dataset.exception.ColumnModificationException;
import com.smartfirehub.dataset.exception.DatasetNotFoundException;
import com.smartfirehub.dataset.exception.DuplicateDatasetNameException;
import com.smartfirehub.dataset.repository.DatasetCategoryRepository;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class DatasetService {

    private static final Set<String> VALID_DATA_TYPES = Set.of(
            "TEXT", "VARCHAR", "INTEGER", "DECIMAL", "BOOLEAN", "DATE", "TIMESTAMP"
    );

    private final DatasetRepository datasetRepository;
    private final DatasetColumnRepository columnRepository;
    private final DatasetCategoryRepository categoryRepository;
    private final DataTableService dataTableService;
    private final UserRepository userRepository;

    public DatasetService(DatasetRepository datasetRepository,
                          DatasetColumnRepository columnRepository,
                          DatasetCategoryRepository categoryRepository,
                          DataTableService dataTableService,
                          UserRepository userRepository) {
        this.datasetRepository = datasetRepository;
        this.columnRepository = columnRepository;
        this.categoryRepository = categoryRepository;
        this.dataTableService = dataTableService;
        this.userRepository = userRepository;
    }

    @Transactional
    public DatasetDetailResponse createDataset(CreateDatasetRequest request, Long userId) {
        // Validate table name
        dataTableService.validateName(request.tableName());

        // Validate column names
        for (DatasetColumnRequest col : request.columns()) {
            dataTableService.validateName(col.columnName());
        }

        // Check uniqueness
        if (datasetRepository.existsByName(request.name())) {
            throw new DuplicateDatasetNameException("Dataset name already exists: " + request.name());
        }
        if (datasetRepository.existsByTableName(request.tableName())) {
            throw new DuplicateDatasetNameException("Table name already exists: " + request.tableName());
        }

        // Verify category exists if provided
        if (request.categoryId() != null) {
            categoryRepository.findById(request.categoryId())
                    .orElseThrow(() -> new CategoryNotFoundException("Category not found: " + request.categoryId()));
        }

        // Save dataset record
        DatasetResponse dataset = datasetRepository.save(request, userId);

        // Save columns
        columnRepository.saveBatch(dataset.id(), request.columns());

        // Create physical table
        dataTableService.createTable(request.tableName(), request.columns());

        // Fetch full detail for response
        return getDatasetById(dataset.id());
    }

    public PageResponse<DatasetResponse> getDatasets(Long categoryId, String datasetType, String search, int page, int size) {
        List<DatasetResponse> content = datasetRepository.findAll(categoryId, datasetType, search, page, size);
        long totalElements = datasetRepository.count(categoryId, datasetType, search);
        int totalPages = (int) Math.ceil((double) totalElements / size);
        return new PageResponse<>(content, page, size, totalElements, totalPages);
    }

    public DatasetDetailResponse getDatasetById(Long id) {
        DatasetResponse dataset = datasetRepository.findById(id)
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
                updatedByUsername
        );
    }

    @Transactional
    public void updateDataset(Long id, UpdateDatasetRequest request, Long userId) {
        datasetRepository.findById(id)
                .orElseThrow(() -> new DatasetNotFoundException("Dataset not found: " + id));

        // Verify new category exists if provided
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

        // Drop the physical table
        dataTableService.dropTable(dataset.tableName());

        // Delete columns
        columnRepository.deleteByDatasetId(id);

        // Delete dataset record
        datasetRepository.deleteById(id);
    }

    @Transactional
    public DatasetColumnResponse addColumn(Long datasetId, AddColumnRequest request) {
        DatasetResponse dataset = datasetRepository.findById(datasetId)
                .orElseThrow(() -> new DatasetNotFoundException("Dataset not found: " + datasetId));

        // Validate column name
        dataTableService.validateName(request.columnName());

        // Check if dataset has data
        long rowCount = dataTableService.countRows(dataset.tableName());
        if (rowCount > 0 && !request.isNullable()) {
            throw new ColumnModificationException("Cannot add non-nullable column to dataset with existing data");
        }

        // Get next column order
        int nextOrder = columnRepository.getMaxOrder(datasetId) + 1;

        // Save column metadata
        DatasetColumnRequest colRequest = new DatasetColumnRequest(
                request.columnName(),
                request.displayName(),
                request.dataType(),
                request.maxLength(),
                request.isNullable(),
                request.isIndexed(),
                request.description()
        );
        DatasetColumnResponse column = columnRepository.save(datasetId, colRequest, nextOrder);

        // Add column to physical table
        dataTableService.addColumn(dataset.tableName(), colRequest);

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

        // dataType 유효성 검증
        if (request.dataType() != null && !VALID_DATA_TYPES.contains(request.dataType())) {
            throw new ColumnModificationException("Invalid data type: " + request.dataType());
        }

        long rowCount = dataTableService.countRows(dataset.tableName());

        // columnName 변경
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

        // dataType 변경
        if (request.dataType() != null && !request.dataType().equals(column.dataType())) {
            if (rowCount > 0) {
                throw new ColumnModificationException("Cannot change data type when dataset has data");
            }
            String currentColName = request.columnName() != null ? request.columnName() : column.columnName();
            dataTableService.alterColumnType(dataset.tableName(), currentColName, request.dataType(), request.maxLength());
        } else if (request.dataType() != null && "VARCHAR".equals(request.dataType()) && request.maxLength() != null && !request.maxLength().equals(column.maxLength())) {
            // VARCHAR인데 maxLength만 바뀐 경우
            if (rowCount > 0) {
                throw new ColumnModificationException("Cannot change column size when dataset has data");
            }
            String currentColName = request.columnName() != null ? request.columnName() : column.columnName();
            dataTableService.alterColumnType(dataset.tableName(), currentColName, request.dataType(), request.maxLength());
        }

        // isNullable 변경
        if (request.isNullable() != null && request.isNullable() != column.isNullable()) {
            if (rowCount > 0) {
                throw new ColumnModificationException("Cannot change nullable constraint when dataset has data");
            }
            String currentColName = request.columnName() != null ? request.columnName() : column.columnName();
            dataTableService.setColumnNullable(dataset.tableName(), currentColName, request.isNullable());
        }

        // isIndexed 변경
        if (request.isIndexed() != null && request.isIndexed() != column.isIndexed()) {
            String currentColName = request.columnName() != null ? request.columnName() : column.columnName();
            dataTableService.setColumnIndex(dataset.tableName(), currentColName, request.isIndexed());
        }

        // 메타데이터 업데이트
        columnRepository.update(columnId, request);
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

        // 최소 1개 컬럼은 유지해야 함
        List<DatasetColumnResponse> columns = columnRepository.findByDatasetId(datasetId);
        if (columns.size() <= 1) {
            throw new ColumnModificationException("Cannot delete the last column of a dataset");
        }

        // 물리 컬럼 삭제
        dataTableService.dropColumn(dataset.tableName(), column.columnName());

        // 메타데이터 삭제
        columnRepository.deleteById(columnId);
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

    public DataQueryResponse getDatasetData(Long datasetId, String search, int page, int size) {
        DatasetResponse dataset = datasetRepository.findById(datasetId)
                .orElseThrow(() -> new DatasetNotFoundException("Dataset not found: " + datasetId));

        List<DatasetColumnResponse> columns = columnRepository.findByDatasetId(datasetId);
        List<String> columnNames = columns.stream().map(DatasetColumnResponse::columnName).toList();

        List<Map<String, Object>> rows = dataTableService.queryData(dataset.tableName(), columnNames, search, page, size);
        long totalElements = dataTableService.countRows(dataset.tableName(), columnNames, search);
        int totalPages = (int) Math.ceil((double) totalElements / size);

        return new DataQueryResponse(columns, rows, page, size, totalElements, totalPages);
    }
}
