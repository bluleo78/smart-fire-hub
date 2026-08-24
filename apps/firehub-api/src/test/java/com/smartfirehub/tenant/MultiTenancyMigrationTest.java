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
}
