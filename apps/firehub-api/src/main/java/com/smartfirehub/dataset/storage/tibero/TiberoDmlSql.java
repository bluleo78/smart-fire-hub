package com.smartfirehub.dataset.storage.tibero;

import com.smartfirehub.dataset.storage.DataTableNames;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Tibero DML·조회 SQL 빌더(순수 함수, WD-74). 연결·바인딩은 {@link TiberoDataRowStore} 가 맡고 여기서는 문자열만 만든다.
 *
 * <p>모든 이름은 {@link DataTableNames} 로 형식을 검증한 뒤 대문자·큰따옴표로 인용해 SQL 주입을 막는다. 값은 항상 {@code ?} 바인드이며,
 * 바인드 순서는 각 메서드 Javadoc 에 적는다. 시스템 컬럼 규칙: {@code ID} 는 테이블 전용 시퀀스의 {@code NEXTVAL}, {@code
 * _UPDATED_AT} 은 쓰기마다 {@code SYSTIMESTAMP}(트리거 없음), {@code CREATED_AT} 은 컬럼 기본값에 맡긴다.
 */
final class TiberoDmlSql {

  private TiberoDmlSql() {}

  // ---------- 이름·검색 헬퍼 ----------

  private static String tbl(String schema, String table) {
    DataTableNames.validate(table);
    return TiberoNames.table(schema, table);
  }

  private static String col(String name) {
    DataTableNames.validate(name);
    return TiberoNames.column(name);
  }

  /** 스테이징 테이블은 내부 생성 이름(대문자 {@code STG_<hex>}) 이라 소문자로 내려 검증한다. */
  private static String stgTbl(String schema, String stagingPhysical) {
    DataTableNames.validate(stagingPhysical.toLowerCase(Locale.ROOT));
    return TiberoNames.table(schema, stagingPhysical);
  }

  private static String seq(String schema, String table) {
    return TiberoNames.quote(TiberoNames.physical(schema))
        + "."
        + TiberoNames.quote(TiberoNames.sequenceName(schema, table));
  }

  private static String stgSeq(String schema, String stagingPhysical) {
    return TiberoNames.quote(TiberoNames.physical(schema))
        + "."
        + TiberoNames.quote(TiberoNames.stagingSequenceName(stagingPhysical));
  }

  /** 컬럼 목록을 {@code "A", "B"} 로 잇는다. */
  private static String cols(List<String> names) {
    List<String> out = new ArrayList<>();
    for (String n : names) {
      out.add(col(n));
    }
    return String.join(", ", out);
  }

  private static String placeholders(int n) {
    return String.join(", ", java.util.Collections.nCopies(n, "?"));
  }

  /**
   * 검색 패턴: 사용자 입력의 {@code \}·{@code %}·{@code _} 를 {@code \} 로 이스케이프해 와일드카드로 새지 않게 하고 앞뒤에 {@code %}
   * 를 붙인다.
   */
  static String searchPattern(String input) {
    return "%" + DataTableNames.escapeLike(input) + "%";
  }

  /**
   * 검색 컬럼들을 OR 로 묶은 조건(대소문자 무시). 컬럼마다 바인드 하나. 컬럼이 없으면 빈 문자열.
   *
   * <p>각 컬럼의 문자열 식은 물리 타입({@code ALL_TAB_COLUMNS})으로 정한다({@link TiberoText#ofPhysical}) — PG 의
   * {@code CAST(c AS TEXT)} 와 같은 모양이라 날짜가 NLS 설정에 흔들리지 않고 BOOLEAN 이 {@code true}/{@code false} 로
   * 검색된다. 타입을 모르는 컬럼은 {@code TO_CHAR(c)}.
   *
   * @param types {컬럼명(소문자) → 물리 타입}. 비어 있으면 모든 컬럼이 {@code TO_CHAR(c)}
   */
  private static String searchWhere(
      List<String> searchColumns, Map<String, TiberoColumnType> types) {
    if (searchColumns.isEmpty()) {
      return "";
    }
    List<String> parts = new ArrayList<>();
    for (String c : searchColumns) {
      String text = TiberoText.ofPhysical(col(c), types.get(c));
      parts.add("UPPER(" + text + ") LIKE UPPER(?) ESCAPE '\\'");
    }
    return " WHERE (" + String.join(" OR ", parts) + ")";
  }

