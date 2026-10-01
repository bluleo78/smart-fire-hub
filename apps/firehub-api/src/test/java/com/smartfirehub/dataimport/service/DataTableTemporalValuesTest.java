package com.smartfirehub.dataimport.service;

import static com.smartfirehub.jooq.Tables.USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.dataimport.dto.ExportFormat;
import com.smartfirehub.dataimport.dto.ExportRequest;
import com.smartfirehub.dataimport.service.export.CsvExportWriter;
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
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * 데이터셋 데이터 탭 조회·단건 조회·중복 조회·CSV/Excel 내보내기(동기·비동기)가 범위 밖 DATE/TIMESTAMP 를 다른 날짜로
 * 바꾸지 않고 PG 텍스트 원문으로 돌려주는지 검증한다(#769).
 *
 * <p>수정 전에는 {@code java.sql.Date}/{@code Timestamp} 를 거치며 10000년 → 0000, BC 소실, infinity → 8994-08-17,
 * -infinity 타임스탬프 → 부호 없는 거대 연도, 1582 전환 공백·DST 공백 이동이 오류 없이 일어났다.
 *
 * <p><b>회귀 가드</b>: 정상 값은 수정 전 경로({@code dsl.fetch} + {@code record.get})로 읽은 Java 값과 응답 JSON·내보내기
 * 문자열이 바이트 단위로 같아야 한다.
 */
@Transactional
class DataTableTemporalValuesTest extends IntegrationTestBase {

  @Autowired private DataTableRowService dataTableRowService;
  @Autowired private DatasetDataService datasetDataService;
  @Autowired private DatasetService datasetService;
  @Autowired private DataExportService dataExportService;
  @Autowired private DatasetRepository datasetRepository;
  @Autowired private DatasetColumnRepository columnRepository;
  @Autowired private DSLContext dsl;
  @Autowired private ObjectMapper objectMapper;

  private static final List<String> COLS = List.of("d", "ts", "label");

  private Long userId;
  private Long datasetId;
  private String table;
  private Map<String, String> types;

  /** 라벨 → 수정 전 경로로 읽은 행(Java 값). */
  private Map<String, Map<String, Object>> preFix;

  /** 라벨 → 컬럼 → PG {@code ::text} 원문. */
  private Map<String, Map<String, String>> pgText;

  /** 라벨 → 범위 밖·재현 불가라 PG 원문이어야 하는 컬럼. */
  private Map<String, Set<String>> abnormal;

  /** 라벨 → id (삽입 순서). */
  private final Map<String, Long> ids = new LinkedHashMap<>();

  @BeforeEach
  void setUp() {
    userId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "temporal769_user")
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "Temporal 769")
            .set(USER.EMAIL, "temporal769@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();
    table = "zz_temporal_769";
    datasetId =
        datasetService
            .createDataset(
                new CreateDatasetRequest(
                    table,
                    table,
                    null,
                    null,
                    "TABLE",
                    "SOURCE",
                    List.of(
                        new DatasetColumnRequest("d", "D", "DATE", null, true, false, null),
                        new DatasetColumnRequest("ts", "TS", "TIMESTAMP", null, true, false, null),
                        new DatasetColumnRequest("label", "Label", "TEXT", null, true, false, null)),
                    null),
                userId)
            .id();
    types = Map.of("d", "DATE", "ts", "TIMESTAMP", "label", "TEXT");

    String[][] rows = {
      {"ok1", "'2024-01-01'", "'2024-01-01 10:00:00.123'"},
      {"ok2", "'2024-06-30'", "'2024-06-30 23:59:59'"},
      {"ok3", "'0001-01-01'", "'1900-01-01 10:00'"},
      {"nul", "NULL", "NULL"},
      {"big", "'10000-01-01'", "'10000-01-01 00:00'"},
      {"big2", "'12345-06-07'", "'10000-01-01 00:00:00.5'"},
      {"bc", "'0044-03-15 BC'", "'0044-03-15 10:00 BC'"},
      {"inf", "'infinity'", "'-infinity'"},
      {"ninf", "'-infinity'", "'infinity'"},
      {"gap", "'1582-10-10'", "NULL"},
      {"dst", "NULL", "'1988-05-08 02:30'"},
      {"inf2", "'infinity'", "NULL"},
    };
    for (String[] r : rows) {
      Long id =
          dsl.fetchOne(
                  "INSERT INTO "
                      + DataSchema.qualify(table)
                      + " (d, ts, label) VALUES ("
                      + r[1]
                      + "::date, "
                      + r[2]
                      + "::timestamp, '"
                      + r[0]
                      + "') RETURNING id")
              .get(0, Long.class);
      ids.put(r[0], id);
    }

    abnormal = new HashMap<>();
    for (String l : List.of("big", "big2", "bc", "inf", "ninf")) {
      abnormal.put(l, Set.of("d", "ts"));
    }
    abnormal.put("gap", Set.of("d"));
    abnormal.put("inf2", Set.of("d"));
    // Asia/Seoul 은 1988-05-08 02:00~03:00 이 DST 공백 — java.sql.Timestamp 가 03:30 으로 민다. 그 밖 시간대는 정상 값
    abnormal.put(
        "dst", "Asia/Seoul".equals(TimeZone.getDefault().getID()) ? Set.of("ts") : Set.of());

    // 수정 전 경로 — 데이터 탭이 쓰던 그대로(dsl.fetch + record.get)
    preFix = new HashMap<>();
    for (var rec : dsl.fetch("SELECT id, \"d\", \"ts\", \"label\" FROM " + DataSchema.qualify(table))) {
      Map<String, Object> m = new HashMap<>();
      for (int i = 0; i < rec.size(); i++) {
        m.put(rec.field(i).getName(), rec.get(i));
      }
      preFix.put((String) m.get("label"), m);
    }
    pgText = new HashMap<>();
    for (var rec : dsl.fetch("SELECT label, d::text, ts::text FROM " + DataSchema.qualify(table))) {
      Map<String, String> m = new HashMap<>();
      m.put("d", rec.get(1, String.class));
      m.put("ts", rec.get(2, String.class));
      pgText.put(rec.get(0, String.class), m);
    }
  }

  private boolean isAbnormal(String label, String col) {
    return abnormal.getOrDefault(label, Set.of()).contains(col);
  }

  /** 한 행이 계약을 지키는지 — 이상 셀은 PG 원문 문자열, 나머지는 수정 전과 같은 JSON. */
  private void assertRowContract(String where, Map<String, Object> row) throws Exception {
    String label = (String) row.get("label");
    for (String col : List.of("d", "ts")) {
      Object v = row.get(col);
      if (isAbnormal(label, col)) {
        assertThat(v).as("%s %s.%s", where, label, col).isEqualTo(pgText.get(label).get(col));
      } else {
        assertThat(objectMapper.writeValueAsString(v))
            .as("%s %s.%s 정상 값 형태 불변", where, label, col)
            .isEqualTo(objectMapper.writeValueAsString(preFix.get(label).get(col)));
      }
    }
    // 이상 셀이 없는 행은 행 전체 JSON 이 수정 전과 바이트 단위로 같다
    if (abnormal.getOrDefault(label, Set.of()).isEmpty()) {
      Map<String, Object> expected = new TreeMap<>(preFix.get(label));
      Map<String, Object> actual = new TreeMap<>(row);
      actual.keySet().retainAll(expected.keySet());
      assertThat(objectMapper.writeValueAsString(actual))
          .as("%s %s 행 JSON 불변", where, label)
          .isEqualTo(objectMapper.writeValueAsString(expected));
    }
  }

  @Test
  void dataTab_outOfRangeCellsArePgText_normalCellsUnchanged() throws Exception {
    // 데이터 탭 API 가 쓰는 서비스 경로(공간 필터 오버로드 포함)
    var response = datasetDataService.getDatasetData(datasetId, null, 0, 50, null, "ASC", false);
    assertThat(response.rows()).hasSize(ids.size());
    for (Map<String, Object> row : response.rows()) {
      assertRowContract("dataTab", row);
    }
    // 내보내기·AI 분류가 쓰는 문자열 컬럼 오버로드
    List<Map<String, Object>> rows =
        dataTableRowService.queryData(table, COLS, null, 0, 50, null, "ASC", types);
    assertThat(rows).hasSize(ids.size());
    for (Map<String, Object> row : rows) {
      assertRowContract("queryData", row);
    }
    // 대표 값은 리터럴로도 고정한다(시간대 무관 값)
    Map<String, Object> big = rows.stream().filter(r -> "big".equals(r.get("label"))).findFirst().orElseThrow();
    assertThat(big.get("d")).isEqualTo("10000-01-01");
    Map<String, Object> inf = rows.stream().filter(r -> "inf".equals(r.get("label"))).findFirst().orElseThrow();
    assertThat(inf.get("d")).isEqualTo("infinity");
    assertThat(inf.get("ts")).isEqualTo("-infinity");
    Map<String, Object> bc = rows.stream().filter(r -> "bc".equals(r.get("label"))).findFirst().orElseThrow();
    assertThat(bc.get("d")).isEqualTo("0044-03-15 BC");
    assertThat(bc.get("ts")).isEqualTo("0044-03-15 10:00:00 BC");
    Map<String, Object> ok1 = rows.stream().filter(r -> "ok1".equals(r.get("label"))).findFirst().orElseThrow();
    assertThat(objectMapper.writeValueAsString(ok1.get("d"))).isEqualTo("\"2024-01-01\"");
  }

  @Test
  void getRow_outOfRangeCellsArePgText_normalCellsUnchanged() throws Exception {
    for (var e : ids.entrySet()) {
      Map<String, Object> row = dataTableRowService.getRow(table, COLS, e.getValue(), types);
      assertRowContract("getRow", row);
    }
  }

  @Test
  void findDuplicateRows_outOfRangeKeyIsPgText() {
    // infinity 가 두 행(inf, inf2) — 중복 키 값도 다른 날짜(8994-08-17)가 아니라 원문이어야 한다.
    // (NULL 두 행(nul, dst)도 한 그룹으로 나오므로 NULL 그룹은 제외하고 본다)
    List<Map<String, Object>> dups =
        dataTableRowService.findDuplicateRows(table, List.of("d"), 10).stream()
            .filter(r -> r.get("d") != null)
            .toList();
    assertThat(dups).hasSize(1);
    assertThat(dups.get(0).get("d")).isEqualTo("infinity");
    assertThat(((Number) dups.get(0).get("duplicate_count")).longValue()).isEqualTo(2L);
  }

  /** 내보내기 기대 행 — 이상 셀은 PG 원문, 정상 셀은 수정 전 Java 값의 toString, NULL 은 빈 문자열. */
  private List<String[]> expectedExportRows() {
    List<String[]> out = new ArrayList<>();
    for (String label : ids.keySet()) {
      String[] v = new String[3];
      int i = 0;
      for (String col : List.of("d", "ts")) {
        Object old = preFix.get(label).get(col);
        v[i++] = isAbnormal(label, col) ? pgText.get(label).get(col) : old == null ? "" : old.toString();
      }
      v[2] = label;
      out.add(v);
    }
    return out;
  }

  private byte[] expectedCsv() throws Exception {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (CsvExportWriter w = new CsvExportWriter(bos)) {
      w.writeHeader(List.of("D", "TS", "Label"));
      for (String[] r : expectedExportRows()) {
        w.writeRow(r);
      }
    }
    return bos.toByteArray();
  }

  private byte[] syncExport(ExportFormat format) throws Exception {
    var result =
        dataExportService.exportDataset(
            datasetId,
            new ExportRequest(format, List.of("label", "d", "ts"), null, null),
            userId,
            "temporal769_user",
            "127.0.0.1",
            "test");
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    result.streamingBody().writeTo(bos);
    return bos.toByteArray();
  }

  @Test
  void csvExport_outOfRangeCellsArePgText_normalCellsByteIdentical() throws Exception {
    byte[] actual = syncExport(ExportFormat.CSV);
    assertThat(new String(actual, StandardCharsets.UTF_8))
        .isEqualTo(new String(expectedCsv(), StandardCharsets.UTF_8));
    String csv = new String(actual, StandardCharsets.UTF_8);
    // 정상 값은 예전 문자열 그대로(Timestamp.toString 의 ".0" 포함)
    assertThat(csv).contains("\"2024-06-30\",\"2024-06-30 23:59:59.0\",\"ok2\"");
    assertThat(csv).contains("\"2024-01-01\",\"2024-01-01 10:00:00.123\",\"ok1\"");
    // 이상 값은 PG 원문
    assertThat(csv).contains("\"10000-01-01\",\"10000-01-01 00:00:00\",\"big\"");
    assertThat(csv).contains("\"0044-03-15 BC\",\"0044-03-15 10:00:00 BC\",\"bc\"");
    assertThat(csv).contains("\"infinity\",\"-infinity\",\"inf\"");
  }

  @Test
  void excelExport_outOfRangeCellsAreStringCellsWithPgText() throws Exception {
    byte[] actual = syncExport(ExportFormat.EXCEL);
    List<String[]> expected = expectedExportRows();
    try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(actual))) {
      Sheet sheet = wb.getSheetAt(0);
      assertThat(sheet.getLastRowNum()).isEqualTo(expected.size());
      for (int r = 0; r < expected.size(); r++) {
        Row row = sheet.getRow(r + 1);
        for (int c = 0; c < 3; c++) {
          Cell cell = row.getCell(c);
          // 내보내기 Excel 은 모든 셀을 문자열 셀로 쓴다 — 정상·이상 값 모두 예전과 같은 셀 타입
          assertThat(cell.getCellType()).as("row %d col %d", r, c).isEqualTo(CellType.STRING);
          assertThat(cell.getStringCellValue()).as("row %d col %d", r, c).isEqualTo(expected.get(r)[c]);
        }
      }
    }
  }

  @Test
  void asyncCsvExport_outOfRangeCellsArePgText_normalCellsByteIdentical() throws Exception {
    // 대용량 비동기 경로 — @Async 프록시를 거치지 않도록 직접 생성해 같은 스레드에서 실행한다
    DataExportAsyncRunner runner =
        new DataExportAsyncRunner(
            dataTableRowService, mock(AsyncJobService.class), mock(AuditLogService.class));
    List<DatasetColumnResponse> columns = columnRepository.findByDatasetId(datasetId);
    String jobId = "zz_769_async_" + System.nanoTime();
    Path file = DataExportService.EXPORT_DIR.resolve(jobId + ".csv");
    try {
      runner.executeAsyncExport(
          jobId,
          datasetRepository.findById(datasetId).orElseThrow(),
          columns,
          types,
          null,
          ExportFormat.CSV,
          null,
          "zz.csv",
          userId,
          "temporal769_user",
          "127.0.0.1",
          "test");
      assertThat(file).exists();
      assertThat(new String(Files.readAllBytes(file), StandardCharsets.UTF_8))
          .isEqualTo(new String(expectedCsv(), StandardCharsets.UTF_8));
    } finally {
      Files.deleteIfExists(file);
    }
  }
}
