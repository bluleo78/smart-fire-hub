package com.smartfirehub.job.repository;

import com.smartfirehub.job.repository.jooq.JooqAsyncJobRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** AsyncJobRepositoryContractTest 를 jOOQ(PostgreSQL) 구현으로 실행한다(WD-706). */
class JooqAsyncJobRepositoryContractTest extends AsyncJobRepositoryContractTest {

  @Autowired private JooqAsyncJobRepository repository;

  @Override
  protected AsyncJobRepository repository() {
    return repository;
  }
}
