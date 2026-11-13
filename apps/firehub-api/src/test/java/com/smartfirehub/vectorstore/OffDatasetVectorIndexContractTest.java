package com.smartfirehub.vectorstore;

import com.smartfirehub.vectorstore.off.OffDatasetVectorIndex;

/**
 * {@link DatasetVectorIndexContractTest} 를 꺼짐 구현(Tibero+platform)으로 실행한다. 두 검색 모두 예외(종류·기능 이름)를
 * 단언한다.
 *
 * <p>꺼짐 구현은 DB 에 닿지 않는다. 그래서 예전 MyBatis 계약 테스트처럼 Tibero 실행 묶음에서 따로 돌릴 필요가 없고, PG 컨텍스트에서 구현을 직접 만들어
 * 쓴다.
 */
class OffDatasetVectorIndexContractTest extends DatasetVectorIndexContractTest {

  private final DatasetVectorIndex index = new OffDatasetVectorIndex();

  @Override
  protected DatasetVectorIndex repository() {
    return index;
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
