package com.smartfirehub.dataset.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.analytics.service.InteractiveQueryConcurrencyGate;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.dto.RowDataRequest;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.repository.DatasetWriteTarget;
import com.smartfirehub.dataset.repository.QueryHistoryRepository;
import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.pipeline.service.PipelineDataSchema;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.securitylevel.sql.GuardedSqlExecutor;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 행 API 의 길이 미지정 VARCHAR 한도가 현재 테넌트 저장소의 기본 길이를 따르는지 검증한다(WD-659).
 *
 * <p>Tibero 는 길이 미지정 VARCHAR 를 VARCHAR2(4000 CHAR) 로 만들므로 300자를 받아야 하고 4001자는 거부해야 한다. PG 는
 * VARCHAR(255) 그대로다. 실제 Tibero 없이 저장소 판정({@link DataTableService#currentBackend()})만 고정하고, INSERT 는
 * mock 이 받는다 — 검증을 통과한 값이 그대로 INSERT 로 넘어가는지와 거부 시 INSERT 가 불리지 않는지를 본다.
 */
class DatasetDataServiceVarcharBackendTest {

  private static final long DATASET_ID = 1L;

  /** 길이 미지정 VARCHAR 한 칸짜리 데이터셋 컬럼. */
  private static final DatasetColumnResponse NO_LENGTH_VARCHAR =
      new DatasetColumnResponse(1L, "memo", "메모", "VARCHAR", null, true, false, null, 0, false);

  /** 저장소 판정과 행 저장 mock 을 묶은 테스트 대상. */
  private record Fixture(DatasetDataService service, DataTableRowService rows) {}

  /** 지정한 저장소를 현재 테넌트 저장소로 보는 서비스를 만든다 — 그 밖의 협력자는 행 추가에 필요한 만큼만 채운다. */
  private static Fixture fixture(DataStoreBackend backend, DatasetColumnResponse column) {
    DataTableService tables = mock(DataTableService.class);
    when(tables.currentBackend()).thenReturn(backend);
    DatasetColumnRepository columns = mock(DatasetColumnRepository.class);
    when(columns.findByDatasetId(DATASET_ID)).thenReturn(List.of(column));
    DatasetDataWriteLock writeLock = mock(DatasetDataWriteLock.class);
    when(writeLock.lockForDataWrite(DATASET_ID))
        .thenReturn(new DatasetWriteTarget(DATASET_ID, "메모", "memo_table", "TABLE"));
    DataTableRowService rows = mock(DataTableRowService.class);
    when(rows.insertRow(any(), anyList(), anyMap(), anyMap())).thenReturn(7L);
    when(rows.getRow(any(), anyList(), anyLong(), anyMap())).thenReturn(Map.of("id", 7L));
    DatasetDataService service =
        new DatasetDataService(
            mock(DatasetRepository.class),
            columns,
            tables,
            rows,
            mock(GuardedSqlExecutor.class),
            mock(ClearanceResolver.class),
            mock(QueryHistoryRepository.class),
            writeLock,
            mock(TransactionTemplate.class),
            mock(PipelineDataSchema.class),
            mock(DatasetAuditRecorder.class),
            mock(InteractiveQueryConcurrencyGate.class));
    return new Fixture(service, rows);
  }

  /** Tibero: 길이 미지정 VARCHAR 에 300자(PG 기본 255 초과)를 넣으면 검증을 통과해 값 그대로 INSERT 로 넘어간다. */
  @Test
  void tibero_noLengthVarchar_accepts300Chars() {
    Fixture f = fixture(DataStoreBackend.TIBERO, NO_LENGTH_VARCHAR);
    String v300 = "가".repeat(300);

    f.service().addRow(DATASET_ID, new RowDataRequest(Map.of("memo", v300)));

    verify(f.rows()).insertRow(eq("memo_table"), anyList(), eq(Map.of("memo", v300)), anyMap());
  }

  /** Tibero: 기본 길이 4000 을 넘는 4001자는 저장소 한도 사유로 거부하고 INSERT 하지 않는다. */
  @Test
  void tibero_noLengthVarchar_rejects4001Chars() {
    Fixture f = fixture(DataStoreBackend.TIBERO, NO_LENGTH_VARCHAR);

    assertThatThrownBy(
            () ->
                f.service()
                    .addRow(DATASET_ID, new RowDataRequest(Map.of("memo", "a".repeat(4001)))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("최대 4000자까지 입력할 수 있습니다(입력 4001자)");
    verify(f.rows(), never()).insertRow(any(), anyList(), anyMap(), anyMap());
  }

  /** PG: 길이 미지정 VARCHAR 는 예전처럼 255자가 한도 — 300자는 거부한다. */
  @Test
  void postgres_noLengthVarchar_stillRejects300Chars() {
    Fixture f = fixture(DataStoreBackend.POSTGRESQL, NO_LENGTH_VARCHAR);

    assertThatThrownBy(
            () ->
                f.service().addRow(DATASET_ID, new RowDataRequest(Map.of("memo", "a".repeat(300)))))
        .hasMessageContaining("최대 255자까지 입력할 수 있습니다(입력 300자)");
    verify(f.rows(), never()).insertRow(any(), anyList(), anyMap(), anyMap());
  }

  /** 명시한 길이는 저장소 기본 길이보다 우선한다 — Tibero 에서도 VARCHAR(10) 은 11자를 거부한다. */
  @Test
  void tibero_explicitLength_wins() {
    DatasetColumnResponse code =
        new DatasetColumnResponse(2L, "code", "코드", "VARCHAR", 10, true, false, null, 0, false);
    Fixture f = fixture(DataStoreBackend.TIBERO, code);

    assertThatThrownBy(
            () ->
                f.service().addRow(DATASET_ID, new RowDataRequest(Map.of("code", "a".repeat(11)))))
        .hasMessageContaining("최대 10자까지 입력할 수 있습니다(입력 11자)");
  }
}
