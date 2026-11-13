package com.smartfirehub.analytics.repository;

import com.smartfirehub.analytics.repository.jooq.JooqChartRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** ChartRepositoryContractTest 를 jOOQ(PostgreSQL) 구현으로 실행한다(WD-710). */
class JooqChartRepositoryContractTest extends ChartRepositoryContractTest {

  @Autowired private JooqChartRepository repository;

  @Override
  protected ChartRepository repository() {
    return repository;
  }
}
