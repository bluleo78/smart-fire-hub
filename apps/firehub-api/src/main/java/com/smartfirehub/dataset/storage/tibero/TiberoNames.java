package com.smartfirehub.dataset.storage.tibero;

import com.smartfirehub.dataset.exception.InvalidTableNameException;
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

  /**
   * 스테이징 테이블 이름 형식({@link #stagingTableName()}) {@code STG_<24 16진>} — 아래 두 판정의 단일 원본이다(코드리뷰 5).
   * db-executor 적재 규칙({@code LoadTableRule})·Python 실행기({@code require_staging_table})와 같은 모양이다.
   */
  private static final String STAGING_REGEX = "STG_[0-9A-F]{24}";

  /**
   * 물리 이름 판정 — <b>대문자만</b>. 스테이징 물리 이름은 언제나 대문자({@link #stagingTableName()})이고, 이 이름을 그대로 인용해
   * SQL(옮기기· 삭제)에 넣거나 카탈로그({@code ALL_OBJECTS})에서 고른 이름을 지우므로 정확히 그 모양이어야 한다.
   */
  private static final Pattern STAGING_PHYSICAL = Pattern.compile(STAGING_REGEX);

  /**
   * 모양 판정 — <b>대소문자 무시</b>. 소문자 메타데이터 이름({@code stg_<hex>})도 같은 스테이징을 가리키므로, 사용자 이름 거부(고아 스윕이 지울
   * 이름을 사용자가 못 쓰게)와 임시 적재 관문(소문자 논리 이름으로 되돌려 쓴다)은 이쪽을 쓴다.
   */
  private static final Pattern STAGING_SHAPED =
      Pattern.compile(STAGING_REGEX, Pattern.CASE_INSENSITIVE);

  /** 스테이징 관문 거부 문구 — 행 저장소·테이블 저장소 관문이 같은 문구를 쓴다. */
  private static String notStagingMessage(String name) {
    return "'" + name + "' 은(는) Tibero 임시(스테이징) 테이블 이름이 아닙니다";
  }

  /** 스테이징 이름 모양인가(대소문자 무시). null 이면 false. */
  public static boolean isStagingName(String name) {
    return name != null && STAGING_SHAPED.matcher(name).matches();
  }

  /** 스테이징 <b>물리</b> 이름(대문자 그대로)인가. null 이면 false. */
  public static boolean isStagingPhysicalName(String name) {
    return name != null && STAGING_PHYSICAL.matcher(name).matches();
  }

  /**
   * 스테이징 이름 모양(대소문자 무시)이 아니면 {@link InvalidTableNameException}. 실제 데이터 테이블에 쓰지 못하게 하는 임시 적재 관문용이다.
   */
  public static void requireStagingName(String name) {
    if (!isStagingName(name)) {
      throw new InvalidTableNameException(notStagingMessage(name));
    }
  }

  /**
   * 스테이징 물리 이름(대문자)이 아니면 {@link InvalidTableNameException}. 실제 데이터 테이블을 지우거나 옮기는 일이 없게 하는 마지막
   * 관문용이다.
   */
  public static void requireStagingPhysicalName(String name) {
    if (!isStagingPhysicalName(name)) {
      throw new InvalidTableNameException(notStagingMessage(name));
    }
  }

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
    if (isStagingName(metadataName)) {
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
    // DUAL 은 예약어는 아니지만 막는다(WD-77). 미한정 DUAL 은 OWNER 세션에서 자기 스키마를 먼저 찾으므로, 같은 이름의 테이블이 있으면
    // 보안 게이트가 시스템 DUAL 로 보고 건너뛴 참조가 실제로는 그 데이터셋을 읽는다(등급 판정 우회).
    if (RESERVED_DUAL.equals(upper)) {
      throw new IllegalArgumentException("'dual' 은 Tibero 예약 이름이라 데이터셋 이름으로 쓸 수 없습니다.");
    }
  }

  /** 사용자 이름으로 쓸 수 없는 시스템 의사 테이블 이름(대문자). */
  private static final String RESERVED_DUAL = "DUAL";

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
    return qualified(schema, physical(tableName));
  }

  /**
   * 스키마 한정 객체 이름 {@code "SCHEMA"."NAME"}. {@code physicalName} 은 이미 물리 이름(테이블 물리명·시퀀스 이름 등)이라 대문자로
   * 바꾸지 않고 그대로 인용한다 — 테이블·시퀀스·스테이징 시퀀스 한정 이름이 모두 이 한 곳을 지난다.
   */
  public static String qualified(String schema, String physicalName) {
    return quote(physical(schema)) + "." + quote(physicalName);
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

  /**
   * 행 트리거 이름 {@code TG_<10hex>}(WD-77) — 스키마·테이블에서 파생해 길이(13자)가 30자 한도 안이고, 같은 테이블이면 언제나 같은 이름이라 존재
   * 확인·재생성이 결정적이다.
   */
  public static String triggerName(String schema, String table) {
    return "TG_" + hash(10, "TG", schema, table);
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

  /**
   * 스테이징 입력 순번({@code _SEQ}) 인덱스 이름 {@code SX_<10hex>}(13자, WD-77) — 스테이징 이름에서 파생해 30자 한도 안이고, 컬럼
   * 인덱스({@code IX_})·PK 인덱스({@code UX_})와 접두가 달라 섞이지 않는다. 테이블을 지우면 인덱스도 함께 지워지므로 따로 지우지 않는다.
   */
  public static String stagingSeqIndexName(String stagingTable) {
    return "SX_" + hash(10, "SX", stagingTable);
  }

  /**
   * 러너 TEMP 테이블(메타데이터) 이름 {@code ptmp_<파이프라인 id>_<단계 id 36진수>}(WD-77) — Tibero 테넌트용.
   *
   * <p>같은 파이프라인·단계면 항상 같은 이름이고 단계 이름과 무관하다 — 단계를 바꿔 이름이 달라지면 이전 TEMP 와 새 TEMP 가 따로 생겨 고아가 된다. 물리
   * 이름은 대문자로 바뀌어도 길이가 같다. id 가 매우 커서 30자를 넘으면(두 id 가 합쳐 24자 초과) {@code ptmp_} + {@code "<p>:<s>"} 의
   * SHA-256 앞 24 소문자 hex 로 대신한다(29자, 같은 입력엔 같은 이름). 이 해시 규칙은 {@link #hash} 와 다르다 — 이미 만든 TEMP 이름과
   * 같아야 하므로 바꾸지 않는다.
   */
  public static String tempTableName(long pipelineId, long stepId) {
    String name = "ptmp_" + pipelineId + "_" + Long.toString(stepId, 36);
    if (name.length() <= TiberoDialect.MAX_IDENTIFIER_LENGTH) {
      return name;
    }
    byte[] digest = sha256(pipelineId + ":" + stepId);
    return "ptmp_" + HexFormat.of().formatHex(digest, 0, 12);
  }

  /** 대문자 정규화한 부분들을 '|' 로 이어 SHA-256 한 뒤 대문자 hex 앞 {@code length} 자(짝수)를 돌려준다. */
  private static String hash(int length, String... parts) {
    String[] upper = new String[parts.length];
    for (int i = 0; i < parts.length; i++) {
      upper[i] = parts[i].toUpperCase(Locale.ROOT);
    }
    byte[] digest = sha256(String.join("|", upper));
    // 바이트 하나가 hex 두 자이므로 필요한 앞부분 바이트만 대문자 hex 로 바꾼다.
    return HexFormat.of().withUpperCase().formatHex(Arrays.copyOf(digest, length / 2));
  }

  /** UTF-8 문자열의 SHA-256 다이제스트 — 파생 이름 해시들이 함께 쓴다. */
  private static byte[] sha256(String input) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 은 모든 JVM 이 보장하므로 도달 불가
      throw new IllegalStateException(e);
    }
  }
}
