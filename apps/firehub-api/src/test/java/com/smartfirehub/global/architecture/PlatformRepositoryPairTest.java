package com.smartfirehub.global.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.ai.repository.AiSessionRepository;
import com.smartfirehub.analytics.repository.AnalyticsDashboardRepository;
import com.smartfirehub.analytics.repository.AnalyticsQueryRunRepository;
import com.smartfirehub.analytics.repository.AnalyticsWorkspaceRepository;
import com.smartfirehub.analytics.repository.ChartRepository;
import com.smartfirehub.analytics.repository.DashboardWidgetRepository;
import com.smartfirehub.analytics.repository.SavedQueryRepository;
import com.smartfirehub.apiconnection.repository.ApiConnectionRepository;
import com.smartfirehub.dashboard.repository.DashboardStatsRepository;
import com.smartfirehub.dataset.repository.DatasetCategoryRepository;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.dataset.repository.DatasetEmbeddingRepository;
import com.smartfirehub.dataset.repository.DatasetFavoriteRepository;
import com.smartfirehub.dataset.repository.DatasetReferenceRepository;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.repository.DatasetSearchRepository;
import com.smartfirehub.dataset.repository.DatasetTagRepository;
import com.smartfirehub.dataset.repository.QueryHistoryRepository;
import com.smartfirehub.dataset.repository.SearchColumnRepository;
import com.smartfirehub.dataset.repository.SearchIndexStateRepository;
import com.smartfirehub.dataset.repository.TenantDataStoreRepository;
import com.smartfirehub.document.repository.DocumentChunkRepository;
import com.smartfirehub.document.repository.DocumentFileRepository;
import com.smartfirehub.embedding.repository.EmbeddingReembedStateRepository;
import com.smartfirehub.file.repository.FileDatasetConfigRepository;
import com.smartfirehub.file.repository.UploadedFileRepository;
import com.smartfirehub.global.platformdb.ConditionalOnPostgresPlatformDb;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.graphingest.repository.GraphIngestRepository;
import com.smartfirehub.mapping.repository.MappingRepository;
import com.smartfirehub.notification.repository.OAuthStateRepository;
import com.smartfirehub.notification.repository.SlackWorkspaceRepository;
import com.smartfirehub.notification.repository.SlackWorkspaceTenantResolver;
import com.smartfirehub.notification.repository.UserChannelBindingRepository;
import com.smartfirehub.notification.repository.UserChannelPreferenceRepository;
import com.smartfirehub.ontology.repository.DatasetOntologyRepository;
import com.smartfirehub.ontology.repository.GraphOntologySourceRepository;
import com.smartfirehub.ontology.repository.OntologyElementRepository;
import com.smartfirehub.ontology.repository.OntologyRepository;
import com.smartfirehub.pipeline.repository.AiInferenceCacheRepository;
import com.smartfirehub.pipeline.repository.PipelineExecutionRepository;
import com.smartfirehub.pipeline.repository.PipelineRepository;
import com.smartfirehub.pipeline.repository.PipelineStepRepository;
import com.smartfirehub.pipeline.repository.TriggerEventRepository;
import com.smartfirehub.pipeline.repository.TriggerRepository;
import com.smartfirehub.pipeline.repository.TriggerTenantResolver;
import com.smartfirehub.proactive.repository.AnomalyEventRepository;
import com.smartfirehub.proactive.repository.MetricSnapshotRepository;
import com.smartfirehub.proactive.repository.ProactiveJobExecutionRepository;
import com.smartfirehub.proactive.repository.ProactiveJobRepository;
import com.smartfirehub.proactive.repository.ReportTemplateRepository;
import com.smartfirehub.proactive.repository.SystemMetricRepository;
import com.smartfirehub.securitylevel.repository.DatasetAccessGrantRepository;
import com.smartfirehub.securitylevel.repository.DatasetAccessRepository;
import com.smartfirehub.securitylevel.repository.EmbeddingAiPolicyRepository;
import com.smartfirehub.securitylevel.repository.SecurityLevelRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Repository;

