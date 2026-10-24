package com.smartfirehub.dataset.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.smartfirehub.dataset.exception.InvalidTableNameException;
import com.smartfirehub.dataset.storage.DataRowStore;
import com.smartfirehub.dataset.storage.DataStoreResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * 임시 테이블 쓰기 창구({@code DataTableRowService#insertTempBatch}, WD-77 Task 6)의 백엔드 중립 규칙.
 *
 * <p>PG 는 임시 테이블이 일반 테이블 모양이라 기본 구현이 예전 {@code insertBatch(임시, …)} 호출과 <b>똑같이</b> 위임해야 한다(PG 동작
 * 불변). Tibero 의 스테이징 전용 동작은 {@code TiberoDataRowStoreTest} 가 고정한다.
 */
class DataTableRowServiceTempBatchTest {

  private static final List<Map<String, Object>> ROWS = List.of(Map.of("name", "a"));
  private static final Map<String, String> TYPES = Map.of("name", "TEXT");

  private final DataStoreResolver resolver = mock(DataStoreResolver.class);
  private final DataRowStore rowStore = mock(DataRowStore.class, CALLS_REAL_METHODS);
  private final DataTableRowService service = new DataTableRowService(resolver);

  @Test
  void pgDefault_delegatesToInsertBatchWithTheSameArguments() {
    when(resolver.rowStore()).thenReturn(rowStore);
    Mockito.doNothing().when(rowStore).insertBatch(any(), any(), any(), any());

    service.insertTempBatch("rtmp_abc_orders", List.of("name"), ROWS, TYPES);

    verify(rowStore).insertBatch("rtmp_abc_orders", List.of("name"), ROWS, TYPES);
  }

  @Test
  void pgDefault_nullColumnTypesStayNull_likeTheThreeArgInsertBatch() {
    when(resolver.rowStore()).thenReturn(rowStore);
    Mockito.doNothing().when(rowStore).insertBatch(any(), any(), any(), any());

    service.insertTempBatch("rtmp_abc_orders", List.of("name"), ROWS, null);

    verify(rowStore).insertBatch("rtmp_abc_orders", List.of("name"), ROWS, null);
  }

  @Test
  void pgDefault_stillValidatesTheTempNameBeforeTouchingTheStore() {
    when(resolver.rowStore()).thenReturn(rowStore);

    assertThatThrownBy(() -> service.insertTempBatch("Bad-Name", List.of("name"), ROWS, null))
        .isInstanceOf(InvalidTableNameException.class);

    verify(rowStore, Mockito.never()).insertBatch(any(), any(), any(), any());
  }

  @Test
  void invalidColumnNames_areRejectedBeforeResolvingTheStore() {
    assertThatThrownBy(
            () -> service.insertTempBatch("rtmp_abc_orders", List.of("Bad Col"), ROWS, null))
        .isInstanceOf(InvalidTableNameException.class);

    verifyNoInteractions(resolver);
  }
}
