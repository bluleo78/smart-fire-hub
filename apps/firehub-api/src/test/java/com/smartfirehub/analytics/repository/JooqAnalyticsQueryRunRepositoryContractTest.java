package com.smartfirehub.analytics.repository;

import com.smartfirehub.analytics.repository.jooq.JooqAnalyticsQueryRunRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** AnalyticsQueryRunRepositoryContractTest 를 jOOQ(PostgreSQL) 구현으로 실행한다(WD-710). */
class JooqAnalyticsQueryRunRepositoryContractTest extends AnalyticsQueryRunRepositoryContractTest {

  @Autowired private JooqAnalyticsQueryRunRepository repository;

  @Override
  protected AnalyticsQueryRunRepository repository() {
    return repository;
  }
}
