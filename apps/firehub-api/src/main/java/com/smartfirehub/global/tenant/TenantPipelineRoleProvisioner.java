package com.smartfirehub.global.tenant;

import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.name;

import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 테넌트별 파이프라인 실행 DB 롤({@code pipeline_executor_t{id}})을 <b>만들고 설정하는 유일한 코드 지점</b>. 런북 §1-3 · §1-5 ·
 * §1-6 의 운영자 수동 SQL 절차를 그대로 옮긴 것이다(#680).
 *
 * <p><b>왜 자동화했나 — V111 의 "운영자 절차" 근거는 이 자리에 해당하지 않는다.</b> V111 이 반대한 대상은 {@code app_tenant} 가
 * EXECUTE 할 수 있는 {@code SECURITY DEFINER} DB 함수였다 (권한 상승 경로가 열린다). 이 클래스는 <b>소유자 커넥션({@code
 * schemaOwnerDataSource} = {@code app})으로, 플랫폼 권한({@code platform:tenant:create}) 뒤에서</b> 도는 자바
 * 코드이므로 신뢰 경계가 다르다. 앱은 이미 같은 권한으로 {@code CREATE SCHEMA}({@link TenantSchemaProvisioner})와 {@code
 * ALTER ROLE ... PASSWORD}({@code RolePasswordSyncCallback})를 하고 있다.
 *
 * <p><b>수동 절차가 만든 실제 장애(2026-09-17).</b> 테넌트 2는 롤 없이 만들어졌고, 그래서 {@link TenantSchemaProvisioner} 가
 * {@code data_t2} 를 executor grant 없이 생성했다. 테넌트는 정상으로 보였고 데이터셋도 잘 만들어졌으며, <b>다음 날 파이프라인을 처음 돌릴
 * 때에야</b> {@code permission denied for schema data_t2} 로 터졌다. 즉 이 수동 절차는 보안을 사 주지 않고 조용한 실패 모드만 하나
 * 만들었다.
 *
 * <p><b>비밀번호는 처음부터 진짜 값이다.</b> 런북 §1-3 은 임시 비밀번호로 롤을 만들고 §1-6 에서 앱을 재기동해 {@code
 * RolePasswordSyncCallback} 이 덮어쓰게 했다. 앱이 {@link TenantPipelineRole#password} 를 직접 계산할 수 있으므로 그 두
 * 단계가 필요 없다 — 생성 즉시 접속 가능한 상태가 된다. 콜백은 시크릿 회전용으로 그대로 남는다.
 */
@Service
public class TenantPipelineRoleProvisioner {

  private static final Logger log = LoggerFactory.getLogger(TenantPipelineRoleProvisioner.class);

  /**
   * 소유자 롤({@code app}) 자격증명으로 여는 DSLContext.
   *
   * <p>{@link TenantSchemaProvisioner} 와 같은 이유로 스프링 기본 DSLContext 를 쓰지 않는다 — 그것은 런타임({@code
   * app_tenant}) 커넥션이라 {@code CREATE ROLE} 이 permission denied 로 터진다.
   */
  private final DSLContext ownerDsl;

  private final String passwordSecret;

  /**
   * 신규/기존 테넌트에 대한 <b>자동</b> 롤 프로비저닝 스위치.
   *
   * <p><b>왜 끌 수 있어야 하는가.</b> {@code pg_roles} 는 DB 가 아니라 <b>클러스터 전역</b>이고, 공유 test DB 에는 테스트가 정리하지
   * 않은 테넌트가 수백 개 쌓여 있다({@link TenantSchemaProvisioner} Javadoc 의 실측). 자동 생성을 켜 두면 테스트가 테넌트를 하나 만들
   * 때마다 LOGIN 롤이 하나씩 영구히 남고, 기동 시 일괄 치유는 그 수백 개를 전부 만들어 버린다. 그래서 {@code test} 프로필에서만 끈다.
   *
   * <p><b>이 플래그는 {@link #ensureRole} 안에 있지 않다 — 일부러 그렇게 뒀다.</b> 기계장치 자체를 끄면 통합 테스트가 프로비저너를 불러도 조용히
   * 아무 일도 하지 않아, 그 테스트가 "초록인데 아무것도 검증하지 않는" 상태가 된다. 그래서 끄는 것은 <b>자동 호출부</b> ({@link
   * #ensureRoleIfAutoProvisionEnabled})뿐이고, 기계장치는 항상 동작한다.
   */
  private final boolean autoProvisionEnabled;

  /**
   * {@code current_database()} 결과 캐시. 이 풀의 접속 대상 DB 는 프로세스 수명 동안 바뀌지 않으므로 테넌트마다 다시 묻지 않는다 — 기동 치유가
   * 테넌트 수만큼 도는 경로라 왕복 하나가 그대로 곱해진다.
   */
  private volatile String cachedDatabase;

  public TenantPipelineRoleProvisioner(
      @Qualifier("schemaOwnerDataSource") DataSource schemaOwnerDataSource,
      @Value("${app.pipeline.role-password-secret}") String passwordSecret,
      @Value("${app.pipeline.role-auto-provision:true}") boolean autoProvisionEnabled) {
    this.ownerDsl = DSL.using(schemaOwnerDataSource, SQLDialect.POSTGRES);
    this.passwordSecret = passwordSecret;
    this.autoProvisionEnabled = autoProvisionEnabled;
  }

  /**
   * 테넌트 PYTHON 읽기 슬롯 롤({@code pipeline_py_t{id}_s1..s10})의 활성 세션을 모두 끊는다 — 등급 정의 변경 재동기화가 새 GRANT 를
   * 커밋하기 전에 부른다(PythonReadGrantSync, WD-29 CR2). 런타임 롤(app_tenant)은 슬롯 롤 세션을 끊을 권한이
   * 없어(pg_signal_backend·슬롯 롤 멤버 아님, 실측) 슬롯 롤 수명을 맡은 이 클래스의 소유자 연결로 한다.
   *
   * <p>대상은 정확한 롤 이름 목록으로만 고른다(LIKE 금지 — 다른 테넌트 롤 오인 방지). {@code pg_terminate_backend(pid, 5000)} 은
   * 대상이 실제로 끝날 때까지(최대 5초) 기다려, 호출자가 이 뒤에 커밋하는 GRANT 를 그 세션이 보지 못한다. false 는 시간 초과이거나 조회와 종료 사이에 이미
   * 끝난 세션(경고만)이라, 아직 살아 있는지 다시 본다. 남아 있거나 권한이 없으면 예외 — 호출자 트랜잭션(넓히는 GRANT)을 되돌린다(fail-closed).
   *
   * @return 끊은(종료 신호를 보낸) 세션 수
   */
  public int terminatePythonReadSessions(long tenantId) {
    String[] roles = TenantPipelineRole.pythonReadRoleNames(tenantId).toArray(new String[0]);
    var results =
        ownerDsl.fetch(
            "select pid, pg_terminate_backend(pid, 5000) from pg_stat_activity"
                + " where usename::text = any({0}::text[])",
            DSL.val(roles));
    List<Integer> unconfirmed = new ArrayList<>();
    for (var r : results) {
      if (!Boolean.TRUE.equals(r.get(1, Boolean.class))) {
        unconfirmed.add(r.get(0, Integer.class));
      }
    }
    if (!unconfirmed.isEmpty()) {
      int alive =
          ownerDsl
              .fetchOne(
                  "select count(*)::int from pg_stat_activity where pid = any({0}::int[])",
                  DSL.val(unconfirmed.toArray(new Integer[0])))
              .get(0, Integer.class);
      if (alive > 0) {
        log.error(
            "PYTHON 슬롯 롤 세션 종료 실패: tenant={} 대상 {}개 중 {}개가 끝나지 않음",
            tenantId,
            results.size(),
            alive);
        throw new IllegalStateException("PYTHON 슬롯 롤 세션을 끊지 못해 등급 변경 재동기화를 되돌렸습니다.");
      }
    }
    return results.size();
  }

  /**
   * 자동 프로비저닝 스위치의 현재 값. 이 플래그를 읽어야 하는 곳이 두 군데(테넌트 생성 호출부와 기동 치유 루프)라, 프로퍼티 이름을 양쪽에 적는 대신 여기 한 곳에서만
   * 읽는다.
   */
  public boolean isAutoProvisionEnabled() {
    return autoProvisionEnabled;
  }

  /**
   * 자동 프로비저닝이 켜져 있을 때만 {@link #ensureRole} 과 {@link #ensurePythonReadRoles} 를 부른다. 근거는 필드 Javadoc
   * 참조.
   *
   * <p>슬롯 롤도 여기서 함께 만든다 — 신규 테넌트({@code PlatformTenantService})가 이 메서드 하나만 부르므로, 따로 두면 신규 테넌트의
   * PYTHON 스텝이 첫 실행에서 인증 실패로 멈춘다.
   */
  public void ensureRoleIfAutoProvisionEnabled(long tenantId) {
    if (!autoProvisionEnabled) {
      log.debug("테넌트 파이프라인 롤 자동 프로비저닝이 꺼져 있다 — 건너뛴다 (tenant={})", tenantId);
      return;
    }
    ensureRole(tenantId);
    ensurePythonReadRoles(tenantId);
  }

  /**
   * 테넌트 {@code tenantId} 의 파이프라인 실행 롤이 접속 가능한 상태가 되도록 보장한다. <b>멱등</b> — 이미 있으면 비밀번호·권한·{@code
   * search_path} 를 현재 값으로 맞추기만 한다.
   *
   * <p>세 문장을 한 트랜잭션으로 묶는다. 롤만 만들어지고 {@code GRANT CONNECT} 가 빠지면 접속 자체가 안 되는데 {@code pg_roles} 조회로는
   * 정상으로 보여서, 정확히 이 클래스가 없애려는 종류의 "조용한 중간 상태"가 다시 생긴다.
   *
   * <p><b>스키마 권한은 여기서 주지 않는다.</b> {@code data_t{id}} 는 첫 데이터셋 생성 시점에 지연 생성되므로(런북 §1-7) 이 시점에는 대개
   * 존재하지 않는다. 스키마 GRANT 는 {@link TenantSchemaProvisioner} 가 스키마를 만들 때 같은 트랜잭션에서 건다 — 롤이 먼저 존재하기만 하면
   * 된다. 이 순서가 바로 테넌트 2에서 깨졌던 전제다.
   */
  public void ensureRole(long tenantId) {
    String roleName = TenantPipelineRole.roleName(tenantId);
    String password = TenantPipelineRole.password(tenantId, passwordSecret);
    // 스키마명은 손으로 조립하지 않는다 — DataSchema 가 유일한 조립 지점이고, 테넌트 1 은
    // data, 그 외는 data_t{id} 라는 분기도 거기 한 곳에만 있어야 한다.
    String schema = DataSchema.forTenant(tenantId);

    ownerDsl.transaction(
        cfg -> {
          DSLContext tx = DSL.using(cfg);

          // 이 롤에 대한 프로비저닝을 클러스터 전역으로 직렬화한다. 락은 트랜잭션 종료 시 자동 해제.
          //
          // **왜 "존재 검사 후 CREATE" 만으로는 부족한가.** 두 세션이 동시에 들어오면 둘 다
          // roleExists=false 를 보고, 진 쪽은 pg_authid_rolname_index 에서 대기하다가
          // 23505(unique_violation) 로 실패한다 — 42710(duplicate_object) 이 아니다(형제 경합인
          // CREATE SCHEMA 에 대해 TenantSchemaProvisioner 가 이미 "실측 SQLSTATE 23505,
          // pg_namespace_nspname_index" 로 기록해 둔 것과 같은 메커니즘이다). 게다가 뒤따르는
          // ALTER/GRANT 세 문장도 pg_authid·pg_database.datacl·pg_db_role_setting 의 같은 튜플을
          // 건드려 동시 실행 시 XX000 "tuple concurrently updated" 가 난다. 즉 SQLSTATE 를 골라
          // 잡는 방식은 잡아야 할 코드가 최소 셋이고, 그 목록이 맞는지는 아무도 검증할 수 없다.
          // 애초에 겹치지 않게 만드는 편이 짧고 확실하다.
          //
          // 실제 경합 경로: 기동 치유 루프와 POST /api/platform/tenants 가 같은 테넌트에 겹치는
          // 경우, 그리고 롤링 업데이트 중 신·구 파드가 동시에 기동 치유를 도는 경우.
          tx.execute("SELECT pg_advisory_xact_lock(hashtext({0})::bigint)", inline(roleName));

          String database = currentDatabase(tx);

          if (TenantSchemaProvisioner.roleExists(tx, roleName)) {
            // 비밀번호를 SQL 리터럴로 인라인하는 이유: CREATE/ALTER ROLE ... PASSWORD 는 바인드
            // 파라미터를 받지 않는다. 값은 HMAC 다이제스트의 hex 32자라 따옴표·역슬래시가 구조적으로
            // 들어갈 수 없지만, 그 사실에 기대지 않고 DSL.inline 에 이스케이프를 맡긴다.
            tx.execute("ALTER ROLE {0} WITH LOGIN PASSWORD {1}", name(roleName), inline(password));
          } else {
            tx.execute("CREATE ROLE {0} LOGIN PASSWORD {1}", name(roleName), inline(password));
          }

          // 접속 권한이 없으면 롤이 있어도 로그인 자체가 거부된다. 멱등이다.
          tx.execute("GRANT CONNECT ON DATABASE {0} TO {1}", name(database), name(roleName));

          // ⚠ IN DATABASE 를 반드시 붙인다. 빠뜨리면 pg_db_role_setting.setdatabase 가 0(=클러스터의
          // 모든 DB)으로 기록된다 — 레거시 pipeline_executor 가 실제로 그 상태다(V111 주석, 런북 §1-5
          // 실측). TenantPipelineRoleProvisionerTest 가 setdatabase != 0 을 단언해 이 한 줄을 고정한다.
          tx.execute(
              "ALTER ROLE {0} IN DATABASE {1} SET search_path TO {2}",
              name(roleName), name(database), name(schema));
        });
    log.info("테넌트 파이프라인 롤 준비 완료: {} (search_path={})", roleName, schema);
  }

  /**
   * 테넌트의 PYTHON 읽기 슬롯 롤 10개({@code pipeline_py_t{id}_s{k}}, WD-29)를 접속 가능한 상태로 보장한다(스펙 §4.4).
   * <b>멱등</b>이고, 자동 프로비저닝 플래그와 무관하다({@link #ensureRole} 과 같은 이유 — 기계장치는 항상 동작하고 플래그는 자동 호출부만 끈다).
   *
   * <p>스키마가 이미 있으면 USAGE 까지 건다. 스키마가 나중에 생기는 순서는 {@link TenantSchemaProvisioner} 가 생성 트랜잭션에서 건다 — 두
   * 순서 모두에서 USAGE 가 빠지지 않게 하려는 것이다. 테이블 SELECT 는 여기서 다루지 않는다(PythonReadGrantSync, 런타임 롤 몫).
   *
   * <p>슬롯 롤은 {@code NOINHERIT} 등 최소 속성으로 만든다 — 사용자 스크립트가 이 자격증명을 그대로 들고 돌기 때문에, 다른 롤을 통해 권한이 새는 경로를
   * 처음부터 닫는다. 10개를 한 트랜잭션으로 묶어 "일부 슬롯만 있는" 중간 상태가 남지 않게 한다.
   */
  public void ensurePythonReadRoles(long tenantId) {
    String schema = DataSchema.forTenant(tenantId);
    ownerDsl.transaction(
        cfg -> {
          DSLContext tx = DSL.using(cfg);
          // ensureRole 과 같은 이유로 클러스터 전역 직렬화한다(기동 치유·테넌트 생성·롤링 업데이트 경합 시
          // 23505·tuple concurrently updated 회피). 락 키는 실행 롤과 겹치지 않게 별도 문자열을 쓴다.
          tx.execute(
              "SELECT pg_advisory_xact_lock(hashtext({0})::bigint)",
              inline("python_read_roles_t" + tenantId));
          String database = currentDatabase(tx);
          boolean schemaExists = TenantSchemaProvisioner.schemaExists(tx, schema);
          for (int slot = 1; slot <= TenantPipelineRole.PYTHON_READ_SLOTS; slot++) {
            String role = TenantPipelineRole.pythonReadRoleName(tenantId, slot);
            String password = TenantPipelineRole.pythonReadPassword(tenantId, slot, passwordSecret);
            if (TenantSchemaProvisioner.roleExists(tx, role)) {
              tx.execute("ALTER ROLE {0} WITH LOGIN PASSWORD {1}", name(role), inline(password));
            } else {
              tx.execute(
                  "CREATE ROLE {0} LOGIN PASSWORD {1}"
                      + " NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS NOINHERIT",
                  name(role), inline(password));
            }
            tx.execute("GRANT CONNECT ON DATABASE {0} TO {1}", name(database), name(role));
            // ensureRole 과 같은 함정 — IN DATABASE 가 빠지면 클러스터 전 DB 에 search_path 가 걸린다.
            tx.execute(
                "ALTER ROLE {0} IN DATABASE {1} SET search_path TO {2}",
                name(role), name(database), name(schema));
            // 이 롤에 '직접' 부여된 public 스키마 권한만 걷는다(멱등·방어적). PUBLIC 의사 롤 경유 USAGE 는 걷지 못해
            // 스키마 안 이름 조회는 될 수 있다 — 앱 메타데이터(public 테이블)를 못 읽게 하는 실제 방어선은 테이블 권한(이 롤·
            // PUBLIC 대상 SELECT GRANT 가 없음)이다.
            tx.execute("REVOKE ALL ON SCHEMA public FROM {0}", name(role));
          }
          if (schemaExists) {
            TenantSchemaProvisioner.grantPythonReadSchemaUsage(tx, tenantId, schema);
          }
        });
    log.info("PYTHON 읽기 슬롯 롤 준비 완료: tenant={} (search_path={})", tenantId, schema);
  }

  /**
   * 스키마가 있으면 슬롯 롤의 스키마 USAGE 누락을 복구한다(CR10) — 기동 치유가 롤 존재 여부와 무관하게 부른다. 롤은 다 있는데 USAGE 만 빠진 상태(운영자
   * 수동 REVOKE·부분 복원)는 "롤이 없을 때만 만든다" 판정으로는 영영 안 고쳐지고, 그 테넌트 PYTHON 이 {@code permission denied for
   * schema} 로 멈추기 때문이다. 이미 완비면 조회 한 번뿐이다(카탈로그 쓰기 없음). 스키마가 없으면 아무것도 하지 않는다 — 지연 생성 설계 유지.
   *
   * @return USAGE 를 새로 건 롤 수
   */
  public int ensurePythonReadSchemaUsage(long tenantId) {
    String schema = DataSchema.forTenant(tenantId);
    return ownerDsl.transactionResult(
        cfg -> {
          DSLContext tx = DSL.using(cfg);
          if (!TenantSchemaProvisioner.schemaExists(tx, schema)) {
            return 0;
          }
          return TenantSchemaProvisioner.grantPythonReadSchemaUsage(tx, tenantId, schema);
        });
  }

  /**
   * 슬롯 롤 10개가 모두 있는가 — 기동 치유가 "없을 때만 만든다"를 판정할 때 쓴다. 하나라도 빠지면 false. 테넌트마다 기동 시 부르므로 롤별 조회 대신
   * pg_roles 를 한 번만 본다.
   */
  public boolean pythonReadRolesExist(long tenantId) {
    List<String> roles = TenantPipelineRole.pythonReadRoleNames(tenantId);
    Integer found =
        ownerDsl
            .fetchOne(
                "select count(*)::int from pg_roles where rolname::text = any({0}::text[])",
                DSL.val(roles.toArray(new String[0])))
            .get(0, Integer.class);
    return found == roles.size();
  }

  /**
   * {@code GRANT ... ON DATABASE} / {@code ALTER ROLE ... IN DATABASE} 에 쓸 현재 DB 이름.
   *
   * <p>경합으로 두 스레드가 동시에 조회해도 같은 값을 쓰므로 락을 걸지 않는다(최악의 경우 조회가 한 번 더 도는 것이 전부다).
   */
  private String currentDatabase(DSLContext tx) {
    String database = cachedDatabase;
    if (database == null) {
      database = tx.fetch("select current_database()").get(0).get(0, String.class);
      cachedDatabase = database;
    }
    return database;
  }

  /**
   * 이 테넌트의 롤이 이미 존재하는가. 판정은 {@link TenantSchemaProvisioner#roleExists} 하나를 쓴다 — 두 프로비저너가 같은 질문에 다른
   * 답을 내면 "롤이 있다고 보고 GRANT 를 거는 쪽"과 "없다고 보고 건너뛰는 쪽"이 엇갈려, 이번 장애와 같은 조용한 중간 상태가 다시 생긴다.
   */
  public boolean roleExists(long tenantId) {
    return TenantSchemaProvisioner.roleExists(ownerDsl, TenantPipelineRole.roleName(tenantId));
  }
}
