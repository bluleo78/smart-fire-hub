package com.smartfirehub.proactive.service;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.DataStoreResolver;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.settings.model.AiCredential;
import com.smartfirehub.vectorstore.VectorStoreFeatures;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * WD-79 — 프로액티브 요청 바디의 {@code dbDialect}. Spring 컨텍스트 없이 저장소 리졸버를 목으로 두고 WireMock 으로 ai-agent 를
 * 스텁해, 테넌트 저장소에 따라 TIBERO/POSTGRESQL 이 실리는지 검증한다.
 */
class ProactiveAiClientDialectTest {

  static WireMockServer wireMock =
      new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());

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
    TenantContext.clear();
    wireMock.resetAll();
  }

  private void runWith(DataStoreBackend backend) {
    runWith(backend, backend == DataStoreBackend.POSTGRESQL);
  }

  private void runWith(DataStoreBackend backend, boolean rowSearch) {
    DataStoreResolver resolver = mock(DataStoreResolver.class);
    when(resolver.backendFor(5L)).thenReturn(backend);
    VectorStoreFeatures features = mock(VectorStoreFeatures.class);
    when(features.supportsRowSearch(backend)).thenReturn(rowSearch);
    ProactiveAiClient client =
        new ProactiveAiClient(
            "http://localhost:" + wireMock.port(), new ObjectMapper(), resolver, features);
    ReflectionTestUtils.setField(client, "internalToken", "t");
    wireMock.stubFor(post(urlEqualTo("/agent/proactive")).willReturn(okJson("{\"sections\":[]}")));
    TenantContext.set(5L);
    client.execute(
        1L, "prompt", "{}", new AiCredential.Sdk("oat-test", "sk-x"), null, null, Map.of(), null);
  }

  @Test
  void proactive_tiberoTenant_sendsTiberoDialect() {
    runWith(DataStoreBackend.TIBERO);
    wireMock.verify(
        postRequestedFor(urlEqualTo("/agent/proactive"))
            .withRequestBody(matchingJsonPath("$.dbDialect", equalTo("TIBERO"))));
  }

  @Test
  void proactive_pgTenant_sendsPostgresqlDialect() {
    runWith(DataStoreBackend.POSTGRESQL);
    wireMock.verify(
        postRequestedFor(urlEqualTo("/agent/proactive"))
            .withRequestBody(matchingJsonPath("$.dbDialect", equalTo("POSTGRESQL"))));
  }

  /** WD-744 — qdrant 조합 Tibero 처럼 행 검색이 되면 rowSearch=true 를 싣는다(방언과 따로). */
  @Test
  void proactive_sendsRowSearchFromVectorStoreFeatures() {
    runWith(DataStoreBackend.TIBERO, true);
    wireMock.verify(
        postRequestedFor(urlEqualTo("/agent/proactive"))
            .withRequestBody(matchingJsonPath("$.dbDialect", equalTo("TIBERO")))
            .withRequestBody(matchingJsonPath("$.rowSearch", equalTo("true"))));
  }

  /** WD-744 — platform 조합 Tibero 처럼 행 검색이 안 되면 rowSearch=false. */
  @Test
  void proactive_sendsRowSearchFalseWhenUnsupported() {
    runWith(DataStoreBackend.TIBERO, false);
    wireMock.verify(
        postRequestedFor(urlEqualTo("/agent/proactive"))
            .withRequestBody(matchingJsonPath("$.rowSearch", equalTo("false"))));
  }
}
