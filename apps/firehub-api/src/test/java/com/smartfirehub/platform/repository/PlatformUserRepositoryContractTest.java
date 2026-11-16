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

  /**
   * WD-773: 운영자 콘솔 멤버 추가 「새 계정」 탭은 정규화 이메일로 {@code findAccounts(q, 0, 20)} 을 불러 username 이 정확히 같은
   * 계정을 찾는다(서버가 기존 계정을 username 으로 찾으므로). 부분일치가 20건을 넘고 그 계정이 이메일 순으로 뒤에 있으면 첫 쪽에 없어 놓쳤다 — username
   * 정확일치를 맨 앞으로 올려 첫 쪽 첫 행에 오게 한다.
   *
   * <p>부분일치 21명은 이메일이 "a…"+q 라 이메일 오름차순으로 앞서고, 정확일치 계정은 이메일이 q 를 포함하지 않는 "z…" 라 이메일 순으로는 맨 뒤다.
   */
  @Test
  @DisplayName("findAccounts(검색어) 는 username 정확일치를 부분일치 20건 너머에 있어도 첫 쪽 첫 행에 둔다")
  void findAccounts_exactUsernameFirst_beyondFirstPage() {
    String q = prefix + "-kim@x.io";
    for (int i = 0; i < 21; i++) {
      // 두 자리 번호로 이메일 순서를 결정적으로 — 모두 q 를 포함해 부분일치에 걸린다.
      user("p" + i, "a" + String.format("%02d", i) + q, "n");
    }
    long exact = fx.createUser(q, "z" + userPrefix + "@x.io");

    PageResponse<PlatformAccountResponse> p0 = call(() -> repository().findAccounts(q, 0, 20));

    assertThat(p0.totalElements()).isEqualTo(22);
    assertThat(accountIds(p0).get(0)).isEqualTo(exact);
  }

  /**
   * WD-773: 서버의 기존 계정 찾기({@code findByUsernameIgnoreCase})와 같은 뜻으로 username 정확일치는 대소문자를 무시한다 — 대문자가
   * 섞여 저장된 username 도 소문자 검색어에 맨 앞이다. 이메일 정확일치 계정보다도 앞선다(멤버 추가가 붙일 계정은 username 으로 정해지므로).
   */
  @Test
  @DisplayName("findAccounts(검색어) 의 username 정확일치는 대소문자를 무시하고 이메일 정확일치보다 앞선다")
  void findAccounts_exactUsernameIgnoringCase_beforeExactEmail() {
    String q = prefix + "-kim@x.io";
    // 이메일이 검색어와 정확히 같은 다른 계정 — 지금까지의 정렬이면 이 계정이 맨 앞이었다.
    long exactEmail = user("byemail", q, "n");
    long exactUsername = fx.createUser(prefix + "-Kim@X.io", "z" + userPrefix + "@x.io");

    assertThat(accountIds(call(() -> repository().findAccounts(q, 0, 20))))
        .containsExactly(exactUsername, exactEmail);
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
  @DisplayName("findAccounts(null) 은 전체 목록을 생성 최신순, 같은 시각은 id 내림차순으로 — 쪽이 겹치지 않는다")
  void findAccounts_all_newestFirst_tieById() {
    // 검색어가 없으면 정렬이 생성 시각 순이라 검색어로 자기 행만 모을 수 없다. 공유 DB 의 다른 행에 기대지 않도록(WD-736)
    // 자기 픽스처 셋에 먼 미래 시각을 주고, 쪽을 하나씩 넘기며 자기 행이 다 나올 때까지만 읽어 자기 행의 상대 순서와 쪽 겹침만 단언한다.
    // 전체 건수나 서로 다른 시점의 질의 결과 비교에는 기대지 않는다.
    LocalDateTime t0 = LocalDateTime.of(2999, 1, 1, 0, 0, 0);
    fx.setUserCreatedAt(uA, t0);
    fx.setUserCreatedAt(uB, t0);
    fx.setUserCreatedAt(uC1, t0.plusSeconds(1));
    List<Long> own = List.of(uC1, uA, uB);

    List<Long> ownSeen = new java.util.ArrayList<>();
    List<Long> allSeen = new java.util.ArrayList<>();
    for (int page = 0; page < 50 && ownSeen.size() < own.size(); page++) {
      int p = page;
      List<Long> ids = accountIds(call(() -> repository().findAccounts(null, p, 2)));
      assertThat(ids).hasSizeLessThanOrEqualTo(2);
      assertThat(java.util.Collections.disjoint(ids, allSeen)).isTrue();
      allSeen.addAll(ids);
      ids.stream().filter(own::contains).forEach(ownSeen::add);
    }

    // uB 는 uA 보다 먼저 만들었으므로 id 가 작다 — 같은 시각이면 id 내림차순이라 uA 가 앞.
    assertThat(ownSeen).containsExactly(uC1, uA, uB);
  }
}
