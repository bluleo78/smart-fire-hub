package com.smartfirehub.tenant.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.tenant.dto.MembershipResponse;
import com.smartfirehub.tenant.dto.TenantMembership;
import com.smartfirehub.tenant.dto.UnavailableWorkspace;
import com.smartfirehub.user.repository.AccessContractBase;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

/**
 * MembershipRepository 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-706).
 *
 * <p>membership·tenant·user 는 정책 없는 전역 테이블이라 모든 호출을 테넌트 컨텍스트 없이 한다(로그인·테넌트 선택 전 경로 — 리뷰 포인트 1). 테넌트
 * 경계는 tenantId 인자가 세운다. 기존 {@code MembershipScopeRepositoryContractTest}(정지·제거의 테넌트 범위)는 그대로 두고
 * 인터페이스의 나머지를 여기서 덮는다.
 */
public abstract class MembershipRepositoryContractTest extends AccessContractBase {

  protected abstract MembershipRepository repository();

  private long tenantA;
  private long tenantB;
  private long tenantC;
  private long user;

  /** A: 멤버십·테넌트 ACTIVE, B: 멤버십 정지, C: 테넌트 정지(멤버십 ACTIVE). */
  @BeforeEach
  void setUp() {
    tenantA = fx.createTenant();
    tenantB = fx.createTenant();
    tenantC = fx.createTenant();
    fx.setTenantStatus(tenantC, "SUSPENDED");
    user = fx.createUser(prefix + "-u", null);
    fx.createMembership(user, tenantA, "ACTIVE");
    fx.createMembership(user, tenantB, "SUSPENDED");
    fx.createMembership(user, tenantC, "ACTIVE");
  }

  private <T> T call(java.util.function.Supplier<T> c) {
    return fx.noTenant(c);
  }

  @Test
  @DisplayName("선택 가능 목록은 멤버십·테넌트 모두 ACTIVE 인 것만, 테넌트 id 오름차순으로 slug·이름·라벨을 싣는다")
  void findActiveByUser_onlyBothActive_ordered() {
    long tenantD = fx.createTenant();
    fx.createMembership(user, tenantD, "ACTIVE");

    List<MembershipResponse> list = call(() -> repository().findActiveByUser(user));

    // 테넌트 id 는 DB 마다 생성 규칙이 달라(Tibero 픽스처는 무작위 예약 대역) 기대 순서도 id 로 정렬해 만든다.
    List<MembershipResponse> expected =
        java.util.stream.Stream.of(tenantA, tenantD)
            .sorted()
            .map(t -> new MembershipResponse(t, fx.tenantSlug(t), fx.tenantName(t), "MEMBER"))
            .toList();
    assertThat(list).containsExactlyElementsOf(expected);
    assertThat(call(() -> repository().findActiveByUser(-1L))).isEmpty();
  }

  @Test
  @DisplayName("들어갈 수 없는 목록은 여집합이고 테넌트 정지가 멤버십 상태보다 우선한다")
  void findUnavailableByUser_reasons() {
    // E 는 멤버십·테넌트 둘 다 정지 — 그래도 사유는 워크스페이스 정지다.
    long tenantE = fx.createTenant();
    fx.createMembership(user, tenantE, "SUSPENDED");
    fx.setTenantStatus(tenantE, "SUSPENDED");

    List<UnavailableWorkspace> list = call(() -> repository().findUnavailableByUser(user));

    Map<Long, UnavailableWorkspace.Reason> reasons =
        Map.of(
            tenantB, UnavailableWorkspace.Reason.MEMBERSHIP_SUSPENDED,
            tenantC, UnavailableWorkspace.Reason.WORKSPACE_SUSPENDED,
            tenantE, UnavailableWorkspace.Reason.WORKSPACE_SUSPENDED);
    List<UnavailableWorkspace> expected =
        reasons.keySet().stream()
            .sorted()
            .map(t -> new UnavailableWorkspace(fx.tenantName(t), reasons.get(t)))
            .toList();
    assertThat(list).containsExactlyElementsOf(expected);
  }

  @Test
  @DisplayName("hasActiveMembership 은 멤버십·테넌트 모두 ACTIVE 일 때만 true")
  void hasActiveMembership() {
    long other = fx.createUser(prefix + "-o", null);

    assertThat(call(() -> repository().hasActiveMembership(user, tenantA))).isTrue();
    assertThat(call(() -> repository().hasActiveMembership(user, tenantB))).isFalse();
    assertThat(call(() -> repository().hasActiveMembership(user, tenantC))).isFalse();
    assertThat(call(() -> repository().hasActiveMembership(other, tenantA))).isFalse();
  }

  @Test
  @DisplayName("createDefaultMembership 은 기본 테넌트(1)에 ACTIVE MEMBER 로 넣는다")
  void createDefaultMembership_joinsDefaultTenant() {
    long fresh = fx.createUser(prefix + "-new", null);

    call(
        () -> {
          repository().createDefaultMembership(fresh);
          return null;
        });

    assertThat(call(() -> repository().findInTenant(fresh, MembershipRepository.DEFAULT_TENANT_ID)))
        .contains(new TenantMembership(fresh, "MEMBER", "ACTIVE"));
  }

