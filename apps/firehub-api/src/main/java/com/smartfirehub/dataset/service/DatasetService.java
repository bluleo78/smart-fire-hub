package com.smartfirehub.dataset.service;

import static org.jooq.impl.DSL.*;

import com.smartfirehub.analytics.repository.AnalyticsDashboardRepository;
import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.dataset.dto.AddColumnRequest;
import com.smartfirehub.dataset.dto.CloneDatasetRequest;
import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.dto.DatasetDetailResponse;
import com.smartfirehub.dataset.dto.DatasetReferencesResponse;
import com.smartfirehub.dataset.dto.DatasetResponse;
import com.smartfirehub.dataset.dto.ReorderColumnsRequest;
import com.smartfirehub.dataset.dto.UpdateColumnRequest;
import com.smartfirehub.dataset.dto.UpdateDatasetRequest;
import com.smartfirehub.dataset.dto.UpdateStatusRequest;
import com.smartfirehub.dataset.exception.CategoryNotFoundException;
import com.smartfirehub.dataset.exception.ColumnModificationException;
import com.smartfirehub.dataset.exception.DatasetInUseException;
import com.smartfirehub.dataset.exception.DatasetNotFoundException;
import com.smartfirehub.dataset.exception.DuplicateDatasetNameException;
import com.smartfirehub.dataset.repository.DatasetCategoryRepository;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.repository.DatasetTagRepository;
import com.smartfirehub.dataset.rowsearch.IndexRef;
import com.smartfirehub.dataset.rowsearch.RowSearchIndex;
import com.smartfirehub.dataset.rowsearch.SearchColumnRepository;
import com.smartfirehub.dataset.rowsearch.SearchConfig;
import com.smartfirehub.dataset.rowsearch.SearchIndexSettingsService;
import com.smartfirehub.dataset.search.DatasetChangedEvent;
import com.smartfirehub.dataset.search.DatasetEmbeddingService;
import com.smartfirehub.dataset.storage.DataTableStore.RebuildBackupState;
import com.smartfirehub.file.repository.FileDatasetConfigRepository;
import com.smartfirehub.file.service.FileObjectStorageService;
import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.mapping.service.MappingReferenceService;
import com.smartfirehub.pipeline.repository.TriggerRepository;
import com.smartfirehub.pipeline.service.PipelineDataSchema;
import com.smartfirehub.pipeline.service.PipelineService;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import com.smartfirehub.securitylevel.service.DatasetSecurityService;
import com.smartfirehub.user.repository.UserRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.exception.IntegrityConstraintViolationException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DatasetService {

  private static final Set<String> VALID_DATA_TYPES =
      Set.of("TEXT", "VARCHAR", "INTEGER", "DECIMAL", "BOOLEAN", "DATE", "TIMESTAMP", "GEOMETRY");

  private static final Set<String> VALID_STATUSES = Set.of("NONE", "CERTIFIED", "DEPRECATED");

  // DOCUMENT 데이터셋은 data.<table> 동적 테이블/컬럼이 없고 데이터가 document_chunk 에 저장된다.
  private static final String DOCUMENT_TYPE = "DOCUMENT";

  // FILE(오브젝트) 데이터셋은 개별 파일이 MinIO 에 저장되고 dataset 은 버킷/프리픽스 매핑만 가지므로
  // data.<table> 동적 테이블/컬럼이 없다.
  private static final String FILE_TYPE = "FILE";

  /** 물리 테이블/컬럼을 갖는 저장 타입인지 여부 (TABLE 만 true, DOCUMENT/FILE 은 false). */
  private static boolean isTableBacked(String storageType) {
    return !DOCUMENT_TYPE.equals(storageType) && !FILE_TYPE.equals(storageType);
  }

  /** FILE 데이터셋 생성 요청에 클라이언트가 프리픽스를 지정했을 때의 오류 코드(400). */
  public static final String FILE_PREFIX_NOT_ALLOWED_CODE = "FILE_PREFIX_NOT_ALLOWED";

  /**
   * 같은 데이터셋에 같은 이름의 필드를 만들거나(추가·생성) 다른 필드 이름으로 바꾸려 할 때의 오류 코드(409, WD-99).
   *
   * <p>사전 검사가 없으면 카탈로그 유니크 인덱스(dataset_id, column_name) 위반이 무결성 처리기의 일반 문구로만 새어 나가 사용자가 어느 필드가 문제인지
   * 알 수 없다. 그래서 카탈로그 저장·DDL 전에 필드명을 실어 거부한다.
   */
  public static final String COLUMN_NAME_DUPLICATE_CODE = "COLUMN_NAME_DUPLICATE";

  /**
   * 카탈로그 dataset_column 의 (dataset_id, column_name) 유니크 제약 이름 — V2 의 이름 없는 {@code UNIQUE
   * (dataset_id, column_name)} 에 PostgreSQL 이 붙인 기본 이름이다(이후 마이그레이션에서 바뀐 적 없음). 사전 검사를 지나 이 제약에
   * 걸리면(동시 추가 등) 쓰기 지점이 이 이름으로 알아보고 같은 409 로 바꾼다({@link #withColumnNameUniqueAs409}). 실제 DB 가 이 이름을
   * 보고하는지는 통합 테스트가 고정한다.
   */
  private static final String COLUMN_NAME_UNIQUE_CONSTRAINT =
      "dataset_column_dataset_id_column_name_key";

  /** 필드명 중복 문구 — 사전 검사는 뒤에 「: 필드명」을 붙이고, 제약 위반 변환은 DB 원문을 쓰지 않으려 이름 없이 쓴다. */
  private static final String COLUMN_NAME_DUPLICATE_MESSAGE = "이미 있는 필드명입니다";

  /** 필드명 중복 409 — 사전 검사(생성·추가·이름 변경)가 같은 코드·문구를 쓰도록 한곳에서 만든다. */
  private static CodedApiException duplicateColumnName(String columnName) {
    return new CodedApiException(
        HttpStatus.CONFLICT,
        COLUMN_NAME_DUPLICATE_CODE,
        COLUMN_NAME_DUPLICATE_MESSAGE + ": " + columnName);
  }

  /**
   * dataset_column 쓰기를 실행하고, 필드명 유니크 제약 위반이면 사전 검사와 같은 코드의 409 로 바꾼다(WD-99) — 보안 등급 이름의 {@code
   * SecurityLevelService.withNameDuplicateAs409} 와 같은 방식.
   *
   * <p>왜: 사전 검사와 쓰기 사이에 같은 이름이 동시에 들어오면 사전 검사는 미커밋 행을 못 봐 통과하고, 쓰기가 유니크 제약에서 끝난다. 그대로 두면 전역 처리기의 일반
   * 중복 문구(코드 없음)가 되어 화면이 필드명 중복 안내를 못 한다. 필드명은 DB 세부 원문에서 꺼내지 않는다(원문 비노출). 다른 제약 위반은 그대로 던진다.
   */
  private static <T> T withColumnNameUniqueAs409(Supplier<T> write) {
    try {
      return write.get();
    } catch (DataIntegrityViolationException e) {
      ServerErrorMessage serverError = DataExceptionTranslator.findServerError(e);
      if (serverError != null
          && COLUMN_NAME_UNIQUE_CONSTRAINT.equals(serverError.getConstraint())) {
        throw new CodedApiException(
            HttpStatus.CONFLICT, COLUMN_NAME_DUPLICATE_CODE, COLUMN_NAME_DUPLICATE_MESSAGE);
      }
      throw e;
    }
  }

  /** 반환값 없는 쓰기용 {@link #withColumnNameUniqueAs409(Supplier)}. */
  private static void withColumnNameUniqueAs409(Runnable write) {
    withColumnNameUniqueAs409(
        () -> {
          write.run();
          return null;
        });
  }

  /** 기본 키 필드의 NULL 허용 거부 — 생성·추가·수정·기본 키 일괄 설정이 같은 문구를 쓴다. */
  private static ColumnModificationException primaryKeyNullable(String columnName) {
    return new ColumnModificationException("기본 키 필드는 NULL 을 허용할 수 없습니다: " + columnName);
  }

  /**
   * FILE 데이터셋의 저장 프리픽스 — 항상 서버가 {@code datasets/<데이터셋 id>/} 로 만든다.
   *
   * <p>보안(격리) 목적: 컨트롤러는 오브젝트 키가 {@code cfg.prefix()} 로 시작하는지로 데이터셋 간 접근을 격리한다. 사용자가 프리픽스를 고를 수 있으면
   * 숨김(보안 등급) 데이터셋이나 다른 테넌트 데이터셋의 프리픽스({@code datasets/<그 id>/}, id 는 순번이라 추측 가능)를 덮는 새 데이터셋을 만들어 그
   * 객체 목록·presigned URL 을 받을 수 있다(최종 리뷰 C1). 데이터셋 id 는 전역 유일이므로 이 형태는 테넌트를 넘어서도 겹치지 않고, 끝의 "/" 가
   * {@code datasets/1/} 과 {@code datasets/12/} 의 부분 일치를 막는다.
   */
  private static String serverFilePrefix(long datasetId) {
    return "datasets/" + datasetId + "/";
  }

  private final DatasetRepository datasetRepository;
  private final DatasetColumnRepository columnRepository;
  private final DatasetCategoryRepository categoryRepository;
  private final DataTableService dataTableService;
  private final DataTableRowService dataTableRowService;
  private final UserRepository userRepository;
  private final DatasetTagRepository tagRepository;
  private final DSLContext dsl;
  private final AuditLogService auditLogService;
  private final DatasetAccessGuard datasetAccessGuard;
  private final DatasetSecurityService datasetSecurityService;
  // 검색 인덱싱: source_text 동기 저장 + 임베딩 비동기 재생성 트리거 (통합 데이터셋 Discovery)
  private final DatasetEmbeddingService datasetEmbeddingService;
  private final ApplicationEventPublisher events;
  // FILE 데이터셋의 MinIO 버킷/프리픽스 매핑 저장소
  private final FileDatasetConfigRepository fileDatasetConfigRepository;
  // FILE 데이터셋 생성 시 bucket 미지정이면 기본 버킷을 조회하기 위해 사용
  private final FileObjectStorageService fileObjectStorageService;
  // 행 검색: 삭제 시 색인 테이블 정리, 검색 대상 필드 타입 변경 차단, 마지막 검색 필드 삭제 시 검색 끄기
  private final RowSearchIndex rowSearchIndex;
  private final SearchColumnRepository searchColumnRepository;
  private final SearchIndexSettingsService searchIndexSettingsService;
  // WD-186: 필드 이름 변경·타입 변경·삭제가 이 데이터셋의 지식그래프 매핑(필드 이름 참조)을 깨지 않게 지킨다.
  private final MappingReferenceService mappingReferenceService;
  // WD-221: 삭제되는 데이터셋을 데이터 변경 트리거의 감시 목록·스냅숏에서 빼고, 감시 대상이 모두 사라진 트리거는 끈다.
  private final TriggerRepository triggerRepository;

  /** 테넌트 방언(PG/Tibero)별 SQL 파서·데이터 스키마 — 사용처 SQL 대조에 쓴다(WD-206). */
  private final PipelineDataSchema pipelineDataSchema;

  /** 데이터셋 정보·상태·필드 변경 감사 기록(WD-463). */
  private final DatasetAuditRecorder datasetAuditRecorder;

  /** 파이프라인 전용 출처 필드를 일반 생성 경로에서 보냈을 때의 오류 코드. */
  public static final String ORIGIN_RESERVED_CODE = "DATASET_ORIGIN_RESERVED";

  /**
   * 일반 데이터셋 생성(REST·AI 에이전트 등 모든 비파이프라인 경로).
   *
   * <p>{@code originType=TEMP} 와 {@code sourcePipelineStepId} 는 파이프라인 러너만 쓰는 예약 필드라 여기서 거부한다(400).
   * 이 두 값이 곧 "어느 스텝의 러너 소유 TEMP 인가"의 식별자다 — 스텝 출력 폴백({@code
   * PipelineStepRepository.findByPipelineId}·{@code TempDatasetService.findExistingTempDataset})과
   * 보안 관문({@code PipelineSecurityGate})이 이 값을 믿는다. 사용자가 위조할 수 있으면 남의 파이프라인 스텝 출력을 자기 데이터셋으로 돌려 실행
   * 주체의 입력 행을 받아 갈 수 있다(유출). 러너는 {@link #createPipelineTempDataset} 만 쓴다.
   */
  @Transactional
  public DatasetDetailResponse createDataset(CreateDatasetRequest request, Long userId) {
    if ("TEMP".equalsIgnoreCase(request.originType()) || request.sourcePipelineStepId() != null) {
      throw new CodedApiException(
          HttpStatus.BAD_REQUEST,
          ORIGIN_RESERVED_CODE,
          "임시(TEMP) 데이터셋과 파이프라인 스텝 연결은 파이프라인 실행만 만들 수 있습니다.");
    }
    return createDatasetInternal(request, userId);
  }

  /** 파이프라인 러너 전용 — 스텝의 러너 소유 TEMP 데이터셋을 만든다(TempDatasetService 만 호출). 예약 필드가 정확히 채워졌는지만 확인한다. */
  @Transactional
  public DatasetDetailResponse createPipelineTempDataset(
      CreateDatasetRequest request, Long userId) {
    if (!"TEMP".equals(request.originType()) || request.sourcePipelineStepId() == null) {
      throw new IllegalArgumentException("파이프라인 TEMP 데이터셋은 originType=TEMP 와 스텝 id 가 필요합니다");
    }
    return createDatasetInternal(request, userId);
  }

  /**
   * 러너 TEMP 재생성의 되돌리기 재료(WD-238) — 옛 테이블을 옮겨 둔 보관본 이름, 재생성 전 컬럼 메타 행 전체, 재생성 전 보안 등급·자동 상향
   * 시각(WD-302). 스텝이 성공하면 {@link #discardPipelineTempBackup}, 실패하면 {@link #restorePipelineTempTable}
   * 에 넘긴다.
   */
  public record PipelineTempRebuild(
      long datasetId,
      String tableName,
      String backupTableName,
      long backupTableOid,
      List<org.jooq.Record> columnSnapshot,
      Long previousLevelId,
      java.time.LocalDateTime previousAutoRaisedAt) {

    /** 자동 상향 시각이 없던 재생성 — WD-302 이전 호출 형태(테스트 등)를 그대로 둔다. */
    public PipelineTempRebuild(
        long datasetId,
        String tableName,
        String backupTableName,
        long backupTableOid,
        List<org.jooq.Record> columnSnapshot,
        Long previousLevelId) {
      this(
          datasetId,
          tableName,
          backupTableName,
          backupTableOid,
          columnSnapshot,
          previousLevelId,
          null);
    }
  }

  /**
   * 파이프라인 러너 전용 — 스텝 소유 TEMP 의 물리 테이블과 컬럼을 새 열 구성으로 <b>같은 데이터셋 id 그대로</b> 다시 만든다(WD-222).
   *
   * <p>왜 삭제 후 새로 만들지 않나: (1) 화면 저장으로 {@code pipeline_step.output_dataset_id} 에 TEMP id 가 고정된 스텝은
   * {@link #deleteDataset} 의 출력 참조 거부에 걸려 재생성이 실패하고, (2) id 가 바뀌면 다른 스텝 입력({@code
   * pipeline_step_input}, CASCADE)이 조용히 사라지고 열린 편집기가 없는 id 를 저장하며, (3) 관리자가 정한 보안 등급 등 데이터셋 메타가
   * 날아간다. 데이터셋 행(이름·등급·출처)은 건드리지 않고, 생성 경로({@link #createDatasetInternal})와 같은 순서로 컬럼 저장 → 테이블 생성만
   * 다시 한다. 새 테이블은 비어 있다 — 러너는 이 TEMP 를 "새로 만든 TEMP" 로 취급한다.
   *
   * <p><b>옛 테이블은 지우지 않고 보관한다(WD-238).</b> 재생성은 이 트랜잭션에서 커밋되고 본 실행(SQL·PYTHON·AI 분류·API 수집)은 다른 연결에서
   * 돌아 한 트랜잭션으로 묶을 수 없다. 예전에는 여기서 옛 테이블을 DROP 해, 본 실행이 실패하면(0 나누기 등) 이전 결과 행이 모두 사라지고 열 메타만 실패한 SQL
   * 의 것으로 바뀐 빈 테이블이 남았다. 이제 옛 테이블을 보관본 이름으로 옮기고({@link DataTableService#moveToRebuildBackup}) 컬럼 메타
   * 행·등급을 돌려준다 — 러너가 스텝 결과에 따라 보관본을 버리거나 되돌린다. 행 검색 색인도 성공 때({@link #discardPipelineTempBackup})
   * 지운다 — 되돌리면 같은 OID·같은 행의 테이블이 돌아와 색인이 그대로 맞는다.
   *
   * @param datasetId 러너 소유 TEMP 데이터셋 id(origin_type=TEMP, source_pipeline_step_id 있음)
   * @param columns 새 사용자 컬럼(러너가 SELECT 결과에서 만든 것)
   * @return 되돌리기 재료
   */
  @Transactional
  public PipelineTempRebuild rebuildPipelineTempTable(
      Long datasetId, List<DatasetColumnRequest> columns) {
    DatasetResponse dataset = requirePipelineTemp(datasetId);
    // 검증은 테이블을 옮기기보다 먼저다 — 실패할 컬럼 구성으로 옛 테이블을 건드리지 않는다.
    dataTableService.validateNewColumns(columns);
    Set<String> seenColumnNames = new java.util.HashSet<>();
    for (DatasetColumnRequest col : columns) {
      if (!seenColumnNames.add(col.columnName())) {
        throw duplicateColumnName(col.columnName());
      }
    }
    // 되돌리기 재료는 무엇이든 바꾸기 전에 읽는다.
    List<org.jooq.Record> columnSnapshot = columnRepository.snapshotByDatasetId(datasetId);
    Long previousLevelId = datasetSecurityService.currentLevelId(datasetId);
    java.time.LocalDateTime previousAutoRaisedAt =
        datasetSecurityService.currentAutoRaisedAt(datasetId);
    // 지문은 바꾸기 전 스냅숏으로 만든다 — 재생성이 커밋되면 컬럼 행 id 가 바뀌어 크래시 뒤 맞춤이 「커밋됨」을 가린다(WD-256).
    long backupTableOid =
        dataTableService.moveToRebuildBackup(
            dataset.tableName(), columnMetaFingerprint(columnSnapshot));
    String backupTableName = DataTableService.rebuildBackupTableName(dataset.tableName());
    columnRepository.deleteByDatasetId(datasetId);
    withColumnNameUniqueAs409(() -> columnRepository.saveBatch(datasetId, columns));
    dataTableService.createTable(dataset.tableName(), columns);
    // 새로 만든 빈 TEMP 에는 이전 실행의 자동 상향 표시를 남기지 않는다(WD-302). 남기면 이번 실행이 등급을 낮추거나 그대로 두어도 보안 탭이
    // 옛 시각으로 「자동 상향되었습니다」를 안내한다. 이번 실행에서 실제로 상향되면 게이트가 다시 찍는다. 실패해 되돌리면 아래 복원이 되살린다.
    datasetSecurityService.clearAutoRaisedMarker(datasetId);
    // 컬럼이 바뀌었으므로 검색 원문·임베딩도 갱신한다(생성 경로와 같은 규칙).
    reindexSearch(datasetId);
    return new PipelineTempRebuild(
        datasetId,
        dataset.tableName(),
        backupTableName,
        backupTableOid,
        columnSnapshot,
        previousLevelId,
        previousAutoRaisedAt);
  }

  /**
   * 재생성한 TEMP 의 스텝이 실패·취소됐을 때 되돌린다(WD-238) — 새 테이블을 지우고 보관본을 원래 이름으로, 컬럼 메타를 재생성 전 행 그대로, 등급을 재생성 전
   * 이상으로 되돌린다. 한 트랜잭션이다(테이블 되돌리기와 메타 되돌리기가 어긋나지 않게). 보관본이 없으면 아무것도 바꾸지 않고 예외를 던진다.
   *
   * @param runAsUserId 등급 복원 감사의 실행 주체
   */
  @Transactional
  public void restorePipelineTempTable(PipelineTempRebuild rebuild, long runAsUserId) {
    requirePipelineTemp(rebuild.datasetId());
    // 저장소 되돌리기를 메타 쓰기보다 먼저 부른다(리뷰 C1). PG 는 DDL 도 이 트랜잭션이라 함께 커밋·롤백된다. Tibero 는 여기서 보관본(OID)만 확인해
    // 없거나 남의 것이면 메타를 쓰기 전에 실패하고, 물리 복원은 아래 메타 복구가 <b>커밋된 뒤</b>로 미룬다 — 그래서 커밋 뒤 어떤 실패·크래시도 「메타=옛 열,
    // 보관본=옛 지문」을 남겨 다음 맞춤이 복원으로 수렴한다(TiberoDataTableStore#restoreRebuildBackup).
    dataTableService.restoreRebuildBackup(
        rebuild.tableName(), rebuild.backupTableName(), rebuild.backupTableOid());
    columnRepository.restoreSnapshot(rebuild.datasetId(), rebuild.columnSnapshot());
    datasetSecurityService.restoreRebuiltPipelineTempLevel(
        rebuild.datasetId(), rebuild.previousLevelId(), runAsUserId);
    datasetSecurityService.restoreAutoRaisedMarker(
        rebuild.datasetId(), rebuild.previousAutoRaisedAt());
    // 컬럼이 되돌아갔으므로 검색 원문·임베딩도 다시 맞춘다.
    reindexSearch(rebuild.datasetId());
  }

  /**
   * 재생성한 TEMP 의 스텝이 성공한 뒤 보관본을 버린다(WD-238). 옛 열 기준 행 검색 색인도 함께 지운다(재생성 전에는 재생성 때 지웠다 — 되돌릴 수 있도록 성공
   * 시점으로 미뤘다). 색인 테이블은 FK 가 없어 따로 지운다.
   *
   * @return 이 실행의 보관본을 지웠는지
   */
  @Transactional
  public boolean discardPipelineTempBackup(PipelineTempRebuild rebuild) {
    requirePipelineTemp(rebuild.datasetId());
    boolean existed =
        dataTableService.dropRebuildBackup(
            rebuild.tableName(), rebuild.backupTableName(), rebuild.backupTableOid());
    // 이 실행의 보관본이 아니면(겹친 다른 실행이 이미 지우고 자기 것을 둠) 색인·보관본 모두 그 실행에 맡긴다.
    if (existed) {
      rowSearchIndex.drop(
          new IndexRef(TenantContext.require(), rebuild.datasetId(), rebuild.tableName()));
    }
    return existed;
  }

  /**
   * 크래시로 남은 러너 TEMP 재생성 보관본을 맞춘다(WD-256) — 러너가 기존 TEMP 를 쓰기 전에 부른다. 현재 컬럼 메타 지문으로 저장소에 맡긴다: 보관본 지문과
   * 같으면(재생성 트랜잭션이 롤백돼 메타가 옛 열) 복원, 다르면(재생성이 커밋됨) 보관본 삭제. 복원했으면 컬럼 구성이 메타와 다시 맞으므로 검색 원문을 다시 맞춘다. PG
   * 는 저장소가 아무것도 하지 않는다({@code NONE}).
   *
   * <p>트랜잭션인 이유: 러너는 트랜잭션 밖에서 돌아 RLS 문맥이 없다 — 데이터셋·컬럼 메타 읽기가 이 트랜잭션 안에서 테넌트 문맥을 얻는다.
   */
  @Transactional
  public RebuildBackupState reconcilePipelineTempTable(Long datasetId) {
    DatasetResponse dataset = requirePipelineTemp(datasetId);
    String fingerprint = columnMetaFingerprint(columnRepository.snapshotByDatasetId(datasetId));
    RebuildBackupState state =
        dataTableService.reconcileRebuildBackup(dataset.tableName(), fingerprint);
    if (state == RebuildBackupState.RESTORED) {
      log.warn(
          "TEMP 데이터셋 {} 의 재생성 보관본을 원래 테이블로 되돌렸습니다(이전 실행이 마무리 전에 멈춘 흔적): {}",
          datasetId,
          dataset.tableName());
      reindexSearch(datasetId);
    } else if (state == RebuildBackupState.DROPPED_LEFTOVER) {
      log.warn(
          "TEMP 데이터셋 {} 의 남은 재생성 보관본을 지웠습니다(재생성은 커밋됐으나 마무리 전에 멈춘 흔적): {}",
          datasetId,
          dataset.tableName());
    }
    return state;
  }

  /**
   * 컬럼 메타 지문(WD-256) — {@code dataset_column} 행의 (id, column_name, data_type) 을 <b>id 순</b>으로 이은
   * 문자열의 SHA-256 앞 32자(소문자 hex). 재생성은 컬럼 행을 지우고 새로 넣어 id 가 바뀌므로, 재생성이 커밋되면 물리 열이 같아도(INTEGER·BIGINT
   * 는 Tibero 에서 둘 다 {@code NUMBER(19)}) 지문이 반드시 달라진다. 되돌리기({@code restoreSnapshot})는 id 까지 그대로 넣어
   * 같은 지문으로 돌아간다.
   *
   * <p>구분: 필드 사이 U+001F(단위 구분자), 행 끝 U+001E(레코드 구분자). 컬럼 이름은 식별자 규칙상 제어 문자를 담을 수 없어 경계가 모호하지 않다. 입력
   * 순서(스냅숏은 column_order 순)와 무관하게 id 로 정렬한다. data_type 이 null 이면 문자열 {@code null} 로 둔다.
   */
  public static String columnMetaFingerprint(List<Record> columnSnapshot) {
    List<Record> sorted = new ArrayList<>(columnSnapshot);
    sorted.sort(Comparator.comparingLong(r -> ((Number) r.get("id")).longValue()));
    StringBuilder text = new StringBuilder();
    for (Record r : sorted) {
      text.append(r.get("id"))
          .append('\u001F')
          .append(r.get("column_name"))
          .append('\u001F')
          .append(r.get("data_type"))
          .append('\u001E');
    }
    // SearchConfig.sha256 은 UTF-8 SHA-256 소문자 hex — 앞 32자만 지문으로 쓴다.
    return SearchConfig.sha256(text.toString()).substring(0, 32);
  }

  /** 러너 소유 TEMP 만 다시 만들거나 되돌릴 수 있다 — 사용자 데이터셋의 테이블을 옮기거나 지우는 경로가 되지 않게 막는다. */
  private DatasetResponse requirePipelineTemp(Long datasetId) {
    DatasetResponse dataset =
        datasetRepository.findById(datasetId).orElseThrow(() -> new DatasetNotFoundException());
    if (!"TEMP".equals(dataset.originType()) || dataset.sourcePipelineStepId() == null) {
      throw new IllegalArgumentException("파이프라인 TEMP 데이터셋만 다시 만들 수 있습니다: " + datasetId);
    }
    return dataset;
  }

  /** 생성 본체 — 출처 필드 검사는 두 공개 진입점이 맡는다. */
  private DatasetDetailResponse createDatasetInternal(CreateDatasetRequest request, Long userId) {
    // 새 이름 검증은 저장 유형 분기·중복 검사·카탈로그 저장보다 먼저다 — 상한을 넘는 이름은 원래 문자열 비교인 중복 검사를 통과해
    // 버리고, 카탈로그(VARCHAR(100))에 먼저 닿으면 400 이 아니라 제약 오류가 된다. 근거는
    // DataTableService.MAX_TABLE_NAME_LENGTH.
    dataTableService.validateNewTableName(request.tableName());

    // DOCUMENT/FILE 데이터셋은 컬럼 정의가 없어 columns 가 null 로 들어올 수 있다(로봇 등 프로그래매틱
    // API 호출 시). 아래 검증에서 NPE 가 나지 않도록 null 을 빈 목록으로 정규화한다.
    List<DatasetColumnRequest> columns = request.columns() != null ? request.columns() : List.of();
    dataTableService.validateNewColumns(columns);

    // 요청 안의 필드명 중복은 카탈로그 저장 전에 거부한다(WD-99) — 그대로 두면 saveBatch 가 유니크 인덱스에 걸려 어느 필드인지 없는
    // 일반 무결성 오류가 된다. 이름 규칙(소문자만 허용)이 대소문자 변형을 막으므로 문자열 그대로 비교한다.
    Set<String> seenColumnNames = new java.util.HashSet<>();
    for (DatasetColumnRequest col : columns) {
      if (!seenColumnNames.add(col.columnName())) {
        throw duplicateColumnName(col.columnName());
      }
    }

    // Validate PK columns: must be NOT NULL, and GEOMETRY type cannot be a primary key (#199)
    for (DatasetColumnRequest col : columns) {
      if (col.isPrimaryKey() && col.isNullable()) {
        throw primaryKeyNullable(col.columnName());
      }
      // GEOMETRY 컬럼은 GiST 인덱스만 지원하며 B-tree UNIQUE INDEX 생성 불가 → PK 거부
      if (col.isPrimaryKey() && "GEOMETRY".equals(col.dataType())) {
        throw new ColumnModificationException(
            "지오메트리(GEOMETRY) 필드는 기본 키로 지정할 수 없습니다: " + col.columnName());
      }
    }

    // FILE 프리픽스는 서버 생성 전용이다 — 지정하면 다른(숨김·타 테넌트) 데이터셋의 객체 경로를 덮을 수 있다(serverFilePrefix).
    // 빈 값은 "지정 안 함"으로 본다(웹·기존 클라이언트가 빈 입력을 생략하거나 빈 문자열로 보낸다). 저장 전에 거부해 부분 생성이 없게 한다.
    if (request.prefix() != null && !request.prefix().isBlank()) {
      throw new CodedApiException(
          HttpStatus.BAD_REQUEST,
          FILE_PREFIX_NOT_ALLOWED_CODE,
          "파일 데이터셋의 저장 경로(prefix)는 지정할 수 없습니다. 서버가 데이터셋마다 자동으로 만듭니다.");
    }

    requireUnusedNameAndTableName(request.name(), request.tableName());

    if (request.categoryId() != null) {
      categoryRepository
          .findById(request.categoryId())
          .orElseThrow(() -> new CategoryNotFoundException());
    }

    DatasetResponse dataset = datasetRepository.save(request, userId);
    // DOCUMENT/FILE 데이터셋은 data.<table> 동적 테이블이 없으므로(문서는 document_chunk, 파일은 MinIO 에 저장)
    // dataset.table_name 은 메타데이터 식별자로만 쓰이며, 컬럼 영속화/물리 테이블 생성을 건너뛴다.
    if (isTableBacked(request.storageType())) {
      // TABLE 데이터셋만 동적 data.<table> 물리 테이블과 컬럼을 만든다.
      withColumnNameUniqueAs409(() -> columnRepository.saveBatch(dataset.id(), request.columns()));
      dataTableService.createTable(request.tableName(), request.columns());
    } else if (FILE_TYPE.equals(request.storageType())) {
      // FILE 데이터셋은 MinIO 버킷/프리픽스 매핑만 저장한다(개별 파일 행 없음).
      String bucket =
          request.bucket() != null && !request.bucket().isBlank()
              ? request.bucket()
              : fileObjectStorageService.defaultBucket();
      // 프리픽스는 서버가 데이터셋 id 로 만든다(클라이언트 지정은 위에서 400 으로 거부됨 — serverFilePrefix 참고).
      fileDatasetConfigRepository.save(dataset.id(), bucket, serverFilePrefix(dataset.id()));
    }

    // 데이터셋 생성 감사 로그 (#60/#92)
    userRepository
        .findById(userId)
        .ifPresent(
            u ->
                auditLogService.log(
                    userId,
                    u.username(),
                    "CREATE",
                    "dataset",
                    String.valueOf(dataset.id()),
                    "데이터셋 생성: " + dataset.name(),
                    null,
                    null,
                    "SUCCESS",
                    null,
                    null));

    // 메타·컬럼이 모두 영속화된 뒤 검색 인덱스 갱신
    reindexSearch(dataset.id());

    return getDatasetById(dataset.id());
  }

  /**
   * 데이터셋 메타(이름/설명/컬럼/태그/카테고리)가 영속화된 직후 검색 인덱스를 갱신한다.
   *
   * <ul>
   *   <li>source_text: 같은 쓰기 트랜잭션에서 동기 저장 → 키워드 검색 즉시 노출.
   *   <li>embedding: 커밋 완료 후 비동기 재생성(외부 호출 동반) — 쓰기 트랜잭션/요청 스레드를 막지 않음.
   * </ul>
   *
   * <p>반드시 컬럼·태그까지 INSERT 된 뒤 호출해야 한다(metaReader 가 방금 쓴 데이터를 같은 트랜잭션에서 읽음).
   */
  private void reindexSearch(long datasetId) {
    datasetEmbeddingService.syncSourceText(datasetId); // 동기: 같은 트랜잭션, 키워드 검색 즉시 노출
    events.publishEvent(new DatasetChangedEvent(datasetId)); // 비동기: 커밋 후 임베딩
  }

  /** 물리 테이블이 없는(DOCUMENT/FILE) 데이터셋의 컬럼·행 조작을 거부한다. */
  private void rejectIfDocument(String storageType, String operation) {
    if (DOCUMENT_TYPE.equals(storageType) || FILE_TYPE.equals(storageType)) {
      throw new IllegalArgumentException(storageType + " 데이터셋은 " + operation + " 작업을 지원하지 않습니다");
    }
  }

  @Transactional(readOnly = true)
  public PageResponse<DatasetResponse> getDatasets(
      Long categoryId,
      String storageType,
      String originType,
      String search,
      int page,
      int size,
      Long currentUserId,
      String status,
      boolean favoriteOnly,
      Long securityLevelId) {
    // 목록은 항상 현재 사용자 가시성 조건과 함께 조회한다(스펙 §4.2 1행).
    Condition access = datasetAccessGuard.visibleCondition();
    List<DatasetResponse> content =
        datasetRepository.findAll(
            categoryId,
            storageType,
            originType,
            search,
            page,
            size,
            currentUserId,
            status,
            favoriteOnly,
            securityLevelId,
            access);
    long totalElements =
        datasetRepository.count(
            categoryId,
            storageType,
            originType,
            search,
            currentUserId,
            status,
            favoriteOnly,
            securityLevelId,
            access);
    int totalPages = (int) Math.ceil((double) totalElements / size);
    // 조회자별 내보내기 가능 여부(스펙 §4.4) — 리포지토리 행은 조회자를 모르므로 여기서 채운다(자격은 요청 단위 캐시).
    content =
        content.stream()
            .map(
                r ->
                    r.withExportAllowed(
                        datasetAccessGuard.exportAllowedForCurrent(r.securityLevel())))
            .toList();
    return new PageResponse<>(content, page, size, totalElements, totalPages);
  }

  @Transactional(readOnly = true)
  public DatasetDetailResponse getDatasetById(Long id) {
    return getDatasetById(id, null);
  }

  @Transactional(readOnly = true)
  public DatasetDetailResponse getDatasetById(Long id, Long currentUserId) {
    DatasetResponse dataset =
        datasetRepository
            .findById(id, currentUserId)
            .orElseThrow(() -> new DatasetNotFoundException());

    List<DatasetColumnResponse> columns = columnRepository.findByDatasetId(id);
    // DOCUMENT/FILE 데이터셋은 data.<table> 동적 테이블이 없으므로 countRows(존재하지 않는 테이블 COUNT) 가 실패한다.
    // 문서 행 수는 document_chunk 기준, 파일 개수는 MinIO 오브젝트 목록 기준이며 Task 4 범위 밖이므로 0 으로 반환한다.
    long rowCount =
        (DOCUMENT_TYPE.equals(dataset.storageType()) || FILE_TYPE.equals(dataset.storageType()))
            ? 0L
            : dataTableRowService.countRows(dataset.tableName());

    String createdByUsername =
        datasetRepository
            .findCreatedByById(id)
            .flatMap(userRepository::findById)
            .map(user -> user.name())
            .orElse("unknown");

    var updatedAt = datasetRepository.findUpdatedAtById(id).orElse(null);

    String updatedByUsername =
        datasetRepository
            .findUpdatedByById(id)
            .flatMap(userRepository::findById)
            .map(user -> user.name())
            .orElse(null);

    List<DatasetDetailResponse.LinkedPipelineInfo> linkedPipelines =
        dsl.selectDistinct(
                field(name("pipeline", "id"), Long.class),
                field(name("pipeline", "name"), String.class),
                field(name("pipeline", "is_active"), Boolean.class))
            .from(table(name("pipeline_step")))
            .join(table(name("pipeline")))
            .on(
                field(name("pipeline_step", "pipeline_id"), Long.class)
                    .eq(field(name("pipeline", "id"), Long.class)))
            .where(field(name("pipeline_step", "output_dataset_id"), Long.class).eq(id))
            .fetch(
                r ->
                    new DatasetDetailResponse.LinkedPipelineInfo(
                        r.get(field(name("pipeline", "id"), Long.class)),
                        r.get(field(name("pipeline", "name"), String.class)),
                        Boolean.TRUE.equals(
                            r.get(field(name("pipeline", "is_active"), Boolean.class)))));

    return new DatasetDetailResponse(
        dataset.id(),
        dataset.name(),
        dataset.tableName(),
        dataset.description(),
        dataset.category(),
        dataset.storageType(),
        dataset.originType(),
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
        dataset.statusUpdatedAt(),
        linkedPipelines,
        dataset.sourcePipelineStepId(),
        dataset.securityLevel(),
        dataset.securityLevelAutoRaisedAt(),
        // 조회자(요청 사용자) 기준 — 요청 밖 호출은 자격 없음(fail-closed)으로 false.
        datasetAccessGuard.exportAllowedForCurrent(dataset.securityLevel()));
  }

  /** 볼 수 있는 데이터셋과 이름이 겹칠 때의 문구 — 조회자가 이미 보는 데이터셋이라 이름을 실어도 된다. */
  static final String DUPLICATE_NAME_MESSAGE = "이미 있는 데이터셋 이름입니다: ";

  /**
   * 테이블명 충돌 문구(WD-155). 테이블명은 data 스키마의 물리 테이블 이름이라 워크스페이스 전체에서 유일해야 하고, 볼 수 없는 데이터셋과 겹쳐도 막을 수밖에
   * 없다. 그래서 보이는 데이터셋과 겹친 경우와 같은 문구로, 받은 값도 싣지 않는다(「알려진 한계」 — deploy.md).
   */
  static final String TABLE_NAME_UNAVAILABLE_MESSAGE = "이 테이블명은 사용할 수 없습니다 — 다른 이름을 입력하세요";

  /**
   * 생성·복제 공통 중복 검사(WD-155). 이름은 조회자가 볼 수 있는 데이터셋 안에서만 겹침을 본다 — 숨긴 데이터셋과 같은 이름이면 그대로 만들어진다(DB 유일 제약
   * 없음, V142). 테이블명은 물리 제약이라 전체에서 보되 문구를 일반화한다.
   */
  private void requireUnusedNameAndTableName(String name, String tableName) {
    if (datasetRepository.existsVisibleByName(name, datasetAccessGuard.visibleCondition(), null)) {
      throw new DuplicateDatasetNameException(DUPLICATE_NAME_MESSAGE + name);
    }
    if (datasetRepository.existsByTableName(tableName)) {
      throw new DuplicateDatasetNameException(TABLE_NAME_UNAVAILABLE_MESSAGE);
    }
  }

  @Transactional
  public void updateDataset(Long id, UpdateDatasetRequest request, Long userId) {
    DatasetResponse current =
        datasetRepository.findById(id).orElseThrow(() -> new DatasetNotFoundException());

    // 이름을 바꿀 때도 볼 수 있는 데이터셋끼리만 겹침을 본다(WD-155) — 예전에는 DB 유일 제약이 걸려, 숨긴 데이터셋 이름으로
    // 바꾸면 409·없는 이름이면 200 으로 갈려 존재가 드러났다.
    if (request.name() != null
        && !request.name().equals(current.name())
        && datasetRepository.existsVisibleByName(
            request.name(), datasetAccessGuard.visibleCondition(), id)) {
      throw new DuplicateDatasetNameException(DUPLICATE_NAME_MESSAGE + request.name());
    }

    if (request.categoryId() != null) {
      categoryRepository
          .findById(request.categoryId())
          .orElseThrow(() -> new CategoryNotFoundException());
    }

    datasetRepository.update(id, request, userId);

    // 이름/설명/카테고리 변경 반영
    reindexSearch(id);

    // 감사(WD-463) — 검증·저장이 모두 끝난 뒤 같은 트랜잭션에서 남긴다.
    auditDatasetUpdate(userId, current);
  }

  /** 데이터셋 정보 수정 감사의 바뀐 항목 키 → 설명용 라벨(WD-463). */
  private static final Map<String, String> DATASET_FIELD_LABELS =
      Map.of("name", "이름", "description", "설명", "category", "카테고리");

  /**
   * 데이터셋 정보 수정 감사(WD-463). 저장 전 값과 저장 후 다시 읽은 값을 비교해 바뀐 항목 <b>이름</b>만 남긴다 — 요청이 아니라 저장값끼리 비교하는 이유:
   * 수정은 전체 교체라 웹이 바뀌지 않은 칸도 함께 보내므로, 요청과 비교하면 그대로인 항목까지 바뀐 것으로 센다. 설명은 null 과 빈 문자열을 같게 본다(화면에서는 둘
   * 다 「설명 없음」). 바뀐 것이 없으면 남기지 않는다. 설명 본문은 싣지 않는다.
   */
  private void auditDatasetUpdate(Long userId, DatasetResponse before) {
    DatasetResponse after =
        datasetRepository.findById(before.id()).orElseThrow(DatasetNotFoundException::new);
    List<String> changed = new ArrayList<>();
    if (!Objects.equals(before.name(), after.name())) changed.add("name");
    if (!Objects.equals(blankToNull(before.description()), blankToNull(after.description()))) {
      changed.add("description");
    }
    if (!Objects.equals(categoryIdOf(before), categoryIdOf(after))) changed.add("category");
    if (changed.isEmpty()) {
      return; // 실제 변경 없음 — 감사 생략
    }
    datasetAuditRecorder.record(
        userId,
        "UPDATE",
        after.id(),
        after.name(),
        "DATASET_UPDATE",
        "데이터셋 수정: "
            + after.name()
            + " (바뀐 항목: "
            + DatasetAuditRecorder.changedLabels(changed, DATASET_FIELD_LABELS)
            + ")",
        Map.of("changedFields", changed));
  }

  private static String blankToNull(String value) {
    return value == null || value.isEmpty() ? null : value;
  }

  private static Long categoryIdOf(DatasetResponse dataset) {
    return dataset.category() == null ? null : dataset.category().id();
  }

  @Transactional
  public void deleteDataset(Long id) {
    DatasetResponse dataset =
        datasetRepository.findById(id).orElseThrow(() -> new DatasetNotFoundException());

    // 파이프라인 스텝 출력 데이터셋으로 참조 중인 경우 삭제 불가 (#126)
    // FK 위반 500 오류를 방지하기 위해 삭제 전 참조 건수를 확인한다.
    int referencingStepCount =
        dsl.fetchCount(
            table(name("pipeline_step")), field(name("output_dataset_id"), Long.class).eq(id));
    if (referencingStepCount > 0) {
      throw new DatasetInUseException(
          referencingStepCount + "개 파이프라인 스텝이 이 데이터셋을 출력으로 참조하고 있어 삭제할 수 없습니다.");
    }

    // DOCUMENT/FILE 데이터셋은 생성 시 동적 테이블을 만든 적이 없으므로 DROP 을 시도하지 않는다.
    // (오늘은 DROP TABLE IF EXISTS 라 FILE 을 걸러도 무해하지만, 생성/삭제 로직의 대칭성을 위해
    // isTableBacked 로 통일한다.) document_file/document_chunk 행, file_dataset_config 행은
    // dataset 삭제 시 FK CASCADE 로 함께 제거된다.
    if (isTableBacked(dataset.storageType())) {
      // 색인 테이블은 FK 가 없어 CASCADE 로 사라지지 않는다 — 원본과 함께 지운다.
      rowSearchIndex.drop(new IndexRef(TenantContext.require(), id, dataset.tableName()));
      dataTableService.dropTable(dataset.tableName());
      // 러너 TEMP 는 재생성 도중 서버가 멈춰 남은 보관본이 있을 수 있다(WD-238) — 데이터셋과 함께 지워 고아로 남기지 않는다.
      if ("TEMP".equals(dataset.originType())) {
        dataTableService.dropRebuildBackup(
            dataset.tableName(),
            DataTableService.rebuildBackupTableName(dataset.tableName()),
            null);
      }
    }
    columnRepository.deleteByDatasetId(id);
    datasetRepository.deleteById(id);
    // 데이터 변경 트리거는 FK 가 아니라 config.datasetIds(JSONB)로 감시하므로 삭제가 전파되지 않는다(WD-221). 남겨 두면 폴러가 매
    // 주기 「행 수 0 ≠ 직전값」으로 보고 삭제된 데이터셋으로 파이프라인을 계속 실행했다 — 같은 트랜잭션에서 감시 목록·스냅숏에서 뺀다.
    triggerRepository.removeWatchedDataset(id);

    // 데이터셋 삭제 감사 로그 (#60/#92)
    var auth =
        org.springframework.security.core.context.SecurityContextHolder.getContext()
            .getAuthentication();
    if (auth != null && auth.getPrincipal() instanceof Long userId) {
      userRepository
          .findById(userId)
          .ifPresent(
              u ->
                  auditLogService.log(
                      userId,
                      u.username(),
                      "DELETE",
                      "dataset",
                      String.valueOf(id),
                      "데이터셋 삭제: " + dataset.name(),
                      null,
                      null,
                      "SUCCESS",
                      null,
                      null));
    }
  }

  /** 행위자 없는 내부·테스트 경로 — 감사는 행위자 "system" 으로 남는다. 사용자 요청은 userId 를 받는 오버로드로 들어온다. */
  @Transactional
  public DatasetColumnResponse addColumn(Long datasetId, AddColumnRequest request) {
    return addColumn(datasetId, request, null);
  }

  /** 필드를 추가하고, 성공하면 감사(WD-463, FIELD_ADD)를 남긴다. */
  @Transactional
  public DatasetColumnResponse addColumn(Long datasetId, AddColumnRequest request, Long userId) {
    DatasetResponse dataset =
        datasetRepository.findById(datasetId).orElseThrow(() -> new DatasetNotFoundException());
    rejectIfDocument(dataset.storageType(), "컬럼 관리");

    DatasetColumnRequest colRequest =
        new DatasetColumnRequest(
            request.columnName(),
            request.displayName(),
            request.dataType(),
            request.maxLength(),
            request.isNullable(),
            request.isIndexed(),
            request.description(),
            request.isPrimaryKey());
    // 카탈로그 저장 전에 새 컬럼(예약어·이름 길이·타입)을 검증한다 — 생성 경로와 같은 이유.
    dataTableService.validateNewColumns(List.of(colRequest));

    // 같은 이름 필드가 이미 있으면 카탈로그 저장·DDL 전에 거부한다(WD-99) — 그대로 두면 columnRepository.save 가 유니크 인덱스에
    // 걸려 「어느 필드인지」 없는 일반 무결성 오류가 된다. 목록은 한 번만 읽어 아래 PK 인덱스 재구성에도 쓴다.
    List<DatasetColumnResponse> existingColumns = columnRepository.findByDatasetId(datasetId);
    boolean nameTaken =
        existingColumns.stream().anyMatch(c -> c.columnName().equals(request.columnName()));
    if (nameTaken) {
      throw duplicateColumnName(request.columnName());
    }

    long rowCount = dataTableRowService.countRows(dataset.tableName());
    if (rowCount > 0 && !request.isNullable()) {
      throw new ColumnModificationException(
          "데이터가 있는 데이터셋에는 NULL 허용을 끈 필드를 추가할 수 없습니다. NULL 허용으로 추가한 뒤 값을 채우고 바꾸세요.");
    }

    // PK validation
    if (request.isPrimaryKey() && rowCount > 0) {
      throw new ColumnModificationException(
          "데이터가 있는 데이터셋에는 기본 키 필드를 추가할 수 없습니다. 필드를 먼저 추가해 값을 채운 뒤 기본 키로 지정하세요.");
    }
    if (request.isPrimaryKey() && request.isNullable()) {
      throw primaryKeyNullable(request.columnName());
    }

    int nextOrder = columnRepository.getMaxOrder(datasetId) + 1;

    DatasetColumnResponse column =
        withColumnNameUniqueAs409(() -> columnRepository.save(datasetId, colRequest, nextOrder));
    dataTableService.addColumn(dataset.tableName(), colRequest);

    if (request.isPrimaryKey()) {
      // 기존 PK 필드(컬럼 순서대로) 뒤에 방금 추가한 필드를 붙인다 — 새 필드는 최대 순서 + 1 이라 다시 읽은 결과와 순서가 같다.
      List<String> pkColumnNames = new ArrayList<>();
      for (DatasetColumnResponse c : existingColumns) {
        if (c.isPrimaryKey()) {
          pkColumnNames.add(c.columnName());
        }
      }
      pkColumnNames.add(column.columnName());
      dataTableService.recreatePrimaryKeyIndex(dataset.tableName(), pkColumnNames);
    }

    // 컬럼 추가 반영
    reindexSearch(datasetId);

    // 감사(WD-463) — 검증·DDL 이 모두 끝난 뒤. 필드 이름만 싣는다(표시 이름·설명 본문은 싣지 않음).
    datasetAuditRecorder.record(
        userId,
        "UPDATE",
        datasetId,
        dataset.name(),
        "FIELD_ADD",
        "필드 추가: " + dataset.name() + "." + column.columnName(),
        Map.of("columnId", column.id(), "columnName", column.columnName()));

    return column;
  }

  /** 행위자 없는 내부·테스트 경로 — 감사는 행위자 "system" 으로 남는다. 사용자 요청은 userId 를 받는 오버로드로 들어온다. */
  @Transactional
  public void updateColumn(Long datasetId, Long columnId, UpdateColumnRequest request) {
    updateColumn(datasetId, columnId, request, null);
  }

  /** 필드를 수정하고, 실제로 바뀐 항목이 있으면 감사(WD-463, FIELD_UPDATE)를 남긴다. */
  @Transactional
  public void updateColumn(
      Long datasetId, Long columnId, UpdateColumnRequest request, Long userId) {
    DatasetResponse dataset =
        datasetRepository.findById(datasetId).orElseThrow(() -> new DatasetNotFoundException());
    rejectIfDocument(dataset.storageType(), "컬럼 관리");

    // WD-186: 이름·타입을 바꿀 수 있는 요청이면 필드를 읽기 전에 매핑 저장과 직렬화하는 잠금을 잡는다 — 아래에서 읽는 현재 필드
    // 이름이 잠금 뒤의 값이어야 매핑 연쇄 갱신·타입 판정이 어긋나지 않는다. 설명만 고치는 요청(설명 칸 인라인 편집)은 매핑과 무관해
    // 잠그지 않는다.
    if (request.columnName() != null || request.dataType() != null) {
      mappingReferenceService.lockForColumnChange(datasetId);
    }

    DatasetColumnResponse column =
        columnRepository
            .findById(columnId)
            .orElseThrow(() -> new IllegalArgumentException("필드를 찾을 수 없습니다: " + columnId));

    Long columnDatasetId =
        columnRepository
            .findDatasetIdByColumnId(columnId)
            .orElseThrow(() -> new IllegalArgumentException("필드를 찾을 수 없습니다: " + columnId));

    if (!columnDatasetId.equals(datasetId)) {
      throw new IllegalArgumentException("이 데이터셋의 필드가 아닙니다: " + columnId);
    }

    if (request.dataType() != null && !VALID_DATA_TYPES.contains(request.dataType())) {
      throw new ColumnModificationException("알 수 없는 데이터 타입입니다: " + request.dataType());
    }

    // WD-186: 매핑 속성에 연결된 필드의 타입을 그 속성 자료형과 맞지 않게 바꾸면 DDL 전에 409 로 막는다(지식 모델 쪽 WD-185 와
    // 대칭). 매핑은 아직 옛 이름을 들고 있으므로 현재(변경 전) 이름으로 판정한다 — 이름 연쇄 갱신은 아래 이름 변경 DDL 뒤에 일어난다.
    if (request.dataType() != null && !request.dataType().equals(column.dataType())) {
      mappingReferenceService.assertColumnTypeCompatible(
          datasetId, column.columnName(), request.dataType());
    }

    long rowCount = dataTableRowService.countRows(dataset.tableName());

    // 1단계: 던질 수 있는 검증을 모두 끝낸 뒤에 물리 DDL 을 실행한다. PG 는 DDL 도 트랜잭션이라 검증 실패 시 함께 롤백되지만, 외부
    // 저장소(Tibero)의 DDL 은 자동 커밋이라 DDL 뒤에 검증이 실패하면 메타데이터는 그대로인데 물리 컬럼만 바뀐 채 남는다(WD-74).
    // 검증들의 상대 순서는 예전 그대로 두어, 여러 조건이 동시에 걸릴 때 어떤 예외가 나는지도 바뀌지 않게 한다.
    boolean renaming =
        request.columnName() != null && !request.columnName().equals(column.columnName());
    if (renaming) {
      // 다른 필드와 같은 이름으로 바꾸려 하면 DDL(renameIndex·renameColumn) 전에 거부한다(WD-99). 자기 자신은 비교에서
      // 뺀다(excludeColumnId). 목록 전체 대신 EXISTS 한 번으로 묻는다.
      if (columnRepository.existsByDatasetIdAndColumnName(
          datasetId, request.columnName(), columnId)) {
        throw duplicateColumnName(request.columnName());
      }
      if (rowCount > 0) {
        throw new ColumnModificationException("데이터가 있는 데이터셋에서는 필드명을 바꿀 수 없습니다.");
      }
      // 새 이름 검증(예약어·형식·길이 상한)을 DDL 전에 미리 한다. renameIndex·renameColumn 도 같은 검증을 하지만, 그건 DDL 직전이라
      // 그 앞의 다른 검증이 실패하는 경우를 막지 못한다.
      dataTableService.validateNewColumnName(request.columnName());
    }

    boolean typeChanging =
        request.dataType() != null && !request.dataType().equals(column.dataType());
    boolean sizeChanging =
        !typeChanging
            && request.dataType() != null
            && "VARCHAR".equals(request.dataType())
            && request.maxLength() != null
            && !request.maxLength().equals(column.maxLength());
    if (typeChanging) {
      // 검색 대상 필드를 TEXT/VARCHAR 밖 타입으로 바꾸면 색인 텍스트를 만들 수 없다 — 먼저 해제하게 한다(409).
      if (!SearchIndexSettingsService.SEARCHABLE_TYPES.contains(request.dataType())
          && searchColumnRepository.isSearchable(columnId)) {
        throw new IllegalStateException(
            "검색 대상 필드는 TEXT/VARCHAR 이외 타입으로 바꿀 수 없습니다. 검색 탭에서 먼저 해제하세요.");
      }
      if (rowCount > 0) {
        throw new ColumnModificationException("데이터가 있는 데이터셋에서는 필드의 데이터 타입을 바꿀 수 없습니다.");
      }
    } else if (sizeChanging) {
      if (rowCount > 0) {
        throw new ColumnModificationException("데이터가 있는 데이터셋에서는 필드 길이를 바꿀 수 없습니다.");
      }
    }

    // Prevent making PK column nullable
    if (request.isNullable() != null
        && request.isNullable()
        && (request.isPrimaryKey() != null ? request.isPrimaryKey() : column.isPrimaryKey())) {
      throw primaryKeyNullable(column.columnName());
    }

    // Handle primary key changes (검증만 — 인덱스 재생성은 메타 저장 뒤)
    if (request.isPrimaryKey() != null && request.isPrimaryKey() != column.isPrimaryKey()) {
      if (request.isPrimaryKey()) {
        // Setting as PK: validate NOT NULL
        boolean nullable =
            request.isNullable() != null ? request.isNullable() : column.isNullable();
        if (nullable) {
          throw primaryKeyNullable(column.columnName());
        }
        // NULL 검사가 중복 검사보다 먼저여야 한다. 중복 검사(COUNT DISTINCT)는 NULL 을 세지 않아, NULL 만 있는 컬럼이
        // "중복값" 메시지로 둔갑한다. 예전에는 setColumnNullable 이 먼저 실행돼 NOT NULL 메시지가 났으므로 그 동작을 유지한다.
        // 데이터가 있으면 이름 변경은 거부되므로 현재 물리 이름은 column.columnName() 이다.
        if (rowCount > 0
            && request.isNullable() != null
            && !request.isNullable()
            && column.isNullable()
            && dataTableRowService.hasNulls(dataset.tableName(), column.columnName())) {
          throw new ColumnModificationException(
              "NOT NULL 로 변경할 수 없습니다: 컬럼 '" + column.columnName() + "' 에 NULL 값이 존재합니다.");
        }
        // Check data uniqueness if dataset has data.
        // 데이터가 있으면 이름 변경은 위에서 이미 거부됐으므로, 이 검사는 DDL 전이어도 지금 물리 이름 그대로 읽는다.
        if (rowCount > 0) {
          List<DatasetColumnResponse> allColumns = columnRepository.findByDatasetId(datasetId);
          String colName =
              request.columnName() != null ? request.columnName() : column.columnName();
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

          if (!dataTableRowService.checkDataUniqueness(dataset.tableName(), pkColNames)) {
            throw new ColumnModificationException("기본 키를 설정할 수 없습니다. 데이터에 중복값이 존재합니다.");
          }
        }
      }
    }

    // 2단계: 물리 DDL — 이름 → 타입·크기 → NULL 허용 → 인덱스 순(예전과 같은 순서).
    if (renaming) {
      // GEOMETRY 필드는 인덱스를 켜지 않아도 GiST 가 자동으로 붙으므로 함께 이름을 맞춘다(WD-100). 그 밖의 필드는 인덱스가 있을 때만.
      if (column.isIndexed() || "GEOMETRY".equalsIgnoreCase(column.dataType())) {
        dataTableService.renameIndex(
            dataset.tableName(), column.columnName(), request.columnName());
      }
      dataTableService.renameColumn(dataset.tableName(), column.columnName(), request.columnName());
      // WD-186: 이 데이터셋의 매핑(draft·active)이 옛 필드 이름을 가리키면 같은 트랜잭션에서 새 이름으로 바꾼다(상태는 그대로).
      mappingReferenceService.renameColumn(datasetId, column.columnName(), request.columnName());
    }

    if (typeChanging || sizeChanging) {
      String currentColName =
          request.columnName() != null ? request.columnName() : column.columnName();
      dataTableService.alterColumnType(
          dataset.tableName(),
          currentColName,
          request.dataType(),
          request.maxLength(),
          column.dataType());
    }

    if (request.isNullable() != null && request.isNullable() != column.isNullable()) {
      String currentColName =
          request.columnName() != null ? request.columnName() : column.columnName();
      try {
        // 데이터 존재 여부와 무관하게 시도하고, DB 가 거부하면 친화적인 메시지로 변환한다.
        // - NOT NULL → NULL 허용: 항상 안전
        // - NULL 허용 → NOT NULL: 컬럼에 NULL 값이 있으면 Postgres 가 거부 (jOOQ
        // IntegrityConstraintViolationException)
        dataTableService.setColumnNullable(
            dataset.tableName(), currentColName, request.isNullable());
      } catch (IntegrityConstraintViolationException | DataIntegrityViolationException e) {
        if (!request.isNullable()) {
          throw new ColumnModificationException(
              "NOT NULL 로 변경할 수 없습니다: 컬럼 '" + currentColName + "' 에 NULL 값이 존재합니다.");
        }
        throw e;
      }
    }

    if (request.isIndexed() != null && request.isIndexed() != column.isIndexed()) {
      String currentColName =
          request.columnName() != null ? request.columnName() : column.columnName();
      String resolvedType = request.dataType() != null ? request.dataType() : column.dataType();
      dataTableService.setColumnIndex(
          dataset.tableName(), currentColName, request.isIndexed(), resolvedType);
    }

    // 이름 변경이 사전 검사를 지나 카탈로그 유니크 제약에 걸리는 경우도 같은 409 로(WD-99).
    withColumnNameUniqueAs409(() -> columnRepository.update(columnId, request));

    // Recreate PK index if PK flag changed
    if (request.isPrimaryKey() != null && request.isPrimaryKey() != column.isPrimaryKey()) {
      List<DatasetColumnResponse> updatedColumns = columnRepository.findByDatasetId(datasetId);
      List<String> pkColumnNames =
          updatedColumns.stream()
              .filter(DatasetColumnResponse::isPrimaryKey)
              .map(DatasetColumnResponse::columnName)
              .toList();
      if (rowCount > 0 && request.isPrimaryKey()) {
        // Use regular (non-concurrent) index creation here because updateColumn() is
        // @Transactional.
        // CREATE INDEX CONCURRENTLY cannot run inside a transaction block.
        // The regular CREATE UNIQUE INDEX will briefly lock the table but is safe within the
        // transaction.
        dataTableService.recreatePrimaryKeyIndex(dataset.tableName(), pkColumnNames);
      } else {
        dataTableService.recreatePrimaryKeyIndex(dataset.tableName(), pkColumnNames);
      }
    }

    // 컬럼명/표시명/타입 등 변경 반영 (PK 플래그만 바뀌어도 무해)
    reindexSearch(datasetId);

    // 감사(WD-463) — 저장 전(column)과 저장 후 다시 읽은 값을 비교해 바뀐 항목 이름만 남긴다.
    auditColumnUpdate(userId, dataset, column);
  }

  /** 필드 수정 감사의 바뀐 항목 키 → 설명용 라벨(WD-463). */
  private static final Map<String, String> COLUMN_FIELD_LABELS =
      Map.of(
          "columnName", "필드명",
          "displayName", "표시 이름",
          "dataType", "데이터 타입",
          "maxLength", "길이",
          "isNullable", "NULL 허용",
          "isIndexed", "인덱스",
          "description", "설명",
          "isPrimaryKey", "기본 키");

  /**
   * 필드 수정 감사(WD-463). 저장값끼리 비교해 바뀐 항목 <b>이름</b>만 모은다 — 웹은 편집 폼의 칸을 함께 보내므로 요청과 비교하면 그대로인 항목도 센다. 바뀐
   * 것이 없으면 남기지 않는다. 표시 이름·설명 본문은 싣지 않고, 설명에는 바뀐 뒤의 필드 이름을 쓴다.
   */
  private void auditColumnUpdate(
      Long userId, DatasetResponse dataset, DatasetColumnResponse before) {
    DatasetColumnResponse after = columnRepository.findById(before.id()).orElse(null);
    if (after == null) {
      return;
    }
    List<String> changed = new ArrayList<>();
    if (!Objects.equals(before.columnName(), after.columnName())) changed.add("columnName");
    if (!Objects.equals(blankToNull(before.displayName()), blankToNull(after.displayName()))) {
      changed.add("displayName");
    }
    if (!Objects.equals(before.dataType(), after.dataType())) changed.add("dataType");
    if (!Objects.equals(before.maxLength(), after.maxLength())) changed.add("maxLength");
    if (before.isNullable() != after.isNullable()) changed.add("isNullable");
    if (before.isIndexed() != after.isIndexed()) changed.add("isIndexed");
    if (!Objects.equals(blankToNull(before.description()), blankToNull(after.description()))) {
      changed.add("description");
    }
    if (before.isPrimaryKey() != after.isPrimaryKey()) changed.add("isPrimaryKey");
    if (changed.isEmpty()) {
      return; // 실제 변경 없음 — 감사 생략
    }
    datasetAuditRecorder.record(
        userId,
        "UPDATE",
        dataset.id(),
        dataset.name(),
        "FIELD_UPDATE",
        "필드 수정: "
            + dataset.name()
            + "."
            + after.columnName()
            + " (바뀐 항목: "
            + DatasetAuditRecorder.changedLabels(changed, COLUMN_FIELD_LABELS)
            + ")",
        Map.of("columnId", after.id(), "columnName", after.columnName(), "changedFields", changed));
  }

  /**
   * 데이터셋의 기본 키 컬럼 집합을 한 번에 갱신한다.
   *
   * <p>복합 PK 의 경우 단일 컬럼별 PUT 으로는 중간 상태가 unique 하지 않아 실패하므로, 최종 PK 컬럼 ID 목록을 받아 트랜잭션 안에서 일괄 적용한다. 검증
   * 순서:
   *
   * <ol>
   *   <li>모든 PK 컬럼이 NOT NULL 인지 확인 (PK 는 nullable 불가)
   *   <li>데이터가 있을 경우 최종 PK 집합이 실제로 unique 한지 한 번에 검증
   *   <li>{@code dataset_column.is_primary_key} 일괄 갱신
   *   <li>unique index 재생성
   * </ol>
   */
  /** 행위자 없는 내부·테스트 경로 — 감사는 행위자 "system" 으로 남는다. 사용자 요청은 userId 를 받는 오버로드로 들어온다. */
  @Transactional
  public void updatePrimaryKeys(Long datasetId, List<Long> requestedPkIds) {
    updatePrimaryKeys(datasetId, requestedPkIds, null);
  }

  /** 기본 키 필드 집합을 바꾸고, 집합이 실제로 바뀌면 감사(WD-463, PRIMARY_KEY_CHANGE)를 남긴다. */
  @Transactional
  public void updatePrimaryKeys(Long datasetId, List<Long> requestedPkIds, Long userId) {
    DatasetResponse dataset =
        datasetRepository.findById(datasetId).orElseThrow(() -> new DatasetNotFoundException());
    rejectIfDocument(dataset.storageType(), "기본키 변경");

    List<Long> pkIds = requestedPkIds != null ? requestedPkIds : List.of();

    List<DatasetColumnResponse> allColumns = columnRepository.findByDatasetId(datasetId);
    java.util.Map<Long, DatasetColumnResponse> byId = new java.util.HashMap<>();
    for (DatasetColumnResponse c : allColumns) {
      byId.put(c.id(), c);
    }

    // 잘못된 컬럼 ID 검증
    List<DatasetColumnResponse> targetCols = new java.util.ArrayList<>();
    for (Long id : pkIds) {
      DatasetColumnResponse c = byId.get(id);
      if (c == null) {
        throw new IllegalArgumentException("이 데이터셋에 없는 필드입니다: " + id);
      }
      targetCols.add(c);
    }

    // 모든 PK 컬럼이 NOT NULL 이어야 한다
    for (DatasetColumnResponse c : targetCols) {
      if (c.isNullable()) {
        throw primaryKeyNullable(c.columnName());
      }
    }

    long rowCount = dataTableRowService.countRows(dataset.tableName());

    List<String> pkColumnNames =
        targetCols.stream().map(DatasetColumnResponse::columnName).toList();

    // 데이터 존재 시 최종 집합이 unique 한지 한 번에 검증
    if (rowCount > 0 && !pkColumnNames.isEmpty()) {
      if (!dataTableRowService.checkDataUniqueness(dataset.tableName(), pkColumnNames)) {
        throw new ColumnModificationException("기본 키를 설정할 수 없습니다. 데이터에 중복값이 존재합니다.");
      }
    }

    columnRepository.updatePrimaryKeys(datasetId, pkIds);
    dataTableService.recreatePrimaryKeyIndex(dataset.tableName(), pkColumnNames);

    // 감사(WD-463) — 기본 키 필드 집합이 실제로 바뀐 경우만. 필드 이름만 싣는다(PK 값은 실제 데이터라 싣지 않음).
    Set<String> beforePk =
        allColumns.stream()
            .filter(DatasetColumnResponse::isPrimaryKey)
            .map(DatasetColumnResponse::columnName)
            .collect(java.util.stream.Collectors.toSet());
    if (!beforePk.equals(new java.util.HashSet<>(pkColumnNames))) {
      String fields = pkColumnNames.isEmpty() ? "없음" : String.join("·", pkColumnNames);
      datasetAuditRecorder.record(
          userId,
          "UPDATE",
          datasetId,
          dataset.name(),
          "PRIMARY_KEY_CHANGE",
          "기본 키 변경: " + dataset.name() + " (기본 키: " + fields + ")",
          Map.of("primaryKeyColumns", pkColumnNames, "changedFields", List.of("isPrimaryKey")));
    }
  }

  /** 행위자 없는 내부·테스트 경로 — 감사는 행위자 "system" 으로 남는다. 사용자 요청은 userId 를 받는 오버로드로 들어온다. */
  @Transactional
  public void deleteColumn(Long datasetId, Long columnId) {
    deleteColumn(datasetId, columnId, null);
  }

  /** 필드를 삭제하고, 성공하면 감사(WD-463, FIELD_DELETE)를 남긴다 — 그 필드의 데이터가 사라지므로 action 은 DELETE. */
  @Transactional
  public void deleteColumn(Long datasetId, Long columnId, Long userId) {
    DatasetResponse dataset =
        datasetRepository.findById(datasetId).orElseThrow(() -> new DatasetNotFoundException());
    rejectIfDocument(dataset.storageType(), "컬럼 관리");

    // WD-186: 필드를 읽기 전에 매핑 저장과 직렬화하는 잠금을 잡는다 — 판정 직후 매핑 저장이 이 필드를 새로 연결하는 틈을 없앤다.
    mappingReferenceService.lockForColumnChange(datasetId);

    DatasetColumnResponse column =
        columnRepository
            .findById(columnId)
            .orElseThrow(() -> new IllegalArgumentException("필드를 찾을 수 없습니다: " + columnId));

    Long columnDatasetId =
        columnRepository
            .findDatasetIdByColumnId(columnId)
            .orElseThrow(() -> new IllegalArgumentException("필드를 찾을 수 없습니다: " + columnId));

    if (!columnDatasetId.equals(datasetId)) {
      throw new IllegalArgumentException("이 데이터셋의 필드가 아닙니다: " + columnId);
    }

    List<DatasetColumnResponse> columns = columnRepository.findByDatasetId(datasetId);
    if (columns.size() <= 1) {
      throw new ColumnModificationException("데이터셋의 마지막 필드는 삭제할 수 없습니다.");
    }

    // WD-186: 이 데이터셋의 매핑(draft·active)이 쓰는 필드면 409 로 막는다. 그대로 지우면 매핑 탭은 없는 필드를 계속 보여 주고
    // 활성화는 400, 그래프 투영은 그 필드를 조용히 건너뛰어 노드 0개로 「성공」한다.
    mappingReferenceService.assertColumnUnused(datasetId, column.columnName());

    // 삭제 전에 검색 대상 여부를 잡아 둔다(삭제 후에는 컬럼 행이 없어 판정할 수 없다).
    boolean wasSearchField = searchColumnRepository.isSearchable(columnId);

    dataTableService.dropColumn(dataset.tableName(), column.columnName());
    columnRepository.deleteById(columnId);

    // 마지막 검색 대상 필드를 지웠으면 검색 탭에서 필드를 모두 해제한 것과 같게 즉시 끈다(상태 행 삭제 + 색인 테이블
    // DROP). 그대로 두면 다음 스윕 전까지 낡은 색인이 남는다. 검색 필드가 남아 있으면 config_hash 가 바뀌어 스윕이
    // 알아서 재색인하므로 손대지 않는다.
    if (wasSearchField && searchColumnRepository.findConfig(datasetId).isEmpty()) {
      searchIndexSettingsService.update(datasetId, List.of());
    }

    // Recreate PK index if deleted column was a PK column
    if (column.isPrimaryKey()) {
      List<DatasetColumnResponse> remainingColumns = columnRepository.findByDatasetId(datasetId);
      List<String> pkColumnNames =
          remainingColumns.stream()
              .filter(DatasetColumnResponse::isPrimaryKey)
              .map(DatasetColumnResponse::columnName)
              .toList();
      dataTableService.recreatePrimaryKeyIndex(dataset.tableName(), pkColumnNames);
    }

    // 컬럼 삭제 반영 (데이터셋은 남으므로 인덱스 갱신)
    reindexSearch(datasetId);

    // 감사(WD-463) — 행이 사라졌으므로 지우기 전에 읽어 둔 필드 이름으로 남긴다.
    datasetAuditRecorder.record(
        userId,
        "DELETE",
        datasetId,
        dataset.name(),
        "FIELD_DELETE",
        "필드 삭제: " + dataset.name() + "." + column.columnName(),
        Map.of("columnId", column.id(), "columnName", column.columnName()));
  }

  /** 행위자 없는 내부·테스트 경로 — 감사는 행위자 "system" 으로 남는다. 사용자 요청은 userId 를 받는 오버로드로 들어온다. */
  @Transactional
  public void reorderColumns(Long datasetId, ReorderColumnsRequest request) {
    reorderColumns(datasetId, request, null);
  }

  /** 필드 순서를 바꾸고, 순서가 실제로 바뀌면 감사(WD-463, FIELD_REORDER)를 남긴다. */
  @Transactional
  public void reorderColumns(Long datasetId, ReorderColumnsRequest request, Long userId) {
    DatasetResponse dataset =
        datasetRepository.findById(datasetId).orElseThrow(() -> new DatasetNotFoundException());

    List<DatasetColumnResponse> existingColumns = columnRepository.findByDatasetId(datasetId);
    Set<Long> existingIds =
        existingColumns.stream()
            .map(DatasetColumnResponse::id)
            .collect(java.util.stream.Collectors.toSet());
    Set<Long> requestIds = new java.util.HashSet<>(request.columnIds());

    if (requestIds.size() != request.columnIds().size()) {
      throw new IllegalArgumentException("필드 순서 요청에 같은 필드가 두 번 들어 있습니다.");
    }
    if (!existingIds.equals(requestIds)) {
      throw new IllegalArgumentException("필드 순서 요청이 데이터셋의 필드 목록과 일치하지 않습니다.");
    }

    columnRepository.updateOrders(datasetId, request.columnIds());

    // 감사(WD-463) — 기존 순서(findByDatasetId 는 순서대로 읽는다)와 같으면 바뀐 것이 없어 남기지 않는다.
    List<Long> beforeOrder = existingColumns.stream().map(DatasetColumnResponse::id).toList();
    if (!beforeOrder.equals(request.columnIds())) {
      datasetAuditRecorder.record(
          userId,
          "UPDATE",
          datasetId,
          dataset.name(),
          "FIELD_REORDER",
          "필드 순서 변경: " + dataset.name(),
          Map.of("changedFields", List.of("columnOrder")));
    }
  }

  // =========================================================================
  // Phase 5.10: Dataset References (dataset-manager subagent 지원)
  // =========================================================================

  /**
   * 주어진 데이터셋을 참조하는 자원(파이프라인/대시보드/Proactive Job)을 집계한다.
   *
   * <p>삭제 전 영향 범위 확인 용도로 사용된다. 각 카테고리별로 다음과 같이 참조를 판정한다.
   *
   * <ul>
   *   <li><b>Pipelines</b>: {@code pipeline_step.output_dataset_id} 또는 {@code
   *       pipeline_step_input.dataset_id} 가 주어진 datasetId 인 파이프라인. 동일 파이프라인이 양쪽에 등장해도 중복 제거된다.
   *   <li><b>Dashboards</b>: {@code dashboard_widget → chart → saved_query.dataset_id} 경로로 연결된
   *       대시보드. 현재 스키마상 dashboard/widget 자체에는 datasetId 가 없고, widget이 참조하는 chart의 saved_query를 통해
   *       간접적으로만 데이터셋과 연결된다. 저장 쿼리 SQL 본문이 이 테이블을 읽는 경우도 포함한다(WD-143). 조회자가 볼 수 없는(남의 비공개) 대시보드는 이름
   *       없이 {@code hiddenDashboardCount} 로 개수만 싣는다.
   *   <li><b>Proactive Jobs</b>: 현재 {@code proactive_job} 스키마는 datasetId 를 저장하지 않는다(채널/수신자/템플릿만
   *       저장). 따라서 이 메서드는 항상 빈 리스트를 반환한다. 추후 스키마가 datasetId 를 포함하게 되면 업데이트 필요.
   *   <li><b>Triggers</b>: {@code pipeline_trigger.trigger_type = 'DATASET_CHANGE'} 이고 {@code
   *       config} JSONB의 {@code datasetIds} 배열이 주어진 datasetId 를 포함하는 트리거. 이 참조는 FK가 아니라 JSONB 배열
   *       소속이므로 위 3개 카테고리와 달리 DB 제약으로 보호되지 않는다 — 데이터셋을 삭제해도 트리거는 고아로 남아 존재하지 않는 데이터셋 ID를 계속 폴링하게
   *       된다(#600).
   * </ul>
   *
   * @param userId 조회자 — 볼 수 없는 대시보드 이름을 가리는 데 쓴다
   */
  @Transactional(readOnly = true)
  public DatasetReferencesResponse getReferences(Long datasetId, Long userId) {
    // 1. 데이터셋 존재 검증: 참조 집계 쿼리를 실행하기 전에 404 조기 반환. 행 전체 대신 SQL 본문 참조 판정(WD-143)에 필요한
    //    물리 테이블 이름만 읽는다.
    String tableName =
        dsl.select(field(name("dataset", "table_name"), String.class))
            .from(table(name("dataset")))
            .where(field(name("dataset", "id"), Long.class).eq(datasetId))
            .fetchOptional(field(name("dataset", "table_name"), String.class))
            .orElseThrow(() -> new DatasetNotFoundException());
    // WD-206: SQL 본문 대조는 테넌트 방언 기준 범위로 — 한 번 만들어 파이프라인·대시보드 양쪽에 쓴다.
    DatasetSqlReferenceMatcher.Scope sqlScope =
        DatasetSqlReferenceMatcher.scope(pipelineDataSchema, tableName);

    // 2. Pipelines: output_dataset_id 또는 pipeline_step_input.dataset_id 기준으로 조회. DISTINCT 로 중복 제거.
    List<DatasetReferencesResponse.ReferenceItem> pipelinesByFk =
        dsl.selectDistinct(
                field(name("pipeline", "id"), Long.class),
                field(name("pipeline", "name"), String.class))
            .from(table(name("pipeline")))
            .where(
                field(name("pipeline", "id"), Long.class)
                    .in(
                        dsl.select(field(name("pipeline_step", "pipeline_id"), Long.class))
                            .from(table(name("pipeline_step")))
                            .where(
                                field(name("pipeline_step", "output_dataset_id"), Long.class)
                                    .eq(datasetId))))
            .or(
                field(name("pipeline", "id"), Long.class)
                    .in(
                        dsl.select(field(name("pipeline_step", "pipeline_id"), Long.class))
                            .from(table(name("pipeline_step")))
                            .join(table(name("pipeline_step_input")))
                            .on(
                                field(name("pipeline_step", "id"), Long.class)
                                    .eq(field(name("pipeline_step_input", "step_id"), Long.class)))
                            .where(
                                field(name("pipeline_step_input", "dataset_id"), Long.class)
                                    .eq(datasetId))))
            .orderBy(field(name("pipeline", "id"), Long.class))
            .fetch(
                r ->
                    new DatasetReferencesResponse.ReferenceItem(
                        r.get(field(name("pipeline", "id"), Long.class)),
                        r.get(field(name("pipeline", "name"), String.class))));

    // 2-0. (WD-441) 이 데이터셋을 병합(MERGE) 적재 출력으로 쓰는 파이프라인 — 기본 키를 해제하면 매 실행 실패하는 대상이다.
    //      pipelines 의 부분집합이라 totalCount 에는 더하지 않는다.
    List<DatasetReferencesResponse.ReferenceItem> mergeOutputPipelines =
        dsl.selectDistinct(
                field(name("pipeline", "id"), Long.class),
                field(name("pipeline", "name"), String.class))
            .from(table(name("pipeline")))
            .join(table(name("pipeline_step")))
            .on(
                field(name("pipeline_step", "pipeline_id"), Long.class)
                    .eq(field(name("pipeline", "id"), Long.class)))
            .where(field(name("pipeline_step", "output_dataset_id"), Long.class).eq(datasetId))
            .and(field(name("pipeline_step", "load_strategy"), String.class).eq("MERGE"))
            .orderBy(field(name("pipeline", "id"), Long.class))
            .fetch(
                r ->
                    new DatasetReferencesResponse.ReferenceItem(
                        r.get(field(name("pipeline", "id"), Long.class)),
                        r.get(field(name("pipeline", "name"), String.class))));

    // 2-1. (WD-143) 선언 입력 없이 SQL 스텝 본문으로만 이 테이블을 읽는 파이프라인 — FK 결과와 id 로 합친다.
    List<DatasetReferencesResponse.ReferenceItem> pipelines =
        mergeById(pipelinesByFk, findPipelinesBySqlText(sqlScope, tableName));

    // 3. Dashboards: dashboard → dashboard_widget → chart → saved_query.dataset_id 경로로 조회. 조회자
    // 가시성(본인 소유 또는
    //    공유)을 함께 읽어, 볼 수 없는 대시보드는 이름 대신 개수만 싣는다(WD-143 — 삭제 확인 창에 남의 비공개 대시보드 이름이 보이지 않게).
    Field<Boolean> dashboardVisible = field(AnalyticsDashboardRepository.visibleTo(userId));
    List<DashboardHit> dashboardsByFk =
        dsl.selectDistinct(
                field(name("dashboard", "id"), Long.class),
                field(name("dashboard", "name"), String.class),
                dashboardVisible)
            .from(table(name("dashboard")))
            .join(table(name("dashboard_widget")))
            .on(
                field(name("dashboard", "id"), Long.class)
                    .eq(field(name("dashboard_widget", "dashboard_id"), Long.class)))
            .join(table(name("chart")))
            .on(
                field(name("dashboard_widget", "chart_id"), Long.class)
                    .eq(field(name("chart", "id"), Long.class)))
            .join(table(name("saved_query")))
            .on(
                field(name("chart", "saved_query_id"), Long.class)
                    .eq(field(name("saved_query", "id"), Long.class)))
            .where(field(name("saved_query", "dataset_id"), Long.class).eq(datasetId))
            .fetch(
                r ->
                    new DashboardHit(
                        r.get(field(name("dashboard", "id"), Long.class)),
                        r.get(field(name("dashboard", "name"), String.class)),
                        Boolean.TRUE.equals(r.get(dashboardVisible))));

    // 3-1. (WD-143) 화면 쿼리 편집기로 저장한 쿼리는 dataset_id 가 비어 있다(편집기가 보내지 않는다) — 저장 쿼리 SQL 본문이 이 테이블을
    //      참조하는 위젯의 대시보드도 찾아 FK 결과와 id 로 합친다. 이게 없으면 "쿼리→차트→대시보드" 로 쓰는 데이터셋이 확인 창에 안 잡힌다.
    java.util.Map<Long, DashboardHit> dashboardHits = new java.util.TreeMap<>();
    dashboardsByFk.forEach(d -> dashboardHits.putIfAbsent(d.id(), d));
    findDashboardsBySqlText(sqlScope, tableName, dashboardVisible)
        .forEach(d -> dashboardHits.putIfAbsent(d.id(), d));
    List<DatasetReferencesResponse.ReferenceItem> dashboards =
        dashboardHits.values().stream()
            .filter(DashboardHit::visible)
            .map(d -> new DatasetReferencesResponse.ReferenceItem(d.id(), d.name()))
            .toList();
    int hiddenDashboardCount = dashboardHits.size() - dashboards.size();

    // 4. Proactive Jobs: 현재 스키마상 datasetId 연결이 없음 → 빈 리스트. (Phase 5.10 설계 문서 참조)
    List<DatasetReferencesResponse.ReferenceItem> proactiveJobs = List.of();

    // 5. Triggers (#600): pipeline_trigger 는 FK가 아니라 config JSONB의 datasetIds 배열로
    //    데이터셋을 감시한다. `config -> 'datasetIds' @> to_jsonb(datasetId)` 로 배열 포함 여부를
    //    검사한다 — 삭제 시 이 참조를 놓치면 트리거가 고아로 남아 존재하지 않는 데이터셋을 계속 폴링한다.
    List<DatasetReferencesResponse.TriggerReferenceItem> triggers =
        dsl.selectDistinct(
                field(name("pipeline_trigger", "id"), Long.class),
                field(name("pipeline_trigger", "name"), String.class),
                field(name("pipeline", "id"), Long.class),
                field(name("pipeline", "name"), String.class))
            .from(table(name("pipeline_trigger")))
            .join(table(name("pipeline")))
            .on(
                field(name("pipeline_trigger", "pipeline_id"), Long.class)
                    .eq(field(name("pipeline", "id"), Long.class)))
            .where(
                field(name("pipeline_trigger", "trigger_type"), String.class).eq("DATASET_CHANGE"))
            .and(
                condition(
                    "(pipeline_trigger.config -> 'datasetIds') @> to_jsonb({0}::bigint)",
                    datasetId))
            .orderBy(field(name("pipeline_trigger", "id"), Long.class))
            .fetch(
                r ->
                    new DatasetReferencesResponse.TriggerReferenceItem(
                        r.get(field(name("pipeline_trigger", "id"), Long.class)),
                        r.get(field(name("pipeline_trigger", "name"), String.class)),
                        r.get(field(name("pipeline", "id"), Long.class)),
                        r.get(field(name("pipeline", "name"), String.class))));

    int totalCount =
        pipelines.size()
            + dashboards.size()
            + hiddenDashboardCount
            + proactiveJobs.size()
            + triggers.size();
    return new DatasetReferencesResponse(
        datasetId,
        pipelines,
        mergeOutputPipelines,
        dashboards,
        hiddenDashboardCount,
        proactiveJobs,
        triggers,
        totalCount);
  }

  /**
   * SQL 스텝 본문이 이 데이터셋 테이블을 참조하는 파이프라인(WD-143). 같은 테넌트(RLS) 안에서 {@code ILIKE '%테이블명%'} 으로 값싸게 1차로 거른
   * 뒤, 관문과 같은 파서로 실제 테이블 참조인지 확정한다 — 1차 거르기만으로는 {@code sales_2024}·문자열 리터럴·주석 안의 이름까지 걸린다. 스텝 본문은
   * 저장 검증과 같은 치환({{#N}}·{{last_run_at}})을 거쳐야 파싱된다.
   */
  private List<DatasetReferencesResponse.ReferenceItem> findPipelinesBySqlText(
      DatasetSqlReferenceMatcher.Scope scope, String tableName) {
    Field<Long> pipelineId = field(name("pipeline", "id"), Long.class);
    Field<String> pipelineName = field(name("pipeline", "name"), String.class);
    Field<String> script = field(name("pipeline_step", "script_content"), String.class);
    return dsl
        .select(pipelineId, pipelineName, script)
        .from(table(name("pipeline")))
        .join(table(name("pipeline_step")))
        .on(pipelineId.eq(field(name("pipeline_step", "pipeline_id"), Long.class)))
        .where(field(name("pipeline_step", "script_type"), String.class).eq("SQL"))
        .and(script.likeIgnoreCase(containsPattern(tableName), '\\'))
        .fetch()
        .stream()
        .filter(
            r ->
                DatasetSqlReferenceMatcher.references(
                    PipelineService.substituteStepReferencesForValidation(r.get(script)), scope))
        .map(
            r ->
                new DatasetReferencesResponse.ReferenceItem(r.get(pipelineId), r.get(pipelineName)))
        .toList();
  }

  /** 참조 대시보드 한 건 — 조회자가 볼 수 있는지(본인 소유 또는 공유)를 함께 든다. 응답으로 직렬화하지 않는다(볼 수 없는 이름이 들어 있다). */
  private record DashboardHit(Long id, String name, boolean visible) {}

  /**
   * 위젯 → 차트 → 저장 쿼리 SQL 본문이 이 데이터셋 테이블을 참조하는 대시보드(WD-143). 거르기·확정 방식은 {@link
   * #findPipelinesBySqlText} 와 같다. 가시성은 FK 경로와 같은 조건({@code visible})으로 함께 읽어 호출부가 이름을 가린다.
   */
  private List<DashboardHit> findDashboardsBySqlText(
      DatasetSqlReferenceMatcher.Scope scope, String tableName, Field<Boolean> visible) {
    Field<Long> dashboardId = field(name("dashboard", "id"), Long.class);
    Field<String> dashboardName = field(name("dashboard", "name"), String.class);
    Field<String> sqlText = field(name("saved_query", "sql_text"), String.class);
    return dsl
        .selectDistinct(dashboardId, dashboardName, visible, sqlText)
        .from(table(name("dashboard")))
        .join(table(name("dashboard_widget")))
        .on(dashboardId.eq(field(name("dashboard_widget", "dashboard_id"), Long.class)))
        .join(table(name("chart")))
        .on(
            field(name("dashboard_widget", "chart_id"), Long.class)
                .eq(field(name("chart", "id"), Long.class)))
        .join(table(name("saved_query")))
        .on(
            field(name("chart", "saved_query_id"), Long.class)
                .eq(field(name("saved_query", "id"), Long.class)))
        .where(sqlText.likeIgnoreCase(containsPattern(tableName), '\\'))
        .fetch()
        .stream()
        .filter(r -> DatasetSqlReferenceMatcher.references(r.get(sqlText), scope))
        .map(
            r ->
                new DashboardHit(
                    r.get(dashboardId), r.get(dashboardName), Boolean.TRUE.equals(r.get(visible))))
        .toList();
  }

  /**
   * {@code ILIKE} 부분 일치 패턴 — 테이블 이름의 {@code _} 는 LIKE 와일드카드라 이스케이프한다(안 하면 1차 거르기가 넓어질 뿐 결과는 같다).
   */
  private static String containsPattern(String tableName) {
    String escaped = tableName.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    return "%" + escaped + "%";
  }

  /** FK 결과와 SQL 본문 결과를 id 로 중복 제거해 합친다 — 기존 응답처럼 id 오름차순. */
  private static List<DatasetReferencesResponse.ReferenceItem> mergeById(
      List<DatasetReferencesResponse.ReferenceItem> byFk,
      List<DatasetReferencesResponse.ReferenceItem> bySql) {
    java.util.Map<Long, DatasetReferencesResponse.ReferenceItem> merged = new java.util.TreeMap<>();
    byFk.forEach(i -> merged.putIfAbsent(i.id(), i));
    bySql.forEach(i -> merged.putIfAbsent(i.id(), i));
    return new ArrayList<>(merged.values());
  }

  // --- Phase 6-1: Status ---

  @Transactional
  public DatasetDetailResponse updateStatus(Long id, UpdateStatusRequest request, Long userId) {
    if (request.status() == null || !VALID_STATUSES.contains(request.status())) {
      throw new IllegalArgumentException(
          "Invalid status. Must be one of: NONE, CERTIFIED, DEPRECATED");
    }

    DatasetResponse before =
        datasetRepository.findById(id).orElseThrow(() -> new DatasetNotFoundException());

    datasetRepository.updateStatus(id, request.status(), request.note(), userId);
    auditStatusChange(userId, before, request);
    return getDatasetById(id, userId);
  }

  /** 데이터셋 상태 값 → 설명용 라벨(WD-463). 웹 목록 필터의 라벨과 같다. */
  private static final Map<String, String> STATUS_LABELS =
      Map.of("NONE", "없음", "CERTIFIED", "인증됨", "DEPRECATED", "사용 중단");

  /**
   * 데이터셋 상태 변경 감사(WD-463, STATUS_CHANGE). 상태나 상태 메모가 실제로 바뀐 경우만 남긴다. 상태는 정해진
   * 값(NONE·CERTIFIED·DEPRECATED)이라 이전·이후 값을 싣고, 상태 메모는 자유 입력 글이라 「바뀌었다」는 항목 이름만 싣는다.
   */
  private void auditStatusChange(Long userId, DatasetResponse before, UpdateStatusRequest request) {
    String beforeStatus = before.status() == null ? "NONE" : before.status();
    List<String> changed = new ArrayList<>();
    if (!beforeStatus.equals(request.status())) changed.add("status");
    if (!Objects.equals(blankToNull(before.statusNote()), blankToNull(request.note()))) {
      changed.add("statusNote");
    }
    if (changed.isEmpty()) {
      return; // 실제 변경 없음 — 감사 생략
    }
    String detail =
        changed.contains("status")
            ? STATUS_LABELS.getOrDefault(beforeStatus, beforeStatus)
                + " → "
                + STATUS_LABELS.getOrDefault(request.status(), request.status())
            : "바뀐 항목: 상태 메모";
    Map<String, Object> extra = new LinkedHashMap<>();
    extra.put("previousStatus", beforeStatus);
    extra.put("status", request.status());
    extra.put("changedFields", changed);
    datasetAuditRecorder.record(
        userId,
        "STATUS_CHANGE",
        before.id(),
        before.name(),
        "STATUS_CHANGE",
        "데이터셋 상태 변경: " + before.name() + " (" + detail + ")",
        extra);
  }

  // =========================================================================
  // Phase 3: Clone/Copy Dataset
  // =========================================================================

  @Transactional
  public DatasetDetailResponse cloneDataset(
      Long sourceId, CloneDatasetRequest request, Long userId) {
    // 1. Fetch source dataset with columns
    DatasetResponse sourceDataset =
        datasetRepository.findById(sourceId).orElseThrow(() -> new DatasetNotFoundException());
    // DOCUMENT 데이터셋은 동적 테이블이 없어 복제 시 phantom 테이블이 생기므로 Phase 1 에서는 거부한다.
    rejectIfDocument(sourceDataset.storageType(), "복제");

    List<DatasetColumnResponse> sourceColumns = columnRepository.findByDatasetId(sourceId);

    // 2. Validate new names don't already exist
    // 새 테이블명 검증은 중복 검사보다 먼저다(생성 경로와 같은 이유).
    dataTableService.validateNewTableName(request.tableName());
    requireUnusedNameAndTableName(request.name(), request.tableName());

    // 3. Create new dataset record
    String description =
        request.description() != null ? request.description() : sourceDataset.description();
    CreateDatasetRequest createRequest =
        new CreateDatasetRequest(
            request.name(),
            request.tableName(),
            description,
            sourceDataset.category() != null ? sourceDataset.category().id() : null,
            sourceDataset.storageType(),
            sourceDataset.originType(),
            List.of(), // columns will be added separately
            null // sourcePipelineStepId — cloned datasets are not auto-generated
            );
    DatasetResponse newDataset = datasetRepository.save(createRequest, userId);

    // 보안 등급: 사본은 원본 등급·허용 목록을 상속한다(스펙 §4.5 clone). 기본 등급으로 두면 기밀 데이터가 내부로 새어 나간다.
    datasetSecurityService.inheritFromSource(sourceId, newDataset.id(), userId);

    // 4. Copy column definitions
    List<DatasetColumnRequest> columnRequests = new ArrayList<>();
    for (DatasetColumnResponse col : sourceColumns) {
      DatasetColumnRequest colReq =
          new DatasetColumnRequest(
              col.columnName(),
              col.displayName(),
              col.dataType(),
              col.maxLength(),
              col.isNullable(),
              col.isIndexed(),
              col.description(),
              col.isPrimaryKey());
      columnRequests.add(colReq);
    }
    withColumnNameUniqueAs409(() -> columnRepository.saveBatch(newDataset.id(), columnRequests));

    // 5. Clone table data or create empty schema
    List<String> userColumnNames =
        sourceColumns.stream().map(DatasetColumnResponse::columnName).toList();

    if (request.includeData()) {
      dataTableService.cloneTable(
          sourceDataset.tableName(), request.tableName(), userColumnNames, sourceColumns);

      // Recreate indexes
      for (DatasetColumnResponse col : sourceColumns) {
        if (col.isIndexed()) {
          dataTableService.setColumnIndex(
              request.tableName(), col.columnName(), true, col.dataType());
        }
      }

      // Recreate PK unique index
      List<String> pkColumnNames =
          sourceColumns.stream()
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

    // 7. 신규 데이터셋(컬럼·태그 적재 완료) 검색 인덱스 갱신
    reindexSearch(newDataset.id());

    // 8. Return full detail response
    return getDatasetById(newDataset.id(), userId);
  }
}
