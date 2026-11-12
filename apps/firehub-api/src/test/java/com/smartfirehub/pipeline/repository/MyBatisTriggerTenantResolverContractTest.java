package com.smartfirehub.pipeline.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** TriggerTenantResolverContractTest 를 MyBatis(PostgreSQL) 구현으로 실행한다(WD-708) — 빈 이름으로 꺼낸다. */
class MyBatisTriggerTenantResolverContractTest extends TriggerTenantResolverContractTest {

  @Autowired private ApplicationContext applicationContext;

  @Override
  protected TriggerTenantResolver resolver() {
    return applicationContext.getBean("myBatisTriggerTenantResolver", TriggerTenantResolver.class);
  }

  @Override
  protected TriggerRepository triggerRepository() {
    return applicationContext.getBean("myBatisTriggerRepository", TriggerRepository.class);
  }
}
