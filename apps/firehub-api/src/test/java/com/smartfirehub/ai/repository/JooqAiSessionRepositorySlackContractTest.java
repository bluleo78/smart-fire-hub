package com.smartfirehub.ai.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.ai.repository.jooq.JooqAiSessionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** AiSessionRepositorySlackContractTest 를 Jooq(PostgreSQL) 구현으로 실행한다(WD-485). */
class JooqAiSessionRepositorySlackContractTest extends AiSessionRepositorySlackContractTest {

  @Autowired private JooqAiSessionRepository repository;

  @Override
  protected AiSessionRepository repository() {
    return repository;
  }

  /**
   * 동일 (team_id, channel_id, thread_ts) 로 두 번 INSERT 하면 uk_ai_session_slack_thread UNIQUE 위반으로 예외가
   * 발생해야 한다.
   */
  @Test
  @DisplayName("중복 (team,channel,thread) INSERT → UNIQUE 제약 위반")
  void createSlackSession_duplicateThread_throwsConstraintViolation() {
    // given: 첫 번째 INSERT
    repository()
        .createSlackSession(
            testUserId, "agent-session-first", TEAM_ID, CHANNEL_ID, THREAD_TS, "첫 번째 세션");

    // when/then: 동일 (team,channel,thread) 로 두 번째 INSERT → UNIQUE 위반
    // DSLContext에 ExceptionTranslatorExecuteListener가 등록되어 있으므로 jOOQ의
    // IntegrityConstraintViolationException이 아니라 Spring의 DuplicateKeyException으로 번역된다 (#312).
    // 이 타입 고정이 곧 예외 번역기 등록 여부의 회귀 가드다.
    assertThatThrownBy(
            () ->
                repository()
                    .createSlackSession(
                        testUserId,
                        "agent-session-second",
                        TEAM_ID,
                        CHANNEL_ID,
                        THREAD_TS,
                        "두 번째 세션"))
        .isInstanceOf(org.springframework.dao.DuplicateKeyException.class)
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }
}
