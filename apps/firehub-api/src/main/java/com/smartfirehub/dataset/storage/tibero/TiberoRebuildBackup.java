package com.smartfirehub.dataset.storage.tibero;

import com.smartfirehub.dataset.storage.DataTableStore.RebuildBackupState;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Tibero TEMP 테이블 재생성 보관본 도우미(WD-256) — 열 구성이 바뀐 러너 TEMP 를 다시 만들기 전에 옛 테이블을 지우지 않고 보관 이름으로 <b>이름만
 * 바꿔</b> 옆에 둔다. 재생성이 롤백되면 되돌리고({@link #restore}), 성공하면 지운다({@link #drop}), 크래시로 남은 보관본은 {@link
 * #reconcile} 이 정리한다.
 *
 * <p>왜 이름 바꾸기인가: 실서버 탐침(Task 5)에서 테이블·시퀀스 이름 바꾸기는 {@code OBJECT_ID}·행·GRANT(_RO·슬롯)·주석을 그대로 유지했다.
 * 그래서 복사·재GRANT 없이 옛 테이블을 그대로 되살릴 수 있다. 삭제 후 재생성 대체 경로는 없다.
 *
 * <p>Tibero 문법(탐침 확인): 테이블 {@code ALTER TABLE "S"."T" RENAME TO "B"}, 색인 {@code ALTER INDEX
 * "S"."IX" RENAME TO "IX2"}, CHECK {@code ALTER TABLE … RENAME CONSTRAINT}. <b>시퀀스는 {@code RENAME
 * "A" TO "B"} 문만</b> 되고({@code ALTER SEQUENCE … RENAME} 은 -7001) 이 문은 스키마 한정자를 받지 않는다 — DDL 커넥션이 곧
 * OWNER 이고 {@code withDdl} 의 문장들이 한정 없는 자기 스키마 객체를 다루므로 그대로 쓴다. 이름을 바꾸면 시퀀스 캐시가 버려져 복원 뒤 다음 값이 앞으로
 * 건너뛸 수 있다(되돌아가거나 중복되지는 않는다).
 *
 * <p>딸린 객체 이름은 모두 {@link TiberoNames} 의 해시 규칙으로 테이블 이름에서 파생되므로, 테이블이 바뀌면 같은 규칙으로 새 이름을 계산해 바꾼다.
 * {@code SYS_} 로 시작하는 시스템 이름(ID PK 제약·그 색인·NOT NULL)은 건드리지 않는다.
 */
@Slf4j
public final class TiberoRebuildBackup {

  /** 보관본 주석 접두 — {@code genia-rebuild:<메타 지문>}. */
  static final String COMMENT_PREFIX = "genia-rebuild:";

  /** 유효한 메타 지문 — SHA-256 앞 32자 소문자 hex. 그 밖의 값은 「판정 불가」로 본다. */
  private static final java.util.regex.Pattern FINGERPRINT =
      java.util.regex.Pattern.compile("[0-9a-f]{32}");

  private static boolean validFingerprint(String fingerprint) {
    return fingerprint != null && FINGERPRINT.matcher(fingerprint).matches();
  }

  private TiberoRebuildBackup() {}

  /** 실행할 DDL 한 단계와 그것을 되돌리는 문장(없으면 null). */
  private record Step(String sql, String undoSql) {}

  /**
   * 테이블을 보관 이름으로 옮기고 원래 테이블의 {@code OBJECT_ID} 를 돌려준다. 호출자는 이어서 같은 이름으로 새 테이블을 만든다.
   *
   * <p>순서: 행 트리거 DROP → 시퀀스·색인·제약 이름 → 테이블 이름 → 주석. 트리거는 테이블 본문에 이름이 박혀 있어 먼저 지운다. 주석은 테이블 이름을 바꾼
   * <b>뒤</b> 보관본에 단다. 중간에 실패하면 그때까지 바꾼 이름을 거꾸로 되돌리고(최선 노력, 실패는 ERROR 로그) 원래 예외를 던진다. 트리거 DROP 도 최선
   * 노력으로 다시 만든다(실패는 ERROR 로그). 그래도 호출자는 {@code finally} 에서 {@code ensureRowTrigger} 로 한 번 더 확인해야
   * 한다(권한이 없으면 되살리지 못할 수 있다).
   *
   * <p><b>DDL 커넥션은 스키마 OWNER 세션이어야 한다</b> — 시퀀스 이름 바꾸기({@code RENAME a TO b})는 스키마 한정자를 받지 않아 현재
   * 스키마의 시퀀스만 바꾼다. 아래 모든 공개 메서드가 같다.
   *
   * @param metaFingerprint 호출자가 계산한 컬럼 메타 지문({@code [0-9a-f]{32}}, 보관본 주석에 기록, {@link #reconcile} 이
   *     비교). null·빈 값이면 주석을 달지 않는다 — 「판정 불가」라 맞춤이 복원 쪽으로 처리한다.
   * @throws IllegalArgumentException 지문이 비어 있지 않은데 형식이 틀릴 때(DDL 전에 거부)
   * @throws IllegalStateException 원래 테이블이 없을 때
   */
  public static long moveToBackup(
      Connection c, String schema, String tableName, String metaFingerprint) throws SQLException {
    boolean hasFingerprint = metaFingerprint != null && !metaFingerprint.isBlank();
    if (hasFingerprint && !validFingerprint(metaFingerprint)) {
      throw new IllegalArgumentException("메타 지문 형식이 올바르지 않습니다(소문자 hex 32자)");
    }
    String backup = TiberoNames.rebuildBackupTableName(schema, tableName);
    Long oid = TiberoCatalog.objectId(c, schema, TiberoNames.physical(tableName), "TABLE");
    if (oid == null) {
      throw new IllegalStateException("재생성할 테이블 '" + tableName + "' 이 없습니다");
    }
    List<Step> steps = new ArrayList<>();
    // 존재할 때만 지운다 — 권한이 없어 만들지 못한 테넌트도 있다. 실패하면 같은 정의로 다시 만든다(최선 노력).
    if (TiberoCatalog.objectId(c, schema, triggerPhysical(schema, tableName), "TRIGGER") != null) {
      steps.add(
          new Step(
              "DROP TRIGGER " + qualified(schema, triggerPhysical(schema, tableName)),
              TiberoDdlSql.createRowTrigger(schema, tableName)));
    }
    steps.addAll(sequenceSteps(c, schema, tableName, backup));
    steps.addAll(companionSteps(c, schema, tableName, tableName, backup));
    steps.add(
        new Step(
            renameTableSql(schema, tableName, backup), renameTableSql(schema, backup, tableName)));
    if (hasFingerprint) {
      steps.add(
          new Step(
              commentSql(schema, backup, COMMENT_PREFIX + metaFingerprint),
              commentSql(schema, backup, "")));
    }
    runWithUndo(c, steps);
    return oid;
  }

  /**
   * 실패한 재생성을 되돌린다 — 보관본이 없거나 {@code OBJECT_ID} 가 {@code expectedObjectId} 와 다르면(다른 실행이 옮긴 것) 아무것도
   * 바꾸지 않고 예외를 던진다. 같으면 원래 이름의 새 테이블·그 시퀀스를 지우고 보관본·딸린 객체 이름을 되돌린 뒤 보관본 주석을 지운다. 트리거 재생성은 호출자(저장소)
   * 몫이다.
   *
   * <p>순서 근거: 새 테이블을 먼저 지워야 원래 이름이 비어 이름을 되돌릴 수 있다. 확인(보관본 OID)이 지우기보다 앞서므로, 보관본이 없을 때 새 테이블까지 지워 둘
   * 다 잃는 일이 없다.
   */
  public static void restore(Connection c, String schema, String tableName, long expectedObjectId)
      throws SQLException {
    String backup = TiberoNames.rebuildBackupTableName(schema, tableName);
    requireBackup(c, schema, tableName, expectedObjectId);
    Long current = TiberoCatalog.objectId(c, schema, TiberoNames.physical(tableName), "TABLE");
    if (current != null) {
      // 새로 만든 테이블(보관본과 OBJECT_ID 가 다르다) — 이름을 비우려고 지운다. 딸린 색인·제약·트리거는 함께 사라진다.
      execute(c, TiberoDdlSql.dropTableOnly(schema, tableName));
    }
    if (TiberoCatalog.objectId(c, schema, sequencePhysical(schema, tableName), "SEQUENCE")
        != null) {
      execute(c, TiberoDdlSql.dropSequence(schema, tableName));
    }
    execute(c, renameTableSql(schema, backup, tableName));
    runAll(c, sequenceSteps(c, schema, backup, tableName));
    runAll(c, companionSteps(c, schema, tableName, backup, tableName));
    execute(c, commentSql(schema, tableName, ""));
  }

  /**
   * 보관본이 있고 {@code OBJECT_ID} 가 {@code expectedObjectId} 인지 확인만 한다(DDL 없음) — 아니면 {@link #restore} 와
   * 같은 문구의 {@code IllegalStateException}. 저장소가 물리 복원을 커밋 뒤로 미루기 전에, 메타를 쓰기 전에 「되돌릴 수 있는가」를 먼저 가리는 데
   * 쓴다.
   */
  public static void requireBackup(
      Connection c, String schema, String tableName, long expectedObjectId) throws SQLException {
    String backup = TiberoNames.rebuildBackupTableName(schema, tableName);
    Long backupOid = TiberoCatalog.objectId(c, schema, TiberoNames.physical(backup), "TABLE");
    if (backupOid == null || backupOid != expectedObjectId) {
      throw new IllegalStateException(
          "테이블 '"
              + tableName
              + "' 의 재생성 보관본 '"
              + backup
              + "' 이 "
              + (backupOid == null ? "없어" : "다른 실행의 것이라")
              + " 되돌릴 수 없습니다");
    }
  }

  /**
   * 보관본(테이블 + 보관본 시퀀스)을 지운다. {@code expectedObjectId} 가 null 이면 무조건, 아니면 같은 {@code OBJECT_ID} 일 때만
   * 지운다.
   *
   * @return 지웠으면 true, 보관본이 없거나 다른 실행의 것이라 건드리지 않았으면 false
   */
  public static boolean drop(Connection c, String schema, String tableName, Long expectedObjectId)
      throws SQLException {
    String backup = TiberoNames.rebuildBackupTableName(schema, tableName);
    Long oid = TiberoCatalog.objectId(c, schema, TiberoNames.physical(backup), "TABLE");
    if (oid == null || (expectedObjectId != null && !expectedObjectId.equals(oid))) {
      return false;
    }
    execute(c, TiberoDdlSql.dropTableOnly(schema, backup));
    // 시퀀스는 테이블과 카탈로그로 이어지지 않아 따로 지운다. 이름만 바뀌고 테이블이 없는 반쯤 상태에서도 없을 수 있다.
    if (TiberoCatalog.objectId(c, schema, sequencePhysical(schema, backup), "SEQUENCE") != null) {
      execute(c, TiberoDdlSql.dropSequence(schema, backup));
    }
    return true;
  }

  /**
   * 크래시 뒤 남은 보관본을 맞춘다 — 「이름 정규화」를 먼저 하고 판정은 나중에 한다.
   *
   * <ol>
   *   <li>정규화(멱등): 원래 테이블이 있으면 그 딸린 객체 이름을 원래 기준으로, 보관본이 있으면 보관 기준으로 맞춘다. 원래 시퀀스가 없고 보관 시퀀스만 있으며 보관
   *       테이블이 없으면(테이블 이름을 바꾸기 전에 죽음) 시퀀스를 되돌린다.
   *   <li>보관본 테이블이 없으면 {@code NONE}.
   *   <li>보관본 주석의 지문이 현재 메타 지문과 <b>다르면</b>(재생성이 커밋돼 메타가 새 열) 보관본을 지운다 → {@code DROPPED_LEFTOVER}.
   *   <li>그 밖(지문이 같음 = 롤백돼 메타가 옛 열, 또는 주석이 없거나 형식이 달라 판정 불가)이면 보관본을 복원한다 → {@code RESTORED}.
   * </ol>
   *
   * 판정 불가를 복원으로 두는 근거: 지우는 쪽으로 틀리면 옛 행을 잃지만, 복원 쪽으로 틀려도 데이터는 남는다. 판정 불가는 보관본 주석이 없거나 형식이 틀림(빈
   * 값·{@code null} 문자열 포함), 또는 현재 지문이 null·빈 값·형식 틀림인 경우다. 지문이 달라도 원래 이름의 테이블이 없으면 보관본이 유일한 사본이라
   * 복원한다.
   */
  public static RebuildBackupState reconcile(
      Connection c, String schema, String tableName, String currentMetaFingerprint)
      throws SQLException {
    String backup = TiberoNames.rebuildBackupTableName(schema, tableName);
    Long original = TiberoCatalog.objectId(c, schema, TiberoNames.physical(tableName), "TABLE");
    Long backupOid = TiberoCatalog.objectId(c, schema, TiberoNames.physical(backup), "TABLE");

    // 1. 정규화 — 어느 이름 바꾸기 단계에서 죽었든 이름이 규칙대로 되돌아오게 한다.
    if (original != null) {
      runAll(c, companionSteps(c, schema, tableName, backup, tableName));
      if (backupOid == null) {
        boolean originalSeq =
            TiberoCatalog.objectId(c, schema, sequencePhysical(schema, tableName), "SEQUENCE")
                != null;
        if (!originalSeq) {
          // 테이블 이름을 바꾸기 전에 죽음 — 보관 이름으로 바뀐 시퀀스를 되돌린다.
          runAll(c, sequenceSteps(c, schema, backup, tableName));
        } else if (TiberoCatalog.objectId(c, schema, sequencePhysical(schema, backup), "SEQUENCE")
            != null) {
          // 보관 테이블이 없는데 보관 시퀀스가 남음 — drop() 이 DROP TABLE 과 DROP SEQUENCE 사이에서 죽은 고아다.
          execute(c, TiberoDdlSql.dropSequence(schema, backup));
        }
      }
    }
    if (backupOid != null) {
      runAll(c, companionSteps(c, schema, backup, tableName, backup));
    }

    // 2. 보관본이 없으면 할 일 없음(원래 테이블 트리거 확인은 호출자 몫).
    if (backupOid == null) {
      return RebuildBackupState.NONE;
    }

    // 3·4. 판정.
    String comment = TiberoCatalog.tableComment(c, schema, TiberoNames.physical(backup));
    String stored =
        comment != null && comment.startsWith(COMMENT_PREFIX)
            ? comment.substring(COMMENT_PREFIX.length())
            : null;
    // 지우는 것은 두 지문이 모두 유효하고 서로 다르며, 새 테이블이 원래 이름으로 이미 있을 때뿐이다(보관본이 유일한 사본이면 지우지 않는다).
    if (validFingerprint(stored)
        && validFingerprint(currentMetaFingerprint)
        && !stored.equals(currentMetaFingerprint)
        && original != null) {
      drop(c, schema, tableName, null);
      return RebuildBackupState.DROPPED_LEFTOVER;
    }
    restore(c, schema, tableName, backupOid);
    return RebuildBackupState.RESTORED;
  }

  // ---------------------------------------------------------------- 딸린 객체 이름

  /**
   * 시퀀스 이름 바꾸기 단계 — {@code from} 테이블의 시퀀스가 있으면 {@code to} 이름으로 바꾼다(없으면 단계 없음). {@code to} 이름이 이미
   * 쓰이고 있는지는 확인하지 않는다 — 그러면 서버가 거절하고 호출자 흐름(되돌림·예외)이 처리한다.
   */
  private static List<Step> sequenceSteps(Connection c, String schema, String from, String to)
      throws SQLException {
    String fromName = sequencePhysical(schema, from);
    String toName = sequencePhysical(schema, to);
    if (TiberoCatalog.objectId(c, schema, fromName, "SEQUENCE") == null) {
      return List.of();
    }
    return List.of(
        new Step(
            "RENAME " + TiberoNames.quote(fromName) + " TO " + TiberoNames.quote(toName),
            "RENAME " + TiberoNames.quote(toName) + " TO " + TiberoNames.quote(fromName)));
  }

  /**
   * 지금 {@code currentTable} 이라는 이름의 테이블에 달린 색인·CHECK 제약 중 {@code fromBase} 기준 이름인 것을 {@code toBase}
   * 기준 이름으로 바꾸는 단계. 이미 {@code toBase} 기준이거나 규칙에 없는 이름(시스템 {@code SYS_…})은 건드리지 않아 멱등이다.
   */
  private static List<Step> companionSteps(
      Connection c, String schema, String currentTable, String fromBase, String toBase)
      throws SQLException {
    String physTable = TiberoNames.physical(currentTable);
    String pkFrom = TiberoNames.primaryKeyIndexName(schema, fromBase);
    String pkTo = TiberoNames.primaryKeyIndexName(schema, toBase);
    // 색인 이름 → 새 이름(복합 PK 색인은 컬럼 수만큼 행이 나오므로 이름으로 한 번만).
    Map<String, String> indexRenames = new LinkedHashMap<>();
    for (TiberoCatalog.NamedColumn row : TiberoCatalog.tableIndexColumns(c, schema, physTable)) {
      if (row.name().equals(pkFrom)) {
        indexRenames.put(row.name(), pkTo);
      } else if (row.name().equals(TiberoNames.indexName(schema, fromBase, row.column()))) {
        indexRenames.put(row.name(), TiberoNames.indexName(schema, toBase, row.column()));
      }
    }
    List<Step> steps = new ArrayList<>();
    for (Map.Entry<String, String> e : indexRenames.entrySet()) {
      steps.add(
          new Step(
              "ALTER INDEX "
                  + qualified(schema, e.getKey())
                  + " RENAME TO "
                  + TiberoNames.quote(e.getValue()),
              "ALTER INDEX "
                  + qualified(schema, e.getValue())
                  + " RENAME TO "
                  + TiberoNames.quote(e.getKey())));
    }
    Map<String, String> checkRenames = new LinkedHashMap<>();
    for (TiberoCatalog.NamedColumn row :
        TiberoCatalog.checkConstraintColumns(c, schema, physTable)) {
      if (row.name().equals(TiberoNames.checkConstraintName(schema, fromBase, row.column()))) {
        checkRenames.put(row.name(), TiberoNames.checkConstraintName(schema, toBase, row.column()));
      }
    }
    String qt = TiberoNames.table(schema, currentTable);
    for (Map.Entry<String, String> e : checkRenames.entrySet()) {
      steps.add(
          new Step(
              "ALTER TABLE "
                  + qt
                  + " RENAME CONSTRAINT "
                  + TiberoNames.quote(e.getKey())
                  + " TO "
                  + TiberoNames.quote(e.getValue()),
              "ALTER TABLE "
                  + qt
                  + " RENAME CONSTRAINT "
                  + TiberoNames.quote(e.getValue())
                  + " TO "
                  + TiberoNames.quote(e.getKey())));
    }
    return steps;
  }

  // ---------------------------------------------------------------- 실행

  /**
   * 단계를 차례로 실행하고, 중간에 실패하면 이미 실행한 단계의 되돌림 문장을 거꾸로 실행한다. 되돌림 실패는 ERROR 로 남기고 다음 되돌림을 계속한다 — 원래 예외가 더
   * 중요하다.
   */
  private static void runWithUndo(Connection c, List<Step> steps) throws SQLException {
    List<Step> done = new ArrayList<>();
    try {
      for (Step s : steps) {
        execute(c, s.sql());
        done.add(s);
      }
    } catch (SQLException | RuntimeException e) {
      for (int i = done.size() - 1; i >= 0; i--) {
        String undo = done.get(i).undoSql();
        if (undo == null) {
          continue;
        }
        try {
          execute(c, undo);
        } catch (SQLException | RuntimeException undoError) {
          log.error("재생성 보관 되돌리기 실패 — 수동 확인 필요: {}", undo, undoError);
          e.addSuppressed(undoError);
        }
      }
      throw e;
    }
  }

  /** 되돌림 없이 단계를 차례로 실행한다(정규화·복원처럼 멱등이라 실패해도 되돌릴 필요가 없는 경로). */
  private static void runAll(Connection c, List<Step> steps) throws SQLException {
    for (Step s : steps) {
      execute(c, s.sql());
    }
  }

  private static void execute(Connection c, String sql) throws SQLException {
    try (Statement st = c.createStatement()) {
      st.execute(sql);
    }
  }

  private static String renameTableSql(String schema, String from, String to) {
    return "ALTER TABLE "
        + TiberoNames.table(schema, from)
        + " RENAME TO "
        + TiberoNames.quote(TiberoNames.physical(to));
  }

  /** 주석 문장. 따옴표는 우리 지문(hex)에만 쓰이지만 방어적으로 이스케이프한다. */
  private static String commentSql(String schema, String table, String text) {
    return "COMMENT ON TABLE "
        + TiberoNames.table(schema, table)
        + " IS '"
        + text.replace("'", "''")
        + "'";
  }

  private static String qualified(String schema, String physicalName) {
    return TiberoNames.qualified(schema, physicalName);
  }

  private static String sequencePhysical(String schema, String table) {
    return TiberoNames.sequenceName(schema, table);
  }

  private static String triggerPhysical(String schema, String table) {
    return TiberoNames.triggerName(schema, table);
  }
}
