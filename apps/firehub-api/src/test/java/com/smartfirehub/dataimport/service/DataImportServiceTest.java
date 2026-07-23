package com.smartfirehub.dataimport.service;

import static com.smartfirehub.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.dataimport.dto.ColumnMappingEntry;
import com.smartfirehub.dataimport.dto.ExportFormat;
import com.smartfirehub.dataimport.dto.ExportRequest;
import com.smartfirehub.dataimport.dto.ExportResult;
import com.smartfirehub.dataimport.dto.ImportPreviewResponse;
import com.smartfirehub.dataimport.dto.ImportResponse;
import com.smartfirehub.dataimport.dto.ImportStartResponse;
import com.smartfirehub.dataimport.dto.ImportValidateResponse;
import com.smartfirehub.dataimport.dto.ParseOptions;
import com.smartfirehub.dataimport.exception.UnsupportedFileTypeException;
import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetDetailResponse;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.job.service.AsyncJobService;
import com.smartfirehub.support.IntegrationTestBase;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class DataImportServiceTest extends IntegrationTestBase {

  @Autowired private DataImportService dataImportService;

  @Autowired private DataExportService dataExportService;

  @Autowired private DatasetService datasetService;

  @Autowired private AuditLogService auditLogService;

  @Autowired private DSLContext dsl;

  @MockitoSpyBean private AsyncJobService asyncJobService;

  @MockitoSpyBean private DataValidationService validationService;

  private Long testUserId;
  private Long testDatasetId;

  @BeforeEach
  void setUp() {
    // Create test user
    testUserId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "testuser")
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "Test User")
            .set(USER.EMAIL, "test@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();

    // Create test dataset
    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest("name", "Name", "TEXT", null, false, false, null),
            new DatasetColumnRequest("age", "Age", "INTEGER", null, true, false, null),
            new DatasetColumnRequest("email", "Email", "TEXT", null, true, false, null));

    DatasetDetailResponse dataset =
        datasetService.createDataset(
            new CreateDatasetRequest(
                "Import Test Dataset",
                "import_test_dataset",
                "Dataset for import testing",
                null,
                "TABLE", "SOURCE",
                columns,
                null),
            testUserId);

    testDatasetId = dataset.id();
  }

  @Test
  void importFile_csvWithValidData_returnsResponse() throws Exception {
    // Given
    String csvContent =
        """
        name,age,email
        Alice,30,alice@example.com
        Bob,25,bob@example.com
        """;

    MockMultipartFile file =
        new MockMultipartFile(
            "file", "test.csv", "text/csv", csvContent.getBytes(StandardCharsets.UTF_8));

    // When
    ImportStartResponse response =
        dataImportService.importFile(
            testDatasetId, file, null, testUserId, "Test User", "127.0.0.1", "TestAgent");

    // Then
    assertThat(response.jobId()).isNotNull();
    assertThat(response.status()).isEqualTo("PENDING");
  }

  @Test
  void importFile_unsupportedFileType_throwsException() {
    // Given
    MockMultipartFile file =
        new MockMultipartFile(
            "file",
            "test.pdf",
            "application/pdf",
            "dummy content".getBytes(StandardCharsets.UTF_8));

    // When/Then
    assertThatThrownBy(
            () ->
                dataImportService.importFile(
                    testDatasetId, file, null, testUserId, "Test User", null, null))
        .isInstanceOf(UnsupportedFileTypeException.class)
        .hasMessageContaining("Unsupported file type");
  }

  @Test
  void importFile_noFileName_throwsException() {
    // Given
    MockMultipartFile file =
        new MockMultipartFile("file", null, "text/csv", "dummy".getBytes(StandardCharsets.UTF_8));

    // When/Then
    assertThatThrownBy(
            () ->
                dataImportService.importFile(
                    testDatasetId, file, null, testUserId, "Test User", null, null))
        .isInstanceOf(UnsupportedFileTypeException.class)
        .hasMessageContaining("File name is required");
  }

  @Test
  void previewImport_fullCsv_countsAllRows() throws Exception {
    // Given - 완전한 CSV (partial=false, 기본 경로)
    String csvContent =
        """
        name,age,email
        Alice,30,alice@example.com
        Bob,25,bob@example.com
        Carol,40,carol@example.com
        """;
    MockMultipartFile file =
        new MockMultipartFile(
            "file", "test.csv", "text/csv", csvContent.getBytes(StandardCharsets.UTF_8));

    // When
    ImportPreviewResponse preview = dataImportService.previewImport(testDatasetId, file);

    // Then - 전체 파일이므로 전체 행수(3)를 정확히 계산한다
    assertThat(preview.fileHeaders()).containsExactly("name", "age", "email");
    assertThat(preview.totalRows()).isEqualTo(3);
  }

  /**
   * 대용량 CSV 미리보기 슬라이스(413 회피) 회귀 방지: 프론트가 앞부분만 잘라 보내면 파일 끝의 마지막 행이 열린 따옴표로 끝날 수 있다. partial=true일
   * 때 전체 행수 계산(countRows, 파일 끝까지 스캔)을 건너뛰어 예외 없이 헤더+샘플을 반환해야 한다. 샘플 5행은 파일 앞에서 조기 종료하므로 잘린 꼬리에 닿지 않는다.
   */
  @Test
  void previewImport_partialTruncatedCsv_skipsCountAndDoesNotThrow() throws Exception {
    // Given - 마지막 행이 닫히지 않은 따옴표로 끝나는(파일 중간에서 잘린) CSV.
    // 완전한 데이터 행 6개 뒤에 잘린 행 1개 → 샘플 5행은 잘린 행에 닿지 않는다.
    String truncatedCsv =
        "name,age,email\n"
            + "Alice,30,a@x.com\n"
            + "Bob,25,b@x.com\n"
            + "Carol,40,c@x.com\n"
            + "Dave,35,d@x.com\n"
            + "Eve,28,e@x.com\n"
            + "Frank,50,f@x.com\n"
            + "Grace,22,\"unterminated"; // 열린 따옴표 상태로 EOF
    MockMultipartFile file =
        new MockMultipartFile(
            "file", "big.csv", "text/csv", truncatedCsv.getBytes(StandardCharsets.UTF_8));

    // When - partial=true
    ImportPreviewResponse preview =
        dataImportService.previewImport(testDatasetId, file, ParseOptions.defaults(), true);

    // Then - 예외 없이 헤더/샘플 반환, 전체 행수는 미계산(-1)
    assertThat(preview.fileHeaders()).containsExactly("name", "age", "email");
    assertThat(preview.sampleRows()).hasSize(5);
    assertThat(preview.totalRows()).isEqualTo(-1);
  }

  /**
   * validateImport 스트리밍 배치 검증(BATCH_SIZE=2000) 시 오류 rowIndex가 배치 로컬(1..N)이 아니라 파일 전역 기준이어야
   * 한다(rowIndexBase 스레딩 회귀 테스트). 5000행 CSV 중 2001~2100행(두 번째 배치 내부)에서 name을 비워 필수값 오류를
   * 유발하고, 에러가 100개로 캡되면서도 rowIndex가 2001~2100 범위(전역)로 정확히 나오는지 검증한다.
   */
  @Test
  void validateImport_largeCsvSpanningMultipleBatches_hasGlobalRowIndexAndCappedErrors()
      throws Exception {
    // Given - 5000행 CSV, 2001~2100행만 name 비움(필수값 위반)
    StringBuilder csv = new StringBuilder("name,age,email\n");
    for (int i = 1; i <= 5000; i++) {
      boolean invalid = i >= 2001 && i <= 2100;
      String name = invalid ? "" : "User" + i;
      csv.append(name).append(",").append(20 + (i % 60)).append(",user").append(i).append("@x.com\n");
    }
    MockMultipartFile file =
        new MockMultipartFile(
            "file", "big.csv", "text/csv", csv.toString().getBytes(StandardCharsets.UTF_8));

    List<ColumnMappingEntry> mappings =
        List.of(
            new ColumnMappingEntry("name", "name"),
            new ColumnMappingEntry("age", "age"),
            new ColumnMappingEntry("email", "email"));

    // When
    ImportValidateResponse result = dataImportService.validateImport(testDatasetId, file, mappings);

    // Then - 전체/정상/오류 건수는 정확해야 하고, 오류는 첫 100개만 반환된다
    assertThat(result.totalRows()).isEqualTo(5000);
    assertThat(result.errorRows()).isEqualTo(100);
    assertThat(result.validRows()).isEqualTo(4900);
    assertThat(result.errors()).hasSize(100);

    // rowIndex가 배치 로컬(1..100)이 아니라 전역(2001~2100)이어야 함 — 두 번째 배치(rowIndexBase=2000)에서 발생
    assertThat(result.errors().get(0).rowNumber()).isEqualTo(2001);
    assertThat(result.errors().get(result.errors().size() - 1).rowNumber()).isEqualTo(2100);
  }

  /** validateImport가 spill한 임시 파일을 finally에서 삭제하는지 확인한다(누수 방지). */
  @Test
  void validateImport_afterCall_cleansUpTempFile() throws Exception {
    // Given
    String csvContent = "name,age,email\nAlice,30,a@x.com\nBob,25,b@x.com\n";
    MockMultipartFile file =
        new MockMultipartFile(
            "file", "small.csv", "text/csv", csvContent.getBytes(StandardCharsets.UTF_8));
    List<ColumnMappingEntry> mappings =
        List.of(new ColumnMappingEntry("name", "name"), new ColumnMappingEntry("age", "age"));

    java.nio.file.Path tempDir =
        java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "firehub-imports");
    java.nio.file.Files.createDirectories(tempDir);
    long before;
    try (var stream = java.nio.file.Files.list(tempDir)) {
      before = stream.count();
    }

    // When
    dataImportService.validateImport(testDatasetId, file, mappings);

    // Then - 호출 직후 temp 디렉터리에 남은 파일 수가 늘어나지 않아야 한다
    long after;
    try (var stream = java.nio.file.Files.list(tempDir)) {
      after = stream.count();
    }
    assertThat(after).isEqualTo(before);
  }

  @Test
  void getImportsByDatasetId_withAuditLogs_returnsImports() {
    // Given - directly log an import audit entry
    Map<String, Object> metadata =
        Map.of(
            "fileName", "test.csv",
            "fileSize", 1024,
            "fileType", "CSV",
            "totalRows", 10,
            "successRows", 10,
            "errorRows", 0);

    auditLogService.log(
        testUserId,
        "Test User",
        "IMPORT",
        "dataset",
        String.valueOf(testDatasetId),
        "파일 임포트: test.csv",
        null,
        null,
        "SUCCESS",
        null,
        metadata);

    // When
    List<ImportResponse> imports = dataImportService.getImportsByDatasetId(testDatasetId);

    // Then
    assertThat(imports).hasSize(1);
    assertThat(imports.get(0).fileName()).isEqualTo("test.csv");
    assertThat(imports.get(0).status()).isEqualTo("COMPLETED");
    assertThat(imports.get(0).successRows()).isEqualTo(10);
  }

  @Test
  void getImportById_returnsImport() {
    // Given
    Map<String, Object> metadata =
        Map.of(
            "fileName", "test.csv",
            "fileSize", 2048,
            "fileType", "CSV",
            "totalRows", 5,
            "successRows", 5,
            "errorRows", 0);

    Long auditId =
        auditLogService.log(
            testUserId,
            "Test User",
            "IMPORT",
            "dataset",
            String.valueOf(testDatasetId),
            "파일 임포트: test.csv",
            null,
            null,
            "SUCCESS",
            null,
            metadata);

    // When
    ImportResponse retrieved = dataImportService.getImportById(testDatasetId, auditId);

    // Then
    assertThat(retrieved.id()).isEqualTo(auditId);
    assertThat(retrieved.fileName()).isEqualTo("test.csv");
    assertThat(retrieved.status()).isEqualTo("COMPLETED");
  }

  @Test
  void getImportById_wrongDataset_throwsException() {
    // Given
    Map<String, Object> metadata =
        Map.of("fileName", "test.csv", "fileSize", 100, "fileType", "CSV");

    Long auditId =
        auditLogService.log(
            testUserId,
            "Test User",
            "IMPORT",
            "dataset",
            String.valueOf(testDatasetId),
            "파일 임포트: test.csv",
            null,
            null,
            "SUCCESS",
            null,
            metadata);

    // Create another dataset
    List<DatasetColumnRequest> columns =
        List.of(new DatasetColumnRequest("col1", "Col1", "TEXT", null, true, false, null));
    DatasetDetailResponse anotherDataset =
        datasetService.createDataset(
            new CreateDatasetRequest(
                "Another Dataset", "another_dataset", null, null, "TABLE", "SOURCE", columns, null),
            testUserId);

    // When/Then
    assertThatThrownBy(() -> dataImportService.getImportById(anotherDataset.id(), auditId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not belong to this dataset");
  }

  @Test
  void exportDataset_csv_generatesValidCsv() throws Exception {
    // Given - directly process import synchronously for test
    String filePath =
        createTempCsvFile("name,age,email\nAlice,30,alice@example.com\nBob,25,bob@example.com");
    dataImportService.processImport(
        "test-job-id",
        testDatasetId,
        filePath,
        "",
        "",
        "test.csv",
        100L,
        "CSV",
        testUserId,
        "Test User",
        "",
        "",
        "APPEND");

    // When
    ExportRequest request = new ExportRequest(ExportFormat.CSV, null, null, null);
    ExportResult result =
        dataExportService.exportDataset(
            testDatasetId, request, testUserId, "testuser", "127.0.0.1", "test");

    // Then
    assertThat(result.streamingBody()).isNotNull();
    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
    result.streamingBody().writeTo(baos);
    String csvOutput = baos.toString(StandardCharsets.UTF_8);
    assertThat(csvOutput).contains("Name");
    assertThat(csvOutput).contains("Age");
    assertThat(csvOutput).contains("Email");
  }

  /**
   * processImport가 Files.newInputStream() 스트리밍 경로를 통해 대용량 파일을 byte[] 로드 없이 파싱·적재하는지 검증한다. 실제 OOM 재현
   * 없이도 스트리밍 코드 경로가 정상 동작함을 확인한다.
   */
  @Test
  void processImport_csvViaStreaming_insertsRowsWithoutLoadingAllBytes() throws Exception {
    // Given: 1000행 CSV 파일을 임시 파일로 생성 (스트리밍 경로 검증)
    StringBuilder csv = new StringBuilder("name,age,email\n");
    for (int i = 0; i < 1000; i++) {
      csv.append("User")
          .append(i)
          .append(",")
          .append(20 + i % 80)
          .append(",user")
          .append(i)
          .append("@example.com\n");
    }
    String filePath = createTempCsvFile(csv.toString());

    // When: processImport 직접 호출 (Jobrunr 잡 메서드, 스트리밍 InputStream 사용)
    dataImportService.processImport(
        "stream-test-job-id",
        testDatasetId,
        filePath,
        "",
        "",
        "stream_test.csv",
        (long) csv.length(),
        "CSV",
        testUserId,
        "Test User",
        "",
        "",
        "APPEND");

    // Then: 1000행 전부 적재됐는지 확인
    var count =
        dsl.fetchCount(
            dsl.select()
                .from(
                    org.jooq.impl.DSL.table(
                        org.jooq.impl.DSL.name("data", "import_test_dataset"))));
    assertThat(count).isEqualTo(1000);
  }

  /**
   * .xls 확장자 파일이 UnsupportedFileTypeException 없이 수락되는지 검증한다. XLS 허용 화이트리스트 추가(Task 6) 이후
   * importFile이 PENDING 상태를 반환해야 한다.
   */
  @Test
  void importFile_xlsExtension_isAccepted() throws Exception {
    // Given: HSSFWorkbook으로 단순 XLS 파일(헤더 "name", 데이터 행 "alice") 생성
    HSSFWorkbook workbook = new HSSFWorkbook();
    var sheet = workbook.createSheet("Sheet1");
    var header = sheet.createRow(0);
    header.createCell(0).setCellValue("name");
    var dataRow = sheet.createRow(1);
    dataRow.createCell(0).setCellValue("alice");

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    workbook.write(baos);
    workbook.close();
    byte[] xlsBytes = baos.toByteArray();

    MockMultipartFile file =
        new MockMultipartFile("file", "sample.xls", "application/vnd.ms-excel", xlsBytes);

    // When: importFile 호출 시 UnsupportedFileTypeException이 발생하지 않아야 함
    ImportStartResponse response =
        dataImportService.importFile(
            testDatasetId, file, null, testUserId, "Test User", "127.0.0.1", "TestAgent");

    // Then: 정상적으로 잡이 등록되어 PENDING 상태 반환
    assertThat(response).isNotNull();
    assertThat(response.jobId()).isNotNull();
    assertThat(response.status()).isEqualTo("PENDING");
  }

  /**
   * 운영 재현 회귀 테스트(#281): 파일 안에 같은 PK가 중복된 데이터를 UPSERT 모드로 임포트해도 실패하지 않아야 한다.
   *
   * <p>수정 전에는 중복 PK 행이 한 배치의 INSERT ... ON CONFLICT DO UPDATE 안에 들어가 Postgres "cannot affect row a
   * second time" 오류로 배치 전체가 실패했다. 수정 후에는 last-write-wins로 dedup되어 정상 적재되고, 같은 PK는 파일의 마지막 값이 남아야
   * 한다.
   */
  @Test
  void processImport_upsertWithWithinFileDuplicatePk_collapsesLastWins() throws Exception {
    // Given: PK 컬럼(code)을 가진 데이터셋 생성
    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest("code", "Code", "TEXT", null, false, false, null, true),
            new DatasetColumnRequest("label", "Label", "TEXT", null, true, false, null, false));
    DatasetDetailResponse pkDataset =
        datasetService.createDataset(
            new CreateDatasetRequest(
                "Upsert Dedup Dataset",
                "upsert_dedup_dataset",
                "UPSERT 중복 PK 회귀 테스트용",
                null,
                "TABLE", "SOURCE",
                columns,
                null),
            testUserId);

    // 같은 code(A001)가 두 번 등장 — UPSERT 시 마지막 값(second)이 이겨야 한다
    String csv =
        """
        code,label
        A001,first
        A002,other
        A001,second
        """;
    String filePath = createTempCsvFile(csv);

    // When: UPSERT 모드로 processImport 직접 호출 (수정 전이면 여기서 배치 실패)
    dataImportService.processImport(
        "upsert-dedup-job-id",
        pkDataset.id(),
        filePath,
        "",
        "",
        "upsert_dedup.csv",
        (long) csv.length(),
        "CSV",
        testUserId,
        "Test User",
        "",
        "",
        "UPSERT");

    // Then: 중복이 접혀 2행만 적재되고, A001의 label은 마지막 값 'second'여야 한다
    var rows =
        dsl.select()
            .from(org.jooq.impl.DSL.table(org.jooq.impl.DSL.name("data", "upsert_dedup_dataset")))
            .fetch();
    assertThat(rows).hasSize(2);

    String a001Label =
        rows.stream()
            .filter(r -> "A001".equals(r.get("code")))
            .map(r -> (String) r.get("label"))
            .findFirst()
            .orElse(null);
    assertThat(a001Label).isEqualTo("second");
  }

  /**
   * 운영 재현 회귀 테스트(#281): REPLACE 모드도 파일 내 중복 PK가 있으면 truncate 후 plain insert 단계에서 unique index를 위반해
   * 실패한다. UPSERT와 동일 근본 원인이며, REPLACE는 파일이 새 진실이므로 last-write-wins dedup이 의미상 타당하다.
   */
  @Test
  void processImport_replaceWithWithinFileDuplicatePk_collapsesLastWins() throws Exception {
    // Given: PK 컬럼(code)을 가진 데이터셋 생성
    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest("code", "Code", "TEXT", null, false, false, null, true),
            new DatasetColumnRequest("label", "Label", "TEXT", null, true, false, null, false));
    DatasetDetailResponse pkDataset =
        datasetService.createDataset(
            new CreateDatasetRequest(
                "Replace Dedup Dataset",
                "replace_dedup_dataset",
                "REPLACE 중복 PK 회귀 테스트용",
                null,
                "TABLE", "SOURCE",
                columns,
                null),
            testUserId);

    // 같은 code(A001)가 두 번 등장 — REPLACE 시 마지막 값(second)이 남아야 한다
    String csv =
        """
        code,label
        A001,first
        A002,other
        A001,second
        """;
    String filePath = createTempCsvFile(csv);

    // When: REPLACE 모드로 processImport 직접 호출 (수정 전이면 unique index 위반으로 실패)
    dataImportService.processImport(
        "replace-dedup-job-id",
        pkDataset.id(),
        filePath,
        "",
        "",
        "replace_dedup.csv",
        (long) csv.length(),
        "CSV",
        testUserId,
        "Test User",
        "",
        "",
        "REPLACE");

    // Then: 중복이 접혀 2행만 남고, A001의 label은 마지막 값 'second'여야 한다
    var rows =
        dsl.select()
            .from(org.jooq.impl.DSL.table(org.jooq.impl.DSL.name("data", "replace_dedup_dataset")))
            .fetch();
    assertThat(rows).hasSize(2);

    String a001Label =
        rows.stream()
            .filter(r -> "A001".equals(r.get("code")))
            .map(r -> (String) r.get("label"))
            .findFirst()
            .orElse(null);
    assertThat(a001Label).isEqualTo("second");
  }

  // ---------------------------------------------------------------------------
  // Task 8: processImport 청크 스트리밍(OOM 회피) 회귀 테스트
  // ---------------------------------------------------------------------------

  /**
   * 피크 메모리 회귀 테스트: processImport가 전체 파일(4500행)을 한 번에 메모리에 올려 단일 validate() 호출로 처리하지 않고,
   * BATCH_SIZE(2000) 이하 크기의 배치로 나누어 여러 번 validate를 호출하는지 검증한다. DataValidationService.validate를
   * 스파이해 각 호출의 rows 크기를 기록 — 기존(전체 로드) 구현이면 단일 호출에 4500행이 전달되어 이 테스트가 실패한다.
   */
  @Test
  void processImport_appendStreaming_neverPassesMoreThanBatchSizeRowsAtOnce() throws Exception {
    // Given: BATCH_SIZE(2000)를 넘는 4500행 CSV
    StringBuilder csv = new StringBuilder("name,age,email\n");
    for (int i = 0; i < 4500; i++) {
      csv.append("User")
          .append(i)
          .append(",")
          .append(20 + i % 60)
          .append(",user")
          .append(i)
          .append("@example.com\n");
    }
    String filePath = createTempCsvFile(csv.toString());

    List<Integer> batchSizesSeen = new ArrayList<>();
    Mockito.doAnswer(
            invocation -> {
              List<?> rows = invocation.getArgument(0);
              batchSizesSeen.add(rows.size());
              return invocation.callRealMethod();
            })
        .when(validationService)
        .validate(Mockito.anyList(), Mockito.anyList(), Mockito.anyInt());

    // When
    dataImportService.processImport(
        "peak-batch-job-id",
        testDatasetId,
        filePath,
        "",
        "",
        "peak_batch.csv",
        (long) csv.length(),
        "CSV",
        testUserId,
        "Test User",
        "",
        "",
        "APPEND");

    // Then: validate가 여러 번(배치 단위) 호출되고, 매 호출의 행 수가 BATCH_SIZE(2000) 이하여야 한다
    assertThat(batchSizesSeen).isNotEmpty();
    assertThat(batchSizesSeen.size()).isGreaterThan(1);
    assertThat(batchSizesSeen).allMatch(size -> size <= 2000);
  }

  /**
   * APPEND 스트리밍: BATCH_SIZE(2000)보다 많은 행을 가진 CSV를 임포트해도 유효 행 전부가 적재되고, progress 콜백이 여러 번
   * 호출되며 processedRows가 단조 증가(non-decreasing)하는지 검증한다. 배치 경계를 넘어 진행률이 리셋되지 않아야 한다.
   */
  @Test
  void processImport_appendStreaming_largeCsv_insertsAllRowsWithMonotonicProgress()
      throws Exception {
    // Given: BATCH_SIZE(2000)를 넘는 4500행 CSV
    StringBuilder csv = new StringBuilder("name,age,email\n");
    for (int i = 0; i < 4500; i++) {
      csv.append("User")
          .append(i)
          .append(",")
          .append(20 + i % 60)
          .append(",user")
          .append(i)
          .append("@example.com\n");
    }
    String filePath = createTempCsvFile(csv.toString());

    List<Integer> processedRowsSeen = new ArrayList<>();
    Mockito.doAnswer(
            invocation -> {
              Map<String, Object> metadata = invocation.getArgument(4);
              Object processed = metadata.get("processedRows");
              if (processed instanceof Integer p) {
                processedRowsSeen.add(p);
              }
              return invocation.callRealMethod();
            })
        .when(asyncJobService)
        .updateProgress(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyInt(),
            Mockito.anyString(),
            Mockito.anyMap());

    // When
    dataImportService.processImport(
        "append-streaming-job-id",
        testDatasetId,
        filePath,
        "",
        "",
        "large_append.csv",
        (long) csv.length(),
        "CSV",
        testUserId,
        "Test User",
        "",
        "",
        "APPEND");

    // Then: 전체 행이 적재되고, 여러 번의 progress 콜백이 단조 증가했어야 한다
    var count =
        dsl.fetchCount(
            dsl.select()
                .from(
                    org.jooq.impl.DSL.table(
                        org.jooq.impl.DSL.name("data", "import_test_dataset"))));
    assertThat(count).isEqualTo(4500);

    assertThat(processedRowsSeen.size()).isGreaterThan(1);
    for (int i = 1; i < processedRowsSeen.size(); i++) {
      assertThat(processedRowsSeen.get(i)).isGreaterThanOrEqualTo(processedRowsSeen.get(i - 1));
    }
  }

  /**
   * UPSERT 스트리밍: 배치 경계(BATCH_SIZE=2000)를 넘나드는 위치에 동일 PK가 등장해도 staging 테이블 기반 promote가
   * last-write-wins로 정확히 처리하는지 검증한다. 첫 배치 끝(code A001)과 마지막 배치(code A001)에 값이 다르게 등장.
   */
  @Test
  void processImport_upsertStreaming_duplicatePkAcrossBatches_lastWriteWinsAndDropsStaging()
      throws Exception {
    // Given: PK 컬럼(code)을 가진 데이터셋
    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest("code", "Code", "TEXT", null, false, false, null, true),
            new DatasetColumnRequest("label", "Label", "TEXT", null, true, false, null, false));
    DatasetDetailResponse pkDataset =
        datasetService.createDataset(
            new CreateDatasetRequest(
                "Upsert Streaming Dataset",
                "upsert_streaming_dataset",
                "UPSERT 배치 경계 dedup 테스트용",
                null,
                "TABLE", "SOURCE",
                columns,
                null),
            testUserId);

    // 2500행: 1행(A001,first) + 2498개 다른 코드 + 마지막 1행(A001,last) — A001이 첫 배치와 이후에 걸쳐 등장
    StringBuilder csv = new StringBuilder("code,label\n");
    csv.append("A001,first\n");
    for (int i = 0; i < 2498; i++) {
      csv.append("CODE").append(i).append(",value").append(i).append("\n");
    }
    csv.append("A001,last\n");
    String filePath = createTempCsvFile(csv.toString());

    // When
    dataImportService.processImport(
        "upsert-streaming-job-id",
        pkDataset.id(),
        filePath,
        "",
        "",
        "upsert_streaming.csv",
        (long) csv.length(),
        "CSV",
        testUserId,
        "Test User",
        "",
        "",
        "UPSERT");

    // Then: A001은 마지막 값('last')이 남고, 총 2499개 고유 row(2498 CODE + A001)가 있어야 한다
    var rows =
        dsl.select()
            .from(
                org.jooq.impl.DSL.table(org.jooq.impl.DSL.name("data", "upsert_streaming_dataset")))
            .fetch();
    assertThat(rows).hasSize(2499);

    String a001Label =
        rows.stream()
            .filter(r -> "A001".equals(r.get("code")))
            .map(r -> (String) r.get("label"))
            .findFirst()
            .orElse(null);
    assertThat(a001Label).isEqualTo("last");

    // staging 테이블이 정리되었는지 확인 (stg_import_* 패턴의 테이블이 남아있지 않아야 함)
    var stagingTables =
        dsl.fetch(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = 'data' AND"
                + " table_name LIKE 'stg_import_%'");
    assertThat(stagingTables).isEmpty();
  }

  /**
   * REPLACE(PK 有) 스트리밍: staging에 적재 후 promoteStagingToReplace로 truncate+insert가 원자적으로 처리되고,
   * 완료 후 staging 테이블이 정리되는지 검증한다.
   */
  @Test
  void processImport_replaceWithPkStreaming_truncateAndPromoteAndDropsStaging() throws Exception {
    // Given
    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest("code", "Code", "TEXT", null, false, false, null, true),
            new DatasetColumnRequest("label", "Label", "TEXT", null, true, false, null, false));
    DatasetDetailResponse pkDataset =
        datasetService.createDataset(
            new CreateDatasetRequest(
                "Replace Streaming Dataset",
                "replace_streaming_dataset",
                "REPLACE 스트리밍 테스트용",
                null,
                "TABLE", "SOURCE",
                columns,
                null),
            testUserId);

    // 기존 데이터 1건 미리 적재 — REPLACE 후에는 사라져야 한다
    String seedCsv = "code,label\nOLD001,old-value\n";
    String seedFilePath = createTempCsvFile(seedCsv);
    dataImportService.processImport(
        "seed-job-id",
        pkDataset.id(),
        seedFilePath,
        "",
        "",
        "seed.csv",
        (long) seedCsv.length(),
        "CSV",
        testUserId,
        "Test User",
        "",
        "",
        "APPEND");

    String csv = "code,label\nA001,first\nA002,second\n";
    String filePath = createTempCsvFile(csv);

    // When
    dataImportService.processImport(
        "replace-streaming-job-id",
        pkDataset.id(),
        filePath,
        "",
        "",
        "replace_streaming.csv",
        (long) csv.length(),
        "CSV",
        testUserId,
        "Test User",
        "",
        "",
        "REPLACE");

    // Then: 기존 OLD001은 사라지고 새 2행만 존재
    var rows =
        dsl.select()
            .from(
                org.jooq.impl.DSL.table(
                    org.jooq.impl.DSL.name("data", "replace_streaming_dataset")))
            .fetch();
    assertThat(rows).hasSize(2);
    assertThat(rows.stream().map(r -> (String) r.get("code")))
        .containsExactlyInAnyOrder("A001", "A002");

    var stagingTables =
        dsl.fetch(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = 'data' AND"
                + " table_name LIKE 'stg_import_%'");
    assertThat(stagingTables).isEmpty();
  }

  /**
   * BLOCKER 회귀 테스트: REPLACE(PK 無) 모드에서 파일 전체 행이 검증 실패하면, lazy truncate가 발동하지 않아 기존 데이터가
   * 보존되어야 한다. 스트리밍 전환 전 원자적 truncate+insert 로직이 "전량 무효" 케이스에서도 대상 테이블을 건드리지 않아야 한다.
   */
  @Test
  void processImport_replaceNoPkAllRowsInvalid_preservesExistingData() throws Exception {
    // Given: PK 없는 데이터셋에 기존 데이터 1건 시딩
    String seedCsv = "name,age,email\nAlice,30,alice@example.com\n";
    String seedFilePath = createTempCsvFile(seedCsv);
    dataImportService.processImport(
        "seed-no-pk-job-id",
        testDatasetId,
        seedFilePath,
        "",
        "",
        "seed.csv",
        (long) seedCsv.length(),
        "CSV",
        testUserId,
        "Test User",
        "",
        "",
        "APPEND");

    // 전량 무효(age가 필수 아니지만 name이 필수 — 비워서 위반)
    String invalidCsv = "name,age,email\n,20,a@x.com\n,21,b@x.com\n";
    String filePath = createTempCsvFile(invalidCsv);

    // When: REPLACE 모드로 임포트 (전량 실패해야 함)
    dataImportService.processImport(
        "replace-no-pk-all-invalid-job-id",
        testDatasetId,
        filePath,
        "",
        "",
        "invalid.csv",
        (long) invalidCsv.length(),
        "CSV",
        testUserId,
        "Test User",
        "",
        "",
        "REPLACE");

    // Then: 기존 데이터(Alice)가 여전히 존재해야 한다 — truncate가 발동하지 않았어야 함
    var rows =
        dsl.select()
            .from(
                org.jooq.impl.DSL.table(org.jooq.impl.DSL.name("data", "import_test_dataset")))
            .fetch();
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("name")).isEqualTo("Alice");
  }

  /** REPLACE(PK 無) 기존 동작 보존 회귀: 정상 데이터는 여전히 truncate 후 정상 적재된다. */
  @Test
  void processImport_replaceNoPk_normalData_truncatesAndInserts() throws Exception {
    // Given: 기존 데이터 시딩
    String seedCsv = "name,age,email\nOld,99,old@example.com\n";
    String seedFilePath = createTempCsvFile(seedCsv);
    dataImportService.processImport(
        "seed-no-pk-normal-job-id",
        testDatasetId,
        seedFilePath,
        "",
        "",
        "seed.csv",
        (long) seedCsv.length(),
        "CSV",
        testUserId,
        "Test User",
        "",
        "",
        "APPEND");

    String csv = "name,age,email\nAlice,30,alice@example.com\nBob,25,bob@example.com\n";
    String filePath = createTempCsvFile(csv);

    // When
    dataImportService.processImport(
        "replace-no-pk-normal-job-id",
        testDatasetId,
        filePath,
        "",
        "",
        "replace.csv",
        (long) csv.length(),
        "CSV",
        testUserId,
        "Test User",
        "",
        "",
        "REPLACE");

    // Then: Old는 사라지고 Alice/Bob만 존재
    var rows =
        dsl.select()
            .from(
                org.jooq.impl.DSL.table(org.jooq.impl.DSL.name("data", "import_test_dataset")))
            .fetch();
    assertThat(rows).hasSize(2);
    assertThat(rows.stream().map(r -> (String) r.get("name")))
        .containsExactlyInAnyOrder("Alice", "Bob");
  }

  // 참고: UPSERT NULL-PK fail-fast 경로(validatePrimaryKeys의 NULL 체크)는 DatasetService가
  // "Primary key column cannot be nullable"로 PK 컬럼의 isNullable=true 생성 자체를 막기 때문에,
  // 일반 validate()가 먼저 필수값 누락으로 걸러 실질적으로 도달 불가능한 방어 코드다(기존 코드도 동일).
  // 이 제약으로 인해 공개 API로는 이 경로를 재현하는 테스트를 구성할 수 없어 별도 테스트를 추가하지 않는다.

  private String createTempCsvFile(String content) throws Exception {
    java.nio.file.Path tempDir =
        java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "firehub-test");
    java.nio.file.Files.createDirectories(tempDir);
    java.nio.file.Path tempFile = java.nio.file.Files.createTempFile(tempDir, "test-", ".csv");
    java.nio.file.Files.writeString(tempFile, content, StandardCharsets.UTF_8);
    return tempFile.toString();
  }
}
