package com.smartfirehub.analytics.repository;

import static com.smartfirehub.jooq.Tables.ANALYTICS_QUERY_RUN;
import static org.jooq.impl.DSL.field;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 애드혹 분석 쿼리 실행 기록(V136 analytics_query_run) — 쿼리 결과 내보내기의 서버 재실행 근거(스펙 §4.4).
 *
 * <p>RLS 테이블이므로 클래스 레벨 {@code @Transactional} 을 둔다 — 컨트롤러가 트랜잭션 없이 부르므로, 이 경계가 있어야
 * TenantAwareTransactionManager 가 GUC(app.tenant_id)를 심어 tenant_id DEFAULT 와 정책이 동작한다. 배경 정리({@link
 * #deleteExpired})도 이 경계 덕분에 TenantScopedRunner 가 세운 테넌트의 GUC 로 돈다.
 */
@Repository
@RequiredArgsConstructor
@Transactional
public class AnalyticsQueryRunRepository {

  /**
   * 보존 기간(1시간) — 이보다 오래된 기록은 내보내기에 쓰지 않고, 주기 정리(AnalyticsQueryRunCleanupService)가 지운다. DB
   * 시계(now())로 비교한다 — created_at 이 DB DEFAULT now() 라서 앱 시계와 섞으면 두 시계 차이만큼 경계가 어긋난다(WD-48 과 같은 함정).
   */
  private static final Field<OffsetDateTime> RETENTION_CUTOFF =
      field("now() - interval '1 hour'", OffsetDateTime.class);

  private final DSLContext dsl;

  /** 실행 기록 — 재판정·재실행에 필요한 SQL 원문과 행 상한. */
  public record Run(UUID id, String sqlText, int maxRows) {}

  /**
   * 기록을 남기고 id 를 돌려준다. 만료 행 정리는 삽입 경로가 아니라 주기 정리({@link #deleteExpired})가 맡는다 — 삽입 때 사용자별로 지우면 다시
   * 실행하지 않는 사용자의 SQL 원문이 무기한 남았다(code-review 7).
   */
  public UUID insert(long userId, String sqlText, int maxRows) {
    return dsl.insertInto(ANALYTICS_QUERY_RUN)
        .set(ANALYTICS_QUERY_RUN.USER_ID, userId)
        .set(ANALYTICS_QUERY_RUN.SQL_TEXT, sqlText)
        .set(ANALYTICS_QUERY_RUN.MAX_ROWS, maxRows)
        .returning(ANALYTICS_QUERY_RUN.ID)
        .fetchOne()
        .get(ANALYTICS_QUERY_RUN.ID);
  }

  /**
   * 현재 테넌트(GUC)의 보존 기간 지난 기록을 모두 지우고 지운 행 수를 돌려준다. 주기 정리가 테넌트마다 부른다 — RLS 가 현재 테넌트 행만 보이게 하므로 테넌트
   * 컨텍스트 없이 부르면 0행이다. created_at 단독 범위라 idx_analytics_query_run_created 를 탄다.
   */
  public int deleteExpired() {
    return dsl.deleteFrom(ANALYTICS_QUERY_RUN)
        .where(ANALYTICS_QUERY_RUN.CREATED_AT.lt(RETENTION_CUTOFF))
        .execute();
  }

  /**
   * 소유자 본인의 보존 기간 안 기록. 다른 사용자·만료·없음은 모두 empty — 호출자가 같은 404 로 응답해 남의 실행 기록 존재를 드러내지 않는다(Review
   * Focus 3).
   */
  @Transactional(readOnly = true)
  public Optional<Run> findOwned(UUID id, long userId) {
    return dsl.select(
            ANALYTICS_QUERY_RUN.ID, ANALYTICS_QUERY_RUN.SQL_TEXT, ANALYTICS_QUERY_RUN.MAX_ROWS)
        .from(ANALYTICS_QUERY_RUN)
        .where(ANALYTICS_QUERY_RUN.ID.eq(id))
        .and(ANALYTICS_QUERY_RUN.USER_ID.eq(userId))
        .and(ANALYTICS_QUERY_RUN.CREATED_AT.ge(RETENTION_CUTOFF))
        .fetchOptional(
            r ->
                new Run(
                    r.get(ANALYTICS_QUERY_RUN.ID),
                    r.get(ANALYTICS_QUERY_RUN.SQL_TEXT),
                    r.get(ANALYTICS_QUERY_RUN.MAX_ROWS)));
  }
}
