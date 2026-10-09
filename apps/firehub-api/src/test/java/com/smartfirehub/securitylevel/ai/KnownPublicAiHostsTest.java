package com.smartfirehub.securitylevel.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 확실한 공용 AI 호스트 판정. <b>웹 apps/firehub-web/src/lib/hosting-location.test.ts 의 'isKnownPublicAiHost' 사례 표와
 * 같은 행이다</b> — 두 언어의 목록을 한 곳에 정의할 수 없어 같은 표로 일치를 고정한다. 한쪽 행을 바꾸면 다른 쪽도 바꿀 것.
 */
class KnownPublicAiHostsTest {

  /** 웹 KNOWN_PUBLIC_AI_HOSTS 와 같은 집합 — 한쪽에만 호스트를 더하면 여기서 깨진다. */
  @Test
  void hostList_matchesWebList() {
    assertThat(KnownPublicAiHosts.HOSTS)
        .isEqualTo(
            Set.of(
                "api.openai.com",
                "api.anthropic.com",
                "generativelanguage.googleapis.com",
                "api.mistral.ai",
                "api.cohere.com",
                "api.cohere.ai",
                "api.groq.com",
                "openrouter.ai",
                "api.together.xyz",
                "api.deepseek.com",
                "api.voyageai.com",
                "api.fireworks.ai",
                "api.perplexity.ai",
                "api.x.ai"));
  }

  /** 공유 사례 표 — "확실"(true)만 서버가 거부하고, 공용일 "가능성"만 있는 주소(gateway.example.com 등)는 허용(false)한다. */
  @ParameterizedTest(name = "{0} → {1}")
  @CsvSource(
      delimiter = '|',
      value = {
        "https://api.openai.com/v1 | true",
        "HTTPS://API.OPENAI.COM/v1 | true",
        "https://api.openai.com:443/v1 | true",
        "https://api.openai.com./v1 | true",
        "https://api.anthropic.com | true",
        "https://generativelanguage.googleapis.com/v1beta/openai | true",
        "https://openrouter.ai/api/v1 | true",
        "https://api.mistral.ai/v1 | true",
        "https://gateway.example.com | false",
        "https://openai.example.com/v1 | false",
        "https://api.openai.com.evil.example/v1 | false",
        "http://10.0.0.5:8000/v1 | false",
        "http://localhost:11434 | false",
        "http://ollama:11434 | false",
        "http://llm.corp.internal | false",
        "not a url | false",
        "'' | false",
      })
  void sharedCaseTable(String url, boolean expected) {
    assertThat(KnownPublicAiHosts.isKnownPublic(url)).isEqualTo(expected);
  }

  @Test
  void nullIsNotKnown() {
    assertThat(KnownPublicAiHosts.isKnownPublic(null)).isFalse();
  }

  @Test
  void requireNotKnownPublic_rejectsWith400Message() {
    assertThatThrownBy(() -> KnownPublicAiHosts.requireNotKnownPublic("https://api.openai.com/v1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(KnownPublicAiHosts.MSG_SELF_HOSTED_PUBLIC);
  }
}
