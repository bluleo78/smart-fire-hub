package com.smartfirehub.dataset.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * DatasetEmbeddingRepositoryContractTest 를 MyBatis(PostgreSQL) 구현으로 실행한다(WD-707) — 빈 이름으로 꺼낸다.
 *
 * <p>벡터는 벡터 저장소 포트가 맡는다(ADR 0002). PG 컨텍스트의 포트는 pgvector 구현이라 이 빈도 벡터를 실제로 쓰고 지운다. 그래서 jOOQ 판과 같은
 * 전체 시나리오를 돌려 MyBatis 표시 열 SQL(WD-718)이 같은 답을 내는지 본다. 꺼짐 포트 경로는 Tibero 하위 클래스가 본다.
 */
class MyBatisDatasetEmbeddingRepositoryContractTest extends DatasetEmbeddingRepositoryContractTest {

  @Autowired private ApplicationContext applicationContext;

  @Override
  protected DatasetEmbeddingRepository repository() {
    return applicationContext.getBean(
        "myBatisDatasetEmbeddingRepository", DatasetEmbeddingRepository.class);
  }
}
