package com.smartfirehub.global.tenant;

import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.name;

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
 * 테넌트별 파이프라인 실행 DB 롤({@code pipeline_executor_t{id}})을 <b>만들고 설정하는 유일한
 * 코드 지점</b>. 런북 §1-3 · §1-5 · §1-6 의 운영자 수동 SQL 절차를 그대로 옮긴 것이다(#680).
 *
 * <p><b>왜 자동화했나 — V111 의 "운영자 절차" 근거는 이 자리에 해당하지 않는다.</b> V111 이
 * 반대한 대상은 {@code app_tenant} 가 EXECUTE 할 수 있는 {@code SECURITY DEFINER} DB 함수였다
 * (권한 상승 경로가 열린다). 이 클래스는 <b>소유자 커넥션({@code schemaOwnerDataSource} = {@code
 * app})으로, 플랫폼 권한({@code platform:tenant:create}) 뒤에서</b> 도는 자바 코드이므로 신뢰
 * 경계가 다르다. 앱은 이미 같은 권한으로 {@code CREATE SCHEMA}({@link TenantSchemaProvisioner})와
 * {@code ALTER ROLE ... PASSWORD}({@code RolePasswordSyncCallback})를 하고 있다.
 *
 * <p><b>수동 절차가 만든 실제 장애(2026-09-17).</b> 테넌트 2는 롤 없이 만들어졌고, 그래서
 * {@link TenantSchemaProvisioner} 가 {@code data_t2} 를 executor grant 없이 생성했다. 테넌트는
 * 정상으로 보였고 데이터셋도 잘 만들어졌으며, <b>다음 날 파이프라인을 처음 돌릴 때에야</b>
 * {@code permission denied for schema data_t2} 로 터졌다. 즉 이 수동 절차는 보안을 사 주지 않고
 * 조용한 실패 모드만 하나 만들었다.
 *
 * <p><b>비밀번호는 처음부터 진짜 값이다.</b> 런북 §1-3 은 임시 비밀번호로 롤을 만들고 §1-6 에서
 * 앱을 재기동해 {@code RolePasswordSyncCallback} 이 덮어쓰게 했다. 앱이
 * {@link TenantPipelineRole#password} 를 직접 계산할 수 있으므로 그 두 단계가 필요 없다 —
 * 생성 즉시 접속 가능한 상태가 된다. 콜백은 시크릿 회전용으로 그대로 남는다.
 */
@Service
public class TenantPipelineRoleProvisioner {

  private static final Logger log = LoggerFactory.getLogger(TenantPipelineRoleProvisioner.class);

  /**
   * 소유자 롤({@code app}) 자격증명으로 여는 DSLContext.
   *
   * <p>{@link TenantSchemaProvisioner} 와 같은 이유로 스프링 기본 DSLContext 를 쓰지 않는다 —
   * 그것은 런타임({@code app_tenant}) 커넥션이라 {@code CREATE ROLE} 이 permission denied 로 터진다.
   */
  private final DSLContext ownerDsl;

  private final String passwordSecret;

  /**
   * 신규/기존 테넌트에 대한 <b>자동</b> 롤 프로비저닝 스위치.
   *
   * <p><b>왜 끌 수 있어야 하는가.</b> {@code pg_roles} 는 DB 가 아니라 <b>클러스터 전역</b>이고,
   * 공유 test DB 에는 테스트가 정리하지 않은 테넌트가 수백 개 쌓여 있다({@link
   * TenantSchemaProvisioner} Javadoc 의 실측). 자동 생성을 켜 두면 테스트가 테넌트를 하나 만들
   * 때마다 LOGIN 롤이 하나씩 영구히 남고, 기동 시 일괄 치유는 그 수백 개를 전부 만들어 버린다.
   * 그래서 {@code test} 프로필에서만 끈다.
   *
   * <p><b>이 플래그는 {@link #ensureRole} 안에 있지 않다 — 일부러 그렇게 뒀다.</b> 기계장치
   * 자체를 끄면 통합 테스트가 프로비저너를 불러도 조용히 아무 일도 하지 않아, 그 테스트가
   * "초록인데 아무것도 검증하지 않는" 상태가 된다. 그래서 끄는 것은 <b>자동 호출부</b>
   * ({@link #ensureRoleIfAutoProvisionEnabled})뿐이고, 기계장치는 항상 동작한다.
   */
  private final boolean autoProvisionEnabled;

  /**
   * {@code current_database()} 결과 캐시. 이 풀의 접속 대상 DB 는 프로세스 수명 동안 바뀌지 않으므로
   * 테넌트마다 다시 묻지 않는다 — 기동 치유가 테넌트 수만큼 도는 경로라 왕복 하나가 그대로 곱해진다.
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
   * 자동 프로비저닝 스위치의 현재 값. 이 플래그를 읽어야 하는 곳이 두 군데(테넌트 생성 호출부와
   * 기동 치유 루프)라, 프로퍼티 이름을 양쪽에 적는 대신 여기 한 곳에서만 읽는다.
   */
  public boolean isAutoProvisionEnabled() {
    return autoProvisionEnabled;
  }

  /** 자동 프로비저닝이 켜져 있을 때만 {@link #ensureRole} 을 부른다. 근거는 필드 Javadoc 참조. */
  public void ensureRoleIfAutoProvisionEnabled(long tenantId) {
    if (!autoProvisionEnabled) {
      log.debug("테넌트 파이프라인 롤 자동 프로비저닝이 꺼져 있다 — 건너뛴다 (tenant={})", tenantId);
      return;
    }
    ensureRole(tenantId);
  }

  /**
   * 테넌트 {@code tenantId} 의 파이프라인 실행 롤이 접속 가능한 상태가 되도록 보장한다. <b>멱등</b>
   * — 이미 있으면 비밀번호·권한·{@code search_path} 를 현재 값으로 맞추기만 한다.
   *
   * <p>세 문장을 한 트랜잭션으로 묶는다. 롤만 만들어지고 {@code GRANT CONNECT} 가 빠지면 접속
   * 자체가 안 되는데 {@code pg_roles} 조회로는 정상으로 보여서, 정확히 이 클래스가 없애려는 종류의
   * "조용한 중간 상태"가 다시 생긴다.
   *
   * <p><b>스키마 권한은 여기서 주지 않는다.</b> {@code data_t{id}} 는 첫 데이터셋 생성 시점에
   * 지연 생성되므로(런북 §1-7) 이 시점에는 대개 존재하지 않는다. 스키마 GRANT 는
   * {@link TenantSchemaProvisioner} 가 스키마를 만들 때 같은 트랜잭션에서 건다 — 롤이 먼저
   * 존재하기만 하면 된다. 이 순서가 바로 테넌트 2에서 깨졌던 전제다.
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
   * {@code GRANT ... ON DATABASE} / {@code ALTER ROLE ... IN DATABASE} 에 쓸 현재 DB 이름.
   *
   * <p>경합으로 두 스레드가 동시에 조회해도 같은 값을 쓰므로 락을 걸지 않는다(최악의 경우 조회가
   * 한 번 더 도는 것이 전부다).
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
   * 이 테넌트의 롤이 이미 존재하는가. 판정은 {@link TenantSchemaProvisioner#roleExists} 하나를 쓴다 —
   * 두 프로비저너가 같은 질문에 다른 답을 내면 "롤이 있다고 보고 GRANT 를 거는 쪽"과 "없다고 보고
   * 건너뛰는 쪽"이 엇갈려, 이번 장애와 같은 조용한 중간 상태가 다시 생긴다.
   */
  public boolean roleExists(long tenantId) {
    return TenantSchemaProvisioner.roleExists(ownerDsl, TenantPipelineRole.roleName(tenantId));
  }
}
