package com.smartfirehub.dataset.storage.tibero;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.storage.DataTableStore.RebuildBackupState;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Tibero TEMP 재생성 보관본 실서버 테스트(WD-256) — 실서버가 있을 때만 돈다(실행: {@code scripts/tibero-test.sh api}).
 *
 * <p>일회용 계정 셋(OWNER {@code WD256O<난수>}, 읽기 {@code <OWNER>_RO}, 슬롯 {@code GENIA_T9<난수>_S1})을 DBA 로
 * 만들고, OWNER 세션에서 실제 DDL 빌더({@link TiberoDdlSql})가 만든 테이블에 {@link TiberoRebuildBackup} 을 적용해 확인한다:
 * 보관 뒤 같은 이름·다른 열 테이블이 이름 충돌 없이 만들어지고, 복원하면 옛 열·행·{@code OBJECT_ID}·GRANT·색인·제약이 돌아오며 시퀀스 다음 값은 이전
 * 최대 ID 보다 크다(Tibero 가 이름 바꿀 때 캐시를 버려 값이 건너뛸 수 있어 정확한 값은 단정하지 않는다). 만든 계정만 마지막에 지운다. 접속 정보는 출력하지
 * 않는다.
 */
@EnabledIfEnvironmentVariable(named = "TIBERO_TEST_HOST", matches = ".+")
class TiberoLiveRebuildTest {

  private static final String FP = "a".repeat(32);
  private static final String FP1 = "c".repeat(32);
  private static final String OTHER_FP = "b".repeat(32);

  private static void run(Connection c, String sql) throws SQLException {
    try (Statement st = c.createStatement()) {
      st.execute(sql);
    }
  }

  private static List<String> column(Connection c, String sql) throws SQLException {
    List<String> out = new ArrayList<>();
    try (Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      while (rs.next()) {
        out.add(rs.getString(1));
      }
    }
    return out;
  }

  private static boolean userExists(Connection dba, String user) throws SQLException {
    return !column(dba, "SELECT 1 FROM DBA_USERS WHERE USERNAME = '" + user + "'").isEmpty();
  }

  /** 테이블의 우리 규칙 색인·제약·시퀀스·GRANT 상태를 이름순 문자열로 모은다(이름 바꾸기 전후 비교용). */
  private static List<String> shape(Connection c, String schema, String table) throws SQLException {
    String phys = TiberoNames.physical(table);
    TreeSet<String> out = new TreeSet<>();
    for (TiberoCatalog.NamedColumn r : TiberoCatalog.tableIndexColumns(c, schema, phys)) {
      // PK 의 SYS_ 색인은 이름 바꾸기 대상이 아니라 그대로 있는지만 본다.
      out.add("index " + r.name() + " " + r.column());
    }
    for (TiberoCatalog.NamedColumn r : TiberoCatalog.checkConstraintColumns(c, schema, phys)) {
      out.add("check " + r.name() + " " + r.column());
    }
    for (String g :
        column(
            c,
            "SELECT GRANTEE || ':' || PRIVILEGE FROM ALL_TAB_PRIVS WHERE TABLE_NAME = '"
                + phys
                + "'")) {
      out.add("grant " + g);
    }
    return new ArrayList<>(out);
  }

  private static List<DatasetColumnRequest> oldColumns() {
    return List.of(
        new DatasetColumnRequest("name", "name", "TEXT", null, true, true, null, false),
        new DatasetColumnRequest("code", "code", "TEXT", 20, true, false, null, true),
        new DatasetColumnRequest("flag", "flag", "BOOLEAN", null, true, true, null, false));
  }

  private static List<DatasetColumnRequest> newColumns() {
    return List.of(
        new DatasetColumnRequest("other", "other", "TEXT", null, true, true, null, false));
  }

  /** 테이블·읽기 GRANT·슬롯 GRANT·행 트리거와 행 세 개를 만든다. */
  private static void createOld(Connection o, String owner, String slot, String t)
      throws SQLException {
    for (String sql : TiberoDdlSql.createTable(owner, t, oldColumns())) {
      run(o, sql);
    }
    run(o, TiberoDdlSql.grantSelectToSlot(owner, TiberoNames.physical(t), slot));
    run(o, TiberoDdlSql.createRowTrigger(owner, t));
    String tbl = TiberoNames.table(owner, t);
    for (int i = 1; i <= 3; i++) {
      run(
          o,
          "INSERT INTO "
              + tbl
              + " (\"NAME\", \"CODE\", \"FLAG\") VALUES ('n"
              + i
              + "', 'c"
              + i
              + "', "
              + (i % 2)
              + ")");
    }
  }

