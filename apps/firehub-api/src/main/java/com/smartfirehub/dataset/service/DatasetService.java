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

    private static final Set<String> VALID_STATUSES = Set.of("NONE", "CERTIFIED", "DEPRECATED");

    private final DatasetRepository datasetRepository;
    private final DatasetColumnRepository columnRepository;
    private final DatasetCategoryRepository categoryRepository;
    private final DataTableService dataTableService;
    private final UserRepository userRepository;
    private final DatasetFavoriteRepository favoriteRepository;
    private final DatasetTagRepository tagRepository;

    public DatasetService(DatasetRepository datasetRepository,
                          DatasetColumnRepository columnRepository,
                          DatasetCategoryRepository categoryRepository,
                          DataTableService dataTableService,
                          UserRepository userRepository,
                          DatasetFavoriteRepository favoriteRepository,
                          DatasetTagRepository tagRepository) {
        this.datasetRepository = datasetRepository;
        this.columnRepository = columnRepository;
        this.categoryRepository = categoryRepository;
        this.dataTableService = dataTableService;
        this.userRepository = userRepository;
        this.favoriteRepository = favoriteRepository;
        this.tagRepository = tagRepository;
    }

    @Transactional
    public DatasetDetailResponse createDataset(CreateDatasetRequest request, Long userId) {
        dataTableService.validateName(request.tableName());

        for (DatasetColumnRequest col : request.columns()) {
            dataTableService.validateName(col.columnName());
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

        int nextOrder = columnRepository.getMaxOrder(datasetId) + 1;

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

        List<DatasetColumnResponse> columns = columnRepository.findByDatasetId(datasetId);
        if (columns.size() <= 1) {
            throw new ColumnModificationException("Cannot delete the last column of a dataset");
        }

        dataTableService.dropColumn(dataset.tableName(), column.columnName());
        columnRepository.deleteById(columnId);
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
}
