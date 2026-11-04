package com.smartfirehub.analytics.repository;

import com.smartfirehub.analytics.repository.jooq.JooqAnalyticsDashboardRepository;
import com.smartfirehub.analytics.repository.jooq.JooqChartRepository;
import com.smartfirehub.analytics.repository.jooq.JooqSavedQueryRepository;
import com.smartfirehub.proactive.repository.ProactiveJobRepository;
import com.smartfirehub.proactive.repository.ReportTemplateRepository;
import com.smartfirehub.proactive.repository.jooq.JooqProactiveJobRepository;
import com.smartfirehub.proactive.repository.jooq.JooqReportTemplateRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** LockUpdatedAtByIdContractTest 를 Jooq(PostgreSQL) 구현으로 실행한다(WD-485). */
class JooqLockUpdatedAtByIdContractTest extends LockUpdatedAtByIdContractTest {

  @Autowired private JooqSavedQueryRepository savedQueryRepository;
  @Autowired private JooqChartRepository chartRepository;
  @Autowired private JooqAnalyticsDashboardRepository dashboardRepository;
  @Autowired private JooqReportTemplateRepository reportTemplateRepository;
  @Autowired private JooqProactiveJobRepository proactiveJobRepository;

  @Override
  protected SavedQueryRepository savedQueryRepository() {
    return savedQueryRepository;
  }

  @Override
  protected ChartRepository chartRepository() {
    return chartRepository;
  }

  @Override
  protected AnalyticsDashboardRepository dashboardRepository() {
    return dashboardRepository;
  }

  @Override
  protected ReportTemplateRepository reportTemplateRepository() {
    return reportTemplateRepository;
  }

  @Override
  protected ProactiveJobRepository proactiveJobRepository() {
    return proactiveJobRepository;
  }
}