  @Test
  @DisplayName("findInTenant(단건)는 상태와 무관하게 그 테넌트 행만, 비멤버는 빈 값")
  void findInTenant_single() {
    assertThat(call(() -> repository().findInTenant(user, tenantB)))
        .contains(new TenantMembership(user, "MEMBER", "SUSPENDED"));
    assertThat(call(() -> repository().findInTenant(user, tenantA)))
        .contains(new TenantMembership(user, "MEMBER", "ACTIVE"));
    long other = fx.createUser(prefix + "-o", null);
    assertThat(call(() -> repository().findInTenant(other, tenantA))).isEmpty();
  }

  @Test
  @DisplayName("findInTenant(배치)는 정지 멤버도 담고 비멤버는 빼며, 빈 입력은 빈 맵 — IN 목록 1000 개를 넘어도 된다")
  void findInTenant_batch_suspendedIncluded_chunked() {
    long second = fx.createUser(prefix + "-s", null);
    fx.createMembership(second, tenantB, "ACTIVE");
    long stranger = fx.createUser(prefix + "-x", null);

    List<Long> ids = manyIds(user, second, stranger);
    Map<Long, TenantMembership> map = call(() -> repository().findInTenant(tenantB, ids));

    assertThat(map)
        .containsOnlyKeys(user, second)
        .containsEntry(user, new TenantMembership(user, "MEMBER", "SUSPENDED"))
        .containsEntry(second, new TenantMembership(second, "MEMBER", "ACTIVE"));
    assertThat(call(() -> repository().findInTenant(tenantB, List.<Long>of()))).isEmpty();
  }

  @Test
  @DisplayName("findActiveMemberIds 는 계정 활성 + ACTIVE 멤버 + ACTIVE 테넌트만 — IN 목록 1000 개를 넘어도 된다")
  void findActiveMemberIds_filters_chunked() {
    long inactive = fx.createUser(prefix + "-i", null);
    fx.createMembership(inactive, tenantA, "ACTIVE");
    fx.setUserActive(inactive, false);
    long suspendedMember = fx.createUser(prefix + "-sm", null);
    fx.createMembership(suspendedMember, tenantA, "SUSPENDED");

    assertThat(
            call(
                () ->
                    repository()
                        .findActiveMemberIds(tenantA, manyIds(user, inactive, suspendedMember))))
        .containsExactly(user);
    // 테넌트가 정지면 멤버십이 ACTIVE 여도 아무도 아니다.
    assertThat(call(() -> repository().findActiveMemberIds(tenantC, List.of(user)))).isEmpty();
    assertThat(call(() -> repository().findActiveMemberIds(tenantA, List.<Long>of()))).isEmpty();
  }

  @Test
  @DisplayName("insertMember 는 ACTIVE MEMBER 로 넣고, 같은 쌍을 다시 넣으면 두 DB 모두 DuplicateKeyException")
  void insertMember_andDuplicate() {
    long fresh = fx.createUser(prefix + "-m", null);

    call(
        () -> {
          repository().insertMember(fresh, tenantA);
          return null;
        });

    assertThat(call(() -> repository().findInTenant(fresh, tenantA)))
        .contains(new TenantMembership(fresh, "MEMBER", "ACTIVE"));
    assertThatThrownBy(
            () ->
                call(
                    () -> {
                      repository().insertMember(fresh, tenantA);
                      return null;
                    }))
        .isInstanceOf(DuplicateKeyException.class);
  }

  @Test
  @DisplayName("updateStatus 는 바뀐 행 수를 돌려주고 비멤버는 0 — 빈 상태값은 두 DB 모두 거절")
  void updateStatus_counts_andEmptyRejected() {
    assertThat(call(() -> repository().updateStatus(user, tenantA, "SUSPENDED"))).isEqualTo(1);
    assertThat(call(() -> repository().findInTenant(user, tenantA)).orElseThrow().status())
        .isEqualTo("SUSPENDED");
    assertThat(call(() -> repository().updateStatus(-1L, tenantA, "ACTIVE"))).isZero();
    // 빈 문자열(리뷰 포인트 2): PG 는 CHECK 위반, Tibero 는 '' = NULL 이라 NOT NULL 위반 — 둘 다 무결성 위반이다.
    assertThatThrownBy(() -> call(() -> repository().updateStatus(user, tenantA, "")))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("delete 는 그 테넌트 멤버십만 지우고 지운 행 수를 돌려준다")
  void delete_counts() {
    assertThat(call(() -> repository().delete(user, tenantA))).isEqualTo(1);
    assertThat(call(() -> repository().delete(user, tenantA))).isZero();
    assertThat(call(() -> repository().findInTenant(user, tenantA))).isEmpty();
    assertThat(call(() -> repository().findInTenant(user, tenantB))).isPresent();
  }

  /** 주어진 id 앞에 없는 id 1199 개를 붙인 목록(IN 목록 1000 개 상한을 넘기려고). 중복도 하나 섞는다. */
  private static List<Long> manyIds(long... real) {
    List<Long> ids = new ArrayList<>();
    for (long i = 1; i <= 1199; i++) {
      ids.add(-i);
    }
    for (long r : real) {
      ids.add(r);
    }
    ids.add(real[0]);
    return ids;
  }
}
