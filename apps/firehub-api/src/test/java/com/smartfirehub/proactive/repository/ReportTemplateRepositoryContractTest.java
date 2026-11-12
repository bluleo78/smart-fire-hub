package com.smartfirehub.proactive.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.dto.LockedRowVersion;
import com.smartfirehub.proactive.dto.ReportTemplateResponse;
import com.smartfirehub.proactive.dto.ReportTemplateSummaryResponse;
import com.smartfirehub.user.repository.AccessFixtures.ReportTemplateStored;
import com.smartfirehub.user.repository.TenantPairContractBase;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ReportTemplateRepository 계약 테스트 — 공개 메서드 7 개 전부(WD-485·WD-709). jOOQ·MyBatis 는 PG, TiberoMyBatis
 * 는 Tibero 실서버(두 계정 RLS)다.
 *
 * <p>리뷰 초점: 시스템 템플릿(user_id NULL)은 모두에게 보이고 남의 템플릿은 목록·가시 조회에서 안 보인다, 섹션 개수는 저장된 JSON 배열 길이, 설명·스타일
 * 공백은 NULL 로 저장, 수정은 소유자만(빌트인·남의 것은 null), 수정 시각 잠금은 빈 옛 행도 "있음".
 */
abstract class ReportTemplateRepositoryContractTest extends TenantPairContractBase {

  /** 계약 대상 리포지토리 — 구현별 하위 클래스가 제공한다. */
  protected abstract ReportTemplateRepository repository();

  private long userA;
  private long userB;

  @BeforeEach
  void setUpUsers() {
    userA = fx.createUser(prefix + "-ua", null);
    userB = fx.createUser(prefix + "-ub", null);
  }

  private static List<Map<String, Object>> sections(int n) {
    return java.util.stream.IntStream.range(0, n)
        .<Map<String, Object>>mapToObj(
            i -> Map.of("key", "s" + i, "label", "섹션" + i, "type", "text"))
        .toList();
  }

  private long create(long tenant, long user, String name, int sectionCount) {
    return fx.inTenant(
        tenant, () -> repository().create(name, "설명", sections(sectionCount), "스타일", user));
  }

  private List<Long> listIds(long user, int page, int size) {
    return inA(() -> repository().findAllForUser(user, page, size)).stream()
        .map(ReportTemplateSummaryResponse::id)
        .toList();
  }

  @Test
  @DisplayName("create 는 섹션 JSON 을 저장하고 findById 가 같은 값으로 돌려준다")
  void create_roundTripsSections() {
    List<Map<String, Object>> secs = sections(3);
    Long id = inA(() -> repository().create("양식-" + prefix, "설명", secs, "스타일", userA));

    ReportTemplateResponse found = inA(() -> repository().findById(id)).orElseThrow();
    assertThat(found.name()).isEqualTo("양식-" + prefix);
    assertThat(found.description()).isEqualTo("설명");
    assertThat(found.style()).isEqualTo("스타일");
    assertThat(found.userId()).isEqualTo(userA);
    assertThat(found.builtin()).isFalse();
    assertThat(found.sections()).isEqualTo(secs);
    assertThat(found.createdAt()).isNotNull();
    assertThat(found.updatedAt()).isNotNull();
    // 삽입 시각은 DB 시계 기준이다.
    LocalDateTime dbNow = fx.dbWallClockNow();
    assertThat(Duration.between(found.createdAt(), dbNow).abs()).isLessThan(Duration.ofMinutes(5));
    assertThat(Duration.between(found.updatedAt(), dbNow).abs()).isLessThan(Duration.ofMinutes(5));
  }

  @Test
  @DisplayName("create 는 공백뿐인 설명·스타일을 NULL 로 저장한다(WD-135)")
  void create_blankDescriptionAndStyleBecomeNull() {
    Long blank = inA(() -> repository().create("b1-" + prefix, "   ", sections(1), "", userA));
    Long nulls = inA(() -> repository().create("b2-" + prefix, null, sections(1), null, userA));

    for (Long id : List.of(blank, nulls)) {
      ReportTemplateStored stored = fx.reportTemplateStored(id);
      assertThat(stored.description()).isNull();
      assertThat(stored.style()).isNull();
      ReportTemplateResponse found = inA(() -> repository().findById(id)).orElseThrow();
      assertThat(found.description()).isNull();
      assertThat(found.style()).isNull();
    }
  }

  @Test
  @DisplayName("findById 는 남의 양식도 돌려주지만 다른 테넌트·없는 번호는 빈 값이다")
  void findById_visibilityAndTenant() {
    long mine = create(tenantA, userA, "f1-" + prefix, 1);
    long others = create(tenantA, userB, "f2-" + prefix, 1);

    assertThat(inA(() -> repository().findById(mine))).isPresent();
    assertThat(inA(() -> repository().findById(others))).as("소유자 조건이 없다").isPresent();
    assertThat(inB(() -> repository().findById(mine))).as("다른 테넌트는 RLS 로 가려진다").isEmpty();
    assertThat(inA(() -> repository().findById(-1L))).isEmpty();
  }

