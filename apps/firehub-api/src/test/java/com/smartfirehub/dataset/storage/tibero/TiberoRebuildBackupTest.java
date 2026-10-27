package com.smartfirehub.dataset.storage.tibero;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.storage.DataTableStore.RebuildBackupState;
import java.sql.SQLException;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Tibero TEMP 재생성 보관본 도우미(WD-256) 단위 테스트 — 카탈로그와 이름 바꾸기 DDL 을 흉내 내는 메모리 속 가짜 서버로 문장 순서와 상태 전이를
 * 검증한다. 실제 서버 동작은 {@code TiberoLiveRebuildTest} 가 맡는다.
 */
class TiberoRebuildBackupTest {

  private static final String S = "GENIA_T5";
  private static final String T = "ptmp_1_a";
  private static final String B = TiberoNames.rebuildBackupTableName(S, T);
  private static final String FP = "a".repeat(32);
  private static final String OTHER_FP = "b".repeat(32);

  private static final String TP = TiberoNames.physical(T);
  private static final String BP = TiberoNames.physical(B);

  /** 보관된 상태(테이블 이름이 B 이고 딸린 객체가 B 기준)를 만든다. */
  private static FakeTibero backedUp(long oid, String fingerprintComment) {
    FakeTibero f = new FakeTibero();
    f.seed(B, oid);
    f.triggers.clear();
    if (fingerprintComment != null) f.comments.put(BP, fingerprintComment);
    return f;
  }

  @Test
  void moveToBackup_ordersTriggerThenCompanionsThenTableThenComment() throws Exception {
    FakeTibero f = new FakeTibero();
    f.seed(T, 77L);

    long oid = TiberoRebuildBackup.moveToBackup(f.connection(), S, T, FP);

    assertThat(oid).isEqualTo(77L);
    int trigger = indexOf(f, "DROP TRIGGER");
    int seq = indexOf(f, "RENAME \"" + TiberoNames.sequenceName(S, T) + "\"");
    int index = indexOf(f, "ALTER INDEX");
    int table = indexOf(f, "ALTER TABLE \"GENIA_T5\".\"PTMP_1_A\" RENAME TO");
    int comment = indexOf(f, "COMMENT ON TABLE");
    assertThat(trigger).isZero();
    assertThat(table).isGreaterThan(Math.max(seq, index));
    assertThat(comment).isGreaterThan(table);
    // 상태: 원래 이름은 비고 보관본이 같은 OBJECT_ID 로 있으며 딸린 객체가 보관 기준 이름이다.
    assertThat(f.tables).containsOnlyKeys(BP).containsEntry(BP, 77L);
    assertThat(f.sequences).containsExactly(TiberoNames.sequenceName(S, B));
    assertThat(f.idDefaults).containsEntry(BP, TiberoNames.sequenceName(S, B));
    assertThat(f.indexes)
        .containsOnlyKeys(
            TiberoNames.indexName(S, B, "NAME"),
            TiberoNames.indexName(S, B, "_UPDATED_AT"),
            TiberoNames.primaryKeyIndexName(S, B),
            "SYS_C_" + TP);
    assertThat(f.checks).containsOnlyKeys(TiberoNames.checkConstraintName(S, B, "FLAG"));
    assertThat(f.triggers).isEmpty();
    assertThat(f.comments).containsEntry(BP, "genia-rebuild:" + FP);
  }

  /**
   * 시퀀스가 없는 테이블도 보관된다 — 없는 시퀀스를 가리키는 DEFAULT MODIFY 는 실서버에서 ALTER 시점에 JDBC-8034 로 거부되므로(리뷰 M-1,
   * {@code TiberoWave5ProbeTest.modifyDefaultToMissingSequence}) 재바인딩을 건너뛰어야 한다.
   */
  @Test
  void moveToBackup_withoutSequence_skipsRebind() throws Exception {
    FakeTibero f = new FakeTibero();
    f.seed(T, 77L);
    f.sequences.clear();
    f.idDefaults.clear();

    assertThat(TiberoRebuildBackup.moveToBackup(f.connection(), S, T, FP)).isEqualTo(77L);

    assertThat(f.tables).containsOnlyKeys(BP);
    assertThat(f.log).noneMatch(sql -> sql.contains(" MODIFY (\"ID\" DEFAULT "));
  }

