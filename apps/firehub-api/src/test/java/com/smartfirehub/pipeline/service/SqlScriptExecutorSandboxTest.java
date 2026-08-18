package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.global.config.TenantPipelineDataSourceRegistry;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.MissingTenantScopeException;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantPipelineRole;
import com.smartfirehub.global.tenant.TenantSchemaProvisioner;
import com.smartfirehub.pipeline.exception.ScriptExecutionException;
import com.smartfirehub.pipeline.exception.UnsafeSqlException;
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
import org.springframework.beans.factory.annotation.Value;

class SqlScriptExecutorSandboxTest extends IntegrationTestBase {

  @Autowired private SqlScriptExecutor sqlScriptExecutor;

  @Autowired
  @Qualifier("pipelineDslContext")
  private DSLContext pipelineDsl;

  // ── P3-b2 T4: search_path 조립 지점 실측 회귀 가드에서 쓰는 필드(라운드 2 리뷰 NIT-3 —
  // 테스트 메서드들 사이에 흩어져 있던 것을 클래스 상단으로 모았다) ────────────────────────

  private static final long TENANT_BASE = TenantRlsTestSupport.randomSchemaProvisioningTenantIdBase();

  @Autowired private TenantSchemaProvisioner provisioner;

  @Autowired
  @Qualifier("schemaOwnerDataSource")
  private DataSource schemaOwnerDataSource;

  /** 롤 비밀번호 파생 HMAC 키 — {@code DataSchemaGrantIsolationTest} 와 같은 이유로 하드코딩하지 않는다. */
  @Value("${app.pipeline.role-password-secret}")
  private String rolePasswordSecret;

  private DSLContext ownerDsl() {
    return DSL.using(schemaOwnerDataSource, SQLDialect.POSTGRES);
  }

  @Test
  void execute_selectFromDataSchema_succeeds() {
    // pipeline_executor는 data 스키마에서 SELECT 가능. 여기서는 search_path=data 영구 설정과 함께
    // 표준 카탈로그 함수 fnAndArg 없이도 동작함을 확인하기 위해 pg_typeof로 상수 SELECT를 실행한다.
    // SELECT-only(테이블 참조 없음)는 AST 검증을 통과한다.
    assertThatCode(() -> sqlScriptExecutor.execute("SELECT 1")).doesNotThrowAnyException();
  }

  @Test
  void execute_dropTable_blockedByValidator() {
    // AST 검증: 비-DML(Drop)은 차단된다 (#136)
    assertThatThrownBy(() -> sqlScriptExecutor.execute("DROP TABLE IF EXISTS data.nonexistent"))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessageContaining("허용되지 않는 SQL 형태");
  }

  @Test
  void execute_createTablePublic_blockedByValidator() {
    // AST 검증: 비-DML(Create)은 차단된다
    assertThatThrownBy(() -> sqlScriptExecutor.execute("CREATE TABLE public.hack_test (id BIGINT)"))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessageContaining("허용되지 않는 SQL 형태");
  }

  @Test
  void execute_setRoleApp_blockedByValidator() {
    // AST 검증: 멀티 스테이트먼트 + SET ROLE(비-DML)은 차단된다
    assertThatThrownBy(() -> sqlScriptExecutor.execute("SET ROLE app; SELECT 1"))
        .isInstanceOf(UnsafeSqlException.class);
  }

  @Test
  void execute_createExtension_blockedByValidator() {
    // AST 검증: CREATE EXTENSION은 비-DML로 차단된다
    assertThatThrownBy(() -> sqlScriptExecutor.execute("CREATE EXTENSION dblink"))
        .isInstanceOf(UnsafeSqlException.class);
  }

  @Test
  void execute_resetRole_blockedByValidator() {
    assertThatThrownBy(() -> sqlScriptExecutor.execute("RESET ROLE; SELECT 1"))
        .isInstanceOf(UnsafeSqlException.class);
  }

