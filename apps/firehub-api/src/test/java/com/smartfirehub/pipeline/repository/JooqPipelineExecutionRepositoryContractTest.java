package com.smartfirehub.pipeline.repository;

import com.smartfirehub.pipeline.repository.jooq.JooqPipelineExecutionRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** PipelineExecutionRepositoryContractTest 를 Jooq(PostgreSQL) 구현으로 실행한다(WD-485, WD-708). */
class JooqPipelineExecutionRepositoryContractTest extends PipelineExecutionRepositoryContractTest {

  @Autowired private JooqPipelineExecutionRepository repository;

  @Override
  protected PipelineExecutionRepository repository() {
    return repository;
  }
}
