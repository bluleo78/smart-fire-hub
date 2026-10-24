package com.smartfirehub.proactive.repository;

import static org.jooq.impl.DSL.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.proactive.dto.ProactiveMessageResponse;
import com.smartfirehub.proactive.util.ProactiveTime;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Table;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 프로액티브 메시지(사용자 알림함) 저장소.
 *
 * <p>클래스 레벨 {@code @Transactional} 이 필요한 이유: V103 으로 {@code tenant_id} 가 생겼고 V104 에서 RLS 가 걸린다. 테넌트
 * 값은 트랜잭션-로컬 GUC 이므로, 트랜잭션 없이 도는 발송 경로({@code @Scheduled} 디스패치 워커, {@code @Async} 잡 실행기)에서는 INSERT
 * 가 NOT NULL 위반으로 깨진다. 전파 REQUIRED 이므로 컨트롤러 경로의 동작은 불변이다.
 */
@Transactional
@Repository
@RequiredArgsConstructor
public class ProactiveMessageRepository {

  private final DSLContext dsl;
  private final ObjectMapper objectMapper;

  /** 수신자의 현재 보안 등급(WD-198) — 리포트 등급이 그보다 높은 메시지는 요약을 비워 돌려준다. */
  private final ClearanceResolver clearanceResolver;

  private static final Table<?> PROACTIVE_MESSAGE = table(name("proactive_message"));
  private static final Field<Long> PM_ID = field(name("proactive_message", "id"), Long.class);
  private static final Field<Long> PM_USER_ID =
      field(name("proactive_message", "user_id"), Long.class);
  private static final Field<Long> PM_EXECUTION_ID =
      field(name("proactive_message", "execution_id"), Long.class);
  private static final Field<String> PM_TITLE =
      field(name("proactive_message", "title"), String.class);
  private static final Field<JSONB> PM_CONTENT =
      field(name("proactive_message", "content"), JSONB.class);
  private static final Field<String> PM_MESSAGE_TYPE =
      field(name("proactive_message", "message_type"), String.class);
  private static final Field<Boolean> PM_READ =
      field(name("proactive_message", "read"), Boolean.class);
  private static final Field<LocalDateTime> PM_READ_AT =
      field(name("proactive_message", "read_at"), LocalDateTime.class);
  private static final Field<LocalDateTime> PM_CREATED_AT =
      field(name("proactive_message", "created_at"), LocalDateTime.class);

  private static final Table<?> PROACTIVE_JOB_EXECUTION = table(name("proactive_job_execution"));
  private static final Field<Long> PJE_ID =
      field(name("proactive_job_execution", "id"), Long.class);
  private static final Field<Long> PJE_JOB_ID =
      field(name("proactive_job_execution", "job_id"), Long.class);

  /** 연결된 실행(리포트)의 등급 순위(WD-198). 실행·등급이 없으면 NULL — 지금처럼 요약을 보인다. 순위는 조회 시점 값이라 등급 순서 변경도 반영된다. */
  private static final Field<Integer> REPORT_LEVEL_RANK =
      field(
              select(field(name("pm_sl", "rank"), Integer.class))
                  .from(table(name("security_level")).as("pm_sl"))
                  .where(
                      field(name("pm_sl", "id"), Long.class)
                          .eq(
                              field(
                                  name("proactive_job_execution", "security_level_id"),
                                  Long.class))))
          .as("report_level_rank");

  private static final Table<?> PROACTIVE_JOB = table(name("proactive_job"));
  private static final Field<Long> PJ_ID = field(name("proactive_job", "id"), Long.class);
  private static final Field<String> PJ_NAME = field(name("proactive_job", "name"), String.class);
  private static final Field<Long> PJ_USER_ID = field(name("proactive_job", "user_id"), Long.class);

