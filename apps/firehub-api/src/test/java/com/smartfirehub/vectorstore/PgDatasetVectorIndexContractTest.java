package com.smartfirehub.vectorstore;

import com.smartfirehub.vectorstore.repository.jooq.PgDatasetVectorIndex;
import org.springframework.beans.factory.annotation.Autowired;

/** {@link DatasetVectorIndexContractTest} 를 pgvector 구현으로 실행한다(ADR 0002 결정 2). */
class PgDatasetVectorIndexContractTest extends DatasetVectorIndexContractTest {

  @Autowired private PgDatasetVectorIndex index;

  @Override
  protected DatasetVectorIndex repository() {
    return index;
  }
}
