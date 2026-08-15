package com.smartfirehub.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.global.tenant.TenantContext;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.jooq.Table;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionTemplate;

// RLS/TenantContext 를 다루는 테스트(RlsIsolationTest/TenantContextGucTest/
// NonTransactionalRlsReadGuardTest)가 각자 다른 rigor로 "TenantContext 설정 → 트랜잭션 실행 →
// finally 에서 컨텍스트 해제"를 반복 구현하고 있었다(일부는 예외 시 컨텍스트가 새는 결함이 있었다).
// 이후 단계에서 도메인 테이블마다 비슷한 RLS 테스트가 추가되므로, 그 반복만 여기서 공유하고 —
// 각 테스트가 무엇을 단언할지는 건드리지 않는다(과잉 일반화하지 않는다).
public final class TenantRlsTestSupport {

  private TenantRlsTestSupport() {}

  /** RLS 격리 검증용 카나리 테이블. 두 테스트가 각자 선언하던 것을 하나로 모은다. */
  public static final Table<?> TENANT_CANARY = table(name("tenant_canary"));

  // nanoTime 기반으로 시작해, 프로세스마다 겹치지 않는 값에서 증가시킨다. 실행마다 고유해야
  // 공유 테스트 DB 에 남는 커밋된 카나리 행이 다음 실행과 섞이지 않는다.
  private static final AtomicLong NEXT_TENANT_ID = new AtomicLong(900_000_000L + System.nanoTime() % 1_000_000L);

  /** 실행마다 고유한 테스트용 테넌트 id를 하나 발급한다. */
  public static long nextTenantId() {
    return NEXT_TENANT_ID.incrementAndGet();
  }

  /**
   * 주어진 테넌트 컨텍스트를 설정하고 트랜잭션 안에서 action 을 실행한 뒤, 성공/예외 여부와 관계없이
   * <b>진입 전 컨텍스트를 복원</b>한다(결과 없음 버전).
   *
   * <p>복원이지 {@code clear} 가 아니다. 대부분의 테스트는 {@code IntegrationTestBase} 가 기본
   * 테넌트를 세워 둔 상태에서 이 헬퍼로 픽스처를 만들고, <b>그 다음에</b> 검증 대상(프로덕션 코드)을
   * 트랜잭션 밖에서 호출한다. 여기서 지워 버리면 그 호출이 컨텍스트 없이 돌아 RLS 가 전 행을 막고
   * 테스트가 "조용한 0행" 으로 실패한다 — 실제로 그 형태로 여러 테스트가 깨졌고, 호출자마다
   * 컨텍스트를 다시 세우는 보상 코드가 복붙되고 있었다. 의미론을 {@link TenantContext#runScoped} 와
   * 맞춰 그 보상을 없앤다.
   */
  public static void runInTenantTransaction(
      TransactionTemplate transactionTemplate, Long tenantId, Runnable action) {
    runInTenantTransaction(
        transactionTemplate,
        tenantId,
        () -> {
          transactionTemplate.executeWithoutResult(status -> action.run());
          return null;
        });
  }

  /** 위와 동일하되 트랜잭션 실행 결과를 반환한다(값 반환 버전). */
  public static <T> T runInTenantTransaction(
      TransactionTemplate transactionTemplate, Long tenantId, Supplier<T> action) {
    if (tenantId != null) {
      return TenantContext.runScopedGet(
          tenantId, () -> transactionTemplate.execute(status -> action.get()));
    }
    // tenantId=null 은 "컨텍스트가 비어 있는 상태" 를 재현하려는 요청이다(fail-closed 검증용).
    // 이때도 진입 전 값은 복원해야 호출한 테스트의 뒷부분이 영향을 받지 않는다.
    Long previous = TenantContext.get();
    TenantContext.clear();
    try {
      return transactionTemplate.execute(status -> action.get());
    } finally {
      if (previous == null) {
        TenantContext.clear();
      } else {
        TenantContext.set(previous);
      }
    }
  }

