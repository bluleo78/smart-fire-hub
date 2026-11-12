package com.smartfirehub.graphingest;

import com.smartfirehub.graphingest.repository.GraphIngestRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** GraphIngestRepositoryContractTest 를 MyBatis(PostgreSQL) 구현으로 실행한다(WD-711) — 빈 이름으로 꺼낸다. */
class MyBatisGraphIngestRepositoryContractTest extends GraphIngestRepositoryContractTest {

  @Autowired private ApplicationContext applicationContext;

  @Override
  protected GraphIngestRepository repository() {
    return applicationContext.getBean("myBatisGraphIngestRepository", GraphIngestRepository.class);
  }
}