  /**
   * 정렬 절. PG 와 같이 정렬 컬럼은 이름 형식만 검증하고(없는 컬럼이면 DB 가 오류를 낸다) 방향은 대소문자 무시 {@code desc} 만 내림차순으로 받는다 —
   * 방향이 화이트리스트라 SQL 에 임의 문자열이 들어가지 않는다. 정렬 컬럼이 없으면 ID 순.
   */
  private static String orderBy(String sortBy, String sortDir) {
    if (sortBy != null) {
      String dir = "desc".equalsIgnoreCase(sortDir) ? "DESC" : "ASC";
      return col(sortBy) + " " + dir + " NULLS LAST, \"ID\"";
    }
    return "\"ID\"";
  }

  // ---------- 조회 ----------

  /**
   * 검색·정렬·페이징 조회. 바인드 순서: 검색 패턴(검색 컬럼 수만큼), {@code RN__ > offset}, {@code RN__ <= offset+size}.
   *
   * <p>바깥 {@code ORDER BY RN__} 가 페이지 안 순서를 보장하고, 바깥 SELECT 가 컬럼을 명시해 순번 별칭 {@code RN__} 이 결과에 새지
   * 않는다(컬럼 목록이 비면 {@code *} — 호출자가 RN__ 을 지운다). 검색 컬럼의 문자열 식은 물리 타입으로 정한다({@link #searchWhere}).
   *
   * @param types {컬럼명(소문자) → 물리 타입}. 검색이 없으면 {@code Map.of()}
   */
  static String select(
      String schema,
      String table,
      List<String> columns,
      List<String> searchColumns,
      String sortBy,
      String sortDir,
      Map<String, TiberoColumnType> types) {
    String outer = columns.isEmpty() ? "*" : "\"ID\", " + cols(columns);
    return "SELECT "
        + outer
        + " FROM (SELECT q.*, ROW_NUMBER() OVER (ORDER BY "
        + orderBy(sortBy, sortDir)
        + ") AS RN__ FROM "
        + tbl(schema, table)
        + " q"
        + searchWhere(searchColumns, types)
        + ") WHERE RN__ > ? AND RN__ <= ? ORDER BY RN__";
  }

  /**
   * 검색 조건에 맞는 행 수. 바인드: 검색 패턴.
   *
   * @param types {컬럼명(소문자) → 물리 타입}. 검색이 없으면 {@code Map.of()}
   */
  static String count(
      String schema,
      String table,
      List<String> searchColumns,
      Map<String, TiberoColumnType> types) {
    return "SELECT COUNT(*) FROM " + tbl(schema, table) + searchWhere(searchColumns, types);
  }

  /** 단건 조회(PG 와 같이 {@code created_at} 포함). 바인드: id. */
  static String getRow(String schema, String table, List<String> columns) {
    String list = columns.isEmpty() ? "" : cols(columns) + ", ";
    return "SELECT \"ID\", "
        + list
        + "\"CREATED_AT\" FROM "
        + tbl(schema, table)
        + " WHERE \"ID\" = ?";
  }

  /**
   * 유일성 판정용 집계 한 번: 컬럼 1 = 전체 행 수, 컬럼 2 = PK 조합의 고유 값 수(같으면 유일). 한 번의 스캔으로 둘을 함께 센다. PG 의 {@code
   * COUNT(DISTINCT (a, b))} 와 같은 NULL 의미를 따른다: 단일 컬럼은 NULL 을 세지 않고({@code COUNT(DISTINCT a)} — NULL
   * 이 있으면 유일하지 않음), 복합은 PG 가 행 값을 NULL 로 보지 않으므로 NULL 이 섞인 조합도 하나의 값으로 센다({@code GROUP BY} 는 NULL 을
   * 한 묶음으로 본다). Oracle 계열에는 {@code COUNT(DISTINCT a, b)} 가 없어 복합은 묶음 수를 센다. 빈 테이블은 {@code SUM} 이
   * NULL 이라 {@code NVL} 로 0.
   */
  static String uniqueness(String schema, String table, List<String> pkColumns) {
    if (pkColumns.size() == 1) {
      return "SELECT COUNT(*), COUNT(DISTINCT " + cols(pkColumns) + ") FROM " + tbl(schema, table);
    }
    return "SELECT NVL(SUM(CNT__), 0), COUNT(*) FROM (SELECT COUNT(*) AS CNT__ FROM "
        + tbl(schema, table)
        + " GROUP BY "
        + cols(pkColumns)
        + ")";
  }

