package com.smartfirehub.dashboard.repository;

import com.smartfirehub.dashboard.repository.jooq.JooqDashboardStatsRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** DashboardStatsRepositoryContractTest 를 jOOQ(PostgreSQL) 구현으로 실행한다(WD-710). */
class JooqDashboardStatsRepositoryContractTest extends DashboardStatsRepositoryContractTest {

  @Autowired private JooqDashboardStatsRepository repository;

  @Override
  protected DashboardStatsRepository repository() {
    return repository;
  }
}
