package com.smartfirehub.dataset.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetResponse;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.search.DatasetEmbeddingService;
import com.smartfirehub.dataset.storage.DataTableStore.RebuildBackupState;
import com.smartfirehub.securitylevel.service.DatasetSecurityService;
import java.util.List;
import java.util.Optional;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 러너 TEMP 재생성의 컬럼 메타 지문과 크래시 흔적 맞춤(WD-256) 단위 테스트 — 지문이 컬럼 id·이름·타입으로만 정해지고, 재생성이 <b>바꾸기 전</b> 스냅숏의
 * 지문을 보관에 넘기며, 맞춤이 현재 메타 지문으로 저장소에 맡기고 결과(RESTORED)에 따라 검색 원문을 다시 맞추는지 본다. 스프링 컨텍스트를 띄우지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class DatasetServicePipelineTempReconcileTest {

  private static final long DATASET_ID = 7L;
  private static final String TABLE = "ptmp_77_2s";

  @Mock private DatasetRepository datasetRepository;
  @Mock private DatasetColumnRepository columnRepository;
  @Mock private DataTableService dataTableService;
  @Mock private DatasetSecurityService datasetSecurityService;
  @Mock private DatasetEmbeddingService datasetEmbeddingService;
  @Mock private ApplicationEventPublisher events;

  @InjectMocks private DatasetService datasetService;

  @BeforeEach
  void pipelineTemp() {
    org.mockito.Mockito.lenient()
        .when(datasetRepository.findById(DATASET_ID))
        .thenReturn(
            Optional.of(
                new DatasetResponse(
                    DATASET_ID,
                    "ds",
                    TABLE,
                    null,
                    null,
                    "TABLE",
                    "TEMP",
                    null,
                    false,
                    List.of(),
                    "NONE",
                    null,
                    null,
                    null,
                    100L)));
  }

  private static Record col(long id, String name, String type) {
    return DSL.using(SQLDialect.POSTGRES)
        .newRecord(
            DSL.field("id", Long.class),
            DSL.field("column_name", String.class),
            DSL.field("data_type", String.class),
            DSL.field("column_order", Integer.class))
        .values(id, name, type, 0);
  }

  @Test
  void fingerprint_is32LowerHex_stable_andChangesWithIdNameType_orderIndependent() {
    List<Record> base = List.of(col(1, "code", "TEXT"), col(2, "amount", "INTEGER"));
    String fp = DatasetService.columnMetaFingerprint(base);

    assertThat(fp).matches("[0-9a-f]{32}");
    assertThat(
            DatasetService.columnMetaFingerprint(
                List.of(col(1, "code", "TEXT"), col(2, "amount", "INTEGER"))))
        .as("같은 입력이면 같다")
        .isEqualTo(fp);
    assertThat(
            DatasetService.columnMetaFingerprint(
                List.of(col(2, "amount", "INTEGER"), col(1, "code", "TEXT"))))
        .as("id 순으로 정렬하므로 입력 순서(column_order)와 무관")
        .isEqualTo(fp);
    assertThat(
            DatasetService.columnMetaFingerprint(
                List.of(col(1, "code", "TEXT"), col(3, "amount", "INTEGER"))))
        .as("id 가 바뀌면(재생성은 행을 새로 넣는다) 다르다")
        .isNotEqualTo(fp);
    assertThat(
            DatasetService.columnMetaFingerprint(
                List.of(col(1, "code", "TEXT"), col(2, "amount2", "INTEGER"))))
        .isNotEqualTo(fp);
    assertThat(
            DatasetService.columnMetaFingerprint(
                List.of(col(1, "code", "TEXT"), col(2, "amount", "BIGINT"))))
        .as("물리 열이 같아도(INTEGER·BIGINT) 메타 타입이 다르면 다르다")
        .isNotEqualTo(fp);
    // 구분자 모호성: 이름·타입 경계가 옮겨 가도 같은 지문이 나오지 않는다.
    assertThat(DatasetService.columnMetaFingerprint(List.of(col(1, "ab", "C"))))
        .isNotEqualTo(DatasetService.columnMetaFingerprint(List.of(col(1, "a", "BC"))));
  }

  @Test
  void fingerprint_knownVector_pinsSha256Hex() {
    // 지문 알고리즘(SHA-256 앞 32자 hex)이 구현 변경에도 바이트 단위로 같음을 고정한다 — 이미 남은 보관본의 지문과 맞아야 한다.
    // 입력 문자열: "1" US "ab" US "C" RS
    assertThat(DatasetService.columnMetaFingerprint(List.of(col(1, "ab", "C"))))
        .isEqualTo("dc8260781e6d69d7906d000c6df5cba0");
  }

  @Test
  void rebuild_passesFingerprintOfPreRebuildSnapshotToBackup() {
    List<Record> snapshot = List.of(col(1, "code", "TEXT"));
    when(columnRepository.snapshotByDatasetId(DATASET_ID)).thenReturn(snapshot);
    when(dataTableService.moveToRebuildBackup(eq(TABLE), any())).thenReturn(77L);

    DatasetService.PipelineTempRebuild rebuild =
        datasetService.rebuildPipelineTempTable(
            DATASET_ID,
            List.of(
                new DatasetColumnRequest("amount", "amount", "INTEGER", null, true, false, null)));

    verify(dataTableService)
        .moveToRebuildBackup(TABLE, DatasetService.columnMetaFingerprint(snapshot));
    assertThat(rebuild.backupTableOid()).isEqualTo(77L);
    assertThat(rebuild.columnSnapshot()).isSameAs(snapshot);
  }

  /**
   * 되돌리기는 저장소 확인(Tibero: 보관본 OID 확인 + 물리 복원을 커밋 뒤로 예약)을 메타 쓰기보다 먼저 한다(리뷰 C1) — 보관본이 없거나 남의 것이면 메타를
   * 건드리기 전에 실패한다.
   */
  @Test
  void restore_storeCheckBeforeMetaWrites_failureLeavesMetaUntouched() {
    DatasetService.PipelineTempRebuild rebuild =
        new DatasetService.PipelineTempRebuild(
            DATASET_ID, TABLE, DataTableService.rebuildBackupTableName(TABLE), 77L, List.of(), 3L);

    datasetService.restorePipelineTempTable(rebuild, 1L);
    org.mockito.InOrder order = org.mockito.Mockito.inOrder(dataTableService, columnRepository);
    order.verify(dataTableService).restoreRebuildBackup(TABLE, rebuild.backupTableName(), 77L);
    order.verify(columnRepository).restoreSnapshot(DATASET_ID, List.of());

    org.mockito.Mockito.clearInvocations(columnRepository, datasetSecurityService);
    org.mockito.Mockito.doThrow(new IllegalStateException("보관본이 없어"))
        .when(dataTableService)
        .restoreRebuildBackup(any(), any(), anyLong());
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> datasetService.restorePipelineTempTable(rebuild, 1L))
        .isInstanceOf(IllegalStateException.class);
    verify(columnRepository, never()).restoreSnapshot(any(), any());
    org.mockito.Mockito.verifyNoInteractions(datasetSecurityService);
  }

  @Test
  void reconcile_delegatesWithCurrentMetaFingerprint_restoredReindexesSearch() {
    List<Record> current = List.of(col(5, "code", "TEXT"));
    when(columnRepository.snapshotByDatasetId(DATASET_ID)).thenReturn(current);
    when(dataTableService.reconcileRebuildBackup(
            TABLE, DatasetService.columnMetaFingerprint(current)))
        .thenReturn(RebuildBackupState.RESTORED);

    assertThat(datasetService.reconcilePipelineTempTable(DATASET_ID))
        .isEqualTo(RebuildBackupState.RESTORED);
    verify(datasetEmbeddingService).syncSourceText(DATASET_ID);
  }

  @Test
  void reconcile_noneOrDropped_doesNotReindex() {
    when(columnRepository.snapshotByDatasetId(DATASET_ID)).thenReturn(List.of());
    when(dataTableService.reconcileRebuildBackup(eq(TABLE), any()))
        .thenReturn(RebuildBackupState.NONE, RebuildBackupState.DROPPED_LEFTOVER);

    assertThat(datasetService.reconcilePipelineTempTable(DATASET_ID))
        .isEqualTo(RebuildBackupState.NONE);
    assertThat(datasetService.reconcilePipelineTempTable(DATASET_ID))
        .isEqualTo(RebuildBackupState.DROPPED_LEFTOVER);
    verify(datasetEmbeddingService, never()).syncSourceText(anyLong());
  }
}
