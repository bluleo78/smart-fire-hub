package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.support.IntegrationTestBase;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** V81/V82 마이그레이션이 만든 전역 테이블·시드·백필이 실제로 존재하는지 검증한다. */
class MultiTenancyMigrationTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;

  @Test
  @DisplayName("기본 테넌트 1번이 slug 'default' 로 시드된다")
  void defaultTenantSeeded() {
    String slug =
        dsl.select(field(name("tenant", "slug"), String.class))
            .from(table(name("tenant")))
            .where(field(name("tenant", "id"), Long.class).eq(1L))
            .fetchOne(0, String.class);

    assertThat(slug).isEqualTo("default");
  }

  @Test
  @DisplayName("V81 적용 이전에 존재한 사용자는 전원 테넌트 1번의 ACTIVE 멤버가 된다")
  void usersExistingBeforeMigrationAreBackfilled() {
    // 공유 테스트 DB 는 다른 세션이 동시에 사용하므로 라이브 전체 카운트 비교는 구조적으로 불안정하다.
    // V81 이 실제로 보장하는 것만 단언한다 — 마이그레이션 적용 시점 이전 사용자의 멤버십.
    Integer notBackfilled =
        dsl.fetchOne(
                """
                select count(*) from "user" u
                where u.created_at < (
                        select installed_on from flyway_schema_history where version = '81')
                  and not exists (
                        select 1 from membership m
                        where m.user_id = u.id and m.tenant_id = 1 and m.status = 'ACTIVE')
                """)
            .get(0, Integer.class);

    assertThat(notBackfilled).isZero();
  }

  @Test
  @DisplayName("백필 단언이 공허하지 않도록 — V81 이전 사용자가 실제로 존재한다 (없으면 스킵)")
  void preMigrationUsersExist() {
    // 어떤 마이그레이션도 "user" 테이블을 시드하지 않는다. 신선한 DB(CI, 새 머신)에서는
    // 모든 마이그레이션이 한 번에 일괄 적용되어 V81 설치 시점보다 먼저 존재하는 사용자가
    // 원천적으로 하나도 없다 — 이 경우 위 usersExistingBeforeMigrationAreBackfilled 의 단언은
    // "공허하게" 참이 되는 것이 맞고, 결함이 아니다. 그래서 이 테스트는 단언이 아니라
    // assumption 이다: 사전 사용자가 없으면 검증할 대상이 없으므로 스킵하고,
    // 사전 사용자가 존재하는 DB(예: 기존 운영 데이터를 복제한 로컬 DB)에서만 실제로 검증한다.
    Integer preMigrationUsers =
        dsl.fetchOne(
                """
                select count(*) from "user" u
                where u.created_at < (
                        select installed_on from flyway_schema_history where version = '81')
                """)
            .get(0, Integer.class);

    Assumptions.assumeTrue(
        preMigrationUsers != null && preMigrationUsers > 0,
        "신선한 DB에는 V81 이전 사용자가 없다 — 백필 검증을 건너뜀 (결함 아님)");
  }

  @Test
  @DisplayName("한 사용자가 같은 테넌트에 두 번 소속될 수 없다")
  void membershipIsUniquePerUserAndTenant() {
    boolean hasUniqueConstraint =
        dsl.fetchExists(
            dsl.selectOne()
                .from(table(name("pg_constraint")))
                .where(field(name("conname"), String.class).eq("membership_unique")));

    assertThat(hasUniqueConstraint).isTrue();
  }

  @Test
  @DisplayName("SUPER_ADMIN 플랫폼 롤이 시스템 롤로 시드된다")
  void superAdminPlatformRoleSeeded() {
    Boolean isSystem =
        dsl.select(field(name("platform_role", "is_system"), Boolean.class))
            .from(table(name("platform_role")))
            .where(field(name("platform_role", "name"), String.class).eq("SUPER_ADMIN"))
            .fetchOne(0, Boolean.class);

    assertThat(isSystem).isTrue();
  }

  @Test
  @DisplayName("ADMIN 롤 보유자 중 SUPER_ADMIN 승격이 누락된 사람은 없다")
  void noAdminHolderMissesSuperAdmin() {
    // 현재 테스트 DB 에는 user_role 이 0행이라 이 단언은 공허하게 통과한다. 그래도 남겨 두는 이유는
    // ADMIN 이 하나라도 생기는 순간 승격 누락을 잡는 불변식이기 때문이다(아래 테스트가 비공허 검증을 담당).
    Integer missing =
        dsl.fetchOne(
                """
                select count(*) from (
                  select distinct ur.user_id from user_role ur
                  join role r on r.id = ur.role_id
                  where r.name = 'ADMIN'
                ) admins
                where not exists (
                  select 1 from platform_user_role pur
                  join platform_role pr on pr.id = pur.platform_role_id
                  where pur.user_id = admins.user_id and pr.name = 'SUPER_ADMIN')
                """)
            .get(0, Integer.class);

    assertThat(missing).isZero();
  }

  @Test
  @DisplayName("SUPER_ADMIN 은 platform 카테고리 권한을 전부 보유한다")
  void superAdminHoldsAllPlatformPermissions() {
    Integer platformPermissions =
        dsl.fetchOne("select count(*) from permission where category = 'platform'")
            .get(0, Integer.class);
    Integer grantedToSuperAdmin =
        dsl.fetchOne(
                """
                select count(*) from platform_role_permission prp
                join platform_role pr on pr.id = prp.platform_role_id
                join permission p on p.id = prp.permission_id
                where pr.name = 'SUPER_ADMIN' and p.category = 'platform'
                """)
            .get(0, Integer.class);

    // V82 가 4건(tenant:create/read/suspend, member:read), V113 이 2건
    // (settings:read/write)을 추가해 6건이다. 이 숫자를 고정해 두는 이유는 카탈로그에 권한을
    // 추가하면서 SUPER_ADMIN 연결을 빼먹는 실수를 잡기 위함이다 — 아래 단언이 그 짝이다.
    assertThat(platformPermissions).isEqualTo(6);
    assertThat(grantedToSuperAdmin).isEqualTo(platformPermissions);
  }

  @Test
  @DisplayName("플랫폼 롤은 SUPER_ADMIN 하나뿐이다 — 두 번째 롤이 생기면 이 단언이 터진다")
  void onlyOnePlatformRoleExists() {
    // 이 단언은 PlatformUserController(platform:member:read 재사용 판단의 근거)와
    // 정확히 짝을 이루는 트립와이어다. 그 컨트롤러 javadoc 은 "platform_role 이 SUPER_ADMIN
    // 하나뿐이라 그 롤이 category='platform' 권한을 이미 전부 가지므로 platform:member:read
    // 를 재사용해도 오늘은 아무것도 좁혀지지 않는다"고 근거를 대는데, 그 전제("하나뿐")를
    // 지금까지 어떤 테스트도 직접 단언하지 않았다 — 위 두 테스트는 SUPER_ADMIN 행의 속성만
    // 본다. 새 플랫폼 롤을 시드하는 마이그레이션이 들어오면 이 테스트가 실패해야 하고,
    // 그 실패는 "PlatformUserController 를 열어 platform:member:read 재사용이 아직
    // 유효한지 재검토하라 — 무효하면 새 롤 전용 권한(예: platform:user:read)을 신설하고
    // V113 패턴(코드 명시 + ON CONFLICT DO NOTHING)을 따르라"는 신호다. 이 단언 자체를
    // 지우거나 숫자만 올려서 통과시키는 것은 트립와이어를 해체하는 것이므로 금지 — 재검토
    // 후 컨트롤러의 권한 결정을 실제로 바꾼 뒤에만 이 값을 갱신한다.
    Integer platformRoleCount =
        dsl.fetchOne("select count(*) from platform_role").get(0, Integer.class);

    assertThat(platformRoleCount).isEqualTo(1);
  }

  @Test
  @DisplayName("고아 권한 settings:write 는 카탈로그에 존재하지 않는다 (V116)")
  void orphanSettingsWritePermissionIsGone() {
    // 왜 DB 를 직접 보는가: SettingsControllerTest 의 mockAuth("settings:write") 는
    // PermissionService 가 @MockitoBean 인 순수 스텁이라 permission 테이블을 전혀 조회하지
    // 않는다 — 행을 지워도 초록, 안 지워도 초록이라 삭제를 **증명하지 못한다**.
    //
    // 왜 지웠나: 이 코드를 요구하는 @RequirePermission 이 프로덕션에 하나도 없는데
    // (SettingsController 의 네 라우트는 전부 ai:settings 다), V42 가 전 테넌트의 ADMIN 롤에
    // 이 권한을 시드해 두었다. 누군가 나중에 @RequirePermission("settings:write") 를 한 줄
    // 붙이면 아무도 권한을 부여하지 않았는데 모든 테넌트 관리자에게 즉시 열린다 — 이름이
    // '시스템 설정 변경'이라 그 한 줄은 자연스러워 보인다.
    //
    // 부여 행(role_permission)은 여기서 세지 않는다. 그 테이블은 RLS 대상이 되어 테넌트를
    // 세우지 않은 이 커넥션에서는 조회가 조건과 무관하게 0행이 된다 — 세어 봐야 삭제를
    // 증명하지 않는 공허한 단언이 하나 늘 뿐이다. cascade 는 FK 정의
    // (role_permission.permission_id / platform_role_permission.permission_id 가 둘 다
    // ON DELETE CASCADE)가 DB 수준에서 보장한다.
    assertThat(permissionCount("settings:write")).isZero();

    // 양성 대조군 — 같은 모양의 조회가 실재하는 권한은 실제로 찾아낸다. 오타난 컬럼·테이블은
    // 대조군 없이도 예외로 터지니 여기서 막는 것은 그게 아니다. 이 커넥션은 app_tenant 롤로
    // 흐르는데(application-test.yml), 만약 permission 이 role_permission 처럼 RLS 대상이라면
    // 테넌트를 세우지 않은 이 커넥션에서는 **모든** count 가 조건과 무관하게 0 이 되어 위
    // isZero() 는 V116 을 돌렸든 안 돌렸든 통과한다. 대조군이 1 을 반환한다는 것이 "이 조회는
    // 실제로 행을 볼 수 있다"의 증거이고, 그래서 위 0 은 "안 보인다"가 아니라 "없다"를 뜻한다.
    assertThat(permissionCount("ai:settings")).isEqualTo(1);

    // 플랫폼 평면의 'platform:settings:write' 는 전혀 다른 권한이며 살아 있어야 한다
    // (V113 이 시드했고 PlatformSettingsController 가 실제로 요구한다). 프리픽스만 다른
    // 두 코드를 섞어 지우는 사고를 여기서 막는다.
    assertThat(permissionCount("platform:settings:write")).isEqualTo(1);
  }

  /**
   * 권한 카탈로그에서 코드 하나의 행 수를 센다.
   *
   * <p>바인드 파라미터를 쓴다 — 위 세 호출은 전부 리터럴이지만, 문자열을 이어 붙이는 형태로
   * 두면 다음 사람이 변수를 넣는 순간 이 테스트가 SQL 조립 예제가 된다.
   */
  private int permissionCount(String code) {
    return dsl.fetchOne("select count(*) from permission where code = ?", code).get(0, Integer.class);
  }
}
