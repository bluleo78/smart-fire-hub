package com.smartfirehub.dataset.storage.tibero;

import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.DataStoreFeatureUnsupportedException;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Tibero 방언 사실(상수·타입 매핑) 모음.
 *
 * <p>R5(결정 기록): 실서버 확인 전이라 Oracle 계열 공통 구문(시퀀스+명시 NEXTVAL, ROW_NUMBER 페이징, 30자 식별자, VARCHAR2(4000),
 * MERGE, {@code ALTER TABLE .. RENAME TO}) 을 가정한다. 실서버 방언 확인(Task 5) 결과가 다르면 이 클래스의 값만 바꾼다 — 다른
 * 클래스는 이 상수를 참조만 한다.
 */
public final class TiberoDialect {

  private TiberoDialect() {}

  /** 식별자(테이블·컬럼·파생 객체) 최대 길이. */
  public static final int MAX_IDENTIFIER_LENGTH = 30;

  /**
   * "객체 없음" 오류 종류. 판별은 코드와 메시지 표기를 모두 본다({@link TiberoErrors#hasCode}).
   *
   * <p>실서버 확인(Tibero 7.2, 2026-10-20): Tibero 는 ORA 코드가 아니라 <b>음수 자체 코드</b>를 돌려준다(예: 테이블 없음 -8033,
   * 메시지 {@code JDBC-8033:...}). Oracle 호환 코드(양수)도 함께 두어 Oracle 계열 서버에서도 같은 판별이 되게 한다. 같은 "없음" 이 문장에
   * 따라 다른 코드로 나올 수 있어(Oracle 의 제약 DROP 2443 / RENAME 23292) 코드를 여러 개 받는다.
   */
  public enum NotFound {
    /** 테이블·뷰 없음. */
    TABLE(942, -8033),
    /** 시퀀스 없음. */
    SEQUENCE(2289, -8034),
    /** 인덱스 없음 — Tibero 는 "스키마 객체가 없거나 유효하지 않음"(-7071)으로 알린다. */
    INDEX(1418, -7071),
    /** 제약 조건 없음 — Oracle 은 DROP 2443·RENAME 23292, Tibero 는 둘 다 -7076. */
    CONSTRAINT(2443, 23292, -7076);

    private final int[] codes;

    NotFound(int... codes) {
      this.codes = codes;
    }

    /** 예외 체인에 이 종류의 "객체 없음" 오류(코드 또는 {@code ORA-xxxxx} 표기)가 있는지. */
    public boolean matches(Throwable error) {
      for (int code : codes) {
        if (TiberoErrors.hasCode(error, code)) {
          return true;
        }
      }
      return false;
    }
  }

  // 아래 오류 코드 묶음은 {Oracle 호환 양수 코드, Tibero 음수 자체 코드} 쌍이다(NotFound 와 같은 이유 — 실서버 확인 결과).

  /** 이름이 이미 다른 객체에 쓰이고 있음(테이블·시퀀스 생성 충돌). */
  public static final Set<Integer> NAME_ALREADY_USED = Set.of(955, -7102);

  /** 고유 제약 위반(중복 키) — Spring {@code DuplicateKeyException} 으로 바꾼다. */
  public static final Set<Integer> UNIQUE_VIOLATION = Set.of(1, -10007);

  /** NOT NULL 컬럼에 NULL 삽입 — {@code DataIntegrityViolationException}. */
  public static final Set<Integer> NOT_NULL_INSERT = Set.of(1400, -10005);

  /** NULL 값이 있어 NOT NULL 제약을 켤 수 없음 — {@code DataIntegrityViolationException}. */
  public static final Set<Integer> NOT_NULL_ENABLE_NULLS_FOUND = Set.of(2296, -7045);

  /** 값이 컬럼 길이보다 큼 — {@code DataIntegrityViolationException}(TEXT 4000 바이트 초과 등). */
  public static final Set<Integer> VALUE_TOO_LARGE = Set.of(12899, -11048);

  /** 무결성 위반으로 바꿀 오류 코드(중복 키 제외). */
  public static final Set<Integer> INTEGRITY_VIOLATIONS =
      Stream.of(NOT_NULL_INSERT, NOT_NULL_ENABLE_NULLS_FOUND, VALUE_TOO_LARGE)
          .flatMap(Set::stream)
          .collect(Collectors.toUnmodifiableSet());

  /** VARCHAR2 최대 바이트 길이. */
  public static final int MAX_VARCHAR = 4000;

