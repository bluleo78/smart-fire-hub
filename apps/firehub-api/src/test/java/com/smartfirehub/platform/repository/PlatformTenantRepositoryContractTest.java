package com.smartfirehub.platform.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.platform.dto.TenantMemberResponse;
import com.smartfirehub.platform.dto.TenantSummaryResponse;
import com.smartfirehub.user.repository.AccessContractBase;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

/**
 * PlatformTenantRepository 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-706).
 *
 * <p>운영자 콘솔 경로라 모든 호출을 테넌트 컨텍스트 없이 한다(리뷰 포인트 1). tenant·membership·user 는 정책 없는 전역 테이블이라 목록은 모든
 * 테넌트에 걸친 결과여야 한다(공유 DB 라 내가 만든 테넌트의 순서·집계만 정확히 본다). 빈 문자열은 DB 별 기대({@link #emptyStringIsNull()})로
 * 가른다.
 */
public abstract class PlatformTenantRepositoryContractTest extends AccessContractBase {

  protected abstract PlatformTenantRepository repository();

  private <T> T call(Supplier<T> c) {
    return fx.noTenant(c);
  }

  /** 리포지토리로 테넌트를 만들고 정리 대상에 올린다. */
  private long insertTenant(String slug, String name) {
    long id = call(() -> repository().insertTenant(slug, name));
    fx.trackTenant(id);
    return id;
  }

  @Test
  @DisplayName(
      "insertTenant 는 ACTIVE 테넌트를 만들고 id 를 돌려준다 — slugExists·existsById·findById 가 컨텍스트 없이 본다")
  void insertTenant_thenExistsAndFindById() {
    String slug = prefix + "-s";
    long id = insertTenant(slug, prefix + " name");

    TenantSummaryResponse t = call(() -> repository().findById(id)).orElseThrow();
    assertThat(t.id()).isEqualTo(id);
    assertThat(t.slug()).isEqualTo(slug);
    assertThat(t.name()).isEqualTo(prefix + " name");
    assertThat(t.status()).isEqualTo("ACTIVE");
    assertThat(t.memberCount()).isZero();
    assertThat(t.createdAt()).isNotNull();

    assertThat(call(() -> repository().slugExists(slug))).isTrue();
    assertThat(call(() -> repository().slugExists(slug + "x"))).isFalse();
    assertThat(call(() -> repository().existsById(id))).isTrue();
    assertThat(call(() -> repository().existsById(-1L))).isFalse();
    assertThat(call(() -> repository().findById(-1L))).isEmpty();
  }

  @Test
  @DisplayName("같은 slug 를 다시 넣으면 두 DB 모두 DuplicateKeyException")
  void insertTenant_duplicateSlug() {
    String slug = prefix + "-dup";
    insertTenant(slug, "first");

    assertThatThrownBy(() -> call(() -> repository().insertTenant(slug, "second")))
        .isInstanceOf(DuplicateKeyException.class);
  }

  @Test
  @DisplayName("빈 이름: PG 는 그대로 저장, Tibero 는 NOT NULL 위반으로 거절(빈 문자열 = NULL)")
  void insertTenant_emptyName_perDb() {
    if (emptyStringIsNull()) {
      assertThatThrownBy(() -> call(() -> repository().insertTenant(prefix + "-en", "")))
          .isInstanceOf(DataIntegrityViolationException.class);
      assertThat(call(() -> repository().slugExists(prefix + "-en"))).isFalse();
    } else {
      long id = insertTenant(prefix + "-en", "");
      assertThat(call(() -> repository().findById(id)).orElseThrow().name()).isEmpty();
    }
  }

