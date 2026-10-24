package com.smartfirehub.dataset.storage.tibero;

import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.exception.InvalidTableNameException;
import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.DataStoreFeatureUnsupportedException;
import com.smartfirehub.dataset.storage.DataTableStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Tibero DDL·통계 SQL 을 만드는 순수 빌더(WD-74).
 *
 * <p>왜 따로 두는가: 실행(커넥션·보상·로깅)과 문자열 생성을 분리하면 실서버 없이 SQL 을 정확히 고정해 검증할 수 있고, 실서버 방언 확인(Task 5) 결과가
 * 달라져도 이 클래스만 고치면 된다. 모든 식별자는 {@link TiberoNames} 로 대문자 인용하며, 사용자 제공 이름은 이 클래스가 {@link
 * TiberoNames#validateUserName} 으로 먼저 검증한다(시스템 컬럼 제외).
 */
public final class TiberoDdlSql {

  private TiberoDdlSql() {}

  /** 시스템 컬럼 이름(소문자 메타데이터 이름). */
  private static final String UPDATED_AT = "_updated_at";

  /**
   * 실행할 DDL 한 문장과, 실행부가 무시해도 되는 "객체 없음" 오류. 예전에는 실행부가 SQL 문자열({@code " DROP CONSTRAINT "})을 보고 무시
   * 여부를 정했는데, 문장을 만든 쪽이 그 사실을 함께 넘기도록 바꿨다.
   */
  public record DdlStep(String sql, Set<TiberoDialect.NotFound> ignorable) {
    public DdlStep {
      ignorable = Set.copyOf(ignorable);
    }

    /** 오류를 무시하지 않는 문장. */
    static DdlStep of(String sql) {
      return new DdlStep(sql, Set.of());
    }

    /** 지정한 "객체 없음" 오류는 무시하는 문장. */
    static DdlStep ignoring(String sql, TiberoDialect.NotFound... notFound) {
      return new DdlStep(sql, Set.of(notFound));
    }

    /** 실행부({@link TiberoErrors#runIgnoring})에 넘길 배열. */
    TiberoDialect.NotFound[] ignorableArray() {
      return ignorable.toArray(new TiberoDialect.NotFound[0]);
    }
  }

  /**
   * 인덱스 키 길이 사전 검사. 컬럼별 선언 바이트 폭의 합이 {@link TiberoDialect#MAX_INDEX_KEY_BYTES} 를 넘으면 DDL 을 실행하기 전에
   * 400 으로 거부한다 — 그대로 실행하면 사용자에게 원문 ORA-01450 이 500 으로 간다.
   *
   * @param columnNames 메시지용 컬럼 이름(메타데이터 이름)
   * @param bytes 컬럼별 바이트 폭(같은 순서)
   * @throws IllegalArgumentException 합이 한도를 넘을 때
   */
  public static void checkIndexKeyLength(List<String> columnNames, List<Integer> bytes) {
    int total = bytes.stream().mapToInt(Integer::intValue).sum();
    if (total > TiberoDialect.MAX_INDEX_KEY_BYTES) {
      throw new IllegalArgumentException(
          "Tibero 저장소의 인덱스 키 길이 제한(약 "
              + TiberoDialect.MAX_INDEX_KEY_BYTES
              + "바이트)을 넘습니다: "
              + String.join(", ", columnNames)
              + " — TEXT 대신 짧은 VARCHAR(n) 을 쓰세요");
    }
  }

  /** 논리 타입 컬럼 정의로 인덱스 키 길이를 검사한다. */
  private static void checkIndexKeyLength(List<DatasetColumnRequest> columns) {
    checkIndexKeyLength(
        columns.stream().map(DatasetColumnRequest::columnName).toList(),
        columns.stream()
            .map(c -> TiberoDialect.indexKeyBytes(c.dataType(), c.maxLength()))
            .toList());
  }

  /** 테이블 생성 순서: 시퀀스 → 테이블 → 컬럼 인덱스 → PK 유니크 인덱스 → _UPDATED_AT 인덱스 → 읽기 계정 GRANT. */
  public static List<String> createTable(
      String schema, String table, List<DatasetColumnRequest> columns) {
    TiberoNames.validateUserName(table);
    for (DatasetColumnRequest c : columns) {
      // 시스템 컬럼(ID·IMPORT_ID·CREATED_AT·_UPDATED_AT)은 이 빌더가 직접 넣으므로 사용자 컬럼으로 오면 중복 정의가 된다 — PG 와 같은
      // 400.
      rejectSystemColumn(c.columnName());
      TiberoNames.validateUserName(c.columnName());
    }
    // 인덱스 키 길이를 DDL 실행 전에 검사한다(컬럼 인덱스는 하나씩, PK 유니크 인덱스는 합쳐서).
    for (DatasetColumnRequest c : columns) {
      if (c.isIndexed()) {
        checkIndexKeyLength(List.of(c));
      }
    }
    checkIndexKeyLength(columns.stream().filter(DatasetColumnRequest::isPrimaryKey).toList());
    String qt = TiberoNames.table(schema, table);
    List<String> out = new ArrayList<>();
    out.add(
        "CREATE SEQUENCE "
            + qualified(schema, TiberoNames.sequenceName(schema, table))
            + " START WITH 1 INCREMENT BY 1 NOCACHE");

    StringBuilder ddl = new StringBuilder("CREATE TABLE " + qt + " (");
    ddl.append("\"ID\" NUMBER(19) PRIMARY KEY, \"IMPORT_ID\" NUMBER(19)");
    for (DatasetColumnRequest c : columns) {
      ddl.append(", ").append(columnDefinition(schema, table, c));
    }
    ddl.append(", \"CREATED_AT\" TIMESTAMP DEFAULT SYSTIMESTAMP");
    ddl.append(", \"_UPDATED_AT\" TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL)");
    out.add(ddl.toString());

    for (DatasetColumnRequest c : columns) {
      if (c.isIndexed()) {
        out.add(createIndex(schema, table, c.columnName()));
      }
    }
    List<String> pk =
        columns.stream()
            .filter(DatasetColumnRequest::isPrimaryKey)
            .map(c -> c.columnName())
            .toList();
    if (!pk.isEmpty()) {
      out.add(createPrimaryKeyIndex(schema, table, pk));
    }
    out.add(createIndex(schema, table, UPDATED_AT));
    out.add(grantReadOnly(schema, table));
    return out;
  }

  /**
   * 행 트리거 한 개(WD-77): INSERT 때 {@code ID} 가 비었으면 테이블 전용 시퀀스에서 채우고, UPDATE 때 {@code _UPDATED_AT} 을
   * 갱신한다.
   *
   * <p>PG 는 bigserial·트리거가 이 일을 하지만 Tibero 테이블에는 기본값이 없어, 사용자가 쓴 INSERT 가 ID 를 못 채우고 UPDATE 가 갱신
   * 시각을 못 옮긴다. {@code ID} 가 이미 있으면 건드리지 않으므로 ID 를 직접 넣는 기존 적재 경로(옮기기·스테이징·{@code
   * TiberoDmlSql.insert})는 시퀀스를 두 번 쓰지 않는다. 실서버(Tibero 7)에서 확인한 문법이다. 컬럼 {@code DEFAULT
   * 시퀀스.NEXTVAL} 도 지원되지만 {@code _UPDATED_AT} 에는 트리거가 어차피 필요해 하나로 묶었다.
   */
  public static String createRowTrigger(String schema, String table) {
    String seq = qualified(schema, TiberoNames.sequenceName(schema, table));
    return "CREATE OR REPLACE TRIGGER "
        + qualified(schema, TiberoNames.triggerName(schema, table))
        + " BEFORE INSERT OR UPDATE ON "
        + TiberoNames.table(schema, table)
        + " FOR EACH ROW\n"
        + "BEGIN\n"
        + "  IF INSERTING AND :NEW.\"ID\" IS NULL THEN :NEW.\"ID\" := "
        + seq
        + ".NEXTVAL; END IF;\n"
        + "  IF UPDATING THEN :NEW.\"_UPDATED_AT\" := SYSTIMESTAMP; END IF;\n"
        + "END;";
  }

  /**
   * 사용자 컬럼 이름이 시스템 예약 컬럼({@link DataTableStore#SYSTEM_COLUMNS})이면 PG 구현과 같은 메시지의 400 으로 거부한다.
   * {@code createTable}(따라서 {@code cloneTable})은 이 빌더 안에서, {@code addColumn} 은 저장소가 DDL 전에 부른다.
   *
   * @throws InvalidTableNameException 시스템 예약 컬럼 이름일 때
   */
  public static void rejectSystemColumn(String columnName) {
    if (DataTableStore.SYSTEM_COLUMNS.contains(columnName.toLowerCase(Locale.ROOT))) {
      throw new InvalidTableNameException(
          "컬럼명 '" + columnName + "'은 시스템 예약어입니다. (예약어: id, import_id, created_at, _updated_at)");
    }
  }

  /** 테이블 삭제 순서: 테이블 → 시퀀스. 없는 객체 무시는 실행부 책임. */
  public static List<String> dropTable(String schema, String table) {
    return List.of(dropTableOnly(schema, table), dropSequence(schema, table));
  }

  /** 테이블만 지운다. */
  public static String dropTableOnly(String schema, String table) {
    return "DROP TABLE " + TiberoNames.table(schema, table);
  }

  /** 테이블 전용 시퀀스를 지운다. */
  public static String dropSequence(String schema, String table) {
    return "DROP SEQUENCE " + qualified(schema, TiberoNames.sequenceName(schema, table));
  }

  /** 컬럼 추가 + (지정 시) 인덱스. 시스템 컬럼 이름 검증은 호출자 책임. */
  public static List<String> addColumn(String schema, String table, DatasetColumnRequest column) {
    TiberoNames.validateUserName(table);
    TiberoNames.validateUserName(column.columnName());
    if (column.isIndexed()) {
      checkIndexKeyLength(List.of(column));
    }
    List<String> out = new ArrayList<>();
    out.add(
        "ALTER TABLE "
            + TiberoNames.table(schema, table)
            + " ADD ("
            + columnDefinition(schema, table, column)
            + ")");
    if (column.isIndexed()) {
      out.add(createIndex(schema, table, column.columnName()));
    }
    return out;
  }

  /** 컬럼 삭제(해당 컬럼 인덱스는 Tibero 가 함께 지운다). */
  public static String dropColumn(String schema, String table, String column) {
    return "ALTER TABLE "
        + TiberoNames.table(schema, table)
        + " DROP COLUMN "
        + TiberoNames.column(column);
  }

  /** 컬럼 이름 변경. 새 이름은 검증한다. */
  public static String renameColumn(String schema, String table, String oldName, String newName) {
    TiberoNames.validateUserName(newName);
    return "ALTER TABLE "
        + TiberoNames.table(schema, table)
        + " RENAME COLUMN "
        + TiberoNames.column(oldName)
        + " TO "
        + TiberoNames.column(newName);
  }

  /**
   * 컬럼 타입 변경. 기존 데이터 변환은 Tibero 의 암묵 변환에 맡긴다(변환 불가 값이 있으면 DB 가 거부한다).
   *
   * @throws DataStoreFeatureUnsupportedException 변경 전·후 타입이 GEOMETRY 일 때
   */
  public static List<DdlStep> alterColumnType(
      String schema,
      String table,
      String column,
      String dataType,
      Integer maxLength,
      String currentDataType) {
    if (isGeometry(dataType) || isGeometry(currentDataType)) {
      throw new DataStoreFeatureUnsupportedException(DataStoreBackend.TIBERO, "GEOMETRY 컬럼");
    }
    String qt = TiberoNames.table(schema, table);
    String qc = TiberoNames.column(column);
    boolean toBoolean = isBoolean(dataType);
    boolean fromBoolean = isBoolean(currentDataType);
    List<DdlStep> out = new ArrayList<>();
    // BOOLEAN 을 벗어나면 이름 있는 CHECK 를 먼저 지운다(남기면 새 타입 값이 0/1 로 제한된다). 이미 없으면 무시한다.
    if (fromBoolean && !toBoolean) {
      out.add(
          DdlStep.ignoring(
              dropCheckConstraint(schema, table, column), TiberoDialect.NotFound.CONSTRAINT));
    }
    out.add(
        DdlStep.of(
            "ALTER TABLE "
                + qt
                + " MODIFY ("
                + qc
                + " "
                + TiberoDialect.columnDdl(dataType, maxLength)
                + ")"));
    if (toBoolean && !fromBoolean) {
      out.add(DdlStep.of("ALTER TABLE " + qt + " ADD " + checkClause(schema, table, column)));
    }
    return out;
  }

  /** BOOLEAN CHECK 제약 삭제. 제약이 없으면 DB 가 거부하므로 실행부가 "제약 없음"을 무시한다. */
  public static String dropCheckConstraint(String schema, String table, String column) {
    return "ALTER TABLE "
        + TiberoNames.table(schema, table)
        + " DROP CONSTRAINT "
        + TiberoNames.quote(TiberoNames.checkConstraintName(schema, table, column));
  }

  /** 컬럼 이름 변경에 맞춘 CHECK 제약 이름 변경(제약 이름이 컬럼 해시라 새 이름으로 옮긴다). */
  public static String renameCheckConstraint(
      String schema, String table, String oldCol, String newCol) {
    return "ALTER TABLE "
        + TiberoNames.table(schema, table)
        + " RENAME CONSTRAINT "
        + TiberoNames.quote(TiberoNames.checkConstraintName(schema, table, oldCol))
        + " TO "
        + TiberoNames.quote(TiberoNames.checkConstraintName(schema, table, newCol));
  }

  /** NOT NULL 제약 켜기/끄기. */
  public static String setNullable(String schema, String table, String column, boolean nullable) {
    return "ALTER TABLE "
        + TiberoNames.table(schema, table)
        + " MODIFY ("
        + TiberoNames.column(column)
        + (nullable ? " NULL)" : " NOT NULL)");
  }

  /** 컬럼 인덱스 생성. */
  public static String createIndex(String schema, String table, String column) {
    return "CREATE INDEX "
        + qualified(schema, TiberoNames.indexName(schema, table, column))
        + " ON "
        + TiberoNames.table(schema, table)
        + " ("
        + TiberoNames.column(column)
        + ")";
  }

  /** 컬럼 인덱스 삭제. */
  public static String dropIndex(String schema, String table, String column) {
    return "DROP INDEX " + qualified(schema, TiberoNames.indexName(schema, table, column));
  }

  /** 컬럼명 변경에 맞춘 인덱스 이름 변경(이름이 컬럼 해시라 새 이름으로 옮긴다). */
  public static String renameIndex(String schema, String table, String oldCol, String newCol) {
    TiberoNames.validateUserName(newCol);
    return "ALTER INDEX "
        + qualified(schema, TiberoNames.indexName(schema, table, oldCol))
        + " RENAME TO "
        + TiberoNames.quote(TiberoNames.indexName(schema, table, newCol));
  }

  /** PK 유니크 인덱스 삭제. */
  public static String dropPrimaryKeyIndex(String schema, String table) {
    return "DROP INDEX " + qualified(schema, TiberoNames.primaryKeyIndexName(schema, table));
  }

  /** PK 컬럼 기준 유니크 인덱스 생성. */
  public static String createPrimaryKeyIndex(String schema, String table, List<String> pkColumns) {
    List<String> cols = pkColumns.stream().map(TiberoNames::column).toList();
    return "CREATE UNIQUE INDEX "
        + qualified(schema, TiberoNames.primaryKeyIndexName(schema, table))
        + " ON "
        + TiberoNames.table(schema, table)
        + " ("
        + String.join(", ", cols)
        + ")";
  }

  /**
   * 복제: 대상 테이블을 {@link #createTable} 과 같은 DDL 로 만들고(컬럼 정의는 {@code columnDefs} 에서 {@code
   * userColumns} 순서로), 마지막에 원본 데이터를 새 시퀀스로 ID 를 매겨 복사한다. CTAS 를 쓰지 않는 이유: NOT NULL·CHECK·인덱스·시퀀스를 한
   * 번에 맞추기 위해서다. {@code _UPDATED_AT} 은 PG 구현처럼 원본 값을 옮기지 않고 복제 시각으로 시작한다.
   */
  public static List<String> cloneTable(
      String schema,
      String source,
      String target,
      List<String> userColumns,
      List<DatasetColumnResponse> columnDefs) {
    TiberoNames.validateUserName(target);
    List<DatasetColumnRequest> requests = new ArrayList<>();
    for (String name : userColumns) {
      DatasetColumnResponse def =
          columnDefs.stream()
              .filter(d -> d.columnName().equalsIgnoreCase(name))
              .findFirst()
              .orElseThrow(() -> new IllegalArgumentException("컬럼 정의가 없습니다: " + name));
      requests.add(
          new DatasetColumnRequest(
              def.columnName(),
              def.displayName(),
              def.dataType(),
              def.maxLength(),
              def.isNullable(),
              // 복제는 사용자 인덱스·PK 인덱스를 만들지 않는다 — 호출자(DatasetService)가 setColumnIndex·
              // recreatePrimaryKeyIndex 로 따로 만들며, 같은 해시 이름이라 여기서 만들면 ORA-00955 로 충돌한다.
              false,
              def.description(),
              false));
    }
    List<String> out = new ArrayList<>(createTable(schema, target, requests));

    List<String> cols = new ArrayList<>();
    for (String name : userColumns) {
      cols.add(TiberoNames.column(name));
    }
    String userList = cols.isEmpty() ? "" : String.join(", ", cols) + ", ";
    out.add(
        "INSERT INTO "
            + TiberoNames.table(schema, target)
            + " (\"ID\", "
            + userList
            + "\"CREATED_AT\", \"_UPDATED_AT\") SELECT "
            + qualified(schema, TiberoNames.sequenceName(schema, target))
            + ".NEXTVAL, "
            + userList
            + "\"CREATED_AT\", SYSTIMESTAMP FROM "
            + TiberoNames.table(schema, source));
    return out;
  }

  /**
   * 컬럼 통계 집계 쿼리. 총건수·NULL 수·고유값 수·최소/최대와 숫자형이면 평균.
   *
   * <p>MIN/MAX 는 컬럼의 실제 타입 순서로 집계한 뒤 문자열로 바꾼다(문자열로 먼저 바꾸면 사전순이 된다). 문자열 변환은 NLS 설정에 의존하지 않도록 타입별 명시
   * 마스크를 쓴다({@link TiberoText#ofLogical}).
   */
  public static String columnStats(String schema, String table, String column, String dataType) {
    String qc = TiberoNames.column(column);
    boolean numeric = isNumeric(dataType);
    return "SELECT COUNT(*) AS TOTAL, COUNT(*) - COUNT("
        + qc
        + ") AS NULL_COUNT, COUNT(DISTINCT "
        + qc
        + ") AS DISTINCT_COUNT, "
        + TiberoText.ofLogical("MIN(" + qc + ")", dataType)
        + " AS MIN_VAL, "
        + TiberoText.ofLogical("MAX(" + qc + ")", dataType)
        + " AS MAX_VAL"
        + (numeric ? ", AVG(" + qc + ") AS AVG_VAL" : "")
        + " FROM "
        + TiberoNames.table(schema, table);
  }

  /**
   * 빈도 상위 5개 값. ROW_NUMBER 로 자른다(동률은 값 순으로 결정적). 값 문자열은 {@link TiberoText#ofLogical} 와 같은 마스크를 쓴다.
   */
  public static String topValues(String schema, String table, String column, String dataType) {
    String qc = TiberoNames.column(column);
    String text = TiberoText.ofLogical(qc, dataType);
    return "SELECT VAL, CNT FROM (SELECT "
        + text
        + " AS VAL, COUNT(*) AS CNT, ROW_NUMBER() OVER (ORDER BY COUNT(*) DESC, "
        + text
        + ") AS RN FROM "
        + TiberoNames.table(schema, table)
        + " WHERE "
        + qc
        + " IS NOT NULL GROUP BY "
        + qc
        + ") WHERE RN <= 5 ORDER BY RN";
  }

  private static boolean isNumeric(String dataType) {
    return "INTEGER".equalsIgnoreCase(dataType) || "DECIMAL".equalsIgnoreCase(dataType);
  }

  /** 읽기 전용 계정({@code <schema>_RO}) 에 SELECT 권한을 준다. */
  public static String grantReadOnly(String schema, String table) {
    return "GRANT SELECT ON "
        + TiberoNames.table(schema, table)
        + " TO "
        + TiberoNames.quote(TiberoNames.physical(schema) + "_RO");
  }

  /** BOOLEAN 의 이름 있는 CHECK 절. */
  private static String checkClause(String schema, String table, String column) {
    return "CONSTRAINT "
        + TiberoNames.quote(TiberoNames.checkConstraintName(schema, table, column))
        + " CHECK ("
        + TiberoNames.column(column)
        + " IN (0,1))";
  }

  /** 사용자 컬럼 정의 조각: 이름 + 타입 [+ BOOLEAN 이름 있는 CHECK] [+ NOT NULL]. */
  private static String columnDefinition(String schema, String table, DatasetColumnRequest c) {
    String qc = TiberoNames.column(c.columnName());
    StringBuilder sb =
        new StringBuilder(qc + " " + TiberoDialect.columnDdl(c.dataType(), c.maxLength()));
    if (isBoolean(c.dataType())) {
      sb.append(" ").append(checkClause(schema, table, c.columnName()));
    }
    if (!c.isNullable()) {
      sb.append(" NOT NULL");
    }
    return sb.toString();
  }

  private static boolean isBoolean(String type) {
    return type != null && "BOOLEAN".equals(type.trim().toUpperCase(Locale.ROOT));
  }

  private static boolean isGeometry(String type) {
    return type != null && "GEOMETRY".equals(type.trim().toUpperCase(Locale.ROOT));
  }

  /** {@code "SCHEMA"."OBJECT"} (객체 이름은 이미 물리 이름). */
  private static String qualified(String schema, String object) {
    return TiberoNames.quote(TiberoNames.physical(schema)) + "." + TiberoNames.quote(object);
  }
}
