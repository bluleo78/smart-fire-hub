package com.smartfirehub.settings.model;

import java.util.Map;

/**
 * 해석된 AI 자격증명. {@code agentType} 을 문자열로 비교하지 않고 타입으로 판별한다 —
 * 1단계에서 문자열 분기가 조용히 컴파일되고 조용히 틀려 과금 혼입 회귀를 만들었다(6b1c6383).
 * 유형이 늘면 switch 누락이 컴파일 오류가 된다.
 *
 * <p>모델은 여기 없다. {@code ai.model} 은 자격증명이 아니라 평면 설정 키로 남는다.
 *
 * <h2>왜 유형별 동작이 이 인터페이스의 메서드인가 (이슈 #695)</h2>
 *
 * <p>#693 직후에는 "자격증명 → 전송 필드" 매핑이 소비처 <b>세 곳</b>({@code AiAgentProxyService}
 * 채팅, {@code AiAgentClient} 분류, {@code ProactiveJobAsyncRunner} 프로액티브)에 각각 독립된
 * {@code switch} 로 재작성돼 있었고, 병합 시점에 이미 서로 드리프트해 있었다 — 한쪽은 미사용
 * 필드를 {@code ""} 로 채우고 다른 쪽은 생략했으며, opencode 모델 접두사 검사는 두 곳에만 있고
 * 한 곳에는 아예 없었다. 다섯 번째 유형이 생기면 세 곳을 손으로 맞춰야 하는데 이미 달라서
 * 리뷰로는 잡히지 않는 상태였다.
 *
 * <p>그래서 유형에 따라 갈리는 동작을 <b>전부</b> 인터페이스 메서드로 내렸다 —
 * {@link #agentType()}, {@link #isComplete()}, {@link #incompleteMessage()},
 * {@link #applyTo(Map)}, {@link #modelProblem(String)}, {@link #modelToSend}. 소비처의
 * {@code switch} 도 {@code instanceof} 도 사라지므로 드리프트할 자리 자체가 없고, 새 변형을
 * 추가하면 "메서드를 구현하지 않았다"는 <b>컴파일 오류</b>로 막힌다 — 소스 텍스트를 읽는 가드
 * ({@code AiCredentialSwitchGuardTest})보다 강한 보장이다.
 *
 * <p><b>"전부"가 중요하다.</b> 첫 리팩터링은 모델 형식 검사만 밖에 남겨 소비처 세 곳이
 * {@code instanceof AiCredential.Opencode} 를 들고 있었는데, 그 가드 테스트는 {@code case
 * AiCredential.} 표식으로 대상을 <b>발견</b>하므로 세 파일이 감시 대상에서 통째로 빠졌다 —
 * 하나를 남긴 대가가 "그 하나가 예전보다 덜 보호받는" 것이었다. 그래서 가드 테스트는 이제
 * 정의 파일 밖의 {@code instanceof AiCredential} 부재도 함께 단언한다.
 *
 * <p>여전히 {@code switch} 로 남아 있는 곳은 인증 상태 컨트롤러({@code AiController})뿐이다.
 * 그쪽은 값을 조립하는 게 아니라 <b>다른 서비스 메서드를 고르는</b> 컨트롤러 계층의 결정이라
 * 모델로 내리지 않았고, 그래서 계속 {@code AiCredentialSwitchGuardTest} 가 지킨다.
 */
public sealed interface AiCredential {

  /** ai-agent 가 body 의 {@code agentType} 키로 읽는 식별자. 저장 값과 같은 문자열이다. */
  String agentType();

  /**
   * 이 자격증명만으로 실제 호출이 가능한가. 거짓이면 호출 전에 사용자에게 보이는 오류로 끝낸다
   * (fail-closed) — 비어 있는 채로 ai-agent 에 넘기면 ambient 자격증명으로 조용히 떨어질 여지를
   * 준다(6b1c6383 의 모양).
   */
  boolean isComplete();

  /** {@link #isComplete()} 가 거짓일 때 사용자에게 보일 문구. 내부 정보를 담지 않는다. */
  String incompleteMessage();

  /**
   * 불완전하면 {@link #incompleteMessage()} 로 {@link IllegalStateException} 을 던지고, 아니면 자신을
   * 돌려준다 — 예외로 끝내는 경로(분류·프로액티브)가 쓴다. 채팅은 SSE 오류 이벤트로 문구만 쓴다.
   */
  default AiCredential requireComplete() {
    if (!isComplete()) {
      throw new IllegalStateException(incompleteMessage());
    }
    return this;
  }

