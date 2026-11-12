package com.smartfirehub.proactive.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** ReportTemplateRepositoryContractTest 를 Jooq(PostgreSQL) 구현으로 실행한다(WD-709) — 빈 이름으로 꺼낸다. */
class JooqReportTemplateRepositoryContractTest extends ReportTemplateRepositoryContractTest {

  @Autowired private ApplicationContext applicationContext;

  @Override
  protected ReportTemplateRepository repository() {
    return applicationContext.getBean(
        "jooqReportTemplateRepository", ReportTemplateRepository.class);
  }
}
