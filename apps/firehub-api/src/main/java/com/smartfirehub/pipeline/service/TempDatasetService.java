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
   *
   * <p>_updated_at은 여기서 별도로 제외하지 않는다. {@code newColumns}는 항상 {@code
   * PipelineAsyncRunner.renameReservedColumns}를 거친 뒤 이 메서드로 들어오므로(SELECT * 패스스루로 실제
   * 소스 테이블의 _updated_at이 섞여 들어와도 이미 {@code updated_at_1}처럼 별칭 처리된 상태다), 이 목록에
   * 리터럴 "_updated_at"이 나타날 일이 없다. 또한 {@code existing}은 {@code dataset_column} 메타데이터에서
   * 오는데, 이 테이블은 물리 시스템 컬럼(id/created_at/_updated_at)을 애초에 행으로 갖지 않는다(사용자가
   * {@code DatasetColumnRequest}로 명시한 컬럼만 등록됨) — created_at도 동일한 이유로 제외 목록에 없다.
   * 사용자가 파이썬/API 스텝 출력 컬럼명을 직접 "_updated_at"으로 지정하는 예약어 오남용은 이 비교가 아니라
   * {@code DataTableService.SYSTEM_COLUMNS} 가드(테이블 생성 시점)에서 걸린다.
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
            datasetName,
            tableName,
            "파이프라인 자동 생성 임시 데이터셋",
            null,
            "TABLE", // storage_type: 임시 데이터셋은 항상 물리 테이블
            "TEMP", // origin_type: 파이프라인 자동 생성
            columnRequests,
            stepId);

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
