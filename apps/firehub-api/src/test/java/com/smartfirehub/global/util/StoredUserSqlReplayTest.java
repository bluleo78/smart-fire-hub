package com.smartfirehub.global.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.pipeline.service.validator.SqlValidator;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * dev DB 에 실제로 저장돼 있던 사용자 SQL(query_history 20행 + saved_query 34행)을 새 {@link
 * SqlValidator} 규칙에 통과시켜 오탐률을 고정하는 회귀 하네스.
 *
 * <p>이슈 #385 / Task 2 게이트. 전량을 {@code SqlValidator("data", true)} 로 실측한 결과 47건 통과, 7건 거부였다.
 * 거부 7건 중 3건은 다른 스키마({@code public.user}) 직접 참조로 정책상 정상 거부, 나머지 4건은 여러 문장이 구분자 없이 붙어 있어
 * 단일 스테이트먼트로도 애초에 파싱 불가능한 기형 문자열(예: {@code "...WHERE 1=0SELECT..."}) — PostgreSQL 자체도 실행할 수
 * 없는 형태이므로 검증기가 막았다고 보기보다 애초에 유효한 SQL 이 아니었다는 뜻이다. 어느 쪽이든 JSqlParser 가 **정당한** 쿼리를
 * 파싱 실패로 오탐 거부한 사례는 **0건**이므로 이 밴드는 "배선 진행" 판정이다. 상세 근거는
 * {@code scratchpad/guard-task2-report.md} 참고. 이 리소스는 {@code allowUnqualifiedTables=true} 를 전제로
 * 고정하므로, 이후 Task 4 가 미한정 허용을 끄는 방향으로 갈 수 없다는 제약도 함께 남긴다.
 *
 * <p>이 클래스는 통과해야 정상인 47건 중 {@code pg_sleep(5)}(펜테스트 흔적으로 보이나 이 밴드가 차단 여부를 판정하지 않음 — 별도
 * 이슈로 남김) 1건을 제외하고, 문자열 중복 제거 후 41건을 {@code stored-user-sql-corpus.json} 에 옮겨 두고 새 규칙이 전부
 * 통과시키는지 단언한다. 개인정보(사람 이름)만 마스킹했고 {@code 'HACKED'} 등 비-PII 값은 원문 그대로 남겼다 — SQL 구조는
 * 원본과 동일하다.
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

    for (String sql : corpus) {
      assertThatCode(() -> validator.validate(sql))
          .as("dev 에 저장돼 있던 실제 사용자 SQL 이 새 규칙에서 거부되면 안 된다: %s", sql)
          .doesNotThrowAnyException();
    }
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