/**
 * 플랫폼 리포지토리 구현 짝 검사(WD-706).
 *
 * <p>무엇을: {@code ..repository.jooq..} 의 {@code @Repository} jOOQ 구현이 구현하는 리포지토리 인터페이스마다, {@link
 * PlatformMyBatisRepository} MyBatis 구현이 있거나 {@link #PENDING} 에 (묶음 번호와 함께) 올라 있어야 한다.
 *
 * <p>왜: Tibero 설치본은 jOOQ 구현을 빼고 MyBatis 구현만 쓴다. 인터페이스에 MyBatis 구현이 빠진 채 남으면 Tibero 기동 때 주입 실패로 터진다.
 * 도메인 묶음(WD-706~712)이 하나씩 짝을 만들 때마다 이 목록에서 지운다. 짝이 생겼는데 목록에 남은 항목은 실패시켜 목록이 낡지 않게 한다.
 *
 * <p>범위 한계: 이 검사는 {@code ..repository.jooq..} 의 {@code @Repository} 클래스와 그 {@code ..repository}
 * 인터페이스만 본다. 빈 PENDING 이 WD-491 완료를 뜻하지는 않는다 — jOOQ {@code @Component} 클래스(예:
 * dataset/repository/jooq/JooqDatasetMetaReader)와 {@code DSLContext} 를 직접 쓰는 서비스는 검사 밖이며, 이후 묶음과
 * WD-494 가 따로 추적한다.
 */
class PlatformRepositoryPairTest {

  /**
   * 아직 MyBatis 구현이 없는 인터페이스 → 맡은 묶음. 묶음은 ADR 0001 「WD-491 쪼개기」 표의 도메인 기준이다. 해당 묶음이 짝을 만들 때 자기 항목을
   * 지운다.
   */
  static final Map<Class<?>, String> PENDING = pending();

  private static Map<Class<?>, String> pending() {
    Map<Class<?>, String> m = new LinkedHashMap<>();
    m.put(AiSessionRepository.class, "묶음 ⑦: ai");
    m.put(AnalyticsDashboardRepository.class, "묶음 ⑤: analytics");
    m.put(AnalyticsQueryRunRepository.class, "묶음 ⑤: analytics");
    m.put(AnalyticsWorkspaceRepository.class, "묶음 ⑤: analytics");
    m.put(ChartRepository.class, "묶음 ⑤: analytics");
    m.put(DashboardWidgetRepository.class, "묶음 ⑤: analytics");
    m.put(SavedQueryRepository.class, "묶음 ⑤: analytics");
    m.put(ApiConnectionRepository.class, "묶음 ③: apiconnection");
    m.put(DashboardStatsRepository.class, "묶음 ⑤: dashboard");
    m.put(DatasetCategoryRepository.class, "묶음 ②: dataset");
    m.put(DatasetColumnRepository.class, "묶음 ②: dataset");
    m.put(DatasetEmbeddingRepository.class, "묶음 ②: dataset");
    m.put(DatasetFavoriteRepository.class, "묶음 ②: dataset");
    m.put(DatasetReferenceRepository.class, "묶음 ②: dataset");
    m.put(DatasetRepository.class, "묶음 ②: dataset");
    m.put(DatasetSearchRepository.class, "묶음 ②: dataset");
    m.put(DatasetTagRepository.class, "묶음 ②: dataset");
    m.put(QueryHistoryRepository.class, "묶음 ②: dataset");
    m.put(SearchColumnRepository.class, "묶음 ②: dataset");
    m.put(SearchIndexStateRepository.class, "묶음 ②: dataset");
    m.put(TenantDataStoreRepository.class, "묶음 ②: dataset");
    m.put(DocumentChunkRepository.class, "묶음 ⑦: document");
    m.put(DocumentFileRepository.class, "묶음 ⑦: document");
    m.put(EmbeddingReembedStateRepository.class, "묶음 ⑦: embedding");
    m.put(FileDatasetConfigRepository.class, "묶음 ⑦: file");
    m.put(UploadedFileRepository.class, "묶음 ⑦: file");
    m.put(GraphIngestRepository.class, "묶음 ⑥: graphingest");
    m.put(MappingRepository.class, "묶음 ③: mapping");
    m.put(SlackWorkspaceTenantResolver.class, "묶음 ④: notification");
    m.put(DatasetOntologyRepository.class, "묶음 ⑥: ontology");
    m.put(GraphOntologySourceRepository.class, "묶음 ⑥: ontology");
    m.put(OntologyElementRepository.class, "묶음 ⑥: ontology");
    m.put(OntologyRepository.class, "묶음 ⑥: ontology");
    m.put(AiInferenceCacheRepository.class, "묶음 ③: pipeline");
    m.put(PipelineExecutionRepository.class, "묶음 ③: pipeline");
    m.put(PipelineRepository.class, "묶음 ③: pipeline");
    m.put(PipelineStepRepository.class, "묶음 ③: pipeline");
    m.put(TriggerEventRepository.class, "묶음 ③: pipeline");
    m.put(TriggerRepository.class, "묶음 ③: pipeline");
    m.put(TriggerTenantResolver.class, "묶음 ③: pipeline");
    m.put(AnomalyEventRepository.class, "묶음 ④: proactive");
    m.put(MetricSnapshotRepository.class, "묶음 ④: proactive");
    m.put(ProactiveJobExecutionRepository.class, "묶음 ④: proactive");
    m.put(ProactiveJobRepository.class, "묶음 ④: proactive");
    m.put(ReportTemplateRepository.class, "묶음 ④: proactive");
    m.put(SystemMetricRepository.class, "묶음 ④: proactive");
    m.put(DatasetAccessGrantRepository.class, "묶음 ⑥: securitylevel");
    m.put(DatasetAccessRepository.class, "묶음 ⑥: securitylevel");
    m.put(EmbeddingAiPolicyRepository.class, "묶음 ⑥: securitylevel");
    m.put(SecurityLevelRepository.class, "묶음 ⑥: securitylevel");
    m.put(OAuthStateRepository.class, "묶음 ④: notification");
    m.put(SlackWorkspaceRepository.class, "묶음 ④: notification");
    m.put(UserChannelBindingRepository.class, "묶음 ④: notification");
    m.put(UserChannelPreferenceRepository.class, "묶음 ④: notification");
    return Collections.unmodifiableMap(m);
  }

