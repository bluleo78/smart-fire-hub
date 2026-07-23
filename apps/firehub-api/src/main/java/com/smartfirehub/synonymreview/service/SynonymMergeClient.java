package com.smartfirehub.synonymreview.service;

import com.smartfirehub.global.exception.ExternalServiceException;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientException;

// 승인된 근접쌍을 ai-agent(Neo4j 소유)에 위임해 동기 병합한다(신규 역방향 통신 — 기존 ai-agent→api
// Internal 인증 패턴을 그대로 재사용). 실패는 ExternalServiceException(502)으로 매핑해 그대로 전파한다 —
// 호출자(SynonymDecisionService.approve)가 이 예외 시 DB 상태를 갱신하지 않으므로 별도 트랜잭션
// 롤백 없이도 "실패 시 pending 유지"가 보장된다.
@Component
public class SynonymMergeClient {
  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  private final WebClient webClient;

  public SynonymMergeClient(
      @Value("${agent.url}") String agentUrl, @Value("${agent.internal-token}") String internalToken) {
    this.webClient =
        WebClient.builder()
            .baseUrl(agentUrl)
            .defaultHeader("Authorization", "Internal " + internalToken)
            .build();
  }

  /** ai-agent POST /agent/graph/merge-entities 호출 — Neo4j에서 두 엔티티 노드를 동기 병합한다. */
  public void mergeEntities(String entityType, String nameA, String nameB) {
    try {
      webClient
          .post()
          .uri("/agent/graph/merge-entities")
          .bodyValue(Map.of("entityType", entityType, "nameA", nameA, "nameB", nameB))
          .retrieve()
          .toBodilessEntity()
          .block(TIMEOUT);
    } catch (WebClientException e) {
      throw new ExternalServiceException("ai-agent 엔티티 병합 호출 실패: " + e.getMessage(), e);
    }
  }
}