  /**
   * ai-agent 요청 바디에 이 유형이 실제로 쓰는 필드만 채운다.
   *
   * <p>세 소비처(채팅/분류/프로액티브)가 모두 이 메서드를 쓴다 — 필드 이름은 ai-agent 가 읽는
   * 이름이라 세 경로에서 같아야 한다. 빈 문자열은 "설정 안 함"이므로 키 자체를 생략한다
   * (ai-agent 의 세 라우트 모두 {@code body.x || ''} 로 읽어 생략과 빈 문자열을 같게 다룬다).
   *
   * <p>{@code model} 은 여기서 다루지 않는다 — 프로액티브는 sdk/cli/cli-api 에서 모델을 아예
   * 보내지 않고 ai-agent 의 고정 기본값을 쓰는데(비용 차이가 있는 의도된 gap), 여기에 넣으면
   * 그 gap 이 조용히 넓어진다. 모델은 소비처가 각자 싣는다.
   */
  void applyTo(Map<String, Object> body);

  /**
   * 이 자격증명으로 {@code model} 을 쓸 수 있는가. 쓸 수 있으면 {@code null}, 아니면 사용자에게
   * 보일 문구를 돌려준다.
   *
   * <p>대부분의 유형은 모델 형식에 제약이 없어 기본 구현이 항상 {@code null} 이다 —
   * {@link Opencode} 만 재정의한다.
   *
   * <p><b>왜 {@code instanceof} 가 아니라 메서드인가 (이슈 #695 리뷰).</b> 처음 리팩터링에서는
   * 이 검사만 sealed 타입 밖에 남아 세 소비처가 각각 {@code instanceof AiCredential.Opencode} 를
   * 들고 있었다. 그런데 {@code AiCredentialSwitchGuardTest} 는 {@code case AiCredential.} 표식으로
   * 감시 대상을 <b>발견</b>하므로, switch 를 instanceof 로 바꾼 그 순간 세 파일이 가드의 시야에서
   * 통째로 빠졌다 — 즉 이 검사는 리팩터링 전보다 오히려 덜 보호받는 상태가 됐었다. 메서드로
   * 내리면 변형 추가가 다시 컴파일 문제로 드러나고, 규칙의 사본이 하나뿐이 된다.
   */
  default String modelProblem(String model) {
    return null;
  }

  /** {@link #modelProblem} 이 문구를 돌려주면 던진다 — 예외로 끝내는 경로(분류·프로액티브)가 쓴다. */
  default void requireModelUsable(String model) {
    String problem = modelProblem(model);
    if (problem != null) {
      throw new IllegalStateException(problem);
    }
  }

  /**
   * ai-agent 요청에 함께 실어 보낼 모델. {@code null} 이면 보내지 않는다는 뜻이다.
   *
   * <p>기본값이 {@code null} 인 이유: sdk/cli/cli-api 의 프로액티브 경로는 지금까지 모델을 보낸
   * 적이 없고 ai-agent 라우트의 고정 기본값을 쓴다(비용 차이가 있는 의도된 gap). {@link Opencode}
   * 만 반드시 보내야 한다 — 안 보내면 그 고정 기본값(슬래시 없음)이 형식 위반이 된다.
   *
   * <p>값을 {@link java.util.function.Supplier} 로 받는 이유는 모델이 필요 없는 유형에서 설정
   * 조회 자체를 하지 않기 위해서다.
   */
  default String modelToSend(java.util.function.Supplier<String> configuredModel) {
    return null;
  }

  /** 빈 문자열이면 키를 생략하는 put — 세 소비처가 쓰던 {@code if (!blank) put} 관용구. */
  private static void putIfPresent(Map<String, Object> body, String key, String value) {
    if (value != null && !value.isBlank()) {
      body.put(key, value);
    }
  }

  /** Claude Agent SDK 실행형태. OAuth 토큰과 API 키를 동시에 들고 다닐 수 있다(우선순위는 소비처 책임). */
  record Sdk(String oauthToken, String apiKey) implements AiCredential {
    @Override
    public String agentType() {
      return "sdk";
    }

    /** sdk 는 API 키 또는 OAuth 토큰 중 하나만 있어도 인증 가능(ai-agent 가 OAuth 를 우선한다). */
    @Override
    public boolean isComplete() {
      return !oauthToken.isBlank() || !apiKey.isBlank();
    }

    @Override
    public String incompleteMessage() {
      return "AI API 키 또는 OAuth 토큰이 설정되지 않았습니다. 관리자 설정에서 등록하세요.";
    }

    @Override
    public void applyTo(Map<String, Object> body) {
      body.put("agentType", agentType());
      putIfPresent(body, "oauthToken", oauthToken);
      putIfPresent(body, "apiKey", apiKey);
    }
  }

