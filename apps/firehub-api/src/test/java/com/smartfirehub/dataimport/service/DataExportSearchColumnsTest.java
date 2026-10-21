package com.smartfirehub.dataimport.service;

import static com.smartfirehub.jooq.Tables.USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.dataimport.dto.ExportFormat;
import com.smartfirehub.dataimport.dto.ExportRequest;
import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.service.DataTableRowService;
import com.smartfirehub.dataset.service.DatasetDataService;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.job.service.AsyncJobService;
import com.smartfirehub.support.IntegrationTestBase;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * 검색 중에 일부 컬럼만 골라 내보내도 데이터 탭과 같은 행이 나오는지 실제 DB 로 검증한다(WD-158).
 *
 * <p>수정 전에는 고른 컬럼이 출력 칸이자 검색 대상이어서, 검색어가 든 컬럼(지역)을 빼면 0행, GEOMETRY 만 고르면 검색 조건이 사라져 전체 행이 나갔다.
 * 견적(「총 N행」)은 컬럼 없이 셌으므로 사용자는 파일이 틀린 것을 알 수 없었다.
 */
@Transactional
class DataExportSearchColumnsTest extends IntegrationTestBase {

  @Autowired private DataExportService dataExportService;
  @Autowired private DataTableRowService dataTableRowService;
  @Autowired private DatasetDataService datasetDataService;
  @Autowired private DatasetService datasetService;
  @Autowired private DatasetRepository datasetRepository;
  @Autowired private DatasetColumnRepository columnRepository;
  @Autowired private DSLContext dsl;

  private static final String TABLE = "zz_export_search_158";

  private Long userId;
  private Long datasetId;

  @BeforeEach
  void setUp() {
    userId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "export158_user")
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "Export 158")
            .set(USER.EMAIL, "export158@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();
    datasetId =
        datasetService
            .createDataset(
                new CreateDatasetRequest(
                    "화재통계_158",
                    TABLE,
                    null,
                    null,
                    "TABLE",
                    "SOURCE",
                    List.of(
                        new DatasetColumnRequest("region", "지역", "TEXT", null, true, false, null),
                        new DatasetColumnRequest("cnt", "건수", "INTEGER", null, true, false, null),
                        new DatasetColumnRequest(
                            "amount", "피해금액", "DECIMAL", null, true, false, null),
                        new DatasetColumnRequest(
                            "geom", "위치", "GEOMETRY", null, true, false, null)),
                    null),
                userId)
            .id();
    dsl.execute(
        "INSERT INTO "
            + DataSchema.qualify(TABLE)
            + " (region, cnt, amount, geom) VALUES"
            + " ('서울', 10, 1000.5, ST_SetSRID(ST_MakePoint(127.0, 37.5), 4326)),"
            + " ('부산', 20, 2000.25, ST_SetSRID(ST_MakePoint(129.0, 35.1), 4326)),"
            + " ('대구', 30, 3000, ST_SetSRID(ST_MakePoint(128.6, 35.8), 4326))");
  }

  /** 동기 CSV 내보내기 결과의 데이터 줄(머리글 제외)을 돌려준다. */
  private List<String> syncCsvDataLines(List<String> columns, String search) throws Exception {
    var result =
        dataExportService.exportDataset(
            datasetId,
            new ExportRequest(ExportFormat.CSV, columns, search, null),
            userId,
            "export158_user",
            "127.0.0.1",
            "test");
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    result.streamingBody().writeTo(bos);
    List<String> lines = Arrays.asList(bos.toString(StandardCharsets.UTF_8).split("\r?\n"));
    return lines.subList(1, lines.size()).stream().filter(l -> !l.isBlank()).toList();
  }

  @Test
  void dataTabSearch_baseline_matchesOneRow() {
    // 비교 기준: 데이터 탭은 전체 컬럼에 검색을 건다
    var page = datasetDataService.getDatasetData(datasetId, "부산", 0, 50, null, "ASC", true);
    assertThat(page.totalElements()).isEqualTo(1);
  }

  @Test
  void selectingColumnsWithoutSearchedColumn_stillExportsSearchMatchedRow() throws Exception {
    // 수정 전: 지역을 빼면 cnt·amount 에만 '부산' ILIKE → 0행
    List<String> lines = syncCsvDataLines(List.of("cnt", "amount"), "부산");
    assertThat(lines).hasSize(1);
    assertThat(lines.get(0)).startsWith("\"20\",");
  }

  @Test
  void selectingOnlyGeometry_doesNotDropSearchCondition() throws Exception {
    // 수정 전: GEOMETRY 만 남으면 검색 대상이 없어 조건 없이 전체 3행
    List<String> lines = syncCsvDataLines(List.of("geom"), "부산");
    assertThat(lines).hasSize(1);
    assertThat(lines.get(0)).contains("129");
  }

  @Test
  void allColumnsAndNoSearch_regressionUnchanged() throws Exception {
    assertThat(syncCsvDataLines(null, "부산")).hasSize(1);
    assertThat(syncCsvDataLines(List.of("cnt"), null)).hasSize(3);
  }

  @Test
  void estimate_countMatchesExportedRows() {
    // 다이얼로그 「총 N행」과 실제 파일 행 수가 같아야 한다 — 컬럼을 골라도 같은 건수
    var estimate =
        dataExportService.estimateExport(
            datasetId, new ExportRequest(ExportFormat.CSV, List.of("cnt", "amount"), "부산", null));
    assertThat(estimate.rowCount()).isEqualTo(1);
  }

  @Test
  void asyncExport_selectingColumnsWithoutSearchedColumn_exportsSearchMatchedRow()
      throws Exception {
    // 대용량 비동기 경로 — @Async 프록시를 거치지 않도록 직접 생성해 같은 스레드에서 실행한다
    DataExportAsyncRunner runner =
        new DataExportAsyncRunner(
            dataTableRowService, mock(AsyncJobService.class), mock(AuditLogService.class));
    List<DatasetColumnResponse> all = columnRepository.findByDatasetId(datasetId);
    List<DatasetColumnResponse> selected =
        all.stream().filter(c -> List.of("cnt", "amount").contains(c.columnName())).toList();
    Map<String, String> types =
        Map.of("region", "TEXT", "cnt", "INTEGER", "amount", "DECIMAL", "geom", "GEOMETRY");
    String jobId = "zz_158_async_" + System.nanoTime();
    Path file = DataExportService.EXPORT_DIR.resolve(jobId + ".csv");
    try {
      runner.executeAsyncExport(
          jobId,
          datasetRepository.findById(datasetId).orElseThrow(),
          selected,
          all.stream().map(DatasetColumnResponse::columnName).toList(),
          types,
          "부산",
          ExportFormat.CSV,
          null,
          "zz.csv",
          userId,
          "export158_user",
          "127.0.0.1",
          "test");
      List<String> lines =
          Arrays.stream(Files.readString(file, StandardCharsets.UTF_8).split("\r?\n"))
              .filter(l -> !l.isBlank())
              .toList();
      assertThat(lines).hasSize(2); // 머리글 + 부산 1행
      assertThat(lines.get(1)).startsWith("\"20\",");
    } finally {
      Files.deleteIfExists(file);
    }
  }
}
