package com.smartfirehub.ontology.service;

import com.smartfirehub.global.exception.ExternalServiceException;
import com.smartfirehub.ontology.dto.GraphResponse;
import com.smartfirehub.ontology.dto.OntologyResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientException;

// ai-agent의 읽기 전용 온톨로지/그래프 엔드포인트를 프록시한다(내부 서비스 인증). DB 미접근 — 순수 프록시.
@Service
public class OntologyProxyService {
  // AiAgentProxyService(verifyCliToken/verifyApiKey)와 동일한 블로킹 타임아웃 — ai-agent 무응답 시
  // 서블릿 스레드가 무한 대기하며 고갈되는 것을 방지한다.
  private static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(40);

  private final WebClient webClient;

  public OntologyProxyService(
      @Value("${agent.url}") String agentUrl,
      @Value("${agent.internal-token}") String internalToken) {
    // 내부 서비스 인증 헤더를 기본값으로 갖는 WebClient. (AiAgentClient와 동일 방식)
    this.webClient =
        WebClient.builder()
            .baseUrl(agentUrl)
            .defaultHeader("Authorization", "Internal " + internalToken)
            .build();
  }

  // 온톨로지 스키마 조회 — ai-agent GET /agent/ontology. 실패(비정상 응답/타임아웃)는 ExternalServiceException(502)로 매핑.
  public OntologyResponse getOntology() {
    try {
      return webClient
          .get()
          .uri("/agent/ontology")
          .retrieve()
          .bodyToMono(OntologyResponse.class)
          .block(BLOCK_TIMEOUT);
    } catch (WebClientException e) {
      throw new ExternalServiceException("온톨로지 스키마 조회 중 ai-agent 호출 실패: " + e.getMessage(), e);
    }
  }

  // 전체 지식그래프 조회 — ai-agent GET /agent/graph. 실패(502 등)는 ExternalServiceException(502)로 매핑.
  public GraphResponse getGraph() {
    try {
      return webClient.get().uri("/agent/graph").retrieve().bodyToMono(GraphResponse.class).block(BLOCK_TIMEOUT);
    } catch (WebClientException e) {
      throw new ExternalServiceException("지식그래프 조회 중 ai-agent 호출 실패: " + e.getMessage(), e);
    }
  }
}
