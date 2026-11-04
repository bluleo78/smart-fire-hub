package com.smartfirehub.graphingest;

import com.smartfirehub.graphingest.repository.GraphIngestRepository;
import com.smartfirehub.graphingest.repository.jooq.JooqGraphIngestRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** GraphIngestRepositoryContractTest 를 Jooq(PostgreSQL) 구현으로 실행한다(WD-485). */
class JooqGraphIngestRepositoryContractTest extends GraphIngestRepositoryContractTest {

  @Autowired private JooqGraphIngestRepository repository;

  @Override
  protected GraphIngestRepository repository() {
    return repository;
  }
}
