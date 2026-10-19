package com.smartfirehub.dataset.storage;

import com.smartfirehub.dataset.service.PostgresDataRowStore;
import com.smartfirehub.dataset.service.PostgresDataTableStore;
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
}
