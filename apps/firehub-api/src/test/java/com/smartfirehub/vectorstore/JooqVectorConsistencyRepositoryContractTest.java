package com.smartfirehub.vectorstore;

import com.smartfirehub.vectorstore.repository.jooq.JooqVectorMarkerRepository;
import com.smartfirehub.vectorstore.repository.jooq.JooqVectorOutboxRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** VectorConsistencyRepositoryContractTest 를 jOOQ(PostgreSQL) 구현으로 실행한다(WD-721). */
class JooqVectorConsistencyRepositoryContractTest extends VectorConsistencyRepositoryContractTest {

  @Autowired private JooqVectorOutboxRepository outbox;
  @Autowired private JooqVectorMarkerRepository markers;

  @Override
  protected VectorOutboxRepository outbox() {
    return outbox;
  }

  @Override
  protected VectorMarkerRepository markers() {
    return markers;
  }
}
