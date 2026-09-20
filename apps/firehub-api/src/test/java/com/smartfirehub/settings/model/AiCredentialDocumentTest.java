package com.smartfirehub.settings.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link AiCredentialDocument} 단위 테스트. DB/Spring 컨텍스트가 필요 없는 순수 JSON 왕복
 * 테스트라 {@code IntegrationTestBase} 를 상속하지 않는다.
 */
class AiCredentialDocumentTest {

  @Test
  void parse_모르는_필드를_보존한다() {
    // 3단계에서 필드가 늘 때 낡은 UI 의 read-modify-write 가 새 필드를 떨구면 안 된다.
    String json = """
        {"v":1,"agentType":"sdk","payload":{"futureKey":"x"},"secret":{},"unknownTop":7}""";
    AiCredentialDocument doc = AiCredentialDocument.parse(json);
    assertThat(doc.toJson()).contains("futureKey").contains("unknownTop");
  }

  @Test
  void secretNames_는_빈_값을_제외한다() {
    // "설정됨" 표시의 근거라, 지운 키가 섞이면 사용자가 있지도 않은 값을 믿는다.
    // oauthToken 의 암호문은 일부러 비워두지 않았다 — 실제 암호화는 빈 평문을 넣어도 빈
    // 암호문을 내지 않으므로, "암호문 자체가 비었는지"가 아니라 "decrypt 한 결과가
    // 비었는지"로 판정해야 한다. 암호문의 공백 여부만 보는 구현도 이 테스트를 속일 수
    // 있었던 낡은 버전에 대한 회귀 방지.
    AiCredentialDocument doc =
        AiCredentialDocument.parse("""
            {"v":1,"agentType":"sdk","payload":{},"secret":{"apiKey":"ZW5j","oauthToken":"Y2xlYXJlZA=="}}""");
    assertThat(doc.secretNames(cipher -> cipher.equals("Y2xlYXJlZA==") ? "" : "plain"))
        .containsExactly("apiKey");
  }

  @Test
  void parse_알수없는_agentType_은_거부하지_않고_보존한다() {
    // fail-closed 는 소비처의 책임이다. 문서 계층에서 throw 하면 관리자가 GET/DELETE 로
    // 되돌릴 수 없다(롤백된 배포가 남긴 행을 화면에서 고칠 수 없게 된다).
    AiCredentialDocument doc =
        AiCredentialDocument.parse("""
            {"v":1,"agentType":"martian","payload":{},"secret":{}}""");
    assertThat(doc.agentType()).isEqualTo("martian");
  }
}
