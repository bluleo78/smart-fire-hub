package com.smartfirehub.dataset.repository;

import static org.jooq.impl.DSL.*;

import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.dto.UpdateColumnRequest;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
// 배경 잡(JobRunr/@Async/@Scheduled)은 앰비언트 트랜잭션이 없다. RLS GUC 는 트랜잭션 시작
// 시점에만 주입되므로, 트랜잭션이 없으면 TenantContext 를 세워도 정책이 전 행을 차단한다.
// REQUIRED 라 서비스가 이미 연 트랜잭션에는 합류한다(기존 경로 동작 불변).
@Transactional
public class DatasetColumnRepository {

  private final DSLContext dsl;

  private static final Table<?> DATASET_COLUMN = table(name("dataset_column"));
  private static final Field<Long> COL_ID = field(name("dataset_column", "id"), Long.class);
  private static final Field<Long> COL_DATASET_ID =
      field(name("dataset_column", "dataset_id"), Long.class);
  private static final Field<String> COL_COLUMN_NAME =
      field(name("dataset_column", "column_name"), String.class);
  private static final Field<String> COL_DISPLAY_NAME =
      field(name("dataset_column", "display_name"), String.class);
  private static final Field<String> COL_DATA_TYPE =
      field(name("dataset_column", "data_type"), String.class);
  private static final Field<Integer> COL_MAX_LENGTH =
      field(name("dataset_column", "max_length"), Integer.class);
  private static final Field<Boolean> COL_IS_NULLABLE =
      field(name("dataset_column", "is_nullable"), Boolean.class);
  private static final Field<Boolean> COL_IS_INDEXED =
      field(name("dataset_column", "is_indexed"), Boolean.class);
  private static final Field<String> COL_DESCRIPTION =
      field(name("dataset_column", "description"), String.class);
  private static final Field<Integer> COL_COLUMN_ORDER =
      field(name("dataset_column", "column_order"), Integer.class);
  private static final Field<Boolean> COL_IS_PRIMARY_KEY =
      field(name("dataset_column", "is_primary_key"), Boolean.class);

  private DatasetColumnResponse mapToColumnResponse(Record r) {
    return new DatasetColumnResponse(
        r.get(COL_ID),
        r.get(COL_COLUMN_NAME),
        r.get(COL_DISPLAY_NAME),
        r.get(COL_DATA_TYPE),
        r.get(COL_MAX_LENGTH),
        r.get(COL_IS_NULLABLE),
        r.get(COL_IS_INDEXED),
        r.get(COL_DESCRIPTION),
        r.get(COL_COLUMN_ORDER),
        Boolean.TRUE.equals(r.get(COL_IS_PRIMARY_KEY)));
  }

  public List<DatasetColumnResponse> findByDatasetId(Long datasetId) {
    return dsl.select(
            COL_ID,
            COL_COLUMN_NAME,
            COL_DISPLAY_NAME,
            COL_DATA_TYPE,
            COL_MAX_LENGTH,
            COL_IS_NULLABLE,
            COL_IS_INDEXED,
            COL_DESCRIPTION,
            COL_COLUMN_ORDER,
            COL_IS_PRIMARY_KEY)
        .from(DATASET_COLUMN)
        .where(COL_DATASET_ID.eq(datasetId))
        .orderBy(COL_COLUMN_ORDER.asc())
        .fetch(this::mapToColumnResponse);
  }

  public DatasetColumnResponse save(Long datasetId, DatasetColumnRequest request, int columnOrder) {
    return dsl.insertInto(DATASET_COLUMN)
        .set(COL_DATASET_ID, datasetId)
        .set(COL_COLUMN_NAME, request.columnName())
        .set(COL_DISPLAY_NAME, request.displayName())
        .set(COL_DATA_TYPE, request.dataType())
        .set(COL_MAX_LENGTH, request.maxLength())
        .set(COL_IS_NULLABLE, request.isNullable())
        .set(COL_IS_INDEXED, request.isIndexed())
        .set(COL_DESCRIPTION, request.description())
        .set(COL_COLUMN_ORDER, columnOrder)
        .set(COL_IS_PRIMARY_KEY, request.isPrimaryKey())
        .returning(
            COL_ID,
            COL_COLUMN_NAME,
            COL_DISPLAY_NAME,
            COL_DATA_TYPE,
            COL_MAX_LENGTH,
            COL_IS_NULLABLE,
            COL_IS_INDEXED,
            COL_DESCRIPTION,
            COL_COLUMN_ORDER,
            COL_IS_PRIMARY_KEY)
        .fetchOne(this::mapToColumnResponse);
  }

