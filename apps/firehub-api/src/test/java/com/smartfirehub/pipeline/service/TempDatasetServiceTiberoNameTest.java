package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetDetailResponse;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.service.DataTableService;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.dataset.storage.tibero.TiberoNames;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Tibero 테넌트의 러너 TEMP 테이블 이름 규칙 검증(WD-77 Task 7, 스펙 §4.5) — 물리(대문자) 이름이 30자 이내이고, 같은 파이프라인·단계면 단계
 * 이름이 바뀌어도 같은 이름이 나와야 한다.
 */
class TempDatasetServiceTiberoNameTest {

  @Test
  void usualIds_followPtmpPipelineStepBase36Format() {
    assertThat(TempDatasetService.tiberoTempTableName(77L, 100L)).isEqualTo("ptmp_77_2s");
    assertThat(TempDatasetService.tiberoTempTableName(1L, 0L)).isEqualTo("ptmp_1_0");
  }

  @Test
  void sameStep_sameName_differentStep_differentName() {
    assertThat(TempDatasetService.tiberoTempTableName(5L, 9L))
        .isEqualTo(TempDatasetService.tiberoTempTableName(5L, 9L));
    assertThat(TempDatasetService.tiberoTempTableName(5L, 9L))
        .isNotEqualTo(TempDatasetService.tiberoTempTableName(5L, 10L));
    assertThat(TempDatasetService.tiberoTempTableName(5L, 9L))
        .isNotEqualTo(TempDatasetService.tiberoTempTableName(6L, 9L));
  }

  @Test
  void hugeIds_stillFitThirtyChars_andStayStable() {
    String name = TempDatasetService.tiberoTempTableName(Long.MAX_VALUE, Long.MAX_VALUE);
    assertThat(TiberoNames.physical(name).length()).isLessThanOrEqualTo(30);
    assertThat(name)
        .isEqualTo(TempDatasetService.tiberoTempTableName(Long.MAX_VALUE, Long.MAX_VALUE));
    assertThat(name).isNotEqualTo(TempDatasetService.tiberoTempTableName(Long.MAX_VALUE, 1L));
    // 사용자 이름 규칙(길이·예약어·스테이징 형식)을 통과하고 메타데이터 이름 형식(소문자 시작)이다.
    TiberoNames.validateUserName(name);
    assertThat(name).matches("^[a-z][a-z0-9_]*$");
  }

  @Test
  void boundary_lengthsNeverExceedThirty() {
    for (long p : new long[] {1L, 99_999_999L, 9_999_999_999_999L, Long.MAX_VALUE}) {
      for (long s : new long[] {0L, 35L, 36L, 1_000_000L, Long.MAX_VALUE}) {
        assertThat(TempDatasetService.tiberoTempTableName(p, s).length())
            .as("p=%d s=%d", p, s)
            .isLessThanOrEqualTo(30);
      }
    }
  }

  @Test
  void createTempDataset_onTibero_usesStableNameIgnoringStepName() {
    DatasetService datasetService = mock(DatasetService.class);
    PipelineDataSchema schema = mock(PipelineDataSchema.class);
    when(schema.isTibero()).thenReturn(true);
    DatasetDetailResponse created =
        new DatasetDetailResponse(
            42L, "d", "t", null, null, "TABLE", "TEMP", null, List.of(), 0L, null, null, null,
            false, List.of(), null, null, null, null, List.of(), null);
    when(datasetService.createPipelineTempDataset(any(), any())).thenReturn(created);
    TempDatasetService service = service(datasetService, schema);

    service.createTempDataset(
        List.of(new ColumnInfo("amount", "DECIMAL")), 77L, "p", 100L, "이름 A", 1L);
    service.createTempDataset(
        List.of(new ColumnInfo("amount", "DECIMAL")), 77L, "p", 100L, "이름이 바뀐 단계", 1L);

    ArgumentCaptor<CreateDatasetRequest> req = ArgumentCaptor.forClass(CreateDatasetRequest.class);
    verify(datasetService, times(2)).createPipelineTempDataset(req.capture(), any());
    assertThat(req.getAllValues())
        .extracting(CreateDatasetRequest::tableName)
        .containsExactly("ptmp_77_2s", "ptmp_77_2s");
  }

  @Test
  void createTempDataset_onPg_keepsExistingNameRule() {
    DatasetService datasetService = mock(DatasetService.class);
    PipelineDataSchema schema = mock(PipelineDataSchema.class);
    when(schema.isTibero()).thenReturn(false);
    DatasetDetailResponse created =
        new DatasetDetailResponse(
            1L, "d", "t", null, null, "TABLE", "TEMP", null, List.of(), 0L, null, null, null, false,
            List.of(), null, null, null, null, List.of(), null);
    when(datasetService.createPipelineTempDataset(any(), any())).thenReturn(created);
    TempDatasetService service = service(datasetService, schema);

    service.createTempDataset(List.of(new ColumnInfo("a", "TEXT")), 77L, "p", 100L, "step one", 1L);

    ArgumentCaptor<CreateDatasetRequest> req = ArgumentCaptor.forClass(CreateDatasetRequest.class);
    verify(datasetService).createPipelineTempDataset(req.capture(), any());
    assertThat(req.getValue().tableName())
        .isEqualTo("ptmp_77_" + TempDatasetService.sanitizeForTableName("step one"));
  }

  @Test
  void validateColumnsForNewTemp_onTibero_rejects31CharColumnWithKoreanMessage() {
    TempDatasetService service = service(mock(DatasetService.class), tiberoSchema(true));
    String col31 = "c" + "x".repeat(30);

    assertThatThrownBy(
            () -> service.validateColumnsForNewTemp(List.of(new ColumnInfo(col31, "TEXT"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("30자를 넘을 수 없습니다");
    // 30자는 통과한다.
    service.validateColumnsForNewTemp(List.of(new ColumnInfo("c" + "x".repeat(29), "TEXT")));
  }

  @Test
  void validateColumnsForNewTemp_onTibero_rejectsReservedWords() {
    TempDatasetService service = service(mock(DatasetService.class), tiberoSchema(true));

    for (String reserved : List.of("date", "size")) {
      assertThatThrownBy(
              () -> service.validateColumnsForNewTemp(List.of(new ColumnInfo(reserved, "TEXT"))))
          .as(reserved)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Tibero 예약어라 사용할 수 없는 이름입니다");
    }
  }

  @Test
  void validateColumnsForNewTemp_onPg_usesOnlyTheOldCheck() {
    TempDatasetService service = service(mock(DatasetService.class), tiberoSchema(false));

    // PG 는 30자 초과·Tibero 예약어(date) 모두 기존 검사만 통과하면 된다.
    assertThatCode(
            () ->
                service.validateColumnsForNewTemp(
                    List.of(
                        new ColumnInfo("c" + "x".repeat(40), "TEXT"),
                        new ColumnInfo("date", "TEXT"))))
        .doesNotThrowAnyException();
  }

  private static PipelineDataSchema tiberoSchema(boolean tibero) {
    PipelineDataSchema schema = mock(PipelineDataSchema.class);
    when(schema.isTibero()).thenReturn(tibero);
    return schema;
  }

  private static TempDatasetService service(
      DatasetService datasetService, PipelineDataSchema schema) {
    return new TempDatasetService(
        mock(DatasetRepository.class),
        datasetService,
        mock(DatasetColumnRepository.class),
        new DataTableService(null),
        schema);
  }
}
