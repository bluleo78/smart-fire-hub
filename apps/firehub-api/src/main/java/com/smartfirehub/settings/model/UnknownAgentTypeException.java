package com.smartfirehub.settings.model;

/**
 * {@code AiCredentialService.resolve()} 가 모르는 {@code agentType} 을 만났을 때 던진다.
 *
 * <p><b>fail-closed 계약의 신호 타입이다.</b> 알 수 없는 유형을 만났다고 빈 자격증명으로 조용히
 * 넘어가면, 소비처가 테넌트가 고른 실행 형태가 아니라 ai-agent 컨테이너의 ambient 키로 떨어져
 * 과금이 새는 회귀가 재발한다({@code 6b1c6383} 과 같은 모양). 그래서 {@link AiCredentialDocument} 를 실제로 소비해 타입
 * 있는 {@link AiCredential} 로 바꾸는 지점(=resolve())에서만 이 예외를 던진다.
 *
 * <p><b>읽기/화면 경로는 이 예외를 던지지 않는다.</b> 손으로 고친 행이나 롤백된 배포가 남긴
 * 알 수 없는 {@code agentType} 도 관리자가 화면에서 보고 되돌릴 수 있어야 하기 때문이다
 * ({@link AiCredentialDocument#parse} 문서 참고) — {@code AiCredentialService.read()} 는 이
 * 예외 없이 동작해야 한다.
 */
public class UnknownAgentTypeException extends RuntimeException {

  public UnknownAgentTypeException(String agentType) {
    super("알 수 없는 AI 자격증명 유형입니다: " + agentType);
  }
}
