package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.tenant.TenantProvisioningService;
import com.smartfirehub.platform.dto.CreateTenantRequest;
import com.smartfirehub.platform.service.PlatformTenantService;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.ArrayList;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 프로비저닝이 소유자에게 ADMIN 역할을 <b>배정</b>하는지 검증한다(V121).
 *
 * <p><b>왜 필요한가.</b> V98 의 프로비저닝은 역할·권한을 복제(정의)만 하고 아무에게도 배정하지
 * 않았다. {@code membership.role='OWNER'} 는 표시용 라벨이라 인가에 쓰이지 않으므로, 워크스페이스
 * 소유자조차 {@code user_role} 0행 → {@code isAdmin=false} 가 되어 관리 메뉴(사용자·역할·감사
 * 로그·설정)가 통째로 사라졌다. 역할 배정 화면 자체가 ADMIN 전용이라 자력 복구도 불가능했다.
 *
 * <p>클래스 레벨 {@code @Transactional} 을 쓰지 않는다 — 생성은 자기 트랜잭션에서 커밋돼야 하고,
 * 검증 조회는 그 밖에서 GUC 를 세워 RLS(V99)를 실제로 통과해야 한다. 검증 대상인 프로덕션 호출은
 * {@link IntegrationTestBase#inTenantFixture} 블록 <b>밖</b>에 둔다(그 헬퍼의 경계 규칙).
 */
class TenantOwnerAdminProvisioningTest extends IntegrationTestBase {

  @Autowired private PlatformTenantService platformTenantService;
  @Autowired private TenantProvisioningService tenantProvisioningService;
  @Autowired private DSLContext dsl;

  private Long createdTenant;
  private final List<Long> createdUsers = new ArrayList<>();

  /** 공유 test DB 라 자기가 만든 것만 지운다. */
  @AfterEach
  void cleanUp() {
    if (createdTenant != null) {
      TenantRlsTestSupport.deleteProvisionedTenantCascade(
          dsl, fixtureTransactionTemplate, createdTenant);
      createdTenant = null;
    }
    // 사용자는 테넌트 밖 전역 테이블이라 위 정리에 걸리지 않는다 — 자식 행이 사라진 뒤에 지운다.
    createdUsers.forEach(userId -> TenantRlsTestSupport.deleteUser(dsl, userId));
    createdUsers.clear();
  }

  /** 앱 경로: 테넌트를 만들면 소유자가 그 테넌트의 ADMIN 역할을 갖는다. */
  @Test
  void create_assignsAdminRoleToOwner() {
    Long ownerId = createUser();

    createdTenant = createTenantViaService(ownerId);

    assertThat(roleNamesOf(createdTenant, ownerId))
        .as("소유자에게 ADMIN 이 배정돼야 관리 메뉴에 들어갈 수 있다")
        .containsExactly("ADMIN");
  }

  /**
   * 운영자 SQL 경로(런북 §1)도 소유자를 ADMIN 으로 만든다.
   *
   * <p>테넌트 생성의 정본은 아직 운영자가 직접 실행하는 SQL 이고, 그 절차가 부르는 것은
   * {@code provision_tenant_defaults} 하나뿐이다. 배정을 앱 코드에만 배선하면 운영자가 만든
   * 테넌트는 계속 권한 0개로 태어난다 — 그 회귀를 여기서 고정한다.
   *
   * <p>두 번 부르는 것은 멱등 검증을 겸한다 — 운영자가 안심하고 다시 부를 수 있어야 하고,
   * {@code containsExactly} 가 중복 배정을 잡는다.
   */
  @Test
  void provisionDefaults_assignsAdminToExistingOwnerIdempotently() {
    Long ownerId = createUser();
    createdTenant = createTenantRowWithMember(ownerId, "OWNER");

    tenantProvisioningService.provisionDefaults(createdTenant);
    tenantProvisioningService.provisionDefaults(createdTenant);

    assertThat(roleNamesOf(createdTenant, ownerId))
        .as("운영자 경로도 한 번의 호출로 배정까지 끝나야 하고, 재실행이 중복을 만들면 안 된다")
        .containsExactly("ADMIN");
  }

  /**
   * 소유자가 아닌 멤버는 ADMIN 을 받지 않는다.
   *
   * <p>배정 대상은 {@code membership.role='OWNER'} 한 종류뿐이다 — 이 조건이 느슨해지면 일반
   * 멤버가 전원 관리자가 되므로, 라벨이 다른 멤버가 섞인 상태에서 고정한다.
   */
  @Test
  void provisionDefaults_assignsNothingToPlainMember() {
    Long ownerId = createUser();
    Long memberId = createUser();
    createdTenant = createTenantRowWithMember(ownerId, "OWNER");
    TenantRlsTestSupport.insertActiveMembership(dsl, memberId, createdTenant, "MEMBER");

    tenantProvisioningService.provisionDefaults(createdTenant);

    assertThat(roleNamesOf(createdTenant, memberId))
        .as("OWNER 가 아닌 멤버는 이 경로로 권한을 얻지 않는다")
        .isEmpty();
  }

  // ── 헬퍼 ────────────────────────────────────────────────────────────────────

  /** 앱 경로(운영자 평면 API 가 쓰는 서비스). 멤버십 삽입·시드·배정이 한 트랜잭션에서 끝난다. */
  private long createTenantViaService(Long ownerId) {
    String slug = "v121-app-" + System.nanoTime();
    return platformTenantService.create(new CreateTenantRequest(slug, slug, ownerId)).id();
  }

  /**
   * 런북 §1-1 + 멤버십까지만 손으로 만든다(프로비저닝은 부르지 않는다) — 운영자 경로의 출발
   * 상태를 그대로 재현하기 위해서다. {@code tenant}/{@code membership} 은 테넌트 경계 위의 전역
   * 테이블이라 컨텍스트 없이 쓴다.
   */
  private long createTenantRowWithMember(Long userId, String role) {
    long tenantId = TenantRlsTestSupport.createActiveTenant(dsl, "v121-ops");
    TenantRlsTestSupport.insertActiveMembership(dsl, userId, tenantId, role);
    return tenantId;
  }

  /** 검증용 사용자. 이 테스트는 로그인하지 않으므로 비밀번호 지정 버전이 필요 없다. */
  private Long createUser() {
    Long userId = TenantRlsTestSupport.insertUser(dsl, "v121-owner");
    createdUsers.add(userId);
    return userId;
  }

  /** 대상 테넌트 컨텍스트에서 그 사용자의 역할 이름을 읽는다(RLS 를 실제로 통과시킨다). */
  private List<String> roleNamesOf(long tenantId, Long userId) {
    return inTenantFixture(
        tenantId,
        () ->
            dsl.fetch(
                    """
                    select r.name
                    from user_role ur
                    join role r on r.id = ur.role_id
                    where ur.user_id = ? and ur.tenant_id = ?
                    order by r.name
                    """,
                    userId,
                    tenantId)
                .into(String.class));
  }
}
