package com.smartfirehub.analytics.repository;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 애드혹 분석 쿼리 실행 기록(V136 analytics_query_run) — 쿼리 결과 내보내기의 서버 재실행 근거(스펙 §4.4).
 *
 * <p>RLS 테이블이므로 클래스 레벨 {@code @Transactional} 을 둔다 — 컨트롤러가 트랜잭션 없이 부르므로, 이 경계가 있어야
 * TenantAwareTransactionManager 가 GUC(app.tenant_id)를 심어 tenant_id DEFAULT 와 정책이 동작한다.
 *
 * <p>새 테이블은 jOOQ 코드젠 대신 plain-SQL {@code table(name(..))} 로 참조한다.
 */
@Repository
@RequiredArgsConstructor
@Transactional
public class AnalyticsQueryRunRepository {

  /**
   * 보존 기간(1시간) — 이보다 오래된 기록은 내보내기에 쓰지 않고, 같은 사용자의 다음 삽입 때 지운다. DB 시계(now())로 비교한다 — created_at 이 DB
   * DEFAULT now() 라서 앱 시계와 섞으면 두 시계 차이만큼 경계가 어긋난다(WD-48 과 같은 함정).
   */
  private static final Field<OffsetDateTime> RETENTION_CUTOFF =
      field("now() - interval '1 hour'", OffsetDateTime.class);

  private static final Table<?> RUN = table(name("analytics_query_run"));
  private static final Field<UUID> ID = field(name("analytics_query_run", "id"), UUID.class);
  private static final Field<Long> USER_ID =
      field(name("analytics_query_run", "user_id"), Long.class);
  private static final Field<String> SQL_TEXT =
      field(name("analytics_query_run", "sql_text"), String.class);
  private static final Field<Integer> MAX_ROWS =
      field(name("analytics_query_run", "max_rows"), Integer.class);
  private static final Field<OffsetDateTime> CREATED_AT =
      field(name("analytics_query_run", "created_at"), OffsetDateTime.class);

  private final DSLContext dsl;

  /** 실행 기록 — 재판정·재실행에 필요한 SQL 원문과 행 상한. */
  public record Run(UUID id, String sqlText, int maxRows) {}

  /**
   * 기록을 남기고 id 를 돌려준다. 같은 사용자의 보존 기간 지난 기록을 함께 지운다 — ai-agent 도 같은 실행 엔드포인트를 써서 행이 계속 쌓이므로 별도 스케줄러
   * 없이 삽입 시점에 정리한다.
   */
  public UUID insert(long userId, String sqlText, int maxRows) {
    dsl.deleteFrom(RUN).where(USER_ID.eq(userId)).and(CREATED_AT.lt(RETENTION_CUTOFF)).execute();
    return dsl.insertInto(RUN)
        .set(USER_ID, userId)
        .set(SQL_TEXT, sqlText)
        .set(MAX_ROWS, maxRows)
        .returning(ID)
        .fetchOne()
        .get(ID);
  }

  /**
   * 소유자 본인의 보존 기간 안 기록. 다른 사용자·만료·없음은 모두 empty — 호출자가 같은 404 로 응답해 남의 실행 기록 존재를 드러내지 않는다(Review
   * Focus 3).
   */
  @Transactional(readOnly = true)
  public Optional<Run> findOwned(UUID id, long userId) {
    return dsl.select(ID, SQL_TEXT, MAX_ROWS)
        .from(RUN)
        .where(ID.eq(id))
        .and(USER_ID.eq(userId))
        .and(CREATED_AT.ge(RETENTION_CUTOFF))
        .fetchOptional(r -> new Run(r.get(ID), r.get(SQL_TEXT), r.get(MAX_ROWS)));
  }
}
