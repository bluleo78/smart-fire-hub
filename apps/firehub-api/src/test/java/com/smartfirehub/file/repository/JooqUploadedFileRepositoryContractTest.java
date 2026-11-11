package com.smartfirehub.file.repository;

import com.smartfirehub.file.repository.jooq.JooqUploadedFileRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** UploadedFileRepositoryContractTest 를 jOOQ(PostgreSQL) 구현으로 실행한다(WD-712). */
class JooqUploadedFileRepositoryContractTest extends UploadedFileRepositoryContractTest {

  @Autowired private JooqUploadedFileRepository repository;

  @Override
  protected UploadedFileRepository repository() {
    return repository;
  }
}
