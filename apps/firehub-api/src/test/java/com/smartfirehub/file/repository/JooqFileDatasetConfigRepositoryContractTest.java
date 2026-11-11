package com.smartfirehub.file.repository;

import com.smartfirehub.file.repository.jooq.JooqFileDatasetConfigRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** FileDatasetConfigRepositoryContractTest 를 jOOQ(PostgreSQL) 구현으로 실행한다(WD-712). */
class JooqFileDatasetConfigRepositoryContractTest extends FileDatasetConfigRepositoryContractTest {

  @Autowired private JooqFileDatasetConfigRepository repository;

  @Override
  protected FileDatasetConfigRepository repository() {
    return repository;
  }
}
