package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * V171 — Tibero V171 의 PG 짝(MigrationParityTest, WD-1144).
 *
 * <p><b>무엇을</b> — {@code saved_query.folder} 가 공백·Cf·채움 문자만인 행을 NULL 로 비운다. V170 의 고정 판정({@code
 * HAS_TEXT_AT_V170})을 다시 부른다.
 *
 * <p><b>왜</b> — Tibero V170 은 적용됐을 수 있어 체크섬을 바꿀 수 없으므로(forward-only) 빠진 Cf·채움 문자는 새 버전 V171 로 옮겼고,
 * PG 도 같은 버전을 맞춘다. PG V170 은 이미 같은 범위를 비워 보통 0행이다(멱등).
 */
public class V171__blank_saved_query_folder_cf_to_null extends BaseJavaMigration {

  @Override
  public void migrate(Context context) throws Exception {
    V170__blank_saved_query_folder_to_null.blankOutBlankFolders(context.getConnection());
  }
}
