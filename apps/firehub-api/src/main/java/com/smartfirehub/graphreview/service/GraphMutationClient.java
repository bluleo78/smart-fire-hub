package com.smartfirehub.graphreview.service;

import com.smartfirehub.global.exception.ExternalServiceException;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientException;

/**
 * 검수 승인 시 Neo4j 소유자인 ai-agent에 그래프 변경을 위임한다(역방향 통신 — 기존 Internal 인증 재사용).
 * 실패는 {@link ExternalServiceException}(502)으로 전파해, 호출자(ReviewItemService)가 이 예외 시
 * DB 상태를 갱신하지 않도록 함으로써 "실패 시 pending 유지"를 트랜잭션 없이 보장한다.
 */
@Component
public class GraphMutationClient {
  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  private final WebClient webClient;

  public GraphMutationClient(
      @Value("${agent.url}") String agentUrl, @Value("${agent.internal-token}") String internalToken) {
    this.webClient =
        WebClient.builder()
            .baseUrl(agentUrl)
            .defaultHeader("Authorization", "Internal " + internalToken)
            .build();
  }

  /** ai-agent POST /agent/graph/merge-entities — 두 엔티티 노드를 Neo4j에서 동기 병합(동의어 승인). */
  public void mergeEntities(String entityType, String nameA, String nameB) {
    post("/agent/graph/merge-entities",
        Map.of("entityType", entityType, "nameA", nameA, "nameB", nameB),
        "엔티티 병합");
  }

  /** ai-agent POST /agent/graph/set-property — 엔티티 노드 속성값을 정정 write(속성 정규화 승인). */
  public void setProperty(String entityKey, String propertyName, String dataType, String value) {
    post("/agent/graph/set-property",
        Map.of("entityKey", entityKey, "propertyName", propertyName, "dataType", dataType, "value", value),
        "엔티티 속성 갱신");
  }

  private void post(String uri, Map<String, String> body, String opLabel) {
    try {
      webClient.post().uri(uri).bodyValue(body).retrieve().toBodilessEntity().block(TIMEOUT);
    } catch (WebClientException e) {
      throw new ExternalServiceException("ai-agent " + opLabel + " 호출 실패: " + e.getMessage(), e);
    }
  }
}