  /**
   * 이 메시지를 받은 사람이 리포트 전문(실행 상세·html)을 열 수 있는가 (WD-162).
   *
   * <p>서버는 작업 작성자만 실행 상세·리포트를 열게 한다({@code ProactiveJobRepository.findById(id, userId)} 의 {@code
   * proactive_job.user_id = 요청자} 조건). 받은 사람에게 「리포트 보기」를 눌러 404 를 맞게 하지 않도록, 같은 규칙(메시지 수신자 = 작업 주인)을
   * 조회 시점에 계산해 응답에 싣는다. 실행·작업이 없는(LEFT JOIN 결과 NULL) 메시지는 열 리포트가 없으므로 false 다.
   */
  private static final Field<Boolean> CAN_VIEW_REPORT =
      when(PJ_USER_ID.isNotNull().and(PJ_USER_ID.eq(PM_USER_ID)), inline(true))
          .otherwise(inline(false))
          .as("can_view_report");

  public Long create(
      Long userId,
      Long executionId,
      String title,
      Map<String, Object> content,
      String messageType) {
    try {
      String contentJson = content != null ? objectMapper.writeValueAsString(content) : "{}";
      return dsl.insertInto(PROACTIVE_MESSAGE)
          .set(PM_USER_ID, userId)
          .set(PM_EXECUTION_ID, executionId)
          .set(PM_TITLE, title)
          .set(PM_CONTENT, JSONB.valueOf(contentJson))
          .set(PM_MESSAGE_TYPE, messageType != null ? messageType : "REPORT")
          .set(PM_READ, false)
          .returning(PM_ID)
          .fetchOne(r -> r.get(PM_ID));
    } catch (Exception e) {
      throw new RuntimeException("Failed to serialize content", e);
    }
  }

  public List<ProactiveMessageResponse> findUnreadByUserId(Long userId) {
    return toResponses(
        dsl.select(
                PM_ID,
                PM_USER_ID,
                PJE_JOB_ID,
                PM_EXECUTION_ID,
                PM_TITLE,
                PM_CONTENT,
                PM_MESSAGE_TYPE,
                PM_READ,
                PM_READ_AT,
                PJ_NAME,
                PM_CREATED_AT,
                CAN_VIEW_REPORT,
                REPORT_LEVEL_RANK)
            .from(PROACTIVE_MESSAGE)
            .leftJoin(PROACTIVE_JOB_EXECUTION)
            .on(PM_EXECUTION_ID.eq(PJE_ID))
            .leftJoin(PROACTIVE_JOB)
            .on(PJE_JOB_ID.eq(PJ_ID))
            .where(PM_USER_ID.eq(userId).and(PM_READ.isFalse()))
            .orderBy(PM_ID.desc())
            .fetch(),
        userId);
  }

  public int countUnreadByUserId(Long userId) {
    return dsl.fetchCount(
        dsl.selectOne()
            .from(PROACTIVE_MESSAGE)
            .where(PM_USER_ID.eq(userId).and(PM_READ.isFalse())));
  }

  public void markAsRead(Long id, Long userId) {
    dsl.update(PROACTIVE_MESSAGE)
        .set(PM_READ, true)
        .set(PM_READ_AT, ProactiveTime.nowUtc())
        .where(PM_ID.eq(id).and(PM_USER_ID.eq(userId)))
        .execute();
  }

  public void markAllAsRead(Long userId) {
    dsl.update(PROACTIVE_MESSAGE)
        .set(PM_READ, true)
        .set(PM_READ_AT, ProactiveTime.nowUtc())
        .where(PM_USER_ID.eq(userId).and(PM_READ.isFalse()))
        .execute();
  }

