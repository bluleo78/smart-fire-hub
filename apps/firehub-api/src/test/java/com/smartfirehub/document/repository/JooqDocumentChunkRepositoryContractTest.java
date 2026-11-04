package com.smartfirehub.document.repository;

import com.smartfirehub.document.repository.jooq.JooqDocumentChunkRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** DocumentChunkRepositoryContractTest 를 Jooq(PostgreSQL) 구현으로 실행한다(WD-485). */
class JooqDocumentChunkRepositoryContractTest extends DocumentChunkRepositoryContractTest {

  @Autowired private JooqDocumentChunkRepository repository;

  @Override
  protected DocumentChunkRepository repository() {
    return repository;
  }
}
