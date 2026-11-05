package com.smartfirehub.notification.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * NotificationOutboxRepositoryContractTest 를 MyBatis(PostgreSQL) 구현으로 실행한다(WD-487 PoC).
 *
 * <p>MyBatis 구현 빈은 {@code autowireCandidate=false} 라 타입 주입에 끼지 않는다 — 그래서 빈 이름으로 꺼낸다. 컨테이너가 만든 빈을
 * 쓰므로 클래스 레벨 {@code @Transactional} 프록시(테넌트 GUC 주입)까지 함께 검증된다.
 */
class MyBatisNotificationOutboxRepositoryContractTest
    extends NotificationOutboxRepositoryContractTest {

  @Autowired private ApplicationContext applicationContext;

  @Override
  protected NotificationOutboxRepository repository() {
    return applicationContext.getBean(
        "myBatisNotificationOutboxRepository", NotificationOutboxRepository.class);
  }
}