  private static void createNew(Connection o, String owner, String t) throws SQLException {
    for (String sql : TiberoDdlSql.createTable(owner, t, newColumns())) {
      run(o, sql);
    }
  }

  private static Long oid(Connection o, String owner, String t) throws SQLException {
    return TiberoCatalog.objectId(o, owner, TiberoNames.physical(t), "TABLE");
  }

  @Test
  void backupRestoreDropReconcile_againstRealServer() throws Exception {
    TiberoTestEnvironment.assumeDriverPresent();
    TiberoTestEnvironment.Dba dba = TiberoTestEnvironment.dbaFromEnv();
    String suffix = String.valueOf(ThreadLocalRandom.current().nextInt(100000, 999999));
    String owner = "WD256O" + suffix;
    String ro = owner + "_RO";
    String slot = "GENIA_T9" + suffix + "_S1";
    String pw = "Pw" + ThreadLocalRandom.current().nextLong(100000000L, 999999999L) + "x";
    List<String> created = new ArrayList<>();
    Throwable primary = null;
    try (Connection d = TiberoTestEnvironment.openDba(dba)) {
      d.setAutoCommit(true);
      // 공유 서버 — 이미 있는 이름이면 우리가 만든 계정이 아니므로 시작하지 않는다.
      for (String u : List.of(owner, ro, slot)) {
        assertThat(userExists(d, u)).as("계정 이름 충돌 %s", u).isFalse();
      }
      for (String u : List.of(owner, ro, slot)) {
        run(d, "CREATE USER \"" + u + "\" IDENTIFIED BY \"" + pw + "\" DEFAULT TABLESPACE \"USR\"");
        created.add(u);
      }
      run(d, "ALTER USER \"" + owner + "\" QUOTA UNLIMITED ON \"USR\"");
      run(
          d,
          "GRANT CREATE SESSION, CREATE TABLE, CREATE VIEW, CREATE SEQUENCE, CREATE TRIGGER TO \""
              + owner
              + "\"");
      run(d, "GRANT CREATE SESSION TO \"" + ro + "\"");
      run(d, "GRANT CREATE SESSION TO \"" + slot + "\"");

      try (Connection o = DriverManager.getConnection(dba.target().jdbcUrl(), owner, pw)) {
        o.setAutoCommit(true);
        restoreKeepsEverything(o, owner, slot);
        dropKeepsNewTable(o, owner, slot);
        reconcileCases(o, owner, slot);
      }
    } catch (Throwable e) {
      primary = e;
      throw e;
    } finally {
      // 정리 실패가 테스트의 원래 실패를 가리지 않게, 이미 실패 중이면 suppressed 로만 붙인다.
      try (Connection d = TiberoTestEnvironment.openDba(dba);
          Statement st = d.createStatement()) {
        // 만든 계정만, 만든 역순으로 지운다.
        for (int i = created.size() - 1; i >= 0; i--) {
          st.execute("DROP USER \"" + created.get(i) + "\" CASCADE");
        }
      } catch (Exception cleanup) {
        if (primary == null) {
          throw cleanup;
        }
        primary.addSuppressed(cleanup);
      }
    }
  }

