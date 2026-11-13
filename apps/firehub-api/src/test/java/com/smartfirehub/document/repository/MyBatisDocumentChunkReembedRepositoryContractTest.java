package com.smartfirehub.document.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * DocumentChunkReembedRepositoryContractTest 를 MyBatis(PostgreSQL) 구현으로 실행한다(WD-712) — 빈 이름으로 꺼낸다.
 *
 * <p>PG 컨텍스트의 청크 포트는 pgvector 구현이라 이 빈도 벡터를 실제로 옮기고 지운다. 그래서 jOOQ 판과 같은 전체 시나리오로 MyBatis 표시 열
 * SQL(WD-718)을 검증한다. 꺼짐 포트 경로는 Tibero 하위 클래스가 본다.
 */
class MyBatisDocumentChunkReembedRepositoryContractTest
    extends DocumentChunkReembedRepositoryContractTest {

  @Autowired private ApplicationContext applicationContext;

  @Override
  protected DocumentChunkRepository repository() {
    return applicationContext.getBean(
        "myBatisDocumentChunkRepository", DocumentChunkRepository.class);
  }
}
