package com.smartfirehub.dataset.dto;

import java.util.List;

public record CreateDatasetRequest(
    String name,
    String tableName,
    String description,
    Long categoryId,
    String datasetType,
    List<DatasetColumnRequest> columns
) {}
