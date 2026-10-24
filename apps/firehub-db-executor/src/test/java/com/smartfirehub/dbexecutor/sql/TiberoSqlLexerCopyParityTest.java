package com.smartfirehub.dbexecutor.sql;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * {@link TiberoSqlLexer} 는 api 복사본({@code com.smartfirehub.dataset.storage.tibero.TiberoSqlLexer})과
 * 패키지 줄만 다르고 나머지는 글자까지 같아야 한다 — 두 쪽이 같은 SQL 을 다르게 자르면 api 검증을 통과한 문장을 실행기가 다르게 해석한다. api 소스를 모듈 기준
 * 상대 경로로 직접 읽어 대조한다(공용 계약 벡터와 같은 방식, 사본을 두지 않는다 — 한쪽만 바뀌면 여기서 깨진다).
 *
 * <p>{@link TiberoSqlGuard} 는 대조하지 않는다 — db-executor 복사본만 증분 커서 후보 문장을 허용하는 의도된 차이가 있다(Ruling 11,
 * 공용 고정값의 guardDivergence 로 따로 고정).
 */
class TiberoSqlLexerCopyParityTest {

  private static final Path API_COPY =
      Path.of(
          "../firehub-api/src/main/java/com/smartfirehub/dataset/storage/tibero/TiberoSqlLexer.java");
  private static final Path EXECUTOR_COPY =
      Path.of("src/main/java/com/smartfirehub/dbexecutor/sql/TiberoSqlLexer.java");

  /** 첫 줄(package 선언)을 뺀 본문. 줄 끝 문자는 LF 로 맞춘다. */
  private static String withoutPackageLine(Path p) throws IOException {
    String text = Files.readString(p, StandardCharsets.UTF_8).replace("\r\n", "\n");
    assertThat(text).as(p + " 첫 줄").startsWith("package ");
    return text.substring(text.indexOf('\n') + 1);
  }

  @Test
  void lexerIsIdenticalToApiCopyExceptPackageLine() throws IOException {
    assertThat(withoutPackageLine(EXECUTOR_COPY))
        .as("db-executor TiberoSqlLexer 가 api 복사본과 갈라졌다 — 두 곳을 같이 고친다")
        .isEqualTo(withoutPackageLine(API_COPY));
  }
}
