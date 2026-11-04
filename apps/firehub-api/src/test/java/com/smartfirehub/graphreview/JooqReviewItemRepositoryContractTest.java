package com.smartfirehub.graphreview;

import com.smartfirehub.graphreview.repository.ReviewItemRepository;
import com.smartfirehub.graphreview.repository.jooq.JooqReviewItemRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** ReviewItemRepositoryContractTest 를 Jooq(PostgreSQL) 구현으로 실행한다(WD-485). */
class JooqReviewItemRepositoryContractTest extends ReviewItemRepositoryContractTest {

  @Autowired private JooqReviewItemRepository repository;

  @Override
  protected ReviewItemRepository repository() {
    return repository;
  }
}
