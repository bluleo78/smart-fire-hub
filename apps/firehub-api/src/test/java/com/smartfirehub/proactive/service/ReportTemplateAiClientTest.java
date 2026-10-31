package com.smartfirehub.proactive.service;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.smartfirehub.global.exception.ExternalServiceException;
import com.smartfirehub.settings.model.AiCredential;
import com.smartfirehub.settings.service.AiCredentialService;
import com.smartfirehub.settings.service.SettingsService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * WD-455 — {@link ReportTemplateAiClient}. Spring 컨텍스트 없이 설정·자격증명 서비스를 목으로 두고 WireMock 으로 ai-agent
 * 를 스텁해, 요청 바디(질문·모델·자격증명)·응답 파싱·실패 매핑(502)·불완전 자격증명(호출 전 409 사유)을 검증한다.
 */
class ReportTemplateAiClientTest {

  static WireMockServer wireMock =
      new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());

  private static final String PATH = "/agent/template-sections/generate";

  @BeforeAll
  static void start() {
    wireMock.start();
  }

  @AfterAll
  static void stop() {
    wireMock.stop();
  }

  @AfterEach
  void clear() {
    wireMock.resetAll();
  }

  private ReportTemplateAiClient client(AiCredential credential) {
    SettingsService settings = mock(SettingsService.class);
    when(settings.getValue("ai.model")).thenReturn(Optional.of("claude-test"));
    AiCredentialService credentials = mock(AiCredentialService.class);
    when(credentials.resolve()).thenReturn(credential);
    ReportTemplateAiClient c =
        new ReportTemplateAiClient(
            "http://localhost:" + wireMock.port(), new ObjectMapper(), settings, credentials);
    ReflectionTestUtils.setField(c, "internalToken", "t");
    return c;
  }

  @Test
  void generateSections_sendsQuestionModelAndCredential_andReturnsSections() {
    wireMock.stubFor(
        post(urlEqualTo(PATH))
            .willReturn(
                okJson(
                    "{\"sections\":[{\"key\":\"summary\",\"type\":\"text\",\"label\":\"요약\"}]}")));

    List<Map<String, Object>> sections =
        client(new AiCredential.Sdk("", "sk-x")).generateSections(7L, "주간 화재 추이");

    assertThat(sections).hasSize(1);
    assertThat(sections.get(0)).containsEntry("key", "summary");
    wireMock.verify(
        postRequestedFor(urlEqualTo(PATH))
            .withHeader("Authorization", equalTo("Internal t"))
            .withHeader("X-On-Behalf-Of", equalTo("7"))
            .withRequestBody(matchingJsonPath("$.question", equalTo("주간 화재 추이")))
            .withRequestBody(matchingJsonPath("$.model", equalTo("claude-test")))
            .withRequestBody(matchingJsonPath("$.agentType", equalTo("sdk")))
            .withRequestBody(matchingJsonPath("$.apiKey", equalTo("sk-x"))));
  }

  @Test
  void generateSections_agentError_throwsExternalServiceException_withoutLeakingBody() {
    wireMock.stubFor(
        post(urlEqualTo(PATH))
            .willReturn(aResponse().withStatus(502).withBody("{\"error\":\"secret detail\"}")));

    assertThatThrownBy(() -> client(new AiCredential.Sdk("", "sk-x")).generateSections(1L, "q"))
        .isInstanceOf(ExternalServiceException.class)
        .hasMessage(ReportTemplateAiClient.GENERATION_FAILED_MESSAGE);
  }

  @Test
  void generateSections_responseWithoutSections_throwsExternalServiceException() {
    wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(okJson("{\"foo\":1}")));

    assertThatThrownBy(() -> client(new AiCredential.Sdk("", "sk-x")).generateSections(1L, "q"))
        .isInstanceOf(ExternalServiceException.class);
  }

  @Test
  void generateSections_incompleteCredential_failsBeforeCalling() {
    assertThatThrownBy(() -> client(new AiCredential.Sdk("", "")).generateSections(1L, "q"))
        .isInstanceOf(IllegalStateException.class);
    wireMock.verify(0, postRequestedFor(urlEqualTo(PATH)));
  }
}
