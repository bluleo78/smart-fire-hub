package com.smartfirehub.ai.repository;

import static org.jooq.impl.DSL.*;

import com.smartfirehub.ai.dto.AiSessionResponse;
import com.smartfirehub.ai.dto.CreateAiSessionRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

/**
 * AI 세션 레포지토리. jOOQ DSLContext 기반 type-safe SQL로 ai_session 테이블을 관리한다. WEB(기본) 및 SLACK 채널 소스를 지원한다.
 */
@Repository
@RequiredArgsConstructor
public class AiSessionRepository {

  private final DSLContext dsl;

  private static final Table<?> AI_SESSION = table(name("ai_session"));
  private static final Field<Long> ID = field(name("ai_session", "id"), Long.class);
  private static final Field<Long> USER_ID = field(name("ai_session", "user_id"), Long.class);
  private static final Field<String> SESSION_ID =
      field(name("ai_session", "session_id"), String.class);
  private static final Field<String> CONTEXT_TYPE =
      field(name("ai_session", "context_type"), String.class);
  private static final Field<Long> CONTEXT_RESOURCE_ID =
      field(name("ai_session", "context_resource_id"), Long.class);
  private static final Field<String> TITLE = field(name("ai_session", "title"), String.class);
  private static final Field<LocalDateTime> CREATED_AT =
      field(name("ai_session", "created_at"), LocalDateTime.class);
  private static final Field<LocalDateTime> UPDATED_AT =
      field(name("ai_session", "updated_at"), LocalDateTime.class);

  // V55에서 추가된 Slack 컨텍스트 필드
  private static final Field<String> CHANNEL_SOURCE =
      field(name("ai_session", "channel_source"), String.class);
  private static final Field<String> SLACK_TEAM_ID =
      field(name("ai_session", "slack_team_id"), String.class);
  private static final Field<String> SLACK_CHANNEL_ID =
      field(name("ai_session", "slack_channel_id"), String.class);
  private static final Field<String> SLACK_THREAD_TS =
      field(name("ai_session", "slack_thread_ts"), String.class);

  public List<AiSessionResponse> findByUserId(Long userId) {
    return dsl.select(
            ID,
            USER_ID,
            SESSION_ID,
            CONTEXT_TYPE,
            CONTEXT_RESOURCE_ID,
            TITLE,
            CREATED_AT,
            UPDATED_AT)
        .from(AI_SESSION)
        .where(USER_ID.eq(userId))
        .orderBy(UPDATED_AT.desc())
        .fetch(
            r ->
                AiSessionResponse.ofWeb(
                    r.get(ID),
                    r.get(USER_ID),
                    r.get(SESSION_ID),
                    r.get(CONTEXT_TYPE),
                    r.get(CONTEXT_RESOURCE_ID),
                    r.get(TITLE),
                    r.get(CREATED_AT),
                    r.get(UPDATED_AT)));
  }

  public Optional<AiSessionResponse> findById(Long id) {
    return dsl.select(
            ID,
            USER_ID,
            SESSION_ID,
            CONTEXT_TYPE,
            CONTEXT_RESOURCE_ID,
            TITLE,
            CREATED_AT,
            UPDATED_AT)
        .from(AI_SESSION)
        .where(ID.eq(id))
        .fetchOptional(
            r ->
                AiSessionResponse.ofWeb(
                    r.get(ID),
                    r.get(USER_ID),
                    r.get(SESSION_ID),
                    r.get(CONTEXT_TYPE),
                    r.get(CONTEXT_RESOURCE_ID),
                    r.get(TITLE),
                    r.get(CREATED_AT),
                    r.get(UPDATED_AT)));
  }

  public Optional<AiSessionResponse> findByUserIdAndContext(
      Long userId, String contextType, Long contextResourceId) {
    return dsl.select(
            ID,
            USER_ID,
            SESSION_ID,
            CONTEXT_TYPE,
            CONTEXT_RESOURCE_ID,
            TITLE,
            CREATED_AT,
            UPDATED_AT)
        .from(AI_SESSION)
        .where(
            USER_ID
                .eq(userId)
                .and(CONTEXT_TYPE.eq(contextType))
                .and(CONTEXT_RESOURCE_ID.eq(contextResourceId)))
        .fetchOptional(
            r ->
                AiSessionResponse.ofWeb(
                    r.get(ID),
                    r.get(USER_ID),
                    r.get(SESSION_ID),
                    r.get(CONTEXT_TYPE),
                    r.get(CONTEXT_RESOURCE_ID),
                    r.get(TITLE),
                    r.get(CREATED_AT),
                    r.get(UPDATED_AT)));
  }

