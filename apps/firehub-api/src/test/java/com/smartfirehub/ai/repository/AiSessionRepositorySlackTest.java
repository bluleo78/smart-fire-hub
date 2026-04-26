package com.smartfirehub.ai.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.*;

import com.smartfirehub.ai.dto.AiSessionResponse;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * AiSessionRepository Slack 컨텍스트 메서드 통합 테스트. V55 마이그레이션으로 추가된 channel_source / slack_* 컬럼과
 * uk_ai_session_slack_thread UNIQUE INDEX 동작을 검증한다.
 */
class AiSessionRepositorySlackTest extends IntegrationTestBase {

  @Autowired private AiSessionRepository aiSessionRepository;

  @Autowired private DSLContext dsl;

  private Long testUserId;

  // 테스트용 테이블/필드 참조
  private static final Table<?> USER_TABLE = table(name("user"));
  private static final Field<Long> U_ID = field(name("user", "id"), Long.class);
  private static final Field<String> U_USERNAME = field(name("user", "username"), String.class);
  private static final Field<String> U_PASSWORD = field(name("user", "password"), String.class);
  private static final Field<String> U_NAME = field(name("user", "name"), String.class);
  private static final Field<String> U_EMAIL = field(name("user", "email"), String.class);

  private static final Table<?> AI_SESSION_TABLE = table(name("ai_session"));
  private static final Field<Long> AS_USER_ID = field(name("ai_session", "user_id"), Long.class);

  // 테스트 픽스처 상수
  private static final String TEAM_ID = "T_TEST_TEAM";
  private static final String CHANNEL_ID = "C_TEST_CHANNEL";
  private static final String THREAD_TS = "1700000001.000100";

  @BeforeEach
  void setUp() {
    // 테스트 격리를 위해 유니크한 사용자 생성
    testUserId =
        dsl.insertInto(USER_TABLE)
            .set(U_USERNAME, "slack_repo_test_" + System.nanoTime())
            .set(U_PASSWORD, "password")
            .set(U_NAME, "Slack Repo Test User")
            .set(U_EMAIL, "slackrepo_" + System.nanoTime() + "@example.com")
            .returning(U_ID)
            .fetchOne(r -> r.get(U_ID));
  }

  @AfterEach
  void tearDown() {
    // FK 순서: ai_session 먼저 삭제 후 user 삭제
    dsl.deleteFrom(AI_SESSION_TABLE).where(AS_USER_ID.eq(testUserId)).execute();
    dsl.deleteFrom(USER_TABLE).where(U_ID.eq(testUserId)).execute();
  }

  /** createSlackSession 후 findBySlackContext로 동일 row를 복구할 수 있어야 한다. */
  @Test
  @DisplayName("createSlackSession → findBySlackContext 동일 row 복구")
  void createSlackSession_thenFindBySlackContext_returnsCreatedRow() {
    // given
    String agentSessionId = "agent-session-abc-123";

    // when
    long createdId =
        aiSessionRepository.createSlackSession(
            testUserId, agentSessionId, TEAM_ID, CHANNEL_ID, THREAD_TS, "Slack 대화 테스트");

    // then: findBySlackContext가 동일 세션 반환
    Optional<AiSessionResponse> found =
        aiSessionRepository.findBySlackContext(TEAM_ID, CHANNEL_ID, THREAD_TS);

    assertThat(found).isPresent();
    AiSessionResponse session = found.get();
    assertThat(session.id()).isEqualTo(createdId);
    assertThat(session.userId()).isEqualTo(testUserId);
    assertThat(session.sessionId()).isEqualTo(agentSessionId);
    assertThat(session.channelSource()).isEqualTo("SLACK");
    assertThat(session.slackTeamId()).isEqualTo(TEAM_ID);
    assertThat(session.slackChannelId()).isEqualTo(CHANNEL_ID);
    assertThat(session.slackThreadTs()).isEqualTo(THREAD_TS);
    assertThat(session.title()).isEqualTo("Slack 대화 테스트");
  }

  /**
   * 동일 (team_id, channel_id, thread_ts) 로 두 번 INSERT 하면 uk_ai_session_slack_thread UNIQUE 위반으로 예외가
   * 발생해야 한다.
   */
  @Test
  @DisplayName("중복 (team,channel,thread) INSERT → UNIQUE 제약 위반")
  void createSlackSession_duplicateThread_throwsConstraintViolation() {
    // given: 첫 번째 INSERT
    aiSessionRepository.createSlackSession(
        testUserId, "agent-session-first", TEAM_ID, CHANNEL_ID, THREAD_TS, "첫 번째 세션");

    // when/then: 동일 (team,channel,thread) 로 두 번째 INSERT → UNIQUE 위반
    // jOOQ는 IntegrityConstraintViolationException을 던진다 (DataAccessException의 하위 클래스).
    assertThatThrownBy(
            () ->
                aiSessionRepository.createSlackSession(
                    testUserId, "agent-session-second", TEAM_ID, CHANNEL_ID, THREAD_TS, "두 번째 세션"))
        .isInstanceOf(org.jooq.exception.IntegrityConstraintViolationException.class);
  }

  /**
   * channel_source='WEB'인 세션은 findBySlackContext 조회 결과에 포함되지 않아야 한다. 같은 user_id를 가져도 Slack 전용 메서드는
   * WEB 세션을 반환하면 안 된다.
   */
  @Test
  @DisplayName("WEB 세션은 findBySlackContext 조회 미포함")
  void findBySlackContext_webSession_notReturned() {
    // given: WEB 세션 생성 (create 메서드 — channel_source 기본값 'WEB')
    com.smartfirehub.ai.dto.CreateAiSessionRequest webRequest =
        new com.smartfirehub.ai.dto.CreateAiSessionRequest("web-session-xyz", null, null, "웹 세션");
    aiSessionRepository.create(testUserId, webRequest);

    // when: Slack 컨텍스트로 조회
    Optional<AiSessionResponse> found =
        aiSessionRepository.findBySlackContext(TEAM_ID, CHANNEL_ID, THREAD_TS);

    // then: 결과 없음
    assertThat(found).isEmpty();
  }

  /**
   * 서로 다른 thread_ts 는 별개의 세션이어야 한다. 같은 (team, channel)이더라도 thread_ts가 다르면 findBySlackContext가 각각을
   * 반환한다.
   */
  @Test
  @DisplayName("서로 다른 thread_ts → 독립적인 별개 세션")
  void createSlackSession_differentThreadTs_independentSessions() {
    // given
    String threadTs2 = "1700000002.000200";
    long id1 =
        aiSessionRepository.createSlackSession(
            testUserId, "agent-session-t1", TEAM_ID, CHANNEL_ID, THREAD_TS, "스레드1 세션");
    long id2 =
        aiSessionRepository.createSlackSession(
            testUserId, "agent-session-t2", TEAM_ID, CHANNEL_ID, threadTs2, "스레드2 세션");

    // when
    Optional<AiSessionResponse> found1 =
        aiSessionRepository.findBySlackContext(TEAM_ID, CHANNEL_ID, THREAD_TS);
    Optional<AiSessionResponse> found2 =
        aiSessionRepository.findBySlackContext(TEAM_ID, CHANNEL_ID, threadTs2);

    // then
    assertThat(found1).isPresent();
    assertThat(found1.get().id()).isEqualTo(id1);
    assertThat(found2).isPresent();
    assertThat(found2.get().id()).isEqualTo(id2);
    assertThat(id1).isNotEqualTo(id2);
  }
}
