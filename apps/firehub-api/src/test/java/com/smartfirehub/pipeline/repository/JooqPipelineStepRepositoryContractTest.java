package com.smartfirehub.pipeline.repository;

import com.smartfirehub.pipeline.repository.jooq.JooqPipelineStepRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** PipelineStepRepositoryContractTest 를 jOOQ(PostgreSQL) 구현으로 실행한다(WD-708). */
class JooqPipelineStepRepositoryContractTest extends PipelineStepRepositoryContractTest {

  @Autowired private JooqPipelineStepRepository repository;

  @Override
  protected PipelineStepRepository repository() {
    return repository;
  }
}
