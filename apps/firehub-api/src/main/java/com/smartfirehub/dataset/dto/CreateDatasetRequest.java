package com.smartfirehub.dataset.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record CreateDatasetRequest(
    @NotBlank String name,
    @NotBlank String tableName,
    String description,
    Long categoryId,
    String datasetType,
    List<DatasetColumnRequest> columns) {}
