package com.smartfirehub.dataset.storage;

import com.smartfirehub.dataset.storage.tibero.TiberoErrors;
import java.sql.SQLException;
import java.util.Set;

/**
 * 「쓰는 도중 테이블 구조가 바뀌었다」를 뜻하는 DB 오류 판별(WD-670) — 페이지·배치 적재의 마지막 방어선.
 *
 * <p>페이지 적재는 데이터셋 행 잠금(KEY SHARE) 뒤 현재 필드 목록을 먼저 확인하지만, 그 확인과 INSERT 사이·또는 잠금을 쓰지 않는 경로에서 오는 원문 오류
 * (PG {@code 42703} 컬럼 없음·{@code 42P01} 테이블 없음, Tibero 의 식별자 무효·테이블 없음·자원 사용 중)를 같은 깔끔한 문구로 바꾸려고
 * 둔다.
 */
public final class OutputStructureErrors {

  private OutputStructureErrors() {}

  /** PG: 컬럼 없음(undefined_column)·테이블 없음(undefined_table). */
  private static final Set<String> PG_STATES = Set.of("42703", "42P01");

  /**
   * Tibero/Oracle 계열: 식별자 무효(ORA-00904)·테이블 없음(ORA-00942, Tibero -8033)·자원 사용 중(ORA-00054). Tibero
   * 자체(음수) 코드는 실서버로 확인한 -8033 만 싣는다 — 나머지 음수 코드는 실서버 확인 뒤 추가한다(후속).
   */
  private static final Set<Integer> TIBERO_CODES = Set.of(904, 942, -8033, 54);

  /** 예외 체인(최대 10단계)에 구조 변경을 뜻하는 DB 오류가 있는지. */
  public static boolean isStructureChange(Throwable error) {
    Throwable t = error;
    for (int depth = 0; t != null && depth < 10; depth++, t = t.getCause()) {
      if (t instanceof SQLException se && se.getSQLState() != null) {
        if (PG_STATES.contains(se.getSQLState())) {
          return true;
        }
      }
    }
    return TiberoErrors.hasAnyCode(error, TIBERO_CODES);
  }
}
