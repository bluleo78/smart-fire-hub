package com.smartfirehub.dashboard.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** DashboardStatsRepositoryContractTest 를 MyBatis(PostgreSQL) 구현으로 실행한다(WD-710) — 빈 이름으로 꺼낸다. */
class MyBatisDashboardStatsRepositoryContractTest extends DashboardStatsRepositoryContractTest {

  @Autowired private ApplicationContext applicationContext;

  @Override
  protected DashboardStatsRepository repository() {
    return applicationContext.getBean(
        "myBatisDashboardStatsRepository", DashboardStatsRepository.class);
  }
}
