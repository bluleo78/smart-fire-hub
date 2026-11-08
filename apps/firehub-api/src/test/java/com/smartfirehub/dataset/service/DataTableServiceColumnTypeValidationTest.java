package com.smartfirehub.dataset.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.storage.DataStoreResolver;
import com.smartfirehub.dataset.storage.DataTableStore;
import com.smartfirehub.dataset.storage.tibero.TiberoDialect;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * WD-636 — 새 컬럼 타입 검증({@link DataTableService#validateNewColumns})이 파사드의 PG 사본이 아니라 현재 테넌트 저장소의
 * 검증({@link DataTableStore#validateColumnType})을 쓰는지 고정한다.
 *
 * <p>결함: 파사드가 PG 상한(10000)으로만 검사해, Tibero 데이터셋의 VARCHAR 4001~10000 은 앞단을 통과한 뒤 DDL 조립 단계에서야 걸렸다.
 * 저장소에 맡기면 백엔드별 상한(Tibero 4000)이 DDL 전에 같은 문구로 적용된다.
 */
class DataTableServiceColumnTypeValidationTest {

  private DataTableStore store;
  private DataTableService service;

  @BeforeEach
  void setUp() {
    DataStoreResolver resolver = mock(DataStoreResolver.class);
    store = mock(DataTableStore.class);
    when(resolver.tableStore()).thenReturn(store);
    when(store.isReservedColumnName(anyString())).thenReturn(false);
    service = new DataTableService(resolver);
  }

  /** 각 컬럼의 타입·길이가 그대로 저장소 검증으로 넘어간다. */
  @Test
  void validateNewColumns_delegatesTypeCheckToStore() {
    service.validateNewColumns(
        List.of(
            new DatasetColumnRequest("a", null, "VARCHAR", 5000, true, false, null),
            new DatasetColumnRequest("b", null, "INTEGER", null, true, false, null)));

    verify(store).validateColumnType("VARCHAR", 5000);
    verify(store).validateColumnType("INTEGER", null);
  }

  /** 저장소가 거부하면(예: Tibero 상한 초과) 그 예외·문구가 그대로 호출자에게 간다. */
  @Test
  void validateNewColumns_propagatesStoreRejection() {
    doThrow(new IllegalArgumentException("문자열(VARCHAR) 필드 길이는 1~4000 사이여야 합니다(입력 5000)."))
        .when(store)
        .validateColumnType("VARCHAR", 5000);

    assertThatThrownBy(
            () ->
                service.validateNewColumns(
                    List.of(
                        new DatasetColumnRequest("a", null, "VARCHAR", 5000, true, false, null))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("문자열(VARCHAR) 필드 길이는 1~4000 사이여야 합니다(입력 5000).");
  }

  /** 타입 변경 경로의 사전 검증도 같은 저장소 검증을 쓴다. */
  @Test
  void validateColumnType_delegatesToStore() {
    service.validateColumnType("VARCHAR", 300);

    verify(store).validateColumnType("VARCHAR", 300);
  }

  /**
   * WD-636 리뷰 — Tibero 저장소의 DDL 조립({@code TiberoDialect.columnDdl})은 별칭 BIGINT·NUMERIC 도 받아 준다. 앱은
   * 정준 타입(INTEGER· DECIMAL)으로만 분기하므로 파사드가 정준 타입 집합을 먼저 보고 저장소 검증 전에 400 문구로 막아야 한다.
   */
  @Test
  void aliasTypes_rejectedBeforeLenientTiberoStore() {
    // 저장소 검증을 실제 Tibero 규칙(별칭 허용)으로 흉내 낸다.
    doAnswer(
            inv -> {
              TiberoDialect.columnDdl(inv.getArgument(0), inv.getArgument(1));
              return null;
            })
        .when(store)
        .validateColumnType(any(), any());

    for (String alias : List.of("bigint", "BIGINT", "NUMERIC")) {
      assertThatThrownBy(
              () ->
                  service.validateNewColumns(
                      List.of(new DatasetColumnRequest("a", null, alias, null, true, false, null))))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("알 수 없는 데이터 타입입니다: " + alias);
      assertThatThrownBy(() -> service.validateColumnType(alias, null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("알 수 없는 데이터 타입입니다: " + alias);
    }
    verify(store, never()).validateColumnType(any(), any());
  }
}
