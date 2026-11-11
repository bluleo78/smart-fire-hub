package com.smartfirehub.file.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** UploadedFileRepositoryContractTest 를 MyBatis(PostgreSQL) 구현으로 실행한다(WD-712) — 빈 이름으로 꺼낸다. */
class MyBatisUploadedFileRepositoryContractTest extends UploadedFileRepositoryContractTest {

  @Autowired private ApplicationContext applicationContext;

  @Override
  protected UploadedFileRepository repository() {
    return applicationContext.getBean(
        "myBatisUploadedFileRepository", UploadedFileRepository.class);
  }
}
