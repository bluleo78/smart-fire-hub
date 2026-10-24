package com.smartfirehub.dataset.storage;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

/**
 * 테넌트별 외부 저장소 설정({@code tenant_data_store}, {@code tenant_data_store_account}) 접근(WD-73).
 *
 * <p>두 테이블은 테넌트 경계 위의 전역 테이블(V138, RLS 없음)이라 테넌트 컨텍스트 없이도 읽고 쓸 수 있다. 코드젠 대상이 아니므로 {@code
 * PlatformTenantRepository} 처럼 이름 기반 jOOQ 를 쓴다. enum 은 {@code name()} 문자열로 저장한다.
 *
 * <p>트랜잭션 경계는 호출 서비스가 잡는다(테넌트 생성과 같은 트랜잭션에서 insert 해야 한다).
 */
@Repository
public class TenantDataStoreRepository {

  // public 스키마를 명시한다: 테넌트 search_path 에 같은 이름의 데이터셋 테이블이 있어도 가려지지 않게 한다.
  private static final Table<Record> STORE = table(name("public", "tenant_data_store"));
  private static final Table<Record> ACCOUNT = table(name("public", "tenant_data_store_account"));

  private static final Field<Long> TENANT_ID = field(name("tenant_id"), Long.class);
  private static final Field<String> BACKEND = field(name("backend"), String.class);
  private static final Field<String> HOST = field(name("host"), String.class);
  private static final Field<Integer> PORT = field(name("port"), Integer.class);
  private static final Field<String> DATABASE_NAME = field(name("database_name"), String.class);
  private static final Field<String> SCHEMA_NAME = field(name("schema_name"), String.class);
  private static final Field<String> TABLESPACE = field(name("tablespace"), String.class);
  private static final Field<String> PROVISION_MODE = field(name("provision_mode"), String.class);
  private static final Field<String> STATUS = field(name("status"), String.class);
  private static final Field<LocalDateTime> UPDATED_AT =
      field(name("updated_at"), LocalDateTime.class);
  private static final Field<Long> SCRIPT_TOKEN_EPOCH =
      field(name("script_token_epoch"), Long.class);
  private static final Field<String> ACCOUNT_ROLE = field(name("account_role"), String.class);
  private static final Field<String> USERNAME = field(name("username"), String.class);
  private static final Field<String> PASSWORD_ENC = field(name("password_enc"), String.class);

  private final DSLContext dsl;

  public TenantDataStoreRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  /** 저장소 설정과 계정 목록을 함께 저장한다. 호출자 트랜잭션 안에서 원자적으로 실행돼야 한다. */
  public void insert(TenantDataStoreRow row, List<StoredAccount> accounts) {
    dsl.insertInto(STORE)
        .set(TENANT_ID, row.tenantId())
        .set(BACKEND, row.backend().name())
        .set(HOST, row.host())
        .set(PORT, row.port())
        .set(DATABASE_NAME, row.database())
        .set(SCHEMA_NAME, row.schemaName())
        .set(TABLESPACE, row.tablespace())
        .set(PROVISION_MODE, row.mode().name())
        .set(STATUS, row.status().name())
        .execute();
    for (StoredAccount account : accounts) {
      dsl.insertInto(ACCOUNT)
          .set(TENANT_ID, row.tenantId())
          .set(ACCOUNT_ROLE, account.role().name())
          .set(USERNAME, account.username())
          .set(PASSWORD_ENC, account.encryptedPassword())
          .execute();
    }
  }

  /** 테넌트의 저장소 백엔드. 행이 없으면 empty(= 기본 PostgreSQL) — 리졸버가 캐시해서 쓴다. */
  public Optional<DataStoreBackend> findBackend(long tenantId) {
    return dsl.select(BACKEND)
        .from(STORE)
        .where(TENANT_ID.eq(tenantId))
        .fetchOptional(BACKEND)
        .map(DataStoreBackend::valueOf);
  }

  /** 테넌트의 저장소 설정 전체. 없으면 empty. */
  public Optional<TenantDataStoreRow> find(long tenantId) {
    return dsl.select()
        .from(STORE)
        .where(TENANT_ID.eq(tenantId))
        .fetchOptional()
        .map(
            r ->
                new TenantDataStoreRow(
                    r.get(TENANT_ID),
                    DataStoreBackend.valueOf(r.get(BACKEND)),
                    r.get(HOST),
                    r.get(PORT),
                    r.get(DATABASE_NAME),
                    r.get(SCHEMA_NAME),
                    r.get(TABLESPACE),
                    DataStoreProvisionMode.valueOf(r.get(PROVISION_MODE)),
                    TenantDataStoreStatus.valueOf(r.get(STATUS))));
  }