  public void saveBatch(Long datasetId, List<DatasetColumnRequest> columns) {
    for (int i = 0; i < columns.size(); i++) {
      DatasetColumnRequest col = columns.get(i);
      dsl.insertInto(DATASET_COLUMN)
          .set(COL_DATASET_ID, datasetId)
          .set(COL_COLUMN_NAME, col.columnName())
          .set(COL_DISPLAY_NAME, col.displayName())
          .set(COL_DATA_TYPE, col.dataType())
          .set(COL_MAX_LENGTH, col.maxLength())
          .set(COL_IS_NULLABLE, col.isNullable())
          .set(COL_IS_INDEXED, col.isIndexed())
          .set(COL_DESCRIPTION, col.description())
          .set(COL_COLUMN_ORDER, i)
          .set(COL_IS_PRIMARY_KEY, col.isPrimaryKey())
          .execute();
    }
  }

  public Optional<DatasetColumnResponse> findById(Long id) {
    return dsl.select(
            COL_ID,
            COL_COLUMN_NAME,
            COL_DISPLAY_NAME,
            COL_DATA_TYPE,
            COL_MAX_LENGTH,
            COL_IS_NULLABLE,
            COL_IS_INDEXED,
            COL_DESCRIPTION,
            COL_COLUMN_ORDER,
            COL_IS_PRIMARY_KEY)
        .from(DATASET_COLUMN)
        .where(COL_ID.eq(id))
        .fetchOptional(this::mapToColumnResponse);
  }

  public void update(Long id, UpdateColumnRequest request) {
    // 부분 수정(PUT) 의미를 유지하기 위해 null 필드는 기존 값을 보존한다.
    // 특히 is_indexed 컬럼은 NOT NULL 제약이므로 null 세팅 시 IntegrityConstraintViolation → 500 (#222).
    var step = dsl.update(DATASET_COLUMN);
    // jOOQ 빌더 타입을 통일하기 위해 항상 1개 이상의 set이 필요. id로 강제 self-assign.
    var set = step.set(COL_ID, COL_ID);

    if (request.displayName() != null) {
      set = set.set(COL_DISPLAY_NAME, request.displayName());
    }
    if (request.isIndexed() != null) {
      set = set.set(COL_IS_INDEXED, request.isIndexed());
    }
    if (request.description() != null) {
      set = set.set(COL_DESCRIPTION, request.description());
    }
    if (request.columnName() != null) {
      set = set.set(COL_COLUMN_NAME, request.columnName());
    }
    if (request.dataType() != null) {
      set = set.set(COL_DATA_TYPE, request.dataType());
    }
    if (request.maxLength() != null) {
      set = set.set(COL_MAX_LENGTH, request.maxLength());
    }
    // maxLength를 null로 명시적으로 세팅해야 하는 경우 (VARCHAR→TEXT 등)
    if (request.dataType() != null && !"VARCHAR".equals(request.dataType())) {
      set = set.setNull(COL_MAX_LENGTH);
    }
    if (request.isNullable() != null) {
      set = set.set(COL_IS_NULLABLE, request.isNullable());
    }
    if (request.isPrimaryKey() != null) {
      set = set.set(COL_IS_PRIMARY_KEY, request.isPrimaryKey());
    }

    set.where(COL_ID.eq(id)).execute();
  }

  public int getMaxOrder(Long datasetId) {
    Integer maxOrder =
        dsl.select(max(COL_COLUMN_ORDER))
            .from(DATASET_COLUMN)
            .where(COL_DATASET_ID.eq(datasetId))
            .fetchOne(0, Integer.class);
    return maxOrder != null ? maxOrder : -1;
  }

  public void deleteByDatasetId(Long datasetId) {
    dsl.deleteFrom(DATASET_COLUMN).where(COL_DATASET_ID.eq(datasetId)).execute();
  }

  /**
   * 데이터셋 컬럼 메타 행 전체(모든 열 — id·is_searchable·tenant_id 포함)를 그대로 읽는다(WD-238). 응답 DTO 는 일부 열만 담아, 실패한
   * TEMP 재생성을 되돌릴 때 검색 대상 설정·컬럼 id 를 잃지 않도록 행 그대로 보관한다.
   */
  public List<Record> snapshotByDatasetId(Long datasetId) {
    return dsl.select()
        .from(DATASET_COLUMN)
        .where(COL_DATASET_ID.eq(datasetId))
        .orderBy(COL_COLUMN_ORDER)
        .fetch();
  }