  public Optional<AiSessionResponse> findByUserIdAndSessionId(Long userId, String sessionId) {
    return dsl.select(
            ID,
            USER_ID,
            SESSION_ID,
            CONTEXT_TYPE,
            CONTEXT_RESOURCE_ID,
            TITLE,
            CREATED_AT,
            UPDATED_AT)
        .from(AI_SESSION)
        .where(USER_ID.eq(userId).and(SESSION_ID.eq(sessionId)))
        .fetchOptional(
            r ->
                AiSessionResponse.ofWeb(
                    r.get(ID),
                    r.get(USER_ID),
                    r.get(SESSION_ID),
                    r.get(CONTEXT_TYPE),
                    r.get(CONTEXT_RESOURCE_ID),
                    r.get(TITLE),
                    r.get(CREATED_AT),
                    r.get(UPDATED_AT)));
  }

  public AiSessionResponse create(Long userId, CreateAiSessionRequest request) {
    Long id =
        dsl.insertInto(AI_SESSION)
            .set(USER_ID, userId)
            .set(SESSION_ID, request.sessionId())
            .set(CONTEXT_TYPE, request.contextType())
            .set(CONTEXT_RESOURCE_ID, request.contextResourceId())
            .set(TITLE, request.title())
            .set(CREATED_AT, currentLocalDateTime())
            .set(UPDATED_AT, currentLocalDateTime())
            .returningResult(ID)
            .fetchOne()
            .get(ID);

    return findById(id).orElseThrow();
  }

  public void updateTitle(Long id, String title) {
    dsl.update(AI_SESSION)
        .set(TITLE, title)
        .set(UPDATED_AT, currentLocalDateTime())
        .where(ID.eq(id))
        .execute();
  }

  public void delete(Long id) {
    dsl.deleteFrom(AI_SESSION).where(ID.eq(id)).execute();
  }

  // -----------------------------------------------------------------------
  // Slack 인바운드 전용 메서드 (V55)
  // -----------------------------------------------------------------------

  /**
   * SLACK 스레드 기준 세션 조회. (slack_team_id, slack_channel_id, slack_thread_ts) 3-tuple로 고유 식별. 동일 스레드의
   * 후속 메시지가 기존 세션을 재사용할 수 있도록 한다.
   *
   * @param teamId Slack 워크스페이스 team_id
   * @param channelId Slack 채널 ID
   * @param threadTs Slack 스레드 타임스탬프 (없으면 최초 메시지 ts)
   * @return 해당 스레드에 연결된 세션, 없으면 Optional.empty()
   */
  public Optional<AiSessionResponse> findBySlackContext(
      String teamId, String channelId, String threadTs) {
    return dsl.select(
            ID,
            USER_ID,
            SESSION_ID,
            CONTEXT_TYPE,
            CONTEXT_RESOURCE_ID,
            TITLE,
            CREATED_AT,
            UPDATED_AT,
            CHANNEL_SOURCE,
            SLACK_TEAM_ID,
            SLACK_CHANNEL_ID,
            SLACK_THREAD_TS)
        .from(AI_SESSION)
        .where(
            CHANNEL_SOURCE
                .eq("SLACK")
                .and(SLACK_TEAM_ID.eq(teamId))
                .and(SLACK_CHANNEL_ID.eq(channelId))
                .and(SLACK_THREAD_TS.eq(threadTs)))
        .fetchOptional(
            r ->
                new AiSessionResponse(
                    r.get(ID),
                    r.get(USER_ID),
                    r.get(SESSION_ID),
                    r.get(CONTEXT_TYPE),
                    r.get(CONTEXT_RESOURCE_ID),
                    r.get(TITLE),
                    r.get(CREATED_AT),
                    r.get(UPDATED_AT),
                    r.get(CHANNEL_SOURCE),
                    r.get(SLACK_TEAM_ID),
                    r.get(SLACK_CHANNEL_ID),
                    r.get(SLACK_THREAD_TS)));
  }

  /**
   * 새 SLACK 세션을 INSERT하고 생성된 row의 id를 반환한다. session_id에는 ai-agent 서비스가 발급한 외부 세션 ID를 저장한다.
   * uk_ai_session_slack_thread UNIQUE 제약 위반 시 DataIntegrityViolationException 발생.
   *
   * @param userId Smart Fire Hub 사용자 ID
   * @param aiAgentSessionId ai-agent 서비스 세션 ID (session_id 컬럼)
   * @param teamId Slack 워크스페이스 team_id
   * @param channelId Slack 채널 ID
   * @param threadTs Slack 스레드 타임스탬프
   * @param title 세션 제목 (예: "Slack 대화")
   * @return 생성된 ai_session.id
   */
  public long createSlackSession(
      long userId,
      String aiAgentSessionId,
      String teamId,
      String channelId,
      String threadTs,
      String title) {
    return dsl.insertInto(AI_SESSION)
        .set(USER_ID, userId)
        .set(SESSION_ID, aiAgentSessionId)
        .set(TITLE, title)
        .set(CHANNEL_SOURCE, "SLACK")
        .set(SLACK_TEAM_ID, teamId)
        .set(SLACK_CHANNEL_ID, channelId)
        .set(SLACK_THREAD_TS, threadTs)
        .set(CREATED_AT, currentLocalDateTime())
        .set(UPDATED_AT, currentLocalDateTime())
        .returningResult(ID)
        .fetchOne()
        .get(ID);
  }
}
