package com.smartfirehub.support.tibero;

import com.smartfirehub.dataset.storage.tibero.TiberoDialect;
import com.smartfirehub.global.platformdb.tibero.flyway.TiberoTenantMigration;
import com.smartfirehub.support.tibero.TiberoTestAccount.Privileges;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;

/**
 * Tibero 테넌트 격리(WD-492) 실서버 하네스 — 운영과 같은 소유자 + 런타임 두 계정.
 *
 * <p>무엇을: 소유자 계정({@code FHT_RLSOWNER_*}, 마이그레이션 권한)에 {@code db/migration-tibero} 전부를 운영과 같은
 * 설정({@link TiberoTenantMigration#configure} — 자리표시자 + 런타임 권한 콜백)으로 적용하고, 런타임 계정({@code
 * FHT_RLSAPP_*}, 접속 권한만)의 풀을 운영 앱 풀처럼 {@code CURRENT_SCHEMA = 소유자} 로 맞춰 준다.
 *
 * <p>JVM 당 하나({@link #shared()}): 기준선 DDL 약 370개라 테스트 클래스마다 다시 만들지 않는다. 종료 훅이 풀을 닫고 런타임 → 소유자 순으로
 * 계정을 지운다(소유자 계정에 묶인 컨텍스트도 {@link TiberoTestAccount} 가 함께 지운다). 그래서 각 테스트 클래스는 자기가 넣은 행을 스스로 지운다.
 *
 * <p>세션 수(공유 서버 한도 20): 소유자 풀 3 + 런타임 공용 풀 2 + 런타임 계정 기본 풀 1(거의 쓰지 않음, 유휴 0). 소유자 풀이 3 인 이유: 콜백이
 * 있으면 Flyway 가 migrate 중에 마이그레이션 연결 둘(이력·실행)과 별도로 콜백용 이벤트 연결을 하나 더 연다({@code
 * Database.getEventConnection}) — 2 로 두면 15초 대기 뒤 연결 고갈로 실패한다(실측).
 */
public final class TiberoTenantRlsSchema {

  /** JVM 당 하나인 하네스. 준비에 실패하면 남기지 않아 다음 호출이 다시 시도한다. */
  private static TiberoTenantRlsSchema shared;

  private final TiberoTestAccount owner;
  private final TiberoTestAccount runtime;
  private final HikariDataSource runtimePool;

  private TiberoTenantRlsSchema(TiberoTestAccount owner, TiberoTestAccount runtime) {
    this.owner = owner;
    this.runtime = runtime;
    this.runtimePool = runtime.newPool(2, runtimeInitSql(owner.name()));
  }

