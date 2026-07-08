package com.smartfirehub.settings.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.support.IntegrationTestBase;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * V69 마이그레이션 후 ai.system_prompt 기본값이 슬림 버전으로 교체됐는지 검증한다.
 * 테스트 DB는 Flyway로 V15(원본 시드) → V69(조건부 교체)까지 적용되므로 슬림 값이어야 한다.
 */
class AiSystemPromptSeedTest extends IntegrationTestBase {

  @Autowired private SettingsService settingsService;

  /** 마이그레이션 후 기본 시스템 프롬프트가 페르소나 1줄 + 언어/포맷 1줄 슬림 버전인지 확인. */
  @Test
  void aiSystemPrompt_isSlimDefault_afterMigration() {
    Optional<String> value = settingsService.getValue("ai.system_prompt");
    assertThat(value)
        .contains(
            "당신은 Smart Fire Hub의 AI 어시스턴트입니다.\n"
                + "응답은 한국어로 하고, 마크다운 형식을 사용하세요.");
    assertThat(value.orElse("")).doesNotContain("get_dataset_columns");
    assertThat(value.orElse("")).doesNotContain("사용 가능한 도구:");
  }
}
