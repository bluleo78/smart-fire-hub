package com.smartfirehub.tenant.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.user.repository.AccessContractBase;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.IllegalTransactionStateException;

/**
 * TenantRepository 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-706).
 *
 * <p>tenant 는 전역 테이블이라 조회는 테넌트 컨텍스트 없이(배경 순회·예약 발화) 불린다(리뷰 포인트 1). 공유 DB 에는 다른 실행의 테넌트가 섞여 있으므로 목록은
 * 정확한 일치 대신 포함·제외·오름차순만 본다. 기본값 시드는 원본 테넌트(1)의 시스템 역할·권한을 기준으로 비교한다 — DB 마다 시드된 시스템 역할 수가 다를 수 있어
 * 이름을 박지 않는다. 이 인터페이스에는 tenant 를 쓰는 메서드가 없어 중복 슬러그(리뷰 포인트 3)는 여기서 다루지 않는다 (PlatformTenantRepository
 * 몫).
 */
public abstract class TenantRepositoryContractTest extends AccessContractBase {

  protected abstract TenantRepository repository();

  private long active;
  private long suspended;

  @BeforeEach
  void createTenants() {
    active = fx.createTenant();
    suspended = fx.createTenant();
    fx.setTenantStatus(suspended, "SUSPENDED");
  }

  @Test
  @DisplayName("ACTIVE 테넌트 id 를 오름차순으로 돌려주고 정지 테넌트는 뺀다 — 테넌트 컨텍스트 없이")
  void findActiveTenantIds_withoutTenant_ascendingActiveOnly() {
    List<Long> ids = fx.noTenant(() -> repository().findActiveTenantIds());

    assertThat(ids).contains(active).doesNotContain(suspended).isSorted();
  }

  @Test
  @DisplayName("isActive 는 ACTIVE 만 true, 정지·없는 테넌트는 false — 테넌트 컨텍스트 없이")
  void isActive_withoutTenant() {
    assertThat(fx.noTenant(() -> repository().isActive(active))).isTrue();
    assertThat(fx.noTenant(() -> repository().isActive(suspended))).isFalse();
    assertThat(fx.noTenant(() -> repository().isActive(-1L))).isFalse();
  }

  @Test
  @DisplayName("기본값 시드는 원본 테넌트의 시스템 역할·권한을 복제하고 기본 등급·분류·OWNER 의 ADMIN 을 만들며, 다시 불러도 늘지 않는다")
  void provisionDefaults_copiesSourceSystemRoles_idempotent() {
    // OWNER 멤버는 시드가 그 테넌트의 ADMIN 을 배정한다(V121, Tibero V156.1). MEMBER 는 아무 역할도 받지 않는다.
    long owner = fx.createUser(prefix + "-owner", null);
    fx.createMembership(owner, active, "ACTIVE");
    fx.setMembershipRole(owner, active, "OWNER");
    long member = fx.createUser(prefix + "-member", null);
    fx.createMembership(member, active, "ACTIVE");

    fx.inTenant(
        active,
        () -> {
          repository().provisionDefaults(active);
          return null;
        });

    List<String> sourceRoles = fx.systemRoleNames(1L);
    assertThat(sourceRoles).contains("ADMIN", "USER");
    assertThat(fx.systemRoleNames(active)).isEqualTo(sourceRoles);
    for (String role : sourceRoles) {
      assertThat(fx.permissionIdsOfRole(active, fx.roleIdByName(active, role)))
          .as("역할 %s 의 권한", role)
          .isEqualTo(fx.permissionIdsOfRole(1L, fx.roleIdByName(1L, role)));
    }
    assertThat(fx.permissionIdsOfRole(active, fx.roleIdByName(active, "ADMIN"))).isNotEmpty();
    // 기본 4등급(createTenant 가 먼저 심은 기본 등급 1개 + 나머지 3개), 기본 분류 3개.
    assertThat(fx.countTenantRows("security_level", active)).isEqualTo(4);
    assertThat(fx.countTenantRows("dataset_category", active)).isEqualTo(3);
    assertThat(fx.countUserRoles(active, owner)).isEqualTo(1);
    assertThat(fx.countUserRoles(active, member)).isZero();

    Map<String, Integer> before = counts(active);
    fx.inTenant(
        active,
        () -> {
          repository().provisionDefaults(active);
          return null;
        });
    assertThat(counts(active)).isEqualTo(before);
    // OWNER→ADMIN 배정도 한 번뿐이다(다시 불러도 늘지 않는다).
    assertThat(fx.countUserRoles(active, owner)).isEqualTo(1);
    assertThat(fx.countUserRoles(active, member)).isZero();
  }

  @Test
  @DisplayName("기본값 시드는 트랜잭션 밖에서 부르면 스스로 열지 않고 거부한다(MANDATORY)")
  void provisionDefaults_outsideTransaction_rejected() {
    assertThatThrownBy(
            () ->
                fx.noTenant(
                    () -> {
                      repository().provisionDefaults(active);
                      return null;
                    }))
        .isInstanceOf(IllegalTransactionStateException.class);
    assertThat(fx.countTenantRows("role", active)).isZero();
  }

  /** 시드가 만지는 테이블별 행 수. */
  private Map<String, Integer> counts(long tenantId) {
    return List.of(
            "role", "role_permission", "security_level", "dataset_category", "report_template")
        .stream()
        .collect(Collectors.toMap(Function.identity(), t -> fx.countTenantRows(t, tenantId)));
  }
}