  /** 애노테이션별 스캔 결과 캐시 — 클래스패스 전체 스캔이 비싸 호출마다 반복하지 않는다. */
  private static final Map<Class<?>, List<Class<?>>> SCAN_CACHE = new java.util.HashMap<>();

  /**
   * 프로덕션 클래스 중 주어진 애노테이션이 붙은 것(시험용 클래스는 {@code com.smartfirehub} 밖에 두므로 걸리지 않는다). 애노테이션당 한 번만 스캔한다.
   */
  private static synchronized List<Class<?>> annotated(
      Class<? extends java.lang.annotation.Annotation> a) throws ClassNotFoundException {
    List<Class<?>> cached = SCAN_CACHE.get(a);
    if (cached != null) {
      return cached;
    }
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(a));
    List<Class<?>> result = new ArrayList<>();
    for (BeanDefinition bd : scanner.findCandidateComponents("com.smartfirehub")) {
      result.add(Class.forName(bd.getBeanClassName()));
    }
    SCAN_CACHE.put(a, result);
    return result;
  }

  /** {@code ..repository.jooq..} 의 {@code @Repository} 구현 클래스. */
  private static List<Class<?>> jooqImpls() throws ClassNotFoundException {
    return annotated(Repository.class).stream()
        .filter(c -> c.getPackageName().contains(".repository.jooq"))
        .toList();
  }

  /** {@code com.smartfirehub..repository} 패키지(하위 제외)에 있는 인터페이스인가. */
  private static boolean isRepositoryInterface(Class<?> i) {
    return i.getPackageName().startsWith("com.smartfirehub.")
        && i.getPackageName().endsWith(".repository");
  }

  /** jOOQ 구현이 구현하는 리포지토리 인터페이스 → 그 jOOQ 구현들. */
  private static Map<Class<?>, List<Class<?>>> jooqByInterface() throws ClassNotFoundException {
    Map<Class<?>, List<Class<?>>> map = new LinkedHashMap<>();
    for (Class<?> impl : jooqImpls()) {
      for (Class<?> i : impl.getInterfaces()) {
        if (isRepositoryInterface(i)) {
          map.computeIfAbsent(i, k -> new ArrayList<>()).add(impl);
        }
      }
    }
    return map;
  }

  /** 인터페이스를 구현하는 {@code @PlatformMyBatisRepository} 클래스. */
  private static List<Class<?>> mybatisImpls(Class<?> iface) throws ClassNotFoundException {
    return annotated(PlatformMyBatisRepository.class).stream()
        .filter(iface::isAssignableFrom)
        .toList();
  }

  /** 모든 플랫폼 리포지토리 인터페이스는 MyBatis 짝이 있거나 PENDING 에 있어야 한다. */
  @Test
  void everyPlatformRepositoryHasMyBatisPairOrIsPending() throws ClassNotFoundException {
    Set<String> missing = new TreeSet<>();
    for (Class<?> iface : jooqByInterface().keySet()) {
      if (mybatisImpls(iface).isEmpty() && !PENDING.containsKey(iface)) {
        missing.add(iface.getName());
      }
    }
    assertThat(missing)
        .as(
            "MyBatis 구현(@PlatformMyBatisRepository)도 PENDING 항목도 없는 인터페이스 — 구현을 만들거나 PENDING 에 묶음과 함께 올린다")
        .isEmpty();
  }

  /** 짝이 생긴 인터페이스는 PENDING 에서 지워야 한다. */
  @Test
  void pendingEntriesHaveNoPair() throws ClassNotFoundException {
    Set<String> stale = new TreeSet<>();
    for (Class<?> iface : PENDING.keySet()) {
      if (!mybatisImpls(iface).isEmpty()) {
        stale.add(iface.getName());
      }
    }
    assertThat(stale).as("MyBatis 짝이 이미 있다 — PENDING 목록에서 지우세요").isEmpty();
  }

  /** PENDING 항목은 실제 jOOQ 구현이 있는 인터페이스여야 한다 — 이름이 바뀌어 목록이 헛돌지 않게. */
  @Test
  void pendingEntriesAreRealPlatformRepositories() throws ClassNotFoundException {
    Set<Class<?>> real = jooqByInterface().keySet();
    Set<String> bogus = new TreeSet<>();
    for (Class<?> iface : PENDING.keySet()) {
      if (!real.contains(iface)) {
        bogus.add(iface.getName());
      }
    }
    assertThat(bogus).as("jOOQ @Repository 구현이 없는 PENDING 항목").isEmpty();
  }

  /** 짝이 있는 인터페이스의 jOOQ 구현은 PG 전용이어야 한다 — Tibero 설치본에서 두 구현이 함께 주입 후보가 되면 안 된다. */
  @Test
  void pairedJooqImplsArePostgresOnly() throws ClassNotFoundException {
    Set<String> offenders = new TreeSet<>();
    for (Map.Entry<Class<?>, List<Class<?>>> e : jooqByInterface().entrySet()) {
      if (mybatisImpls(e.getKey()).isEmpty()) {
        continue;
      }
      for (Class<?> jooq : e.getValue()) {
        if (!jooq.isAnnotationPresent(ConditionalOnPostgresPlatformDb.class)) {
          offenders.add(jooq.getName());
        }
      }
    }
    assertThat(offenders)
        .as("MyBatis 짝이 있는 jOOQ 구현에 @ConditionalOnPostgresPlatformDb 가 없다")
        .isEmpty();
  }

  /** MyBatis 구현은 {@code ..repository.mybatis..} 패키지에 둔다. */
  @Test
  void mybatisImplsLiveInMybatisPackage() throws ClassNotFoundException {
    Set<String> offenders = new TreeSet<>();
    for (Class<?> c : annotated(PlatformMyBatisRepository.class)) {
      if (!c.getPackageName().contains(".repository.mybatis")) {
        offenders.add(c.getName());
      }
    }
    assertThat(offenders)
        .as("@PlatformMyBatisRepository 클래스는 ..repository.mybatis.. 에 둔다")
        .isEmpty();
  }
}
