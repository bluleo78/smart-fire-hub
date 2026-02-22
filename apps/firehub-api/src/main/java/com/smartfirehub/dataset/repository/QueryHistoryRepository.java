package com.smartfirehub.dataset.repository;

import static org.jooq.impl.DSL.*;

import com.smartfirehub.dataset.dto.QueryHistoryResponse;
import java.time.LocalDateTime;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

@Repository
public class QueryHistoryRepository {

  private final DSLContext dsl;

  private static final Table<?> QUERY_HISTORY = table(name("query_history"));
  private static final Field<Long> QH_ID = field(name("query_history", "id"), Long.class);
  private static final Field<Long> QH_DATASET_ID =
      field(name("query_history", "dataset_id"), Long.class);
  private static final Field<Long> QH_USER_ID = field(name("query_history", "user_id"), Long.class);
  private static final Field<String> QH_SQL_TEXT =
      field(name("query_history", "sql_text"), String.class);
  private static final Field<String> QH_QUERY_TYPE =
      field(name("query_history", "query_type"), String.class);
  private static final Field<Integer> QH_AFFECTED_ROWS =
      field(name("query_history", "affected_rows"), Integer.class);
  private static final Field<Long> QH_EXECUTION_TIME_MS =
      field(name("query_history", "execution_time_ms"), Long.class);
  private static final Field<Boolean> QH_SUCCESS =
      field(name("query_history", "success"), Boolean.class);
  private static final Field<String> QH_ERROR_MESSAGE =
      field(name("query_history", "error_message"), String.class);
  private static final Field<LocalDateTime> QH_EXECUTED_AT =
      field(name("query_history", "executed_at"), LocalDateTime.class);

  public QueryHistoryRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public void save(
      Long datasetId,
      Long userId,
      String sql,
      String queryType,
      int affectedRows,
      long executionTimeMs,
      boolean success,
      String error) {
    dsl.insertInto(QUERY_HISTORY)
        .set(QH_DATASET_ID, datasetId)
        .set(QH_USER_ID, userId)
        .set(QH_SQL_TEXT, sql)
        .set(QH_QUERY_TYPE, queryType)
        .set(QH_AFFECTED_ROWS, affectedRows)
        .set(QH_EXECUTION_TIME_MS, executionTimeMs)
        .set(QH_SUCCESS, success)
        .set(QH_ERROR_MESSAGE, error)
        .execute();
  }

  public List<QueryHistoryResponse> findByDatasetId(Long datasetId, int page, int size) {
    return dsl.select(
            QH_ID,
            QH_SQL_TEXT,
            QH_QUERY_TYPE,
            QH_AFFECTED_ROWS,
            QH_EXECUTION_TIME_MS,
            QH_SUCCESS,
            QH_ERROR_MESSAGE,
            QH_EXECUTED_AT)
        .from(QUERY_HISTORY)
        .where(QH_DATASET_ID.eq(datasetId))
        .orderBy(QH_EXECUTED_AT.desc())
        .limit(size)
        .offset(page * size)
        .fetch(this::mapToResponse);
  }

  public long countByDatasetId(Long datasetId) {
    Long count =
        dsl.select(count())
            .from(QUERY_HISTORY)
            .where(QH_DATASET_ID.eq(datasetId))
            .fetchOne(0, Long.class);
    return count != null ? count : 0L;
  }

  private QueryHistoryResponse mapToResponse(Record r) {
    return new QueryHistoryResponse(
        r.get(QH_ID),
        r.get(QH_SQL_TEXT),
        r.get(QH_QUERY_TYPE),
        r.get(QH_AFFECTED_ROWS) != null ? r.get(QH_AFFECTED_ROWS) : 0,
        r.get(QH_EXECUTION_TIME_MS) != null ? r.get(QH_EXECUTION_TIME_MS) : 0L,
        Boolean.TRUE.equals(r.get(QH_SUCCESS)),
        r.get(QH_ERROR_MESSAGE),
        r.get(QH_EXECUTED_AT));
  }
}