  /**
   * 알림 목록 조회 (#520).
   *
   * <p>{@code unreadOnly=true} 이면 안 읽은 알림만 조회한다 — 안 읽은 알림이 많이 쌓였을 때 "더 보기"로 전체를 스크롤하지 않고도 안 읽은 것만
   * 골라볼 수 있도록 서버 사이드 필터링을 지원한다. 기존 페이지네이션(limit/offset)과 함께 적용된다.
   */
  public List<ProactiveMessageResponse> findByUserId(
      Long userId, int limit, int offset, boolean unreadOnly) {
    var condition = PM_USER_ID.eq(userId);
    if (unreadOnly) {
      condition = condition.and(PM_READ.isFalse());
    }
    return toResponses(
        dsl.select(
                PM_ID,
                PM_USER_ID,
                PJE_JOB_ID,
                PM_EXECUTION_ID,
                PM_TITLE,
                PM_CONTENT,
                PM_MESSAGE_TYPE,
                PM_READ,
                PM_READ_AT,
                PJ_NAME,
                PM_CREATED_AT,
                CAN_VIEW_REPORT,
                REPORT_LEVEL_RANK)
            .from(PROACTIVE_MESSAGE)
            .leftJoin(PROACTIVE_JOB_EXECUTION)
            .on(PM_EXECUTION_ID.eq(PJE_ID))
            .leftJoin(PROACTIVE_JOB)
            .on(PJE_JOB_ID.eq(PJ_ID))
            .where(condition)
            .orderBy(PM_ID.desc())
            .limit(limit)
            .offset(offset)
            .fetch(),
        userId);
  }

  /**
   * 행 → 응답 + 리포트 등급 가림(WD-198). 알림 메시지의 요약은 발송 때 리포트에서 복사해 둔 것이라, 화면이 다시 읽을 때마다 그대로 나간다. 그래서 연결된
   * 리포트의 등급이 <b>수신자</b>의 현재 최대 등급보다 높으면 요약을 비우고 리포트 열기도 막는다(수신자가 작성자든 아니든 같은 기준 — 메시지를 보는 사람이
   * 수신자다). 리포트 등급이 없는 메시지는 지금처럼 보인다. 자격 계산은 등급 있는 행이 하나라도 있을 때만 한 번 한다.
   */
  private List<ProactiveMessageResponse> toResponses(
      List<? extends org.jooq.Record> rows, Long userId) {
    Integer recipientRank = null;
    if (rows.stream().anyMatch(r -> r.get(REPORT_LEVEL_RANK) != null)) {
      recipientRank = clearanceResolver.resolve(userId).rank();
    }
    List<ProactiveMessageResponse> out = new java.util.ArrayList<>(rows.size());
    for (org.jooq.Record r : rows) {
      Integer reportRank = r.get(REPORT_LEVEL_RANK);
      boolean blocked = reportRank != null && recipientRank < reportRank;
      out.add(toResponse(r, blocked));
    }
    return out;
  }

  private ProactiveMessageResponse toResponse(org.jooq.Record r, boolean levelBlocked) {
    try {
      JSONB contentJsonb = r.get(PM_CONTENT);
      Map<String, Object> content =
          contentJsonb != null
              ? objectMapper.readValue(contentJsonb.data(), new TypeReference<>() {})
              : Map.of();
      if (levelBlocked) {
        // 요약(리포트 본문 일부)만 지운다 — 제목·작업 이름·시각은 남겨 「등급 때문에 볼 수 없는 알림」임을 알 수 있게 한다.
        Map<String, Object> masked = new HashMap<>(content);
        masked.remove("summary");
        masked.remove("sections");
        masked.remove("rawText");
        content = masked;
      }
      return new ProactiveMessageResponse(
          r.get(PM_ID),
          r.get(PM_USER_ID),
          r.get(PJE_JOB_ID), // execution JOIN을 통해 상위 잡 ID 포함
          r.get(PM_EXECUTION_ID),
          r.get(PM_TITLE),
          content,
          r.get(PM_MESSAGE_TYPE),
          r.get(PM_READ),
          r.get(PM_READ_AT),
          r.get(PJ_NAME),
          r.get(PM_CREATED_AT),
          // WD-162: 수신자 = 작업 주인일 때만 true — 프런트가 「리포트 보기」 대신 요약·안내를 보일지 고르는 기준
          Boolean.TRUE.equals(r.get(CAN_VIEW_REPORT)) && !levelBlocked,
          levelBlocked);
    } catch (Exception e) {
      throw new RuntimeException("Failed to deserialize content", e);
    }
  }
}
