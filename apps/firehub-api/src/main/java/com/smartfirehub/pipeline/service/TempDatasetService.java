package com.smartfirehub.pipeline.service;

import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.service.DatasetService;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TempDatasetService {

  private final DatasetRepository datasetRepository;
  private final DatasetService datasetService;
  private final DatasetColumnRepository columnRepository;

  /** Find the dataset ID for an existing temp dataset linked to the given pipeline step (FK). */
  public Optional<Long> findExistingTempDataset(Long stepId) {
    return datasetRepository.findBySourcePipelineStepId(stepId);
  }

  /**
   * Compare existing dataset columns with the new column list. Returns true if schema has changed.
   * PK columns and import_id are excluded from comparison.
   */
  public boolean hasSchemaChanged(Long datasetId, List<ColumnInfo> newColumns) {
    List<DatasetColumnResponse> existing = columnRepository.findByDatasetId(datasetId);
    List<String> existingPairs =
        existing.stream()
            .filter(c -> !c.isPrimaryKey() && !"import_id".equals(c.columnName()))
            .map(c -> c.columnName() + ":" + c.dataType())
            .sorted()
            .toList();
    List<String> newPairs =
        newColumns.stream().map(c -> c.name() + ":" + c.appType()).sorted().toList();
    return !existingPairs.equals(newPairs);
  }

  /** Create a new TEMP dataset for the given pipeline step and return its ID. */
  public Long createTempDataset(
      List<ColumnInfo> columns,
      Long pipelineId,
      String pipelineName,
      Long stepId,
      String stepName,
      Long userId) {

    String sanitizedName = sanitizeForTableName(stepName);
    String tableName = "ptmp_" + pipelineId + "_" + sanitizedName;
    String datasetName = pipelineName + " > " + stepName + " (자동생성)";

    List<DatasetColumnRequest> columnRequests =
        columns.stream()
            .map(
                c ->
                    new DatasetColumnRequest(
                        c.name(), c.name(), c.appType(), null, true, false, null, false))
            .toList();

    CreateDatasetRequest request =
        new CreateDatasetRequest(
            datasetName, tableName, "파이프라인 자동 생성 임시 데이터셋", null, "TEMP", columnRequests, stepId);

    return datasetService.createDataset(request, userId).id();
  }

  /** Delete a temp dataset (used when schema has changed). */
  public void deleteTempDataset(Long datasetId) {
    datasetService.deleteDataset(datasetId);
  }

  /**
   * Sanitize a step name to a safe PostgreSQL table name suffix. Converts to lowercase,
   * non-alphanumeric chars to underscores, deduplicates underscores, and appends a 4-char hash to
   * avoid collisions.
   */
  static String sanitizeForTableName(String stepName) {
    String sanitized =
        stepName
            .toLowerCase()
            .replaceAll("[^a-z0-9]", "_")
            .replaceAll("_+", "_")
            .replaceAll("^_|_$", "");
    if (sanitized.isEmpty()) sanitized = "step";
    if (sanitized.length() > 30) sanitized = sanitized.substring(0, 30);
    String hash = Integer.toHexString(stepName.hashCode() & 0xFFFF);
    return sanitized + "_" + hash;
  }
}
