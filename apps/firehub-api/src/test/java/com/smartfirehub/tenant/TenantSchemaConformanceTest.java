package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 테넌트 스코프 테이블의 스키마 형태를 <b>카탈로그에서 집합을 유도해</b> 전수 검사한다.
 *
 * <p>왜 필요한가: 지금까지 밴드마다 손으로 유지하는 테이블 목록 테스트가 4벌 쌓였고
 * ({@code DatasetDomainColumnTest}, {@code PipelineDomainColumnTest}, {@code RbacAuditReportSchemaTest},
 * {@code OntologyGraphSchemaTest}) 그 4벌이 덮는 31테이블 밖에 8테이블이 무커버로 남아 있었다
 * ({@code chart}, {@code dashboard}, {@code dashboard_widget}, {@code document_chunk},
 * {@code document_file}, {@code query_history}, {@code saved_query}, {@code tenant_canary}).
 * 이 테스트는 목록을 손으로 들지 않으므로 새 밴드가 테이블을 추가해도 자동으로 덮는다.
 *
 * <p><b>기존 4벌은 지우지 않는다.</b> 그쪽은 "이 인덱스는 tenant_id 를 포함하면 안 된다" 같은
 * <i>결정</i>을 못박는 단언이라 균일 검사로 대체되지 않는다. 이 테스트는 균일한 부분만 가져간다.
 *
 * <p><b>fail-open 방지가 이 테스트의 핵심이다.</b> {@code relrowsecurity = true} 에서 집합을
 * 유도하면 "RLS 켜기를 잊은 테이블"은 애초에 집합에 들어오지 않아 조용히 통과한다. 그래서
 * {@link #tablesWithTenantColumnMustHaveRlsEnabled()} 역방향 단언을 함께 둔다 — 이게 없으면
 * 이 테스트는 다음 밴드에서 무의미해진다.
 */
class TenantSchemaConformanceTest extends IntegrationTestBase {

  /**
   * RLS 활성 테이블 집합의 하한. 카탈로그 쿼리가 망가져 빈 집합을 돌려주면 모든 루프 단언이
   * 공허하게 통과하므로, 최소 개수를 못박아 자기검증한다. 2026-08-15 실측 39테이블 — 밴드가
   * 늘어나도 편집할 필요가 없도록 {@code >=} 로 비교한다.
   */
  private static final int MIN_RLS_TABLE_COUNT = 39;

  /**
   * {@code tenant_id} 가 nullable 이어도 되는 테이블. 로그인·회원가입 감사는 <b>테넌트를 고르기
   * 전</b>에 기록되므로 테넌트를 붙일 수 없는 행이 존재한다 ({@code RbacAuditReportSchemaTest} 근거).
   * 그 대가로 정책이 형태 (b) 여야 한다 — 아래 {@link #NON_STANDARD_POLICY_FORM_TABLES} 참조.
   */
  private static final Set<String> NULLABLE_TENANT_ID_TABLES = Set.of("audit_log");

  /**
   * 정책이 표준 형태 (a)({@code tenant_id = GUC})가 아니어도 되는 테이블. {@code audit_log} 는
   * NULL tenant_id 행을 함께 다뤄야 해서 {@code NOT (tenant_id IS DISTINCT FROM GUC)} 형태다.
   * 이 형태는 GUC 가 비면 NULL-vs-NULL 이 참이 되어 <b>fail-open</b> 이므로 여기 말고는 금지다.
   */
  private static final Set<String> NON_STANDARD_POLICY_FORM_TABLES = Set.of("audit_log");

  /**
   * {@code tenant(id)} FK 가 없어도 되는 테이블. {@code tenant_canary} 는 RLS 격리 자체를 검증하는
   * 테스트 전용 카나리라 존재하지 않는 테넌트 id 로도 행을 심을 수 있어야 한다
   * ({@code TenantRlsTestSupport.TENANT_CANARY}).
   */
  private static final Set<String> NO_TENANT_FK_TABLES = Set.of("tenant_canary");

  /**
   * 정책명이 {@code <table>_tenant_isolation} 규칙을 벗어나는 테이블. {@code tenant_canary} 는
   * {@code tenant_canary_isolation} 이다(테이블명이 이미 tenant 로 시작해 중복을 피한 것).
   */
  private static final Set<String> NON_STANDARD_POLICY_NAME_TABLES = Set.of("tenant_canary");

  /** {@code tenant_id} 컬럼을 갖고도 RLS 를 끈 것이 <b>의도된</b> 전역 테이블. */
  private static final Set<String> RLS_DISABLED_ALLOWLIST = Set.of("membership");

  /**
   * 밴드 진행 중 임시 허용 목록 — <b>커밋 시점에 반드시 비어 있어야 한다.</b>
   *
   * <p>왜 있는가: 이 프로젝트는 컬럼 마이그레이션과 정책 마이그레이션을 나눠 넣는다(V101/V102,
   * P2-e 의 V103/V104). 그 중간 커밋에서는 {@code tenant_id} 는 있는데 RLS 는 아직 꺼진 테이블이
   * 생겨 역방향 단언이 빨개진다. 그때 <b>영구 허용목록에 밀어 넣지 말고</b> 여기에 적어라 —
   * 정책 마이그레이션이 들어오면 아래 staleness 가드가 다시 빨개져서 지우지 않고는 밴드를
   * 끝낼 수 없다. 구멍이 밴드 밖으로 새어 나가지 못하게 하는 장치다.
   *
   * <p>P2-e 기록: V103 이 7테이블을 여기 올렸고 V104 가 정책을 켜면서 다시 비웠다 — 장치가
   * 설계대로 한 바퀴 돌았다. <b>영구 허용목록으로 옮기는 것은 이 장치를 우회하는 것이다.</b>
   */
  private static final Set<String> IN_FLIGHT_RLS_PENDING = Set.of();

  /** RLS 활성 테이블 집합. 파티션 테이블('p')도 포함해야 뒤늦게 생긴 구멍을 놓치지 않는다. */
  private static final String RLS_TABLES_CTE =
      "with rls as ("
          + " select c.oid, c.relname, c.relforcerowsecurity from pg_class c"
          + " join pg_namespace n on n.oid = c.relnamespace"
          + " where n.nspname = 'public' and c.relkind in ('r','p') and c.relrowsecurity)";

  @Autowired private DSLContext dsl;

  /** 카탈로그 쿼리 첫 컬럼(테이블명)을 문자열 목록으로 뽑는다. */
  private List<String> tableNames(String sql) {
    return dsl.fetch(sql).getValues(0, String.class);
  }

  @Test
  @DisplayName("RLS 활성 테이블 집합이 비어 있지 않다 — 이후 전수 단언의 자기검증")
  void rlsTableSetIsNotVacuous() {
    List<String> tables = tableNames(RLS_TABLES_CTE + " select relname from rls");
    assertThat(tables)
        .as("RLS 활성 테이블 — 이 집합이 비면 아래 전수 단언이 전부 공허하게 통과한다")
        .hasSizeGreaterThanOrEqualTo(MIN_RLS_TABLE_COUNT);
  }

  @Test
  @DisplayName("RLS 활성 테이블은 전부 tenant_id 컬럼을 갖는다")
  void rlsTablesHaveTenantColumn() {
    List<String> offenders =
        tableNames(
            RLS_TABLES_CTE
                + " select r.relname from rls r where not exists ("
                + "   select 1 from pg_attribute a where a.attrelid = r.oid"
                + "   and a.attname = 'tenant_id' and a.attnum > 0 and not a.attisdropped)");
    assertThat(offenders).as("RLS 를 켰지만 tenant_id 컬럼이 없는 테이블").isEmpty();
  }

  @Test
  @DisplayName("tenant_id 는 NOT NULL — audit_log 만 예외이고, 그 예외도 실제로 nullable 이어야 한다")
  void tenantColumnIsNotNullExceptAuditLog() {
    List<String> nullable =
        tableNames(
            RLS_TABLES_CTE
                + " select r.relname from rls r"
                + " join pg_attribute a on a.attrelid = r.oid and a.attname = 'tenant_id'"
                + " where not a.attnotnull");
    // containsExactlyInAnyOrder 로 두는 이유: 예외를 "검사 안 함" 으로 두면 예외 자체가 fail-open
    // 이 된다. audit_log 가 실제로 nullable 인 것까지 여기서 못박는다.
    assertThat(nullable)
        .as("tenant_id 가 nullable 인 RLS 테이블 (허용: %s)", NULLABLE_TENANT_ID_TABLES)
        .containsExactlyInAnyOrderElementsOf(NULLABLE_TENANT_ID_TABLES);
  }

  @Test
  @DisplayName("tenant_id DEFAULT 가 app.tenant_id GUC 를 읽는다")
  void tenantColumnDefaultReadsGuc() {
    // 앱 코드는 tenant_id 를 직접 넣지 않는다. DEFAULT 가 GUC 를 읽지 않으면 모든 INSERT 가
    // NOT NULL 위반으로 깨진다.
    List<String> offenders =
        tableNames(
            RLS_TABLES_CTE
                + " select r.relname from rls r"
                + " join pg_attribute a on a.attrelid = r.oid and a.attname = 'tenant_id'"
                + " left join pg_attrdef d on d.adrelid = r.oid and d.adnum = a.attnum"
                + " where coalesce(pg_get_expr(d.adbin, d.adrelid), '')"
                + "   not like '%current_setting%app.tenant_id%'");
    assertThat(offenders).as("tenant_id DEFAULT 가 GUC 를 읽지 않는 테이블").isEmpty();
  }

  @Test
  @DisplayName("FORCE ROW LEVEL SECURITY 는 어느 테이블에서도 켜져 있지 않다")
  void forceRowLevelSecurityIsNeverEnabled() {
    // FORCE 를 켜면 테이블 소유자에게까지 정책이 적용돼 SECURITY DEFINER 우회(V95 트리거 해석,
    // V98 프로비저닝)가 0행을 받아 전멸한다. 실수로 켜지는 것을 카탈로그로 못박는다.
    List<String> offenders =
        tableNames(RLS_TABLES_CTE + " select relname from rls where relforcerowsecurity");
    assertThat(offenders).as("FORCE RLS 가 켜진 테이블").isEmpty();
  }

  @Test
  @DisplayName("RLS 활성 테이블은 cmd=ALL 정책을 정확히 하나씩 갖는다")
  void rlsTablesHaveExactlyOneAllCommandPolicy() {
    List<String> offenders =
        tableNames(
            RLS_TABLES_CTE
                + " select r.relname from rls r where"
                + " (select count(*) from pg_policies p"
                + "   where p.schemaname = 'public' and p.tablename = r.relname) <> 1"
                + " or exists (select 1 from pg_policies p"
                + "   where p.schemaname = 'public' and p.tablename = r.relname and p.cmd <> 'ALL')");
    assertThat(offenders).as("정책이 1개가 아니거나 cmd 가 ALL 이 아닌 테이블").isEmpty();
  }

  @Test
  @DisplayName("정책명은 <table>_tenant_isolation — tenant_canary 만 예외이고 이름을 못박는다")
  void policyNamesFollowConvention() {
    List<String> offenders =
        tableNames(
            RLS_TABLES_CTE
                + " select r.relname from rls r where not exists ("
                + "   select 1 from pg_policies p where p.schemaname = 'public'"
                + "   and p.tablename = r.relname"
                + "   and p.policyname = r.relname || '_tenant_isolation')");
    assertThat(offenders)
        .as("정책명 규칙을 벗어난 테이블 (허용: %s)", NON_STANDARD_POLICY_NAME_TABLES)
        .containsExactlyInAnyOrderElementsOf(NON_STANDARD_POLICY_NAME_TABLES);

    // 예외를 건너뛰지 않고 대체 형태를 못박는다.
    assertThat(
            tableNames(
                "select policyname from pg_policies"
                    + " where schemaname = 'public' and tablename = 'tenant_canary'"))
        .as("tenant_canary 의 정책명")
        .containsExactly("tenant_canary_isolation");
  }

  @Test
  @DisplayName("tenant(id) FK 가 정확히 하나 — tenant_canary 만 예외이고 0개임을 못박는다")
  void tenantForeignKeyExistsExceptCanary() {
    List<String> offenders =
        tableNames(
            RLS_TABLES_CTE
                + " select r.relname from rls r where ("
                + "   select count(*) from pg_constraint k"
                + "   where k.conrelid = r.oid and k.contype = 'f'"
                + "   and k.confrelid = 'tenant'::regclass"
                + "   and k.conkey = array[(select a.attnum from pg_attribute a"
                + "     where a.attrelid = r.oid and a.attname = 'tenant_id')]) <> 1");
    assertThat(offenders)
        .as("tenant(id) FK 가 1개가 아닌 테이블 (허용: %s)", NO_TENANT_FK_TABLES)
        .containsExactlyInAnyOrderElementsOf(NO_TENANT_FK_TABLES);
  }

  @Test
  @DisplayName("정책 USING/WITH CHECK 가 둘 다 app.tenant_id 를 참조한다")
  void policiesReferenceTenantGucOnBothSides() {
    // WITH CHECK 가 없으면 읽기는 막히지만 남의 테넌트에 행을 심을 수 있다.
    List<String> offenders =
        tableNames(
            RLS_TABLES_CTE
                + " select distinct r.relname from rls r"
                + " join pg_policies p on p.schemaname = 'public' and p.tablename = r.relname"
                + " where coalesce(p.qual, '') not like '%app.tenant_id%'"
                + " or coalesce(p.with_check, '') not like '%app.tenant_id%'");
    assertThat(offenders).as("USING/WITH CHECK 가 GUC 를 참조하지 않는 테이블").isEmpty();
  }

  @Test
  @DisplayName("표준 형태 (a) 를 긍정적으로 못박는다 — tenant_id = GUC 등식이어야 한다")
  void standardPoliciesAreEqualityAgainstTheGuc() {
    // 위의 두 테스트는 부정형이라 빠져나갈 틈이 있다: GUC 를 참조하되(테스트 224 통과)
    // DISTINCT FROM 이 아닌(테스트 238 통과) 형태 — 예컨대 하위질의나 OR 절이 섞인 정책은
    // 전수 검사를 그대로 통과한다. 이 테스트의 존재 이유가 "형태 균일성"이므로 등식 자체를 못박는다.
    //
    // audit_log 는 의도적으로 형태 (b) 라 제외한다. 그쪽은 테스트 238 이 형태를 따로 못박는다.
    List<String> offenders =
        tableNames(
            RLS_TABLES_CTE
                + " select distinct r.relname from rls r"
                + " join pg_policies p on p.schemaname = 'public' and p.tablename = r.relname"
                + " where r.relname <> 'audit_log'"
                + " and (coalesce(p.qual, '') not like '%tenant_id = %app.tenant_id%'"
                + " or coalesce(p.with_check, '') not like '%tenant_id = %app.tenant_id%')");
    assertThat(offenders).as("표준 형태 (a) 등식이 아닌 테이블 (audit_log 제외)").isEmpty();
  }

  @Test
  @DisplayName("DISTINCT FROM 형태(fail-open)는 audit_log 에서만 쓰이고, audit_log 는 실제로 그 형태다")
  void onlyAuditLogUsesNullTolerantPolicyForm() {
    // 금지 부분 문자열은 'IS NOT DISTINCT FROM' 이 아니라 'DISTINCT FROM' 이다.
    // 실제 카탈로그 텍스트가 NOT (tenant_id IS DISTINCT FROM ...) 이라서, 'IS NOT DISTINCT FROM'
    // 으로 검사하면 audit_log 를 포함해 모든 테이블에서 공허하게 통과한다.
    List<String> offenders =
        tableNames(
            RLS_TABLES_CTE
                + " select distinct r.relname from rls r"
                + " join pg_policies p on p.schemaname = 'public' and p.tablename = r.relname"
                + " where coalesce(p.qual, '') like '%DISTINCT FROM%'"
                + " or coalesce(p.with_check, '') like '%DISTINCT FROM%'");
    assertThat(offenders)
        .as("NULL 관용 형태를 쓰는 테이블 (허용: %s)", NON_STANDARD_POLICY_FORM_TABLES)
        .containsExactlyInAnyOrderElementsOf(NON_STANDARD_POLICY_FORM_TABLES);

    // 예외의 대체 형태를 양쪽 모두에 대해 못박는다 — 한쪽만 관용이면 읽기·쓰기 비대칭이 된다.
    List<String> auditQuals =
        tableNames(
            "select qual from pg_policies where schemaname = 'public'"
                + " and tablename = 'audit_log'");
    assertThat(auditQuals).as("audit_log 정책 USING").hasSize(1);
    assertThat(auditQuals.get(0))
        .as("audit_log 정책 USING 형태")
        .contains("IS DISTINCT FROM")
        .contains("app.tenant_id");

    List<String> auditWithChecks =
        tableNames(
            "select with_check from pg_policies where schemaname = 'public'"
                + " and tablename = 'audit_log'");
    assertThat(auditWithChecks).as("audit_log 정책 WITH CHECK").hasSize(1);
    assertThat(auditWithChecks.get(0))
        .as("audit_log 정책 WITH CHECK 형태 — 없으면 남의 테넌트에 감사 로그를 심을 수 있다")
        .contains("IS DISTINCT FROM")
        .contains("app.tenant_id");
  }

  @Test
  @DisplayName("역방향 — tenant_id 컬럼이 있는데 RLS 가 꺼진 테이블은 허용목록 외 0건")
  void tablesWithTenantColumnMustHaveRlsEnabled() {
    // 이 테스트가 없으면 위의 전수 단언들은 "RLS 켜기를 잊은 테이블"에 대해 fail-open 이다
    // (집합 자체를 relrowsecurity 에서 유도하므로 잊힌 테이블은 검사 대상에 들어오지도 않는다).
    List<String> rlsDisabledWithTenantColumn =
        tableNames(
            "select c.relname from pg_class c"
                + " join pg_namespace n on n.oid = c.relnamespace"
                + " join pg_attribute a on a.attrelid = c.oid and a.attname = 'tenant_id'"
                + "   and a.attnum > 0 and not a.attisdropped"
                + " where n.nspname = 'public' and c.relkind in ('r','p')"
                + "   and not c.relrowsecurity");

    assertThat(rlsDisabledWithTenantColumn)
        .as(
            "tenant_id 가 있는데 RLS 가 꺼진 테이블 (영구 허용: %s / 밴드 중 임시: %s)",
            RLS_DISABLED_ALLOWLIST, IN_FLIGHT_RLS_PENDING)
        .isSubsetOf(union(RLS_DISABLED_ALLOWLIST, IN_FLIGHT_RLS_PENDING));

    // staleness 가드 — 두 목록에 **똑같이** 적용한다.
    // 임시 목록만 강제하면(초기 구현) 영구 목록이 무방비가 된다: 밴드 담당자가 신규 테이블을
    // 임시 대신 영구 목록에 밀어 넣는 순간 안전망이 통째로 사라지고, 주석의 경고만 남는다.
    // 여기에 영구 목록까지 넣으면 어느 쪽에 넣든 RLS 를 켠 뒤 지우지 않으면 반드시 빨개진다.
    //
    // 이 단언은 역방향 쿼리의 자기검증도 겸한다 — membership 이 영구 목록에 있으므로,
    // 쿼리가 망가져 빈 결과를 내면 여기서 잡힌다. 망가진 쿼리 위에서는 위의 isSubsetOf 가
    // 공허하게 참이 되기 때문에 이 자기검증이 없으면 전체가 무의미해진다.
    assertThat(rlsDisabledWithTenantColumn)
        .as(
            "허용목록에 남았지만 이미 RLS 가 켜진 항목 — 목록에서 지워라"
                + " (동시에 역방향 쿼리 자기검증: membership 이 반드시 잡혀야 한다)")
        .containsAll(union(RLS_DISABLED_ALLOWLIST, IN_FLIGHT_RLS_PENDING));
  }

  private static Set<String> union(Set<String> a, Set<String> b) {
    return Stream.concat(a.stream(), b.stream()).collect(Collectors.toUnmodifiableSet());
  }
}
