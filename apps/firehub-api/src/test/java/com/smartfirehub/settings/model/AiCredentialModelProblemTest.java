package com.smartfirehub.settings.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 유형별 모델 형식 검사({@link AiCredential#modelProblem}) — WD-766.
 *
 * <p>opencode 에서 Claude 계열로 유형만 바꾸면 {@code ai.model} 에 opencode 형식 값(예 {@code openai/gpt-4o})이 남는다
 * — Claude 계열은 공급자 접두({@code /})가 있는 값을 막고 「모델을 다시 선택」 안내를 돌려준다(채팅·분류·프로액티브 같은 검사·같은 문구).
 */
class AiCredentialModelProblemTest {

  /** 시험 이름에는 유형만 보인다 — 레코드 기본 toString 은 비밀 필드까지 찍는다(가짜 값이라도 습관을 맞춘다). */
  static List<Named<AiCredential>> claudeCredentials() {
    return List.of(
        Named.of("sdk", new AiCredential.Sdk("", "sk-ant")),
        Named.of("cli", new AiCredential.Cli("oat")),
        Named.of("cli-api", new AiCredential.CliApi("sk-ant")));
  }

  @ParameterizedTest
  @MethodSource("claudeCredentials")
  void Claude_계열에_opencode_형식_모델이_남아_있으면_다시_선택_안내를_돌려준다(AiCredential credential) {
    String problem = credential.modelProblem("openai/gpt-4o");

    assertThat(problem)
        .isNotNull()
        .contains("openai/gpt-4o")
        .contains("opencode 형식")
        .contains("모델을 다시 선택");
    assertThatThrownBy(() -> credential.requireModelUsable("anthropic/claude-sonnet-5"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("모델을 다시 선택");
  }

  @ParameterizedTest
  @MethodSource("claudeCredentials")
  void Claude_계열에_정상_Claude_모델은_통과한다(AiCredential credential) {
    for (String model :
        List.of("claude-haiku-4-5", "claude-opus-4-1", "claude-sonnet-4-5[1m]", "sonnet")) {
      assertThat(credential.modelProblem(model)).as(model).isNull();
    }
    // null 은 「설정 안 함」— 채팅 경로가 맵에서 값을 못 찾은 경우로, 예전처럼 막지 않는다(기본값은 ai-agent 몫).
    assertThat(credential.modelProblem(null)).isNull();
  }

  /** opencode 기존 규칙은 그대로다 — 형식(공급자/모델)과 공급자 일치를 본다. */
  @ParameterizedTest
  @ValueSource(strings = {"claude-haiku-4-5", "anthropic/gpt-4o"})
  void opencode_는_기존_규칙_그대로_공급자_불일치나_슬래시_없음을_막는다(String model) {
    AiCredential.Opencode opencode = new AiCredential.Opencode("openai", "https://x", "", "k");

    assertThat(opencode.modelProblem(model)).isEqualTo(opencode.modelFormatMessage(model));
    assertThat(opencode.modelProblem("openai/gpt-4o")).isNull();
  }
}