  /** JVM 공용 하네스. 첫 호출이 두 계정 생성 → migrate 를 하고 종료 훅을 건다. 드라이버 jar 가 없으면 테스트를 건너뛴다. */
  public static synchronized TiberoTenantRlsSchema shared() {
    TiberoTestServer.assumeDriverPresent();
    if (shared == null) {
      TiberoTestAccount owner = TiberoTestAccount.create("RLSOWNER", Privileges.MIGRATION_OWNER, 3);
      TiberoTestAccount runtime = null;
      TiberoTenantRlsSchema schema;
      try {
        runtime = TiberoTestAccount.create("RLSAPP", Privileges.SESSION_ONLY, 1);
        schema = new TiberoTenantRlsSchema(owner, runtime);
        long started = System.nanoTime();
        int executed = schema.flyway().migrate().migrationsExecuted;
        TiberoTestEvidence.write(
            "테넌트 격리 스키마 적용 " + executed + "건, " + (System.nanoTime() - started) / 1_000_000 + "ms");
      } catch (RuntimeException e) {
        // 마이그레이션이 실패해도 방금 만든 계정은 남기지 않는다 — 런타임(풀 포함) → 소유자 순.
        closeQuietly(runtime, e);
        closeQuietly(owner, e);
        throw e;
      }
      TiberoTestAccount r = runtime;
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    try {
                      r.close();
                    } finally {
                      owner.close();
                    }
                  },
                  "tibero-tenant-rls-cleanup"));
      shared = schema;
    }
    return shared;
  }

  /**
   * 운영 앱 풀과 같은 런타임 세션 초기화 문장 — {@code CURRENT_SCHEMA} 를 소유자로 맞춘 뒤 NLS 를 고정한다({@code
   * application-tibero.yml} 의 connection-init-sql 과 같은 모양, TiberoProfileSettingsTest 가 그쪽을 고정).
   */
  public static String runtimeInitSql(String ownerSchema) {
    return TiberoDialect.sessionInitSql(ownerSchema);
  }

  /** 소유자 계정 — 스키마 객체를 가졌고 정책에 걸리지 않는다. 픽스처 행 정리에 쓴다. */
  public TiberoTestAccount owner() {
    return owner;
  }

  /** 런타임 계정 — 앱 풀 계정. 기본 풀은 CURRENT_SCHEMA 가 없으니 {@link #runtimeConnection()} 을 쓴다. */
  public TiberoTestAccount runtime() {
    return runtime;
  }

  /** 소유자 스키마의 테넌트 컨텍스트 이름. 앱 코드는 이 이름을 쓰지 않는다(패키지가 숨긴다) — 테스트 단언만 쓴다. */
  public String contextName() {
    return TiberoTenantMigration.contextNameFor(owner.name());
  }

  /** 운영 앱처럼 CURRENT_SCHEMA = 소유자인 런타임 커넥션(공용 풀 크기 2). 컨텍스트는 세션 범위라 바꿨으면 닫기 전에 지운다. */
  public Connection runtimeConnection() throws SQLException {
    return runtimePool.getConnection();
  }

  /** 런타임 계정의 새 풀(CURRENT_SCHEMA = 소유자). 호출자가 닫는다 — 닫지 않아도 종료 훅이 계정을 지우기 전에 닫는다. */
  public HikariDataSource newRuntimePool(int size) {
    return runtime.newPool(size, runtimeInitSql(owner.name()));
  }

  /** 하네스와 같은 설정의 Flyway — migrate 를 다시 돌려 보는 테스트가 같은 자리표시자·콜백을 쓰게 한 곳에서 만든다. */
  public Flyway flyway() {
    FluentConfiguration c =
        Flyway.configure()
            .dataSource(owner.dataSource())
            .schemas(owner.name())
            .initSql(TiberoDialect.PLATFORM_SESSION_INIT_SQL)
            .locations("classpath:db/migration-tibero");
    return TiberoTenantMigration.configure(c, owner.name(), runtime.name()).load();
  }

  // --- 테넌트 컨텍스트 도우미(시험 전용) ---
  // 앱 코드는 FH_TENANT 호출을 TiberoPlatformTenantSession 한 파일에만 둔다(소스 검사 테스트). 시험은 매니저 없이 커넥션에 직접 심어야 해서
  // 호출 문자열을 여기 한곳에 둔다.

  /** 테넌트 심기 호출 — 패키지가 NULL 을 거부한다(-20001). */
  public static final String SET_TENANT_CALL = "{call FH_TENANT.set_tenant(?)}";

  /** 테넌트 지우기 호출 — TENANT_ID 와 BYPASS 를 모두 지운다. */
  public static final String CLEAR_TENANT_CALL = "{call FH_TENANT.clear_tenant}";

  /** 커넥션에 테넌트를 심는다. 컨텍스트는 세션 범위라 풀에 돌려주기 전에 {@link #clearTenant} 로 지운다. */
  public static void setTenant(Connection c, long tenant) throws SQLException {
    try (CallableStatement cs = c.prepareCall(SET_TENANT_CALL)) {
      cs.setLong(1, tenant);
      cs.execute();
    }
  }

  /** 커넥션의 테넌트·우회 표시를 지운다. */
  public static void clearTenant(Connection c) throws SQLException {
    try (CallableStatement cs = c.prepareCall(CLEAR_TENANT_CALL)) {
      cs.execute();
    }
  }

  /** 런타임 커넥션으로 하는 일 — 결과를 돌려줄 수 있다. */
  @FunctionalInterface
  public interface RuntimeWork<T> {
    T run(Connection c) throws SQLException;
  }

  /**
   * 런타임 커넥션(운영 앱 풀과 같은 CURRENT_SCHEMA)에서 테넌트를 정하고 일을 한다. {@code tenant} 가 null 이면 심지 않는다 — 공용 풀의
   * 커넥션은 매번 끝에 지우므로 비어 있다. 컨텍스트는 세션 범위라 공용 풀(크기 2)의 다음 사용자에게 남기지 않도록 끝나면 반드시 지운다.
   */
  public <T> T withRuntime(Long tenant, RuntimeWork<T> work) throws SQLException {
    try (Connection c = runtimeConnection()) {
      try {
        if (tenant != null) {
          setTenant(c, tenant);
        }
        return work.run(c);
      } finally {
        clearTenant(c);
      }
    }
  }

  /** 이 세션의 테넌트 컨텍스트 속성 값(TENANT_ID·BYPASS, 없으면 null). */
  public String context(Connection c, String attribute) throws SQLException {
    return TiberoSql.queryString(
        c, "SELECT SYS_CONTEXT('" + contextName() + "','" + attribute + "') FROM DUAL");
  }

  private static void closeQuietly(TiberoTestAccount account, RuntimeException primary) {
    if (account == null) return;
    try {
      account.close();
    } catch (RuntimeException suppressed) {
      primary.addSuppressed(suppressed);
    }
  }
}
