package com.smartfirehub.dataset.storage;

import com.smartfirehub.dataset.exception.InvalidTableNameException;
import java.util.regex.Pattern;

/**
 * 데이터셋 테이블·컬럼 이름 검증 — 백엔드와 무관한 단일 규칙(WD-72).
 *
 * <p>파사드와 모든 저장소 구현이 같은 검증기를 쓰도록 한 곳에 둔다. 식별자는 SQL 에 인용되어 들어가므로 {@code [a-z][a-z0-9_]*} 로 제한해 주입을
 * 막는다.
 */
public final class DataTableNames {

  private static final Pattern VALID_NAME = Pattern.compile("^[a-z][a-z0-9_]*$");

  private DataTableNames() {}

  /** 이름이 {@code [a-z][a-z0-9_]*} 패턴인지 검증한다. 어긋나면 {@link InvalidTableNameException}. */
  public static void validate(String name) {
    if (!VALID_NAME.matcher(name).matches()) {
      throw new InvalidTableNameException("Invalid name: " + name + ". Must match [a-z][a-z0-9_]*");
    }
  }
}
