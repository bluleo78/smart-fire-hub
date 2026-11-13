package com.smartfirehub.analytics.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.analytics.dto.CreateChartRequest;
import com.smartfirehub.analytics.dto.CreateDashboardRequest;
import com.smartfirehub.analytics.dto.CreateSavedQueryRequest;
import com.smartfirehub.global.dto.LockedRowVersion;
import com.smartfirehub.proactive.repository.ProactiveJobRepository;
import com.smartfirehub.proactive.repository.ReportTemplateRepository;
import com.smartfirehub.user.repository.TenantPairContractBase;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code lockUpdatedAtById} 다섯 곳(차트·대시보드·저장 쿼리·리포트 양식·Proactive Job)의 실 DB 검증(WD-485).
 *
 * <p>반환 타입을 jOOQ {@code Record1} 에서 {@link LockedRowVersion} 으로 바꿨다. 지켜야 할 것: 행이 없으면 빈 값, 행이 있으면
 * 저장된 수정 시각, 그리고 수정 시각이 비어 있는(nullable, V42) 옛 행은 <b>빈 값이 아니라 null 을 담은 값</b>이어야 한다 — 섞이면 그런 행의 편집이
 * 404 로 거부된다.
 *
 * <p>인터페이스 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-485). WD-710 에서 픽스처 seam({@code AccessFixtures}) 위로 옮겨
 * MyBatis(PG)·Tibero(두 계정 RLS) 구현도 같은 계약으로 돈다. 클래스 트랜잭션을 두지 않는다 — 리포지토리 호출마다 테넌트 트랜잭션을 열고 닫아야 소유자
 * 계정 픽스처(수정 시각 바꾸기·정리)가 잠금에 막히지 않는다. 비교 기준은 픽스처로 박은 고정 시각(마이크로초 정밀도)이다.
 */
public abstract class LockUpdatedAtByIdContractTest extends TenantPairContractBase {

  /** 계약 대상 리포지토리 — 구현별 하위 클래스가 제공한다. */
  protected abstract SavedQueryRepository savedQueryRepository();

  /** 계약 대상 리포지토리 — 구현별 하위 클래스가 제공한다. */
  protected abstract ChartRepository chartRepository();

  /** 계약 대상 리포지토리 — 구현별 하위 클래스가 제공한다. */
  protected abstract AnalyticsDashboardRepository dashboardRepository();

  /** 계약 대상 리포지토리 — 구현별 하위 클래스가 제공한다. */
  protected abstract ReportTemplateRepository reportTemplateRepository();

  /** 계약 대상 리포지토리 — 구현별 하위 클래스가 제공한다. */
  protected abstract ProactiveJobRepository proactiveJobRepository();

  /** 저장해 두고 돌려받을 고정 수정 시각(마이크로초까지 — 두 DB 모두 보존하는 정밀도). */
  private static final LocalDateTime STORED = LocalDateTime.of(2026, 3, 4, 5, 6, 7, 123456000);

  private long userId;

  @BeforeEach
  void createUser() {
    userId = fx.createUser(prefix + "-lock", null);
  }

  @Test
  @DisplayName("분석 자산 — 저장된 수정 시각을 돌려주고, 없는 id·다른 테넌트는 빈 값")
  void analytics_presentRowReturnsStoredUpdatedAt_missingRowIsEmpty() {
    Long queryId =
        inA(
            () ->
                savedQueryRepository()
                    .insert(
                        new CreateSavedQueryRequest("lock q", null, "SELECT 1", null, null, false),
                        userId));
    Long chartId =
        inA(
            () ->
                chartRepository()
                    .insert(
                        new CreateChartRequest("lock c", null, queryId, "BAR", Map.of(), false),
                        userId));
    Long dashboardId =
        inA(
            () ->
                dashboardRepository()
                    .insert(new CreateDashboardRequest("lock d", null, false, null), userId));
    fx.setSavedQueryUpdatedAt(queryId, STORED);
    fx.setChartUpdatedAt(chartId, STORED);
    fx.setDashboardUpdatedAt(dashboardId, STORED);

    assertThat(inA(() -> savedQueryRepository().lockUpdatedAtById(queryId)))
        .contains(new LockedRowVersion(STORED));
    assertThat(inA(() -> chartRepository().lockUpdatedAtById(chartId)))
        .contains(new LockedRowVersion(STORED));
    assertThat(inA(() -> dashboardRepository().lockUpdatedAtById(dashboardId)))
        .contains(new LockedRowVersion(STORED));

    assertThat(inA(() -> savedQueryRepository().lockUpdatedAtById(-1L))).isEmpty();
    assertThat(inA(() -> chartRepository().lockUpdatedAtById(-1L))).isEmpty();
    assertThat(inA(() -> dashboardRepository().lockUpdatedAtById(-1L))).isEmpty();

    assertThat(inB(() -> savedQueryRepository().lockUpdatedAtById(queryId))).isEmpty();
    assertThat(inB(() -> chartRepository().lockUpdatedAtById(chartId))).isEmpty();
    assertThat(inB(() -> dashboardRepository().lockUpdatedAtById(dashboardId))).isEmpty();
  }

  @Test
  @DisplayName("리포트 양식 — 수정 시각이 비어 있어도 빈 값이 아니라 null 을 담은 값, 없는 id 는 빈 값")
  void reportTemplate_nullUpdatedAtIsPresentWithNull_notEmpty() {
    Long templateId =
        inA(
            () ->
                reportTemplateRepository()
                    .create(
                        "lock t",
                        null,
                        List.of(Map.of("key", "s1", "type", "text")),
                        null,
                        userId));
    fx.setReportTemplateUpdatedAt(templateId, STORED);

    assertThat(inA(() -> reportTemplateRepository().lockUpdatedAtById(templateId)))
        .contains(new LockedRowVersion(STORED));

    fx.setReportTemplateUpdatedAt(templateId, null);
    Optional<LockedRowVersion> locked =
        inA(() -> reportTemplateRepository().lockUpdatedAtById(templateId));
    assertThat(locked).as("행은 있으므로 빈 값이 아니다").isPresent();
    assertThat(locked.get().updatedAt()).isNull();

    assertThat(inA(() -> reportTemplateRepository().lockUpdatedAtById(-1L))).isEmpty();
  }

  @Test
  @DisplayName("Proactive Job — 수정 시각이 비어 있어도 빈 값이 아니라 null 을 담은 값, 없는 id 는 빈 값")
  void proactiveJob_nullUpdatedAtIsPresentWithNull_notEmpty() {
    Long jobId =
        inA(
            () ->
                proactiveJobRepository()
                    .create(
                        userId,
                        "lock j",
                        "prompt",
                        null,
                        "0 9 * * *",
                        null,
                        false,
                        null,
                        Map.of()));
    fx.setProactiveJobUpdatedAt(jobId, STORED);

    assertThat(inA(() -> proactiveJobRepository().lockUpdatedAtById(jobId)))
        .contains(new LockedRowVersion(STORED));

    fx.setProactiveJobUpdatedAt(jobId, null);
    Optional<LockedRowVersion> locked =
        inA(() -> proactiveJobRepository().lockUpdatedAtById(jobId));
    assertThat(locked).as("행은 있으므로 빈 값이 아니다").isPresent();
    assertThat(locked.get().updatedAt()).isNull();

    assertThat(inA(() -> proactiveJobRepository().lockUpdatedAtById(-1L))).isEmpty();
  }
}
