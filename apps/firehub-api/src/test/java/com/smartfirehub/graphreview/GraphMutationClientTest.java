package com.smartfirehub.graphreview;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.smartfirehub.global.exception.ExternalServiceException;
import com.smartfirehub.graphreview.service.GraphMutationClient;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * GraphMutationClient 단위 테스트 — ai-agent 응답을 WireMock으로 모킹해 상태코드별 예외 매핑을 검증한다.
 *
 * <p>핵심 회귀 대상(#310): 대상 노드 부재(409)는 장애(502)와 구분해 사유 문구를 그대로 살려 올려야 한다. 이 매핑이
 * 깨지면 검수자에게 "승인 처리에 실패했습니다."라는 일반 문구만 남아 왜 실패했는지 알 수 없게 된다.
 */
class GraphMutationClientTest {

  static WireMockServer wireMock;

  @BeforeAll
  static void startWireMock() {
    wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
    wireMock.start();
  }

  @AfterAll
  static void stopWireMock() {
    wireMock.stop();
  }

  @BeforeEach
  void resetWireMock() {
    wireMock.resetAll();
  }

  private GraphMutationClient client() {
    return new GraphMutationClient("http://localhost:" + wireMock.port(), "test-token");
  }

  @Test
  @DisplayName("409(대상 노드 부재)는 응답 바디의 사유를 담은 IllegalStateException으로 전파된다 (#310)")
  void addRelation_conflict_propagatesReason() {
    wireMock.stubFor(
        post(urlEqualTo("/agent/graph/add-relation"))
            .willReturn(
                aResponse()
                    .withStatus(409)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"error\":\"graph target missing\","
                            + "\"message\":\"주어/목적어 엔티티가 그래프에 없어 관계를 적재할 수 없습니다.\"}")));

    assertThatThrownBy(() -> client().addRelation("1:a", "CAUSED_BY", "9:없음", List.of(18L)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("그래프에 없어 관계를 적재할 수 없습니다");
  }

  @Test
  @DisplayName("409 바디가 JSON이 아니어도 일반 사유로 폴백하고 IllegalStateException은 유지한다")
  void setProperty_conflictWithoutJsonBody_fallsBack() {
    wireMock.stubFor(
        post(urlEqualTo("/agent/graph/set-property")).willReturn(aResponse().withStatus(409).withBody("boom")));

    assertThatThrownBy(() -> client().setProperty("3:없음", "피해액", "number", "100"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("엔티티 속성 갱신");
  }

  @Test
  @DisplayName("5xx 등 실제 장애는 종전대로 ExternalServiceException(502)으로 전파된다")
  void mergeEntities_serverError_mapsToExternalServiceException() {
    wireMock.stubFor(post(urlEqualTo("/agent/graph/merge-entities")).willReturn(aResponse().withStatus(502)));

    assertThatThrownBy(() -> client().mergeEntities("Cause", "누전", "합선"))
        .isInstanceOf(ExternalServiceException.class);
  }

  @Test
  @DisplayName("2xx는 정상 반환한다(호출자가 status를 approved로 갱신하는 경로)")
  void addRelation_success() {
    wireMock.stubFor(post(urlEqualTo("/agent/graph/add-relation")).willReturn(aResponse().withStatus(204)));

    assertThatCode(() -> client().addRelation("1:a", "CAUSED_BY", "2:b", List.of(7L))).doesNotThrowAnyException();
  }
}
