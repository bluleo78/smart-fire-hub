package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.role.repository.RoleRepository;
import com.smartfirehub.support.TenantRlsTestSupport;
import com.smartfirehub.user.repository.AccessContractBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code RoleRepository.findByName}/{@code existsByName} 에는 tenant_id 술어가 없다 — 이름만으로 조회한다. V99 이전에는
 * 여러 테넌트가 같은 이름(예: ADMIN)의 역할을 갖는 순간 findByName 이 {@code TooManyRowsException} 을 던졌다.
 *
 * <p>이 테스트는 V99 정책이 그 문제를 해소한다는 것을 고정한다. 술어를 추가해서가 아니라 <b>RLS 가 행을 걸러서</b> 해소된다 — 즉 이 두 쿼리는 <b>테넌트
 * 컨텍스트가 있는 트랜잭션 안에서 불릴 때만</b> 옳다. 실제 호출처(SignupTransaction, RoleService.createRole)는 둘 다
 * {@code @Transactional} 이라 조건을 만족한다. 그 전제가 깨지는 회귀(누군가 트랜잭션 밖에서 부르는 경우)를 여기서 잡는다.
 *
 * <p>인터페이스 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-485). 픽스처는 {@code AccessFixtures} seam 이라 PG·Tibero 모두 같은
 * 시나리오를 돈다(WD-706).
 */
abstract class RoleRepositoryTenantScopeContractTest extends AccessContractBase {

  /** 계약 대상 리포지토리 — 구현별 하위 클래스가 제공한다. */
  protected abstract RoleRepository repository();

  private long tenantA;
  private long tenantB;
  private String sharedName;

  @BeforeEach
  void setUp() {
    tenantA = fx.createTenant();
    tenantB = fx.createTenant();
    // 두 테넌트가 같은 이름의 역할을 갖게 만든다 — 술어 없는 조회가 두 행을 만나는 상황의 재현이다.
    sharedName = "역할중복-" + TenantRlsTestSupport.nextTenantId();
    fx.createRole(tenantA, sharedName, "d");
    fx.createRole(tenantB, sharedName, "d");
  }

  @Test
  @DisplayName("여러 테넌트가 같은 이름을 가져도 findByName 은 자기 테넌트의 한 행만 돌려준다")
  void findByNameReturnsExactlyOwnTenantRow() {
    var inA = fx.inTenant(tenantA, () -> repository().findByName(sharedName));
    var inB = fx.inTenant(tenantB, () -> repository().findByName(sharedName));

    // 예외 없이 값이 나오는 것 자체가 단언이다 — 술어 없는 쿼리를 RLS 가 한 행으로 좁혔다는 뜻이다.
    assertThat(inA).isPresent();
    assertThat(inB).isPresent();
    assertThat(inA.get().id())
        .as("각 테넌트는 자기 사본을 봐야 한다 — 같은 행이 나오면 격리가 안 된 것이다")
        .isNotEqualTo(inB.get().id());
  }

  @Test
  @DisplayName("시스템 역할 ADMIN 도 테넌트 컨텍스트 안에서는 한 행으로 좁혀진다")
  void findByNameOnSystemRoleDoesNotThrow() {
    // 공유 테스트 DB 에는 프로비저닝된 여러 테넌트의 ADMIN 이 실제로 존재한다. 기본 테넌트
    // 컨텍스트에서 부르면 그중 하나만 보여야 한다(V99 이전엔 여기서 TooManyRowsException 이었다).
    var admin = fx.inTenant(DEFAULT_TEST_TENANT_ID, () -> repository().findByName("ADMIN"));

    assertThat(admin).isPresent();
  }

  @Test
  @DisplayName("ADMIN 이름을 여러 테넌트가 가져도 자기 테넌트의 한 행으로 좁혀진다")
  void findByNameOnSameSystemNameInTwoTenantsReturnsOwnRow() {
    long adminA = fx.createRole(tenantA, "ADMIN", "관리자");
    long adminB = fx.createRole(tenantB, "ADMIN", "관리자");

    var inA = fx.inTenant(tenantA, () -> repository().findByName("ADMIN"));
    var inB = fx.inTenant(tenantB, () -> repository().findByName("ADMIN"));

    assertThat(inA).get().extracting(r -> r.id()).isEqualTo(adminA);
    assertThat(inB).get().extracting(r -> r.id()).isEqualTo(adminB);
  }

  @Test
  @DisplayName("existsByName 은 다른 테넌트에만 있는 이름을 존재하지 않는 것으로 본다")
  void existsByNameIsTenantScoped() {
    String onlyInB = "B전용역할-" + TenantRlsTestSupport.nextTenantId();
    fx.createRole(tenantB, onlyInB, "d");

    Boolean seenFromB = fx.inTenant(tenantB, () -> repository().existsByName(onlyInB));
    Boolean seenFromA = fx.inTenant(tenantA, () -> repository().existsByName(onlyInB));

    assertThat(seenFromB).as("자기 테넌트의 이름은 존재로 보여야 한다").isTrue();
    assertThat(seenFromA).as("남의 테넌트 이름 때문에 중복으로 판정되면 테넌트가 서로의 역할 이름을 막게 된다").isFalse();
  }
}