  @Test
  @DisplayName(
      "빈 slug: PG 는 \"\" 로 저장·조회되고 두 번째는 중복, Tibero 는 NULL 로 저장돼 조회 null·slugExists false·중복 아님")
  void insertTenant_emptySlug_perDb() {
    long first = insertTenant("", prefix + " e1");
    if (emptyStringIsNull()) {
      assertThat(call(() -> repository().findById(first)).orElseThrow().slug()).isNull();
      assertThat(call(() -> repository().slugExists(""))).isFalse();
      // 유일 제약은 NULL 끼리 겹치지 않는다.
      long second = insertTenant("", prefix + " e2");
      assertThat(call(() -> repository().findById(second)).orElseThrow().slug()).isNull();
    } else {
      assertThat(call(() -> repository().findById(first)).orElseThrow().slug()).isEmpty();
      assertThat(call(() -> repository().slugExists(""))).isTrue();
      assertThatThrownBy(() -> call(() -> repository().insertTenant("", prefix + " e2")))
          .isInstanceOf(DuplicateKeyException.class);
    }
  }

  @Test
  @DisplayName("테넌트 생성 트랜잭션 안에서 insertOwnerMembership 을 두 번 불러도 OWNER 하나 — 트랜잭션이 깨지지 않는다")
  void insertOwnerMembership_idempotentInsideTransaction() {
    long owner = fx.createUser(prefix + "-own", null);

    long tenantId =
        fx.inTenant(
            null,
            () -> {
              long id = repository().insertTenant(prefix + "-tx", prefix + " tx");
              repository().insertOwnerMembership(id, owner);
              repository().insertOwnerMembership(id, owner);
              // 같은 트랜잭션에서 뒤 문장이 정상 실행돼야 한다(PG 는 삼킨 유일 위반이 트랜잭션을 중단시킨다).
              assertThat(repository().findMembers(id)).hasSize(1);
              return id;
            });
    fx.trackTenant(tenantId);

    assertThat(call(() -> repository().findMembers(tenantId)))
        .containsExactly(
            new TenantMemberResponse(owner, prefix + "-own", null, "OWNER", "ACTIVE", true));
    assertThat(call(() -> repository().findById(tenantId)).orElseThrow().memberCount())
        .isEqualTo(1);
  }

  @Test
  @DisplayName("insertOwnerMembership 은 이미 있는 멤버십의 라벨·상태를 바꾸지 않는다")
  void insertOwnerMembership_existingRowUntouched() {
    long tenantId = fx.createTenant();
    long user = fx.createUser(prefix + "-m", null);
    fx.createMembership(user, tenantId, "SUSPENDED");

    call(
        () -> {
          repository().insertOwnerMembership(tenantId, user);
          return null;
        });

    assertThat(call(() -> repository().findMembers(tenantId)))
        .containsExactly(
            new TenantMemberResponse(user, prefix + "-m", null, "MEMBER", "SUSPENDED", true));
  }

  @Test
  @DisplayName("findAll 은 모든 테넌트(기본 테넌트 포함·상태 무관)를 id 오름차순으로, 멤버 수는 정지 멤버십까지 센다")
  void findAll_allTenants_orderedById_memberCounts() {
    long a = fx.createTenant();
    long b = fx.createTenant();
    long c = fx.createTenant();
    fx.setTenantStatus(c, "SUSPENDED");
    long u1 = fx.createUser(prefix + "-1", null);
    long u2 = fx.createUser(prefix + "-2", null);
    fx.setUserActive(u2, false);
    fx.createMembership(u1, a, "ACTIVE");
    fx.createMembership(u2, a, "SUSPENDED");
    fx.createMembership(u1, c, "ACTIVE");
    LocalDateTime createdAt = LocalDateTime.of(2031, 2, 3, 4, 5, 6, 123_456_000);
    fx.setTenantCreatedAt(a, createdAt);

    List<TenantSummaryResponse> all = call(() -> repository().findAll());

    List<Long> ids = all.stream().map(TenantSummaryResponse::id).toList();
    assertThat(ids).isSorted().doesNotHaveDuplicates().contains(1L);
    Set<Long> mine = Set.of(a, b, c);
    List<TenantSummaryResponse> expected =
        java.util.stream.Stream.of(a, b, c)
            .sorted()
            .map(
                t ->
                    new TenantSummaryResponse(
                        t,
                        fx.tenantSlug(t),
                        fx.tenantName(t),
                        t == c ? "SUSPENDED" : "ACTIVE",
                        t == a ? 2 : (t == c ? 1 : 0),
                        null))
            .toList();
    List<TenantSummaryResponse> actualMine =
        all.stream()
            .filter(t -> mine.contains(t.id()))
            .map(
                t ->
                    new TenantSummaryResponse(
                        t.id(), t.slug(), t.name(), t.status(), t.memberCount(), null))
            .toList();
    assertThat(actualMine).containsExactlyElementsOf(expected);
    // 시각 왕복(리뷰 포인트 5): 저장 TZ 벽시계 그대로 마이크로초까지.
    assertThat(all.stream().filter(t -> t.id() == a).findFirst().orElseThrow().createdAt())
        .isEqualTo(createdAt);
  }

