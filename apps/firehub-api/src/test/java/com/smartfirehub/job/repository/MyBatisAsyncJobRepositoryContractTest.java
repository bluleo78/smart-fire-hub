package com.smartfirehub.job.repository;

import com.smartfirehub.job.repository.mybatis.AsyncJobMapper;
import com.smartfirehub.job.repository.mybatis.AsyncJobRow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** AsyncJobRepositoryContractTest 를 MyBatis(PostgreSQL) 구현으로 실행한다(WD-706) — 빈 이름으로 꺼낸다. */
class MyBatisAsyncJobRepositoryContractTest extends AsyncJobRepositoryContractTest {

  @Autowired private ApplicationContext applicationContext;

  @Override
  protected AsyncJobRepository repository() {
    return applicationContext.getBean("myBatisAsyncJobRepository", AsyncJobRepository.class);
  }

  @Override
  protected boolean hasRawMapper() {
    return true;
  }

  /** 매퍼 빈을 트랜잭션 없이 부른다(시험 기반에 앰비언트 트랜잭션이 없다). */
  @Override
  protected AsyncJobRow rawSelectByIdWithoutTransaction(String id) {
    return applicationContext.getBean(AsyncJobMapper.class).selectById(id);
  }
}
