package com.smartfirehub.notification.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * UserChannelBindingRepositoryContractTest 를 MyBatis(PostgreSQL) 구현으로 실행한다(WD-709) — 빈 이름으로 꺼낸다.
 */
class MyBatisUserChannelBindingRepositoryContractTest
    extends UserChannelBindingRepositoryContractTest {

  @Autowired private ApplicationContext applicationContext;

  @Override
  protected UserChannelBindingRepository repository() {
    return applicationContext.getBean(
        "myBatisUserChannelBindingRepository", UserChannelBindingRepository.class);
  }
}
