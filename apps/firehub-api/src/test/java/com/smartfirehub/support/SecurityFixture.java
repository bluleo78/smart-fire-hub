package com.smartfirehub.support;

import static com.smartfirehub.jooq.Tables.DATASET;
import static com.smartfirehub.jooq.Tables.DATASET_ACCESS_GRANT;
import static com.smartfirehub.jooq.Tables.PERMISSION;
import static com.smartfirehub.jooq.Tables.ROLE;
import static com.smartfirehub.jooq.Tables.ROLE_PERMISSION;
import static com.smartfirehub.jooq.Tables.SECURITY_LEVEL;
import static com.smartfirehub.jooq.Tables.USER_ROLE;

import org.jooq.DSLContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 보안 등급 테스트 픽스처 — 기본 테넌트(1)에 데이터셋 메타 행·역할·허용 목록을 직접 심는다.
 *
 * <p>데이터셋은 <b>메타 행만</b> 만든다(물리 테이블 없음). ID·목록 경로는 메타만으로 판정되기 때문이다. SQL 경로 테스트는 물리 테이블이 필요하므로 {@code
 * DatasetService.createDataset} 를 쓴다(그 테스트에 명시).
 *
 * <p>모든 RLS 테이블 쓰기는 테넌트 1 트랜잭션 안에서 한다({@link TenantRlsTestSupport#runInTenantTransaction}). 테넌트 1 의
 * 공유 등급 플래그를 바꾸는 테스트는 반드시 finally 에서 원복한다(다른 테스트가 같은 DB 를 쓴다).
 */
public final class SecurityFixture {

  private static final long TENANT = 1L;
  private final DSLContext dsl;
  private final TransactionTemplate tx;
  private final PasswordEncoder encoder;

  public SecurityFixture(DSLContext dsl, TransactionTemplate tx, PasswordEncoder encoder) {
    this.dsl = dsl;
    this.tx = tx;
    this.encoder = encoder;
  }

  /** 테넌트 1 의 등급 id 를 이름으로 찾는다(V133 시드: 공개·내부·민감·기밀). */
  public long levelId(String name) {
    return TenantRlsTestSupport.runInTenantTransaction(
        tx,
        TENANT,
        () ->
            dsl.select(SECURITY_LEVEL.ID)
                .from(SECURITY_LEVEL)
                .where(SECURITY_LEVEL.NAME.eq(name))
                .fetchSingle(SECURITY_LEVEL.ID));
  }

  /** 물리 테이블 없는 데이터셋 메타 행. table_name 은 테스트마다 고유해야 한다. */
  public long createDatasetRow(String tableName, long levelId, long createdBy) {
    return TenantRlsTestSupport.runInTenantTransaction(
        tx,
        TENANT,
        () ->
            dsl.insertInto(DATASET)
                .set(DATASET.NAME, "sf_" + tableName)
                .set(DATASET.TABLE_NAME, tableName)
                .set(DATASET.CREATED_BY, createdBy)
                .set(DATASET.STORAGE_TYPE, "TABLE") // NOT NULL, 기본값 없음
                .set(DATASET.ORIGIN_TYPE, "SOURCE") // NOT NULL, 기본값 없음
                .set(DATASET.SECURITY_LEVEL_ID, levelId)
                .returning(DATASET.ID)
                .fetchSingle(DATASET.ID));
  }

  /** 주어진 자격과 권한을 가진 사용자 정의 역할. */
  public long createRole(String name, long levelId, String... permissionCodes) {
    return TenantRlsTestSupport.runInTenantTransaction(
        tx,
        TENANT,
        () -> {
          long roleId =
              dsl.insertInto(ROLE)
                  .set(ROLE.NAME, name)
                  .set(ROLE.IS_SYSTEM, false)
                  .set(ROLE.MAX_SECURITY_LEVEL_ID, levelId)
                  .returning(ROLE.ID)
                  .fetchSingle(ROLE.ID);
          for (String code : permissionCodes) {
            dsl.insertInto(ROLE_PERMISSION)
                .set(ROLE_PERMISSION.ROLE_ID, roleId)
                .set(
                    ROLE_PERMISSION.PERMISSION_ID,
                    dsl.select(PERMISSION.ID).from(PERMISSION).where(PERMISSION.CODE.eq(code)))
                .execute();
          }
          return roleId;
        });
  }

  /** 모든 권한을 가진 역할(라우트 열거 테스트용). */
  public long createAllPermissionRole(String name, long levelId) {
    // 운영자 평면 권한(category='platform')은 테넌트 역할에 붙이지 않는다.
    String[] all =
        dsl.select(PERMISSION.CODE)
            .from(PERMISSION)
            .where(PERMISSION.CATEGORY.ne("platform"))
            .fetchArray(PERMISSION.CODE);
    return createRole(name, levelId, all);
  }

  public void assignRole(long userId, long roleId) {
    TenantRlsTestSupport.runInTenantTransaction(
        tx,
        TENANT,
        () ->
            dsl.insertInto(USER_ROLE)
                .set(USER_ROLE.USER_ID, userId)
                .set(USER_ROLE.ROLE_ID, roleId)
                .onConflictDoNothing()
                .execute());
  }

  /** USER 역할(기본=내부)을 떼어 사용자의 자격을 지정 역할로만 결정되게 한다. */
  public void removeUserRole(long userId) {
    TenantRlsTestSupport.runInTenantTransaction(
        tx,
        TENANT,
        () ->
            dsl.execute(
                "delete from user_role where user_id = ? and role_id in (select id from role where name = 'USER')",
                userId));
  }

  public void grantUser(long datasetId, long userId) {
    TenantRlsTestSupport.runInTenantTransaction(
        tx,
        TENANT,
        () ->
            dsl.insertInto(DATASET_ACCESS_GRANT)
                .set(DATASET_ACCESS_GRANT.DATASET_ID, datasetId)
                .set(DATASET_ACCESS_GRANT.USER_ID, userId)
                .execute());
  }

  public void grantRole(long datasetId, long roleId) {
    TenantRlsTestSupport.runInTenantTransaction(
        tx,
        TENANT,
        () ->
            dsl.insertInto(DATASET_ACCESS_GRANT)
                .set(DATASET_ACCESS_GRANT.DATASET_ID, datasetId)
                .set(DATASET_ACCESS_GRANT.ROLE_ID, roleId)
                .execute());
  }

  public void setLevelFlags(long levelId, boolean allowlistRequired, boolean adminBypass) {
    TenantRlsTestSupport.runInTenantTransaction(
        tx,
        TENANT,
        () ->
            dsl.update(SECURITY_LEVEL)
                .set(SECURITY_LEVEL.ALLOWLIST_REQUIRED, allowlistRequired)
                .set(SECURITY_LEVEL.ADMIN_BYPASS, adminBypass)
                .where(SECURITY_LEVEL.ID.eq(levelId))
                .execute());
  }

  public void deleteDatasetRow(long datasetId) {
    TenantRlsTestSupport.runInTenantTransaction(
        tx, TENANT, () -> dsl.deleteFrom(DATASET).where(DATASET.ID.eq(datasetId)).execute());
  }

  public void deleteRole(long roleId) {
    TenantRlsTestSupport.runInTenantTransaction(
        tx,
        TENANT,
        () -> {
          dsl.deleteFrom(USER_ROLE).where(USER_ROLE.ROLE_ID.eq(roleId)).execute();
          dsl.deleteFrom(ROLE_PERMISSION).where(ROLE_PERMISSION.ROLE_ID.eq(roleId)).execute();
          dsl.deleteFrom(ROLE).where(ROLE.ID.eq(roleId)).execute();
        });
  }

  /** 테넌트 1 ACTIVE 멤버 + USER 역할. 반환값은 userId. */
  public long createUser(String prefix) {
    String u = prefix + System.nanoTime() + "@example.com";
    return TestUsers.createMember(dsl, tx, encoder, u, u, "Password123", prefix, TENANT).id();
  }

  public void deleteUser(long userId) {
    TestUsers.cleanup(dsl, tx, userId, TENANT);
  }
}
