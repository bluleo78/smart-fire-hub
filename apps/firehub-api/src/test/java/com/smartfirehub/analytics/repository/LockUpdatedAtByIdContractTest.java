package com.smartfirehub.analytics.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.analytics.dto.CreateChartRequest;
import com.smartfirehub.analytics.dto.CreateDashboardRequest;
import com.smartfirehub.analytics.dto.CreateSavedQueryRequest;
import com.smartfirehub.global.dto.LockedRowVersion;
import com.smartfirehub.proactive.repository.ProactiveJobRepository;
import com.smartfirehub.proactive.repository.ReportTemplateRepository;
import com.smartfirehub.support.IntegrationTestBase;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code lockUpdatedAtById} 다섯 곳(차트·대시보드·저장 쿼리·리포트 양식·Proactive Job)의 실 DB 검증(WD-485).
 *
 * <p>반환 타입을 jOOQ {@code Record1} 에서 {@link LockedRowVersion} 으로 바꿨다. 지켜야 할 것: 행이 없으면 빈 값, 행이 있으면
 * 저장된 수정 시각, 그리고 수정 시각이 비어 있는(nullable, V42) 옛 행은 <b>빈 값이 아니라 null 을 담은 값</b>이어야 한다 — 섞이면 그런 행의 편집이
 * 404 로 거부된다.
 *
 * <p>인터페이스 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-485).
 */
@Transactional
abstract class LockUpdatedAtByIdContractTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;

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

  private Long userId;

  @BeforeEach
  void setUp() {
    userId =
        dsl.insertInto(DSL.table(DSL.name("user")))
            .set(DSL.field(DSL.name("user", "username"), String.class), "lock_ts_user")
            .set(DSL.field(DSL.name("user", "password"), String.class), "password")
            .set(DSL.field(DSL.name("user", "name"), String.class), "Lock Ts User")
            .set(DSL.field(DSL.name("user", "email"), String.class), "lock_ts@example.com")
            .returning(DSL.field(DSL.name("user", "id"), Long.class))
            .fetchOne()
            .get(DSL.field(DSL.name("user", "id"), Long.class));
  }

  /** 테이블의 수정 시각을 그대로 읽는다 — 리포지토리가 돌려준 값과 비교하는 기준. */
  private LocalDateTime storedUpdatedAt(String table, Long id) {
    return dsl.select(DSL.field(DSL.name(table, "updated_at"), LocalDateTime.class))
        .from(DSL.table(DSL.name(table)))
        .where(DSL.field(DSL.name(table, "id"), Long.class).eq(id))
        .fetchOne()
        .value1();
  }

  /** 수정 시각을 비운다 — nullable 컬럼의 옛 행을 재현한다. */
  private void clearUpdatedAt(String table, Long id) {
    dsl.update(DSL.table(DSL.name(table)))
        .setNull(DSL.field(DSL.name(table, "updated_at"), LocalDateTime.class))
        .where(DSL.field(DSL.name(table, "id"), Long.class).eq(id))
        .execute();
  }

  @Test
  void analytics_presentRowReturnsStoredUpdatedAt_missingRowIsEmpty() {
    Long queryId =
        savedQueryRepository()
            .insert(
                new CreateSavedQueryRequest("lock q", null, "SELECT 1", null, null, false), userId);
    Long chartId =
        chartRepository()
            .insert(
                new CreateChartRequest("lock c", null, queryId, "BAR", Map.of(), false), userId);
    Long dashboardId =
        dashboardRepository()
            .insert(new CreateDashboardRequest("lock d", null, false, null), userId);

    assertThat(savedQueryRepository().lockUpdatedAtById(queryId))
        .contains(new LockedRowVersion(storedUpdatedAt("saved_query", queryId)));
    assertThat(chartRepository().lockUpdatedAtById(chartId))
        .contains(new LockedRowVersion(storedUpdatedAt("chart", chartId)));
    assertThat(dashboardRepository().lockUpdatedAtById(dashboardId))
        .contains(new LockedRowVersion(storedUpdatedAt("dashboard", dashboardId)));

    assertThat(savedQueryRepository().lockUpdatedAtById(-1L)).isEmpty();
    assertThat(chartRepository().lockUpdatedAtById(-1L)).isEmpty();
    assertThat(dashboardRepository().lockUpdatedAtById(-1L)).isEmpty();
  }

  @Test
  void reportTemplate_nullUpdatedAtIsPresentWithNull_notEmpty() {
    Long templateId =
        reportTemplateRepository()
            .create("lock t", null, List.of(Map.of("key", "s1", "type", "text")), null, userId);

    assertThat(reportTemplateRepository().lockUpdatedAtById(templateId))
        .contains(new LockedRowVersion(storedUpdatedAt("report_template", templateId)));

    clearUpdatedAt("report_template", templateId);
    Optional<LockedRowVersion> locked = reportTemplateRepository().lockUpdatedAtById(templateId);
    assertThat(locked).as("행은 있으므로 빈 값이 아니다").isPresent();
    assertThat(locked.get().updatedAt()).isNull();

    assertThat(reportTemplateRepository().lockUpdatedAtById(-1L)).isEmpty();
  }

  @Test
  void proactiveJob_nullUpdatedAtIsPresentWithNull_notEmpty() {
    Long jobId =
        proactiveJobRepository()
            .create(userId, "lock j", "prompt", null, "0 9 * * *", null, false, null, Map.of());

    assertThat(proactiveJobRepository().lockUpdatedAtById(jobId))
        .contains(new LockedRowVersion(storedUpdatedAt("proactive_job", jobId)));

    clearUpdatedAt("proactive_job", jobId);
    Optional<LockedRowVersion> locked = proactiveJobRepository().lockUpdatedAtById(jobId);
    assertThat(locked).as("행은 있으므로 빈 값이 아니다").isPresent();
    assertThat(locked.get().updatedAt()).isNull();

    assertThat(proactiveJobRepository().lockUpdatedAtById(-1L)).isEmpty();
  }
}
