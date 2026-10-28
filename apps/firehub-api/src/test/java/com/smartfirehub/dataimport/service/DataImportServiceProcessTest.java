package com.smartfirehub.dataimport.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataimport.exception.FileUnreadableException;
import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetDetailResponse;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.job.service.AsyncJobService;
import com.smartfirehub.support.IntegrationTestBase;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Task2: 임포트 잡(processImport)이 검증(Pass1, fail-fast)과 삽입(Pass2)을 완전히 분리해, 오류가 하나라도 있으면 부분 적재 없이 0행으로
 * 실패해야 함을 검증한다. 기존(옛) 계약은 "일부 유효 행은 보존"(#7/#168/#169, APPEND 부분 성공)이었으나, 이 잡의 fail-fast 재설계로 뒤집힌다 —
 * 이 계약 변경 자체가 이 테스트 클래스의 핵심 목적이다.
 */
@Transactional
class DataImportServiceProcessTest extends IntegrationTestBase {

  @Autowired private DataImportService dataImportService;

  @Autowired private DatasetService datasetService;

  @Autowired private DSLContext dsl;

  @MockitoSpyBean private AsyncJobService asyncJobService;

  private Long testUserId;

  @BeforeEach
  void setUp() {
    testUserId =
        dsl.insertInto(com.smartfirehub.jooq.Tables.USER)
            .set(com.smartfirehub.jooq.Tables.USER.USERNAME, "process_test_user")
            .set(com.smartfirehub.jooq.Tables.USER.PASSWORD, "password")
            .set(com.smartfirehub.jooq.Tables.USER.NAME, "Process Test User")
            .set(com.smartfirehub.jooq.Tables.USER.EMAIL, "process_test@example.com")
            .returning(com.smartfirehub.jooq.Tables.USER.ID)
            .fetchOne()
            .getId();
  }

  private Long createDatasetWithBigintColumn(String tableName) {
    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest("name", "Name", "TEXT", null, false, false, null),
            new DatasetColumnRequest("amount", "Amount", "INTEGER", null, false, false, null));

    DatasetDetailResponse dataset =
        datasetService.createDataset(
            new CreateDatasetRequest(
                "Process Fail-Fast Dataset " + tableName,
                tableName,
                "fail-fast 회귀 테스트용",
                null,
                "TABLE",
                "SOURCE",
                columns,
                null),
            testUserId);
    return dataset.id();
  }

  /** badRowIndex(1-based, 헤더 제외 데이터 행 기준)가 -1이면 전량 유효, 그 외에는 해당 행의 amount를 비숫자 값으로 채운다. */
  private Path writeCsv(int rows, int badRowIndex) throws Exception {
    StringBuilder csv = new StringBuilder("name,amount\n");
    for (int i = 1; i <= rows; i++) {
      String amount = (i == badRowIndex) ? "notanumber" : String.valueOf(1000 + i);
      csv.append("User").append(i).append(",").append(amount).append("\n");
    }
    Path tempDir = Path.of(System.getProperty("java.io.tmpdir"), "firehub-test");
    Files.createDirectories(tempDir);
    Path tempFile = Files.createTempFile(tempDir, "process-test-", ".csv");
    Files.writeString(tempFile, csv.toString(), StandardCharsets.UTF_8);
    return tempFile;
  }

  private long countRows(String tableName) {
    return dsl.fetchCount(
        dsl.select().from(org.jooq.impl.DSL.table(org.jooq.impl.DSL.name("data", tableName))));
  }

  @Test
  void append_failsFast_noPartialInsert_whenErrorInLaterBatch() throws Exception {
    // given: BIGINT(INTEGER) 컬럼 'amount' 데이터셋, 5000행 중 4001번째 행만 비숫자(불량) — 3번째 배치(4000~)에서 오류
    String tableName = "process_failfast_dataset";
    Long datasetId = createDatasetWithBigintColumn(tableName);
    Path csv = writeCsv(5000, 4001);

    // when: APPEND 모드로 processImport 직접 호출 (Jobrunr 잡 메서드의 실제 테스트 진입점)
    dataImportService.processImport(
        "process-failfast-job-id",
        datasetId,
        csv.toString(),
        "",
        "",
        "process_failfast.csv",
        Files.size(csv),
        "CSV",
        testUserId,
        "Test User",
        "",
        "",
        "APPEND",
        1L);

    // then: 첫 오류에서 중단 → target 테이블에 0행(부분 적재 없음), 잡은 실패 처리
    assertThat(countRows(tableName)).isZero();

    ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
    Mockito.verify(asyncJobService).failJob(Mockito.eq("process-failfast-job-id"), msg.capture());
    // 매핑 없는(no-mapping) 경로이므로 validate()의 영문 오류 메시지("Row 4001: ...")를 통해
    // rowIndexBase가 파일 전역 기준으로 정확히 threading됐음을 확인한다.
    assertThat(msg.getValue()).contains("4001");
  }

  /** 임포트 잡 경로에서도 엑셀이 아닌 .xlsx 는 한국어 안내로 실패해야 한다(WD-204). 예전엔 범용 「관리자에게 문의」 문구로 끝나 원인을 알 수 없었다. */
  @Test
  void xlsx_notExcel_failsJobWithKoreanMessage() throws Exception {
    String tableName = "process_fake_xlsx_dataset";
    Long datasetId = createDatasetWithBigintColumn(tableName);
    Path tempDir = Path.of(System.getProperty("java.io.tmpdir"), "firehub-test");
    Files.createDirectories(tempDir);
    Path fake = Files.createTempFile(tempDir, "process-fake-", ".xlsx");
    Files.writeString(fake, "not an excel file", StandardCharsets.UTF_8);

    dataImportService.processImport(
        "process-fake-xlsx-job-id",
        datasetId,
        fake.toString(),
        "",
        "",
        "fake.xlsx",
        Files.size(fake),
        "XLSX",
        testUserId,
        "Test User",
        "",
        "",
        "APPEND",
        1L);

    ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
    Mockito.verify(asyncJobService).failJob(Mockito.eq("process-fake-xlsx-job-id"), msg.capture());
    assertThat(msg.getValue()).isEqualTo(FileUnreadableException.EXCEL_UNREADABLE);
    assertThat(countRows(tableName)).isZero();
  }

  @Test
  void append_insertsAll_whenAllValid() throws Exception {
    String tableName = "process_allvalid_dataset";
    Long datasetId = createDatasetWithBigintColumn(tableName);
    Path csv = writeCsv(5000, -1);

    dataImportService.processImport(
        "process-allvalid-job-id",
        datasetId,
        csv.toString(),
        "",
        "",
        "process_allvalid.csv",
        Files.size(csv),
        "CSV",
        testUserId,
        "Test User",
        "",
        "",
        "APPEND",
        1L);

    assertThat(countRows(tableName)).isEqualTo(5000);
    Mockito.verify(asyncJobService, Mockito.never())
        .failJob(Mockito.anyString(), Mockito.anyString());
  }

  /**
   * WD-255: CSV 의 빈 줄·쉼표만 있는 줄은 모든 값이 비어 있는 행으로 적재되지 않고 건너뛴다. 건너뛴 수는 잡 결과와 감사 로그의 skippedRows 로 남아
   * 화면에 「빈 행 건너뜀」으로 보인다.
   */
  @Test
  void append_skipsBlankAndCommaOnlyLines_reportsSkippedRows() throws Exception {
    String tableName = "process_blank_rows_dataset";
    Long datasetId = createDatasetWithBigintColumn(tableName);
    Path tempDir = Path.of(System.getProperty("java.io.tmpdir"), "firehub-test");
    Files.createDirectories(tempDir);
    Path csv = Files.createTempFile(tempDir, "process-blank-", ".csv");
    Files.writeString(csv, "name,amount\nUser1,1001\n\n,\nUser2,1002\n\n", StandardCharsets.UTF_8);

    dataImportService.processImport(
        "process-blank-job-id",
        datasetId,
        csv.toString(),
        "",
        "",
        "process_blank.csv",
        Files.size(csv),
        "CSV",
        testUserId,
        "Test User",
        "",
        "",
        "APPEND",
        1L);

    // 데이터 2행만 적재되고, 모든 값이 NULL 인 행은 없다
    assertThat(countRows(tableName)).isEqualTo(2);
    assertThat(
            dsl.fetchCount(
                dsl.select()
                    .from(org.jooq.impl.DSL.table(org.jooq.impl.DSL.name("data", tableName)))
                    .where(org.jooq.impl.DSL.field(org.jooq.impl.DSL.name("name")).isNull())))
        .isZero();

    // 잡 결과: 전체·성공은 데이터 행 기준, 건너뛴 빈 행 3개
    @SuppressWarnings("unchecked")
    ArgumentCaptor<java.util.Map<String, Object>> result =
        ArgumentCaptor.forClass(java.util.Map.class);
    Mockito.verify(asyncJobService)
        .completeJob(Mockito.eq("process-blank-job-id"), result.capture());
    assertThat(result.getValue())
        .containsEntry("totalRows", 2)
        .containsEntry("successRows", 2)
        .containsEntry("skippedRows", 3);

    // 감사 로그 metadata 에도 같은 값이 남는다
    String metadata =
        dsl.fetchValue(
                "SELECT metadata->>'skippedRows' FROM audit_log WHERE resource_id = ? AND action_type"
                    + " = 'IMPORT' ORDER BY id DESC LIMIT 1",
                String.valueOf(datasetId))
            .toString();
    assertThat(metadata).isEqualTo("3");
  }

  // ---- WD-160: 위치(GEOMETRY) 컬럼 임포트 ----

  private Long createGeometryDataset(String tableName) {
    DatasetDetailResponse dataset =
        datasetService.createDataset(
            new CreateDatasetRequest(
                "Geometry Import " + tableName,
                tableName,
                "WD-160 GEOMETRY 임포트 회귀",
                null,
                "TABLE",
                "SOURCE",
                List.of(
                    new DatasetColumnRequest("name", "Name", "TEXT", null, true, false, null),
                    new DatasetColumnRequest("geom", "Geom", "GEOMETRY", null, true, false, null)),
                null),
            testUserId);
    return dataset.id();
  }

  private Path writeGeometryCsv(String body) throws Exception {
    Path tempDir = Path.of(System.getProperty("java.io.tmpdir"), "firehub-test");
    Files.createDirectories(tempDir);
    Path tempFile = Files.createTempFile(tempDir, "geom-import-", ".csv");
    Files.writeString(tempFile, "name,geom\n" + body, StandardCharsets.UTF_8);
    return tempFile;
  }

  private void runImport(String jobId, Long datasetId, Path csv, String mode) throws Exception {
    dataImportService.processImport(
        jobId,
        datasetId,
        csv.toString(),
        "",
        "",
        "geom.csv",
        Files.size(csv),
        "CSV",
        testUserId,
        "Test User",
        "",
        "",
        mode,
        1L);
  }

  /** 이름 → ST_AsText(geom) (NULL 이면 null) — 위치 값이 GeoJSON 에서 실제 geometry 로 들어갔는지 본다. */
  private java.util.Map<String, String> geometries(String tableName) {
    java.util.Map<String, String> out = new java.util.HashMap<>();
    dsl.fetch("SELECT name, ST_AsText(geom) AS wkt FROM data.\"" + tableName + "\"")
        .forEach(r -> out.put(r.get("name", String.class), r.get("wkt", String.class)));
    return out;
  }

  private static final String GEOM_ROWS =
      "서울,\"{\"\"type\"\":\"\"Point\"\",\"\"coordinates\"\":[127,37.5]}\"\n"
          + "부산,\"{\"\"type\"\":\"\"Point\"\",\"\"coordinates\"\":[129,35.1]}\"\n"
          + "빈값,\n";

  @Test
  void append_insertsGeometryFromGeoJson() throws Exception {
    // 수정 전: GeoJSON 문자열이 varchar 로 묶여 "column geom is of type geometry but expression is of
    // type character varying" 로 잡이 실패하고 0행이었다
    String tableName = "process_geom_append";
    Long datasetId = createGeometryDataset(tableName);
    runImport("geom-append-job", datasetId, writeGeometryCsv(GEOM_ROWS), "APPEND");

    Mockito.verify(asyncJobService, Mockito.never())
        .failJob(Mockito.anyString(), Mockito.anyString());
    java.util.Map<String, String> geoms = geometries(tableName);
    assertThat(geoms).hasSize(3);
    assertThat(geoms.get("서울")).isEqualTo("POINT(127 37.5)");
    assertThat(geoms.get("부산")).isEqualTo("POINT(129 35.1)");
    assertThat(geoms.get("빈값")).isNull();
  }

  @Test
  void replaceWithoutPk_insertsGeometryFromGeoJson() throws Exception {
    // PK 없는 REPLACE 도 대상 테이블에 바로 넣는 경로 — APPEND 와 같은 결함이었다
    String tableName = "process_geom_replace";
    Long datasetId = createGeometryDataset(tableName);
    runImport("geom-replace-job", datasetId, writeGeometryCsv(GEOM_ROWS), "REPLACE");

    Mockito.verify(asyncJobService, Mockito.never())
        .failJob(Mockito.anyString(), Mockito.anyString());
    assertThat(geometries(tableName))
        .containsEntry("서울", "POINT(127 37.5)")
        .containsEntry("부산", "POINT(129 35.1)")
        .hasSize(3);
  }

  @Test
  void append_allNullGeometry_succeeds() throws Exception {
    // 위치 값이 모두 비어 있는 파일 — 수정 전에도 성공했다(NULL 은 geometry 로 들어간다). 타입 캐스트를 넣은 뒤에도 그대로인지 지키는 회귀 가드
    String tableName = "process_geom_null";
    Long datasetId = createGeometryDataset(tableName);
    runImport("geom-null-job", datasetId, writeGeometryCsv("가,\n나,\n"), "APPEND");

    Mockito.verify(asyncJobService, Mockito.never())
        .failJob(Mockito.anyString(), Mockito.anyString());
    assertThat(countRows(tableName)).isEqualTo(2);
  }

  @Test
  void append_featureGeoJson_storesInnerGeometry() throws Exception {
    // WD-201: 행 추가·편집과 같은 규칙 — Feature 는 안의 geometry 로 저장된다. 수정 전에는 ST_GeomFromGeoJSON 이
    // Feature 를 "invalid GeoJson representation" 으로 거부해 INSERT 에서 잡 전체가 실패했다.
    String tableName = "process_geom_feature";
    Long datasetId = createGeometryDataset(tableName);
    String rows =
        "서울,\"{\"\"type\"\":\"\"Feature\"\",\"\"properties\"\":{},"
            + "\"\"geometry\"\":{\"\"type\"\":\"\"Point\"\",\"\"coordinates\"\":[126.97,37.56]}}\"\n"
            + "부산,\"{\"\"type\"\":\"\"Point\"\",\"\"coordinates\"\":[129,35.1]}\"\n";
    runImport("geom-feature-job", datasetId, writeGeometryCsv(rows), "APPEND");

    Mockito.verify(asyncJobService, Mockito.never())
        .failJob(Mockito.anyString(), Mockito.anyString());
    assertThat(geometries(tableName))
        .containsEntry("서울", "POINT(126.97 37.56)")
        .containsEntry("부산", "POINT(129 35.1)")
        .hasSize(2);
  }

  @Test
  void duplicateHeader_jobFailsWithKoreanHeaderMessage_noRowsInserted() throws Exception {
    // WD-205: 헤더에 같은 이름(name)이 두 번 있는 파일을 바로 임포트 잡으로 넘기면(미리보기를 거치지 않는 API/AI 경로)
    // 잡 실패 메시지가 일반 문구가 아니라 파서의 한국어 헤더 오류 문구 그대로여야 하고, 한 행도 적재되지 않아야 한다.
    String tableName = "process_dup_header_dataset";
    Long datasetId = createDatasetWithBigintColumn(tableName);
    Path tempDir = Path.of(System.getProperty("java.io.tmpdir"), "firehub-test");
    Files.createDirectories(tempDir);
    Path csv = Files.createTempFile(tempDir, "process-dup-header-", ".csv");
    Files.writeString(csv, "name,name,amount\n서울,부산,1\n", StandardCharsets.UTF_8);

    dataImportService.processImport(
        "process-dup-header-job-id",
        datasetId,
        csv.toString(),
        "",
        "",
        "dup_header.csv",
        Files.size(csv),
        "CSV",
        testUserId,
        "Test User",
        "",
        "",
        "APPEND",
        1L);

    assertThat(countRows(tableName)).isZero();
    ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
    Mockito.verify(asyncJobService).failJob(Mockito.eq("process-dup-header-job-id"), msg.capture());
    assertThat(msg.getValue())
        .isEqualTo("파일에 같은 이름의 열이 있습니다: name (1·2번째 열). 열 이름을 고친 뒤 다시 올려 주세요.");
  }

  // ---- WD-246: 일·월이 앞에 오는 날짜를 열 단위로 한 가지 순서로 해석 ----

  private Long createDateDataset(String tableName) {
    DatasetDetailResponse dataset =
        datasetService.createDataset(
            new CreateDatasetRequest(
                "Date Order Import " + tableName,
                tableName,
                "WD-246 날짜 순서 회귀",
                null,
                "TABLE",
                "SOURCE",
                List.of(
                    new DatasetColumnRequest("name", "Name", "TEXT", null, false, false, null),
                    new DatasetColumnRequest("dt", "Dt", "DATE", null, true, false, null)),
                null),
            testUserId);
    return dataset.id();
  }

  private Path writeDateCsv(List<String> dates) throws Exception {
    StringBuilder csv = new StringBuilder("name,dt\n");
    for (int i = 0; i < dates.size(); i++) {
      csv.append("r").append(i + 1).append(",").append(dates.get(i)).append("\n");
    }
    Path tempDir = Path.of(System.getProperty("java.io.tmpdir"), "firehub-test");
    Files.createDirectories(tempDir);
    Path tempFile = Files.createTempFile(tempDir, "process-date-", ".csv");
    Files.writeString(tempFile, csv.toString(), StandardCharsets.UTF_8);
    return tempFile;
  }

  private void runImport(String jobId, Long datasetId, Path csv) throws Exception {
    dataImportService.processImport(
        jobId,
        datasetId,
        csv.toString(),
        "",
        "",
        "dates.csv",
        Files.size(csv),
        "CSV",
        testUserId,
        "Test User",
        "",
        "",
        "APPEND",
        1L);
  }

  @Test
  void dateColumn_monthFirstEvidenceAfterFirstBatch_storesEarlierAmbiguousRowsMonthFirst()
      throws Exception {
    // 앞 2500행(첫 배치 2000행 + 사전검증 샘플 200행 모두 포함)은 01/02/2026 처럼 모호하고, 2501행에서야 월/일 근거(01/13/2026)가
    // 나온다. Pass1 이 정한 월/일 순서를 Pass2 가 공유해야 앞 행들도 1월 2일로 저장된다 — 공유하지 않으면 2월 1일로 조용히 저장된다.
    String tableName = "process_date_month_first";
    Long datasetId = createDateDataset(tableName);
    List<String> dates =
        new java.util.ArrayList<>(java.util.Collections.nCopies(2500, "01/02/2026"));
    dates.add("01/13/2026");
    runImport("process-date-mdy-job-id", datasetId, writeDateCsv(dates));

    Mockito.verify(asyncJobService, Mockito.never())
        .failJob(Mockito.anyString(), Mockito.anyString());
    var stored =
        dsl.fetch(
            "SELECT dt, count(*) AS n FROM data.\"" + tableName + "\" GROUP BY dt ORDER BY dt");
    assertThat(stored).hasSize(2);
    assertThat(stored.get(0).get("dt", java.time.LocalDate.class))
        .isEqualTo(java.time.LocalDate.of(2026, 1, 2));
    assertThat(stored.get(0).get("n", Long.class)).isEqualTo(2500L);
    assertThat(stored.get(1).get("dt", java.time.LocalDate.class))
        .isEqualTo(java.time.LocalDate.of(2026, 1, 13));
  }

  @Test
  void dateColumn_mixedDayFirstAndMonthFirst_failsJobInKorean_noRowsInserted() throws Exception {
    // WD-237 t2 재현(31/12 일/월 + 12/31 월/일) — 예전엔 COMPLETED 로 행마다 다른 순서로 저장됐다
    String tableName = "process_date_mixed";
    Long datasetId = createDateDataset(tableName);
    runImport(
        "process-date-mixed-job-id",
        datasetId,
        writeDateCsv(List.of("31/12/2026", "01/02/2026", "12/31/2026")));

    assertThat(countRows(tableName)).isZero();
    ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
    Mockito.verify(asyncJobService).failJob(Mockito.eq("process-date-mixed-job-id"), msg.capture());
    assertThat(msg.getValue())
        .startsWith("검증 실패로 한 행도 적재되지 않았습니다 (오류 1건)")
        .contains("3행 'dt' 컬럼 - 같은 열에 일/월/연도와 월/일/연도 형식이 섞여 있습니다")
        .contains("31/12/2026(1행, 일/월/연도)");
  }
}
