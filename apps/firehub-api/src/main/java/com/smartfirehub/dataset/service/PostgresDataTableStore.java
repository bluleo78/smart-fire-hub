package com.smartfirehub.dataset.service;

import com.smartfirehub.dataset.dto.ColumnStatsResponse;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.exception.InvalidTableNameException;
import com.smartfirehub.dataset.rowsearch.IndexRef;
import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.DataTableNames;
import com.smartfirehub.dataset.storage.DataTableStore;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.TenantSchemaProvisioner;
import com.smartfirehub.securitylevel.pythonread.PythonReadGrantSync;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

/**
 * PostgreSQL 데이터셋 저장소의 DDL 구현(WD-72). 기존 {@code DataTableService} 의 본문을 그대로 이사한 것이다 — 동작 변경 없음.
 *
 * <p>WD-100 이후 필드 인덱스는 이름 규칙으로 추측하지 않고 카탈로그로 찾는다 — {@link #findFieldIndexes} 와 {@link
 * #chooseFieldIndexName} 참고(긴 테이블명에서 63바이트로 잘린 이름이 다른 필드와 겹치던 결함).
 *
 * <p>호출자의 트랜잭션에 그대로 참여한다(별도 {@code @Transactional} 없음). 파사드({@code DataTableService})가 {@link
 * DataStoreResolver} 로 이 구현을 골라 위임하므로, 새 백엔드를 추가해도 호출부는 바뀌지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostgresDataTableStore implements DataTableStore {

  @Override
  public DataStoreBackend backend() {
    return DataStoreBackend.POSTGRESQL;
  }

  private final DSLContext dsl;
  private final TenantSchemaProvisioner schemaProvisioner;

  /**
   * PYTHON 읽기 슬롯 GRANT 훅(WD-29). 새 테이블(생성·클론)과 RENAME 맞바꿈(ACL 을 잃는다) 뒤에 커밋 후 계산값을 맞춘다. 반드시 {@link
   * PythonReadGrantSync#syncTableAfterCommit} 경유 — 미커밋 트랜잭션 안에서 동기화를 직접 부르면 새 연결이 그 트랜잭션을 기다려 자기
   * 교착한다.
   */
  private final PythonReadGrantSync pythonReadGrantSync;

  /**
   * _updated_at 트리거를 (재)설치한다. CREATE TABLE ... LIKE 는 트리거를 복사하지 않으므로 새 테이블·임시(_tmp) 테이블·복제 테이블 모두 이
   * 메서드를 거쳐야 한다.
   */
  private void installUpdatedAtTrigger(String tableName) {
    String q = DataSchema.qualify(tableName);
    dsl.execute("DROP TRIGGER IF EXISTS fh_touch_updated_at ON " + q);
    dsl.execute(
        "CREATE TRIGGER fh_touch_updated_at BEFORE INSERT OR UPDATE ON "
            + q
            + " FOR EACH ROW EXECUTE FUNCTION public.fh_touch_updated_at()");
  }

  /**
   * {{last_run_at}} 범위 조건 성능용 인덱스.
   *
   * <p>이름·모양(ix_&lt;table&gt;_upd, (_updated_at))은 기존 테이블을 백필하는 {@code
   * V125__pipeline_incremental_updated_at_indexes} 와 반드시 같아야 한다 — 규약을 둘로 늘리면 V125 가 이미 있는 인덱스를 못
   * 알아보고 중복 인덱스를 만든다. 여기서는 CONCURRENTLY 를 쓰지 않는다: 이 경로는 방금 만든 <b>빈</b> 테이블에만 쓰이므로 빌드가 즉시 끝나고, 데이터셋
   * 생성 트랜잭션 안에서 실행되기 때문에 CONCURRENTLY 자체가 불가능하다.
   */
  private void createUpdatedAtIndex(String tableName) {
    dsl.execute(
        "CREATE INDEX IF NOT EXISTS \""
            + "ix_"
            + tableName
            + "_upd\" ON "
            + DataSchema.qualify(tableName)
            + " (_updated_at)");
  }

  private String mapDataType(String dataType, Integer maxLength) {
    return switch (dataType) {
      case "TEXT" -> "TEXT";
      case "VARCHAR" -> {
        int len = maxLength != null ? maxLength : ColumnPhysicalType.DEFAULT_VARCHAR_LENGTH;
        if (len < 1 || len > 10000) {
          throw new IllegalArgumentException("VARCHAR length must be between 1 and 10000");
        }
        yield "VARCHAR(" + len + ")";
      }
      case "INTEGER" -> "BIGINT";
      // 값 검사(ColumnPhysicalType.enforceLimits)와 같은 상수로 조립해야 "검증 통과 == INSERT 성공"이 유지된다.
      case "DECIMAL" ->
          "NUMERIC("
              + ColumnPhysicalType.DECIMAL_PRECISION
              + ","
              + ColumnPhysicalType.DECIMAL_SCALE
              + ")";
      case "BOOLEAN" -> "BOOLEAN";
      case "DATE" -> "DATE";
      case "TIMESTAMP" -> "TIMESTAMP";
      case "GEOMETRY" -> "GEOMETRY(Geometry, 4326)";
      default -> throw new IllegalArgumentException("Unknown data type: " + dataType);
    };
  }

  /**
   * 행 검색 색인 테이블 이름(fh_search_{datasetId})과 REPLACE 임시 테이블({@link DataTableService#TEMP_TABLE_PREFIX})에
   * 충돌하지 않도록 사용자 테이블 이름에서 예약한다. 파사드({@link DataTableService#validateNewTableName})가 먼저 걸러내지만,
   * 이 구현에 직접 들어오는 호출(계약 테스트 등)에서도 같은 규칙이 지켜지도록 여기서도 본다.
   */
  private void rejectReservedTableName(String tableName) {
    for (String prefix : List.of(IndexRef.TABLE_PREFIX, DataTableService.TEMP_TABLE_PREFIX)) {
      if (tableName.startsWith(prefix)) {
        throw new InvalidTableNameException("'" + prefix + "' 로 시작하는 이름은 시스템 예약어입니다: " + tableName);
      }
    }
  }

  @Override
  public void createTable(String tableName, List<DatasetColumnRequest> columns) {
    DataTableNames.validate(tableName);
    rejectReservedTableName(tableName);
    // 스키마가 없으면 만든다 — 신규 테넌트의 첫 데이터셋 생성이 여기서 지연 생성을 트리거한다.
    // DROP TABLE IF EXISTS(바로 아래)가 대상 스키마 자체를 요구하므로 반드시 그보다 먼저다.
    schemaProvisioner.ensureCurrentTenantSchema();

    // 고아 테이블(참조하는 dataset 행이 없는 물리 테이블)을 회수하기 위해 먼저 지운다.
    //
    // P3-b2 T5 재검토(V112) — 이 DROP 이 이제 안전한 근거는 물리 스키마 분리다. 예전(V109/V110
    // 시점)에는 idx_dataset_table_name 이 전역 유니크였고 DataSchema.current() 가 상수 "data" 를
    // 돌려줘 모든 테넌트가 같은 물리 스키마를 공유했다 — 그 상태에서 유니크를 (tenant_id,
    // table_name) 으로 접으면 두 테넌트의 같은 이름이 data 스키마의 같은 물리 테이블로 해석되고,
    // 이 DROP 이 앞선 테넌트의 데이터를 예외 없이 지우고 커밋되는 경로가 열렸다(그래서 V109 가
    // V110 으로 되돌려졌다 — task-1-report.md/V110 참조).
    //
    // 지금은 다르다: DataSchema.current() 가 테넌트별 물리 스키마(data_t{tenantId})를 돌려주므로
    // (P3-b2 T1, 커밋 266c002b), qualify(tableName) 이 이미 "현재 테넌트의 스키마 안의 그 이름"
    // 으로 완전히 한정한다 — 다른 테넌트가 같은 tableName 으로 만든 물리 테이블은 애초에
    // 이 DROP 문의 대상 범위(다른 스키마)에 들지 않는다. 그래서 V112 가 유니크를 (tenant_id,
    // table_name) 으로 접어도(=서로 다른 테넌트가 같은 table_name 을 카탈로그 행 레벨에서 공유해도)
    // 물리적으로는 서로 다른 테이블을 가리키므로 크로스 테넌트 데이터 손실은 일어나지 않는다 —
    // recreatingOneTenantsTableLeavesTheOthersRowsIntact(DataTableServiceTenantUniqueTest)가
    // 이걸 행 단위로 고정한다. 이 DROP 이 회수하는 "고아 테이블"은 여전히 같은 테넌트 안의
    // 것뿐이다(DatasetRepository.existsByTableName 이 이제 RLS 스코프 = 물리 스코프와 정확히
    // 일치하므로).
    dsl.execute("DROP TABLE IF EXISTS " + DataSchema.qualify(tableName));

    StringBuilder sql = new StringBuilder();
    sql.append("CREATE TABLE ").append(DataSchema.qualify(tableName)).append(" (");
    sql.append("id BIGSERIAL PRIMARY KEY, ");
    sql.append("import_id BIGINT, ");

    // id, import_id, created_at, _updated_at은 시스템 예약 컬럼명 — 사용자 지정 불가
    for (DatasetColumnRequest col : columns) {
      if (SYSTEM_COLUMNS.contains(col.columnName().toLowerCase())) {
        throw new InvalidTableNameException(
            "컬럼명 '"
                + col.columnName()
                + "'은 시스템 예약어입니다. (예약어: id, import_id, created_at, _updated_at)");
      }
      DataTableNames.validate(col.columnName());
      sql.append("\"").append(col.columnName()).append("\" ");
      sql.append(mapDataType(col.dataType(), col.maxLength()));
      if (!col.isNullable()) {
        sql.append(" NOT NULL");
      }
      sql.append(", ");
    }

    sql.append("created_at TIMESTAMP DEFAULT NOW()");
    sql.append(", _updated_at TIMESTAMPTZ NOT NULL DEFAULT now()");
    sql.append(")");

    dsl.execute(sql.toString());

    // 신규 테이블도 기존 테이블(V124 백필)과 동일하게 증분 처리 기반(트리거+인덱스)을 갖춘다.
    installUpdatedAtTrigger(tableName);
    createUpdatedAtIndex(tableName);

    // 필드 인덱스 — GEOMETRY 는 항상 GiST, 나머지는 인덱스를 켠 필드만 B-tree. 방금 만든 테이블이라 기존 인덱스 확인은 건너뛴다.
    for (DatasetColumnRequest col : columns) {
      FieldIndexKind kind = autoIndexKind(col.dataType(), col.isIndexed());
      if (kind != null) {
        createFieldIndex(tableName, col.columnName(), kind, true);
      }
    }

    // Create unique index for primary key columns
    List<DatasetColumnRequest> pkColumns =
        columns.stream().filter(DatasetColumnRequest::isPrimaryKey).toList();
    if (!pkColumns.isEmpty()) {
      StringBuilder uniqueSql = new StringBuilder();
      uniqueSql
          .append("CREATE UNIQUE INDEX \"ux_")
          .append(tableName)
          .append("_pk\" ON ")
          .append(DataSchema.qualify(tableName))
          .append(" (");
      for (int i = 0; i < pkColumns.size(); i++) {
        if (i > 0) uniqueSql.append(", ");
        uniqueSql.append("\"").append(pkColumns.get(i).columnName()).append("\"");
      }
      uniqueSql.append(")");
      dsl.execute(uniqueSql.toString());
    }
    // PYTHON 읽기 슬롯 GRANT — 새 테이블은 ACL 이 비어 있다. 커밋 후 데이터셋 등급 기준으로 맞춘다.
    pythonReadGrantSync.syncTableAfterCommit(tableName);
  }

  @Override
  public void addColumn(String tableName, DatasetColumnRequest column) {
    DataTableNames.validate(tableName);
    DataTableNames.validate(column.columnName());

    // id, import_id, created_at, _updated_at은 시스템 예약 컬럼명 — addColumn 시에도 차단
    if (SYSTEM_COLUMNS.contains(column.columnName().toLowerCase())) {
      throw new InvalidTableNameException(
          "컬럼명 '"
              + column.columnName()
              + "'은 시스템 예약어입니다. (예약어: id, import_id, created_at, _updated_at)");
    }

    StringBuilder sql = new StringBuilder();
    sql.append("ALTER TABLE ").append(DataSchema.qualify(tableName)).append(" ");
    sql.append("ADD COLUMN \"").append(column.columnName()).append("\" ");
    sql.append(mapDataType(column.dataType(), column.maxLength()));
    if (!column.isNullable()) {
      sql.append(" NOT NULL");
    }

    dsl.execute(sql.toString());

    // 필드 인덱스 — 규칙은 createTable 과 같다.
    FieldIndexKind kind = autoIndexKind(column.dataType(), column.isIndexed());
    if (kind != null) {
      createFieldIndex(tableName, column.columnName(), kind, false);
    }
  }

  @Override
  public void setColumnIndex(
      String tableName, String columnName, boolean indexed, String columnType) {
    DataTableNames.validate(tableName);
    DataTableNames.validate(columnName);

    if (indexed) {
      createFieldIndex(tableName, columnName, FieldIndexKind.forColumnType(columnType), false);
    } else {
      // 이름을 규칙으로 다시 만들어 지우지 않는다(WD-100) — 잘린 옛 이름·REPLACE 맞바꿈이 남긴 이름은 규칙과 다르고, 규칙 이름이 다른
      // 필드(또는 다른 테이블)의 인덱스와 같을 수 있다. 카탈로그에서 "이 컬럼의 필드 인덱스"만 찾아 실제 이름으로 지운다.
      // GiST 는 지우지 않는다 — GEOMETRY 필드는 인덱스 설정과 무관하게 항상 GiST 를 갖는다(생성·컬럼 추가·복제가 모두 자동으로 붙인다).
      // 끄기가 GiST 까지 지우면 공간 질의가 순차 스캔으로 떨어진다. 우리가 GiST 를 만드는 곳은 GEOMETRY 컬럼뿐이다.
      for (FieldIndex index : findFieldIndexes(tableName, columnName)) {
        if (index.kind() == FieldIndexKind.BTREE) {
          dsl.execute("DROP INDEX " + DataSchema.qualify(index.name()));
        }
      }
    }
  }

  /**
   * 테이블 생성·컬럼 추가 시 자동으로 붙는 필드 인덱스의 종류 — GEOMETRY 는 인덱스 설정과 무관하게 항상 GiST, 나머지는 인덱스를 켰을 때만 B-tree.
   *
   * @return 붙일 인덱스가 없으면 {@code null}
   */
  private static FieldIndexKind autoIndexKind(String dataType, boolean indexed) {
    if ("GEOMETRY".equalsIgnoreCase(dataType)) {
      return FieldIndexKind.GIST;
    }
    return indexed ? FieldIndexKind.BTREE : null;
  }

  /** PostgreSQL 식별자 최대 길이(NAMEDATALEN-1). 넘는 이름은 오류 없이 잘린다. 이름 규칙은 ASCII 만 허용하므로 글자 수 = 바이트 수. */
  static final int PG_MAX_IDENTIFIER_LENGTH = 63;

  /**
   * 데이터셋 필드 인덱스의 종류. {@code amName} 은 카탈로그(pg_am)의 접근 방법 이름, {@code nameSuffix} 는 이름 규칙의 접미사다.
   * GEOMETRY 필드는 GiST, 나머지는 B-tree 를 쓴다.
   */
  enum FieldIndexKind {
    BTREE("btree", ""),
    GIST("gist", "_gist");

    final String amName;
    final String nameSuffix;

    FieldIndexKind(String amName, String nameSuffix) {
      this.amName = amName;
      this.nameSuffix = nameSuffix;
    }

    static FieldIndexKind forColumnType(String columnType) {
      return "GEOMETRY".equalsIgnoreCase(columnType) ? GIST : BTREE;
    }

    /** 카탈로그 조회({@link #findFieldIndexes})가 btree·gist 만 거르므로 두 값만 들어온다. */
    static FieldIndexKind fromAmName(String amName) {
      return switch (amName) {
        case "btree" -> BTREE;
        case "gist" -> GIST;
        default -> throw new IllegalStateException("필드 인덱스가 아닌 접근 방법: " + amName);
      };
    }
  }

  /** 카탈로그에서 찾은 필드 인덱스 한 개 — 실제 이름과 종류. */
  private record FieldIndex(String name, FieldIndexKind kind) {}

  /**
   * 기존 규칙의 필드 인덱스 이름 {@code idx_<table>_<column>}(+{@code _gist}). 63바이트 안에 들어올 때만 쓴다 — 짧은 이름의 기존
   * 인덱스 이름과 동작을 바꾸지 않기 위해서다.
   */
  static String preferredFieldIndexName(String tableName, String columnName, FieldIndexKind kind) {
    return "idx_" + tableName + "_" + columnName + kind.nameSuffix;
  }

  /**
   * 고정 길이 해시 이름 {@code idx_<md5 32자>}(+{@code _gist}) — 최대 41자. 기존 규칙 이름이 63바이트를 넘거나 이미 다른 객체가 쓰고
   * 있을 때 쓴다(WD-95 의 {@code rtmp_<md5>} 관례).
   *
   * <p>해시 입력의 구분자 {@code ':'} 는 이름 규칙({@code [a-z][a-z0-9_]*})에 들어올 수 없는 문자다. {@code _} 로 이으면 테이블
   * {@code a_b}·컬럼 {@code c} 와 테이블 {@code a}·컬럼 {@code b_c} 가 같은 입력이 된다. 종류도 입력에 넣어 같은 컬럼의
   * B-tree·GiST 가 겹치지 않게 한다. md5 를 자르지 않는 이유: 41자로도 한도 안이라 자를 이득이 없고, 자를수록 서로 다른 필드가 겹칠 확률만 커진다.
   */
  static String hashedFieldIndexName(String tableName, String columnName, FieldIndexKind kind) {
    return "idx_"
        + DataTableNames.md5Hex(tableName + ":" + columnName + ":" + kind.amName)
        + kind.nameSuffix;
  }

  /**
   * 새로 만들(또는 이름을 바꿀) 필드 인덱스의 이름을 고른다. 기존 규칙 이름이 63바이트 안이고 스키마에서 비어 있으면 그것을, 아니면 해시 이름을 쓴다.
   *
   * <p>"비어 있는지"는 테이블 단위가 아니라 <b>스키마 단위</b>로 본다 — PostgreSQL 에서 인덱스 이름은 같은 스키마의 테이블·시퀀스·다른 테이블의 인덱스와
   * 이름 공간을 공유한다. 63바이트 안인 규칙 이름도 서로 다른 필드끼리 겹칠 수 있다(위의 {@code a_b}/{@code c} 대 {@code a}/{@code
   * b_c}, 또는 잘린 옛 이름 {@code idx_<t>_abc} 가 63자 규칙 이름 {@code idx_<t>_abc} 와 같은 경우).
   *
   * @param selfName 이름을 바꾸는 인덱스 자신의 현재 이름(생성이면 {@code null}). 자기 이름은 "사용 중"으로 세지 않는다 — 세면 이미 규칙 이름을
   *     가진 인덱스가 자기 자신에 막혀 해시 이름으로 바뀐다.
   * @return 쓸 수 있는 이름. 두 후보가 모두 이미 쓰이고 있으면 {@code null}
   */
  private String chooseFieldIndexName(
      String tableName, String columnName, FieldIndexKind kind, String selfName) {
    String preferred = preferredFieldIndexName(tableName, columnName, kind);
    String hashed = hashedFieldIndexName(tableName, columnName, kind);
    Set<String> taken = takenRelationNames(preferred, hashed);
    if (selfName != null) {
      taken.remove(selfName);
    }
    if (preferred.length() <= PG_MAX_IDENTIFIER_LENGTH && !taken.contains(preferred)) {
      return preferred;
    }
    return taken.contains(hashed) ? null : hashed;
  }

  /** 후보 이름 중 현재 테넌트 데이터 스키마에서 이미 쓰이는(테이블·인덱스·시퀀스 등) 것 — 한 번의 조회로 묻는다. */
  private Set<String> takenRelationNames(String... candidates) {
    return new HashSet<>(
        dsl.fetch(
                "SELECT c.relname::text FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                    + " WHERE n.nspname = ? AND c.relname = ANY(?::text[])",
                DataSchema.current(),
                candidates)
            .getValues(0, String.class));
  }

  /**
   * 필드 인덱스의 「확인 → 이름 고르기 → CREATE/RENAME」을 스키마 단위로 직렬화한다(트랜잭션 범위 advisory 잠금, 커밋·롤백 때 풀린다).
   *
   * <p>왜 필요한가: 확인과 생성 사이에 다른 트랜잭션이 끼면, 같은 컬럼을 동시에 켜는 두 요청(더블클릭)이나 같은 이름을 고른 다른 테이블끼리 둘 다 같은 이름으로
   * CREATE 해 두 번째가 이름 중복(500)으로 실패한다. 미커밋 인덱스는 카탈로그 조회에 보이지 않으므로 확인만으로는 막을 수 없다. 잠금을 쥔 뒤의 조회는 앞선
   * 트랜잭션의 커밋을 보므로, 두 번째 요청은 "이미 있음"으로 건너뛴다. 인덱스 이름은 스키마 단위로 유일하므로 잠금도 스키마 단위다.
   *
   * <p>호출자 트랜잭션 안에서 불려야 의미가 있다 — 이 DDL 의 호출부({@code DatasetService} 의 생성·컬럼 추가·필드 수정·복제)는 모두
   * {@code @Transactional} 이다.
   */
  private void lockFieldIndexNames() {
    // 잠금 키 = 'fh_field_index:' || 스키마명. 스키마명은 바인드로 넘긴다(소스에서 스키마명을 문자열로 잇지 않는다는 DataSchema 규약).
    dsl.execute(
        "SELECT pg_advisory_xact_lock(hashtextextended('fh_field_index:' || ?, 0))",
        DataSchema.current());
  }

  /**
   * 카탈로그에서 그 테이블의 그 컬럼에 걸린 <b>필드 인덱스</b>를 찾는다(WD-100). 이름은 보지 않는다.
   *
   * <p><b>필드 인덱스로 보는 기준</b> — 아래를 모두 만족하는 인덱스다.
   *
   * <ul>
   *   <li>현재 테넌트 데이터 스키마의 그 테이블에 걸려 있다.
   *   <li>키 컬럼이 정확히 하나({@code indnatts = 1})이고 그것이 그 컬럼({@code indkey[0]} 의 attname)이다.
   *   <li>식(expression)·부분(predicate) 인덱스가 아니다({@code indexprs}·{@code indpred} 가 NULL).
   *   <li>UNIQUE·PRIMARY KEY 가 아니다 — 시스템 PK({@code id})와 사용자 PK 유니크 인덱스({@code ux_<table>_pk}, 단일
   *       컬럼 PK 포함)를 건드리지 않는다.
   *   <li>접근 방법이 btree 또는 gist 다(우리가 만드는 두 종류).
   * </ul>
   *
   * <p>이름을 보지 않는 이유: 같은 필드의 인덱스가 여러 이름으로 존재한다 — 규칙 이름, 63바이트에서 잘린 옛 이름, 해시 이름, 그리고 REPLACE
   * 적재({@link #createTempTable} 의 {@code LIKE ... INCLUDING ALL})가 맞바꿈 뒤 남기는 PostgreSQL 기본
   * 이름({@code rtmp_<md5>_<col>_idx}). 이름으로 고르면 이 중 일부를 놓치거나 다른 필드의 인덱스를 잡는다.
   *
   * <p>이 기준이 사용자 인덱스를 잡지 않는 근거: 앱 사용자는 데이터 스키마에 인덱스를 만들 수 없다(SQL 스텝은 SELECT/DML 만 허용, PYTHON 읽기 슬롯은
   * 읽기 전용). {@code _updated_at} 인덱스({@code ix_<table>_upd})는 시스템 컬럼 위라 필드 이름으로는 닿지 않는다.
   */
  private List<FieldIndex> findFieldIndexes(String tableName, String columnName) {
    return dsl.fetch(
            "SELECT ic.relname::text AS index_name, am.amname::text AS am_name"
                + " FROM pg_index i"
                + " JOIN pg_class ic ON ic.oid = i.indexrelid"
                + " JOIN pg_class tc ON tc.oid = i.indrelid"
                + " JOIN pg_namespace n ON n.oid = tc.relnamespace"
                + " JOIN pg_am am ON am.oid = ic.relam"
                + " JOIN pg_attribute a ON a.attrelid = tc.oid AND a.attnum = i.indkey[0]"
                + " WHERE n.nspname = ? AND tc.relname = ? AND a.attname = ?"
                + " AND i.indnatts = 1"
                + " AND i.indexprs IS NULL AND i.indpred IS NULL"
                + " AND NOT i.indisunique AND NOT i.indisprimary"
                + " AND am.amname IN ('btree', 'gist')"
                + " ORDER BY ic.relname",
            DataSchema.current(),
            tableName,
            columnName)
        .map(
            r ->
                new FieldIndex(
                    r.get("index_name", String.class),
                    FieldIndexKind.fromAmName(r.get("am_name", String.class))));
  }

  /**
   * 그 컬럼에 그 종류의 필드 인덱스가 없을 때만 만든다(WD-100).
   *
   * <p>{@code CREATE INDEX IF NOT EXISTS} 를 쓰지 않는다 — IF NOT EXISTS 는 "그 <b>이름</b>이 있는가"만 봐서, 이름이 겹친
   * 다른 필드의 인덱스가 있으면 이 필드의 인덱스를 조용히 건너뛰었다(메타데이터는 「인덱스 있음」인데 실제로는 없음). 대신 카탈로그로 "이 컬럼에 이미 있는가"를 보고(옛
   * 잘린 이름으로 이미 있으면 중복 생성하지 않는다), 없으면 비어 있는 이름을 골라 그냥 CREATE 한다. 그래서 건너뛰기는 "이 컬럼에 이미 있음" 일 때뿐이다. 확인과
   * 생성 사이의 경합은 {@link #lockFieldIndexNames} 가 막는다.
   *
   * @param freshTable 같은 트랜잭션에서 방금 만든 테이블이면 {@code true} — 필드 인덱스가 있을 수 없으므로 확인 조회를 건너뛴다.
   */
  private void createFieldIndex(
      String tableName, String columnName, FieldIndexKind kind, boolean freshTable) {
    lockFieldIndexNames();
    if (!freshTable
        && findFieldIndexes(tableName, columnName).stream().anyMatch(i -> i.kind() == kind)) {
      return;
    }
    String indexName = chooseFieldIndexName(tableName, columnName, kind, null);
    if (indexName == null) {
      // 규칙 이름과 해시 이름이 모두 다른 객체에 쓰이고 있다 — 사실상 일어나지 않지만, 조용히 건너뛰면 결함이 그대로 재현되므로 실패시킨다.
      throw new IllegalStateException(
          "필드 '" + columnName + "' 의 인덱스 이름을 정할 수 없습니다(후보 이름이 모두 사용 중): " + tableName);
    }
    String using = kind == FieldIndexKind.GIST ? " USING GIST" : "";
    dsl.execute(
        "CREATE INDEX \""
            + indexName
            + "\" ON "
            + DataSchema.qualify(tableName)
            + using
            + " (\""
            + columnName
            + "\")");
  }

  @Override
  public void recreatePrimaryKeyIndex(String tableName, List<String> pkColumnNames) {
    DataTableNames.validate(tableName);
    for (String col : pkColumnNames) {
      DataTableNames.validate(col);
    }
    // Drop existing PK index if exists
    String dropSql = "DROP INDEX IF EXISTS " + DataSchema.qualify("ux_" + tableName + "_pk");
    dsl.execute(dropSql);

    // Create new index if there are PK columns
    if (!pkColumnNames.isEmpty()) {
      StringBuilder sql = new StringBuilder();
      sql.append("CREATE UNIQUE INDEX \"ux_")
          .append(tableName)
          .append("_pk\" ON ")
          .append(DataSchema.qualify(tableName))
          .append(" (");
      for (int i = 0; i < pkColumnNames.size(); i++) {
        if (i > 0) sql.append(", ");
        sql.append("\"").append(pkColumnNames.get(i)).append("\"");
      }
      sql.append(")");
      dsl.execute(sql.toString());
    }
  }

  @Override
  public void createPrimaryKeyIndexConcurrently(String tableName, List<String> pkColumnNames) {
    DataTableNames.validate(tableName);
    for (String col : pkColumnNames) {
      DataTableNames.validate(col);
    }
    if (pkColumnNames.isEmpty()) return;

    // Drop existing PK index if exists
    String dropSql = "DROP INDEX IF EXISTS " + DataSchema.qualify("ux_" + tableName + "_pk");
    dsl.execute(dropSql);

    // CREATE INDEX CONCURRENTLY cannot run inside a transaction
    StringBuilder sql = new StringBuilder();
    sql.append("CREATE UNIQUE INDEX CONCURRENTLY \"ux_")
        .append(tableName)
        .append("_pk\" ON ")
        .append(DataSchema.qualify(tableName))
        .append(" (");
    for (int i = 0; i < pkColumnNames.size(); i++) {
      if (i > 0) sql.append(", ");
      sql.append("\"").append(pkColumnNames.get(i)).append("\"");
    }
    sql.append(")");
    dsl.execute(sql.toString());
  }

  @Override
  public void dropTable(String tableName) {
    DataTableNames.validate(tableName);
    String sql = "DROP TABLE IF EXISTS " + DataSchema.qualify(tableName);
    dsl.execute(sql);
  }

  /**
   * Creates a temporary staging table {@link DataTableService#tempTableName} in the current
   * tenant's data schema with the same structure as the original table (using PostgreSQL's LIKE
   * ... INCLUDING ALL). Used by the REPLACE load strategy to safely stage new data before
   * swapping.
   */
  @Override
  public void createTempTable(String tableName) {
    DataTableNames.validate(tableName);
    String tmpName = DataTableService.tempTableName(tableName);
    // Drop any leftover temp table from a previous failed run
    dsl.execute("DROP TABLE IF EXISTS " + DataSchema.qualify(tmpName));
    dsl.execute(
        "CREATE TABLE "
            + DataSchema.qualify(tmpName)
            + " (LIKE "
            + DataSchema.qualify(tableName)
            + " INCLUDING ALL)");
    // LIKE INCLUDING ALL shares the original SERIAL sequence, creating a dependency
    // that blocks DROP TABLE on the original. Give the temp table its own sequence.
    String tmpSeq = tmpName + "_id_seq";
    dsl.execute("DROP SEQUENCE IF EXISTS " + DataSchema.qualify(tmpSeq));
    dsl.execute(
        "CREATE SEQUENCE "
            + DataSchema.qualify(tmpSeq)
            + " OWNED BY "
            + DataSchema.qualify(tmpName)
            + ".id");
    // nextval 인자는 문자열 리터럴 안의 식별자다 — 여기서도 스키마를 한정해야 스왑 후 DEFAULT 가
    // 엉뚱한 스키마의 시퀀스를 가리키지 않는다.
    dsl.execute(
        "ALTER TABLE "
            + DataSchema.qualify(tmpName)
            + " ALTER COLUMN id SET DEFAULT nextval('"
            + DataSchema.qualify(tmpSeq)
            + "')");
    // LIKE INCLUDING ALL은 컬럼 기본값·인덱스는 복사하지만 트리거는 복사하지 않는다 — 임시 테이블에도
    // 별도로 설치해야 스왑(swapTable, RENAME) 후 원본 이름을 물려받은 테이블이 트리거를 유지한다.
    installUpdatedAtTrigger(tmpName);
  }

  /**
   * 원본을 지우고 {@link DataTableService#tempTableName} 을 원본 이름으로 바꾼다(현재 테넌트 데이터 스키마). 삭제·리네임은 하나의
   * {@code dsl.transaction} 안에서 원자적으로 일어난다. 모든 데이터가 임시 테이블에 적재된 뒤에만 호출한다.
   */
  @Override
  public void swapTable(String tableName) {
    DataTableNames.validate(tableName);
    String tmpName = DataTableService.tempTableName(tableName);
    dsl.transaction(
        cfg -> {
          var txDsl = org.jooq.impl.DSL.using(cfg);
          // 원본 id 시퀀스의 실제 이름을 DROP 전에 읽어 둔다 — 원본을 지우면 소유 시퀀스도 함께 사라지고 그 이름을 임시 시퀀스가 물려받는다.
          // 이름을 규칙으로 다시 만들지 않는 이유: PostgreSQL 은 긴 테이블의 시퀀스 이름을 잘라 짓고 충돌하면 번호를 붙이므로, 앞부분이 같은
          // 긴 테이블 둘에서 규칙으로 만든 이름은 다른 테이블의 시퀀스와 겹칠 수 있다.
          String seq =
              (String)
                  txDsl.fetchValue(
                      "SELECT s.relname::text FROM pg_class s WHERE s.oid = pg_get_serial_sequence(?, 'id')::regclass",
                      DataSchema.qualify(tableName));
          if (seq == null) {
            // 원본을 지우기 전이라 안전하게 멈춘다 — 시퀀스 이름 없이 맞바꾸면 다음 REPLACE 의 임시 시퀀스 정리가 살아있는 시퀀스에 닿는다.
            throw new IllegalStateException(
                "테이블 '" + tableName + "' 의 id 시퀀스를 찾을 수 없어 교체(REPLACE) 적재를 할 수 없습니다");
          }
          // 파괴 연산 — 스키마 해석이 틀리면 남의 테넌트 테이블을 지운다. 반드시 헬퍼 경유.
          txDsl.execute("DROP TABLE " + DataSchema.qualify(tableName));
          // RENAME TO 의 새 이름은 스키마를 붙이지 않는다(PostgreSQL 문법). 스키마는 원본을 따라간다.
          txDsl.execute(
              "ALTER TABLE " + DataSchema.qualify(tmpName) + " RENAME TO \"" + tableName + "\"");
          // 시퀀스 리네임을 빠뜨리면 스왑 후 nextval 이 옛 이름의 시퀀스를 가리킨 채 조용히 동작한다.
          String tmpSeq = tmpName + "_id_seq";
          txDsl.execute(
              "ALTER SEQUENCE IF EXISTS "
                  + DataSchema.qualify(tmpSeq)
                  + " RENAME TO \""
                  + seq
                  + "\"");
        });
    // PYTHON 읽기 슬롯 GRANT — RENAME 으로 들어온 임시 테이블은 원본 ACL 을 갖지 않는다. 맞바꿈 트랜잭션 블록 뒤라
    // 바깥 트랜잭션이 없으면 즉시, 있으면 그 커밋 후 복원한다.
    pythonReadGrantSync.syncTableAfterCommit(tableName);
  }

  /**
   * Drops the temporary staging table {@link DataTableService#tempTableName} in the current
   * tenant's data schema if it exists. Called on failure to preserve the original table's data.
   */
  @Override
  public void dropTempTable(String tableName) {
    DataTableNames.validate(tableName);
    String tmpName = DataTableService.tempTableName(tableName);
    dsl.execute("DROP TABLE IF EXISTS " + DataSchema.qualify(tmpName));
  }

  @Override
  public void renameColumn(String tableName, String oldName, String newName) {
    DataTableNames.validate(tableName);
    DataTableNames.validate(oldName);
    DataTableNames.validate(newName);
    String sql =
        "ALTER TABLE "
            + DataSchema.qualify(tableName)
            + " RENAME COLUMN \""
            + oldName
            + "\" TO \""
            + newName
            + "\"";
    dsl.execute(sql);
  }

  @Override
  public void renameIndex(String tableName, String oldColName, String newColName) {
    DataTableNames.validate(tableName);
    DataTableNames.validate(oldColName);
    DataTableNames.validate(newColName);
    // 컬럼 이름 변경(renameColumn) 전에 불린다는 계약이다 — 카탈로그를 옛 컬럼 이름으로 찾는다.
    // 이름을 규칙으로 추측하지 않고(WD-100) 그 컬럼의 필드 인덱스를 모두(B-tree·GiST) 찾아 새 규칙 이름으로 맞춘다.
    lockFieldIndexNames();
    for (FieldIndex index : findFieldIndexes(tableName, oldColName)) {
      String target = chooseFieldIndexName(tableName, newColName, index.kind(), index.name());
      if (target == null) {
        // 새 이름 후보(규칙·해시)가 모두 다른 객체에 쓰이고 있으면 이름만 그대로 두고 컬럼 이름 바꾸기는 계속한다. 인덱스 자체는 살아 있고,
        // 끄기·켜기·다음 이름 바꾸기는 이름이 아니라 카탈로그로 찾으므로 동작에 영향이 없다 — 예전처럼 겹치는 이름으로 RENAME 해 500 을 내는 것보다
        // 낫다. 다만 사실상 일어나지 않아야 하는 상태이므로 원인을 추적할 수 있게 경고를 남긴다.
        log.warn(
            "필드 인덱스 이름을 새 컬럼 이름에 맞추지 못해 그대로 둡니다(후보 이름이 모두 사용 중): table={}, column={}->{}, index={}",
            tableName,
            oldColName,
            newColName,
            index.name());
        continue;
      }
      if (target.equals(index.name())) {
        continue;
      }
      dsl.execute(
          "ALTER INDEX " + DataSchema.qualify(index.name()) + " RENAME TO \"" + target + "\"");
    }
  }

  @Override
  public void alterColumnType(
      String tableName,
      String columnName,
      String dataType,
      Integer maxLength,
      String currentDataType) {
    DataTableNames.validate(tableName);
    DataTableNames.validate(columnName);

    // GEOMETRY로/에서의 타입 변환은 차단한다 (PostgreSQL이 GEOMETRY CAST를 지원하지 않음)
    if ("GEOMETRY".equalsIgnoreCase(dataType) || "GEOMETRY".equalsIgnoreCase(currentDataType)) {
      throw new IllegalArgumentException("GEOMETRY 타입은 다른 타입으로 변환할 수 없습니다. 컬럼을 삭제 후 다시 추가하세요.");
    }

    String newType = mapDataType(dataType, maxLength);
    String sql =
        "ALTER TABLE "
            + DataSchema.qualify(tableName)
            + " ALTER COLUMN \""
            + columnName
            + "\" TYPE "
            + newType
            + " USING \""
            + columnName
            + "\"::"
            + newType;
    dsl.execute(sql);
  }

  @Override
  public void setColumnNullable(String tableName, String columnName, boolean nullable) {
    DataTableNames.validate(tableName);
    DataTableNames.validate(columnName);
    String sql =
        "ALTER TABLE "
            + DataSchema.qualify(tableName)
            + " ALTER COLUMN \""
            + columnName
            + "\" "
            + (nullable ? "DROP NOT NULL" : "SET NOT NULL");
    dsl.execute(sql);
  }

  @Override
  public void dropColumn(String tableName, String columnName) {
    DataTableNames.validate(tableName);
    DataTableNames.validate(columnName);
    String sql =
        "ALTER TABLE " + DataSchema.qualify(tableName) + " DROP COLUMN \"" + columnName + "\"";
    dsl.execute(sql);
  }

  @Override
  public void cloneTable(
      String sourceTable,
      String targetTable,
      List<String> userColumns,
      List<DatasetColumnResponse> columnDefs) {
    DataTableNames.validate(sourceTable);
    DataTableNames.validate(targetTable);
    rejectReservedTableName(targetTable);
    for (String col : userColumns) {
      DataTableNames.validate(col);
    }

    // Build column list for SELECT
    StringBuilder colList = new StringBuilder();
    for (int i = 0; i < userColumns.size(); i++) {
      if (i > 0) colList.append(", ");
      colList.append("\"").append(userColumns.get(i)).append("\"");
    }
    colList.append(", created_at");

    // CREATE TABLE AS SELECT (copies data + column types, but not constraints)
    String createSql =
        "CREATE TABLE "
            + DataSchema.qualify(targetTable)
            + " AS SELECT "
            + colList
            + " FROM "
            + DataSchema.qualify(sourceTable);
    dsl.execute(createSql);

    // Add system columns
    dsl.execute(
        "ALTER TABLE " + DataSchema.qualify(targetTable) + " ADD COLUMN id BIGSERIAL PRIMARY KEY");
    dsl.execute("ALTER TABLE " + DataSchema.qualify(targetTable) + " ADD COLUMN import_id BIGINT");
    // 복제본은 새 데이터셋이므로 원본의 _updated_at 값을 옮기지 않고(CTAS SELECT 목록에서 제외됨)
    // 복제 시각을 기준으로 새로 시작한다.
    dsl.execute(
        "ALTER TABLE "
            + DataSchema.qualify(targetTable)
            + " ADD COLUMN _updated_at TIMESTAMPTZ NOT NULL DEFAULT now()");
    installUpdatedAtTrigger(targetTable);
    createUpdatedAtIndex(targetTable);

    // Re-apply NOT NULL constraints (CTAS does not preserve them)
    for (DatasetColumnResponse col : columnDefs) {
      if (!col.isNullable()) {
        dsl.execute(
            "ALTER TABLE "
                + DataSchema.qualify(targetTable)
                + " ALTER COLUMN \""
                + col.columnName()
                + "\" SET NOT NULL");
      }
    }

    // Recreate GiST indexes for GEOMETRY columns (CTAS does not copy indexes)
    for (DatasetColumnResponse col : columnDefs) {
      if ("GEOMETRY".equalsIgnoreCase(col.dataType())) {
        // CTAS 로 방금 만든 테이블이라 기존 인덱스 확인은 건너뛴다.
        createFieldIndex(targetTable, col.columnName(), FieldIndexKind.GIST, true);
      }
    }
    // PYTHON 읽기 슬롯 GRANT — CTAS 복제본은 새 테이블이라 ACL 이 비어 있다.
    pythonReadGrantSync.syncTableAfterCommit(targetTable);
  }

  private static final Set<String> NUMERIC_TYPES = Set.of("INTEGER", "DECIMAL");

  @Override
  public List<ColumnStatsResponse> getColumnStats(
      String tableName, List<DatasetColumnResponse> columns) {
    DataTableNames.validate(tableName);

    // Set statement_timeout to 30 seconds for profiling queries
    dsl.execute("SET LOCAL statement_timeout = '30s'");

    // Check row count to decide whether to sample
    long rowCount = countRowsInternal(tableName);
    boolean sampled = rowCount > 100_000;
    String qualifiedTable = DataSchema.qualify(tableName);
    String fromClause = sampled ? qualifiedTable + " TABLESAMPLE BERNOULLI(10)" : qualifiedTable;

    List<ColumnStatsResponse> result = new ArrayList<>();

    for (DatasetColumnResponse col : columns) {
      DataTableNames.validate(col.columnName());
      String colName = col.columnName();
      String dataType = col.dataType();

      if ("GEOMETRY".equalsIgnoreCase(dataType)) {
        // GEOMETRY-specific stats: count, null count, bbox via ST_Extent, geometry type
        // distribution
        String geoStatsSql =
            "SELECT COUNT(*) AS total,"
                + " COUNT(*) FILTER (WHERE \""
                + colName
                + "\" IS NULL) AS null_count,"
                + " COUNT(\""
                + colName
                + "\") AS non_null_count,"
                + " ST_AsText(ST_Extent(\""
                + colName
                + "\")) AS bbox"
                + " FROM "
                + fromClause;
        var geoRecord = dsl.fetchOne(geoStatsSql);

        long total = geoRecord.get("total", Long.class);
        long nullCount = geoRecord.get("null_count", Long.class);
        double nullPercent = total > 0 ? (double) nullCount / total * 100.0 : 0.0;
        long nonNullCount = geoRecord.get("non_null_count", Long.class);
        String bbox = geoRecord.get("bbox", String.class);

        // Get geometry type distribution as top values
        String geoTypeSql =
            "SELECT GeometryType(\""
                + colName
                + "\") AS val, COUNT(*) AS cnt"
                + " FROM "
                + fromClause
                + " WHERE \""
                + colName
                + "\" IS NOT NULL"
                + " GROUP BY GeometryType(\""
                + colName
                + "\")"
                + " ORDER BY cnt DESC LIMIT 5";
        var geoTopRecords = dsl.fetch(geoTypeSql);
        List<ColumnStatsResponse.ValueCount> topValues = new ArrayList<>();
        for (var rec : geoTopRecords) {
          String val = rec.get("val", String.class);
          long cnt = rec.get("cnt", Long.class);
          topValues.add(new ColumnStatsResponse.ValueCount(val, cnt));
        }

        result.add(
            new ColumnStatsResponse(
                colName,
                dataType,
                total,
                nullCount,
                nullPercent,
                nonNullCount,
                bbox,
                null,
                null,
                topValues,
                sampled));
        continue;
      }

      // 최솟값·최댓값은 컬럼의 실제 타입 순서로 집계한 뒤 텍스트로 바꾼다(#773).
      // 집계 전에 ::text 로 바꾸면 사전순(collation) 비교가 되어 INTEGER 가 "10 ~ 9" 가 되고,
      // 음수 부호·5자리 연도·'-infinity' 가 범위에서 빠진다. 집계 후 ::text 는 PG 원문 출력이라
      // 범위 밖 날짜(BC·±infinity·10000년)도 그대로 보존된다(#768/#769 원칙).
      // 단, BOOLEAN 은 PG 에 MIN/MAX 집계가 없으므로 기존처럼 텍스트로 집계한다('false' < 'true').
      boolean aggregateAsText = "BOOLEAN".equalsIgnoreCase(dataType);
      String minExpr =
          aggregateAsText ? "MIN(\"" + colName + "\"::text)" : "MIN(\"" + colName + "\")::text";
      String maxExpr =
          aggregateAsText ? "MAX(\"" + colName + "\"::text)" : "MAX(\"" + colName + "\")::text";

      // Build aggregate stats query
      StringBuilder statsSql = new StringBuilder();
      statsSql
          .append("SELECT COUNT(*) AS total,")
          .append(" COUNT(*) FILTER (WHERE \"")
          .append(colName)
          .append("\" IS NULL) AS null_count,")
          .append(" COUNT(DISTINCT \"")
          .append(colName)
          .append("\") AS distinct_count,")
          .append(" ")
          .append(minExpr)
          .append(" AS min_val,")
          .append(" ")
          .append(maxExpr)
          .append(" AS max_val");

      if (NUMERIC_TYPES.contains(dataType)) {
        statsSql.append(", AVG(\"").append(colName).append("\"::numeric) AS avg_val");
      }

      statsSql.append(" FROM ").append(fromClause);

      var statsRecord = dsl.fetchOne(statsSql.toString());

      long total = statsRecord.get("total", Long.class);
      long nullCount = statsRecord.get("null_count", Long.class);
      double nullPercent = total > 0 ? (double) nullCount / total * 100.0 : 0.0;
      long distinctCount = statsRecord.get("distinct_count", Long.class);
      String minVal = statsRecord.get("min_val", String.class);
      String maxVal = statsRecord.get("max_val", String.class);
      Double avgVal = null;
      if (NUMERIC_TYPES.contains(dataType)) {
        Object rawAvg = statsRecord.get("avg_val");
        if (rawAvg != null) {
          avgVal = ((Number) rawAvg).doubleValue();
        }
      }

      // Get top 5 values
      String topSql =
          "SELECT \""
              + colName
              + "\"::text AS val, COUNT(*) AS cnt"
              + " FROM "
              + fromClause
              + " WHERE \""
              + colName
              + "\" IS NOT NULL"
              + " GROUP BY \""
              + colName
              + "\""
              + " ORDER BY cnt DESC LIMIT 5";

      var topRecords = dsl.fetch(topSql);
      List<ColumnStatsResponse.ValueCount> topValues = new ArrayList<>();
      for (var rec : topRecords) {
        String val = rec.get("val", String.class);
        long cnt = rec.get("cnt", Long.class);
        topValues.add(new ColumnStatsResponse.ValueCount(val, cnt));
      }

      result.add(
          new ColumnStatsResponse(
              colName,
              dataType,
              total,
              nullCount,
              nullPercent,
              distinctCount,
              minVal,
              maxVal,
              avgVal,
              topValues,
              sampled));
    }

    return result;
  }

  /**
   * Internal row count used by getColumnStats to avoid circular dependency with
   * DataTableRowService.
   */
  private long countRowsInternal(String tableName) {
    String sql = "SELECT COUNT(*) FROM " + DataSchema.qualify(tableName);
    Long count = dsl.fetchOne(sql).get(0, Long.class);
    return count != null ? count : 0L;
  }
}