  /**
   * 인덱스 키 최대 바이트(추정치). Oracle 은 블록 크기에 따라 약 6398 바이트(8K 블록)에서 ORA-01450 을 낸다 — 여유를 두고 6000 으로 잡는다.
   * Tibero 의 실제 한도는 아직 모른다 → {@code TiberoDialectProbeTest.index_key_limit} 로 확인한 뒤 이 값만 바꾼다.
   */
  public static final int MAX_INDEX_KEY_BYTES = 6000;

  /** AL32UTF8 에서 문자 하나의 최대 바이트(VARCHAR2(n CHAR) 의 선언 바이트 폭 = n × 4, 4000 상한). */
  private static final int MAX_BYTES_PER_CHAR = 4;

  /**
   * 논리 타입 컬럼이 인덱스 키에서 차지하는 선언 바이트 폭(추정). TEXT 4000, VARCHAR(n) {@code min(n×4, 4000)}(CHAR 의미 —
   * A7), NUMBER 22, DATE 7, TIMESTAMP 11. 알 수 없는 타입은 보수적으로 4000 으로 본다.
   */
  public static int indexKeyBytes(String logicalType, Integer maxLength) {
    String type = logicalType == null ? "" : logicalType.trim().toUpperCase(Locale.ROOT);
    return switch (type) {
      case "VARCHAR" ->
          Math.min((maxLength == null ? MAX_VARCHAR : maxLength) * MAX_BYTES_PER_CHAR, MAX_VARCHAR);
      case "INTEGER", "BIGINT", "DECIMAL", "NUMERIC", "BOOLEAN" -> 22;
      case "DATE" -> 7;
      case "TIMESTAMP" -> 11;
      default -> MAX_VARCHAR;
    };
  }

  /** JDBC 드라이버 클래스(드라이버 jar 는 로컬 libs 에서 선택적으로 가져온다). */
  public static final String DRIVER_CLASS = "com.tmax.tibero.jdbc.TbDriver";

  /** 커넥션 유효성 검사 쿼리(FROM 절이 필수라 DUAL 이 필요). */
  public static final String VALIDATION_QUERY = "SELECT 1 FROM DUAL";

  /**
   * 세션 NLS 고정 문장. 풀 커넥션은 Hikari {@code connectionInitSql} 로, DDL 직결 커넥션은 연 직후 실행한다. 날짜·타임스탬프 기본 형식과
   * 소수점 문자를 고정해 DB·클라이언트 로캘 기본값에 따라 암묵 변환(문자열 ↔ 날짜·숫자) 결과가 달라지지 않게 한다. 익명 블록 하나로 묶은 이유: {@code
   * connectionInitSql} 은 문장 하나만 받는다. 실서버 동작은 {@code TiberoDialectProbeTest.session_init_block} 으로
   * 확인한다.
   */
  public static final String SESSION_INIT_SQL =
      "BEGIN EXECUTE IMMEDIATE 'ALTER SESSION SET NLS_DATE_FORMAT=''YYYY-MM-DD'''; EXECUTE IMMEDIATE"
          + " 'ALTER SESSION SET NLS_TIMESTAMP_FORMAT=''YYYY-MM-DD HH24:MI:SS.FF6'''; EXECUTE"
          + " IMMEDIATE 'ALTER SESSION SET NLS_NUMERIC_CHARACTERS=''.,'''; END;";

  /**
   * 행 저장소 조회·검색·페이징·건수·DML 문장의 실행 시간 제한(초). 응답 없는 질의가 요청 스레드와 묶인 커넥션을 무한정 붙잡지 않게 한다. 컬럼 통계는 따로 30초를
   * 쓴다. 데이터 크기에 비례하는 일괄 문장(스테이징 승격·전체 삭제)에는 걸지 않는다 — 큰 가져오기를 실패시키지 않도록.
   */
  public static final int QUERY_TIMEOUT_SECONDS = 60;

