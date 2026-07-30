package com.smartfirehub.ontology.service;

import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.global.exception.ExternalServiceException;
import com.smartfirehub.ontology.dto.CreateOntologyRequest;
import com.smartfirehub.ontology.dto.GraphResponse;
import com.smartfirehub.ontology.dto.OntologyResponse;
import com.smartfirehub.ontology.dto.OntologySummary;
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

    // 5-6: 타입 리네임은 이제 순수 DB 연산이다 — OntologyRepository가 entity_type_id를 UPDATE로
    // 보존하므로(리네임돼도 같은 행), ai-agent는 이 id 기반으로 Neo4j 노드 key를 구성해 리네임에도
    // key가 바뀌지 않는다. 5-5에서 있었던 "저장 직후 ai-agent에 Neo4j key 마이그레이션 동기 요청"은
    // 더 이상 필요 없어 제거했다(renames는 검증에만 쓰이고 ai-agent로 전달되지 않음).

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

  // id 스코프 조회.
  public OntologyResponse getById(long ontologyId) {
    return ontologyRepository.findById(ontologyId);
  }

  // 온톨로지 목록(요약).
  public List<OntologySummary> listOntologies() {
    return ontologyRepository.findAllSummaries();
  }

  // 신규 온톨로지 생성 — 검증(IllegalArgumentException→400) 후 삽입, 새 id 반환.
  public long createOntology(CreateOntologyRequest req) {
    validateCore(req.domain(), req.entities(), req.relations());
    long id = ontologyRepository.createOntology(req);

    // 감사 로그 — 신규 생성된 온톨로지의 실제 id를 entityId로 기록한다(레거시처럼 "1" 고정 아님).
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.getPrincipal() instanceof Long userId) {
      userRepository
          .findById(userId)
          .ifPresent(
              u ->
                  auditLogService.log(
                      userId,
                      u.username(),
                      "ONTOLOGY_CREATE",
                      "ontology",
                      String.valueOf(id),
                      "지식 모델 생성 — domain=" + req.domain(),
                      null,
                      null,
                      "SUCCESS",
                      null,
                      null));
    }

    return id;
  }

  // id 스코프 편집 — 검증 + 낙관적 잠금(버전 불일치 IllegalStateException→409).
  public OntologyResponse updateOntology(long ontologyId, UpdateOntologyRequest req) {
    validate(req);
    ontologyRepository.updateOntology(ontologyId, req);

    // 감사 로그 — 편집 대상 온톨로지의 실제 id(ontologyId)를 entityId로 기록한다.
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
                      String.valueOf(ontologyId),
                      "지식 모델 편집 — ontologyId=" + ontologyId,
                      null,
                      null,
                      "SUCCESS",
                      null,
                      null));
    }

    return ontologyRepository.findById(ontologyId);
  }

  // Neo4j 노드 예약 필드(loader.ts 모델 (:Entity{key,type,name,sourceChunkIds,schemaVersion}))와 겹치는
  // 속성명은 적재 시 SET n += props 가 노드 정체성 필드를 덮어쓰므로 편집 시점에 차단한다.
  // ai-agent loader.ts 의 동일 상수와 노드 모델이 바뀌면 함께 갱신해야 한다(서비스 경계상 공유 불가).
  private static final Set<String> RESERVED_PROPERTY_NAMES =
      Set.of("key", "type", "name", "sourceChunkIds", "schemaVersion");

  // 온톨로지 본문 공통 검증(생성/편집 공용) — domain, entity 타입, resolution, property, relation 참조 무결성.
  private void validateCore(
      String domain,
      List<OntologyResponse.EntityType> entities,
      List<OntologyResponse.Triple> relations) {
    if (domain == null || domain.isBlank()) {
      throw new IllegalArgumentException("domain은 비어 있을 수 없습니다.");
    }
    if (entities == null || entities.isEmpty()) {
      throw new IllegalArgumentException("엔티티 타입은 최소 1개 이상이어야 합니다.");
    }
    if (relations == null) {
      throw new IllegalArgumentException("relations는 null일 수 없습니다.");
    }
    Set<String> seenTypes = new HashSet<>();
    for (var e : entities) {
      if (e.type() == null || e.type().isBlank()) {
        throw new IllegalArgumentException("엔티티 타입명은 비어 있을 수 없습니다.");
      }
      if (!seenTypes.add(e.type())) {
        throw new IllegalArgumentException("중복된 엔티티 타입명: " + e.type());
      }
      if (!"embedding".equals(e.resolution()) && !"exact".equals(e.resolution())) {
        throw new IllegalArgumentException("resolution은 embedding 또는 exact여야 합니다: " + e.type());
      }
      if (e.properties() != null) {
        Set<String> seenPropNames = new HashSet<>();
        for (var p : e.properties()) {
          // blank 검사를 예약어/중복보다 먼저 둔다 — 이름이 빈 속성이 2개면 ''끼리 충돌해
          // "중복된 속성명"으로 오진단되고(실제 원인은 미입력), null이면 Set.of#contains가 NPE를 던져 500이 된다.
          if (p.name() == null || p.name().isBlank()) {
            throw new IllegalArgumentException("속성명은 비어 있을 수 없습니다: " + e.type());
          }
          if (RESERVED_PROPERTY_NAMES.contains(p.name())) {
            throw new IllegalArgumentException("예약어는 속성명으로 쓸 수 없습니다: " + p.name());
          }
          if (!seenPropNames.add(p.name())) {
            throw new IllegalArgumentException("중복된 속성명(" + e.type() + "): " + p.name());
          }
          if (p.dataType() != null && !List.of("text", "number", "date").contains(p.dataType())) {
            throw new IllegalArgumentException("데이터 타입은 text|number|date 중 하나여야 합니다: " + p.name());
          }
        }
      }
    }
    Set<String> seenTriples = new HashSet<>();
    for (var r : relations) {
      // 관계명 blank도 중복(tripleKey) 검사보다 먼저 — 빈 관계명 2건은 tripleKey가 같아
      // "중복된 관계"로 오진단된다. 이름 없는 관계는 LLM 추출·표 투영이 참조할 수 없어 무의미하다.
      if (r.relation() == null || r.relation().isBlank()) {
        throw new IllegalArgumentException(
            "관계명은 비어 있을 수 없습니다: " + r.subject() + " → " + r.object());
      }
      if (!seenTypes.contains(r.subject())) {
        throw new IllegalArgumentException("관계가 존재하지 않는 엔티티 타입을 참조합니다(subject): " + r.subject());
      }
      if (!seenTypes.contains(r.object())) {
        throw new IllegalArgumentException("관계가 존재하지 않는 엔티티 타입을 참조합니다(object): " + r.object());
      }
      String tripleKey = r.subject() + "|" + r.relation() + "|" + r.object();
      if (!seenTriples.add(tripleKey)) {
        throw new IllegalArgumentException("중복된 관계: " + tripleKey);
      }
    }
  }

  // 편집 페이로드 검증 — DB CHECK/UNIQUE 제약보다 먼저 걸러 명확한 400을 반환한다(500/DataIntegrity 방지).
  private void validate(UpdateOntologyRequest req) {
    validateCore(req.domain(), req.entities(), req.relations());

    Set<String> seenTypes = new HashSet<>();
    for (var e : req.entities()) {
      seenTypes.add(e.type());
    }

    // 타입 리네임(5-5) 무결성 — to는 최종 entities에 실존해야 하고, from은 리네임돼 사라졌어야 하므로
    // 최종 entities에 존재하면 안 된다(잘못된 rename 의도가 Neo4j 마이그레이션으로 새는 것을 방지).
    // to 중복도 금지한다 — 리포지토리가 renames를 to→from HashMap으로 뒤집기 때문에(같은 to의 앞 항목이
    // 덮어써짐) 덮어써진 from의 기존 행이 "매칭 안 된 타입"으로 판정돼 DELETE된다. 그 행의 entity_type_id가
    // 사라지면 이 id를 key로 삼는 Neo4j 노드 연결도 끊긴다(데이터 소실 경로). 검증이 유일한 방어선이다.
    Set<String> seenFroms = new HashSet<>();
    Set<String> seenTos = new HashSet<>();
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
      if (!seenTos.add(rename.to())) {
        throw new IllegalArgumentException("중복된 타입 리네임(to): " + rename.to());
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
