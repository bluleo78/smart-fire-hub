package com.smartfirehub.analytics.repository;

import com.smartfirehub.analytics.repository.jooq.JooqDashboardWidgetRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** DashboardWidgetRepositoryContractTest 를 jOOQ(PostgreSQL) 구현으로 실행한다(WD-710). */
class JooqDashboardWidgetRepositoryContractTest extends DashboardWidgetRepositoryContractTest {

  @Autowired private JooqDashboardWidgetRepository repository;

  @Override
  protected DashboardWidgetRepository repository() {
    return repository;
  }
}
