package com.smartfirehub.securitylevel.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** DatasetAccessRepositoryContractTest 를 MyBatis(PostgreSQL) 구현으로 실행한다(WD-711) — 빈 이름으로 꺼낸다. */
class MyBatisDatasetAccessRepositoryContractTest extends DatasetAccessRepositoryContractTest {

  @Autowired private ApplicationContext applicationContext;

  @Override
  protected DatasetAccessRepository repository() {
    return applicationContext.getBean(
        "myBatisDatasetAccessRepository", DatasetAccessRepository.class);
  }

  /** 문자열 술어 렌더는 MyBatis 구현에 없다 — VECTOR_SEARCH 꺼진 기능(스펙 D3). */
  @Override
  protected boolean supportsRenderedSql() {
    return false;
  }
}
