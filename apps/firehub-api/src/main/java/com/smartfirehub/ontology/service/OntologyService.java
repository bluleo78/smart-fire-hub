package com.smartfirehub.ontology.service;

import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.global.exception.ExternalServiceException;
import com.smartfirehub.ontology.dto.GraphResponse;
import com.smartfirehub.ontology.dto.OntologyResponse;
import com.smartfirehub.ontology.dto.UpdateOntologyRequest;
import com.smartfirehub.ontology.repository.OntologyRepository;
import com.smartfirehub.user.repository.UserRepository;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientException;

// 온톨로지 스키마는 api DB 단일 소유(OntologyRepository), 전체 그래프(/graph)는 ai-agent(Neo4j) 프록시.
// B-2a 소스 플립: 과거 getOntology 프록시를 DB 읽기로 교체했다(getGraph 는 프록시 유지).
@Service
public class OntologyService {
  // ai-agent 무응답 시 서블릿 스레드 고갈 방지용 블로킹 타임아웃(getGraph 프록시 전용).
  private static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(40);

  private final WebClient webClient;
  private final OntologyRepository ontologyRepository;
  private final AuditLogService auditLogService;
  private final UserRepository userRepository;

  public OntologyService(
      @Value("${agent.url}") String agentUrl,
      @Value("${agent.internal-token}") String internalToken,
      OntologyRepository ontologyRepository,
      AuditLogService auditLogService,
      UserRepository userRepository) {
    this.webClient =
        WebClient.builder()
            .baseUrl(agentUrl)
            .defaultHeader("Authorization", "Internal " + internalToken)
            .build();
    this.ontologyRepository = ontologyRepository;
    this.auditLogService = auditLogService;
    this.userRepository = userRepository;
  }

  // 온톨로지 스키마 — api DB에서 직접 조회(더 이상 ai-agent 프록시 아님).
  public OntologyResponse getOntology() {
    return ontologyRepository.findOntology();
  }

  // 지식 모델 편집(B-2b 슬라이스 5-1) — full-document 교체 + schema_version 원자 증가.
  // 검증 실패는 IllegalArgumentException(→400), 버전 충돌은 리포지토리에서 IllegalStateException(→409).
  // 성공 시 audit 기록 후 갱신된 온톨로지를 재조회해 반환한다.
  public OntologyResponse updateOntology(UpdateOntologyRequest req) {
    validate(req);
    int newVersion = ontologyRepository.updateOntology(req);

    // 감사 로그 — 현재 인증 사용자(principal=Long userId). username은 best-effort 조회.
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.getPrincipal() instanceof Long userId) {
      userRepository
          .findById(userId)
          .ifPresent(
              u ->
                  auditLogService.log(
                      userId,
                      u.username(),
                      "ONTOLOGY_UPDATE",
                      "ontology",
                      "1",
                      "지식 모델 편집 — schema_version " + (newVersion - 1) + "→" + newVersion,
                      null,
                      null,
                      "SUCCESS",
                      null,
                      null));
    }

    return ontologyRepository.findOntology();
  }

  // 편집 페이로드 검증 — DB CHECK 제약보다 먼저 걸러 명확한 400을 반환한다(500/DataIntegrity 방지).
  private void validate(UpdateOntologyRequest req) {
    if (req.domain() == null || req.domain().isBlank()) {
      throw new IllegalArgumentException("domain은 비어 있을 수 없습니다.");
    }
    if (req.entities() == null || req.entities().isEmpty()) {
      throw new IllegalArgumentException("엔티티 타입은 최소 1개 이상이어야 합니다.");
    }
    if (req.relations() == null) {
      throw new IllegalArgumentException("relations는 null일 수 없습니다.");
    }
    Set<String> seenTypes = new HashSet<>();
    for (var e : req.entities()) {
      if (e.type() == null || e.type().isBlank()) {
        throw new IllegalArgumentException("엔티티 타입명은 비어 있을 수 없습니다.");
      }
      if (!seenTypes.add(e.type())) {
        throw new IllegalArgumentException("중복된 엔티티 타입명: " + e.type());
      }
      if (!"embedding".equals(e.resolution()) && !"exact".equals(e.resolution())) {
        throw new IllegalArgumentException(
            "resolution은 embedding 또는 exact여야 합니다: " + e.type());
      }
      if (e.properties() != null) {
        for (var p : e.properties()) {
          if (p.dataType() != null && !List.of("text", "number", "date").contains(p.dataType())) {
            throw new IllegalArgumentException(
                "데이터 타입은 text|number|date 중 하나여야 합니다: " + p.name());
          }
        }
      }
    }
  }

  // 전체 지식그래프 — ai-agent GET /agent/graph 프록시(Neo4j). 실패는 ExternalServiceException(502)로 매핑.
  public GraphResponse getGraph() {
    try {
      return webClient.get().uri("/agent/graph").retrieve().bodyToMono(GraphResponse.class).block(BLOCK_TIMEOUT);
    } catch (WebClientException e) {
      throw new ExternalServiceException("지식그래프 조회 중 ai-agent 호출 실패: " + e.getMessage(), e);
    }
  }
}
