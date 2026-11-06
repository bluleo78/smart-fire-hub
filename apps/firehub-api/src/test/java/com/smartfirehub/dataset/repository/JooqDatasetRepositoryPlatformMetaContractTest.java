package com.smartfirehub.dataset.repository;

import com.smartfirehub.dataset.repository.jooq.JooqDatasetRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** DatasetRepositoryPlatformMetaContractTest 를 Jooq(PostgreSQL) 구현으로 실행한다(WD-494 최종 리뷰 지적 6). */
class JooqDatasetRepositoryPlatformMetaContractTest
    extends DatasetRepositoryPlatformMetaContractTest {

  @Autowired private JooqDatasetRepository repository;

  @Override
  protected DatasetRepository repository() {
    return repository;
  }
}
