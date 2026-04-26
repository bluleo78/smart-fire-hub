package com.smartfirehub.analytics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.analytics.dto.AnalyticsQueryResponse;
import com.smartfirehub.analytics.dto.CreateSavedQueryRequest;
import com.smartfirehub.analytics.dto.SavedQueryResponse;
import com.smartfirehub.analytics.exception.SavedQueryNotFoundException;
import com.smartfirehub.support.IntegrationTestBase;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * SavedQueryService 추가 통합 테스트. 기존 SavedQueryServiceTest에서 커버되지 않은 분기: - executeById(): 정상 실행 및 접근
 * 불가 예외
 */
@Transactional
class SavedQueryServiceExtTest extends IntegrationTestBase {

  @Autowired private SavedQueryService savedQueryService;
  @Autowired private DSLContext dsl;

  private Long ownerUserId;
  private Long otherUserId;

  @BeforeEach
  void setUp() {
    ownerUserId =
        dsl.insertInto(DSL.table(DSL.name("user")))
            .set(
                DSL.field(DSL.name("user", "username"), String.class),
                "sq_ext_owner_" + System.nanoTime())
            .set(DSL.field(DSL.name("user", "password"), String.class), "password")
            .set(DSL.field(DSL.name("user", "name"), String.class), "SQ Ext Owner")
            .set(
                DSL.field(DSL.name("user", "email"), String.class),
                "sq_ext_" + System.nanoTime() + "@example.com")
            .returning(DSL.field(DSL.name("user", "id"), Long.class))
            .fetchOne()
            .get(DSL.field(DSL.name("user", "id"), Long.class));

    otherUserId =
        dsl.insertInto(DSL.table(DSL.name("user")))
            .set(
                DSL.field(DSL.name("user", "username"), String.class),
                "sq_ext_other_" + System.nanoTime())
            .set(DSL.field(DSL.name("user", "password"), String.class), "password")
            .set(DSL.field(DSL.name("user", "name"), String.class), "SQ Ext Other")
            .set(
                DSL.field(DSL.name("user", "email"), String.class),
                "sq_ext_other_" + System.nanoTime() + "@example.com")
            .returning(DSL.field(DSL.name("user", "id"), Long.class))
            .fetchOne()
            .get(DSL.field(DSL.name("user", "id"), Long.class));
  }

  // ── executeById(): 정상 실행 ─────────────────────────────────────────────────

  @Test
  void executeById_ownerCanExecute_returnsQueryResult() {
    SavedQueryResponse query =
        savedQueryService.create(
            new CreateSavedQueryRequest("Exec Query", null, "SELECT 100 AS val", null, null, false),
            ownerUserId);

    AnalyticsQueryResponse result =
        savedQueryService.executeById(query.id(), 100, false, ownerUserId);

    assertThat(result).isNotNull();
    assertThat(result.queryType()).isEqualTo("SELECT");
    assertThat(result.totalRows()).isEqualTo(1);
  }

  @Test
  void executeById_sharedQuery_otherUserCanExecute() {
    SavedQueryResponse query =
        savedQueryService.create(
            new CreateSavedQueryRequest("Shared Exec", null, "SELECT 200 AS val", null, null, true),
            ownerUserId);

    AnalyticsQueryResponse result =
        savedQueryService.executeById(query.id(), 100, true, otherUserId);

    assertThat(result).isNotNull();
    assertThat(result.totalRows()).isEqualTo(1);
  }

  @Test
  void executeById_privateQuery_otherUserThrowsNotFound() {
    SavedQueryResponse query =
        savedQueryService.create(
            new CreateSavedQueryRequest("Private Exec", null, "SELECT 1", null, null, false),
            ownerUserId);

    assertThatThrownBy(() -> savedQueryService.executeById(query.id(), 100, false, otherUserId))
        .isInstanceOf(SavedQueryNotFoundException.class);
  }

  @Test
  void executeById_readOnly_returnsResult() {
    SavedQueryResponse query =
        savedQueryService.create(
            new CreateSavedQueryRequest("ReadOnly Exec", null, "SELECT 42 AS n", null, null, false),
            ownerUserId);

    AnalyticsQueryResponse result =
        savedQueryService.executeById(query.id(), 50, true, ownerUserId);

    assertThat(result.totalRows()).isEqualTo(1);
  }
}
