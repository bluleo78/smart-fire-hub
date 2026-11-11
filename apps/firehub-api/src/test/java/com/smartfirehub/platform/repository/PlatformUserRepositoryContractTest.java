package com.smartfirehub.platform.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.platform.dto.PlatformAccountResponse;
import com.smartfirehub.platform.dto.PlatformUserResponse;
import com.smartfirehub.user.repository.AccessContractBase;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PlatformUserRepository 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-706).
 *
 * <p>운영자 콘솔 경로라 모든 호출을 테넌트 컨텍스트 없이 한다(리뷰 포인트 1). user 는 전역 테이블이라 소속 테넌트와 무관하게 모두 보여야 한다. 정렬 비교 키는
 * 접두 뒤 소문자 한 글자만 다르게 만든다 — PG 로캘 정렬과 Tibero 이진 정렬이 대소문자·구두점에서 갈리므로(Task 5 와 같은 회피). 사용자명은 접두를 뒤집어
 * 만들어 검색어(접두)에 걸리지 않게 하고, username 매칭은 따로 한 명으로 시험한다.
 */
public abstract class PlatformUserRepositoryContractTest extends AccessContractBase {

  protected abstract PlatformUserRepository repository();

  /** 검색어에 걸리지 않는 사용자명 접두(접두를 뒤집은 것). */
  private String userPrefix;

  private long uA;
  private long uB;
  private long uC1;
  private long uC2;
  private long uD1;
  private long uD2;
  private long uInactive;
  private long uNullEmail;
  private long uByUsername;
  private long tenantX;
  private long tenantY;

  /**
   * 검색어 = 접두. 이메일 정렬: a(uA) → b(uB) → c(uC2 이름 a, uC1 이름 b) → d(uD1·uD2 이메일·이름 같음 → id) →
   * e(uInactive) → x…(uByUsername, 사용자명만 맞음) → NULL(uNullEmail, 이름만 맞음, 두 DB 모두 오름차순에서 NULL 이 뒤).
   */
  @BeforeEach
  void setUp() {
    userPrefix = new StringBuilder(prefix).reverse().toString();
    uB = user("b", prefix + "b@x.io", "n");
    uA = user("a", prefix + "a@x.io", "n");
    uC1 = user("c1", prefix + "c@x.io", prefix + "b");
    uC2 = user("c2", prefix + "c@x.io", prefix + "a");
    uD1 = user("d1", prefix + "d@x.io", "n");
    uD2 = user("d2", prefix + "d@x.io", "n");
    uInactive = user("e", prefix + "e@x.io", "n");
    fx.setUserActive(uInactive, false);
    uNullEmail = user("z", null, prefix + "name");
    uByUsername = fx.createUser(prefix + "-login", "x" + userPrefix + "@x.io");
    tenantX = fx.createTenant();
    tenantY = fx.createTenant();
  }

  private long user(String suffix, String email, String name) {
    long id = fx.createUser(userPrefix + "-" + suffix, email);
    fx.setUserName(id, name);
    return id;
  }

  private <T> T call(Supplier<T> c) {
    return fx.noTenant(c);
  }

  private static List<Long> ids(List<PlatformUserResponse> list) {
    return list.stream().map(PlatformUserResponse::id).toList();
  }

  private static List<Long> accountIds(PageResponse<PlatformAccountResponse> page) {
    return page.content().stream().map(PlatformAccountResponse::id).toList();
  }

  @Test
  @DisplayName("search 는 활성 사용자만 이메일→이름→id 순으로, 이메일 NULL 은 뒤 — 사용자명은 맞추지 않는다")
  void search_orderAndFilters() {
    assertThat(ids(call(() -> repository().search(prefix, 20))))
        .containsExactly(uA, uB, uC2, uC1, uD1, uD2, uNullEmail);
  }

  @Test
  @DisplayName("search 상한이 정렬 뒤에 자른다")
  void search_limitCutsAfterOrdering() {
    assertThat(ids(call(() -> repository().search(prefix, 3)))).containsExactly(uA, uB, uC2);
  }

  @Test
  @DisplayName("이메일 정확일치(대소문자 무시)가 알파벳 순보다 먼저 온다")
  void search_exactEmailFirst() {
    String exact = prefix + "m@x.io";
    long exactUser = user("m", exact, "n");
    // 정확일치 이메일을 포함하면서 알파벳 순으로는 앞서는 이메일.
    long before = user("m0", "a" + exact, "n");

    assertThat(ids(call(() -> repository().search(exact.toUpperCase(), 20))))
        .containsExactly(exactUser, before);
  }

