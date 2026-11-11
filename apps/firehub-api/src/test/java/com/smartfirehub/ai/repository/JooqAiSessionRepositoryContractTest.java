package com.smartfirehub.ai.repository;

import com.smartfirehub.ai.repository.jooq.JooqAiSessionRepository;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Autowired;

/** AiSessionRepositoryContractTest 를 jOOQ(PostgreSQL) 구현으로 실행한다(WD-712). */
class JooqAiSessionRepositoryContractTest extends AiSessionRepositoryContractTest {

  @Autowired private JooqAiSessionRepository repository;
  @Autowired private DSLContext dsl;

  @Override
  protected AiSessionRepository repository() {
    return repository;
  }

  /**
   * jOOQ 판은 {@code currentLocalDateTime()}(PG LOCALTIMESTAMP)로 쓰므로 기준 시간대는 PG 세션의 TimeZone 이다.
   * pgjdbc 는 접속할 때의 JVM 기본 시간대를 세션에 심고, 풀 연결은 테스트가 {@code TimeZone.setDefault} 를 바꾸기 전에 열려 있어 그
   * 값(테스트 JVM 이 뜰 때의 시간대 — PG 태스크는 컴퓨터 시간대)에 고정된다. 운영에서는 JVM 시간대 = 세션 시간대라 MyBatis 판(JVM 벽시계 바인드)과
   * 같은 값이 된다 — 여기서는 세션 시간대를 직접 물어 기대값을 정해, 테스트 중 JVM 시간대만 바꾼 상황에서도 jOOQ 판의 실제 의미(LOCALTIMESTAMP)를
   * 단언한다.
   */
  @Override
  protected LocalDateTime expectedWriteClockNow() {
    String sessionZone = String.valueOf(dsl.fetchValue("SELECT current_setting('TimeZone')"));
    return LocalDateTime.now(ZoneId.of(sessionZone));
  }
}
