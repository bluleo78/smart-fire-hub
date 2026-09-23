package com.smartfirehub.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.smartfirehub.ai.service.AiChatRequestBuilder.Prepared;
import com.smartfirehub.settings.model.AiBehaviorDefaults;
import com.smartfirehub.settings.model.AiCredential;
import com.smartfirehub.settings.service.AiCredentialService;
import com.smartfirehub.settings.service.SettingsService;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 채팅 요청 바디 조립 테스트(이슈 #709). 웹 채팅과 Slack 인바운드가 이 한 곳을 공유하므로, 자격증명이
 * 실리는지와 미설정일 때 ai-agent 를 부르지 말라는 신호(problem)를 내는지를 여기서 고정한다.
 */
@ExtendWith(MockitoExtension.class)
class AiChatRequestBuilderTest {

  @Mock private SettingsService settingsService;
  @Mock private AiCredentialService aiCredentialService;
  @InjectMocks private AiChatRequestBuilder builder;

  @Test
  @DisplayName("자격증명이 완전하면 자격증명·테넌트·세션·동작 설정을 실은 바디를 만든다")
  void prepare_complete_buildsBodyWithCredential() {
    when(settingsService.getAsMap("ai"))
        .thenReturn(Map.of("ai.model", "claude-sonnet-5", "ai.max_turns", "7"));
    when(aiCredentialService.resolve()).thenReturn(new AiCredential.CliApi("sk-live"));

    Prepared prepared = builder.prepare(7L, 42L, "", "hi");

    assertThat(prepared.problem()).isNull();
    assertThat(prepared.body())
        .containsEntry("message", "hi")
        .containsEntry("sessionId", "")
        .containsEntry("userId", 42L)
        .containsEntry("tenantId", 7L)
        .containsEntry("agentType", "cli-api")
        .containsEntry("apiKey", "sk-live")
        .containsEntry("model", "claude-sonnet-5")
        .containsEntry("maxTurns", 7)
        // 저장값이 없는 동작 키는 코드 기본값
        .containsEntry("maxTokens", AiBehaviorDefaults.MAX_TOKENS);
  }

  @Test
  @DisplayName("자격증명 미설정이면 바디 없이 유형별 안내 문구를 돌려준다")
  void prepare_incomplete_returnsProblem() {
    AiCredential empty = new AiCredential.Sdk("", "");
    when(aiCredentialService.resolve()).thenReturn(empty);

    Prepared prepared = builder.prepare(7L, 42L, "", "hi");

    assertThat(prepared.body()).isNull();
    assertThat(prepared.problem()).isEqualTo(empty.incompleteMessage());
  }
}
