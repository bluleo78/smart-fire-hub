package com.smartfirehub.pipeline.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** PipelineExecutionRepositoryContractTest 를 MyBatis(PostgreSQL) 구현으로 실행한다(WD-708) — 빈 이름으로 꺼낸다. */
class MyBatisPipelineExecutionRepositoryContractTest
    extends PipelineExecutionRepositoryContractTest {

  @Autowired private ApplicationContext applicationContext;

  @Override
  protected PipelineExecutionRepository repository() {
    return applicationContext.getBean(
        "myBatisPipelineExecutionRepository", PipelineExecutionRepository.class);
  }
}