  @Test
  @DisplayName("시스템 양식(user_id NULL)은 모두에게 보이고 남의 양식은 목록·가시 조회에서 빠진다")
  void findAllForUser_andFindVisibleTo_systemTemplateVisibleToEveryone() {
    long system = fx.createReportTemplate(tenantA, null, "sys-" + prefix);
    long mine = create(tenantA, userA, "mine-" + prefix, 2);
    long others = create(tenantA, userB, "oth-" + prefix, 2);

    List<ReportTemplateSummaryResponse> listA =
        inA(() -> repository().findAllForUser(userA, 0, 100));
    assertThat(listA).extracting(ReportTemplateSummaryResponse::id).contains(system, mine);
    assertThat(listA).extracting(ReportTemplateSummaryResponse::id).doesNotContain(others);
    ReportTemplateSummaryResponse sysRow =
        listA.stream().filter(r -> r.id() == system).findFirst().orElseThrow();
    assertThat(sysRow.builtin()).isTrue();
    assertThat(sysRow.userId()).isNull();
    assertThat(sysRow.sectionCount()).as("기본 sections 는 빈 배열").isZero();
    ReportTemplateSummaryResponse mineRow =
        listA.stream().filter(r -> r.id() == mine).findFirst().orElseThrow();
    assertThat(mineRow.builtin()).isFalse();
    assertThat(mineRow.sectionCount()).isEqualTo(2);
    assertThat(mineRow.name()).isEqualTo("mine-" + prefix);
    assertThat(mineRow.createdAt()).isNotNull();

    assertThat(listIds(userB, 0, 100)).contains(system, others).doesNotContain(mine);

    assertThat(inA(() -> repository().findVisibleTo(system, userA))).isPresent();
    assertThat(inA(() -> repository().findVisibleTo(system, userB))).isPresent();
    assertThat(inA(() -> repository().findVisibleTo(mine, userA))).isPresent();
    assertThat(inA(() -> repository().findVisibleTo(mine, userB))).as("남의 양식").isEmpty();
    assertThat(inB(() -> repository().findVisibleTo(system, userA))).as("다른 테넌트").isEmpty();
    assertThat(inA(() -> repository().findVisibleTo(-1L, userA))).isEmpty();

    // 다른 테넌트 목록에는 A 의 양식이 하나도 없다.
    assertThat(
            inB(() -> repository().findAllForUser(userA, 0, 100)).stream()
                .map(ReportTemplateSummaryResponse::id)
                .toList())
        .doesNotContain(system, mine, others);
  }

  /** jOOQ jsonb_array_length 는 원소 종류와 무관하게 센다 — 문자열 원소·null 원소·중첩 배열도 하나씩(WD-709 fix C2). */
  @Test
  @DisplayName("findAllForUser 의 섹션 개수는 객체가 아닌 원소도 하나씩 센 배열 길이다")
  void findAllForUser_sectionCountCountsNonObjectElements() {
    long mixed = create(tenantA, userA, "mixed-" + prefix, 1);
    fx.setReportTemplateSectionsJson(mixed, "[\"a\", {\"x\": 1}]");
    long nested = create(tenantA, userA, "nested-" + prefix, 1);
    fx.setReportTemplateSectionsJson(nested, "[null, [1, 2], 3]");

    Map<Long, Integer> counts = new HashMap<>();
    for (ReportTemplateSummaryResponse r : inA(() -> repository().findAllForUser(userA, 0, 100))) {
      counts.put(r.id(), r.sectionCount());
    }

    assertThat(counts.get(mixed)).isEqualTo(2);
    assertThat(counts.get(nested)).isEqualTo(3);
  }

  @Test
  @DisplayName("findAllForUser 는 id 오름차순으로 페이지를 나눈다")
  void findAllForUser_pagesInIdOrder() {
    long t1 = create(tenantA, userA, "p1-" + prefix, 1);
    long t2 = create(tenantA, userA, "p2-" + prefix, 1);
    long t3 = create(tenantA, userA, "p3-" + prefix, 1);

    List<Long> all = listIds(userA, 0, 100);
    assertThat(all).isSorted();
    assertThat(all).containsSubsequence(t1, t2, t3);

    List<Long> p0 = listIds(userA, 0, 2);
    List<Long> p1 = listIds(userA, 1, 2);
    assertThat(p0).hasSize(2).isEqualTo(all.subList(0, 2));
    assertThat(p1).isEqualTo(all.subList(2, Math.min(4, all.size())));
    assertThat(listIds(userA, 1000, 2)).isEmpty();
  }