  // ── 도메인 RLS 테스트용 공용 헬퍼 ───────────────────────────────────────
  //
  // V87 부터 도메인 테이블의 tenant_id 에 tenant(id) FK 가 걸리므로, 더 이상 가짜 테넌트 id 로
  // 행을 만들 수 없다. 실제 tenant 행을 만들어 써야 한다.

  private static final Table<?> TENANT = table(name("tenant"));

  /**
   * 테스트 전용 ACTIVE 테넌트를 하나 만들고 id 를 반환한다.
   *
   * <p>`tenant` 은 테넌트 경계 위의 전역 테이블이라 RLS 가 없다(V81) — 컨텍스트 없이 직접
   * insert/delete 할 수 있다. slug 는 실행마다 고유해야 공유 테스트 DB 에서 충돌하지 않는다.
   */
  public static long createActiveTenant(DSLContext dsl, String slugPrefix) {
    long suffix = nextTenantId();
    return dsl.insertInto(TENANT)
        .set(field(name("slug"), String.class), slugPrefix + "-" + suffix)
        .set(field(name("name"), String.class), "Test Tenant " + suffix)
        .set(field(name("status"), String.class), "ACTIVE")
        .returning(field(name("id"), Long.class))
        .fetchOne()
        .get(field(name("id"), Long.class));
  }

  /**
   * 테스트 픽스처용 사용자를 하나 만들고 id 를 반환한다.
   *
   * <p>도메인 테이블 다수가 {@code created_by}/{@code uploaded_by} 를 NOT NULL 로 요구하는데,
   * {@code "user"} 는 테넌트 경계 위의 전역 테이블(RLS 없음)이라 컨텍스트 없이 만들 수 있다.
   * username/email 이 유니크라 접두사만으로는 부족해 실행마다 고유한 접미사를 붙인다.
   */
  public static Long insertUser(DSLContext dsl, String prefix) {
    long suffix = nextTenantId();
    return dsl.insertInto(table(name("user")))
        .set(field(name("username"), String.class), prefix + suffix)
        .set(field(name("password"), String.class), "pw")
        .set(field(name("name"), String.class), "Test User")
        .set(field(name("email"), String.class), prefix + suffix + "@example.com")
        .returning(field(name("id"), Long.class))
        .fetchOne()
        .get(field(name("id"), Long.class));
  }

  /** 위에서 만든 사용자를 지운다. 자식 행이 남아 있으면 FK 때문에 실패하므로 도메인 정리 뒤에 부른다. */
  public static void deleteUser(DSLContext dsl, Long userId) {
    if (userId != null) {
      dsl.deleteFrom(table(name("user"))).where(field(name("id"), Long.class).eq(userId)).execute();
    }
  }

  /** 테스트가 만든 테넌트를 지운다. 자식 행이 남아 있으면 FK 때문에 실패하므로 마지막에 부른다. */
  public static void deleteTenants(DSLContext dsl, Long... tenantIds) {
    for (Long id : tenantIds) {
      if (id != null) {
        dsl.deleteFrom(TENANT).where(field(name("id"), Long.class).eq(id)).execute();
      }
    }
  }

  /**
   * 한 테이블의 테넌트 격리를 <b>양방향</b>으로 단언한다.
   *
   * <p>단방향("타 테넌트에서 0행")만 보면 빈 테이블에서 공허하게 통과한다 — P1 에서 실제로 이
   * 형태의 단언이 결함을 통과시킨 전례가 있다. 소유 테넌트에서 실제로 보이는 것을 함께 확인해야
   * 단언이 의미를 갖는다. DEFAULT 가 GUC 에서 채워졌는지도 같이 본다.
   *
   * <p>PK 컬럼 기본값은 {@code id} 다 — 대부분의 대상 테이블이 서로게이트 {@code id} PK 를 갖는다.
   * 그렇지 않은 테이블({@code dataset_ontology} 처럼 {@code dataset_id} 로 행을 식별하는 경우)은
   * 아래 오버로드로 식별 컬럼을 넘긴다. 그래야 그 테이블도 "소유 테넌트에서 보인다 + 남에게는 안
   * 보인다 + tenant_id DEFAULT 가 GUC 에서 채워졌다" 세 다리를 똑같이 검증받는다(직접 만든 단방향
   * 카운트로 대체하면 마지막 다리가 빠진다).
   *
   * @param insertReturningPk 소유 테넌트 컨텍스트 안에서 행을 만들고 PK 를 반환한다
   */
  public static void assertTwoSidedIsolation(
      TransactionTemplate tx,
      DSLContext dsl,
      long ownerTenant,
      long otherTenant,
      String tableName,
      Supplier<Long> insertReturningPk) {
    assertTwoSidedIsolation(tx, dsl, ownerTenant, otherTenant, tableName, "id", insertReturningPk);
  }

