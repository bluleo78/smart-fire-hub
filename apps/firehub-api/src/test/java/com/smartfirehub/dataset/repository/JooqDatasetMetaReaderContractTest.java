package com.smartfirehub.dataset.repository;

import com.smartfirehub.dataset.repository.jooq.JooqDatasetMetaReader;
import org.springframework.beans.factory.annotation.Autowired;

/** DatasetMetaReaderContractTest 를 Jooq(PostgreSQL) 구현으로 실행한다(WD-485). */
class JooqDatasetMetaReaderContractTest extends DatasetMetaReaderContractTest {

  @Autowired private JooqDatasetMetaReader repository;

  @Override
  protected DatasetMetaReader repository() {
    return repository;
  }
}
