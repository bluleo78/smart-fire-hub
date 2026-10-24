package com.smartfirehub.pipeline.service;

import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.service.DataTableService;
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
  private final DataTableService dataTableService;

  /** Find the dataset ID for an existing temp dataset linked to the given pipeline step (FK). */
  public Optional<Long> findExistingTempDataset(Long stepId) {
    return datasetRepository.findBySourcePipelineStepId(stepId);
  }

  /**
   * Compare existing dataset columns with the new column list. Returns true if schema has changed.
   * PK columns and import_id are excluded from comparison.
   *
   * <p>_updated_at은 여기서 별도로 제외하지 않는다. {@code newColumns}는 항상 {@code
   * PipelineAsyncRunner.renameReservedColumns}를 거친 뒤 이 메서드로 들어오므로(SELECT * 패스스루로 실제 소스 테이블의
   * _updated_at이 섞여 들어와도 이미 {@code updated_at_1}처럼 별칭 처리된 상태다), 이 목록에 리터럴 "_updated_at"이 나타날 일이 없다.
   * 또한 {@code existing}은 {@code dataset_column} 메타데이터에서 오는데, 이 테이블은 물리 시스템
   * 컬럼(id/created_at/_updated_at)을 애초에 행으로 갖지 않는다(사용자가 {@code DatasetColumnRequest}로 명시한 컬럼만 등록됨) —
   * created_at도 동일한 이유로 제외 목록에 없다. 사용자가 파이썬/API 스텝 출력 컬럼명을 직접 "_updated_at"으로 지정하는 예약어 오남용은 이 비교가
   * 아니라 {@code DataTableService.SYSTEM_COLUMNS} 가드(테이블 생성 시점)에서 걸린다.
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

    List<DatasetColumnRequest> columnRequests = toColumnRequests(columns);

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

    // 예약 출처 필드(TEMP·스텝 id)는 러너 전용 진입점으로만 쓸 수 있다 — 일반 createDataset 은 거부한다.
    return datasetService.createPipelineTempDataset(request, userId).id();
  }

  /**
   * 새 TEMP 데이터셋 컬럼이 생성 검증({@link DataTableService#validateNewColumns})을 통과하는지 미리 확인한다. 스키마가 바뀐 TEMP
   * 는 "옛 것 삭제 → 새로 생성" 이라, 러너가 삭제 전에 불러 실패할 생성이면 옛 TEMP 를 그대로 둔다. 실제 생성과 같은 요청({@link
   * #toColumnRequests})으로 검증한다.
   */
  public void validateColumnsForNewTemp(List<ColumnInfo> columns) {
    dataTableService.validateNewColumns(toColumnRequests(columns));
  }

  /**
   * 스키마가 바뀐 러너 소유 TEMP 를 같은 데이터셋 id 그대로 새 열 구성으로 다시 만든다(WD-222). 삭제 후 생성은 화면 저장으로 출력 id 가 고정된 스텝에서
   * 출력 참조 거부로 실패하고 id 가 바뀌어 참조가 깨지므로 쓰지 않는다 — 근거는 {@link DatasetService#rebuildPipelineTempTable}.
   */
  public void rebuildTempDataset(Long datasetId, List<ColumnInfo> columns) {
    datasetService.rebuildPipelineTempTable(datasetId, toColumnRequests(columns));
  }

  /** TEMP 컬럼 정보를 데이터셋 컬럼 생성 요청으로 바꾼다 — 사전 검증과 실제 생성이 같은 요청을 쓰게 하는 단일 지점. */
  private static List<DatasetColumnRequest> toColumnRequests(List<ColumnInfo> columns) {
    return columns.stream()
        .map(
            c ->
                new DatasetColumnRequest(
                    c.name(), c.name(), c.appType(), null, true, false, null, false))
        .toList();
  }

  /**
   * 임시 데이터셋을 데이터셋 삭제 경로로 지운다 — 스키마가 바뀌었을 때(러너), 그리고 스텝·파이프라인을 지울 때(WD-170, PipelineService) 쓴다. 출력
   * 참조 거부는 호출자 트랜잭션을 롤백 전용으로 만들므로 호출자가 미리 걸러서 부른다.
   */
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
