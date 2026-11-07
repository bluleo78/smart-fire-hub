package com.smartfirehub.dataset.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.dto.CategoryResponse;
import com.smartfirehub.dataset.exception.CategoryNotFoundException;
import com.smartfirehub.support.ConcurrentCalls;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.PausedTransactionRace;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.List;
import java.util.Objects;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * WD-584 — 같은 카테고리를 동시에 여러 번 지우면 모두 204 가 되고 「삭제」 감사가 요청 수만큼 남던 문제.
 *
 * <p>존재 확인 뒤 지우는 흐름이라, 먼저 지운 쪽이 커밋하기 전에 확인을 통과한 요청은 DELETE 가 0 행이어도 성공으로 끝났다. 진 쪽은 순차 두 번째 삭제와 같은
 * 404 여야 하고 감사는 실제로 지운 1건만 남아야 한다.
 *
 * <p>클래스 레벨 {@code @Transactional} 을 쓰지 않는다 — 각 요청이 실제처럼 자기 트랜잭션으로 커밋해야 서로를 기다린다.
 */
class DatasetCategoryConcurrentDeleteTest extends IntegrationTestBase {

  @Autowired private DatasetCategoryService categoryService;
  @Autowired private DSLContext dsl;

  private Long userId;
  private String suffix;

  @BeforeEach
  void setUp() {
    suffix = Long.toString(System.nanoTime());
    userId = TenantRlsTestSupport.insertUser(dsl, "wd584_cat_");
  }

  @AfterEach
  void tearDown() {
    inTenantFixture(
        () -> {
          dsl.execute("DELETE FROM audit_log WHERE user_id = ?", userId);
          dsl.execute("DELETE FROM dataset_category WHERE name LIKE ?", "WD584 " + suffix + "%");
        });
    TenantRlsTestSupport.deleteUser(dsl, userId);
  }

  private CategoryResponse create(String label) {
    return categoryService.createCategory("WD584 " + suffix + " " + label, null, userId);
  }

  private int deleteAudits(Long categoryId) {
    return inTenantFixture(
        () ->
            dsl.fetchOne(
                    "SELECT count(*) FROM audit_log WHERE action_type = 'DELETE' AND resource ="
                        + " 'dataset_category' AND resource_id = ?",
                    String.valueOf(categoryId))
                .get(0, Integer.class));
  }

  @Test
  void 같은_카테고리를_6건_동시에_지우면_1건만_성공하고_나머지는_404다() throws Exception {
    CategoryResponse category = create("six");

    List<Throwable> results =
        ConcurrentCalls.run(
            6, DEFAULT_TEST_TENANT_ID, () -> categoryService.deleteCategory(category.id(), userId));

    assertThat(results.stream().filter(Objects::isNull).count()).isEqualTo(1);
    assertThat(results.stream().filter(Objects::nonNull).toList())
        .hasSize(5)
        .allMatch(CategoryNotFoundException.class::isInstance);
    assertThat(deleteAudits(category.id())).isEqualTo(1);
  }

  @Test
  void 지운_트랜잭션이_커밋되기_전_같은_삭제는_기다렸다가_404이고_감사는_1건이다() throws Exception {
    CategoryResponse category = create("paused");

    PausedTransactionRace.Outcome<Void> out =
        PausedTransactionRace.run(
            fixtureTransactionTemplate,
            DEFAULT_TEST_TENANT_ID,
            () -> categoryService.deleteCategory(category.id(), userId),
            () -> {
              categoryService.deleteCategory(category.id(), userId);
              return null;
            });

    assertThat(out.secondBlocked()).isTrue();
    assertThat(out.secondError()).isInstanceOf(CategoryNotFoundException.class);
    assertThat(deleteAudits(category.id())).isEqualTo(1);
  }

  @Test
  void 순차로_두_번_지우면_두_번째는_404다() {
    CategoryResponse category = create("seq");

    categoryService.deleteCategory(category.id(), userId);

    assertThatThrownBy(() -> categoryService.deleteCategory(category.id(), userId))
        .isInstanceOf(CategoryNotFoundException.class);
    assertThat(deleteAudits(category.id())).isEqualTo(1);
  }
}