  /** NULL 존재 확인: 첫 NULL 행에서 멈추도록 {@code ROWNUM = 1}. 행이 한 건이라도 나오면 NULL 이 있다. */
  static String hasNulls(String schema, String table, String column) {
    return "SELECT 1 FROM "
        + tbl(schema, table)
        + " WHERE "
        + col(column)
        + " IS NULL AND ROWNUM = 1";
  }

  /**
   * 중복 PK 조합과 개수. {@code FETCH FIRST} 대신 {@code ROWNUM} 으로 건수를 제한한다(Oracle 계열 공통). 바인드: limit. 결과
   * 키는 소문자로 읽으면 PG 와 같은 {@code <pk…>, duplicate_count}.
   */
  static String duplicates(String schema, String table, List<String> pkColumns) {
    String pk = cols(pkColumns);
    return "SELECT * FROM (SELECT "
        + pk
        + ", COUNT(*) AS \"DUPLICATE_COUNT\" FROM "
        + tbl(schema, table)
        + " GROUP BY "
        + pk
        + " HAVING COUNT(*) > 1) WHERE ROWNUM <= ?";
  }

  // ---------- 쓰기 ----------

  /** 시퀀스에서 다음 ID 를 받는다. */
  static String nextId(String schema, String table) {
    DataTableNames.validate(table);
    return "SELECT " + seq(schema, table) + ".NEXTVAL FROM DUAL";
  }

  /**
   * 단건 INSERT(ID 는 시퀀스 NEXTVAL). 배치는 JDBC addBatch 로 같은 문장을 반복한다. 바인드: [importId], 컬럼들.
   *
   * @param withImportId true 면 {@code IMPORT_ID} 를 첫 바인드로 채운다
   */
  static String insert(String schema, String table, List<String> columns, boolean withImportId) {
    String importCol = withImportId ? "\"IMPORT_ID\", " : "";
    String importVal = withImportId ? "?, " : "";
    return "INSERT INTO "
        + tbl(schema, table)
        + " (\"ID\", "
        + importCol
        + cols(columns)
        + ", \"_UPDATED_AT\") VALUES ("
        + seq(schema, table)
        + ".NEXTVAL, "
        + importVal
        + placeholders(columns.size())
        + ", SYSTIMESTAMP)";
  }

  /** 호출자가 미리 받은 ID 로 INSERT 한다(ID 를 되돌려주기 위해). 바인드: id, 컬럼들. */
  static String insertWithId(String schema, String table, List<String> columns) {
    return "INSERT INTO "
        + tbl(schema, table)
        + " (\"ID\", "
        + cols(columns)
        + ", \"_UPDATED_AT\") VALUES (?, "
        + placeholders(columns.size())
        + ", SYSTIMESTAMP)";
  }

  /** id 로 수정. 바인드: 컬럼들, id. */
  static String update(String schema, String table, List<String> columns) {
    List<String> sets = new ArrayList<>();
    for (String c : columns) {
      sets.add(col(c) + " = ?");
    }
    sets.add("\"_UPDATED_AT\" = SYSTIMESTAMP");
    return "UPDATE " + tbl(schema, table) + " SET " + String.join(", ", sets) + " WHERE \"ID\" = ?";
  }

  /** id 목록 삭제. 바인드: id n 개. */
  static String deleteByIds(String schema, String table, int n) {
    return "DELETE FROM " + tbl(schema, table) + " WHERE \"ID\" IN (" + placeholders(n) + ")";
  }

  /** 전체 삭제(TRUNCATE 는 DDL 이라 암묵 커밋 — 쓰지 않는다). */
  static String deleteAll(String schema, String table) {
    return "DELETE FROM " + tbl(schema, table);
  }

  /**
   * 기존 키 조회. 단일 PK 는 {@code IN (?, ..)}, 복합 PK 는 {@code (a, b) IN ((?, ?), ..)}. 바인드: 키 n 개 × PK 컬럼
   * 수(키 순서대로). Oracle 은 IN 목록을 1000 개로 제한하므로 호출자가 그 이하로 나눠 부른다.
   */
  static String existingKeys(String schema, String table, List<String> pkColumns, int n) {
    String pk = cols(pkColumns);
    String in;
    if (pkColumns.size() == 1) {
      in = pk + " IN (" + placeholders(n) + ")";
    } else {
      String tuple = "(" + placeholders(pkColumns.size()) + ")";
      in = "(" + pk + ") IN (" + String.join(", ", java.util.Collections.nCopies(n, tuple)) + ")";
    }
    return "SELECT " + pk + " FROM " + tbl(schema, table) + " WHERE " + in;
  }

