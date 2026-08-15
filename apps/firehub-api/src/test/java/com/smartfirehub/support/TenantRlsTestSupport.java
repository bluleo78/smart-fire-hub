package com.smartfirehub.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.global.tenant.TenantContext;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.jooq.Table;
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
   * <p>PK 는 {@code id} 로 고정한다 — 현재 이 헬퍼로 검증하는 테이블은 전부 서로게이트 {@code id}
   * PK 다. {@code dataset_id} 가 PK 인 테이블(dataset_embedding·file_dataset_config)을 실제로
   * 검증하게 되면 그때 파라미터를 추가한다.
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
    final String pkColumn = "id";

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

  /** 현재 테넌트 컨텍스트에서 해당 행이 보이는지 확인한다(RLS 적용 결과). */
  public static boolean rowExists(DSLContext dsl, String tableName, String pkColumn, Long pk) {
    return dsl.fetchCount(table(name(tableName)), field(name(pkColumn), Long.class).eq(pk)) > 0;
  }
}
