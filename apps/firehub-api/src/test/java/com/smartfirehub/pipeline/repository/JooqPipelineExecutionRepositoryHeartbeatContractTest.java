package com.smartfirehub.pipeline.repository;

import com.smartfirehub.pipeline.repository.jooq.JooqPipelineExecutionRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** PipelineExecutionRepositoryHeartbeatContractTest 를 Jooq(PostgreSQL) 구현으로 실행한다(WD-485). */
class JooqPipelineExecutionRepositoryHeartbeatContractTest
    extends PipelineExecutionRepositoryHeartbeatContractTest {

  @Autowired private JooqPipelineExecutionRepository repository;

  @Override
  protected PipelineExecutionRepository repository() {
    return repository;
  }
}