  /**
   * MERGE(upsert) — 바인드 한 벌을 {@code USING (SELECT ? "C" … FROM DUAL)} 소스로 받는다. 바인드: [importId],
   * 컬럼들. {@code IMPORT_ID} 를 소스 컬럼으로 넣어 UPDATE·INSERT 양쪽이 같은 바인드를 쓰게 한다. ON 절 컬럼(PK)은 UPDATE 에서
   * 제외한다(Oracle 이 금지).
   */
  static String merge(
      String schema,
      String table,
      List<String> columns,
      List<String> pkColumns,
      boolean withImportId) {
    StringBuilder using = new StringBuilder("(SELECT ");
    if (withImportId) {
      using.append("? \"IMPORT_ID\", ");
    }
    List<String> sel = new ArrayList<>();
    for (String c : columns) {
      sel.add("? " + col(c));
    }
    using.append(String.join(", ", sel)).append(" FROM DUAL)");
    return mergeCore(schema, table, using.toString(), columns, pkColumns, withImportId);
  }

  /** MERGE 공통 본문: ON(PK 일치) → UPDATE(PK 제외 컬럼, [IMPORT_ID], _UPDATED_AT) / INSERT(ID 시퀀스). */
  private static String mergeCore(
      String schema,
      String table,
      String using,
      List<String> columns,
      List<String> pkColumns,
      boolean withImportId) {
    Set<String> pkSet = new HashSet<>(pkColumns);
    List<String> on = new ArrayList<>();
    for (String pk : pkColumns) {
      on.add("t." + col(pk) + " = s." + col(pk));
    }
    List<String> sets = new ArrayList<>();
    for (String c : columns) {
      if (!pkSet.contains(c)) {
        sets.add("t." + col(c) + " = s." + col(c));
      }
    }
    if (withImportId) {
      sets.add("t.\"IMPORT_ID\" = s.\"IMPORT_ID\"");
    }
    sets.add("t.\"_UPDATED_AT\" = SYSTIMESTAMP");

    List<String> insertCols = new ArrayList<>();
    List<String> insertVals = new ArrayList<>();
    insertCols.add("\"ID\"");
    insertVals.add(seq(schema, table) + ".NEXTVAL");
    if (withImportId) {
      insertCols.add("\"IMPORT_ID\"");
      insertVals.add("s.\"IMPORT_ID\"");
    }
    for (String c : columns) {
      insertCols.add(col(c));
      insertVals.add("s." + col(c));
    }
    insertCols.add("\"_UPDATED_AT\"");
    insertVals.add("SYSTIMESTAMP");

    return "MERGE INTO "
        + tbl(schema, table)
        + " t USING "
        + using
        + " s ON ("
        + String.join(" AND ", on)
        + ") WHEN MATCHED THEN UPDATE SET "
        + String.join(", ", sets)
        + " WHEN NOT MATCHED THEN INSERT ("
        + String.join(", ", insertCols)
        + ") VALUES ("
        + String.join(", ", insertVals)
        + ")";
  }

  // ---------- 스테이징 ----------

  /**
   * 스테이징 테이블(PK·시스템 컬럼 없음, 입력 순번 {@code _SEQ} 포함)과 그 전용 시퀀스를 만든다. PK 제약을 두지 않아 같은 PK 가 여러 번 들어와도
   * 되고, 중복 정리는 승격 SQL 에서 한다.
   *
   * @param stagingPhysical 대문자 {@code STG_<hex>}
   * @param columnDdls 컬럼별 타입 DDL(columns 와 같은 순서)
   * @return [CREATE TABLE, CREATE SEQUENCE]
   */
  static List<String> createStaging(
      String schema, String stagingPhysical, List<String> columns, List<String> columnDdls) {
    StringBuilder ddl =
        new StringBuilder(
            "CREATE TABLE " + stgTbl(schema, stagingPhysical) + " (\"_SEQ\" NUMBER(19)");
    for (int i = 0; i < columns.size(); i++) {
      ddl.append(", ").append(col(columns.get(i))).append(' ').append(columnDdls.get(i));
    }
    ddl.append(")");
    return List.of(
        ddl.toString(),
        "CREATE SEQUENCE "
            + stgSeq(schema, stagingPhysical)
            + " START WITH 1 INCREMENT BY 1 NOCACHE");
  }