  /** 보관 → 같은 이름·다른 열 생성 성공 → 복원하면 옛 상태가 돌아온다. */
  private void restoreKeepsEverything(Connection o, String owner, String slot) throws Exception {
    String t = "rb_keep";
    createOld(o, owner, slot, t);
    long oid = oid(o, owner, t);
    List<String> shapeBefore = shape(o, owner, t);
    assertThat(shapeBefore).anyMatch(s -> s.startsWith("grant " + owner + "_RO:"));
    assertThat(shapeBefore).anyMatch(s -> s.startsWith("grant " + slot + ":"));
    String tbl = TiberoNames.table(owner, t);
    List<String> rowsBefore = column(o, "SELECT \"NAME\" FROM " + tbl + " ORDER BY \"ID\"");
    long maxIdBefore = Long.parseLong(column(o, "SELECT MAX(\"ID\") FROM " + tbl).get(0));

    assertThat(TiberoRebuildBackup.moveToBackup(o, owner, t, FP1)).isEqualTo(oid);
    String backup = TiberoNames.rebuildBackupTableName(owner, t);
    assertThat(oid(o, owner, t)).isNull();
    assertThat(oid(o, owner, backup)).isEqualTo(oid);
    assertThat(TiberoCatalog.tableComment(o, owner, TiberoNames.physical(backup)))
        .isEqualTo("genia-rebuild:" + FP1);

    // 시퀀스 이름을 바꾸면 Tibero 는 ID DEFAULT 의 옛 이름을 자동 갱신하지 않는다. 보관본도 INSERT 가 가능해야 복원 중간 상태가 안전하다.
    String backupTable = TiberoNames.table(owner, backup);
    run(o, "INSERT INTO " + backupTable + " (\"NAME\", \"CODE\") VALUES ('probe', 'probe')");
    assertThat(column(o, "SELECT \"ID\" FROM " + backupTable + " WHERE \"NAME\" = 'probe'"))
        .hasSize(1);
    run(o, "DELETE FROM " + backupTable + " WHERE \"NAME\" = 'probe'");

    // 같은 이름·다른 열로 만들어도 색인·시퀀스·제약 이름이 부딪치지 않는다.
    createNew(o, owner, t);
    long newOid = oid(o, owner, t);
    assertThat(newOid).isNotEqualTo(oid);

    TiberoRebuildBackup.restore(o, owner, t, oid);

    assertThat(oid(o, owner, t)).isEqualTo(oid);
    assertThat(oid(o, owner, backup)).isNull();
    assertThat(shape(o, owner, t)).isEqualTo(shapeBefore);
    assertThat(column(o, "SELECT \"NAME\" FROM " + tbl + " ORDER BY \"ID\"")).isEqualTo(rowsBefore);
    assertThat(TiberoCatalog.tableComment(o, owner, TiberoNames.physical(t))).isNull();
    assertThat(
            column(
                o,
                "SELECT COUNT(*) FROM USER_TAB_COLUMNS WHERE TABLE_NAME = '"
                    + TiberoNames.physical(t)
                    + "' AND COLUMN_NAME = 'OTHER'"))
        .containsExactly("0");

    // ID 는 복원이 원래 시퀀스 이름으로 다시 묶은 열 기본값이 채운다(트리거는 UPDATE 전용이라 INSERT 에 관여하지 않는다). 이 테스트가 restore
    // 재바인딩을 실서버로 고정하는 근거다. 트리거는 호출자가 다시 만든다. 값은 캐시 폐기로 건너뛸 수 있으므로 "이전 최대보다 크고 유일" 만 본다.
    run(o, TiberoDdlSql.createRowTrigger(owner, t));
    run(o, "INSERT INTO " + tbl + " (\"NAME\", \"CODE\") VALUES ('n4', 'c4')");
    run(o, "INSERT INTO " + tbl + " (\"NAME\", \"CODE\") VALUES ('n5', 'c5')");
    List<String> ids = column(o, "SELECT \"ID\" FROM " + tbl + " WHERE \"NAME\" IN ('n4','n5')");
    assertThat(ids).hasSize(2).doesNotHaveDuplicates();
    assertThat(ids).allSatisfy(id -> assertThat(Long.parseLong(id)).isGreaterThan(maxIdBefore));
    assertThat(column(o, "SELECT COUNT(DISTINCT \"ID\") FROM " + tbl)).containsExactly("5");
  }

  /** 보관 → 새 테이블 → drop(oid) 하면 보관본·보관 시퀀스만 사라지고 새 테이블은 그대로다. */
  private void dropKeepsNewTable(Connection o, String owner, String slot) throws Exception {
    String t = "rb_drop";
    createOld(o, owner, slot, t);
    long oid = oid(o, owner, t);
    TiberoRebuildBackup.moveToBackup(o, owner, t, FP1);
    createNew(o, owner, t);
    long newOid = oid(o, owner, t);
    String backup = TiberoNames.rebuildBackupTableName(owner, t);

    assertThat(TiberoRebuildBackup.drop(o, owner, t, oid + 1)).isFalse();
    assertThat(TiberoRebuildBackup.drop(o, owner, t, oid)).isTrue();

    assertThat(oid(o, owner, backup)).isNull();
    assertThat(
            TiberoCatalog.objectId(o, owner, TiberoNames.sequenceName(owner, backup), "SEQUENCE"))
        .isNull();
    assertThat(oid(o, owner, t)).isEqualTo(newOid);
    assertThat(TiberoCatalog.objectId(o, owner, TiberoNames.sequenceName(owner, t), "SEQUENCE"))
        .isNotNull();
  }

