package com.smartfirehub.pipeline.repository;

import com.smartfirehub.pipeline.repository.jooq.JooqPipelineRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** PipelineRepositoryContractTest 를 jOOQ(PostgreSQL) 구현으로 실행한다(WD-708). */
class JooqPipelineRepositoryContractTest extends PipelineRepositoryContractTest {

  @Autowired private JooqPipelineRepository repository;

  @Override
  protected PipelineRepository repository() {
    return repository;
  }
}
