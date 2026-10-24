package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.exception.InvalidTableNameException;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.service.DataTableService;
import com.smartfirehub.dataset.service.DatasetService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TempDatasetServiceTest {

  @Mock DatasetRepository datasetRepository;
  @Mock DatasetService datasetService;
  @Mock DatasetColumnRepository columnRepository;

  @InjectMocks TempDatasetService tempDatasetService;

  // ------------------------------------------------------------------ //
  // sanitizeForTableName
  // ------------------------------------------------------------------ //

  @Test
  void sanitizeForTableName_asciiLettersAndNumbers_preserved() {
    String result = TempDatasetService.sanitizeForTableName("mysqlstep");
    assertThat(result).startsWith("mysqlstep");
    assertThat(result).matches("[a-z0-9_]+");
  }

  @Test
  void sanitizeForTableName_koreanChars_replacedWithUnderscore() {
    String result = TempDatasetService.sanitizeForTableName("데이터 정제");
    // Korean and spaces become underscores; hash appended
    assertThat(result).matches("[a-z0-9_]+");
    assertThat(result).isNotBlank();
  }

  @Test
  void sanitizeForTableName_specialChars_replacedWithUnderscore() {
    String result = TempDatasetService.sanitizeForTableName("API-Call Step!");
    assertThat(result).matches("[a-z0-9_]+");
    assertThat(result).doesNotContain("-").doesNotContain(" ").doesNotContain("!");
  }

  @Test
  void sanitizeForTableName_multipleConsecutiveSpecialChars_deduplicated() {
    String result = TempDatasetService.sanitizeForTableName("step---name");
    assertThat(result).doesNotContain("__");
    // Should not start or end with underscore before hash
    assertThat(result).matches("[a-z0-9_]+");
  }

  @Test
  void sanitizeForTableName_emptyOrPureSpecial_fallsBackToStep() {
    String result = TempDatasetService.sanitizeForTableName("!!!");
    assertThat(result).startsWith("step");
  }

  @Test
  void sanitizeForTableName_longName_truncatedTo30Chars() {
    String longName = "a".repeat(50);
    String result = TempDatasetService.sanitizeForTableName(longName);
    // base part is max 30 chars + "_" + 4-char hash
    assertThat(result.length()).isLessThanOrEqualTo(35);
  }

  @Test
  void sanitizeForTableName_appendsHashSuffix() {
    String result1 = TempDatasetService.sanitizeForTableName("step");
    String result2 = TempDatasetService.sanitizeForTableName("STEP");
    // Different input → different hash even if base is the same
    assertThat(result1).isNotEqualTo(result2);
  }

  // ------------------------------------------------------------------ //
  // hasSchemaChanged
  // ------------------------------------------------------------------ //

  @Test
  void hasSchemaChanged_sameColumns_returnsFalse() {
    Long datasetId = 1L;
    List<DatasetColumnResponse> existing =
        List.of(col("id", "INTEGER", false), col("name", "TEXT", false));
    when(columnRepository.findByDatasetId(datasetId)).thenReturn(existing);

    List<ColumnInfo> newCols =
        List.of(new ColumnInfo("id", "INTEGER"), new ColumnInfo("name", "TEXT"));

    assertThat(tempDatasetService.hasSchemaChanged(datasetId, newCols)).isFalse();
  }

  @Test
  void hasSchemaChanged_differentColumnType_returnsTrue() {
    Long datasetId = 2L;
    List<DatasetColumnResponse> existing =
        List.of(col("id", "INTEGER", false), col("name", "TEXT", false));
    when(columnRepository.findByDatasetId(datasetId)).thenReturn(existing);

    List<ColumnInfo> newCols =
        List.of(new ColumnInfo("id", "INTEGER"), new ColumnInfo("name", "DECIMAL"));

    assertThat(tempDatasetService.hasSchemaChanged(datasetId, newCols)).isTrue();
  }

  @Test
  void hasSchemaChanged_additionalColumn_returnsTrue() {
    Long datasetId = 3L;
    List<DatasetColumnResponse> existing = List.of(col("id", "INTEGER", false));
    when(columnRepository.findByDatasetId(datasetId)).thenReturn(existing);

    List<ColumnInfo> newCols =
        List.of(new ColumnInfo("id", "INTEGER"), new ColumnInfo("extra", "TEXT"));

    assertThat(tempDatasetService.hasSchemaChanged(datasetId, newCols)).isTrue();
  }

  @Test
  void hasSchemaChanged_pkColumnExcludedFromComparison_returnsFalse() {
    Long datasetId = 4L;
    List<DatasetColumnResponse> existing =
        List.of(
            col("import_id", "INTEGER", false), // import_id excluded
            colPk("pk_col", "INTEGER"), // PK excluded
            col("name", "TEXT", false));
    when(columnRepository.findByDatasetId(datasetId)).thenReturn(existing);

    List<ColumnInfo> newCols = List.of(new ColumnInfo("name", "TEXT"));

    assertThat(tempDatasetService.hasSchemaChanged(datasetId, newCols)).isFalse();
  }

  // ------------------------------------------------------------------ //
  // validateColumnsForNewTemp (WD-95)
  // ------------------------------------------------------------------ //

  /**
   * 옛 TEMP 를 지우기 전 사전 검증 — 실제 생성과 같은 규칙(이름 길이 상한·예약어·타입)을 쓰는지 확인한다. 검증 메서드는 DB 를 쓰지 않으므로 실제 {@link
   * DataTableService} 를 의존성 없이 만들어 쓴다(목으로 두면 검증 자체가 사라져 공허해진다).
   */
  @Test
  void validateColumnsForNewTemp_63자는_통과하고_64자_예약어_미지원타입은_거부된다() {
    TempDatasetService service =
        new TempDatasetService(
            datasetRepository,
            datasetService,
            columnRepository,
            new DataTableService(null),
            org.mockito.Mockito.mock(PipelineDataSchema.class));
    String col63 = "c_" + "x".repeat(61);
    String col64 = "c_" + "x".repeat(62);

    service.validateColumnsForNewTemp(List.of(new ColumnInfo(col63, "TEXT")));

    assertThatThrownBy(
            () -> service.validateColumnsForNewTemp(List.of(new ColumnInfo(col64, "TEXT"))))
        .isInstanceOf(InvalidTableNameException.class)
        .hasMessage("필드명은 최대 63자까지 쓸 수 있습니다(입력 64자)");
    assertThatThrownBy(
            () -> service.validateColumnsForNewTemp(List.of(new ColumnInfo("id", "TEXT"))))
        .isInstanceOf(InvalidTableNameException.class)
        .hasMessageContaining("시스템 예약어");
    assertThatThrownBy(
            () -> service.validateColumnsForNewTemp(List.of(new ColumnInfo("ok", "NOPE"))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ------------------------------------------------------------------ //
  // Helpers
  // ------------------------------------------------------------------ //

  private DatasetColumnResponse col(String name, String dataType, boolean isPk) {
    return new DatasetColumnResponse(null, name, null, dataType, null, true, false, null, 0, isPk);
  }

  private DatasetColumnResponse colPk(String name, String dataType) {
    return new DatasetColumnResponse(null, name, null, dataType, null, false, false, null, 0, true);
  }
}