  /** 크래시 뒤 맞춤 네 경우. */
  private void reconcileCases(Connection o, String owner, String slot) throws Exception {
    // (a) 보관 후 새 테이블 없이 같은 지문 → RESTORED
    String a = "rb_rec_a";
    createOld(o, owner, slot, a);
    long oidA = oid(o, owner, a);
    TiberoRebuildBackup.moveToBackup(o, owner, a, FP);
    assertThat(TiberoRebuildBackup.reconcile(o, owner, a, FP))
        .isEqualTo(RebuildBackupState.RESTORED);
    assertThat(oid(o, owner, a)).isEqualTo(oidA);

    // (b) 보관 후 다른 열 새 테이블 + 같은 지문 → RESTORED(새 테이블은 지워지고 옛 테이블이 돌아온다)
    String b = "rb_rec_b";
    createOld(o, owner, slot, b);
    long oidB = oid(o, owner, b);
    TiberoRebuildBackup.moveToBackup(o, owner, b, FP);
    createNew(o, owner, b);
    assertThat(TiberoRebuildBackup.reconcile(o, owner, b, FP))
        .isEqualTo(RebuildBackupState.RESTORED);
    assertThat(oid(o, owner, b)).isEqualTo(oidB);
    // 원래 이름의 시퀀스는 복원된 옛 것이고 보관 시퀀스는 없다(새 테이블의 시퀀스는 테이블과 함께 지워졌다).
    String backupB = TiberoNames.rebuildBackupTableName(owner, b);
    assertThat(TiberoCatalog.objectId(o, owner, TiberoNames.sequenceName(owner, b), "SEQUENCE"))
        .isNotNull();
    assertThat(
            TiberoCatalog.objectId(o, owner, TiberoNames.sequenceName(owner, backupB), "SEQUENCE"))
        .isNull();
    assertThat(column(o, "SELECT COUNT(*) FROM " + TiberoNames.table(owner, b)))
        .containsExactly("3");

    // (c) 다른 지문 → DROPPED_LEFTOVER(보관본만 지워지고 새 테이블은 남는다)
    String c = "rb_rec_c";
    createOld(o, owner, slot, c);
    TiberoRebuildBackup.moveToBackup(o, owner, c, FP);
    createNew(o, owner, c);
    long newC = oid(o, owner, c);
    assertThat(TiberoRebuildBackup.reconcile(o, owner, c, OTHER_FP))
        .isEqualTo(RebuildBackupState.DROPPED_LEFTOVER);
    assertThat(oid(o, owner, c)).isEqualTo(newC);
    String backupC = TiberoNames.rebuildBackupTableName(owner, c);
    assertThat(oid(o, owner, backupC)).isNull();
    assertThat(
            TiberoCatalog.objectId(o, owner, TiberoNames.sequenceName(owner, backupC), "SEQUENCE"))
        .isNull();
    assertThat(TiberoCatalog.objectId(o, owner, TiberoNames.sequenceName(owner, c), "SEQUENCE"))
        .isNotNull();

    // (d) 시퀀스만 보관 이름으로 바뀐 상태 → 정규화 후 NONE
    String d = "rb_rec_d";
    createOld(o, owner, slot, d);
    String backupD = TiberoNames.rebuildBackupTableName(owner, d);
    run(
        o,
        "RENAME \""
            + TiberoNames.sequenceName(owner, d)
            + "\" TO \""
            + TiberoNames.sequenceName(owner, backupD)
            + "\"");
    assertThat(TiberoRebuildBackup.reconcile(o, owner, d, FP)).isEqualTo(RebuildBackupState.NONE);
    assertThat(TiberoCatalog.objectId(o, owner, TiberoNames.sequenceName(owner, d), "SEQUENCE"))
        .isNotNull();
    assertThat(
            TiberoCatalog.objectId(o, owner, TiberoNames.sequenceName(owner, backupD), "SEQUENCE"))
        .isNull();
  }
}
