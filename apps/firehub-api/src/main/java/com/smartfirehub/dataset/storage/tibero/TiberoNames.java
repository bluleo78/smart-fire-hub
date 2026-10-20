package com.smartfirehub.dataset.storage.tibero;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Tibero 식별자 규칙: 대문자 + 큰따옴표 인용 + 30자 제한 + 파생 객체의 짧은 해시 이름.
 *
 * <p>왜 해시인가: 인덱스·시퀀스 이름을 "테이블_컬럼_idx" 식으로 이으면 30자를 쉽게 넘는다. 스키마·테이블[·컬럼] 로 SHA-256 을 만들어 앞부분만 쓰면 길이가
 * 고정이고 같은 입력엔 같은 이름이라 DROP·재생성이 결정적이다. 입력은 항상 대문자로 정규화해 호출자가 소문자 메타데이터 이름을 주든 대문자 스키마 이름을 주든 결과가
 * 같다.
 */
public final class TiberoNames {

  private TiberoNames() {}

  /** 스테이징 테이블 이름 형식({@link #stagingTableName()}) — 대소문자 무시. 사용자 이름으로는 쓸 수 없다. */
  private static final Pattern STAGING_SHAPED =
      Pattern.compile("^stg_[0-9a-f]{24}$", Pattern.CASE_INSENSITIVE);

  /**
   * 메타데이터 이름(소문자)을 Tibero 물리 이름(대문자)으로 바꾼다.
   *
   * <p>로캘 독립({@code Locale.ROOT}) — 터키어 로캘에서 i 가 İ 로 바뀌는 문제를 막는다. 사용자가 정한 이름은 호출 전에 {@link
   * #validateUserName} 으로 길이·예약어를 검사해야 한다(이 메서드는 검사하지 않는다).
   */
  public static String physical(String metadataName) {
    return metadataName.toUpperCase(Locale.ROOT);
  }

  /**
   * 사용자가 정한 테이블·컬럼 이름을 검증한다(형식은 DataTableNames 가 이미 검사).
   *
   * <p>스테이징 테이블 이름 형식({@code stg_<24hex>})도 거부한다 — 고아 staging 스윕({@link
   * TiberoCatalog#staleStagingTables})은 이 형식의 테이블을 오래되면 지우므로, 사용자 데이터셋이 이 이름을 가지면 데이터가 사라진다.
   *
   * @throws IllegalArgumentException 30자 초과, 예약어, 스테이징 예약 형식
   */
  public static void validateUserName(String metadataName) {
    String upper = physical(metadataName);
    if (STAGING_SHAPED.matcher(metadataName).matches()) {
      throw new IllegalArgumentException(
          "내부 스테이징 테이블용으로 예약된 이름 형식이라 사용할 수 없습니다(stg_ + 16진수 24자): " + metadataName);
    }
    if (upper.length() > TiberoDialect.MAX_IDENTIFIER_LENGTH) {
      throw new IllegalArgumentException(
          "Tibero 에서는 이름이 "
              + TiberoDialect.MAX_IDENTIFIER_LENGTH
              + "자를 넘을 수 없습니다: "
              + metadataName);
    }
    if (TiberoDialect.RESERVED_WORDS.contains(upper)) {
      throw new IllegalArgumentException("Tibero 예약어라 사용할 수 없는 이름입니다: " + metadataName);
    }
  }

  /**
   * 물리 이름을 큰따옴표로 인용한다(예약어·대소문자 문제 회피).
   *
   * <p>우리 식별자에는 따옴표·NUL 이 있을 수 없으므로 이스케이프하지 않고 거부한다(SQL 주입 방지).
   *
   * @throws IllegalArgumentException 이름에 {@code "} 또는 NUL 이 있을 때
   */
  public static String quote(String physical) {
    if (physical.indexOf('"') >= 0 || physical.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("식별자에 따옴표나 NUL 문자를 쓸 수 없습니다");
    }
    return "\"" + physical + "\"";
  }

  /**
   * 스키마 한정 테이블 이름 {@code "SCHEMA"."TABLE"}. 스키마는 이미 물리(대문자) 이름이다. 사용자 제공 테이블 이름은 호출 전에 {@link
   * #validateUserName} 으로 검사해야 한다.
   */
  public static String table(String schema, String tableName) {
    return quote(physical(schema)) + "." + quote(physical(tableName));
  }

  /** 인용된 컬럼 이름 {@code "COL"}. 사용자 제공 컬럼 이름은 호출 전에 {@link #validateUserName} 으로 검사해야 한다. */
  public static String column(String metadataName) {
    return quote(physical(metadataName));
  }

  /** 컬럼 인덱스 이름 {@code IX_<10hex>}. */
  public static String indexName(String schema, String table, String column) {
    return "IX_" + hash(10, "IX", schema, table, column);
  }

  /** PK 인덱스 이름 {@code UX_<10hex>}. */
  public static String primaryKeyIndexName(String schema, String table) {
    return "UX_" + hash(10, "UX", schema, table);
  }

  /** BOOLEAN 컬럼 CHECK 제약 이름 {@code CK_<10hex>} — 이름을 고정해 타입 변경·컬럼 삭제 때 찾아 지울 수 있게 한다. */
  public static String checkConstraintName(String schema, String table, String column) {
    return "CK_" + hash(10, "CK", schema, table, column);
  }

  /** 테이블 전용 시퀀스 이름 {@code SQ_<10hex>}. */
  public static String sequenceName(String schema, String table) {
    return "SQ_" + hash(10, "SQ", schema, table);
  }

  /** 스테이징 테이블 이름 {@code STG_<24hex>} — 호출마다 달라야 하므로 랜덤 UUID 기반. */
  public static String stagingTableName() {
    return "STG_"
        + UUID.randomUUID().toString().replace("-", "").substring(0, 24).toUpperCase(Locale.ROOT);
  }

  /** 스테이징 테이블 전용 시퀀스 이름 {@code SQ_<10hex>}. */
  public static String stagingSequenceName(String stagingTable) {
    return "SQ_" + hash(10, "SQ", stagingTable);
  }

  /** 대문자 정규화한 부분들을 '|' 로 이어 SHA-256 한 뒤 대문자 hex 앞 {@code length} 자(짝수)를 돌려준다. */
  private static String hash(int length, String... parts) {
    String[] upper = new String[parts.length];
    for (int i = 0; i < parts.length; i++) {
      upper[i] = parts[i].toUpperCase(Locale.ROOT);
    }
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(String.join("|", upper).getBytes(StandardCharsets.UTF_8));
      // 바이트 하나가 hex 두 자이므로 필요한 앞부분 바이트만 대문자 hex 로 바꾼다.
      return HexFormat.of().withUpperCase().formatHex(Arrays.copyOf(digest, length / 2));
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 은 모든 JVM 이 보장하므로 도달 불가
      throw new IllegalStateException(e);
    }
  }
}
