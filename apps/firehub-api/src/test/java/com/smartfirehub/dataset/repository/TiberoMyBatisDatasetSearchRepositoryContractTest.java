package com.smartfirehub.dataset.repository;

import com.smartfirehub.dataset.repository.mybatis.MyBatisDatasetSearchRepository;
import com.smartfirehub.support.tibero.TiberoLive;
import org.junit.jupiter.api.TestInstance;

/**
 * DatasetSearchRepositoryContractTest 를 MyBatis 구현으로 Tibero 실행 묶음에서 돌린다(WD-707). 두 메서드는 DB 에 닿지 않고
 * 예외만 던지므로(스펙 D1 — Tibero 기준선에 벡터 테이블·pg_trgm 이 없다) 하네스 프록시 없이 구현을 그대로 쓴다 — 프록시의 테넌트 트랜잭션 검사가 먼저 터져
 * 다른 예외를 단언하는 일이 없게.
 */
@TiberoLive
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoMyBatisDatasetSearchRepositoryContractTest extends DatasetSearchRepositoryContractTest {

  private final DatasetSearchRepository repository = new MyBatisDatasetSearchRepository();

  @Override
  protected DatasetSearchRepository repository() {
    return repository;
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