  @Test
  @DisplayName("LIKE 메타문자는 글자 그대로 맞춘다")
  void search_escapesLikeMetacharacters() {
    assertThat(call(() -> repository().search(prefix + "%", 20))).isEmpty();
    assertThat(call(() -> repository().search(prefix + "_@", 20))).isEmpty();
    assertThat(ids(call(() -> repository().search(prefix + "a@", 20)))).containsExactly(uA);
  }

  @Test
  @DisplayName("search 결과는 id·이메일·이름 세 필드만 싣는다")
  void search_fields() {
    assertThat(call(() -> repository().search(prefix + "a@", 20)))
        .containsExactly(new PlatformUserResponse(uA, prefix + "a@x.io", "n"));
  }

  @Test
  @DisplayName("findAccounts(검색어) 는 비활성·사용자명 매칭도 담고 검색 순으로 2쪽 이상 넘긴다")
  void findAccounts_search_pages() {
    PageResponse<PlatformAccountResponse> p0 = call(() -> repository().findAccounts(prefix, 0, 4));
    PageResponse<PlatformAccountResponse> p1 = call(() -> repository().findAccounts(prefix, 1, 4));
    PageResponse<PlatformAccountResponse> p2 = call(() -> repository().findAccounts(prefix, 2, 4));
    PageResponse<PlatformAccountResponse> p3 = call(() -> repository().findAccounts(prefix, 3, 4));

    assertThat(accountIds(p0)).containsExactly(uA, uB, uC2, uC1);
    assertThat(accountIds(p1)).containsExactly(uD1, uD2, uInactive, uByUsername);
    assertThat(accountIds(p2)).containsExactly(uNullEmail);
    assertThat(accountIds(p3)).isEmpty();
    assertThat(p0.totalElements()).isEqualTo(9);
    assertThat(p0.totalPages()).isEqualTo(3);
    assertThat(p1.page()).isEqualTo(1);
    assertThat(p1.size()).isEqualTo(4);
  }

  @Test
  @DisplayName("findAccounts 는 활성·운영자 여부·소속 수(정지 포함)·생성 시각을 싣는다")
  void findAccounts_fields() {
    long role = fx.createPlatformRole(prefix + "-op");
    fx.grantPlatformRole(uA, role);
    fx.createMembership(uB, tenantX, "ACTIVE");
    fx.createMembership(uB, tenantY, "SUSPENDED");
    LocalDateTime createdAt = LocalDateTime.of(2031, 2, 3, 4, 5, 6, 123_456_000);
    fx.setUserCreatedAt(uA, createdAt);

    List<PlatformAccountResponse> rows =
        call(() -> repository().findAccounts(prefix, 0, 20)).content();

    PlatformAccountResponse a = rows.get(0);
    assertThat(a)
        .isEqualTo(
            new PlatformAccountResponse(
                uA, userPrefix + "-a", prefix + "a@x.io", "n", true, true, 0, createdAt));
    PlatformAccountResponse b = rows.get(1);
    assertThat(b.id()).isEqualTo(uB);
    assertThat(b.operator()).isFalse();
    assertThat(b.membershipCount()).isEqualTo(2);
    PlatformAccountResponse inactive =
        rows.stream().filter(r -> r.id() == uInactive).findFirst().orElseThrow();
    assertThat(inactive.active()).isFalse();
  }

  @Test
  @DisplayName("findAccounts(null) 은 전체 목록을 생성 최신순, 같은 시각은 id 내림차순으로 — 2쪽이 겹치지 않는다")
  void findAccounts_all_newestFirst_tieById() {
    // 공유 DB 에서도 첫 쪽에 오도록 먼 미래 시각을 준다.
    LocalDateTime t0 = LocalDateTime.of(2999, 1, 1, 0, 0, 0);
    fx.setUserCreatedAt(uA, t0);
    fx.setUserCreatedAt(uB, t0);
    fx.setUserCreatedAt(uC1, t0.plusSeconds(1));

    PageResponse<PlatformAccountResponse> p0 = call(() -> repository().findAccounts(null, 0, 2));
    PageResponse<PlatformAccountResponse> p1 = call(() -> repository().findAccounts(null, 1, 2));

    // uB 는 uA 보다 먼저 만들었으므로 id 가 작다 — 같은 시각이면 id 내림차순이라 uA 가 앞.
    assertThat(accountIds(p0)).containsExactly(uC1, uA);
    assertThat(accountIds(p1)).startsWith(uB);
    assertThat(p0.totalElements()).isGreaterThanOrEqualTo(9);
    assertThat(p0.totalPages()).isEqualTo((int) ((p0.totalElements() + 1) / 2));
  }
}