  /**
   * 위와 동일하되 행을 식별할 컬럼을 지정한다(서로게이트 {@code id} PK 가 없는 테이블용).
   *
   * @param pkColumn 행을 유일하게 식별하는 컬럼 이름. {@code insertReturningPk} 가 반환하는 값과
   *     같은 컬럼이어야 한다.
   */
  public static void assertTwoSidedIsolation(
      TransactionTemplate tx,
      DSLContext dsl,
      long ownerTenant,
      long otherTenant,
      String tableName,
      String pkColumn,
      Supplier<Long> insertReturningPk) {
    Long pk = runInTenantTransaction(tx, ownerTenant, insertReturningPk);
    assertThat(pk).as("%s: 픽스처가 행을 만들지 못했다", tableName).isNotNull();

    Boolean visibleToOwner =
        runInTenantTransaction(tx, ownerTenant, () -> rowExists(dsl, tableName, pkColumn, pk));
    assertThat(visibleToOwner).as("%s: 소유 테넌트에서 자기 행이 보여야 한다", tableName).isTrue();

    Boolean visibleToOther =
        runInTenantTransaction(tx, otherTenant, () -> rowExists(dsl, tableName, pkColumn, pk));
    assertThat(visibleToOther)
        .as("%s: 다른 테넌트에서 남의 행이 보이면 격리 실패다", tableName)
        .isFalse();

    Long stored =
        runInTenantTransaction(
            tx,
            ownerTenant,
            () ->
                dsl.select(field(name("tenant_id"), Long.class))
                    .from(table(name(tableName)))
                    .where(field(name(pkColumn), Long.class).eq(pk))
                    .fetchOne(field(name("tenant_id"), Long.class)));
    assertThat(stored)
        .as("%s: tenant_id DEFAULT 가 GUC 에서 채워져야 한다", tableName)
        .isEqualTo(ownerTenant);
  }

  /**
   * 테넌트의 RBAC 관련 행을 FK 순서대로 지운다({@code role_permission}/{@code user_role}/
   * {@code report_template} → {@code role}). role 을 참조하는 자식부터 지워야 role 삭제가 FK 에
   * 걸리지 않는다. 세 개의 RBAC 테스트가 각자 복붙하던 것을 모았다 — 특정 테스트가 그중 일부
   * 테이블에 행을 만들지 않았어도, 없는 행을 지우는 DELETE 는 0행으로 끝나 안전하다.
   *
   * <p>호출자가 대상 테넌트 컨텍스트 트랜잭션 안에서 불러야 한다({@link #runInTenantTransaction}).
   */
  public static void deleteRbacCascade(DSLContext dsl, long tenantId) {
    dsl.execute("delete from role_permission where tenant_id = ?", tenantId);
    dsl.execute("delete from user_role where tenant_id = ?", tenantId);
    dsl.execute("delete from report_template where tenant_id = ?", tenantId);
    dsl.execute("delete from role where tenant_id = ?", tenantId);
  }

