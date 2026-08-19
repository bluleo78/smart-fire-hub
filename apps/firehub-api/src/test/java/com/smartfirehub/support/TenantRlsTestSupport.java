package com.smartfirehub.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.global.tenant.TenantContext;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.regex.Pattern;
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

  /**
   * 사용자를 한 테넌트의 ACTIVE 멤버로 만든다.
   *
   * <p>{@code membership} 은 테넌트 경계 <b>위</b>의 전역 테이블(RLS 미적용)이라 컨텍스트·트랜잭션
   * 없이 삽입된다. 이 삽입을 각 테스트가 손으로 쓰면 컬럼 구성이 제각각 드리프트하므로 여기로 모은다
   * — 멤버십이 "정확히 1개"인지에 결과가 달라지는 테스트가 여럿이라 형태가 어긋나면 조용히
   * 잘못된 이유로 초록이 된다.
   */
  public static void insertActiveMembership(DSLContext dsl, Long userId, long tenantId) {
    dsl.insertInto(table(name("membership")))
        .set(field(name("user_id"), Long.class), userId)
        .set(field(name("tenant_id"), Long.class), tenantId)
        .set(field(name("role"), String.class), "MEMBER")
        .set(field(name("status"), String.class), "ACTIVE")
        .execute();
  }

  /** 위에서 만든 멤버십을 사용자 단위로 지운다. {@link #deleteUser} 보다 먼저 불러야 FK 가 풀린다. */
  public static void deleteMembership(DSLContext dsl, Long userId) {
    if (userId != null) {
      dsl.deleteFrom(table(name("membership")))
          .where(field(name("user_id"), Long.class).eq(userId))
          .execute();
    }
  }

  /**
   * 프로액티브 잡을 하나 만들고 id 를 반환한다. 호출자가 연 테넌트 컨텍스트/트랜잭션 안에서
   * 실행되어야 하며, {@code tenant_id} 는 싣지 않고 GUC 파생 DEFAULT(V103)에 맡긴다 — 앱이 직접
   * 실으면 GUC 와 어긋날 여지가 생겨 격리 단언이 무의미해진다.
   *
   * <p>{@code name} 은 접두사에 고유 접미사를 붙여 만든다(공유 테스트 DB 라 충돌 방지).
   */
  public static Long insertProactiveJob(DSLContext dsl, Long ownerUserId, String namePrefix) {
    return (Long)
        dsl.fetchValue(
            "insert into proactive_job (user_id, name, prompt) values (?, ?, '테넌트 검증용')"
                + " returning id",
            ownerUserId,
            namePrefix + "-" + nextTenantId());
  }

  /** 위 잡의 성공 실행 이력을 하나 만든다. 호출 규약은 {@link #insertProactiveJob} 과 같다. */
  public static Long insertProactiveExecution(DSLContext dsl, Long jobId) {
    return (Long)
        dsl.fetchValue(
            "insert into proactive_job_execution (job_id, status) values (?, 'SUCCESS')"
                + " returning id",
            jobId);
  }

  /**
   * 테스트가 만든 테넌트를 지운다. 자식 행이 남아 있으면 FK 때문에 실패하므로 마지막에 부른다.
   *
   * <p><b>{@code oauth_state} 는 여기서 함께 지운다.</b> V106 이 붙인 5개 채널 FK 중 어느 것도
   * {@code ON DELETE CASCADE} 가 아닌데, 나머지 4개는 {@link #deleteChannelCascade} 가 맡는 반면
   * {@code oauth_state} 는 그 cascade 에 넣지 않았다(RLS 대상이 아니고 TTL 만료 삭제 경로가 따로
   * 있어 채널 cascade 의 일부로 보기 어렵다). 그 결과 "스크래치 테넌트에서 OAuth state 를
   * issue/consume 하는" 테스트가 teardown 에서 {@code 23503} 으로 터진다 — 문제는 cascade 가 아니라
   * <b>테넌트 teardown</b> 이므로 여기서 막는 것이 맞다. {@code where tenant_id = ?} 로 좁히므로
   * 지우는 대상은 인자로 받은 테넌트의 행뿐이다.
   */
  public static void deleteTenants(DSLContext dsl, Long... tenantIds) {
    for (Long id : tenantIds) {
      if (id != null) {
        dsl.execute("delete from oauth_state where tenant_id = ?", id);
        dsl.deleteFrom(TENANT).where(field(name("id"), Long.class).eq(id)).execute();
      }
    }
  }

  /**
   * {@code DatasetService.createDataset} 이 만든 {@code dataset} 카탈로그 행을 지운다(RLS
   * 스코프). {@code dataset_column}·{@code query_history} 는 {@code ON DELETE CASCADE} 로 함께
   * 사라진다. {@link #deleteTenants} 보다 반드시 먼저 부른다 — {@code fk_dataset_tenant} 에는
   * cascade 가 없다.
   *
   * <p><b>{@code WHERE tenant_id = ?} 를 명시하는 이유.</b> RLS 만으로도 오늘은 확실히 안전하다
   * (실측: {@code dataset} 은 {@code relrowsecurity=t}, 이 헬퍼가 받는 {@code dsl} 은 소유자
   * {@code app} 이 아니라 {@code app_tenant} 로 접속하고 그 롤은 {@code rolbypassrls=f} 다 —
   * GUC 가 없으면 fail-closed 로 "아무것도 안 지운다" 방향이라 폭발 반경이 닫혀 있다). 그런데
   * {@link #deleteTenants} 도 {@code where tenant_id = ?} 를 명시하고, 이 밴드는 정확히
   * "조건 없는 삭제가 위험하다"는 이유로 R7 하드가드까지 만들었다 — WHERE 없는 DELETE 를 새로
   * 심어 그 규율과 어긋나는 선례를 남기지 않는다.
   *
   * <p>승격 이유(simplify 패스 REUSE 축): 이 메서드와 {@link #deleteOwnAuditLogRows} 가 두 테스트
   * 파일에 바이트 단위로 동일하게 복붙돼 있었고, 두 파일의 Javadoc 이 서로를 "같은 함정을 겪고
   * 고친 패턴" 이라고 교차 인용하면서도 승격되지 않았다. 같은 밴드에서 {@link #ensureRoleExists}
   * · {@link #cleanupAll} 은 정확히 같은 이유로 이미 승격됐다 — 세 번째 테스트가 또 복붙하거나
   * 한쪽만 고쳐져 FK 순서·WHERE 절이 어긋나는 것을 막는다.
   */
  public static void deleteOwnDatasetRows(
      DSLContext dsl, TransactionTemplate transactionTemplate, long tenantId) {
    runInTenantTransaction(
        transactionTemplate,
        tenantId,
        () ->
            dsl.deleteFrom(table(name("dataset")))
                .where(field(name("tenant_id"), Long.class).eq(tenantId))
                .execute());
  }

  /**
   * {@code DatasetService.createDataset} 이 남긴 이 테넌트의 감사 로그를 지운다 — {@code
   * audit_log_user_id_fkey}/{@code fk_audit_log_tenant} 에 cascade 가 없어 남겨 두면 user·tenant
   * 삭제가 FK 위반({@code 23503})으로 실패한다. {@code WHERE tenant_id = ?} 근거와 승격 이유는
   * {@link #deleteOwnDatasetRows} 와 같다.
   */
  public static void deleteOwnAuditLogRows(
      DSLContext dsl, TransactionTemplate transactionTemplate, long tenantId) {
    runInTenantTransaction(
        transactionTemplate,
        tenantId,
        () ->
            dsl.deleteFrom(table(name("audit_log")))
                .where(field(name("tenant_id"), Long.class).eq(tenantId))
                .execute());
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
   * <b>{@code provision_tenant_defaults} 가 만드는 행 전부</b>를 FK 순서대로 지운다
   * ({@code role_permission}/{@code user_role}/{@code report_template}/{@code dataset_category} →
   * {@code role}). role 을 참조하는 자식부터 지워야 role 삭제가 FK 에 걸리지 않는다. 세 개의 RBAC
   * 테스트가 각자 복붙하던 것을 모았다 — 특정 테스트가 그중 일부 테이블에 행을 만들지 않았어도,
   * 없는 행을 지우는 DELETE 는 0행으로 끝나 안전하다.
   *
   * <p><b>이 목록은 프로비저닝 함수와 함께 움직여야 한다.</b> V113 이 함수에
   * {@code dataset_category} 시드를 추가했을 때 여기를 같이 고치지 않아, 정리 단계가 그 행을 남기고
   * 이어지는 {@code tenant} 삭제가 FK 로 터졌다(테스트 3건 실패). 이름은 RBAC 이지만 실제 계약은
   * "프로비저닝이 남긴 것을 되돌린다"다 — 함수에 시드 테이블을 추가하면 여기에도 추가한다.
   *
   * <p>호출자가 대상 테넌트 컨텍스트 트랜잭션 안에서 불러야 한다({@link #runInTenantTransaction}).
   */
  public static void deleteRbacCascade(DSLContext dsl, long tenantId) {
    dsl.execute("delete from role_permission where tenant_id = ?", tenantId);
    dsl.execute("delete from user_role where tenant_id = ?", tenantId);
    dsl.execute("delete from report_template where tenant_id = ?", tenantId);
    // V113 의 기본 카테고리 시드. dataset_category 는 tenant FK 를 잡으므로 남기면 tenant 삭제가
    // 터진다. dataset 이 이 카테고리를 참조하고 있으면 그 테스트가 자기 dataset 을 먼저 지워야 한다.
    dsl.execute("delete from dataset_category where tenant_id = ?", tenantId);
    dsl.execute("delete from role where tenant_id = ?", tenantId);
  }

  /**
   * 온톨로지·그래프 8테이블(P2-d)의 테넌트 행을 FK 순서대로 지운다. {@link #deleteRbacCascade} 와
   * 같은 계약이다 — 호출자가 대상 테넌트 컨텍스트 트랜잭션 안에서 부른다.
   *
   * <p><b>{@code where tenant_id = ?} 를 명시하는 이유 — 세 cascade 헬퍼의 공통 근거(정본).</b>
   * RLS 가 이미 현재 테넌트 행만 보여 주지만, 이 프로젝트는 어느 테이블에도 FORCE RLS 를 쓰지
   * 않으므로 테이블 소유 롤({@code app})로 접속하면 정책이 통째로 우회된다. 그때 WHERE 없는
   * DELETE 는 공유 테스트 DB 의 남의 행 — 여기서는 V71/V72/V80 시드 — 까지 지워 무관한 테스트를
   * 전부 무너뜨린다. WHERE 는 그 사고에 대한 안전장치다. 반대로 RLS 대상 테이블이므로
   * <b>테넌트 컨텍스트 안에서</b> 불러야 정책이 켜진 뒤에도 같은 행을 지운다.
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
   * 프로액티브·AI 7테이블(P2-e)의 테넌트 행을 FK 순서대로 지운다. {@link #deleteOntologyGraphCascade}
   * 와 같은 계약이다 — 호출자가 대상 테넌트 컨텍스트 트랜잭션 안에서 부른다.
   *
   * <p>순서가 중요하다: {@code proactive_message} → {@code proactive_job_execution} →
   * {@code metric_snapshot}/{@code anomaly_event} → {@code proactive_job}. 전부 CASCADE FK 지만
   * 명시적으로 지워야 정책이 자식까지 스코프하는지가 정리 단계에서 드러난다.
   *
   * <p>{@code ai_session} 은 {@code "user"} 로의 FK 가 <b>CASCADE 가 아니다</b> — 이 정리가 0행이
   * 되면 뒤이은 {@link #deleteUser} 가 FK 위반으로 요란하게 터진다. 반대로 {@code proactive_job} 은
   * CASCADE 라 사용자 삭제가 조용히 뒤처리를 해 준다. 그래서 이 헬퍼는 "터지지 않았으니 됐다"로
   * 검증할 수 없고, 호출부가 반드시 테넌트 트랜잭션 안에서 불러야 한다.
   *
   * <p>{@code where tenant_id = ?} 를 명시하는 이유는 {@link #deleteOntologyGraphCascade} 의
   * 해당 문단과 같다(V104 도 FORCE RLS 를 쓰지 않는다).
   */
  public static void deleteProactiveAiCascade(DSLContext dsl, long tenantId) {
    dsl.execute("delete from proactive_message where tenant_id = ?", tenantId);
    dsl.execute("delete from proactive_job_execution where tenant_id = ?", tenantId);
    dsl.execute("delete from metric_snapshot where tenant_id = ?", tenantId);
    dsl.execute("delete from anomaly_event where tenant_id = ?", tenantId);
    dsl.execute("delete from proactive_job where tenant_id = ?", tenantId);
    dsl.execute("delete from ai_session where tenant_id = ?", tenantId);
    dsl.execute("delete from ai_inference_cache where tenant_id = ?", tenantId);
  }

  /**
   * P2-f 채널 도메인 정리. 삭제 순서는 FK 역순 {@code notification_outbox} →
   * {@code user_channel_binding} → {@code user_channel_preference} → {@code slack_workspace}.
   *
   * <p><b>왜 필요한가.</b> notification 테스트 패키지에는 정리 자체가 없어서 공유 테스트 DB 의
   * {@code notification_outbox} 에 픽스처 잔재가 수천 행 쌓였다(2026-08-16 실측 3883행). 이 헬퍼는
   * <b>새 누수만</b> 막는 용도다.
   *
   * <p><b>⚠ 반드시 테스트가 직접 만든 테넌트로 부를 것 — {@code DEFAULT_TEST_TENANT_ID}(=1) 로
   * 부르면 안 된다.</b> 위 3883행은 V106 의 고아 폴백이 전부 테넌트 1 로 마감한 것이라(실측:
   * {@code select tenant_id, count(*) ... group by 1} → {@code 1 | 3883}), 테넌트 1 로 부르는 순간
   * <b>다른 세션이 만든 행까지 통째로 지운다</b>. {@code createActiveTenant}/{@code nextTenantId}
   * 로 만든 테넌트만 넘겨라. 기존 테스트를 정리하려면 이 헬퍼가 아니라 그 테스트가 만든 키
   * (user_id, correlation_id 등)로 좁힌 DELETE 를 써야 한다.
   *
   * <p>{@code where tenant_id = ?} 를 명시하는 이유는 {@link #deleteOntologyGraphCascade} 의
   * 해당 문단과 같다.
   *
   * <p>{@code oauth_state} 는 <b>넣지 않았다</b>. RLS 대상이 아니고(V106 [R7]) TTL 만료 삭제 경로가
   * 따로 있어 테넌트 단위 cascade 의 일부로 보기 어렵다 — 필요해지면 별도 헬퍼가 맞다.
   */
  public static void deleteChannelCascade(DSLContext dsl, long tenantId) {
    // 경고를 주석으로만 두면 놓친다 — 기계적으로 막는다. 리터럴 1 은
    // IntegrationTestBase.DEFAULT_TEST_TENANT_ID 값이다(support 패키지에서 그 상수를 참조하면
    // 테스트 기반 클래스와 순환 의존이 생기므로 값을 직접 쓰고 이유를 여기 남긴다).
    if (tenantId == 1L) {
      throw new IllegalArgumentException(
          "deleteChannelCascade 를 기본 테넌트(1)로 부르면 다른 세션이 만든 공유 테스트 DB 의 행까지"
              + " 지운다(2026-08-16 실측: notification_outbox 3883행이 전부 tenant_id=1)."
              + " 테스트가 직접 만든 테넌트로 부르거나, 자기가 만든 키로 좁힌 DELETE 를 쓸 것.");
    }
    dsl.execute("delete from notification_outbox where tenant_id = ?", tenantId);
    dsl.execute("delete from user_channel_binding where tenant_id = ?", tenantId);
    dsl.execute("delete from user_channel_preference where tenant_id = ?", tenantId);
    dsl.execute("delete from slack_workspace where tenant_id = ?", tenantId);
  }

  /**
   * 주어진 correlation 의 outbox 행을 "지금 due" 상태로 당긴다. 호출자는 대상 테넌트 컨텍스트
   * 트랜잭션 안에서 부른다(RLS 대상 테이블).
   *
   * <p><b>왜 필요한가.</b> {@code next_attempt_at} 기본값은 <b>DB 의</b> {@code now()} 인데
   * {@code claimDue} 는 <b>JVM 의</b> {@code OffsetDateTime.now()} 와 비교한다. 컨테이너 DB 시계가
   * 호스트보다 수십 ms 앞서 있으면(2026-08-16 실측 +70ms) 방금 넣은 행이 아직 "미래"라 클레임되지
   * 않아 테스트가 간헐 실패한다. 운영에서는 30초 주기 폴링이라 이 편차가 무해하므로 프로덕션을
   * 고치는 대신 픽스처를 DB 시계 기준으로 당긴다.
   *
   * <p>P2-f 이전에는 이 함정이 보이지 않았다 — 클레임이 전역이라 공유 테스트 DB 의 오래된 적체가
   * 항상 먼저 잡혀 단언이 남의 행으로 통과했기 때문이다.
   */
  public static void makeOutboxRowDue(DSLContext dsl, UUID correlationId) {
    dsl.execute(
        "update notification_outbox set next_attempt_at = now() - interval '1 minute'"
            + " where correlation_id = ?",
        correlationId);
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
   * <p><b>왜 타입이 아니라 SQLSTATE 를 단언하는가(P2-f Task 6 리뷰 m1).</b> {@code
   * DataAccessException} 타입만 보면 <b>무관한 제약 위반으로도 통과</b>한다 — 픽스처가 FK(23503)나
   * 유니크(23505)를 건드리기만 해도 "정책이 거부했다"로 읽힌다. 정책 위반은 {@code 42501}
   * ({@code insufficient_privilege}) 하나뿐이므로 그 값을 못박으면 형태 자체로 안전해진다. 메시지
   * 문자열이 아니라 SQLSTATE 인 이유는 메시지가 PG 버전마다 흔들리기 때문이고, 드라이버 타입
   * ({@code PSQLException})이 아니라 {@link SQLException} 인 이유는 SQLSTATE 만 필요한데 드라이버
   * 클래스에 묶일 이유가 없기 때문이다.
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

    SQLException sqlEx = findSqlException(thrown);
    assertThat((Object) sqlEx)
        .as("%s: SQLException 이 감싸져 있어야 SQLSTATE 를 볼 수 있다", tableName)
        .isNotNull();
    assertThat(sqlEx.getSQLState())
        .as("%s: 42501(정책 위반)이 아니면 FK·유니크 같은 무관한 제약이 거부한 것이다", tableName)
        .isEqualTo("42501");
  }

  /**
   * 예외 체인에서 {@link SQLException} 을 찾는다 — jOOQ/Spring 이 여러 겹으로 감싼다.
   *
   * <p>SQLSTATE 로 단언하려는 테스트가 각자 복붙하던 순회를 여기로 모은다.
   */
  public static SQLException findSqlException(Throwable t) {
    for (Throwable cur = t; cur != null; cur = cur.getCause()) {
      if (cur instanceof SQLException sql) {
        return sql;
      }
      if (cur.getCause() == cur) {
        break;
      }
    }
    return null;
  }

  /** 현재 테넌트 컨텍스트에서 해당 행이 보이는지 확인한다(RLS 적용 결과). */
  public static boolean rowExists(DSLContext dsl, String tableName, String pkColumn, Long pk) {
    return dsl.fetchCount(table(name(tableName)), field(name(pkColumn), Long.class).eq(pk)) > 0;
  }

  // ── 테넌트 스키마 프로비저닝 테스트용 공용 헬퍼(P3-b2 Task 1) ─────────────────
  //
  // TenantSchemaProvisionerTest 가 처음 도입했고, Task 5(DataTableService DROP·REPLACE 경로 감사)가
  // 그대로 재사용한다. data_t{id} 파생이 900_000_xxx 대역에서만 성립하므로(DataSchema 규약), 이
  // 대역 밖에서 프로비저너를 시험하면 삭제 가드(dropSchemasCreatedByThisTest)를 통과하지 못한다.

  /**
   * 스키마 프로비저닝 테스트 전용 테넌트 id 기저를 무작위로 하나 뽑는다.
   *
   * <p><b>왜 고정 리터럴(900_000_001 등)을 쓰면 안 되는가(라운드 1 리뷰 실측).</b> 이 저장소는
   * 여러 워크트리가 같은 공유 test DB 를 동시에 쓴다. 두 워크트리가 같은 테스트 클래스를
   * 동시에 돌리면 고정 id 는 같은 스키마({@code data_t900000003})·같은 롤을 노려, 늦게 온 쪽의
   * {@code ensureExecutorRoleExists} 가 "이미 있다"고 판단해 정리를 건너뛰고, 먼저 끝난 쪽이
   * 상대가 아직 쓰고 있는 스키마를 드롭하는 교차 실패가 재현됐다. 호출하는 테스트 클래스가
   * 이 메서드를 정적 필드로 <b>한 번만</b> 받아 오프셋(+1, +2, ...)을 더해 쓰면, 클래스 안의
   * 테스트끼리는 오프셋으로 구분되고 서로 다른 프로세스(워크트리)는 서로 다른 기저를 뽑으므로
   * 충돌 확률이 사실상 0이 된다.
   *
   * <p>Task 5 도 같은 프로비저닝 테스트 패턴을 재사용하므로 이 헬퍼를 그대로 쓴다.
   *
   * @return 900_000_000 이상 999_900_000 미만의 무작위 값. 오프셋을 더해도(호출부가 보통 한
   *     자릿수 오프셋만 쓴다) 900_000_000~999_999_999 대역과 {@code ^data_t[0-9]+$} 가드 안에
   *     여유 있게 머물도록 상한에 100,000 의 여백을 둔다.
   */
  public static long randomSchemaProvisioningTenantIdBase() {
    return ThreadLocalRandom.current().nextLong(900_000_000L, 999_900_000L);
  }

  /**
   * 지정한 id 로 ACTIVE 테넌트를 만든다. {@link #createActiveTenant} 와 달리 id 를 호출자가
   * 정한다 — 스키마 프로비저닝 테스트는 파생된 스키마명({@code data_t{id}})이 삭제 가드의
   * {@code ^data_t[0-9]+$} 를 통과해야 하므로, 900_000_xxx 대역의 id 를 직접 지정해야 한다.
   * slug 는 id 자체로 고유하므로 별도 접미사가 필요 없다.
   */
  public static void insertActiveTenant(DSLContext dsl, long tenantId) {
    dsl.insertInto(TENANT)
        .set(field(name("id"), Long.class), tenantId)
        .set(field(name("slug"), String.class), "schema-provision-" + tenantId)
        .set(field(name("name"), String.class), "Schema Provision Test " + tenantId)
        .set(field(name("status"), String.class), "ACTIVE")
        .execute();
  }

  /** {@link #dropSchemasCreatedByThisTest} 가 이름을 검증하는 정규식. 테넌트 스키마 명명 규약({@code
   * DataSchema.TENANT_SCHEMA_PREFIX})과 정확히 일치해야 한다. */
  private static final Pattern TENANT_SCHEMA_NAME = Pattern.compile("^data_t[0-9]+$");

  /**
   * 테스트가 만든 테넌트 스키마를 지운다.
   *
   * <p><b>하드 가드(R7):</b> 이름이 {@code ^data_t[0-9]+$} 에 맞지 않으면 드롭하지 않고 {@link
   * IllegalArgumentException} 을 던진다. 이 가드가 없으면 단 한 줄의 실수로 공유 test DB 의
   * {@code data}(그리고 dev·prod 의 기존 테이블)가 사라진다. {@code dropHelperRefusesNonTenantSchemas}
   * 가 이 가드 자체의 비공허성을 변이로 증명한다.
   *
   * @param ownerDsl 스키마 소유자(app) 자격증명으로 연 DSLContext. 런타임 롤(app_tenant)은 스키마
   *     소유자가 아니므로 DROP SCHEMA 권한이 없다 — {@code schemaOwnerDataSource} 로 만들어야 한다.
   */
  public static void dropSchemasCreatedByThisTest(DSLContext ownerDsl, String... schemas) {
    // 검증을 전부 먼저 끝내고 나서 드롭한다(라운드 1 리뷰 nit) — 검증과 드롭을 한 루프에서
    // 섞으면 앞쪽 스키마를 이미 지운 뒤에야 뒤쪽의 이름 위반이 발견돼, 정리가 절반만 되고
    // 예외가 나는 상태가 된다. 전부 검증 → 전부 드롭 순서면 이름이 하나라도 잘못됐을 때
    // 아무것도 지우지 않고 즉시 실패한다(부분 정리보다 안전).
    for (String schema : schemas) {
      if (!TENANT_SCHEMA_NAME.matcher(schema).matches()) {
        throw new IllegalArgumentException(
            "dropSchemasCreatedByThisTest 는 data_t{id} 형태의 이름만 지운다(공유 test DB 의 data"
                + " 스키마 삭제 사고 방지): "
                + schema);
      }
    }
    for (String schema : schemas) {
      ownerDsl.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  /**
   * 지정한 롤이 없으면 최소 권한(NOLOGIN)으로 만든다.
   *
   * <p>신규 테넌트의 파이프라인 실행 롤({@code pipeline_executor_t{id}})은 운영자 절차(#383)로
   * 만들어지므로 test DB 에 미리 없을 수 있다. 프로비저닝 테스트가 권한 부여를 검증하려면
   * 롤이 실재해야 하므로, 없으면 이 헬퍼가 최소 권한으로 만들어 준다.
   *
   * <p>라운드 3 리뷰 N8 로 {@code TenantSchemaProvisionerTest} 의 private 헬퍼에서 여기로
   * 승격했다 — Task 5 가 같은 프로비저닝 테스트 패턴을 그대로 재사용한다.
   *
   * @return 이 메서드가 롤을 새로 만들었으면 {@code true}(호출자가 {@link
   *     #dropRoleIfCreatedByThisTest} 로 정리해야 함). 이미 있었으면 {@code false} — 이 경우
   *     호출자가 만든 것이 아니므로 절대 지우면 안 된다(운영자가 미리 만들어 둔 실행 롤일 수
   *     있다).
   */
  public static boolean ensureRoleExists(DSLContext ownerDsl, String roleName) {
    boolean exists =
        ownerDsl.fetchExists(
            ownerDsl.selectOne().from("pg_roles").where(field("rolname", String.class).eq(roleName)));
    if (exists) {
      return false;
    }
    ownerDsl.execute("CREATE ROLE " + roleName + " NOLOGIN");
    return true;
  }

  /**
   * {@link #ensureRoleExists} 가 <b>이 테스트에서 실제로 만들었을 때만</b> 롤을 지운다.
   *
   * <p>라운드 2 리뷰가 "정리 규율이 테스트마다 다르다"고 지적한 것을 라운드 3 이 다시 잡았다
   * (같은 파일 안에서도 재발) — 플래그 없이 {@code DROP ROLE IF EXISTS} 를 무조건 실행하면,
   * 무작위 id 라 확률은 낮아도 다른 세션이 만든 동명의 롤(원칙적으로 테넌트 id 가 다르면 롤
   * 이름도 다르지만, 운영자가 미리 만들어 둔 실행 롤을 이 테스트가 우연히 재사용한 경우 등)을
   * 지울 수 있다. 헬퍼로 뽑아 세 테스트(그리고 Task 5)가 같은 규율을 강제로 따르게 한다 —
   * "이 테스트를 짤 때마다 플래그를 손으로 잘 챙겨야 한다"가 아니라 시그니처 자체가 강제한다.
   *
   * @param createdByThisTest {@link #ensureRoleExists} 의 반환값을 그대로 넘긴다.
   */
  public static void dropRoleIfCreatedByThisTest(
      DSLContext ownerDsl, String roleName, boolean createdByThisTest) {
    if (createdByThisTest) {
      ownerDsl.execute("DROP ROLE IF EXISTS " + roleName);
    }
  }

  /**
   * 정리 단계들을 <b>서로 독립적으로</b> 실행한다(라운드 1 리뷰 should-fix 5, 라운드 3 리뷰 N8
   * 로 {@code TenantSchemaProvisionerTest} 에서 여기로 승격 — Task 5 가 재사용한다).
   *
   * <p>순차 {@code finally} 블록에서 한 단계가 던지면 뒤따르는 정리가 전부 스킵된다 — 예를 들어
   * 스키마 드롭이 일시적으로 실패하면 테넌트 행 삭제가 안 불려 고정/무작위 id 가 영구히 남고,
   * 다음 실행의 픽스처 삽입이 중복 키로 깨져 수동 DB 수술 전까지 복구되지 않는다. 각 단계를
   * 독립적으로 실행해 하나가 실패해도 나머지가 최대한 정리되게 하고, 실패는 모아서 마지막에
   * 하나로 알린다(억제된 예외로 전부 보존).
   *
   * <p>{@code RuntimeException} 이 아니라 {@code Throwable} 을 잡는다(라운드 2 리뷰 nit) —
   * 정리 단계 안에서 {@code AssertionError}(단언 실패는 {@code Error} 계층이다)가 나면
   * {@code RuntimeException} 만 잡던 버전은 그 즉시 나머지 단계를 스킵했다. 이 메서드의 목적
   * 자체가 "한 단계가 어떻게 실패하든 나머지는 최대한 정리한다"이므로 예외 계층을 좁힐 이유가
   * 없다.
   */
  public static void cleanupAll(Runnable... steps) {
    RuntimeException combined = null;
    for (Runnable step : steps) {
      try {
        step.run();
      } catch (Throwable e) {
        if (combined == null) {
          combined = new IllegalStateException("정리 단계 중 일부가 실패했다", e);
        } else {
          combined.addSuppressed(e);
        }
      }
    }
    if (combined != null) {
      throw combined;
    }
  }
}
