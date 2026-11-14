package com.smartfirehub.document.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * DocumentChunkRepositoryContractTest 를 MyBatis(PostgreSQL) 구현으로 실행한다(WD-712) — 빈 이름으로 꺼낸다.
 *
 * <p>PG 컨텍스트의 청크 포트는 pgvector 구현이라 이 빈도 벡터를 실제로 쓴다. 예전에는 MyBatis 판 {@code insertBatch} 가 예외를 바로
 * 던졌지만, 적재가 "청크 저장 → 벡터 쓰기(포트)" 로 나뉘면서(WD-720) 그 메서드가 없어졌다. 그래서 jOOQ 판과 같은 전체 시나리오를 돈다. 꺼짐 포트 경로는
 * Tibero 하위 클래스가 본다.
 */
class MyBatisDocumentChunkRepositoryContractTest extends DocumentChunkRepositoryContractTest {

  @Autowired private ApplicationContext applicationContext;

  @Override
  protected DocumentChunkRepository repository() {
    return applicationContext.getBean(
        "myBatisDocumentChunkRepository", DocumentChunkRepository.class);
  }
}
