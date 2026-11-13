package com.smartfirehub.analytics.repository;

import com.smartfirehub.analytics.repository.jooq.JooqAnalyticsWorkspaceRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** AnalyticsWorkspaceRepositoryContractTest 를 jOOQ(PostgreSQL) 구현으로 실행한다(WD-710). */
class JooqAnalyticsWorkspaceRepositoryContractTest
    extends AnalyticsWorkspaceRepositoryContractTest {

  @Autowired private JooqAnalyticsWorkspaceRepository repository;

  @Override
  protected AnalyticsWorkspaceRepository repository() {
    return repository;
  }
}