  /**
   * 옛 모양(ID 기본값 없음) 테이블을 보관하다 뒤 단계가 실패해도, 트리거 되돌리기(UPDATE 전용 트리거 재생성)보다 ID 기본값이 먼저 있어야 한다 (리뷰 M-3,
   * 스펙 §5.1 「ID 기본값 → 트리거」). 그래서 첫 단계 앞에서 원래 시퀀스로 기본값을 둔다.
   */
  @Test
  void moveToBackup_legacyWithoutDefault_bindsDefaultBeforeTriggerUndo() {
    FakeTibero f = new FakeTibero();
    f.seed(T, 77L);
    f.idDefaults.remove(TP);
    f.failOn = sql -> sql.startsWith("ALTER TABLE") && sql.contains(" RENAME TO ");

    assertThatThrownBy(() -> TiberoRebuildBackup.moveToBackup(f.connection(), S, T, FP))
        .isInstanceOf(SQLException.class);

    assertThat(f.idDefaults).containsEntry(TP, TiberoNames.sequenceName(S, T));
    int bind = indexOf(f, TiberoDdlSql.setIdDefault(S, T));
    assertThat(bind).isZero();
    assertThat(bind).isLessThan(indexOf(f, "CREATE OR REPLACE TRIGGER"));
  }

  @Test
  void moveToBackup_missingTable_throwsPgMessage() {
    FakeTibero f = new FakeTibero();
    assertThatThrownBy(() -> TiberoRebuildBackup.moveToBackup(f.connection(), S, T, FP))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("재생성할 테이블 '" + T + "' 이 없습니다");
    assertThat(f.log).isEmpty();
  }

  @Test
  void moveToBackup_midFailure_undoesRenamesInReverseRecreatesTriggerAndRethrowsOriginal() {
    FakeTibero f = new FakeTibero();
    f.seed(T, 77L);
    Set<String> before = f.everything();
    f.failOn = sql -> sql.startsWith("ALTER TABLE") && sql.contains(" RENAME TO ");

    assertThatThrownBy(() -> TiberoRebuildBackup.moveToBackup(f.connection(), S, T, FP))
        .isInstanceOf(SQLException.class)
        .hasMessage("주입된 실패");

    // 이름과 트리거까지 모두 원래대로.
    assertThat(f.everything()).isEqualTo(before);
    // 되돌림은 실행의 역순: 실패한 테이블 이름 바꾸기 바로 다음에 마지막으로 바꾼 제약이 되돌려지고,
    // 첫 번째로 바꾼 시퀀스가 그 앞(마지막에서 둘째)이며 트리거 재생성이 맨 마지막이다.
    int failed = indexOf(f, "ALTER TABLE \"GENIA_T5\".\"PTMP_1_A\" RENAME TO");
    assertThat(f.log.get(failed + 1)).contains("RENAME CONSTRAINT");
    assertThat(f.log.get(f.log.size() - 2))
        .startsWith("RENAME \"" + TiberoNames.sequenceName(S, B));
    assertThat(f.log.get(f.log.size() - 1)).startsWith("CREATE OR REPLACE TRIGGER");
  }

  @Test
  void moveToBackup_undoFailure_isSuppressedOnOriginalException() {
    FakeTibero f = new FakeTibero();
    f.seed(T, 77L);
    f.failOn =
        sql ->
            (sql.startsWith("ALTER TABLE") && sql.contains(" RENAME TO "))
                || sql.startsWith("CREATE OR REPLACE TRIGGER");

    assertThatThrownBy(() -> TiberoRebuildBackup.moveToBackup(f.connection(), S, T, FP))
        .isInstanceOf(SQLException.class)
        .hasMessage("주입된 실패")
        .satisfies(e -> assertThat(e.getSuppressed()).hasSize(1));
  }