  /**
   * 온톨로지·그래프 8테이블(P2-d)의 테넌트 행을 FK 순서대로 지운다. {@link #deleteRbacCascade} 와
   * 같은 계약이다 — 호출자가 대상 테넌트 컨텍스트 트랜잭션 안에서 부른다.
   *
   * <p>RLS 가 이미 현재 테넌트 행만 보여 주지만 {@code where tenant_id = ?} 를 명시한다. V102 는
   * FORCE RLS 를 쓰지 않으므로 테이블 소유 롤({@code app})로 접속하면 정책이 통째로 우회되고,
   * 그때 WHERE 없는 DELETE 는 공유 테스트 DB 의 V71/V72/V80 시드까지 지워 무관한 테스트를 전부
   * 무너뜨린다. WHERE 는 그 사고에 대한 안전장치다.
   */
  public static void deleteOntologyGraphCascade(DSLContext dsl, long tenantId) {
    // ontology 를 참조하는 자식부터 지운다(ON DELETE CASCADE 가 있어도 명시적으로 지워야
    // 정책이 자식까지 스코프하는지가 정리 단계에서 드러난다).
    dsl.execute("delete from ontology_relation where tenant_id = ?", tenantId);
    dsl.execute("delete from ontology_entity_property where tenant_id = ?", tenantId);
    dsl.execute("delete from ontology_entity_type where tenant_id = ?", tenantId);
    dsl.execute("delete from dataset_ontology where tenant_id = ?", tenantId);
    dsl.execute("delete from dataset_mapping where tenant_id = ?", tenantId);
    dsl.execute("delete from dataset_graph_ingest where tenant_id = ?", tenantId);
    dsl.execute("delete from graph_review_item where tenant_id = ?", tenantId);
    dsl.execute("delete from ontology where tenant_id = ?", tenantId);
  }

  /**
   * 테넌트 컨텍스트가 비어 있으면 자기 행조차 보이지 않는지(fail-closed) 단언한다.
   *
   * <p>공유 테스트 DB 라 전체 카운트로는 단언할 수 없다(다른 세션이 동시에 쓴다). 소유 테넌트에서
   * 방금 만든 행 하나가 컨텍스트 없이도 보이는지로 확인한다. 정책이 fail-open 이면 여기서 걸린다.
   *
   * @param insertReturningPk 소유 테넌트 컨텍스트 안에서 행을 만들고 {@code id} 를 반환한다
   */
  public static void assertFailsClosedWithoutContext(
      TransactionTemplate tx,
      DSLContext dsl,
      long ownerTenant,
      String tableName,
      Supplier<Long> insertReturningPk) {
    Long pk = runInTenantTransaction(tx, ownerTenant, insertReturningPk);
    assertThat(pk).as("%s: 픽스처가 행을 만들지 못했다", tableName).isNotNull();

    // tenantId=null 은 "컨텍스트가 빈 상태" 재현이다.
    Boolean visible =
        runInTenantTransaction(tx, null, () -> rowExists(dsl, tableName, "id", pk));

    assertThat(visible)
        .as("%s: 테넌트 컨텍스트 없이 행이 보이면 fail-open 이다", tableName)
        .isFalse();
  }

  /**
   * 다른 테넌트 id 를 명시한 INSERT 를 정책의 WITH CHECK 가 거부하는지 단언한다.
   *
   * <p>WITH CHECK 가 없으면 격리가 읽기에만 걸린 상태가 된다 — 한 테넌트가 남의 테넌트에 행을 심을
   * 수 있다. 삽입 SQL 은 테이블마다 다르므로 호출자가 람다로 넘긴다(헬퍼는 트랜잭션·단언만 소유).
   *
   * @param crossTenantInsert {@code actingTenant} 컨텍스트 안에서 남의 tenant_id 로 INSERT 를 시도
   */
  public static void assertCrossTenantInsertRejected(
      TransactionTemplate tx, long actingTenant, String tableName, Runnable crossTenantInsert) {
    Throwable thrown =
        catchThrowable(() -> runInTenantTransaction(tx, actingTenant, crossTenantInsert));

    assertThat(thrown)
        .as("%s: 다른 테넌트 id 로 INSERT 가 통과하면 WITH CHECK 가 없는 것이다", tableName)
        .isInstanceOf(DataAccessException.class);
  }

  /** 현재 테넌트 컨텍스트에서 해당 행이 보이는지 확인한다(RLS 적용 결과). */
  public static boolean rowExists(DSLContext dsl, String tableName, String pkColumn, Long pk) {
    return dsl.fetchCount(table(name(tableName)), field(name(pkColumn), Long.class).eq(pk)) > 0;
  }
}
