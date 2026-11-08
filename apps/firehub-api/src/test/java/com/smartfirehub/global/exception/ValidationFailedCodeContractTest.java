package com.smartfirehub.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 검증 실패 기계용 코드(VALIDATION_FAILED, WD-640) 계약 고정(WD-654).
 *
 * <p>왜: 서버 상수 {@link GlobalExceptionHandler#VALIDATION_FAILED_CODE} 를 web·admin·ai-agent 가 각자 문자열로
 * 복제해 쓴다. 서버 값만 바뀌면 세 클라이언트가 조용히 검증 실패를 못 알아본다(필드 문구가 사라지고 일반 오류로 보인다). 공용 패키지로 묶는 것보다 가볍게, 각 클라이언트
 * 파일의 상수 선언을 읽어 서버 값과 같은지 본다 — 주석에도 같은 글자가 있으므로 단순 포함 검사가 아니라 선언문({@code const
 * VALIDATION_FAILED_CODE = '…'})을 정규식으로 하나만 뽑아 비교한다.
 *
 * <p>작업 디렉터리는 apps/firehub-api 다(Gradle 테스트 기본값) — 형제 앱 파일을 읽는 선례:
 * TenantSchemaVectorConformanceTest.
 */
class ValidationFailedCodeContractTest {

  /** 클라이언트 상수 선언 — export 유무와 따옴표 종류를 허용한다. */
  private static final Pattern DECLARATION =
      Pattern.compile("(?:export\\s+)?const\\s+VALIDATION_FAILED_CODE\\s*=\\s*['\"]([^'\"]*)['\"]");

  @ParameterizedTest
  @ValueSource(
      strings = {
        "../firehub-web/src/lib/api-error.ts",
        "../firehub-admin/src/lib/http-errors.ts",
        "../firehub-ai-agent/src/mcp/api-client.ts"
      })
  void clientConstantMatchesServer(String file) throws IOException {
    Path path = Path.of(file);
    assertThat(path).as("클라이언트 파일 %s", file).exists();
    String source = Files.readString(path, StandardCharsets.UTF_8);

    List<String> values = new ArrayList<>();
    Matcher m = DECLARATION.matcher(source);
    while (m.find()) {
      values.add(m.group(1));
    }
    assertThat(values)
        .as("%s 의 VALIDATION_FAILED_CODE 선언(정확히 하나, 서버 값과 같아야 한다)", file)
        .containsExactly(GlobalExceptionHandler.VALIDATION_FAILED_CODE);
  }
}
