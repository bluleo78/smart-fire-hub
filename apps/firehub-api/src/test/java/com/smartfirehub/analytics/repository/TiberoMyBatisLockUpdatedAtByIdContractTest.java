package com.smartfirehub.analytics.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.analytics.repository.mybatis.AnalyticsDashboardMapper;
import com.smartfirehub.analytics.repository.mybatis.ChartMapper;
import com.smartfirehub.analytics.repository.mybatis.MyBatisAnalyticsDashboardRepository;
import com.smartfirehub.analytics.repository.mybatis.MyBatisChartRepository;
import com.smartfirehub.analytics.repository.mybatis.MyBatisSavedQueryRepository;
import com.smartfirehub.analytics.repository.mybatis.SavedQueryMapper;
import com.smartfirehub.proactive.repository.ProactiveJobRepository;
import com.smartfirehub.proactive.repository.ReportTemplateRepository;
import com.smartfirehub.proactive.repository.mybatis.MyBatisProactiveJobRepository;
import com.smartfirehub.proactive.repository.mybatis.MyBatisReportTemplateRepository;
import com.smartfirehub.proactive.repository.mybatis.ProactiveJobMapper;
import com.smartfirehub.proactive.repository.mybatis.ReportTemplateMapper;
import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoPlatformSchema;
import com.smartfirehub.user.repository.AccessFixtures;
import com.smartfirehub.user.repository.TiberoAccessFixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

/**
 * LockUpdatedAtByIdContractTest 를 MyBatis 구현 다섯 + Tibero 실서버로 실행한다(WD-710). 리포지토리는 런타임 계정 +
 * DBMS_RLS 위(하네스 프록시가 호출마다 테넌트 트랜잭션을 연다), 픽스처는 정책을 우회하는 소유자 계정으로 만들고 지운다.
 */
@TiberoLive
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoMyBatisLockUpdatedAtByIdContractTest extends LockUpdatedAtByIdContractTest {

  private TiberoPlatformSchema poc;
  private SavedQueryRepository savedQueryRepository;
  private ChartRepository chartRepository;
  private AnalyticsDashboardRepository dashboardRepository;
  private ReportTemplateRepository reportTemplateRepository;
  private ProactiveJobRepository proactiveJobRepository;

  @BeforeAll
  void startTibero() {
    poc = TiberoPlatformSchema.shared();
    ObjectMapper om = new ObjectMapper();
    savedQueryRepository =
        poc.transactional(
            SavedQueryRepository.class,
            new MyBatisSavedQueryRepository(poc.mapper(SavedQueryMapper.class)));
    chartRepository =
        poc.transactional(
            ChartRepository.class, new MyBatisChartRepository(poc.mapper(ChartMapper.class), om));
    dashboardRepository =
        poc.transactional(
            AnalyticsDashboardRepository.class,
            new MyBatisAnalyticsDashboardRepository(poc.mapper(AnalyticsDashboardMapper.class)));
    reportTemplateRepository =
        poc.transactional(
            ReportTemplateRepository.class,
            new MyBatisReportTemplateRepository(poc.mapper(ReportTemplateMapper.class), om));
    proactiveJobRepository =
        poc.transactional(
            ProactiveJobRepository.class,
            new MyBatisProactiveJobRepository(poc.mapper(ProactiveJobMapper.class), om));
  }

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

  @Override
  protected AccessFixtures newFixtures() {
    return new TiberoAccessFixtures(poc);
  }
}
