package com.smartfirehub.dataimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.dataimport.dto.ColumnMappingEntry;
import com.smartfirehub.dataimport.dto.ImportResponse;
import com.smartfirehub.dataimport.dto.ImportValidateResponse;
import com.smartfirehub.dataimport.dto.ValidationErrorDetail;
import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.job.service.AsyncJobService;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import com.smartfirehub.support.TestUsers;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * WD-96: 물리 타입 제약(VARCHAR(n) 길이, NUMERIC(18,6) 정수부)을 넘는 값이 사전검증에서는 "성공", 실제 임포트에서는 "관리자에게 문의" 일반
 * 문구로 실패하던 결함의 회귀 테스트.
 *
 * <ul>
 *   <li>검증 기준이 실제 PostgreSQL 과 일치하는지(경계값을 진짜 INSERT 로 대조)
 *   <li>사전검증 결과에 오류 행·칸·사유가 잡히는지
 *   <li>샘플(앞 200행) 밖 행의 위반도 임포트 실패 사유·오류 상세에 행·칸·사유로 남는지
 *   <li>그래도 DB 단계에서 나는 데이터 오류가 DB 원문 대신 한국어 사유로 바뀌는지(실제 예외 래핑 체인)
 * </ul>
 *
 * <p>트랜잭션 없이 돈다 — DB 오류를 일으키는 INSERT 가 테스트 트랜잭션 안에서 나면 트랜잭션이 abort 되어 이후 잡 실패 처리·감사 기록까지 25P02 로 함께
 * 깨져, 검증하려는 경로와 무관한 이유로 실패한다. 그래서 고유 이름으로 만들고 {@link #tearDown} 에서 직접 정리한다.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DataImportPhysicalLimitIntegrationTest extends IntegrationTestBase {

  @Autowired private DataImportService dataImportService;
  @Autowired private DatasetService datasetService;
  @Autowired private DatasetColumnRepository columnRepository;
  @Autowired private DataValidationService validationService;
  @Autowired private DSLContext dsl;
  @Autowired private ObjectMapper objectMapper;

  @MockitoSpyBean private AsyncJobService asyncJobService;

  private Long userId;
  private Long datasetId;
  private String tableName;
  private final List<Path> tempFiles = new ArrayList<>();

  @BeforeEach
  void setUpFixtures() {
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    tableName = "wd96_" + suffix;
    userId = TenantRlsTestSupport.insertUser(dsl, "wd96_user_");
    // code VARCHAR(10), amount DECIMAL(→NUMERIC(18,6)) — 둘 다 nullable 이라 한 칸씩 독립 검사할 수 있다.
    datasetId =
        datasetService
            .createDataset(
                new CreateDatasetRequest(
                    "WD96 Dataset " + suffix,
                    tableName,
                    "물리 타입 제약 회귀 테스트",
                    null,
                    "TABLE",
                    "SOURCE",
                    List.of(
                        new DatasetColumnRequest("code", "코드", "VARCHAR", 10, true, false, null),
                        new DatasetColumnRequest(
                            "amount", "금액", "DECIMAL", null, true, false, null),
                        new DatasetColumnRequest("memo", "메모", "TEXT", null, true, false, null)),
                    null),
                userId)
            .id();
  }

  @AfterEach
  void tearDown() throws Exception {
    for (Path p : tempFiles) {
      Files.deleteIfExists(p);
    }
    // 임포트가 감사 로그를 남기므로 사용자 정리(TestUsers.cleanup)는 마지막이다(FK).
    TenantRlsTestSupport.cleanupAll(
        () -> datasetService.deleteDataset(datasetId),
        () -> TestUsers.cleanup(dsl, fixtureTransactionTemplate, userId, DEFAULT_TEST_TENANT_ID));
  }

  // ------------------------------------------------------------------ //
  // 검증 기준 == PostgreSQL 기준 (경계값을 실제 INSERT 로 대조)
  // ------------------------------------------------------------------ //

  /**
   * 검증기 판정을 실제 PostgreSQL 대입과 대조한다. 각 칸 값에 대해:
   *
   * <ul>
   *   <li>검증기가 거부하면 → 원래 값(형식 변환만 한 값)을 넣어도 DB 가 거부해야 한다(PG 가 받는 값을 막지 않는다).
   *   <li>검증기가 통과시키면 → 검증기 출력값(정규화 결과)은 반드시 들어가고 저장값이 출력값과 같아야 하며, 원래 값도 DB 가 받는다면 그 저장값 역시 출력값과
   *       같아야 한다(반올림·공백 절단 규칙이 PG 와 동일).
   * </ul>
   *
   * <p>원래 값은 DB 가 거부하지만 정규화 값은 들어가는 경우(예: {@code 1E-1001})는 정규화가 고친 경우라 불일치가 아니다.
   */
  @Test
  void 검증의_수락_여부와_정규화_값이_실제_INSERT_와_경계에서_일치한다() throws Exception {
    List<DatasetColumnResponse> columns = columnRepository.findByDatasetId(datasetId);

    // 경계 양쪽 — 한글·이모지(코드포인트) / 초과분이 공백뿐인 값 / NUL / 정수부 12·13자리 / 반올림 경계(양·음) / 지수 표기
    Map<String, List<String>> cases = new LinkedHashMap<>();
    cases.put(
        "code",
        List.of(
            "a".repeat(10),
            "a".repeat(11),
            "가".repeat(10),
            "가".repeat(11),
            "😀".repeat(10),
            "😀".repeat(11),
            "\"abcdefghij   \"",
            "\"abcdefghij  x\"",
            "a\u0000b"));
    cases.put("memo", List.of("plain", "a\u0000b"));
    cases.put(
        "amount",
        List.of(
            "999999999999.999999",
            "999999999999.9999994",
            "999999999999.9999995",
            "-999999999999.9999994",
            "-999999999999.9999995",
            "-0.0000005",
            "0.0000005",
            "0.0000004",
            "1.1234565",
            "1E-1001",
            "1000000000000",
            "123456789012",
            "12345678901234",
            "1E11",
            "1E12",
            "-1.5E3"));

    List<String> mismatches = new ArrayList<>();
    for (var entry : cases.entrySet()) {
      DatasetColumnResponse column = column(columns, entry.getKey());
      for (String cell : entry.getValue()) {
        Object raw = validationService.convertValue(cell.trim(), column.dataType());
        Object out =
            DataValidationServiceTest.validateCell(validationService, column, cell).value();
        Attempt rawTry = insert(column.columnName(), raw);
        if (out == null) {
          if (rawTry.ok()) {
            mismatches.add(column.columnName() + "=" + cell + ": 검증 거부인데 DB 는 받음");
          }
          continue;
        }
        Attempt outTry = insert(column.columnName(), out);
        if (!outTry.ok() || !same(outTry.stored(), out)) {
          mismatches.add(column.columnName() + "=" + cell + ": 정규화 값 저장 실패/불일치 " + outTry);
        }
        if (rawTry.ok() && !same(rawTry.stored(), out)) {
          mismatches.add(
              column.columnName() + "=" + cell + ": PG 저장값 " + rawTry.stored() + " != 정규화 " + out);
        }
      }
    }
    assertThat(mismatches).isEmpty();
  }

  // ------------------------------------------------------------------ //
  // 사전검증 — 오류 상세표에 행·칸·사유
  // ------------------------------------------------------------------ //

  @Test
  void 사전검증은_길이_초과와_정수부_초과_행을_칸과_사유로_잡는다() throws Exception {
    String csv = "code,amount\nOK,1.5\n1234567890123,2\nOK2,12345678901234\n";
    MockMultipartFile file =
        new MockMultipartFile("file", "wd96.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

    ImportValidateResponse res =
        dataImportService.validateImport(datasetId, file, identityMappings());

    assertThat(res.validRows()).isEqualTo(1);
    assertThat(res.errorRows()).isEqualTo(2);
    assertThat(res.errors())
        .extracting(
            ValidationErrorDetail::rowNumber,
            ValidationErrorDetail::columnName,
            ValidationErrorDetail::error)
        .containsExactly(
            tuple(2, "code", "최대 10자까지 입력할 수 있습니다(입력 13자)"),
            tuple(3, "amount", "정수부는 최대 12자리까지 입력할 수 있습니다"));
  }

  // ------------------------------------------------------------------ //
  // 실제 임포트 — 샘플 밖 행의 위반도 행·칸·사유로 실패 사유에
  // ------------------------------------------------------------------ //

  @Test
  void 샘플_밖_행의_길이_초과도_임포트_실패_사유와_오류_상세에_행과_칸이_남는다() throws Exception {
    // 300행 중 250번째 행만 code 가 13자 — 사전검증(앞 200행)은 통과하고 임포트 검증에서만 걸린다.
    StringBuilder csv = new StringBuilder("code,amount\n");
    for (int i = 1; i <= 300; i++) {
      csv.append(i == 250 ? "1234567890123" : "C" + i).append(",").append(i).append("\n");
    }
    Path csvPath = writeTemp(csv.toString(), ".csv");

    MockMultipartFile file =
        new MockMultipartFile(
            "file", "wd96.csv", "text/csv", csv.toString().getBytes(StandardCharsets.UTF_8));
    ImportValidateResponse pre =
        dataImportService.validateImport(datasetId, file, identityMappings());
    assertThat(pre.validRows()).isEqualTo(200);
    assertThat(pre.errorRows()).isZero();

    String jobId = "wd96-beyond-sample-" + UUID.randomUUID();
    runImport(jobId, csvPath, "APPEND");

    assertThat(capturedFailMessage(jobId))
        .startsWith("검증 실패로 한 행도 적재되지 않았습니다 (오류 1건)")
        .contains("250행 'code' 컬럼 - 최대 10자까지 입력할 수 있습니다(입력 13자)");
    assertThat(countRows()).isZero();

    // 웹이 실패 화면에서 그리는 오류 상세(감사 로그 errorDetails)에도 같은 행·칸·사유가 있다.
    ImportResponse failed = latestImport();
    assertThat(failed.status()).isEqualTo("FAILED");
    Map<?, ?> details = objectMapper.readValue((String) failed.errorDetails(), Map.class);
    assertThat((List<?>) details.get("errors"))
        .singleElement()
        .satisfies(
            e -> {
              Map<?, ?> m = (Map<?, ?>) e;
              assertThat(m.get("rowNumber")).isEqualTo(250);
              assertThat(m.get("columnName")).isEqualTo("code");
              assertThat(m.get("error")).isEqualTo("최대 10자까지 입력할 수 있습니다(입력 13자)");
            });
  }

  // ------------------------------------------------------------------ //
  // DB 단계 데이터 오류 — 원문 대신 한국어 사유 (실제 예외 래핑 체인)
  // ------------------------------------------------------------------ //

  @Test
  void DB에서_난_길이_초과는_일반_문구가_아니라_글자_수_제한_사유로_보인다() throws Exception {
    // 메타데이터는 VARCHAR(10) 인데 물리 컬럼만 VARCHAR(5) 로 줄여, 검증을 통과한 값이 INSERT 에서 22001 로 실패하게 만든다.
    dsl.execute(
        "ALTER TABLE " + DataSchema.qualify(tableName) + " ALTER COLUMN code TYPE varchar(5)");
    Path csvPath = writeTemp("code,amount\nabcdefg,1\n", ".csv");

    String jobId = "wd96-db-varchar-" + UUID.randomUUID();
    runImport(jobId, csvPath, "APPEND");

    String message = capturedFailMessage(jobId);
    // PG 는 22001 에 컬럼 정보를 싣지 않고, 다중 행 INSERT 라 행도 특정할 수 없다 — 길이 상한만 안내한다.
    assertThat(message)
        .isEqualTo("데이터 오류로 임포트에 실패했습니다: 글자 수 제한(최대 5자)을 넘는 값이 있습니다. 파일의 값 또는 컬럼 설정을 확인해주세요.");
    assertNoDbInternals(message);
    assertThat(latestImport().errorMessage()).isEqualTo(message);
  }

  @Test
  void DB에서_난_숫자_범위_초과는_REPLACE_트랜잭션_래핑을_거쳐도_숫자_범위_사유로_보인다() throws Exception {
    // REPLACE(PK 無)는 삽입을 transactionTemplate 안에서 돌려 예외가 한 번 더 감싸진다 — 원인 체인을 끝까지 따라가야 한다.
    dsl.execute(
        "ALTER TABLE " + DataSchema.qualify(tableName) + " ALTER COLUMN amount TYPE numeric(8,2)");
    Path csvPath = writeTemp("code,amount\nA,1234567\n", ".csv");

    String jobId = "wd96-db-numeric-" + UUID.randomUUID();
    runImport(jobId, csvPath, "REPLACE");

    String message = capturedFailMessage(jobId);
    assertThat(message).contains("숫자 범위를 넘는 값이 있습니다");
    assertNoDbInternals(message);
  }

  // ------------------------------------------------------------------ //
  // helpers
  // ------------------------------------------------------------------ //

  private List<ColumnMappingEntry> identityMappings() {
    return List.of(
        new ColumnMappingEntry("code", "code"), new ColumnMappingEntry("amount", "amount"));
  }

  private void runImport(String jobId, Path csvPath, String mode) throws Exception {
    Path mappingsPath = writeTemp(objectMapper.writeValueAsString(identityMappings()), ".json");
    dataImportService.processImport(
        jobId,
        datasetId,
        csvPath.toString(),
        mappingsPath.toString(),
        "",
        csvPath.getFileName().toString(),
        Files.size(csvPath),
        "CSV",
        userId,
        "WD96 User",
        "",
        "",
        mode,
        DEFAULT_TEST_TENANT_ID);
  }

  private String capturedFailMessage(String jobId) {
    ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
    Mockito.verify(asyncJobService).failJob(Mockito.eq(jobId), msg.capture());
    return msg.getValue();
  }

  /** DB 메시지 원문·SQL·내부 테이블명이 사용자 문구에 새지 않아야 한다. */
  private void assertNoDbInternals(String message) {
    assertThat(message)
        .doesNotContain(tableName)
        .doesNotContain("character varying")
        .doesNotContain("numeric field overflow")
        .doesNotContain("INSERT")
        .doesNotContain("ERROR")
        .doesNotContain("관리자에게 문의");
  }

  private ImportResponse latestImport() {
    return dataImportService.getImportsByDatasetId(datasetId).get(0);
  }

  private long countRows() {
    return dsl.fetchCount(DSL.table(DataSchema.qualify(tableName)));
  }

  private Path writeTemp(String content, String ext) throws Exception {
    Path dir = Path.of(System.getProperty("java.io.tmpdir"), "firehub-test");
    Files.createDirectories(dir);
    Path p = Files.createTempFile(dir, "wd96-", ext);
    Files.writeString(p, content, StandardCharsets.UTF_8);
    tempFiles.add(p);
    return p;
  }

  private static DatasetColumnResponse column(List<DatasetColumnResponse> columns, String name) {
    return columns.stream().filter(c -> c.columnName().equals(name)).findFirst().orElseThrow();
  }

  private record Attempt(boolean ok, Object stored) {}

  /**
   * 실제 대입(INSERT ... RETURNING)으로 넣어 저장값을 돌려받는다 — {@code ?::varchar(10)} 같은 명시 캐스트는 SQL 표준상 초과분을
   * 조용히 잘라 버려 대조가 공허해진다.
   */
  private Attempt insert(String column, Object bind) {
    try {
      Object stored =
          dsl.fetchValue(
              "INSERT INTO "
                  + DataSchema.qualify(tableName)
                  + " (\""
                  + column
                  + "\") VALUES (?) RETURNING \""
                  + column
                  + "\"",
              bind);
      return new Attempt(true, stored);
    } catch (DataAccessException e) {
      return new Attempt(false, null);
    }
  }

  /** 숫자는 표기(scale)까지 같은지 toPlainString 으로, 문자열은 그대로 비교한다. */
  private static boolean same(Object a, Object b) {
    if (a instanceof BigDecimal x && b instanceof BigDecimal y) {
      return x.toPlainString().equals(y.toPlainString());
    }
    return Objects.equals(a, b);
  }
}