  @Test
  @DisplayName("findById 는 멤버 수(정지 포함)·상태를 싣는다")
  void findById_memberCount() {
    long a = fx.createTenant();
    long u1 = fx.createUser(prefix + "-1", null);
    long u2 = fx.createUser(prefix + "-2", null);
    fx.createMembership(u1, a, "ACTIVE");
    fx.createMembership(u2, a, "SUSPENDED");
    fx.setTenantStatus(a, "SUSPENDED");

    TenantSummaryResponse t = call(() -> repository().findById(a)).orElseThrow();
    assertThat(t.memberCount()).isEqualTo(2);
    assertThat(t.status()).isEqualTo("SUSPENDED");
    assertThat(t.slug()).isEqualTo(fx.tenantSlug(a));
  }

  @Test
  @DisplayName("updateStatus 는 바뀐 행 수 — 이미 그 상태거나 없는 테넌트는 0")
  void updateStatus_counts() {
    long a = fx.createTenant();

    assertThat(call(() -> repository().updateStatus(a, "SUSPENDED"))).isEqualTo(1);
    assertThat(call(() -> repository().findById(a)).orElseThrow().status()).isEqualTo("SUSPENDED");
    assertThat(call(() -> repository().updateStatus(a, "SUSPENDED"))).isZero();
    assertThat(call(() -> repository().updateStatus(-1L, "ACTIVE"))).isZero();
    assertThat(call(() -> repository().updateStatus(a, "ACTIVE"))).isEqualTo(1);
  }

  @Test
  @DisplayName("빈 상태값: PG 는 CHECK 위반, Tibero 는 status <> NULL 이 어떤 행도 맞추지 않아 0 행(상태 그대로)")
  void updateStatus_empty_perDb() {
    long a = fx.createTenant();

    if (emptyStringIsNull()) {
      assertThat(call(() -> repository().updateStatus(a, ""))).isZero();
    } else {
      assertThatThrownBy(() -> call(() -> repository().updateStatus(a, "")))
          .isInstanceOf(DataIntegrityViolationException.class);
    }
    assertThat(call(() -> repository().findById(a)).orElseThrow().status()).isEqualTo("ACTIVE");
  }

  @Test
  @DisplayName("findMembers 는 그 테넌트 멤버만 사용자 id 순으로, 멤버십 라벨·상태와 전역 계정 활성을 따로 싣는다")
  void findMembers_orderedByUserId() {
    long a = fx.createTenant();
    long other = fx.createTenant();
    long u1 = fx.createUser(prefix + "-1", null);
    long u2 = fx.createUser(prefix + "-2", prefix + "@x.io");
    long stranger = fx.createUser(prefix + "-3", null);
    fx.createMembership(u2, a, "SUSPENDED");
    fx.createMembership(u1, a, "ACTIVE");
    fx.setMembershipRole(u1, a, "OWNER");
    fx.setUserActive(u2, false);
    fx.createMembership(stranger, other, "ACTIVE");

    assertThat(call(() -> repository().findMembers(a)))
        .containsExactly(
            new TenantMemberResponse(u1, prefix + "-1", null, "OWNER", "ACTIVE", true),
            new TenantMemberResponse(
                u2, prefix + "-2", prefix + "@x.io", "MEMBER", "SUSPENDED", false));
    assertThat(call(() -> repository().findMembers(fx.createTenant()))).isEmpty();
  }
}
