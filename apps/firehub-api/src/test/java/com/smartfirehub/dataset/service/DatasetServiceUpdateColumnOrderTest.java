package com.smartfirehub.dataset.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.dto.DatasetResponse;
import com.smartfirehub.dataset.dto.UpdateColumnRequest;
import com.smartfirehub.dataset.exception.ColumnModificationException;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.rowsearch.SearchColumnRepository;
import com.smartfirehub.mapping.service.MappingReferenceService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link DatasetService#updateColumn} 의 검증 순서 단위 테스트(WD-74).
 *
 * <p>외부 저장소(Tibero)의 DDL 은 자동 커밋이라 PG 처럼 트랜잭션 롤백으로 되돌릴 수 없다. 그래서 던질 수 있는 검증은 모두 첫 물리 DDL({@code
 * renameIndex}/{@code renameColumn}) 전에 끝나야 한다. 통합 테스트(PG)로는 롤백 덕분에 늘 통과하므로 저장소 파사드를 가짜로 두고 "DDL 호출
 * 없음" 을 직접 확인한다. 스프링 컨텍스트를 띄우지 않는다(사용하지 않는 의존성은 null 로 주입된다).
 */
@ExtendWith(MockitoExtension.class)
class DatasetServiceUpdateColumnOrderTest {

  private static final long DATASET_ID = 1L;
  private static final long COLUMN_ID = 10L;

  @Mock private DatasetRepository datasetRepository;
  @Mock private DatasetColumnRepository columnRepository;
  @Mock private DataTableService dataTableService;
  @Mock private DataTableRowService dataTableRowService;
  @Mock private SearchColumnRepository searchColumnRepository;
  // WD-186: updateColumn 이 매핑 잠금·타입 판정·이름 연쇄 갱신을 부르므로 가짜로 둔다(없으면 null 주입으로 NPE).
  @Mock private MappingReferenceService mappingReferenceService;

  @InjectMocks private DatasetService datasetService;

  @BeforeEach
  void setUp() {
    when(datasetRepository.findById(DATASET_ID))
        .thenReturn(
            Optional.of(
                new DatasetResponse(
                    DATASET_ID,
                    "ds",
                    "t",
                    null,
                    null,
                    "TABLE",
                    null,
                    null,
                    false,
                    List.of(),
                    "NONE",
                    null,
                    null,
                    null,
                    null)));
    when(columnRepository.findDatasetIdByColumnId(COLUMN_ID)).thenReturn(Optional.of(DATASET_ID));
  }

  /** 컬럼 메타데이터: 이름 code, PK 여부·NULL 허용·인덱스는 인자대로. */
  private void column(boolean primaryKey, boolean nullable, boolean indexed) {
    when(columnRepository.findById(COLUMN_ID))
        .thenReturn(
            Optional.of(
                new DatasetColumnResponse(
                    COLUMN_ID,
                    "code",
                    "코드",
                    "TEXT",
                    null,
                    nullable,
                    indexed,
                    null,
                    0,
                    primaryKey)));
  }

  /** 어떤 물리 DDL 도, 메타 저장(매핑의 필드 이름 연쇄 갱신 포함)도 하지 않았는지. */
  private void verifyNoDdl() {
    verify(dataTableService, never()).renameIndex(anyString(), anyString(), anyString());
    verify(dataTableService, never()).renameColumn(anyString(), anyString(), anyString());
    verify(mappingReferenceService, never()).renameColumn(anyLong(), anyString(), anyString());
    verify(dataTableService, never())
        .alterColumnType(anyString(), anyString(), anyString(), any(), any());
    verify(dataTableService, never()).setColumnNullable(anyString(), anyString(), anyBoolean());
    verify(dataTableService, never()).setColumnIndex(anyString(), anyString(), anyBoolean(), any());
    verify(columnRepository, never()).update(any(), any());
  }

  @Test
  void renameWithNullableOnPrimaryKey_throwsBeforeAnyDdl() {
    // 예전에는 renameIndex·renameColumn(Tibero 는 자동 커밋)을 먼저 실행한 뒤 PK NULL 검사로 실패해 물리 이름만 바뀌었다.
    column(true, false, true);
    when(dataTableRowService.countRows("t")).thenReturn(0L);
    UpdateColumnRequest request =
        new UpdateColumnRequest("new_code", null, null, null, true, null, null, null);

    assertThatThrownBy(() -> datasetService.updateColumn(DATASET_ID, COLUMN_ID, request))
        .isInstanceOf(ColumnModificationException.class)
        .hasMessage("기본 키 필드는 NULL 을 허용할 수 없습니다: code");

    verifyNoDdl();
  }

  @Test
  void renameWithTypeChangeOfSearchField_throwsBeforeAnyDdl() {
    column(false, true, false);
    when(dataTableRowService.countRows("t")).thenReturn(0L);
    when(searchColumnRepository.isSearchable(COLUMN_ID)).thenReturn(true);
    UpdateColumnRequest request =
        new UpdateColumnRequest("new_code", null, "INTEGER", null, null, null, null, null);

    assertThatThrownBy(() -> datasetService.updateColumn(DATASET_ID, COLUMN_ID, request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("검색 대상 필드는 TEXT/VARCHAR 이외 타입으로 바꿀 수 없습니다. 검색 탭에서 먼저 해제하세요.");

    verifyNoDdl();
  }

  @Test
  void setPrimaryKeyOnNullOnlyColumn_throwsNullMessageNotDuplicateMessage() {
    // NULL 만 있는 컬럼: 예전처럼 NOT NULL 메시지가 나야 하고(중복 검사는 NULL 을 세지 않는다), DDL 은 없다.
    column(false, true, false);
    when(dataTableRowService.countRows("t")).thenReturn(5L);
    when(dataTableRowService.hasNulls("t", "code")).thenReturn(true);
    UpdateColumnRequest request =
        new UpdateColumnRequest(null, null, null, null, false, null, null, true);

    assertThatThrownBy(() -> datasetService.updateColumn(DATASET_ID, COLUMN_ID, request))
        .isInstanceOf(ColumnModificationException.class)
        .hasMessage("NOT NULL 로 변경할 수 없습니다: 컬럼 'code' 에 NULL 값이 존재합니다.");

    verify(dataTableRowService, never()).checkDataUniqueness(anyString(), any());
    verifyNoDdl();
  }

  @Test
  void setPrimaryKeyOnDuplicateData_throwsBeforeNullableDdl() {
    // NOT NULL 로 바꾸며 PK 로 지정 — 중복 검사가 실패하면 setColumnNullable(DDL)도 실행하지 않는다.
    column(false, true, false);
    when(dataTableRowService.countRows("t")).thenReturn(5L);
    when(columnRepository.findByDatasetId(DATASET_ID))
        .thenReturn(
            List.of(
                new DatasetColumnResponse(
                    COLUMN_ID, "code", "코드", "TEXT", null, true, false, null, 0, false)));
    when(dataTableRowService.hasNulls("t", "code")).thenReturn(false);
    when(dataTableRowService.checkDataUniqueness("t", List.of("code"))).thenReturn(false);
    UpdateColumnRequest request =
        new UpdateColumnRequest(null, null, null, null, false, null, null, true);

    assertThatThrownBy(() -> datasetService.updateColumn(DATASET_ID, COLUMN_ID, request))
        .isInstanceOf(ColumnModificationException.class)
        .hasMessage("기본 키를 설정할 수 없습니다. 데이터에 중복값이 존재합니다.");

    verifyNoDdl();
  }
}
