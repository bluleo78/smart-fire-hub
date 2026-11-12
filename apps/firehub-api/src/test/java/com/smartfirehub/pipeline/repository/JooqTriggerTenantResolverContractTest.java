package com.smartfirehub.pipeline.repository;

import com.smartfirehub.pipeline.repository.jooq.JooqTriggerRepository;
import com.smartfirehub.pipeline.repository.jooq.JooqTriggerTenantResolver;
import org.springframework.beans.factory.annotation.Autowired;

/** TriggerTenantResolverContractTest 를 jOOQ(PostgreSQL) 구현으로 실행한다(WD-708). */
class JooqTriggerTenantResolverContractTest extends TriggerTenantResolverContractTest {

  @Autowired private JooqTriggerTenantResolver resolver;
  @Autowired private JooqTriggerRepository triggerRepository;

  @Override
  protected TriggerTenantResolver resolver() {
    return resolver;
  }

  @Override
  protected TriggerRepository triggerRepository() {
    return triggerRepository;
  }
}
