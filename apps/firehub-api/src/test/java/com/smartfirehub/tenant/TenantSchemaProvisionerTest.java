package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.field;

import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.MissingTenantScopeException;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantPipelineRole;
import com.smartfirehub.global.tenant.TenantSchemaProvisioner;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * {@link TenantSchemaProvisioner} 의 지연 생성·멱등성·권한 부여·자가치유를 검증한다.
 *
 * <p><b>테넌트 id 는 프로세스마다 무작위 기저(라운드 1 리뷰 R13) + 클래스 내 고정 오프셋을
 * 쓴다.</b> 고정 리터럴을 썼던 초판은 동시에 도는 다른 워크트리와 같은 스키마·롤 이름을 노려
 * 교차 실패가 재현됐다({@link TenantRlsTestSupport#randomSchemaProvisioningTenantIdBase()}
 * 참조). 이 대역이라야 {@code data_t{id}} 가 파생되어 삭제 가드
 * ({@link TenantRlsTestSupport#dropSchemasCreatedByThisTest})를 통과한다. 테넌트 1 로
 * 프로비저너를 시험하면 {@code data} 자체를 건드리게 되므로 절대 쓰지 않는다.
 *
 * <p><b>역할 생성/정리·독립 정리 규율은 {@link TenantRlsTestSupport} 의 헬퍼로 승격돼 있다</b>
 * (라운드 3 리뷰 N8) — {@code ensureRoleExists}/{@code dropRoleIfCreatedByThisTest}/
 * {@code cleanupAll}. 이 클래스는 그 헬퍼를 그대로 쓴다. Task 5 도 새 헬퍼를 만들지 말고 이걸
 * 재사용한다.
 *
 * <p><b>정리를 {@code finally} 가 아니라 {@code @AfterEach} 로 하는 이유(최종 전체 리뷰 R24 —
 * {@code DataTableServiceTenantUniqueTest} 에 적용한 것과 같은 수정을 이 파일에도 적용).</b>
 * plain try/finally 는 {@code finally} 가 예외를 던지면 {@code try} 가 던진 원래 예외(있었다면)
 * 를 완전히 대체한다(억제 연결 없음). {@link #grantsRequiredPrivileges()} 가 검증하는
 * {@code REVOKE ALL ON SCHEMA public}(B4) 가 회귀하면, 스트레이 ACL 항목이 롤에 남은 채
 * 정리 단계의 {@code DROP ROLE} 이 "일부 객체가 이 롤에 의존한다"는 이유로 먼저 실패해
 * JUnit 이 원래 {@code AssertionError}(스트레이 항목이 안 지워졌다) 대신 "정리 실패"만
 * 보고한다 — 이건 변이 아티팩트가 아니라 실제 회귀에서도 그대로 재현되는 마스킹이고, 그
 * 가드가 지키는 것이 하필 "신규 테넌트만 조용히 빠지는 비대칭"이라 신호가 가려지면 그
 * 비대칭이 조용히 돌아온다. {@code @AfterEach} 는 JUnit 5 가 정리 실패를 <b>테스트 자신의
 * 실패를 주 예외로 유지한 채</b> suppressed 로 붙이므로 이 위험이 구조적으로 사라진다.
 */
class TenantSchemaProvisionerTest extends IntegrationTestBase {

  /** 이 클래스의 모든 테스트가 공유하는 무작위 기저. 테스트마다 +1, +2, ... 오프셋만 더한다. */
  private static final long TENANT_BASE =
      TenantRlsTestSupport.randomSchemaProvisioningTenantIdBase();

  @Autowired private TenantSchemaProvisioner provisioner;
  @Autowired private DSLContext dsl;

  @Autowired
  @Qualifier("schemaOwnerDataSource")
  private DataSource schemaOwnerDataSource;

  // 테스트 메서드가 채우고 @AfterEach 가 읽는 픽스처 상태. JUnit 5 기본 생명주기(PER_METHOD)라
  // 테스트마다 새 인스턴스가 만들어지므로 필드가 다음 테스트로 새지 않는다. null/false 초기값은
  // "아직 이 자원을 안 만들었다/이 테스트가 만든 게 아니다"를 뜻하고, @AfterEach 가 null 을
  // 건너뛴다 — 픽스처 생성 극초반에 실패해도 cleanup 이 잘못된 인자로 헬퍼를 불러 NPE 로 더
  // 시끄러워지지 않게 한다.
  private Long tenantId;
  private String schema;
  private String executorRole;
  private boolean roleCreatedByThisTest;

  // 프로비저닝 뒤에 남겨 두는 스트레이 public grant 를 정리해야 하는지
  // (skipsProvisioningOnceDefaultPrivilegesAreComplete 전용). 이 grant 가 남아 있으면
  // PostgreSQL 이 DROP ROLE 을 거부하므로("privileges for schema public" 의존성) 롤 삭제
  // **전에** 회수해야 한다.
  private boolean strayPublicGrantNeedsRevoke;

  /** DROP SCHEMA 는 소유자(app)만 할 수 있다 — 런타임 롤(app_tenant)은 스키마 소유자가 아니다. */
  private DSLContext ownerDsl() {
    return DSL.using(schemaOwnerDataSource, SQLDialect.POSTGRES);
  }

  @AfterEach
  void cleanupFixture() {
    TenantRlsTestSupport.cleanupAll(
        () -> {
          if (schema != null) {
            TenantRlsTestSupport.dropSchemasCreatedByThisTest(ownerDsl(), schema);
          }
        },
        () -> {
          if (executorRole != null && strayPublicGrantNeedsRevoke) {
            ownerDsl().execute("REVOKE ALL ON SCHEMA public FROM " + executorRole);
          }
        },
        () -> {
          if (executorRole != null) {
            TenantRlsTestSupport.dropRoleIfCreatedByThisTest(
                ownerDsl(), executorRole, roleCreatedByThisTest);
          }
        },
        () -> {
          if (tenantId != null) {
            TenantRlsTestSupport.deleteTenants(dsl, tenantId);
          }
        });
  }

  /** 스키마가 없으면 만든다 — 그리고 자기가 만든 것만 지운다. */
  @Test
  void createsSchemaWhenAbsent() {
    tenantId = TENANT_BASE + 1;
    TenantRlsTestSupport.insertActiveTenant(dsl, tenantId);
    schema = TenantContext.runScopedGet(tenantId, DataSchema::current); // → "data_t{id}"
    provision(tenantId);
    assertThat(schemaExists(schema)).isTrue();
  }

  /** 두 번 불러도 안전하다(멱등). */
  @Test
  void isIdempotent() {
    tenantId = TENANT_BASE + 2;
    TenantRlsTestSupport.insertActiveTenant(dsl, tenantId);
    schema = TenantContext.runScopedGet(tenantId, DataSchema::current);
    provision(tenantId);
    provision(tenantId); // 두 번째 호출도 예외 없이 끝나야 한다
    assertThat(schemaExists(schema)).isTrue();
  }

  /**
   * 권한 세트가 실제로 걸린다 — 이 밴드에서 조용히 실패할 수 있는 유일한 지점이다.
   *
   * <p>왜 pg_default_acl 을 보는가: GRANT ... ON ALL TABLES 는 갓 만든 빈 스키마에서 no-op 이다.
   * 신규 테넌트의 파이프라인이 <b>나중에 만들어질</b> 테이블을 볼 수 있게 하는 것은 오직
   * ALTER DEFAULT PRIVILEGES 항목뿐이다. 이게 빠지면 신규 테넌트만 조용히 파이프라인이 안 돈다.
   *
   * <p><b>왜 {@code defaultAclExists} 가 {@code defaclrole}(FOR ROLE)과 {@code defaclobjtype}
   * 을 함께 확인하는가(라운드 1 리뷰 BLOCKER 2).</b> 스키마 하나만으로 조인하고 grantee 만 보면
   * {@code FOR ROLE app_tenant} → {@code FOR ROLE app} 변이가 초록으로 통과한다(실증: 두 값
   * 모두 같은 스키마에 같은 grantee 로 항목을 남기므로 스키마·grantee 만 보는 조회는 구별하지
   * 못한다). 그 변이의 실제 결과는 "신규 테이블이 executor 롤에게 조용히 안 보임" — 이 테스트가
   * 막으려던 바로 그 실패이므로, {@code defaclrole='app_tenant'} 와
   * {@code defaclobjtype in ('r','S')} 를 각각 확인해야 그 변이가 실제로 잡힌다.
   *
   * <p>이 테스트가 도는 test DB 는 executor 롤(랜덤 오프셋 기반)을 미리 만들어 두지 않는다
   * (신규 테넌트 롤 생성은 운영자 절차, #383) — 그래서 GRANT/ALTER DEFAULT PRIVILEGES 문장
   * 자체는 롤이 없으면 건너뛴다. 이 테스트는 스키마·런타임 롤(app_tenant) 권한만 검증하고,
   * executor 롤 대상 권한(defaultAclExists)은 롤을 직접 만들어 검증한다. 롤이 <b>나중에</b>
   * 생기는 경우의 자가치유는 {@link #selfHealsWhenExecutorRoleAppearsLater()} 가 별도로 다룬다.
   *
   * <p><b>{@code REVOKE ALL ON SCHEMA public FROM executorRole}(최종 전체 리뷰 B4) 검증이
   * {@code has_schema_privilege} 가 아니라 {@code aclexplode} 로 롤 전용 ACL 항목을 직접 보는
   * 이유.</b> 리뷰가 처음 제안한 검증은 {@code has_schema_privilege(executorRole, 'public',
   * 'USAGE')} 가 {@code false} 인지였는데, <b>실측해 보니 이 함수는 REVOKE 여부와 무관하게
   * 항상 {@code true} 다</b> — {@code public} 스키마의 {@code nspacl} 에 {@code PUBLIC} 의사
   * 롤 자체가 이미 {@code =U/pg_database_owner}(USAGE)를 갖고 있어서(PG15+ 기본값), 특정
   * 롤에서만 REVOKE 해도 PUBLIC 경유 권한이 남는다(직접 프로브로 재현: {@code CREATE ROLE} →
   * {@code REVOKE ALL ON SCHEMA public} → {@code has_schema_privilege(...,'USAGE')} 여전히
   * {@code true}). {@code DataSchemaGrantIsolationTest:225-231} 가 V32/V111 의 같은 REVOKE 에
   * 대해 이미 기록해 둔 바로 그 함정이다 — 제안된 단언을 그대로 넣으면 REVOKE 가 정확히
   * 실행돼도 실패하는 거짓 실패 테스트가 됐을 것이다. 그래서 이 REVOKE 문의 실제 관측 가능한
   * 효과(스트레이 롤 전용 GRANT 를 지운다)를 스트레이 상태를 직접 만들어 확인한다.
   */
  @Test
  void grantsRequiredPrivileges() {
    tenantId = TENANT_BASE + 3;
    TenantRlsTestSupport.insertActiveTenant(dsl, tenantId);
    schema = TenantContext.runScopedGet(tenantId, DataSchema::current);
    executorRole = TenantPipelineRole.roleName(tenantId);
    roleCreatedByThisTest = TenantRlsTestSupport.ensureRoleExists(ownerDsl(), executorRole);

    // 스트레이 grant 시뮬레이션 — 운영자가 실수로(또는 낡은 절차로) 이 롤에 public 스키마
    // 권한을 명시적으로 준 상태를 재현한다. provisioner 의 REVOKE 가 이 롤 전용 ACL 항목을
    // 지워야 한다.
    ownerDsl().execute("GRANT USAGE ON SCHEMA public TO " + executorRole);
    assertThat(publicSchemaHasRoleSpecificAclEntry(executorRole))
        .as("스트레이 grant 시뮬레이션이 실제로 롤 전용 ACL 항목을 남겼는지 확인")
        .isTrue();

    provision(tenantId);
    // 런타임 롤이 이 스키마 안에 데이터셋 테이블을 만드는 주체다.
    assertThat(hasSchemaPrivilege("app_tenant", schema, "CREATE")).isTrue();
    assertThat(hasSchemaPrivilege("app_tenant", schema, "USAGE")).isTrue();
    // app_tenant 가 만들 미래 테이블·시퀀스 각각에 대한 기본 권한 항목이 존재하는가.
    assertThat(defaultAclExists(schema, "app_tenant", executorRole, "r")).isTrue();
    assertThat(defaultAclExists(schema, "app_tenant", executorRole, "S")).isTrue();
    // B4 — REVOKE ALL ON SCHEMA public 이 실행돼 스트레이 롤 전용 항목이 사라졌는가.
    assertThat(publicSchemaHasRoleSpecificAclEntry(executorRole))
        .as("provisioner 의 REVOKE ALL ON SCHEMA public 이 롤 전용 ACL 항목을 지웠어야 한다")
        .isFalse();
  }

  /**
   * 자가치유(라운드 1 리뷰 BLOCKER 1) — executor 롤이 스키마 생성 <b>이후</b>에 생겨도, 다음
   * 프로비저닝 호출이 그 스키마에 다시 들어가 권한을 마저 건다.
   *
   * <p>왜 필요한가: 신규 테넌트의 롤 생성은 운영자 절차(#383)라 첫 데이터셋 생성 시점에는 아직
   * 없는 경우가 흔하다. 단순히 "스키마가 있으면 끝"으로 단락시키면, 롤이 나중에 생겨도 이
   * 메서드가 다시 들어오지 않아 그 테넌트의 파이프라인이 에러 없이 영구히 안 돈다 — 자가치유
   * 경로가 없으면 원인 추적조차 안 되는 조용한 실패다.
   */
  @Test
  void selfHealsWhenExecutorRoleAppearsLater() {
    tenantId = TENANT_BASE + 4;
    TenantRlsTestSupport.insertActiveTenant(dsl, tenantId);
    schema = TenantContext.runScopedGet(tenantId, DataSchema::current);
    executorRole = TenantPipelineRole.roleName(tenantId);

    // 1차 — 롤이 아직 없는 상태에서 프로비저닝한다. 스키마만 생기고 executor 대상 grant 는
    // 전부 건너뛴다(정상 동작 — 위 grantsRequiredPrivileges 의 전제와 같다).
    provision(tenantId);
    assertThat(schemaExists(schema)).isTrue();
    assertThat(roleExistsInDb(executorRole)).as("아직 롤을 만들지 않았다").isFalse();

    // 운영자가 뒤늦게 롤을 만든다(#383 절차의 재현).
    roleCreatedByThisTest = TenantRlsTestSupport.ensureRoleExists(ownerDsl(), executorRole);

    // 2차 — 스키마는 이미 있지만, 롤이 이 스키마의 기본 권한을 아직 못 받았으므로 단락 조건
    // (R12)이 거짓이 되어 grant 블록이 다시 실행돼야 한다.
    provision(tenantId);

    assertThat(hasSchemaPrivilege(executorRole, schema, "USAGE"))
        .as("자가치유로 뒤늦게 USAGE 가 걸려야 한다")
        .isTrue();
    assertThat(defaultAclExists(schema, "app_tenant", executorRole, "r")).isTrue();
    assertThat(defaultAclExists(schema, "app_tenant", executorRole, "S")).isTrue();
  }

  /**
   * 자가치유 재개정(라운드 2 리뷰 SHOULD-FIX) — 운영자가 "권한 없음" 증상을 보고 가장 자연스러운
   * 1차 조치로 {@code GRANT USAGE ON SCHEMA} 만 손으로 줘도, 다음 프로비저닝 호출이 남은
   * {@code ALTER DEFAULT PRIVILEGES} 까지 마저 건다.
   *
   * <p>왜 필요한가: 치유 완료 판정을 {@code has_schema_privilege(..., 'USAGE')} 로만 봤을 때는
   * 이 시나리오에서 영구 단락에 갇혔다 — USAGE 는 있지만 기본 권한이 없는 중간 상태가
   * "완료"로 오판되어, 그 테넌트에서 <b>나중에 만들어지는</b> 테이블만 executor 에게 계속 안
   * 보이는 조용한 실패가 됐다(증상이 부분적이라 원인 추적이 더 어렵다). 치유 완료 판정을
   * {@code pg_default_acl} 항목(테이블·시퀀스 둘 다) 존재로 바꿔 이 함정을 없앤다.
   */
  @Test
  void selfHealsWhenOnlyUsageWasGrantedManually() {
    tenantId = TENANT_BASE + 5;
    TenantRlsTestSupport.insertActiveTenant(dsl, tenantId);
    schema = TenantContext.runScopedGet(tenantId, DataSchema::current);
    executorRole = TenantPipelineRole.roleName(tenantId);

    // 1차 — 롤 없이 프로비저닝해 스키마만 만든다.
    provision(tenantId);

    // 운영자가 롤을 만들고, "권한 없음" 을 본 가장 자연스러운 1차 조치로 USAGE 만 손으로
    // 준다 — ALTER DEFAULT PRIVILEGES 는 아직 걸지 않은 중간 상태를 재현한다.
    roleCreatedByThisTest = TenantRlsTestSupport.ensureRoleExists(ownerDsl(), executorRole);
    ownerDsl().execute("GRANT USAGE ON SCHEMA " + schema + " TO " + executorRole);

    // 함정 상태가 실제로 재현됐는지 먼저 확인한다 — 이게 없으면 아래 자가치유 단언이
    // "애초에 완료 상태였다"로도 통과하는 공허한 테스트가 된다.
    assertThat(hasSchemaPrivilege(executorRole, schema, "USAGE")).isTrue();
    assertThat(defaultAclExists(schema, "app_tenant", executorRole, "r"))
        .as("함정 상태 재현 확인 — 기본 권한은 아직 없어야 한다")
        .isFalse();

    // 2차 — USAGE 만으로는 "완료"로 오판하지 않고 다시 들어가 기본 권한까지 마저 걸어야 한다.
    provision(tenantId);

    assertThat(defaultAclExists(schema, "app_tenant", executorRole, "r"))
        .as("자가치유로 테이블 기본 권한이 뒤늦게 걸려야 한다")
        .isTrue();
    assertThat(defaultAclExists(schema, "app_tenant", executorRole, "S"))
        .as("자가치유로 시퀀스 기본 권한이 뒤늦게 걸려야 한다")
        .isTrue();
  }

  /**
   * 단락이 실제로 일어난다 — 기본 권한이 완료된 상태에서 다시 부르면 부여 블록을 재실행하지
   * 않는다(코드리뷰 지적: {@code hasCompleteDefaultPrivileges} 를 검증하는 테스트가 하나도
   * 없어서, 그 메서드를 {@code return false} 로 변이시켜도 스위트 전체가 초록이었다).
   *
   * <p><b>왜 단락 여부를 이렇게 관측하는가.</b> 단락은 정의상 "아무 일도 하지 않는다" 라서
   * 직접 관측할 수 없다. 그래서 <b>프로비저닝이 끝난 뒤에</b> 스트레이 public grant 를 심고
   * 다시 호출한다 — 단락하면 부여 블록의 {@code REVOKE ALL ON SCHEMA public} 이 돌지 않으므로
   * 스트레이가 <b>남아 있어야</b> 한다. {@code hasCompleteDefaultPrivileges} 가 {@code false} 를
   * 돌려주는 변이(또는 단락 조건 자체의 삭제)는 그 REVOKE 를 실행시켜 스트레이를 지우므로 이
   * 단언이 즉시 빨개진다 — 변이로 확인함.
   *
   * <p>이 단락이 지키는 것: 테넌트 1 의 물리 스키마 {@code data} 에는 테이블이 86개 있고
   * {@code GRANT ... ON ALL TABLES} 는 테이블마다 {@code AccessExclusiveLock} 을 잡는다. 단락이
   * 죽으면 데이터셋 생성마다 그 락 폭풍이 재현된다(R9 가 막으려던 결함). 그 시나리오 자체는
   * 공유 test DB 의 {@code data} 를 건드리므로 재현하지 않고(클래스 Javadoc 규율), 판정이 살아
   * 있다는 사실만 신규 스키마에서 확인한다.
   */
  @Test
  void skipsProvisioningOnceDefaultPrivilegesAreComplete() {
    tenantId = TENANT_BASE + 6;
    TenantRlsTestSupport.insertActiveTenant(dsl, tenantId);
    schema = TenantContext.runScopedGet(tenantId, DataSchema::current);
    executorRole = TenantPipelineRole.roleName(tenantId);
    roleCreatedByThisTest = TenantRlsTestSupport.ensureRoleExists(ownerDsl(), executorRole);

    // 1차 — 롤이 이미 있으므로 6문장이 전부 걸려 "기본 권한 완료" 상태가 된다.
    provision(tenantId);
    assertThat(defaultAclExists(schema, "app_tenant", executorRole, "r"))
        .as("전제 확인 — 1차 프로비저닝으로 테이블 기본 권한이 걸려 있어야 한다")
        .isTrue();
    assertThat(defaultAclExists(schema, "app_tenant", executorRole, "S"))
        .as("전제 확인 — 1차 프로비저닝으로 시퀀스 기본 권한이 걸려 있어야 한다")
        .isTrue();

    // 프로비저닝 **이후에** 스트레이 grant 를 심는다 — 2차 호출이 단락하는지를 가리는 표식.
    ownerDsl().execute("GRANT USAGE ON SCHEMA public TO " + executorRole);
    strayPublicGrantNeedsRevoke = true;
    assertThat(publicSchemaHasRoleSpecificAclEntry(executorRole))
        .as("표식 심기가 실제로 롤 전용 ACL 항목을 남겼는지 확인")
        .isTrue();

    // 2차 — 완료 상태이므로 단락해야 한다.
    provision(tenantId);

    assertThat(publicSchemaHasRoleSpecificAclEntry(executorRole))
        .as("단락했다면 REVOKE 가 돌지 않아 표식이 남아 있어야 한다 — 사라졌으면 단락 판정이 죽은 것")
        .isTrue();
  }

  /** 테넌트 컨텍스트가 없으면 조용히 기본 스키마로 떨어지지 않고 터진다. */
  @Test
  void failsWithoutTenantContext() {
    TenantContext.clear();
    assertThatThrownBy(() -> provisioner.ensureCurrentTenantSchema())
        .isInstanceOf(MissingTenantScopeException.class);
  }

  /** 삭제 헬퍼는 data 를 절대 지우지 않는다 (R7 가드의 비공허성). */
  @Test
  void dropHelperRefusesNonTenantSchemas() {
    DSLContext owner = ownerDsl();
    assertThatThrownBy(() -> TenantRlsTestSupport.dropSchemasCreatedByThisTest(owner, "data"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> TenantRlsTestSupport.dropSchemasCreatedByThisTest(owner, "public"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * 테넌트 스코프를 열고 프로비저너를 한 번 부른다. {@code runScopedGet} 이 {@code Supplier} 를
   * 요구해서 생기는 {@code return null;} 의식이 여섯 테스트에 그대로 복붙돼 있었다(simplify 패스
   * SIMPLIFICATION 축) — 일곱 번째 테스트를 쓰는 사람이 의도가 아니라 보일러플레이트를 복사하지
   * 않게 한다.
   */
  private void provision(long tenantId) {
    TenantContext.runScopedGet(
        tenantId,
        () -> {
          provisioner.ensureCurrentTenantSchema();
          return null;
        });
  }

  // ── 검증 전용 헬퍼 ──────────────────────────────────────────────────────

  private boolean schemaExists(String schema) {
    return dsl.fetchExists(
        dsl.selectOne().from("pg_namespace").where(field("nspname", String.class).eq(schema)));
  }

  private boolean roleExistsInDb(String roleName) {
    return dsl.fetchExists(
        dsl.selectOne().from("pg_roles").where(field("rolname", String.class).eq(roleName)));
  }

  private boolean hasSchemaPrivilege(String role, String schema, String privilege) {
    Object result = dsl.fetchValue("select has_schema_privilege(?, ?, ?)", role, schema, privilege);
    return Boolean.TRUE.equals(result);
  }

  /**
   * {@code public} 스키마의 ACL 에 지정한 롤을 grantee 로 하는 항목이 있는지 {@code
   * aclexplode} 로 직접 본다(최종 전체 리뷰 B4) — {@code has_schema_privilege} 를 쓰지 않는
   * 이유는 {@link #grantsRequiredPrivileges} Javadoc 참조(PUBLIC 의사 롤 자체가 이미 USAGE 를
   * 가지고 있어 그 함수는 REVOKE 여부와 무관하게 항상 {@code true} 다). {@code aclexplode} 는
   * PUBLIC 부여를 {@code grantee=0} 으로 표현하므로 {@code pg_roles} 와 조인하면 실제 이름이
   * 있는 롤(=PUBLIC 이 아닌 특정 롤) 항목만 남는다.
   */
  private boolean publicSchemaHasRoleSpecificAclEntry(String roleName) {
    Object result =
        dsl.fetchValue(
            "select exists ("
                + "  select 1 from pg_namespace n, aclexplode(n.nspacl) a"
                + "  join pg_roles r on r.oid = a.grantee"
                + "  where n.nspname = 'public' and r.rolname = ?"
                + ")",
            roleName);
    return Boolean.TRUE.equals(result);
  }

  /**
   * {@code pg_default_acl} 을 {@code pg_namespace}·{@code pg_roles} 와 조인해, 지정한
   * {@code forRole}({@code FOR ROLE})이 만든 {@code objType}({@code 'r'}=테이블, {@code 'S'}=
   * 시퀀스) 기본 권한 중 {@code granteeRole} 에게 준 항목이 있는지 본다.
   *
   * <p>스키마만으로 조인하고 grantee 만 보는 버전은 {@code defaclrole} 을 구별하지 못해
   * {@code FOR ROLE app_tenant} → {@code FOR ROLE app} 변이를 통과시킨다(라운드 1 리뷰 실증,
   * 클래스 Javadoc 참조) — 그래서 {@code defaclrole}·{@code defaclobjtype} 을 명시 조건으로
   * 추가했다.
   */
  private boolean defaultAclExists(String schema, String forRole, String granteeRole, String objType) {
    Object result =
        dsl.fetchValue(
            "select exists ("
                + "  select 1 from pg_default_acl da"
                + "  join pg_namespace n on n.oid = da.defaclnamespace"
                + "  join pg_roles r on r.oid = da.defaclrole"
                + "  where n.nspname = ?"
                + "    and r.rolname = ?"
                + "    and da.defaclobjtype = ?"
                + "    and exists ("
                + "      select 1 from unnest(da.defaclacl) as acl(item)"
                + "      where item::text like ?"
                + "    )"
                + ")",
            schema,
            forRole,
            objType,
            granteeRole + "=%");
    return Boolean.TRUE.equals(result);
  }
}