  /**
   * 스테이징 삭제 순서: 테이블 → 전용 시퀀스({@link TiberoDdlSql#dropTable} 와 같은 모양). 없는 객체 무시는 실행부 책임. 테이블 문장을 먼저
   * 만들면서 이름을 검증하므로 잘못된 이름이면 어느 문장도 나오지 않는다.
   */
  static List<String> dropStaging(String schema, String stagingPhysical) {
    return List.of(
        "DROP TABLE " + stgTbl(schema, stagingPhysical),
        "DROP SEQUENCE " + stgSeq(schema, stagingPhysical));
  }

  /** 스테이징 INSERT({@code _SEQ} 는 스테이징 시퀀스 NEXTVAL). 바인드: 컬럼들. */
  static String insertStaging(String schema, String stagingPhysical, List<String> columns) {
    return "INSERT INTO "
        + stgTbl(schema, stagingPhysical)
        + " (\"_SEQ\", "
        + cols(columns)
        + ") VALUES ("
        + stgSeq(schema, stagingPhysical)
        + ".NEXTVAL, "
        + placeholders(columns.size())
        + ")";
  }

  /** PK 별 마지막 입력 행({@code _SEQ} 최대)만 남기는 정리 소스. */
  static String promoteUpsertSource(
      String schema, String stagingPhysical, List<String> columns, List<String> pkColumns) {
    String list = cols(columns);
    return "SELECT "
        + list
        + " FROM (SELECT "
        + list
        + ", ROW_NUMBER() OVER (PARTITION BY "
        + cols(pkColumns)
        + " ORDER BY \"_SEQ\" DESC) AS RN__ FROM "
        + stgTbl(schema, stagingPhysical)
        + ") WHERE RN__ = 1";
  }

  /**
   * MERGE 전에 한 번 실행하는 집계: 컬럼 1 = 정리 소스 행 수, 컬럼 2 = 그 중 대상에 이미 있는 키 수. inserted = 1 − 2, updated = 2
   * 이므로 MERGE 의 영향 행 수에 기대지 않는다. SUM 은 0행이면 NULL 이라 {@code NVL} 로 0 을 만든다.
   */
  static String promoteUpsertCount(
      String schema,
      String table,
      String stagingPhysical,
      List<String> columns,
      List<String> pkColumns) {
    List<String> on = new ArrayList<>();
    for (String pk : pkColumns) {
      on.add("t." + col(pk) + " = s." + col(pk));
    }
    return "SELECT COUNT(*), NVL(SUM(CASE WHEN EXISTS (SELECT 1 FROM "
        + tbl(schema, table)
        + " t WHERE "
        + String.join(" AND ", on)
        + ") THEN 1 ELSE 0 END), 0) FROM ("
        + promoteUpsertSource(schema, stagingPhysical, columns, pkColumns)
        + ") s";
  }

  /** 정리 소스를 대상에 MERGE 한다. importId 가 있으면 바인드 하나(소스에 상수 컬럼으로 붙는다). */
  static String promoteUpsertMerge(
      String schema,
      String table,
      String stagingPhysical,
      List<String> columns,
      List<String> pkColumns,
      boolean withImportId) {
    String source = promoteUpsertSource(schema, stagingPhysical, columns, pkColumns);
    String using =
        withImportId
            ? "(SELECT src.*, ? \"IMPORT_ID\" FROM (" + source + ") src)"
            : "(" + source + ")";
    return mergeCore(schema, table, using, columns, pkColumns, withImportId);
  }

  /**
   * 대상 비운 뒤 스테이징을 복사하는 INSERT … SELECT. PK 가 있으면 정리 소스, 없으면 모든 행을 입력 순서({@code _SEQ})대로 복사한다. ORDER
   * BY 는 NEXTVAL 과 같은 질의 블록에 둘 수 없어 인라인 뷰 안에 둔다.
   */
  static String promoteReplace(
      String schema,
      String table,
      String stagingPhysical,
      List<String> columns,
      List<String> pkColumns) {
    String list = cols(columns);
    String source =
        pkColumns.isEmpty()
            ? "SELECT " + list + " FROM " + stgTbl(schema, stagingPhysical) + " ORDER BY \"_SEQ\""
            : promoteUpsertSource(schema, stagingPhysical, columns, pkColumns);
    return "INSERT INTO "
        + tbl(schema, table)
        + " (\"ID\", "
        + list
        + ", \"_UPDATED_AT\") SELECT "
        + seq(schema, table)
        + ".NEXTVAL, "
        + list
        + ", SYSTIMESTAMP FROM ("
        + source
        + ")";
  }
}
