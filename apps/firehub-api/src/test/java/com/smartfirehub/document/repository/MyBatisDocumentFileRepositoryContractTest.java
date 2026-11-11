package com.smartfirehub.document.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** DocumentFileRepositoryContractTest 를 MyBatis(PostgreSQL) 구현으로 실행한다(WD-712) — 빈 이름으로 꺼낸다. */
class MyBatisDocumentFileRepositoryContractTest extends DocumentFileRepositoryContractTest {

  @Autowired private ApplicationContext applicationContext;

  @Override
  protected DocumentFileRepository repository() {
    return applicationContext.getBean(
        "myBatisDocumentFileRepository", DocumentFileRepository.class);
  }
}
