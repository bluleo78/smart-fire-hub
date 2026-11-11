package com.smartfirehub.platform.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.user.repository.AccessContractBase;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PlatformRoleRepository 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-706).
 *
 * <p>인증 필터가 운영자 토큰을 검증하는 경로라 모든 호출을 테넌트 컨텍스트 없이 한다(리뷰 포인트 1). 플랫폼 RBAC 테이블은 정책 없는 전역 테이블이라 Tibero
 * 런타임 계정도 컨텍스트 없이 같은 결과를 내야 한다. 인터페이스에 쓰기가 없어 행은 픽스처가 만든다.
 */
public abstract class PlatformRoleRepositoryContractTest extends AccessContractBase {

  protected abstract PlatformRoleRepository repository();

  private String codeA;
  private String codeB;
  private String codeC;
  private long operator;
  private long roleWithoutPermissions;
  private long nobody;

  /**
   * 역할 r1(A·B)·r2(B·C)·r3(권한 없음). operator 는 r1·r2, roleWithoutPermissions 는 r3, nobody 는 플랫폼 롤 없음.
   */
  @BeforeEach
  void setUp() {
    codeA = prefix + ":a";
    codeB = prefix + ":b";
    codeC = prefix + ":c";
    long pA = fx.createPermission(codeA, "platform", "a");
    long pB = fx.createPermission(codeB, "platform", "b");
    long pC = fx.createPermission(codeC, "platform", "c");
    long r1 = fx.createPlatformRole(prefix + "-r1");
    long r2 = fx.createPlatformRole(prefix + "-r2");
    long r3 = fx.createPlatformRole(prefix + "-r3");
    fx.grantPlatformPermission(r1, pA);
    fx.grantPlatformPermission(r1, pB);
    fx.grantPlatformPermission(r2, pB);
    fx.grantPlatformPermission(r2, pC);

    operator = fx.createUser(prefix + "-op", null);
    roleWithoutPermissions = fx.createUser(prefix + "-r3", null);
    nobody = fx.createUser(prefix + "-no", null);
    fx.grantPlatformRole(operator, r1);
    fx.grantPlatformRole(operator, r2);
    fx.grantPlatformRole(roleWithoutPermissions, r3);
  }

  private <T> T call(Supplier<T> c) {
    return fx.noTenant(c);
  }

  @Test
  @DisplayName("플랫폼 권한 코드는 모든 플랫폼 롤의 권한을 중복 없이 모은다 — 테넌트 컨텍스트 없이")
  void findPlatformPermissionCodes_unionDistinct_withoutTenant() {
    assertThat(call(() -> repository().findPlatformPermissionCodes(operator)))
        .containsExactlyInAnyOrder(codeA, codeB, codeC);
  }

  @Test
  @DisplayName("플랫폼 롤이 없거나 권한 없는 롤뿐이면 예외가 아니라 빈 집합(fail-closed)")
  void findPlatformPermissionCodes_emptyWhenNoRoleOrNoPermission() {
    assertThat(call(() -> repository().findPlatformPermissionCodes(nobody))).isEmpty();
    assertThat(call(() -> repository().findPlatformPermissionCodes(roleWithoutPermissions)))
        .isEmpty();
    assertThat(call(() -> repository().findPlatformPermissionCodes(-1L))).isEmpty();
  }

  @Test
  @DisplayName("hasAnyPlatformRole 은 권한 유무와 무관하게 롤 보유만 본다")
  void hasAnyPlatformRole() {
    assertThat(call(() -> repository().hasAnyPlatformRole(operator))).isTrue();
    assertThat(call(() -> repository().hasAnyPlatformRole(roleWithoutPermissions))).isTrue();
    assertThat(call(() -> repository().hasAnyPlatformRole(nobody))).isFalse();
    assertThat(call(() -> repository().hasAnyPlatformRole(-1L))).isFalse();
  }
}
