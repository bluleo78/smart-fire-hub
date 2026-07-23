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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientException;

// 온톨로지 스키마는 api DB 단일 소유(OntologyRepository), 전체 그래프(/graph)는 ai-agent(Neo4j) 프록시.
// B-2a 소스 플립: 과거 getOntology 프록시를 DB 읽기로 교체했다(getGraph 는 프록시 유지).
@Slf4j
@Service
public class OntologyService {
  // ai-agent 무응답 시 서블릿 스레드 고갈 방지용 블로킹 타임아웃(getGraph 프록시·리네임 마이그레이션 공용).
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

    // 5-5: 타입 리네임 — DB 커밋(위)은 이미 완료된 소스오브트루스. Neo4j 노드 key/type은 같은 요청
    // 안에서 동기적으로 마이그레이션을 시도하되(즉시 일관성), Postgres/Neo4j는 별개 저장소라 진짜
    // 2PC는 불가능하므로 best-effort로 처리한다 — 실패해도 전체 응답은 성공 처리한다. 실패한 타입의
    // 데이터셋은 schema_version 불일치로 기존 stale 판정(dataset_graph_ingest)에 자연히 걸려
    // 재적재(graphrag_ingest)가 새 타입명 기준 조회는 복구한다(5-4 인프라가 안전망) — 다만 old-type
    // 노드 자체는 재적재로 자동 삭제되지 않고 고아로 남을 수 있어 완전한 정리는 아니다.
    for (var rename : req.renames()) {
      try {
        webClient
            .post()
            .uri("/agent/graph/rename-type")
            .bodyValue(new RenameTypeBody(rename.from(), rename.to()))
            .retrieve()
            .bodyToMono(Void.class)
            .block(BLOCK_TIMEOUT);
      } catch (WebClientException e) {
        log.warn(
            "타입 리네임 Neo4j 마이그레이션 실패(무시하고 계속, DB는 이미 커밋됨): {} → {}: {}",
            rename.from(), rename.to(), e.getMessage());
      }
    }

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

  // ai-agent POST /agent/graph/rename-type 요청 바디(oldType/newType — TypeRename의 from/to와 필드명 다름).
  private record RenameTypeBody(String oldType, String newType) {}

  // Neo4j 노드 예약 필드(loader.ts 모델 (:Entity{key,type,name,sourceChunkIds,schemaVersion}))와 겹치는
  // 속성명은 적재 시 SET n += props 가 노드 정체성 필드를 덮어쓰므로 편집 시점에 차단한다.
  // ai-agent loader.ts 의 동일 상수와 노드 모델이 바뀌면 함께 갱신해야 한다(서비스 경계상 공유 불가).
  private static final Set<String> RESERVED_PROPERTY_NAMES =
      Set.of("key", "type", "name", "sourceChunkIds", "schemaVersion");

  // 편집 페이로드 검증 — DB CHECK/UNIQUE 제약보다 먼저 걸러 명확한 400을 반환한다(500/DataIntegrity 방지).
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
        Set<String> seenPropNames = new HashSet<>();
        for (var p : e.properties()) {
          if (RESERVED_PROPERTY_NAMES.contains(p.name())) {
            throw new IllegalArgumentException("예약어는 속성명으로 쓸 수 없습니다: " + p.name());
          }
          if (!seenPropNames.add(p.name())) {
            throw new IllegalArgumentException(
                "중복된 속성명(" + e.type() + "): " + p.name());
          }
          if (p.dataType() != null && !List.of("text", "number", "date").contains(p.dataType())) {
            throw new IllegalArgumentException(
                "데이터 타입은 text|number|date 중 하나여야 합니다: " + p.name());
          }
        }
      }
    }

    // 관계 참조 무결성 — subject/object는 위 entities 루프에서 확정된 타입 집합에 존재해야 한다.
    // 오탈자·삭제된 타입 참조를 DB 삽입 전에 400으로 차단(FK 없는 문자열 참조라 안 걸러지면 조용히 깨짐).
    Set<String> seenTriples = new HashSet<>();
    for (var r : req.relations()) {
      if (!seenTypes.contains(r.subject())) {
        throw new IllegalArgumentException(
            "관계가 존재하지 않는 엔티티 타입을 참조합니다(subject): " + r.subject());
      }
      if (!seenTypes.contains(r.object())) {
        throw new IllegalArgumentException(
            "관계가 존재하지 않는 엔티티 타입을 참조합니다(object): " + r.object());
      }
      String tripleKey = r.subject() + "|" + r.relation() + "|" + r.object();
      if (!seenTriples.add(tripleKey)) {
        throw new IllegalArgumentException("중복된 관계: " + tripleKey);
      }
    }

    // 타입 리네임(5-5) 무결성 — to는 최종 entities에 실존해야 하고, from은 리네임돼 사라졌어야 하므로
    // 최종 entities에 존재하면 안 된다(잘못된 rename 의도가 Neo4j 마이그레이션으로 새는 것을 방지).
    Set<String> seenFroms = new HashSet<>();
    for (var rename : req.renames()) {
      if (rename.from() == null || rename.from().isBlank() || rename.to() == null || rename.to().isBlank()) {
        throw new IllegalArgumentException("타입 리네임의 from/to는 비어 있을 수 없습니다.");
      }
      if (rename.from().equals(rename.to())) {
        throw new IllegalArgumentException("타입 리네임의 from과 to가 동일합니다: " + rename.from());
      }
      if (!seenTypes.contains(rename.to())) {
        throw new IllegalArgumentException(
            "타입 리네임의 to가 최종 엔티티 타입에 없습니다: " + rename.to() + " (from " + rename.from() + ")");
      }
      if (seenTypes.contains(rename.from())) {
        throw new IllegalArgumentException(
            "타입 리네임의 from이 여전히 엔티티 타입으로 남아 있습니다: " + rename.from());
      }
      if (!seenFroms.add(rename.from())) {
        throw new IllegalArgumentException("중복된 타입 리네임(from): " + rename.from());
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
