package com.smartfirehub.ai.repository;

import com.smartfirehub.ai.dto.AiSessionResponse;
import com.smartfirehub.ai.dto.CreateAiSessionRequest;
import java.util.List;
import java.util.Optional;

/**
 * AI 세션 레포지토리. jOOQ DSLContext 기반 type-safe SQL로 ai_session 테이블을 관리한다. WEB(기본) 및 SLACK 채널 소스를 지원한다.
 *
 * <p>구현은 {@code JooqAiSessionRepository}(jOOQ/PostgreSQL) 이다 — WD-485. 다루는 테이블: {@code ai_session}.
 */
public interface AiSessionRepository {

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
  List<AiSessionResponse> findByUserId(Long userId, int page, int size);

  Optional<AiSessionResponse> findById(Long id);

  Optional<AiSessionResponse> findByUserIdAndContext(
      Long userId, String contextType, Long contextResourceId);

  Optional<AiSessionResponse> findByUserIdAndSessionId(Long userId, String sessionId);

  /** 이 세션이 다른 사용자에게 기록돼 있는지. RLS 로 현재 테넌트 안에서만 본다. */
  boolean existsForOtherUser(Long userId, String sessionId);

  AiSessionResponse create(Long userId, CreateAiSessionRequest request);

  void updateTitle(Long id, String title);

  void delete(Long id);

  /**
   * SLACK 스레드 기준 세션 조회. (slack_team_id, slack_channel_id, slack_thread_ts) 3-tuple로 고유 식별. 동일 스레드의
   * 후속 메시지가 기존 세션을 재사용할 수 있도록 한다.
   *
   * @param teamId Slack 워크스페이스 team_id
   * @param channelId Slack 채널 ID
   * @param threadTs Slack 스레드 타임스탬프 (없으면 최초 메시지 ts)
   * @return 해당 스레드에 연결된 세션, 없으면 Optional.empty()
   */
  Optional<AiSessionResponse> findBySlackContext(String teamId, String channelId, String threadTs);

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
  long createSlackSession(
      long userId,
      String aiAgentSessionId,
      String teamId,
      String channelId,
      String threadTs,
      String title);
}
