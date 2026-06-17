package com.smartfirehub.dataimport.service;

import static com.smartfirehub.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.dataimport.dto.ExportFormat;
import com.smartfirehub.dataimport.dto.ExportRequest;
import com.smartfirehub.dataimport.dto.ExportResult;
import com.smartfirehub.dataimport.dto.ImportResponse;
import com.smartfirehub.dataimport.dto.ImportStartResponse;
import com.smartfirehub.dataimport.exception.UnsupportedFileTypeException;
import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetDetailResponse;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.support.IntegrationTestBase;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class DataImportServiceTest extends IntegrationTestBase {

  @Autowired private DataImportService dataImportService;

  @Autowired private DataExportService dataExportService;

  @Autowired private DatasetService datasetService;

  @Autowired private AuditLogService auditLogService;

  @Autowired private DSLContext dsl;

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

  private String createTempCsvFile(String content) throws Exception {
    java.nio.file.Path tempDir =
        java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "firehub-test");
    java.nio.file.Files.createDirectories(tempDir);
    java.nio.file.Path tempFile = java.nio.file.Files.createTempFile(tempDir, "test-", ".csv");
    java.nio.file.Files.writeString(tempFile, content, StandardCharsets.UTF_8);
    return tempFile.toString();
  }
}
