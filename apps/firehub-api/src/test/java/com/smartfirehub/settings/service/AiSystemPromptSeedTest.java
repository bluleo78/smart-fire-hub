package com.smartfirehub.settings.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.settings.model.AiBehaviorDefaults;
import org.junit.jupiter.api.Test;

/**
 * {@code ai.system_prompt} 코드 기본값이 V69 의 슬림 버전인지 검증한다.
 *
 * <p>예전에는 V69 마이그레이션 후 {@code system_settings} 행을 읽어 확인했지만, AI 설정이 테넌트
 * 전용이 되면서(V127 이 {@code ai.*} 플랫폼 행을 지움) 기본값의 출처는 코드
 * ({@link AiBehaviorDefaults#SYSTEM_PROMPT})다. 이 값은 주 프롬프트(ai-agent system-prompt.ts)
 * 뒤에 [사용자 지시사항]으로 덧붙는 얇은 레이어라, 존재하지 않는 도구 나열이 되살아나면 안 된다.
 */
class AiSystemPromptSeedTest {

  /** 기본 시스템 프롬프트가 페르소나 1줄 + 언어/포맷 1줄 슬림 버전인지 확인. */
  @Test
  void aiSystemPrompt_codeDefault_isSlim() {
    String value = AiBehaviorDefaults.defaultOf("ai.system_prompt");
    assertThat(value)
        .isEqualTo(
            "당신은 Smart Fire Hub의 AI 어시스턴트입니다.\n"
                + "응답은 한국어로 하고, 마크다운 형식을 사용하세요.");
    assertThat(value).doesNotContain("get_dataset_columns");
    assertThat(value).doesNotContain("사용 가능한 도구:");
  }
}
