package com.smartfirehub.dataimport.service;

import static com.smartfirehub.jooq.Tables.USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataimport.dto.ColumnMappingEntry;
import com.smartfirehub.dataimport.dto.ImportPreviewResponse;
import com.smartfirehub.dataimport.dto.ImportValidateResponse;
import com.smartfirehub.dataimport.exception.InvalidFileHeaderException;
import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetDetailResponse;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.support.IntegrationTestBase;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사전 검증(validateImport)이 전량이 아니라 파일 앞 200행만 샘플링해 검사하는지 검증한다. 대용량 파일에서도 즉시 응답할 수 있도록 하는 것이 목적이며, 이
 * 테스트는 응답 DTO의 sampleSize/sampled 필드와 실제 검사 대상 행수가 일치하는지 확인한다.
 */
@Transactional
class DataImportServiceValidateTest extends IntegrationTestBase {

  @Autowired private DataImportService dataImportService;

  @Autowired private DatasetService datasetService;

  @Autowired private DSLContext dsl;

  private Long testUserId;
  private Long testDatasetId;

  @BeforeEach
  void setUp() {
    testUserId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "validate_sample_user")
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "Validate Sample User")
            .set(USER.EMAIL, "validate_sample@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();

    List<DatasetColumnRequest> columns =
        List.of(new DatasetColumnRequest("name", "Name", "TEXT", null, false, false, null));

    DatasetDetailResponse dataset =
        datasetService.createDataset(
            new CreateDatasetRequest(
                "Validate Sample Dataset",
                "validate_sample_dataset",
                "Dataset for validateImport sampling test",
                null,
                "TABLE",
                "SOURCE",
                columns,
                null),
            testUserId);

    testDatasetId = dataset.id();
  }

  @Test
  void validateImport_samplesFirst200Rows_andFlagsSampled() throws Exception {
    // given: 헤더 + 300행(모두 유효) CSV — 샘플 크기(200)보다 큰 파일
    StringBuilder csv = new StringBuilder("name\n");
    for (int i = 0; i < 300; i++) {
      csv.append("row").append(i).append("\n");
    }
    MockMultipartFile file =
        new MockMultipartFile(
            "file", "t.csv", "text/csv", csv.toString().getBytes(StandardCharsets.UTF_8));
    List<ColumnMappingEntry> mappings = List.of(new ColumnMappingEntry("name", "name"));

    // when
    ImportValidateResponse res = dataImportService.validateImport(testDatasetId, file, mappings);

    // then: 전체 300이 아니라 샘플 200만 검사, sampled=true
    assertThat(res.sampled()).isTrue();
    assertThat(res.sampleSize()).isEqualTo(200);
    assertThat(res.validRows()).isEqualTo(200);
    assertThat(res.errorRows()).isZero();
  }

  @Test
  void validateImport_allInvalidSample_validRowsZero() throws Exception {
    // given: name(필수) 컬럼이 비어있는 300행 CSV — 전부 검증 실패.
    // 빈 줄은 빈 행으로 건너뛰므로(WD-255) 매핑하지 않는 memo 열에 값을 두어 「name 만 빈 행」을 만든다.
    StringBuilder csv = new StringBuilder("name,memo\n");
    for (int i = 0; i < 300; i++) {
      csv.append(",m").append(i).append("\n");
    }
    MockMultipartFile file =
        new MockMultipartFile(
            "file", "invalid.csv", "text/csv", csv.toString().getBytes(StandardCharsets.UTF_8));
    List<ColumnMappingEntry> mappings = List.of(new ColumnMappingEntry("name", "name"));

    // when
    ImportValidateResponse res = dataImportService.validateImport(testDatasetId, file, mappings);

    // then: 샘플 200행 모두 검사 대상이나 전부 오류 → validRows=0
    assertThat(res.sampled()).isTrue();
    assertThat(res.sampleSize()).isEqualTo(200);
    assertThat(res.validRows()).isZero();
    assertThat(res.errorRows()).isEqualTo(200);
  }

  // ── WD-612: 헤더 크기 상한 — 미리보기·사전 검증이 같은 400 예외로 거절, 샘플 값은 표시용으로만 자른다 ──

  /** c1..cN 헤더 + 같은 너비 데이터 한 줄 CSV 업로드 파일. */
  private static MockMultipartFile csvWithColumns(int columns) {
    StringBuilder header = new StringBuilder("name");
    StringBuilder data = new StringBuilder("a");
    for (int i = 2; i <= columns; i++) {
      header.append(",c").append(i);
      data.append(',').append(i);
    }
    return new MockMultipartFile(
        "file",
        "wide.csv",
        "text/csv",
        (header + "\n" + data + "\n").getBytes(StandardCharsets.UTF_8));
  }

  private static final String TOO_MANY_COLUMNS =
      "파일의 열이 너무 많습니다: 1,601개 (최대 1,600개). 가져올 열만 남긴 뒤 다시 올려 주세요.";

  @Test
  void previewAndValidate_tooManyColumns_rejectedWithSameKoreanMessage() {
    MockMultipartFile file = csvWithColumns(ImportFileLimits.MAX_HEADER_COLUMNS + 1);
    List<ColumnMappingEntry> mappings = List.of(new ColumnMappingEntry("name", "name"));

    assertThatThrownBy(() -> dataImportService.previewImport(testDatasetId, file))
        .isExactlyInstanceOf(InvalidFileHeaderException.class)
        .hasMessage(TOO_MANY_COLUMNS);
    assertThatThrownBy(() -> dataImportService.validateImport(testDatasetId, file, mappings))
        .isExactlyInstanceOf(InvalidFileHeaderException.class)
        .hasMessage(TOO_MANY_COLUMNS);
  }

  @Test
  void previewAndValidate_hugeSingleLineHeader_rejectedWithoutReadingWholeLine() {
    // 이슈 재현(줄바꿈 없는 60MB 한 줄)의 축소판 — 헤더 줄 상한의 2배 크기
    int size = (int) (ImportFileLimits.MAX_HEADER_LINE_CHARS * 2);
    MockMultipartFile file =
        new MockMultipartFile(
            "file", "one-line.csv", "text/csv", "a".repeat(size).getBytes(StandardCharsets.UTF_8));
    List<ColumnMappingEntry> mappings = List.of(new ColumnMappingEntry("name", "name"));

    assertThatThrownBy(() -> dataImportService.previewImport(testDatasetId, file))
        .isExactlyInstanceOf(InvalidFileHeaderException.class)
        .hasMessage(FileHeaderValidator.HEADER_LINE_TOO_LONG);
    assertThatThrownBy(() -> dataImportService.validateImport(testDatasetId, file, mappings))
        .isExactlyInstanceOf(InvalidFileHeaderException.class)
        .hasMessage(FileHeaderValidator.HEADER_LINE_TOO_LONG);
  }

  @Test
  void previewAndValidate_headerNameTooLong_rejected_atLimitAccepted() throws Exception {
    String expected = "열 이름이 너무 깁니다: 2번째 열(501자) (최대 500자). 열 이름을 줄인 뒤 다시 올려 주세요.";
    MockMultipartFile over =
        new MockMultipartFile(
            "file",
            "long.csv",
            "text/csv",
            ("name," + "가".repeat(501) + "\na,b\n").getBytes(StandardCharsets.UTF_8));
    List<ColumnMappingEntry> mappings = List.of(new ColumnMappingEntry("name", "name"));
    assertThatThrownBy(() -> dataImportService.previewImport(testDatasetId, over))
        .isExactlyInstanceOf(InvalidFileHeaderException.class)
        .hasMessage(expected);
    assertThatThrownBy(() -> dataImportService.validateImport(testDatasetId, over, mappings))
        .isExactlyInstanceOf(InvalidFileHeaderException.class)
        .hasMessage(expected);

    // 상한 딱 그 값(500자)은 미리보기·검증 모두 통과하고, 미리보기 헤더와 매핑 제안의 파일 열 이름이 잘리지 않고 같다
    String name500 = "가".repeat(500);
    MockMultipartFile atLimit =
        new MockMultipartFile(
            "file",
            "ok.csv",
            "text/csv",
            ("name," + name500 + "\na,b\n").getBytes(StandardCharsets.UTF_8));
    ImportPreviewResponse preview = dataImportService.previewImport(testDatasetId, atLimit);
    assertThat(preview.fileHeaders()).containsExactly("name", name500);
    assertThat(preview.suggestedMappings())
        .extracting(m -> m.fileColumn())
        .containsExactly("name", name500);
    ImportValidateResponse res = dataImportService.validateImport(testDatasetId, atLimit, mappings);
    assertThat(res.validRows()).isEqualTo(1);
  }

  @Test
  void preview_longSampleValue_truncatedForDisplay_validateSeesFullValue() throws Exception {
    // 샘플 값은 화면 표시용이라 1,000자로 잘라 말줄임표를 붙인다 — 응답이 긴 본문 셀만큼 커지지 않게. 검증·임포트는 원본을 다시 읽는다.
    String longValue = "v".repeat(ImportFileLimits.MAX_PREVIEW_VALUE_LENGTH + 500);
    MockMultipartFile file =
        new MockMultipartFile(
            "file",
            "longvalue.csv",
            "text/csv",
            ("name\n" + longValue + "\nshort\n").getBytes(StandardCharsets.UTF_8));

    ImportPreviewResponse preview = dataImportService.previewImport(testDatasetId, file);
    assertThat(preview.sampleRows()).hasSize(2);
    assertThat(preview.sampleRows().get(0).get("name"))
        .hasSize(ImportFileLimits.MAX_PREVIEW_VALUE_LENGTH + 1)
        .endsWith("…");
    assertThat(preview.sampleRows().get(1).get("name")).isEqualTo("short");
    assertThat(preview.totalRows()).isEqualTo(2);

    ImportValidateResponse res =
        dataImportService.validateImport(
            testDatasetId, file, List.of(new ColumnMappingEntry("name", "name")));
    assertThat(res.validRows()).isEqualTo(2);
  }
}
