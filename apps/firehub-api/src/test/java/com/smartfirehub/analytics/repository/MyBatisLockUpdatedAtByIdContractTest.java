package com.smartfirehub.analytics.repository;

import com.smartfirehub.proactive.repository.ProactiveJobRepository;
import com.smartfirehub.proactive.repository.ReportTemplateRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** LockUpdatedAtByIdContractTest 를 MyBatis(PostgreSQL) 구현 다섯으로 실행한다(WD-710) — 빈 이름으로 꺼낸다. */
class MyBatisLockUpdatedAtByIdContractTest extends LockUpdatedAtByIdContractTest {

  @Autowired private ApplicationContext applicationContext;

  @Override
  protected SavedQueryRepository savedQueryRepository() {
    return applicationContext.getBean("myBatisSavedQueryRepository", SavedQueryRepository.class);
  }

  @Override
  protected ChartRepository chartRepository() {
    return applicationContext.getBean("myBatisChartRepository", ChartRepository.class);
  }

  @Override
  protected AnalyticsDashboardRepository dashboardRepository() {
    return applicationContext.getBean(
        "myBatisAnalyticsDashboardRepository", AnalyticsDashboardRepository.class);
  }

  @Override
  protected ReportTemplateRepository reportTemplateRepository() {
    return applicationContext.getBean(
        "myBatisReportTemplateRepository", ReportTemplateRepository.class);
  }

  @Override
  protected ProactiveJobRepository proactiveJobRepository() {
    return applicationContext.getBean(
        "myBatisProactiveJobRepository", ProactiveJobRepository.class);
  }
}
