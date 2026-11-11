package com.smartfirehub.document.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * DocumentChunkRepositoryContractTest 를 MyBatis(PostgreSQL) 구현으로 실행한다(WD-712) — 빈 이름으로 꺼낸다.
 * 벡터·트라이그램 메서드는 PG 프로필에서도 만들지 않으므로(스펙 D1 — PG 운영은 jOOQ) 예외를 단언한다.
 */
class MyBatisDocumentChunkRepositoryContractTest extends DocumentChunkRepositoryContractTest {

  @Autowired private ApplicationContext applicationContext;

  @Override
  protected DocumentChunkRepository repository() {
    return applicationContext.getBean(
        "myBatisDocumentChunkRepository", DocumentChunkRepository.class);
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
