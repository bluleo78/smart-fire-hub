package com.smartfirehub.global.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.pipeline.service.validator.SqlValidator;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;

/**
 * dev DB 에 실제로 저장돼 있던 사용자 SQL(query_history 20행 + saved_query 34행)을 새 {@link
 * SqlValidator} 규칙에 통과시켜 오탐률을 고정하는 회귀 하네스.
 *
 * <p>이슈 #385 / Task 2 게이트. 전량을 {@code SqlValidator("data", true)} 로 실측한 결과 47건 통과, 7건 거부였다.
 * 거부 7건 중 3건은 다른 스키마({@code public.user}) 직접 참조로 정책상 정상 거부, 나머지 4건은 여러 문장이 구분자 없이 붙어 있어
 * 단일 스테이트먼트로도 애초에 파싱 불가능한 기형 문자열(예: {@code "...WHERE 1=0SELECT..."}) — PostgreSQL 자체도 실행할 수
 * 없는 형태이므로 검증기가 막았다고 보기보다 애초에 유효한 SQL 이 아니었다는 뜻이다. 어느 쪽이든 JSqlParser 가 **정당한** 쿼리를
 * 파싱 실패로 오탐 거부한 사례는 **0건**이므로 이 밴드는 "배선 진행" 판정이다. 단, 이 코퍼스는 dev 이력에 실제로 있던 형태(단순
 * SELECT/GROUP BY 위주 41건)일 뿐 방언 적합성 스위트가 아니다 — PostGIS 연산자·특수 방언 구문은 dev 이력에 아예 없었으므로
 * "오탐 0건"을 Task 4 의 {@code search_path} 축소 근거로 인용하면 안 된다(Task 4 가 별도로 실측할 것). 상세 근거는
 * {@code scratchpad/guard-task2-report.md} 참고.
 *
 * <p>이 클래스는 {@code new SqlValidator("data", true)} 를 직접 생성해 쓸 뿐 스프링 컨텍스트나
 * {@code AnalyticsQueryExecutionService} 등 실제 배선을 참조하지 않는다. 따라서 Task 4 가 프로덕션 배선에서
 * {@code allowUnqualifiedTables=false} 로 바꿔도 **이 테스트 자체는 계속 초록**이다 — 미한정 허용을 끄면 안 된다는 결론은
 * 이 테스트가 아니라 원장 R1 판정과 dev 저장 쿼리 실측(위 문단)에 근거한다.
 *
 * <p>이 클래스는 통과해야 정상인 47건 중 {@code pg_sleep(5)}(펜테스트 흔적으로 보이며 R5 판정에 따라 Task 5 에서
 * {@code BLOCKED_FUNCTIONS} 에 추가될 예정 — 미리 이 하네스에 "반드시 통과" 로 고정하지 않았다) 1건을 제외하고, 문자열 중복
 * 제거 후 41건을 {@code stored-user-sql-corpus.json} 에 옮겨 두고 새 규칙이 전부 통과시키는지 단언한다. 개인정보(사람
 * 이름)만 마스킹했고 {@code 'HACKED'} 등 비-PII 값은 원문 그대로 남겼다 — SQL 구조는 원본과 동일하다. 이 41건 중
 * {@code UPDATE ... SET 고객명='HACKED'} / {@code DELETE ... WHERE 1=0} 계열 3건은 현재 정책(SELECT/INSERT/UPDATE/DELETE
 * 전부 허용)상 통과가 맞아 넣었을 뿐 — 후속 밴드가 애드혹 경로를 SELECT 전용으로 좁히면 이 3건이 거부되는 것은 회귀가 아니다.
 */
class StoredUserSqlReplayTest {

  private static final String CORPUS_RESOURCE = "/global/util/stored-user-sql-corpus.json";

  /** dev 애드혹 경로와 동일한 정책: data 스키마만 허용, 미한정 테이블명은 허용(Task 2 R1 판정). */
  private final SqlValidator validator = new SqlValidator("data", true);

  @Test
  void 저장된_사용자_SQL_전량이_새_규칙을_통과한다() throws IOException {
    List<String> corpus = loadCorpus();

    // 리소스가 비어 있으면 아래 반복문이 아무 것도 검증하지 않고 공허하게 통과해버린다 —
    // 그런 상황을 막기 위한 역방향 단언(계획서 Task 2 Step 4).
    assertThat(corpus).isNotEmpty();

    // assertThatCode 를 반복문에서 바로 쓰면 첫 실패에서 예외가 던져져 이후 케이스의 오탐이
    // 가려진다. SoftAssertions 로 전량을 끝까지 돌리고 실패를 한 번에 모아 보고한다.
    SoftAssertions softly = new SoftAssertions();
    for (String sql : corpus) {
      softly
          .assertThatCode(() -> validator.validate(sql))
          .as("dev 에 저장돼 있던 실제 사용자 SQL 이 새 규칙에서 거부되면 안 된다: %s", sql)
          .doesNotThrowAnyException();
    }
    softly.assertAll();
  }

  private List<String> loadCorpus() throws IOException {
    try (InputStream is = getClass().getResourceAsStream(CORPUS_RESOURCE)) {
      if (is == null) {
        throw new IllegalStateException("테스트 리소스를 찾을 수 없습니다: " + CORPUS_RESOURCE);
      }
      return new ObjectMapper().readValue(is, new com.fasterxml.jackson.core.type.TypeReference<>() {});
    }
  }
}