  /**
   * 지정한 백엔드의 READY 저장소를 가진 테넌트 id(오름차순). 배경 작업(고아 스테이징 정리 등)이 외부 저장소 테넌트만 순회할 때 쓴다 — PENDING 저장소는
   * 풀을 만들 수 없으므로 제외한다.
   */
  public List<Long> findReadyTenantIds(DataStoreBackend backend) {
    return dsl.select(TENANT_ID)
        .from(STORE)
        .where(BACKEND.eq(backend.name()))
        .and(STATUS.eq(TenantDataStoreStatus.READY.name()))
        .orderBy(TENANT_ID)
        .fetch(TENANT_ID);
  }

  /** 테넌트의 계정 목록. 역할 선언 순서(OWNER, RO, S1..S10)로 돌려준다 — 문자열 정렬이면 S10 이 S2 앞에 온다. */
  public List<StoredAccount> findAccounts(long tenantId) {
    return dsl
        .select(ACCOUNT_ROLE, USERNAME, PASSWORD_ENC)
        .from(ACCOUNT)
        .where(TENANT_ID.eq(tenantId))
        .fetch()
        .map(
            r ->
                new StoredAccount(
                    DataStoreAccountRole.valueOf(r.get(ACCOUNT_ROLE)),
                    r.get(USERNAME),
                    r.get(PASSWORD_ENC)))
        .stream()
        .sorted(Comparator.comparing(StoredAccount::role))
        .toList();
  }

  /** 상태를 바꾸고 {@code updated_at} 을 갱신한다. */
  public void updateStatus(long tenantId, TenantDataStoreStatus status) {
    dsl.update(STORE)
        .set(STATUS, status.name())
        .set(UPDATED_AT, LocalDateTime.now())
        .where(TENANT_ID.eq(tenantId))
        .execute();
  }

  /**
   * 저장소 행을 {@code SELECT … FOR UPDATE} 로 잠근다. 호출자 트랜잭션이 끝날 때까지 같은 테넌트의 검증 전환과 비밀번호 재발급이 서로를 기다리게 해,
   * 재발급 직전 비밀번호로 확인한 결과가 READY 로 확정되는 경합을 막는다. 행이 없으면 아무것도 잠그지 않는다.
   */
  public void lockForUpdate(long tenantId) {
    dsl.select(TENANT_ID).from(STORE).where(TENANT_ID.eq(tenantId)).forUpdate().fetch();
  }

  /**
   * 계정의 암호화된 비밀번호만 교체한다(username 은 유지). 비밀번호 재발급용.
   *
   * @throws IllegalStateException 어떤 역할의 계정 행이 없어 갱신되지 않음 — 일부만 바뀐 채 새 스크립트를 내주면 안 되므로 트랜잭션을 롤백시킨다
   */
  public void replacePasswords(long tenantId, List<StoredAccount> accounts) {
    for (StoredAccount account : accounts) {
      int updated =
          dsl.update(ACCOUNT)
              .set(PASSWORD_ENC, account.encryptedPassword())
              .where(TENANT_ID.eq(tenantId))
              .and(ACCOUNT_ROLE.eq(account.role().name()))
              .execute();
      if (updated == 0) {
        throw new IllegalStateException(
            "저장소 계정 행이 없어 비밀번호를 교체하지 못했습니다: tenant=" + tenantId + ", role=" + account.role());
      }
    }
  }

  /**
   * 테넌트의 스크립트 토큰 세대 번호(V147). 행이 없으면 empty.
   *
   * <p>스크립트 토큰(Task 13)이 발급 시점의 이 값을 싣고, db-executor 는 기술자의 값보다 낮은 토큰을 거부한다. 이 저장소는 값을 읽고 올리기만 한다.
   */
  public Optional<Long> findScriptTokenEpoch(long tenantId) {
    return dsl.select(SCRIPT_TOKEN_EPOCH)
        .from(STORE)
        .where(TENANT_ID.eq(tenantId))
        .fetchOptional(SCRIPT_TOKEN_EPOCH);
  }

  /**
   * 스크립트 토큰 세대 번호를 1 올리고 새 값을 돌려준다(행이 없으면 empty). 호출자는 이 변경이 <b>커밋된 뒤에</b> 넓히는 GRANT 를 걸어야 한다 — 그래야
   * 다른 읽는 쪽(기술자 조회)이 새 번호를 먼저 본다.
   */
  public Optional<Long> incrementScriptTokenEpoch(long tenantId) {
    return dsl.update(STORE)
        .set(SCRIPT_TOKEN_EPOCH, SCRIPT_TOKEN_EPOCH.plus(1L))
        .set(UPDATED_AT, LocalDateTime.now())
        .where(TENANT_ID.eq(tenantId))
        .returning(SCRIPT_TOKEN_EPOCH)
        .fetchOptional()
        .map(r -> r.get(SCRIPT_TOKEN_EPOCH));
  }
}
