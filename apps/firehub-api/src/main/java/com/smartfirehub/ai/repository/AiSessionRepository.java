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
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 세션 레포지토리. jOOQ DSLContext 기반 type-safe SQL로 ai_session 테이블을 관리한다. WEB(기본) 및 SLACK 채널 소스를 지원한다.
 *
 * <p>클래스 레벨 {@code @Transactional} 이 필요한 이유: V103 으로 {@code tenant_id} 가 생겼고
 * V104 에서 RLS 가 걸린다. 테넌트 값은 트랜잭션-로컬 GUC 이므로, 트랜잭션 없이 도는 경로에서는
 * 세션 INSERT 가 NOT NULL 위반으로 깨지고 조회는 조용히 0행이 된다. 전파 REQUIRED 이므로
 * 컨트롤러 경로의 동작은 불변이다.
 *
 * <p><b>Slack inbound 는 이것으로 고쳐지지 않는다 — 오해하지 마라.</b> 이 어노테이션은 트랜잭션이
 * 없다는 문제만 푼다. {@code SlackInboundService} 의 {@code @Async} 스레드에는 애초에
 * {@link com.smartfirehub.global.tenant.TenantContext} 가 없고(원 요청이 permitAll Slack 웹훅이라
 * 승계할 테넌트가 없다), {@code TenantAwareTransactionManager.doBegin} 은 컨텍스트가 null 이면
 * GUC 를 <b>아예 세팅하지 않는다</b>. 즉 트랜잭션은 열리지만 여전히 조회 0행 / INSERT NOT NULL
 * 위반이다. 7테이블 중 배경 쓰기 경로에 트랜잭션만 있고 <b>테넌트 해석이 없는 유일한 테이블</b>이
 * {@code ai_session} 이다.
 *
 * <p>지금 무해한 이유는 오직 하나다: {@code @Async("slackInboundExecutor")} 가 가리키는 빈이
 * 존재하지 않아(커밋 {@code 968a28c2} 에서 삭제) <b>Slack inbound 경로 자체가 죽어 있다</b>.
 * P2-f 가 그 빈을 복원하는 순간 이것은 Critical 이 된다 — 복원과 <b>같은 커밋</b>에서 permitAll
 * 웹훅의 테넌트 해석(팀 ID → 테넌트, V95 의 {@code SECURITY DEFINER} 패턴)을 함께 넣어야 한다.
 */
@Transactional
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

  /**
   * 사용자 ID로 AI 세션 목록을 페이지네이션 조회한다.
   *
   * <p>page·size 파라미터로 LIMIT/OFFSET 슬라이싱을 적용하여 전체 세션이 아닌 요청된 범위만 반환한다. 기존에는 전체 목록을 반환하여 세션 수가 많을 때
   * 성능 저하가 발생했다.
   *
   * @param userId 조회할 사용자 ID
   * @param page 0-based 페이지 번호
   * @param size 페이지당 항목 수
   * @return 페이지네이션된 세션 응답 목록 (updatedAt 내림차순 정렬)
   */
  public List<AiSessionResponse> findByUserId(Long userId, int page, int size) {
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
        .limit(size)
        .offset((long) page * size)
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
