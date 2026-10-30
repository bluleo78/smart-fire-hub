package com.smartfirehub.dataset.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataset.service.PostgresDataRowStore;
import com.smartfirehub.dataset.service.PostgresDataTableStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** PostgreSQL 구현에 대한 저장소 백엔드 계약 테스트(WD-72). 현행 PG 동작이 계약의 기준이다. */
class PostgresDataStoreContractTest extends DataStoreContractTest {

  @Autowired private PostgresDataTableStore tableStore;
  @Autowired private PostgresDataRowStore rowStore;

  @Override
  protected DataTableStore tableStore() {
    return tableStore;
  }

  @Override
  protected DataRowStore rowStore() {
    return rowStore;
  }

  /** PG 식별자 규칙([a-z][a-z0-9_]*)에 맞는 고유 이름. */
  @Override
  protected String newTableName() {
    return "contract_" + System.nanoTime();
  }

  /**
   * 변경 카운터(WD-399)는 PG 만 준다 — 빈 테이블은 0, 없는 테이블은 빠지고 예외가 나지 않는다. 행이 있는 테이블의 값은 통계가 비동기로 갱신돼 여기서 단언하지
   * 않는다(폴러의 판정 규칙은 TriggerEventServiceChangeCounterTest 가 본다).
   */
  @Test
  void 변경_카운터는_없는_테이블을_뺀다() {
    String empty = createEmptyContractTable();
    String missing = newTableName();

    Map<String, DataRowStore.ChangeCounter> counters =
        rowStore.changeCounters(List.of(empty, missing));
    assertThat(counters).containsOnlyKeys(empty);
    assertThat(counters.get(empty).counter()).isZero();
    assertThat(counters.get(empty).relationId()).isPositive();
    assertThat(rowStore.changeCounters(List.of())).isEmpty();
  }
}