  /** Oracle 계열 예약어(대문자). 테이블·컬럼 이름이 겹치면 생성·이름 변경 시 거부한다. */
  public static final Set<String> RESERVED_WORDS =
      Set.of(
          "ACCESS",
          "ADD",
          "ALL",
          "ALTER",
          "AND",
          "ANY",
          "AS",
          "ASC",
          "AUDIT",
          "BETWEEN",
          "BY",
          "CHAR",
          "CHECK",
          "CLUSTER",
          "COLUMN",
          "COMMENT",
          "COMPRESS",
          "CONNECT",
          "CREATE",
          "CURRENT",
          "DATE",
          "DECIMAL",
          "DEFAULT",
          "DELETE",
          "DESC",
          "DISTINCT",
          "DROP",
          "ELSE",
          "EXCLUSIVE",
          "EXISTS",
          "FILE",
          "FLOAT",
          "FOR",
          "FROM",
          "GRANT",
          "GROUP",
          "HAVING",
          "IDENTIFIED",
          "IMMEDIATE",
          "IN",
          "INCREMENT",
          "INDEX",
          "INITIAL",
          "INSERT",
          "INTEGER",
          "INTERSECT",
          "INTO",
          "IS",
          "LEVEL",
          "LIKE",
          "LOCK",
          "LONG",
          "MAXEXTENTS",
          "MINUS",
          "MLSLABEL",
          "MODE",
          "MODIFY",
          "NOAUDIT",
          "NOCOMPRESS",
          "NOT",
          "NOWAIT",
          "NULL",
          "NUMBER",
          "OF",
          "OFFLINE",
          "ON",
          "ONLINE",
          "OPTION",
          "OR",
          "ORDER",
          "PCTFREE",
          "PRIOR",
          "PRIVILEGES",
          "PUBLIC",
          "RAW",
          "RENAME",
          "RESOURCE",
          "REVOKE",
          "ROW",
          "ROWID",
          "ROWNUM",
          "ROWS",
          "SELECT",
          "SESSION",
          "SET",
          "SHARE",
          "SIZE",
          "SMALLINT",
          "START",
          "SUCCESSFUL",
          "SYNONYM",
          "SYSDATE",
          "TABLE",
          "THEN",
          "TO",
          "TRIGGER",
          "UID",
          "UNION",
          "UNIQUE",
          "UPDATE",
          "USER",
          "VALIDATE",
          "VALUES",
          "VARCHAR",
          "VARCHAR2",
          "VIEW",
          "WHENEVER",
          "WHERE",
          "WITH");

  /**
   * 논리 타입(메타데이터의 PG 계열 이름)을 Tibero 컬럼 타입 DDL 조각으로 바꾼다.
   *
   * <p>BOOLEAN 의 CHECK 제약은 컬럼 이름이 필요해 호출하는 DDL 빌더가 덧붙인다.
   *
   * <p>VARCHAR(n) 은 PG 처럼 <b>문자</b> 수로 세도록 {@code VARCHAR2(n CHAR)} 로 만든다(바이트로 세면 한글 n 자가 들어가지
   * 않는다). TEXT 는 {@code VARCHAR2(4000)} — 바이트 한도라 UTF-8 한글로는 약 1333 자까지다. 넘는 값은 ORA-12899 로 거부되고
   * 커넥션 래퍼가 {@code DataIntegrityViolationException}(409) 으로 바꾼다(500 이 아니다).
   *
   * @param logicalType TEXT, VARCHAR, INTEGER, DECIMAL, BOOLEAN, DATE, TIMESTAMP, GEOMETRY
   * @param maxLength VARCHAR 길이(그 외 타입은 무시)
   * @throws DataStoreFeatureUnsupportedException GEOMETRY(WD-80 에서 지원)
   * @throws IllegalArgumentException VARCHAR 길이가 4000 초과이거나 알 수 없는 타입
   */
  public static String columnDdl(String logicalType, Integer maxLength) {
    String type = logicalType == null ? "" : logicalType.trim().toUpperCase(Locale.ROOT);
    return switch (type) {
      case "TEXT" -> "VARCHAR2(" + MAX_VARCHAR + ")";
      case "VARCHAR" -> {
        int n = maxLength == null ? MAX_VARCHAR : maxLength;
        if (n < 1 || n > MAX_VARCHAR) {
          throw new IllegalArgumentException(
              "Tibero VARCHAR 길이는 1~" + MAX_VARCHAR + " 이어야 합니다: " + n);
        }
        yield "VARCHAR2(" + n + " CHAR)";
      }
      case "INTEGER", "BIGINT" -> "NUMBER(19)";
      case "DECIMAL", "NUMERIC" -> "NUMBER(18,6)";
      case "BOOLEAN" -> "NUMBER(1)";
      case "DATE" -> "DATE";
      case "TIMESTAMP" -> "TIMESTAMP";
      case "GEOMETRY" ->
          throw new DataStoreFeatureUnsupportedException(DataStoreBackend.TIBERO, "GEOMETRY 컬럼");
      default -> throw new IllegalArgumentException("지원하지 않는 컬럼 타입입니다: " + logicalType);
    };
  }
}