  @Test
  void moveToBackup_blankFingerprint_writesNoComment_invalidIsRejectedBeforeDdl() throws Exception {
    for (String fp : new String[] {null, "", "  "}) {
      FakeTibero f = new FakeTibero();
      f.seed(T, 77L);
      TiberoRebuildBackup.moveToBackup(f.connection(), S, T, fp);
      assertThat(f.log).noneMatch(x -> x.startsWith("COMMENT"));
      assertThat(f.comments).isEmpty();
    }
    FakeTibero g = new FakeTibero();
    g.seed(T, 77L);
    assertThatThrownBy(() -> TiberoRebuildBackup.moveToBackup(g.connection(), S, T, "fp1"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(g.log).isEmpty();
  }

  @Test
  void moveToBackup_commentFailure_alsoRestoresTableName() {
    FakeTibero f = new FakeTibero();
    f.seed(T, 77L);
    f.failOn = sql -> sql.startsWith("COMMENT ON TABLE") && sql.contains("genia-rebuild");

    assertThatThrownBy(() -> TiberoRebuildBackup.moveToBackup(f.connection(), S, T, FP))
        .isInstanceOf(SQLException.class);

    assertThat(f.tables).containsOnlyKeys(TP);
    assertThat(f.sequences).containsExactly(TiberoNames.sequenceName(S, T));
    assertThat(f.idDefaults).containsEntry(TP, TiberoNames.sequenceName(S, T));
  }

  @Test
  void restore_objectIdMismatch_changesNothing() {
    FakeTibero f = backedUp(77L, "genia-rebuild:" + FP);
    f.seed(T, 99L);
    Set<String> before = f.everything();
    int logged = f.log.size();

    assertThatThrownBy(() -> TiberoRebuildBackup.restore(f.connection(), S, T, 78L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("다른 실행의 것이라");

    assertThat(f.log).hasSize(logged);
    assertThat(f.everything()).isEqualTo(before);
  }

  @Test
  void restore_missingBackup_throwsAndKeepsNewTable() {
    FakeTibero f = new FakeTibero();
    f.seed(T, 99L);
    Set<String> before = f.everything();

    assertThatThrownBy(() -> TiberoRebuildBackup.restore(f.connection(), S, T, 77L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("없어");

    assertThat(f.log).isEmpty();
    assertThat(f.everything()).isEqualTo(before);
  }

  @Test
  void restore_dropsNewTableThenRenamesBackAndClearsComment() throws Exception {
    FakeTibero f = backedUp(77L, "genia-rebuild:" + FP);
    f.seed(T, 99L); // 같은 이름으로 새로 만든 테이블

    TiberoRebuildBackup.restore(f.connection(), S, T, 77L);

    assertThat(f.tables).containsOnlyKeys(TP).containsEntry(TP, 77L);
    assertThat(f.sequences).containsExactly(TiberoNames.sequenceName(S, T));
    assertThat(f.idDefaults).containsEntry(TP, TiberoNames.sequenceName(S, T));
    assertThat(f.indexes)
        .containsOnlyKeys(
            TiberoNames.indexName(S, T, "NAME"),
            TiberoNames.indexName(S, T, "_UPDATED_AT"),
            TiberoNames.primaryKeyIndexName(S, T),
            "SYS_C_" + BP);
    assertThat(f.checks).containsOnlyKeys(TiberoNames.checkConstraintName(S, T, "FLAG"));
    assertThat(f.comments).isEmpty();
    assertThat(indexOf(f, "DROP TABLE")).isLessThan(indexOf(f, "ALTER TABLE \"GENIA_T5\".\"" + BP));
  }

  /** 보관 시퀀스가 없으면 복원은 테이블 이름만 되돌리고 없는 시퀀스로 DEFAULT 를 묶으려 하지 않는다(리뷰 M-1). */
  @Test
  void restore_withoutBackupSequence_restoresWithoutRebind() throws Exception {
    FakeTibero f = backedUp(77L, "genia-rebuild:" + FP);
    f.sequences.clear();
    f.idDefaults.clear();

    TiberoRebuildBackup.restore(f.connection(), S, T, 77L);

    assertThat(f.tables).containsOnlyKeys(TP).containsEntry(TP, 77L);
    assertThat(f.log).noneMatch(sql -> sql.contains(" MODIFY (\"ID\" DEFAULT "));
  }

  /** 원래·보관 시퀀스가 모두 없는 테이블의 맞춤은 매번 실패하지 않고 NONE 이다(리뷰 M-1). */
  @Test
  void reconcile_noSequencesAtAll_isNoneWithoutRebind() throws Exception {
    FakeTibero f = new FakeTibero();
    f.seed(T, 77L);
    f.sequences.clear();
    f.idDefaults.clear();

    assertThat(TiberoRebuildBackup.reconcile(f.connection(), S, T, FP))
        .isEqualTo(RebuildBackupState.NONE);
    assertThat(f.log).noneMatch(sql -> sql.contains(" MODIFY (\"ID\" DEFAULT "));
  }

  /** 복원 중 시퀀스 이름만 되돌린 뒤 중단돼도 다음 맞춤이 ID 기본값 참조를 고친다. */
  @Test
  void reconcile_originalSequenceRestoredButDefaultStale_repairsDefault() throws Exception {
    FakeTibero f = new FakeTibero();
    f.seed(T, 77L);
    f.idDefaults.put(TP, TiberoNames.sequenceName(S, B));

    assertThat(TiberoRebuildBackup.reconcile(f.connection(), S, T, FP))
        .isEqualTo(RebuildBackupState.NONE);
    assertThat(f.idDefaults).containsEntry(TP, TiberoNames.sequenceName(S, T));
  }

  @Test
  void drop_nullDropsUnconditionally_idMismatchReturnsFalse() throws Exception {
    FakeTibero f = backedUp(77L, "genia-rebuild:" + FP);
    f.seed(T, 99L);

    assertThat(TiberoRebuildBackup.drop(f.connection(), S, T, 78L)).isFalse();
    assertThat(f.tables).containsKey(BP);

    assertThat(TiberoRebuildBackup.drop(f.connection(), S, T, 77L)).isTrue();
    // 보관 테이블과 보관 시퀀스가 사라지고 새 테이블은 그대로다.
    assertThat(f.tables).containsOnlyKeys(TP);
    assertThat(f.sequences).containsExactly(TiberoNames.sequenceName(S, T));

    assertThat(TiberoRebuildBackup.drop(f.connection(), S, T, null)).isFalse(); // 이미 없음

    FakeTibero g = backedUp(5L, null);
    assertThat(TiberoRebuildBackup.drop(g.connection(), S, T, null)).isTrue();
    assertThat(g.everything()).isEmpty();
  }

  @Test
  void reconcile_noBackup_isNone() throws Exception {
    FakeTibero f = new FakeTibero();
    f.seed(T, 99L);
    Set<String> before = f.everything();
    assertThat(TiberoRebuildBackup.reconcile(f.connection(), S, T, FP))
        .isEqualTo(RebuildBackupState.NONE);
    assertThat(f.everything()).isEqualTo(before);
  }

  @Test
  void reconcile_differentFingerprint_dropsLeftover() throws Exception {
    FakeTibero f = backedUp(77L, "genia-rebuild:" + OTHER_FP);
    f.seed(T, 99L);
    assertThat(TiberoRebuildBackup.reconcile(f.connection(), S, T, FP))
        .isEqualTo(RebuildBackupState.DROPPED_LEFTOVER);
    assertThat(f.tables).containsOnlyKeys(TP).containsEntry(TP, 99L);
    assertThat(f.sequences).containsExactly(TiberoNames.sequenceName(S, T));
  }

  @Test
  void reconcile_sameFingerprint_restoresWithAndWithoutNewTable() throws Exception {
    FakeTibero withNew = backedUp(77L, "genia-rebuild:" + FP);
    withNew.seed(T, 99L);
    assertThat(TiberoRebuildBackup.reconcile(withNew.connection(), S, T, FP))
        .isEqualTo(RebuildBackupState.RESTORED);
    assertThat(withNew.tables).containsOnlyKeys(TP).containsEntry(TP, 77L);

    FakeTibero withoutNew = backedUp(77L, "genia-rebuild:" + FP);
    assertThat(TiberoRebuildBackup.reconcile(withoutNew.connection(), S, T, FP))
        .isEqualTo(RebuildBackupState.RESTORED);
    assertThat(withoutNew.tables).containsOnlyKeys(TP).containsEntry(TP, 77L);
    assertThat(withoutNew.sequences).containsExactly(TiberoNames.sequenceName(S, T));
  }

  @Test
  void reconcile_missingOrMalformedComment_restores() throws Exception {
    for (String comment :
        new String[] {
          null,
          "무슨 주석",
          "genia-rebuild",
          "genia-rebuild:",
          "genia-rebuild:null",
          "genia-rebuild:" + "B".repeat(32)
        }) {
      FakeTibero f = backedUp(77L, comment);
      assertThat(TiberoRebuildBackup.reconcile(f.connection(), S, T, FP))
          .as("주석=%s", comment)
          .isEqualTo(RebuildBackupState.RESTORED);
      assertThat(f.tables).containsOnlyKeys(TP).containsEntry(TP, 77L);
    }
  }

  @Test
  void reconcile_undeterminableCurrentFingerprint_restoresEvenIfStoredDiffers() throws Exception {
    for (String current : new String[] {null, "", "  ", "zz", "A".repeat(32)}) {
      FakeTibero f = backedUp(77L, "genia-rebuild:" + OTHER_FP);
      f.seed(T, 99L);
      assertThat(TiberoRebuildBackup.reconcile(f.connection(), S, T, current))
          .as("현재 지문=%s", current)
          .isEqualTo(RebuildBackupState.RESTORED);
      assertThat(f.tables).containsOnlyKeys(TP).containsEntry(TP, 77L);
    }
  }

  @Test
  void reconcile_differentFingerprintButOriginalMissing_restoresBecauseBackupIsOnlyCopy()
      throws Exception {
    FakeTibero f = backedUp(77L, "genia-rebuild:" + OTHER_FP);
    assertThat(TiberoRebuildBackup.reconcile(f.connection(), S, T, FP))
        .isEqualTo(RebuildBackupState.RESTORED);
    assertThat(f.tables).containsOnlyKeys(TP).containsEntry(TP, 77L);
  }

  @Test
  void reconcile_orphanBackupSequence_isDroppedThenBackupSucceeds() throws Exception {
    // drop() 이 DROP TABLE 과 DROP SEQUENCE 사이에서 죽어 보관 시퀀스만 남은 상태.
    FakeTibero f = new FakeTibero();
    f.seed(T, 99L);
    f.sequences.add(TiberoNames.sequenceName(S, B));

    assertThat(TiberoRebuildBackup.reconcile(f.connection(), S, T, FP))
        .isEqualTo(RebuildBackupState.NONE);
    assertThat(f.sequences).containsExactly(TiberoNames.sequenceName(S, T));

    assertThat(TiberoRebuildBackup.moveToBackup(f.connection(), S, T, FP)).isEqualTo(99L);
  }

  @Test
  void reconcile_sequenceOnlyRenamed_normalizesBackToNone() throws Exception {
    // 시퀀스만 보관 이름으로 바뀌고 테이블 이름은 그대로 — 테이블 이름을 바꾸기 전에 죽은 경우.
    FakeTibero f = new FakeTibero();
    f.seed(T, 99L);
    f.sequences.remove(TiberoNames.sequenceName(S, T));
    f.sequences.add(TiberoNames.sequenceName(S, B));

    assertThat(TiberoRebuildBackup.reconcile(f.connection(), S, T, FP))
        .isEqualTo(RebuildBackupState.NONE);
    assertThat(f.sequences).containsExactly(TiberoNames.sequenceName(S, T));
  }

  @Test
  void reconcile_companionsHalfRenamed_normalizesBackToNone() throws Exception {
    // 색인 하나만 보관 이름으로 바뀐 채 죽은 경우.
    FakeTibero f = new FakeTibero();
    f.seed(T, 99L);
    String[] tc = f.indexes.remove(TiberoNames.indexName(S, T, "NAME"));
    f.indexes.put(TiberoNames.indexName(S, B, "NAME"), tc);

    assertThat(TiberoRebuildBackup.reconcile(f.connection(), S, T, FP))
        .isEqualTo(RebuildBackupState.NONE);
    assertThat(f.indexes).containsKey(TiberoNames.indexName(S, T, "NAME"));
  }

  private static int indexOf(FakeTibero f, String prefix) {
    for (int i = 0; i < f.log.size(); i++) {
      if (f.log.get(i).startsWith(prefix)) return i;
    }
    throw new AssertionError("문장 없음: " + prefix + " in " + f.log);
  }
}
