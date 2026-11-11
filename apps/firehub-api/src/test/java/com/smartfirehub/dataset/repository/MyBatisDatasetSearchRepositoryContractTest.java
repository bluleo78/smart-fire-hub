package com.smartfirehub.dataset.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * DatasetSearchRepositoryContractTest 를 MyBatis(PostgreSQL) 구현으로 실행한다(WD-707) — 빈 이름으로 꺼낸다. 두 검색 모두
 * PG 프로필에서도 만들지 않으므로(스펙 D1 — PG 운영은 jOOQ) 예외를 단언한다.
 */
class MyBatisDatasetSearchRepositoryContractTest extends DatasetSearchRepositoryContractTest {

  @Autowired private ApplicationContext applicationContext;

  @Override
  protected DatasetSearchRepository repository() {
    return applicationContext.getBean(
        "myBatisDatasetSearchRepository", DatasetSearchRepository.class);
  }

  @Override
  protected boolean supportsVectorSearch() {
    return false;
  }

  @Override
  protected boolean supportsTrigramSearch() {
    return false;
  }
}
