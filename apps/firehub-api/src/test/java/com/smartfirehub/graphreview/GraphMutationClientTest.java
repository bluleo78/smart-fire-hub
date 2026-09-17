package com.smartfirehub.graphreview;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.smartfirehub.global.exception.ExternalServiceException;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.graphreview.service.GraphMutationClient;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

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
    // 운영에서는 JwtAuthenticationFilter 가 요청마다 세워 두는 값이다. 없으면 postJson 이 원격을
    // 부르기 전에 끊으므로(대행 주체 확정 실패), 상태코드 매핑 테스트가 원격까지 가지 못한다.
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(42L, null, List.of()));
    TenantContext.set(7L);
  }

  @AfterEach
  void clearRequestContext() {
    // 대행 헤더는 요청 컨텍스트에서 읽으므로, 남겨 두면 형제 테스트의 요청에 새어 나간다.
    SecurityContextHolder.clearContext();
    TenantContext.clear();
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

    assertThatThrownBy(() -> client().addRelation("1:a", "CAUSED_BY", "9:없음", List.of(18L), 42L))
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

    assertThatThrownBy(() -> client().mergeEntities("Cause", "누전", "합선", 42L))
        .isInstanceOf(ExternalServiceException.class);
  }

  @Test
  @DisplayName("장애 메시지에 내부 호스트·포트·경로가 새지 않고 행동 가능한 문구만 남는다 (#313)")
  void setProperty_serverError_doesNotLeakInternalAddress() {
    wireMock.stubFor(
        post(urlEqualTo("/agent/graph/set-property"))
            .willReturn(aResponse().withStatus(502).withBody("{\"error\":\"set property failed\"}")));

    assertThatThrownBy(() -> client().setProperty("3:창고 화재", "피해액", "number", "30000000"))
        .isInstanceOf(ExternalServiceException.class)
        .hasMessageNotContaining("http://")
        .hasMessageNotContaining("127.0.0.1")
        .hasMessageNotContaining(String.valueOf(wireMock.port()))
        .hasMessageNotContaining("/agent/graph/")
        .hasMessageContaining("엔티티 속성 갱신")
        .hasMessageContaining("다시 시도");
  }

  @Test
  @DisplayName("연결 자체가 실패해도 내부 주소를 노출하지 않는다 (#313)")
  void setProperty_connectionRefused_doesNotLeakInternalAddress() {
    // 아무도 듣지 않는 포트 → WebClientRequestException(연결 거부). 메시지에 내부 주소가 박히는 대표 경로다.
    GraphMutationClient offline = new GraphMutationClient("http://127.0.0.1:1", "test-token");

    assertThatThrownBy(() -> offline.setProperty("3:창고 화재", "피해액", "number", "30000000"))
        .isInstanceOf(ExternalServiceException.class)
        .hasMessageNotContaining("127.0.0.1")
        .hasMessageNotContaining("http://")
        .hasMessageContaining("엔티티 속성 갱신");
  }

  @Test
  @DisplayName("2xx는 정상 반환한다(호출자가 status를 approved로 갱신하는 경로)")
  void addRelation_success() {
    wireMock.stubFor(post(urlEqualTo("/agent/graph/add-relation")).willReturn(aResponse().withStatus(204)));

    assertThatCode(() -> client().addRelation("1:a", "CAUSED_BY", "2:b", List.of(7L), 42L))
        .doesNotThrowAnyException();
  }

  // ── datasetId 배선(#678) — ai-agent의 세 라우트(merge-entities/add-entity/add-relation)는
  // "기본 온톨로지" 폴백이 사라지며 datasetId를 필수로 요구하게 됐다(z.number()). 이 Java 메서드들이
  // 그 필드를 실제로 담아 보내지 않으면 셋 다 400으로 깨진다 — 요청 바디를 직접 검사해 회귀를 잡는다.
  // matchingJsonPath만으로는 datasetId가 문자열로 새어나가도(String.valueOf 같은 실수) 통과하므로,
  // equalToJson으로 값의 JSON 타입(숫자)까지 함께 검증한다.
  @Test
  @DisplayName("mergeEntities는 datasetId를 JSON 숫자로 보낸다(#678)")
  void mergeEntities_sendsDatasetIdAsNumber() {
    wireMock.stubFor(post(urlEqualTo("/agent/graph/merge-entities")).willReturn(aResponse().withStatus(204)));

    client().mergeEntities("Cause", "누전", "합선", 42L);

    wireMock.verify(postRequestedFor(urlEqualTo("/agent/graph/merge-entities"))
        .withRequestBody(matchingJsonPath("$[?(@.datasetId == 42)]")));
  }

  @Test
  @DisplayName("addEntity는 datasetId를 JSON 숫자로 보낸다(#678)")
  void addEntity_sendsDatasetIdAsNumber() {
    wireMock.stubFor(post(urlEqualTo("/agent/graph/add-entity")).willReturn(aResponse().withStatus(204)));

    client().addEntity("Cause", "노후배선", null, List.of(10L), List.of(), 42L);

    wireMock.verify(postRequestedFor(urlEqualTo("/agent/graph/add-entity"))
        .withRequestBody(matchingJsonPath("$[?(@.datasetId == 42)]")));
  }

  @Test
  @DisplayName("addRelation은 datasetId를 JSON 숫자로 보낸다(#678)")
  void addRelation_sendsDatasetIdAsNumber() {
    wireMock.stubFor(post(urlEqualTo("/agent/graph/add-relation")).willReturn(aResponse().withStatus(204)));

    client().addRelation("1:a", "CAUSED_BY", "2:b", List.of(7L), 42L);

    wireMock.verify(postRequestedFor(urlEqualTo("/agent/graph/add-relation"))
        .withRequestBody(matchingJsonPath("$[?(@.datasetId == 42)]")));
  }
  @Test
  @DisplayName("승인 요청의 사용자·테넌트를 대행 헤더로 실어 보낸다")
  void postJson_attachesDelegationHeaders() {
    // ai-agent 는 이 요청을 처리하다 api 를 역호출해 온톨로지를 읽는다 — 그때 쓸 주체가 픽스처의
    // 사용자·테넌트로 실려 나가야 한다.
    wireMock.stubFor(
        post(urlEqualTo("/agent/graph/merge-entities")).willReturn(aResponse().withStatus(204)));

    assertThatCode(() -> client().mergeEntities("Cause", "a", "b", 900L)).doesNotThrowAnyException();

    wireMock.verify(
        postRequestedFor(urlEqualTo("/agent/graph/merge-entities"))
            .withHeader("X-On-Behalf-Of", equalTo("42"))
            .withHeader("X-On-Behalf-Of-Tenant", equalTo("7")));
  }

  @Test
  @DisplayName("요청 컨텍스트에 대행 주체가 없으면 원격을 부르지 않고 사유를 남긴 채 실패한다")
  void postJson_withoutRequestContext_failsFastBeforeCalling() {
    // ai-agent 는 대행 헤더 없는 변형 요청을 400 으로 거부한다 — "헤더 없이 진행"은 완화가 아니라
    // 확정된 실패이고, 그 실패는 일반 502 문구로 퇴화해 원인이 원격 로그에만 남는다.
    SecurityContextHolder.clearContext();
    TenantContext.clear();
    wireMock.stubFor(
        post(urlEqualTo("/agent/graph/merge-entities")).willReturn(aResponse().withStatus(204)));

    assertThatThrownBy(() -> client().mergeEntities("Cause", "a", "b", 900L))
        .isInstanceOf(ExternalServiceException.class)
        .hasMessageContaining("워크스페이스 정보를 확인할 수 없어");

    wireMock.verify(0, postRequestedFor(urlEqualTo("/agent/graph/merge-entities")));
  }

  @Test
  @DisplayName("400(요청 계약 불일치)은 버전 스큐를 알리는 문구로 매핑된다")
  void postJson_badRequest_mapsToContractMismatch() {
    // 실제로 400 이 나는 경우는 두 이미지의 버전 스큐다(구버전 api 가 대행 헤더를 안 보내면
    // ai-agent 가 400). 일반 catch 로 흘리면 "응답 코드 400" 만 남아 원인 추적이 불가능하다.
    wireMock.stubFor(
        post(urlEqualTo("/agent/graph/merge-entities"))
            .willReturn(
                aResponse()
                    .withStatus(400)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"error\":\"missing delegation headers\"}")));

    assertThatThrownBy(() -> client().mergeEntities("Cause", "a", "b", 900L))
        .isInstanceOf(ExternalServiceException.class)
        .hasMessageContaining("버전 불일치");
  }
}