  @Test
  @DisplayName("lockUpdatedAtById 는 저장된 수정 시각을, 빈 옛 행은 null 을 담은 값을, 없는 행은 빈 값을 돌려준다")
  void lockUpdatedAtById() {
    long id = create(tenantA, userA, "lock-" + prefix, 1);
    LocalDateTime fixed = LocalDateTime.of(2030, 1, 2, 3, 4, 5);
    fx.setReportTemplateUpdatedAt(id, fixed);

    assertThat(inA(() -> repository().lockUpdatedAtById(id))).contains(new LockedRowVersion(fixed));

    fx.setReportTemplateUpdatedAt(id, null);
    Optional<LockedRowVersion> locked = inA(() -> repository().lockUpdatedAtById(id));
    assertThat(locked).as("행은 있으므로 빈 값이 아니다").isPresent();
    assertThat(locked.get().updatedAt()).isNull();

    assertThat(inA(() -> repository().lockUpdatedAtById(-1L))).isEmpty();
    assertThat(inB(() -> repository().lockUpdatedAtById(id))).as("다른 테넌트").isEmpty();
  }

  @Test
  @DisplayName("update 는 넘긴 필드만 고치고 저장된 수정 시각을 돌려준다")
  void update_partialFieldsAndReturnsStoredUpdatedAt() {
    long id = create(tenantA, userA, "up-" + prefix, 2);
    LocalDateTime old = LocalDateTime.of(2030, 1, 2, 3, 4, 5);
    fx.setReportTemplateUpdatedAt(id, old);

    // 이름만 — 나머지는 그대로.
    LocalDateTime t1 = inA(() -> repository().update(id, userA, "바뀐-" + prefix, null, null, null));
    assertThat(t1).isNotNull();
    ReportTemplateStored s1 = fx.reportTemplateStored(id);
    assertThat(s1.name()).isEqualTo("바뀐-" + prefix);
    assertThat(s1.description()).isEqualTo("설명");
    assertThat(s1.style()).isEqualTo("스타일");
    assertThat(s1.updatedAt()).as("반환값은 저장된 수정 시각이다").isEqualTo(t1);
    assertThat(inA(() -> repository().findById(id)).orElseThrow().sections()).hasSize(2);

    // 섹션 교체·설명 비움(공백은 비움 = NULL)·스타일 교체.
    List<Map<String, Object>> newSecs = sections(4);
    LocalDateTime t2 = inA(() -> repository().update(id, userA, null, "  ", newSecs, "새 스타일"));
    assertThat(t2).isNotNull();
    ReportTemplateResponse r2 = inA(() -> repository().findById(id)).orElseThrow();
    assertThat(r2.name()).isEqualTo("바뀐-" + prefix);
    assertThat(r2.description()).isNull();
    assertThat(r2.style()).isEqualTo("새 스타일");
    assertThat(r2.sections()).isEqualTo(newSecs);
    assertThat(r2.updatedAt()).isEqualTo(t2);

    // 모두 null 이어도 수정 시각은 갱신된다.
    LocalDateTime t3 = inA(() -> repository().update(id, userA, null, null, null, null));
    assertThat(t3).isNotNull();
    assertThat(fx.reportTemplateStored(id).updatedAt()).isEqualTo(t3);
  }

  @Test
  @DisplayName("update 는 남의 양식·시스템 양식·다른 테넌트에는 null 을 돌려주고 행을 바꾸지 않는다")
  void update_ownerOnly() {
    long id = create(tenantA, userA, "own-" + prefix, 1);
    long system = fx.createReportTemplate(tenantA, null, "sys-" + prefix);

    assertThat(inA(() -> repository().update(id, userB, "x", null, null, null))).isNull();
    assertThat(inA(() -> repository().update(system, userA, "x", null, null, null))).isNull();
    assertThat(inB(() -> repository().update(id, userA, "x", null, null, null))).isNull();
    assertThat(inA(() -> repository().update(-1L, userA, "x", null, null, null))).isNull();
    assertThat(fx.reportTemplateStored(id).name()).isEqualTo("own-" + prefix);
    assertThat(fx.reportTemplateStored(system).name()).isEqualTo("sys-" + prefix);
  }

  @Test
  @DisplayName("delete 는 본인 양식만 지우고 지운 행 수를 돌려준다(빌트인·남의 것·두 번째 삭제는 0)")
  void delete_ownerOnly() {
    long id = create(tenantA, userA, "del-" + prefix, 1);
    long system = fx.createReportTemplate(tenantA, null, "sys-" + prefix);

    assertThat(inA(() -> repository().delete(id, userB))).isZero();
    assertThat(inA(() -> repository().delete(system, userA))).isZero();
    assertThat(inB(() -> repository().delete(id, userA))).as("다른 테넌트").isZero();
    assertThat(fx.reportTemplateStored(id)).isNotNull();
    assertThat(fx.reportTemplateStored(system)).isNotNull();

    assertThat(inA(() -> repository().delete(id, userA))).isEqualTo(1);
    assertThat(fx.reportTemplateStored(id)).isNull();
    assertThat(inA(() -> repository().delete(id, userA))).isZero();
  }
}
