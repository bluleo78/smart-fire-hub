package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * V125 — 데이터셋 물리 테이블의 {@code ix_<table>_upd} 인덱스를 <b>CONCURRENTLY</b> 로,
 * <b>트랜잭션 밖에서</b> 만든다.
 *
 * <h2>왜 백필(V124)에서 분리했나 (되돌리지 말 것)</h2>
 *
 * <p>처음에는 V123 하나가 컬럼·트리거·인덱스를 한 트랜잭션 안에서 전부 만들었다(지금 컬럼·트리거는 V124 담당). 평범한 {@code CREATE INDEX}
 * 는 대상 테이블에 SHARE 락을 잡아 인덱스가 다 만들어질 때까지 <b>쓰기를 막는다</b>. 게다가 전부 한
 * 트랜잭션이라 <b>첫 번째 테이블에서 잡은 락이 마이그레이션 전체가 커밋될 때까지 풀리지 않는다</b> —
 * 데이터셋 테이블이 수십 개인 운영 DB 에서는 앞쪽 테이블이 "자기 인덱스 빌드 시간"이 아니라
 * "마이그레이션 전체 시간" 동안 쓰기 차단된다. 배포 중 임포트·파이프라인이 통째로 멈춘다.
 *
 * <p>그래서 값싼 것(컬럼 추가 — {@code now()} 는 STABLE 이라 PG 의 fast-default 경로로 테이블 재작성
 * 없음 — 과 트리거 생성)은 V124 에 남기고, 비싼 인덱스 빌드만 이 마이그레이션으로 옮겼다. 여기서는
 * {@code CREATE INDEX CONCURRENTLY} 가 쓰기를 막지 않고, 테이블 하나가 끝나면 그 테이블의 락은 즉시
 * 풀린다(문장마다 자기 트랜잭션이므로).
 *
 * <p><b>다시 V124 백필 루프 안으로 접어 넣지 말 것.</b> 접는 순간 위 두 가지(쓰기 차단 + 락 누적)가 함께 돌아온다.
 *
 * <h2>왜 SQL 스크립트가 아니라 Java 마이그레이션인가</h2>
 *
 * <p>{@code CREATE INDEX CONCURRENTLY} 는 트랜잭션 블록 안에서 실행할 수 없고, 이는 {@code DO $$ ... $$}
 * 블록 안에서도 마찬가지다(DO 블록 자체가 하나의 트랜잭션이다). 그런데 대상 테이블 이름은 {@code dataset}
 * 카탈로그에서 런타임에 읽어야 하므로 정적 SQL 로는 쓸 수 없다. dblink 로 우회하는 길은 이 프로젝트에서
 * 금지된 확장이다({@code SqlValidator}). 남는 것은 Java 마이그레이션이고, Flyway 의 non-transactional
 * 설정(SQL 스크립트의 {@code executeInTransaction=false} 와 같은 스위치)은 Java 쪽에서
 * {@link #canExecuteInTransaction()} {@code = false} 로 표현한다.
 *
 * <h2>실패 정책 — 한 테이블이 실패해도 마이그레이션은 계속한다</h2>
 *
 * <p>{@code ix_<table>_upd} 가 없거나 INVALID 여도 <b>정합성은 깨지지 않는다</b> — 증분 조건
 * ({@code _updated_at > {{last_run_at}}})은 순차 스캔으로도 같은 결과를 내고, 느려질 뿐이다. 반대로
 * 인덱스 하나 때문에 마이그레이션을 실패시키면 배포 자체가 막힌다. 그래서 테이블 단위로 예외를 잡아
 * WARN 로그를 크게 남기고 다음 테이블로 넘어간다.
 *
 * <p><b>수리는 자동이 아니다 — 정확히 적는다.</b> Flyway 는 {@code success=true} 로 기록된 이
 * 마이그레이션을 다시 돌리지 않고, {@code scripts/sql/complete_updated_at_backfill.sql} 도 인덱스는
 * 일부러 만들지 않는다(컬럼·트리거 전용). 그러므로 건너뛴 테이블의 인덱스는 운영자가 한가할 때
 * 직접, 트랜잭션 밖에서 만들어야 한다 — WARN 로그가 그 명령을 그대로 찍어 준다:
 *
 * <pre>
 * CREATE INDEX CONCURRENTLY IF NOT EXISTS "ix_&lt;table&gt;_upd" ON "&lt;schema&gt;"."&lt;table&gt;" (_updated_at);
 * </pre>
 *
 * <p>자동으로 수리되는 경우는 하나뿐이다: 마이그레이션이 <b>완주하지 못한</b> 경우(컨테이너가 도중에
 * 죽는 등). 그때는 history 행이 남지 않아 다음 기동이 이 마이그레이션을 다시 돌리고, 아래 규칙대로
 * INVALID 잔재를 버리고 다시 만든다.
 *
 * <h2>재실행 안전성</h2>
 *
 * <ul>
 *   <li>유효한 인덱스가 이미 있으면 건드리지 않는다(dev DB 처럼 예전 형태의 V123 가 이미 만들어 둔 경우).
 *   <li>INVALID 인덱스(CONCURRENTLY 실패 잔재)가 있으면 {@code DROP INDEX CONCURRENTLY} 후 다시 만든다.
 *       {@code CREATE INDEX CONCURRENTLY IF NOT EXISTS} 만 쓰면 INVALID 인덱스를 "있다"고 보고 조용히
 *       건너뛰어 영원히 고쳐지지 않는다 — 그래서 상태를 직접 {@code pg_index.indisvalid} 로 본다.
 *   <li>물리 테이블이 없는 데이터셋 행은 건너뛴다(V124 백필 루프와 같은 규칙).
 * </ul>
 *
 * <p>인덱스 이름·모양({@code ix_<table>_upd}, {@code (_updated_at)})은 신규 테이블을 만드는 런타임 경로
 * ({@code DataTableService.createUpdatedAtIndex})와 반드시 같아야 한다. 규약을 둘로 늘리지 말 것.
 */
public class V125__pipeline_incremental_updated_at_indexes extends BaseJavaMigration {

  private static final Logger log =
      LoggerFactory.getLogger(V125__pipeline_incremental_updated_at_indexes.class);

  /**
   * Flyway 에게 "이 마이그레이션은 트랜잭션 밖에서 실행하라"고 알린다 — SQL 스크립트의
   * {@code executeInTransaction=false} 와 같은 스위치다. 이 값이 true 가 되면 첫 CREATE INDEX
   * CONCURRENTLY 에서 SQLSTATE 25001("cannot run inside a transaction block")로 즉시 실패한다.
   */
  @Override
  public boolean canExecuteInTransaction() {
    return false;
  }

  @Override
  public void migrate(Context context) throws Exception {
    // context.getConnection() 은 Flyway 소유자 롤(app) 연결이다 — dataset 테이블의 RLS 를 우회하므로
    // 모든 테넌트의 데이터셋이 보인다. 런타임 DSLContext(app_tenant)를 쓰면 GUC 미설정 상태에서
    // 0건이 보여 아무 것도 하지 않는 "성공"이 된다(V124 테스트 주석 참고).
    //
    // 트랜잭션 안이면 아래 CREATE INDEX CONCURRENTLY 가 전부 SQLSTATE 25001 로 실패하고(테이블 단위로
    // 삼켜지므로 마이그레이션은 "성공"처럼 끝난다), 인덱스가 하나도 안 생긴다. 조용한 무동작을 막기 위해
    // 먼저 크게 경고한다 — 실측(2026-09-21): canExecuteInTransaction() 을 true 로 바꾸면
    // "ERROR: CREATE INDEX CONCURRENTLY cannot run inside a transaction block" 이 테이블마다 찍힌다.
    if (!context.getConnection().getAutoCommit()) {
      log.error(
          "V125 가 트랜잭션 안에서 실행되고 있다 — canExecuteInTransaction() 이 false 인지 확인하라."
              + " 이 상태로는 CREATE INDEX CONCURRENTLY 가 전부 실패한다.");
    }
    ensureUpdatedAtIndexes(context.getConnection());
  }

  /**
   * 모든 TABLE 스토리지 데이터셋의 물리 테이블에 {@code ix_<table>_upd} 인덱스를 보장한다.
   *
   * <p>테스트가 픽스처에 대해 직접 호출할 수 있도록 public static 으로 둔다 — 그래야 "V125 가 실제로
   * 인덱스를 만든다"를 마이그레이션 파일 자체의 코드로 증명할 수 있다(복사본 SQL 로 흉내 내면
   * 파일이 바뀌어도 테스트가 따라가지 못한다).
   *
   * @param conn 소유자 롤 연결. <b>autocommit 이어야 한다</b>(CONCURRENTLY 제약).
   */
  public static void ensureUpdatedAtIndexes(Connection conn) throws SQLException {
    // CREATE INDEX CONCURRENTLY 는 "지금 열려 있는 모든 트랜잭션"이 끝나기를 기다린다 — 대상 테이블과
    // 무관한 트랜잭션도 포함이다(V123 상단의 fh_incremental_cursor_candidate 한계와 같은 성질).
    // 배포 시점에 idle in transaction 커넥션이 하나라도 있으면(다른 컨테이너·풀·사람이 연 psql)
    // 이 문장이 **무한정** 멈추고 기동이 끝나지 않는다. 실측(2026-09-21): 열린 트랜잭션 하나로
    // CIC 가 wait_event=virtualxid 로 15분 넘게 대기했다. 그래서 상한을 두고, 걸리면 아래 실패 정책
    // (크게 경고하고 건너뛰기)으로 흘려보낸다 — 배포를 멈추는 것보다 인덱스가 늦는 편이 낫다.
    // 실측으로 확인: lock_timeout 은 이 virtualxid 대기에도 적용된다(SQLSTATE 55P03).
    // 30초인 이유: 이 마이그레이션은 API 기동 중(readiness 전)에 돈다. prod 의 API 헬스체크는
    // start_period 40s + 10s×15회 ≈ 190초라, 상한이 길면 테이블 몇 개만 막혀도 기동이 그 창을 넘긴다.
    // (인덱스 빌드 자체의 소요 시간은 여기서 제한하지 않는다 — 빌드는 트래픽을 막지 않는다.)
    exec(conn, "SET lock_timeout = '30s'");
    List<String[]> targets = new ArrayList<>();
    // 스키마 매핑(테넌트1 = data, 그 외 data_t{id})은 DataSchema.java·V124 과 동일해야 한다.
    String discover =
        """
        select case when d.tenant_id = 1 then 'data' else 'data_t' || d.tenant_id end as sch,
               d.table_name
        from dataset d
        where d.storage_type = 'TABLE'
          and d.table_name is not null
        """;
    try (Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery(discover)) {
      while (rs.next()) {
        targets.add(new String[] {rs.getString(1), rs.getString(2)});
      }
    }

    int created = 0;
    int repaired = 0;
    int skipped = 0;
    int failed = 0;
    for (String[] t : targets) {
      String schema = t[0];
      String table = t[1];
      try {
        if (!tableExists(conn, schema, table)) {
          continue; // 카탈로그 행만 있고 물리 테이블이 없는 데이터셋 — V124 백필 루프와 같은 규칙.
        }
        String index = "ix_" + table + "_upd";
        Boolean valid = indexValidity(conn, schema, index);
        if (Boolean.TRUE.equals(valid)) {
          skipped++;
          continue;
        }
        boolean wasInvalid = valid != null;
        if (wasInvalid) {
          // INVALID 잔재 — 버리고 다시 만든다. DROP 도 CONCURRENTLY 여야 쓰기를 막지 않는다.
          exec(conn, "DROP INDEX CONCURRENTLY IF EXISTS " + qualify(schema, index));
        }
        exec(
            conn,
            "CREATE INDEX CONCURRENTLY IF NOT EXISTS "
                + quote(index)
                + " ON "
                + qualify(schema, table)
                + " (_updated_at)");
        // 집계는 **성공한 뒤에** 올린다 — 실패한 테이블이 "신규"와 "실패"에 동시에 잡히면
        // 운영자가 요약만 보고 조치 여부를 판단할 수 없다(실측으로 겪은 혼동).
        if (wasInvalid) {
          repaired++;
        } else {
          created++;
        }
      } catch (SQLException e) {
        // 의도적으로 삼킨다 — 위 "실패 정책" 참고. 인덱스 부재는 성능 저하일 뿐 정합성 문제가 아니고,
        // 여기서 던지면 배포 전체가 막힌다.
        failed++;
        log.warn(
            "V125: {}.{} 의 _updated_at 인덱스 생성 실패 — 증분 처리가 느려질 수 있다(정합성 문제는 아니다)."
                + " 마이그레이션은 계속한다. **자동 수리 경로는 없다** — Flyway 는 성공 기록된 이"
                + " 마이그레이션을 다시 돌리지 않고, complete_updated_at_backfill.sql 도 인덱스는 만들지"
                + " 않는다. 한가할 때 소유자 롤로 직접 실행하라(트랜잭션 밖):"
                + " CREATE INDEX CONCURRENTLY IF NOT EXISTS \"ix_{}_upd\" ON \"{}\".\"{}\" (_updated_at);"
                + " 원인: {}",
            schema,
            table,
            table,
            schema,
            table,
            e.getMessage(),
            e);
      }
    }
    // 같은 커넥션이 풀로 돌아가 다른 용도로 쓰일 수 있으므로 세션 설정을 되돌린다.
    exec(conn, "RESET lock_timeout");
    log.info(
        "V125: _updated_at 인덱스 정리 완료 — 신규 {}, 재생성(INVALID 수리) {}, 이미 정상 {}, 실패 {}",
        created,
        repaired,
        skipped,
        failed);
  }

  /** 인덱스가 없으면 null, 있으면 {@code indisvalid} 값. */
  private static Boolean indexValidity(Connection conn, String schema, String index)
      throws SQLException {
    String sql =
        """
        select i.indisvalid
        from pg_class c
        join pg_namespace n on n.oid = c.relnamespace
        join pg_index i on i.indexrelid = c.oid
        where n.nspname = ? and c.relname = ?
        """;
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, schema);
      ps.setString(2, index);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getBoolean(1) : null;
      }
    }
  }

  private static boolean tableExists(Connection conn, String schema, String table)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement("select to_regclass(format('%I.%I', ?, ?)) is not null")) {
      ps.setString(1, schema);
      ps.setString(2, table);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() && rs.getBoolean(1);
      }
    }
  }

  private static void exec(Connection conn, String sql) throws SQLException {
    try (Statement st = conn.createStatement()) {
      st.execute(sql);
    }
  }

  private static String qualify(String schema, String name) {
    return quote(schema) + "." + quote(name);
  }

  /**
   * 식별자 인용. 테이블명은 {@code DataTableService.validateName} 이 {@code [a-z][a-z0-9_]*} 로
   * 제한하지만, 여기서는 DB 에서 읽은 값이므로 방어적으로 큰따옴표를 이스케이프한다.
   */
  private static String quote(String identifier) {
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }
}