  @Test
  void execute_selectFromInformationSchema_blockedByValidator() {
    // AST 검증: data 외 스키마 참조 차단 (정보 스키마 노출 방지)
    assertThatThrownBy(
            () ->
                sqlScriptExecutor.execute(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = 'data'"))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessageContaining("data");
  }

  @Test
  void pipelineDslContext_usesCorrectUser() {
    // pipeline_executor가 연결한 사용자인지 확인. 이 빈은 R5 에 따라 그대로 남아 있다(dev·prod 의
    // 현행 경로) — SqlScriptExecutor 가 더 이상 이 빈을 쓰지 않는다는 사실은 아래 테넌트 롤 테스트가
    // 고정한다.
    String currentUser = pipelineDsl.fetch("SELECT current_user").get(0).get(0, String.class);
    assertThat(currentUser).isEqualTo("pipeline_executor");
  }

  // ── P3-b1 Task 3: 테넌트별 롤·명시적 search_path 배선 ─────────────────────────

  /**
   * 관측용 가드 테이블. <b>컬럼 DEFAULT 로</b> 실행 세션의 {@code current_user} 와 {@code search_path}
   * 를 기록한다 — 이게 이 테스트의 핵심 장치다.
   *
   * <p>왜 DEFAULT 인가: 프로덕션 경로({@link SqlScriptExecutor#execute})가 실행하는 SQL 은 {@code
   * SqlValidator} 를 먼저 통과해야 하고, 그 검증기는 {@code current_setting} 같은 GUC 조회 함수를
   * 차단한다(#385). 반면 컬럼 DEFAULT 는 <b>서버가 INSERT 시점에</b> 평가하므로 사용자 SQL 에 그
   * 함수를 적을 필요가 없다 — 검증기 정책을 건드리지 않고 실행 세션의 신원·스키마를 관측할 수 있다.
   */
  private static final String GUARD_TABLE = "data.p3b_sql_exec_guard";

  @Autowired private TenantPipelineDataSourceRegistry tenantPipelineDataSources;

  /** 메인 애플리케이션 커넥션({@code app_tenant}) — 가드 테이블 DDL·검증 조회용. */
  @Autowired private DSLContext dsl;

  /**
   * 가드 테이블을 만들고 테넌트 1 파이프라인 롤에만 명시적으로 권한을 준다.
   *
   * <p>DDL 은 트랜잭션 밖(autocommit)에서 실행돼야 다른 커넥션(테넌트 풀)에서 보인다. 이 클래스는
   * 클래스 레벨 {@code @Transactional} 을 쓰지 않으므로 그대로 커밋된다 — 공유 test DB 이므로
   * <b>이 테스트가 만든 것만</b> 이름 접두어로 구분해 지운다.
   *
   * <p><b>{@code @BeforeEach} 가 아니라 필요한 테스트에서만 호출한다.</b> 공유 test DB 에 매 테스트마다
   * DDL 을 걸면(이 클래스는 11건) 다른 워크트리 세션과 부딪히는 플레이크 창이 그만큼 넓어진다 —
   * 이 저장소에서 이미 겪은 실패 형태다. 정리는 {@code @AfterEach} 가 무조건 하므로(IF EXISTS) 남지 않는다.
   */
  private void createGuardTable() {
    dropGuardTable();
    dsl.execute(
        "CREATE TABLE "
            + GUARD_TABLE
            + " (n INT, who TEXT DEFAULT current_user,"
            + " sp TEXT DEFAULT current_setting('search_path'))");
    // 기본 권한(ALTER DEFAULT PRIVILEGES)에 의존하지 않고 명시적으로 준다 — 어떤 롤이 무엇을 갖는지
    // 테스트를 읽는 사람이 바로 알 수 있어야 한다.
    dsl.execute("GRANT INSERT, SELECT ON " + GUARD_TABLE + " TO pipeline_executor_t1");
  }

  @AfterEach
  void dropGuardTable() {
    dsl.execute("DROP TABLE IF EXISTS " + GUARD_TABLE);
  }

  @Test
  void execute_runsAsTenantPipelineRole_andSetsDataSearchPath() {
    createGuardTable();

    // 프로덕션 경로로 INSERT 를 실행한다. 검증기(strict)를 통과하는 최소 DML 이다.
    sqlScriptExecutor.execute("INSERT INTO " + GUARD_TABLE + " (n) VALUES (1)");

    var row = dsl.fetch("SELECT who, sp FROM " + GUARD_TABLE).get(0);

    // (1) 접속 신원이 공용 pipeline_executor 가 아니라 테넌트 1 전용 롤이다 — dslForWithoutLease(tenantId) 배선
    //     을 단일 pipelineDsl 로 되돌리면 이 단언이 깨진다(변이 테스트 대상).
    assertThat(row.get("who", String.class)).isEqualTo("pipeline_executor_t1");
    // (2) 실행 세션의 search_path 가 DataSchema.current() 와 일치한다.
    //     ⚠ 오늘은 롤 레벨 기본값도 같은 값이라 이 단언만으로 "명시 SET 이 실행됐다"를 증명하지는
    //     못한다(물리 스키마가 data 하나뿐 — P3-b1 R8/R5). 명시 실행 여부는 아래
    //     tenantPool_setLocalSearchPath_isSessionSourced 가 pg_settings.source 로 구분한다.
    assertThat(row.get("sp", String.class)).isEqualTo(DataSchema.current());
  }

  @Test
  void tenantPool_setLocalSearchPath_isSessionSourced() {
    // 명시적 SET LOCAL 이 "롤 기본값과 우연히 같은 값" 이 아니라 실제로 세션에 적용된 설정임을
    // pg_settings.source 로 구분한다. 롤 레벨 ALTER ROLE ... IN DATABASE 설정은 source='database',
    // 세션에서 SET 한 값은 source='session' 이다.
    tenantPipelineDataSources.withTenantDsl(
        DEFAULT_TEST_TENANT_ID,
        leasedDsl -> {
          leasedDsl.transaction(
            cfg -> {
              cfg.dsl().execute("SET LOCAL search_path = '" + DataSchema.current() + "'");
              var row =
                  cfg.dsl()
                      .fetch("SELECT setting, source FROM pg_settings WHERE name = 'search_path'")
                      .get(0);
              assertThat(row.get("setting", String.class)).isEqualTo(DataSchema.current());
              assertThat(row.get("source", String.class)).isEqualTo("session");
            });
          return null;
        });
  }

  /**
   * 런타임 SQL 실패(검증기는 통과, DB 가 거부)가 {@code ScriptExecutionException} 으로 감싸지면서
   * <b>원인 메시지를 잃지 않는지</b> 고정한다.
   *
   * <p>왜 필요한가: 이 경로는 이제 {@code SET LOCAL search_path} 를 유효하게 만들기 위해 스크립트를
   * jOOQ 트랜잭션으로 감싼다. 기존 실패 테스트는 전부 <b>검증기 단계</b>에서 끝나 트랜잭션에 진입조차
   * 하지 않으므로, 트랜잭션 경계를 지나온 예외의 메시지가 파이프라인 실행 로그로 그대로 노출되는지는
   * 아무도 보고 있지 않았다. {@code PipelineAsyncRunner} 가 이 메시지를 사용자에게 보여 준다.
   */
  @Test
  void execute_runtimeSqlFailure_wrapsWithUnderlyingCause() {
    assertThatThrownBy(
            () -> sqlScriptExecutor.execute("INSERT INTO data.p3b_absent_table (n) VALUES (1)"))
        .isInstanceOf(ScriptExecutionException.class)
        // 원인 텍스트(관계 없음 = SQLSTATE 42P01)가 살아 있어야 사용자가 원인을 알 수 있다.
        .hasMessageContaining("p3b_absent_table");
  }

  /**
   * 테넌트가 없으면 조용히 기본 스키마·공용 롤로 떨어지지 않고 즉시 거부한다.
   *
   * <p><b>어디서 던지는지까지 단언하는 이유(코드리뷰 지적 6).</b> 예외 <i>클래스</i>만 단언하면 이
   * 테스트는 공허하다 — {@code execute()} 의 첫 문장인 {@code sqlValidator.validate(...)} 가
   * {@code allowedSchema()} → {@code DataSchema.current()} 를 거치며 이미 같은 예외를 던지므로,
   * {@code SqlScriptExecutor} 의 {@code TenantContext.require(...)} 를 **지워도 초록으로 남는다**.
   *
   * <p>그래서 메시지로 <b>먼저 걸리는 지점이 검증기</b>임을 고정한다({@code TenantContext.require} 는
   * 호출 문맥 문자열을 예외 메시지에 싣는다: 검증기 경로는 "data 스키마 식별자 해석",
   * 실행기 경로는 "파이프라인 SQL 실행"). 이 순서가 바뀌면 빨개져 재검토를 강제한다.
   *
   * <p>덧붙여 {@code SqlScriptExecutor} 의 {@code require} 는 <b>2차 가드가 아니라 테넌트 id 취득</b>
   * 이다(풀을 고르는 데 값이 필요하다) — 이 경로의 fail-closed 는 검증기가 담당한다.
   */
  @Test
  void execute_withoutTenantContext_failsClosedAtValidator() {
    TenantContext.clear();
    assertThatThrownBy(() -> sqlScriptExecutor.execute("SELECT 1"))
        .isInstanceOf(MissingTenantScopeException.class)
        .hasMessageContaining("data 스키마 식별자 해석");
  }

  // ── P3-b2 T4: search_path 조립 지점 실측 회귀 가드(접미사 붙은 테넌트) ────────────────────
  //
  // 위 execute_runsAsTenantPipelineRole_andSetsDataSearchPath 는 테넌트 1(물리 스키마 data,
  // 접미사 없음)로만 돈다 — SET LOCAL search_path = '" + DataSchema.current() + "'"(인용 있음,
  // 콤마 목록 없음) 조립이 숫자 접미사가 붙은 스키마(data_t{id})에서도 실제로 그 스키마의
  // 테이블을 해석하는지는 이 클래스의 기존 테스트 어디도 증명하지 않는다.
  //
  // Task 4 의 psql 직접 실측(작은따옴표 인용 스키마명이 숫자를 포함해도 identical 하게 해석됨)
  // 을 실제 프로덕션 경로(jOOQ + 테넌트별 커넥션 풀)로 한 번 더 확인한다 — 무변경 판정의 근거를
  // 코드로도 고정한다.
  //
  // 클래스 레벨 @Transactional 을 쓰지 않는 이 파일의 관례를 그대로 따른다 — 새 테넌트 롤로
  // 로그인하려면 그 롤·스키마·권한이 커밋돼 있어야 하고(별도 커넥션이 봐야 하므로), 클래스
  // 레벨 트랜잭션 안에 있으면 롤백돼 보이지 않는다. 이 절에서 쓰는 필드(TENANT_BASE·
  // provisioner·schemaOwnerDataSource·rolePasswordSecret)는 클래스 상단으로 옮겨 뒀다.

  @Test
  void execute_runsAsTenantPipelineRole_andSetsDataSearchPath_forSuffixedTenant() {
    long tenantId = TENANT_BASE + 1;
    TenantRlsTestSupport.insertActiveTenant(dsl, tenantId);
    String schema = TenantContext.runScopedGet(tenantId, DataSchema::current); // "data_t{tenantId}"
    String executorRole = TenantPipelineRole.roleName(tenantId);
    String guardTable = schema + ".p3b_sql_exec_guard_suffixed";

    try {
      // LOGIN 롤을 먼저 만든다 — ensureCurrentTenantSchema() 는 executorRole 이 "이미 존재해야"
      // ALTER DEFAULT PRIVILEGES 를 함께 건다(TenantSchemaProvisioner 참조). 비밀번호는 T1 의
      // 파생 규약으로 계산한 값(하드코딩 금지) — TenantPipelineDataSourceRegistry 가 같은 함수로
      // 계산한 값으로 접속을 시도하므로 반드시 일치해야 한다.
      ownerDsl()
          .execute(
              "CREATE ROLE "
                  + executorRole
                  + " LOGIN PASSWORD '"
                  + TenantPipelineRole.password(tenantId, rolePasswordSecret)
                  + "'");
      String db = ownerDsl().fetch("SELECT current_database()").get(0).get(0, String.class);
      ownerDsl().execute("GRANT CONNECT ON DATABASE \"" + db + "\" TO " + executorRole);

      TenantContext.runScopedGet(
          tenantId,
          () -> {
            provisioner.ensureCurrentTenantSchema();
            return null;
          });

      // 관측용 가드 테이블 — 기존 테스트와 동일한 장치(컬럼 DEFAULT 로 세션의 current_user·
      // search_path 를 기록한다). app_tenant 로 만들고 executorRole 에 명시적으로 권한을 준다.
      dsl.execute(
          "CREATE TABLE "
              + guardTable
              + " (n INT, who TEXT DEFAULT current_user,"
              + " sp TEXT DEFAULT current_setting('search_path'))");
      dsl.execute("GRANT INSERT, SELECT ON " + guardTable + " TO " + executorRole);

      // 검증 대상 호출도 테넌트 N 컨텍스트 안에서 해야 한다 — SqlValidator 싱글턴이 검증 시점에
      // DataSchema.current() 를 다시 묻으므로(위 클래스 Javadoc 참조), 컨텍스트가 비어 있으면
      // 기본 테넌트(1)로 떨어져 "data_t{id} 스키마 참조 거부"로 실패한다.
      TenantContext.runScopedGet(
          tenantId,
          () -> {
            sqlScriptExecutor.execute("INSERT INTO " + guardTable + " (n) VALUES (1)");
            return null;
          });

      var row = dsl.fetch("SELECT who, sp FROM " + guardTable).get(0);
      // 실측 판정: 인용된 단일 스키마 조립이 숫자 접미사 스키마에서도 정확히 그 스키마 하나로
      // 해석된다(psql 프로브가 SHOW 로 확인한 것을 여기서는 실제 테이블 조회로 확인한다).
      assertThat(row.get("who", String.class)).isEqualTo(executorRole);
      assertThat(row.get("sp", String.class)).isEqualTo(schema);
    } finally {
      TenantRlsTestSupport.cleanupAll(
          () -> dsl.execute("DROP TABLE IF EXISTS " + guardTable),
          () -> TenantRlsTestSupport.dropSchemasCreatedByThisTest(ownerDsl(), schema),
          () -> {
            // R22(라운드 2 리뷰 확정) — 이 롤은 무조건 생성·삭제한다, ensureRoleExists 의
            // "만들었을 때만" 플래그 패턴을 쓰지 않는다. 규율: 롤이 LOGIN 을 필요로 하면 무조건
            // 생성/삭제, 아니면 플래그 패턴. ensureRoleExists 는 NOLOGIN 만 만들어
            // SqlScriptExecutor 가 실제로 로그인해야 하는 이 롤의 요구를 못 채운다 — 헬퍼를
            // 확장하는 것은 이 밴드 범위에서 이득이 없고, 무작위 900,000,000+ 대역 id 라 이
            // 롤이 사전에 존재할 확률은 무시할 만하다(DataSchemaGrantIsolationTest 가 고정
            // 리터럴 id 로 같은 근거를 이미 쓰고 있다).
            ownerDsl().execute("REVOKE ALL ON DATABASE \"" +
                ownerDsl().fetch("SELECT current_database()").get(0).get(0, String.class) + "\" FROM "
                + executorRole);
            ownerDsl().execute("DROP OWNED BY " + executorRole);
            ownerDsl().execute("DROP ROLE IF EXISTS " + executorRole);
          },
          () -> TenantRlsTestSupport.deleteTenants(dsl, tenantId));
    }
  }
}
