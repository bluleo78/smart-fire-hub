package com.smartfirehub.settings.model;

/**
 * 해석된 AI 자격증명. {@code agentType} 을 문자열로 비교하지 않고 타입으로 판별한다 —
 * 1단계에서 문자열 분기가 조용히 컴파일되고 조용히 틀려 과금 혼입 회귀를 만들었다(6b1c6383).
 * 유형이 늘면 switch 누락이 컴파일 오류가 된다.
 *
 * <p>모델은 여기 없다. {@code ai.model} 은 자격증명이 아니라 평면 설정 키로 남는다.
 */
public sealed interface AiCredential {

  /** Claude Agent SDK 실행형태. OAuth 토큰과 API 키를 동시에 들고 다닐 수 있다(우선순위는 소비처 책임). */
  record Sdk(String oauthToken, String apiKey) implements AiCredential {}

  /** Claude Code CLI(OAuth) 실행형태. */
  record Cli(String oauthToken) implements AiCredential {}

  /** Claude Code CLI(API 키) 실행형태. */
  record CliApi(String apiKey) implements AiCredential {}

  /** opencode 실행형태. provider/baseUrl/추론강도까지 테넌트별로 갈리므로 하나로 묶는다. */
  record Opencode(String providerId, String baseUrl, String reasoningEffort, String apiKey)
      implements AiCredential {}
}