  /** Claude Code CLI(OAuth) 실행형태. */
  record Cli(String oauthToken) implements AiCredential {
    @Override
    public String agentType() {
      return "cli";
    }

    @Override
    public boolean isComplete() {
      return !oauthToken.isBlank();
    }

    @Override
    public String incompleteMessage() {
      return "Claude CLI OAuth 토큰이 설정되지 않았습니다. 관리자 설정에서 토큰을 등록하세요.";
    }

    @Override
    public void applyTo(Map<String, Object> body) {
      body.put("agentType", agentType());
      putIfPresent(body, "oauthToken", oauthToken);
    }
  }

  /** Claude Code CLI(API 키) 실행형태. */
  record CliApi(String apiKey) implements AiCredential {
    @Override
    public String agentType() {
      return "cli-api";
    }

    @Override
    public boolean isComplete() {
      return !apiKey.isBlank();
    }

    @Override
    public String incompleteMessage() {
      return "AI API 키가 설정되지 않았습니다. 관리자 설정에서 API 키를 등록하세요.";
    }

    @Override
    public void applyTo(Map<String, Object> body) {
      body.put("agentType", agentType());
      putIfPresent(body, "apiKey", apiKey);
    }
  }

  /** opencode 실행형태. provider/baseUrl/추론강도까지 테넌트별로 갈리므로 하나로 묶는다. */
  record Opencode(String providerId, String baseUrl, String reasoningEffort, String apiKey)
      implements AiCredential {
    @Override
    public String agentType() {
      return "opencode";
    }

    /**
     * 옵션 3 폐기(2026-09-19, 이슈 #693) — provider 설정(providerId/baseUrl)이 없으면 ai-agent 의
     * {@code buildOpenCodeConfig} 가 배포 측 전역 설정으로 조용히 떨어질 여지를 주지 않고 여기서
     * 먼저 막는다. {@code apiKey} 는 인증이 필요 없는 사설 게이트웨이가 있을 수 있어 필수가 아니다.
     */
    @Override
    public boolean isComplete() {
      return !providerId.isBlank() && !baseUrl.isBlank();
    }

    @Override
    public String incompleteMessage() {
      return "AI 공급자 설정(공급자/기본 URL)이 완전하지 않습니다. 관리자 설정에서 opencode 설정을 확인하세요.";
    }

    @Override
    public void applyTo(Map<String, Object> body) {
      body.put("agentType", agentType());
      // providerId/baseUrl 은 빈 값이어도 싣는다 — 생략하면 ai-agent 쪽에서 "provider 블록 없음"
      // 과 구분되지 않아 옵션 3 시절 동작(전역 설정 상속)으로 오인될 여지가 생긴다. 빈 값 자체는
      // isComplete() 가 호출 전에 막는다.
      body.put("providerId", providerId);
      body.put("baseUrl", baseUrl);
      putIfPresent(body, "reasoningEffort", reasoningEffort);
      putIfPresent(body, "apiKey", apiKey);
    }

    /**
     * {@code ai.model} 이 opencode 형식({@code providerId/modelId})이고 이 자격증명의 공급자와
     * 일치하는가.
     *
     * <p>세 소비처에 복붙돼 있던 검사다 — 채팅과 분류에는 있었고 프로액티브에는 <b>없어서</b>
     * 같은 규칙이 세 갈래였다(이슈 #695). 저장 시 검증
     * ({@code OpencodeCredentialValidation.checkProviderConsistency})은 순환 잠금을 피하려고
     * 슬래시 없는 값을 통과시켜야 하지만, 여기는 <b>실제 호출 시점</b>이라 그 이유가 성립하지
     * 않는다 — 슬래시가 없으면 무조건 형식 위반이다.
     */
    public boolean matchesModel(String model) {
      if (model == null) {
        return false;
      }
      int slash = model.indexOf('/');
      return slash >= 0 && model.substring(0, slash).equals(providerId);
    }

    @Override
    public String modelProblem(String model) {
      return matchesModel(model) ? null : modelFormatMessage(model);
    }

    /** opencode 는 모델을 반드시 함께 보내야 한다 — 그 이유는 인터페이스 쪽 javadoc 참고. */
    @Override
    public String modelToSend(java.util.function.Supplier<String> configuredModel) {
      return configuredModel.get();
    }

    /** 세 경로가 같은 문구를 보여야 하므로 문구도 여기서 만든다. */
    public String modelFormatMessage(String model) {
      return "AI 모델("
          + model
          + ")이 opencode 형식(공급자/모델)이 아니거나 선택한 공급자와 일치하지 않습니다."
          + " 관리자 설정에서 모델을 다시 선택하세요.";
    }
  }
}
