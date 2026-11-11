package com.smartfirehub.document.repository;

import com.smartfirehub.document.repository.jooq.JooqDocumentFileRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** DocumentFileRepositoryContractTest 를 jOOQ(PostgreSQL) 구현으로 실행한다(WD-712). */
class JooqDocumentFileRepositoryContractTest extends DocumentFileRepositoryContractTest {

  @Autowired private JooqDocumentFileRepository repository;

  @Override
  protected DocumentFileRepository repository() {
    return repository;
  }
}
