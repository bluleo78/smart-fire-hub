package com.smartfirehub.notification.repository.jooq;

import com.smartfirehub.notification.repository.NotificationOutboxRepository;
import com.smartfirehub.notification.repository.NotificationOutboxRepositoryContractTest;
import org.springframework.beans.factory.annotation.Autowired;

/** NotificationOutboxRepositoryContractTest 를 Jooq(PostgreSQL) 구현으로 실행한다(WD-485). */
class JooqNotificationOutboxRepositoryContractTest
    extends NotificationOutboxRepositoryContractTest {

  @Autowired private JooqNotificationOutboxRepository repository;

  @Override
  protected NotificationOutboxRepository repository() {
    return repository;
  }
}
