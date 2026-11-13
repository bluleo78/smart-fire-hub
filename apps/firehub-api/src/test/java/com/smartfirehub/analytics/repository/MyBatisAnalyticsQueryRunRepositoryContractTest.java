package com.smartfirehub.analytics.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** AnalyticsQueryRunRepositoryContractTest 를 MyBatis(PostgreSQL) 구현으로 실행한다(WD-710) — 빈 이름으로 꺼낸다. */
class MyBatisAnalyticsQueryRunRepositoryContractTest
    extends AnalyticsQueryRunRepositoryContractTest {

  @Autowired private ApplicationContext applicationContext;

  @Override
  protected AnalyticsQueryRunRepository repository() {
    return applicationContext.getBean(
        "myBatisAnalyticsQueryRunRepository", AnalyticsQueryRunRepository.class);
  }
}
