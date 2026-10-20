package com.smartfirehub.dataset.storage;

import com.smartfirehub.dataset.exception.InvalidTableNameException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.springframework.util.DigestUtils;

/**
 * 데이터셋 테이블·컬럼 이름 검증 — 백엔드와 무관한 단일 규칙(WD-72).
 *
 * <p>파사드와 모든 저장소 구현이 같은 검증기를 쓰도록 한 곳에 둔다. 식별자는 SQL 에 인용되어 들어가므로 {@code [a-z][a-z0-9_]*} 로 제한해 주입을
 * 막는다.
 */
public final class DataTableNames {

  private static final Pattern VALID_NAME = Pattern.compile("^[a-z][a-z0-9_]*$");

  private DataTableNames() {}

  /**
   * 이름이 {@code [a-z][a-z0-9_]*} 패턴인지 — 예외 없이 판정만 한다. 호출 문맥에 맞는 안내를 따로 내야 하는 곳(파이프라인 SQL 결과 컬럼명,
   * WD-111)이 정규식을 복사하지 않고 같은 규칙을 쓰게 한다.
   */
  public static boolean isValid(String name) {
    return name != null && VALID_NAME.matcher(name).matches();
  }

  /** 이름이 {@code [a-z][a-z0-9_]*} 패턴인지 검증한다. 어긋나면 {@link InvalidTableNameException}. */
  public static void validate(String name) {
    if (!isValid(name)) {
      throw new InvalidTableNameException("Invalid name: " + name + ". Must match [a-z][a-z0-9_]*");
    }
  }

  /**
   * 문자열의 MD5(32자 소문자 hex). 길이 한도가 있는 식별자를 고정 길이로 만들 때 쓴다 — REPLACE 임시 테이블({@code rtmp_<md5>})과 긴 필드
   * 인덱스 이름({@code idx_<md5>}, WD-100)이 같은 규칙을 쓰도록 한 곳에 둔다. 보안 용도가 아니라 이름 분산용이다.
   */
  public static String md5Hex(String value) {
    return DigestUtils.md5DigestAsHex(value.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * LIKE/ILIKE 검색어의 {@code \}·{@code %}·{@code _} 를 {@code \} 로 이스케이프한다(호출 SQL 은 {@code ESCAPE
   * '\'}).
   *
   * <p>사용자 입력이 와일드카드로 새지 않게 하는 규칙이 백엔드마다 같아야 하므로 한 곳에 둔다. 앞뒤 {@code %} 는 붙이지 않는다 — 감싸는 방식은 각 저장소가
   * 정한다.
   */
  public static String escapeLike(String input) {
    return input.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }
}
