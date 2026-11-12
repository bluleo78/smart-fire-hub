package com.smartfirehub.notification.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * UserChannelPreferenceRepositoryContractTest 를 MyBatis(PostgreSQL) 구현으로 실행한다(WD-709) — 빈 이름으로 꺼낸다.
 */
class MyBatisUserChannelPreferenceRepositoryContractTest
    extends UserChannelPreferenceRepositoryContractTest {

  @Autowired private ApplicationContext applicationContext;

  @Override
  protected UserChannelPreferenceRepository repository() {
    return applicationContext.getBean(
        "myBatisUserChannelPreferenceRepository", UserChannelPreferenceRepository.class);
  }
}