  /**
   * {@link #snapshotByDatasetId} 로 보관한 행으로 데이터셋 컬럼 메타를 되돌린다 — 현재 행을 지우고 보관 행을 id 까지 그대로 다시
   * 넣는다(WD-238).
   */
  public void restoreSnapshot(Long datasetId, List<Record> snapshot) {
    deleteByDatasetId(datasetId);
    for (Record row : snapshot) {
      // set(Record) 는 「바뀐 값」만 넣으므로 조회한 행에는 쓸 수 없다 — 열마다 값을 그대로 옮긴다.
      java.util.Map<Field<?>, Object> values = new java.util.LinkedHashMap<>();
      for (Field<?> f : row.fields()) {
        values.put(field(name("dataset_column", f.getName())), row.get(f));
      }
      dsl.insertInto(DATASET_COLUMN).set(values).execute();
    }
  }

  /**
   * 같은 데이터셋에 이 이름의 필드가 있는지 — 필드명 중복 사전 검사(WD-99). {@code excludeColumnId} 는 비교에서 뺄 필드(이름 변경 시 자기
   * 자신)이며 null 이면 모두 비교한다. 목록 전체를 읽지 않도록 EXISTS 한 번으로 묻는다.
   */
  public boolean existsByDatasetIdAndColumnName(
      Long datasetId, String columnName, Long excludeColumnId) {
    var condition = COL_DATASET_ID.eq(datasetId).and(COL_COLUMN_NAME.eq(columnName));
    if (excludeColumnId != null) {
      condition = condition.and(COL_ID.ne(excludeColumnId));
    }
    return dsl.fetchExists(DATASET_COLUMN, condition);
  }

  public Optional<Long> findDatasetIdByColumnId(Long columnId) {
    return dsl.select(COL_DATASET_ID)
        .from(DATASET_COLUMN)
        .where(COL_ID.eq(columnId))
        .fetchOptional(r -> r.get(COL_DATASET_ID));
  }

  public void deleteById(Long id) {
    dsl.deleteFrom(DATASET_COLUMN).where(COL_ID.eq(id)).execute();
  }

  /**
   * 데이터셋의 모든 컬럼의 PK 플래그를 한 번에 갱신한다. {@code pkColumnIds} 에 포함된 컬럼은 true, 나머지는 false 로 설정한다. 복합 PK 일괄
   * 변경 시 트랜잭션 안에서 호출되어야 한다.
   */
  public void updatePrimaryKeys(Long datasetId, List<Long> pkColumnIds) {
    // 1) 우선 데이터셋의 모든 컬럼 PK 를 false 로
    dsl.update(DATASET_COLUMN)
        .set(COL_IS_PRIMARY_KEY, Boolean.FALSE)
        .where(COL_DATASET_ID.eq(datasetId))
        .execute();
    if (pkColumnIds == null || pkColumnIds.isEmpty()) {
      return;
    }
    // 2) 지정된 컬럼만 true 로
    dsl.update(DATASET_COLUMN)
        .set(COL_IS_PRIMARY_KEY, Boolean.TRUE)
        .where(COL_DATASET_ID.eq(datasetId))
        .and(COL_ID.in(pkColumnIds))
        .execute();
  }

  public void updateOrders(Long datasetId, List<Long> columnIds) {
    for (int i = 0; i < columnIds.size(); i++) {
      dsl.update(DATASET_COLUMN)
          .set(COL_COLUMN_ORDER, i)
          .where(COL_ID.eq(columnIds.get(i)))
          .and(COL_DATASET_ID.eq(datasetId))
          .execute();
    }
  }

  public String findFirstDescriptionByColumnName(String columnName, Long excludeDatasetId) {
    return dsl.select(COL_DESCRIPTION)
        .from(DATASET_COLUMN)
        .where(COL_COLUMN_NAME.eq(columnName))
        .and(COL_DATASET_ID.ne(excludeDatasetId))
        .and(COL_DESCRIPTION.isNotNull())
        .and(COL_DESCRIPTION.ne(""))
        .limit(1)
        .fetchOptional(r -> r.get(COL_DESCRIPTION))
        .orElse(null);
  }

  public void updateDescription(Long columnId, String description) {
    dsl.update(DATASET_COLUMN)
        .set(COL_DESCRIPTION, description)
        .where(COL_ID.eq(columnId))
        .execute();
  }
}
