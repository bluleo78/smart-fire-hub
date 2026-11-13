package com.smartfirehub.analytics.repository;

import com.smartfirehub.analytics.repository.jooq.JooqAnalyticsDashboardRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** AnalyticsDashboardRepositoryContractTest 를 jOOQ(PostgreSQL) 구현으로 실행한다(WD-710). */
class JooqAnalyticsDashboardRepositoryContractTest
    extends AnalyticsDashboardRepositoryContractTest {

  @Autowired private JooqAnalyticsDashboardRepository repository;

  @Override
  protected AnalyticsDashboardRepository repository() {
    return repository;
  }
}
