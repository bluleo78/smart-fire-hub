package com.smartfirehub.ontology.service;

import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.global.exception.ExternalServiceException;
import com.smartfirehub.ontology.OntologyRules;
import com.smartfirehub.ontology.dto.CreateOntologyRequest;
import com.smartfirehub.ontology.dto.GraphResponse;
import com.smartfirehub.ontology.dto.OntologyResponse;
import com.smartfirehub.ontology.dto.OntologySummary;
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

  // 온톨로지 생명주기 상태 전체 집합 — listOntologies(필터 검증)와 createOntology(status 검증)가
  // 각자 다른 형태(List.contains vs 연쇄 equals)로 같은 판정을 중복하던 것을 하나로 통일했다.
  private static final Set<String> VALID_STATUSES = Set.of("draft", "active", "archived");

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

  // id 스코프 조회.
  public OntologyResponse getById(long ontologyId) {
    return ontologyRepository.findById(ontologyId);
  }

  // 온톨로지 목록(요약).
  // statusParam: null 또는 미지정 → active만(바인딩 후보). "all" → 전체. 그 외는 해당 상태만.
  // 기본값을 active로 둔 이유: 이 목록의 주 소비자가 "어디에 연결할까"를 고르는 화면이기 때문이다.
  // 전체가 필요한 관리 화면만 명시적으로 all을 넘긴다.
  public List<OntologySummary> listOntologies(String statusParam) {
    String filter = (statusParam == null || statusParam.isBlank()) ? "active" : statusParam;
    List<OntologySummary> summaries;
    if ("all".equals(filter)) {
      summaries = ontologyRepository.findAllSummaries((String) null);
    } else {
      if (!VALID_STATUSES.contains(filter)) {
        throw new IllegalArgumentException("알 수 없는 상태입니다: " + filter);
      }
      summaries = ontologyRepository.findAllSummaries(filter);
    }
    // isDefault는 리포지토리가 모르는 서비스 판정이다 — "기본 온톨로지"의 기준(현재는 id=1)이 바뀌어도
    // 프론트가 DEFAULT_ONTOLOGY_ID를 따로 들고 있지 않도록 여기서 계산해 채운다.
    return summaries.stream().map(this::withDefaultFlag).toList();
  }

  private OntologySummary withDefaultFlag(OntologySummary s) {
    return new OntologySummary(
        s.id(), s.domain(), s.schemaVersion(), s.status(), s.entityCount(), s.datasetCount(),
        s.updatedAt(), s.id() == DEFAULT_ONTOLOGY_ID);
  }

  // 하위호환 — 무인자 호출은 기본값(active)과 동일하게 동작한다.
  // 주의: 이 서비스 메서드의 "무인자"는 active-only를 뜻하고, OntologyRepository의 무인자
  // findAllSummaries()는 전체를 뜻한다 — 계층별로 무인자 기본값의 의미가 다르니 혼동하지 말 것.
  public List<OntologySummary> listOntologies() {
    return listOntologies(null);
  }

  // 신규 온톨로지 생성 — 검증(IllegalArgumentException→400) 후 삽입, 새 id 반환.
  public long createOntology(CreateOntologyRequest req) {
    // 오타 등 알 수 없는 status 문자열이 통과하면 "active".equals(status)가 false가 되어 완전성
    // 게이트를 조용히 건너뛰고, DB CHECK(status) 제약에서 500으로 터진다 — 여기서 400으로 막는다.
    // VALID_STATUSES는 Set.of() 기반이라 contains(null)이 NPE를 던진다 — 이전 코드(String.equals 연쇄)는
    // null을 안전하게 "알 수 없는 상태"로 처리했으므로, 그 동작을 유지하려면 null을 먼저 걸러야 한다.
    if (req.status() == null || !VALID_STATUSES.contains(req.status())) {
      throw new IllegalArgumentException("알 수 없는 상태입니다: " + req.status());
    }
    // 생성 시점에 archived를 지정하는 것은 의미가 없다 — 은퇴는 운영을 마친 뒤의 상태 전이다.
    if ("archived".equals(req.status())) {
      throw new IllegalArgumentException("archived 상태로는 온톨로지를 생성할 수 없습니다.");
    }
    // active로 만들 때만 완전성(엔티티 ≥1)까지 본다. draft는 빈 껍데기 생성을 허용한다.
    validateCore(req.domain(), req.entities(), req.relations(), "active".equals(req.status()));

    // 도메인 중복 사전 검사 — 검사 없이 부분 유니크 인덱스(V79)까지 내려가면 DataIntegrityViolationException이
    // "Data integrity violation: duplicate entry" 라는 영문 DB 문구로 번역돼(#GlobalExceptionHandler)
    // 생성 다이얼로그 도메인 필드 아래에 그대로 노출된다. 여기서 한국어 409로 먼저 막는다.
    // 인덱스가 archived를 제외하므로 이 검사도 archived를 빼고 살아있는 것만 본다 — 그래야 은퇴한 온톨로지와
    // 같은 이름의 후속 온톨로지를 만들 수 있다는 전제가 여기서도 깨지지 않는다.
    if (ontologyRepository.existsLiveDomain(req.domain())) {
      throw new IllegalStateException("이미 같은 도메인의 온톨로지가 있습니다: " + req.domain());
    }

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

  // 문서 적재 파이프라인이 단수 GET /ontology(하드코딩 findById(1L), 상태 미검사)로 의존하는 기본 온톨로지.
  // 삭제뿐 아니라 은퇴도 막아야 "id=1은 항상 active"가 가정이 아닌 강제가 된다.
  private static final long DEFAULT_ONTOLOGY_ID = 1L;

  // 상태 전이 판정. 허용: draft→active, active→archived, archived→active. 그 외 상태 변경은 거부.
  // 거부는 IllegalStateException(→409) — 잘못된 입력(400)이 아니라 현재 상태와의 충돌이다.
  // 호출 전제(changeStatus가 보장): to는 유효한 상태이고 from과 다르다.
  private void assertTransitionAllowed(long ontologyId, String from, String to) {
    if ("draft".equals(to)) {
      throw new IllegalStateException(
          "이미 사용을 시작한 온톨로지는 초안으로 되돌릴 수 없습니다. 은퇴(archived)를 사용하세요.");
    }
    if ("archived".equals(to)) {
      if (!"active".equals(from)) {
        throw new IllegalStateException("운영 중인 온톨로지만 은퇴시킬 수 있습니다. 현재 상태: " + from);
      }
      if (ontologyId == DEFAULT_ONTOLOGY_ID) {
        throw new IllegalStateException("기본 온톨로지는 은퇴시킬 수 없습니다. 문서 적재가 이 온톨로지에 의존합니다.");
      }
      return;
    }
    // 남은 경우는 to="active" — draft→active(활성화), archived→active(복귀) 모두 허용.
  }

  // 상태 전이 전용 경로 — 스키마를 건드리지 않으므로 schema_version을 올리지 않고, 단일 UPDATE라
  // 원자적이며, 감사 로그도 편집이 아닌 상태 변경으로 남는다. 전이는 이 메서드로만 가능하다
  // (PUT은 더 이상 status를 받지 않는다) — 두 경로를 남겨두면 낡은 경로로 위 문제들이 되살아난다.
  public void changeStatus(long ontologyId, String target) {
    if (target == null || !VALID_STATUSES.contains(target)) {
      throw new IllegalArgumentException("알 수 없는 상태입니다: " + target);
    }
    // 존재하지 않는 id는 여기서 400으로 떨어진다.
    String current = ontologyRepository.findStatusById(ontologyId);
    if (target.equals(current)) {
      return; // 멱등 — 같은 상태로의 요청은 성공으로 흘려보낸다.
    }
    assertTransitionAllowed(ontologyId, current, target);

    if ("active".equals(target)) {
      // active 진입은 완전성 게이트다. PUT 경로에서는 요청 본문을 검사했지만 이 경로엔 본문이 없다 —
      // 저장된 스키마를 읽어 검사하지 않으면 엔티티 0개짜리 빈 초안이 그대로 활성화된다.
      OntologyResponse persisted = ontologyRepository.findById(ontologyId);
      validateCore(persisted.domain(), persisted.entities(), persisted.relations(), true);

      // 도메인 선점 검사는 archived→active(복귀)에만 필요하다. 부분 유니크 인덱스가 archived를 빼므로
      // 은퇴 중에 같은 도메인의 후속 온톨로지가 생겼을 수 있고, 그대로 복귀시키면 인덱스 위반이
      // 영문 DB 문구로 새어나간다. draft 행은 이미 인덱스 안에 있어 자기 자신과 충돌하므로 검사하지 않는다.
      // 문구가 "운영 중"이 아닌 이유: 인덱스는 draft도 포함하므로 같은 도메인의 초안이 있어도 충돌한다.
      // 실제로 막히는 조건 그대로("살아 있는 온톨로지가 있다")를 말해야 사용자가 초안을 지우거나
      // 이름을 바꾸는 등 올바른 다음 행동을 고를 수 있다.
      if ("archived".equals(current) && ontologyRepository.existsLiveDomain(persisted.domain())) {
        throw new IllegalStateException(
            "같은 도메인의 온톨로지가 이미 있어 복귀시킬 수 없습니다(초안 포함): " + persisted.domain());
      }
    }

    ontologyRepository.updateStatus(ontologyId, target);

    // 감사 로그 — 스키마 편집(ONTOLOGY_UPDATE)과 구분되는 별도 액션으로 남긴다.
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.getPrincipal() instanceof Long userId) {
      userRepository
          .findById(userId)
          .ifPresent(
              u ->
                  auditLogService.log(
                      userId,
                      u.username(),
                      "ONTOLOGY_STATUS_CHANGE",
                      "ontology",
                      String.valueOf(ontologyId),
                      "지식 모델 상태 변경 — " + current + "→" + target,
                      null,
                      null,
                      "SUCCESS",
                      null,
                      null));
    }
  }

  // 온톨로지 삭제. 거부 사유는 두 가지뿐이다 — 참조 중이거나, 기본 온톨로지이거나.
  // 상태는 사유가 아니다: 참조 없는 active를 못 지우면 잘못 활성화한 온톨로지를 회수할 수 없다.
  // 참조가 있어 지울 수 없는 것은 은퇴(archived)로 물러나게 한다 — 삭제와 은퇴가 짝을 이뤄야
  // 막다른 길이 생기지 않는다.
  public void deleteOntology(long ontologyId) {
    if (ontologyId == DEFAULT_ONTOLOGY_ID) {
      throw new IllegalStateException("기본 온톨로지는 삭제할 수 없습니다. 문서 적재가 이 온톨로지에 의존합니다.");
    }
    // 존재 확인 — 없으면 400(IllegalArgumentException)으로 떨어진다.
    ontologyRepository.findStatusById(ontologyId);

    int references = ontologyRepository.countReferences(ontologyId);
    if (references > 0) {
      throw new IllegalStateException(
          references + "개 데이터셋이 사용 중입니다. 은퇴(archived)를 사용하세요.");
    }
    ontologyRepository.deleteOntology(ontologyId);

    // 감사 로그 — 삭제된 온톨로지의 id를 entityId로 기록한다.
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.getPrincipal() instanceof Long userId) {
      userRepository
          .findById(userId)
          .ifPresent(
              u ->
                  auditLogService.log(
                      userId,
                      u.username(),
                      "ONTOLOGY_DELETE",
                      "ontology",
                      String.valueOf(ontologyId),
                      "지식 모델 삭제 — ontologyId=" + ontologyId,
                      null,
                      null,
                      "SUCCESS",
                      null,
                      null));
    }
  }

  // 온톨로지 본문 공통 검증 — domain, entity 타입, resolution, property, relation 참조 무결성.
  // requireComplete=false(draft)면 "엔티티 최소 1개" 같은 완전성 규칙을 건너뛰고 형식 규칙만 본다.
  // draft는 정의상 미완성이고, 완전성은 active로 전이할 때 게이트로 검사한다.
  // 요소 하나로 판정 가능한 규칙(타입명·resolution·속성·관계 형식)은 OntologyRules에 위임한다 —
  // element 패키지(요소 단위 편집)와 문구·판정을 공유해야 프론트 e2e의 문구 단언이 두 경로에서
  // 동시에 맞는다. 여기 남는 것은 여러 요소를 한꺼번에 봐야 하는 문서 단위 불변식뿐이다
  // (완전성, 목록 전체 중복 스캔, 관계의 엔티티 참조 무결성).
  // (S2 Task 7) 전체 스키마 교체 PUT(updateOntology)이 요소 단위 편집 API로 대체되며 삭제됐지만,
  // 이 메서드 자체는 살아 있다 — createOntology(생성)와 changeStatus(active 전이 게이트)가 여전히
  // 문서 전체를 한 번에 검사해야 하기 때문이다. 같은 두 불변식(참조 무결성·완전성)은 요소 단위
  // 편집 경로에서도 각각 OntologyElementService.addRelation(requireType)과
  // deleteEntityType(countEntityTypes 가드)로 개별 보장된다(요소 단위 테스트: RelationElementTest,
  // EntityTypeElementTest#active_온톨로지의_마지막_타입은_삭제할_수_없다).
  private void validateCore(
      String domain,
      List<OntologyResponse.EntityType> entities,
      List<OntologyResponse.Triple> relations,
      boolean requireComplete) {
    if (domain == null || domain.isBlank()) {
      throw new IllegalArgumentException("domain은 비어 있을 수 없습니다.");
    }
    if (entities == null) {
      throw new IllegalArgumentException("entities는 null일 수 없습니다.");
    }
    if (requireComplete && entities.isEmpty()) {
      throw new IllegalArgumentException("엔티티 타입은 최소 1개 이상이어야 합니다.");
    }
    if (relations == null) {
      throw new IllegalArgumentException("relations는 null일 수 없습니다.");
    }
    Set<String> seenTypes = new HashSet<>();
    for (var e : entities) {
      // blank 검사만 먼저 부른다 — 중복 판정이 원래 위치(blank 다음, resolution 앞)를 지켜야
      // 두 조건이 겹친 요청의 문구가 리팩터링 전과 같다. validateEntityTypeCommon 안에서 blank를
      // 다시 검사하지만 이미 통과한 값이라 no-op이다.
      OntologyRules.validateEntityTypeName(e.type());
      if (!seenTypes.add(e.type())) {
        throw OntologyRules.duplicateEntityTypeName(e.type());
      }
      OntologyRules.validateEntityTypeCommon(e.type(), e.description(), e.naming(), e.resolution());
      if (e.properties() != null) {
        Set<String> seenPropNames = new HashSet<>();
        for (var p : e.properties()) {
          OntologyRules.validatePropertyName(p.name(), e.type());
          if (!seenPropNames.add(p.name())) {
            throw OntologyRules.duplicatePropertyName(e.type(), p.name());
          }
          OntologyRules.validatePropertyCommon(p.description(), p.dataType(), e.type(), p.name());
        }
      }
    }
    Set<String> seenTriples = new HashSet<>();
    for (var r : relations) {
      // 원본 순서(관계명 blank → subject 존재 → object 존재 → description null)를 그대로 지킨다 —
      // subject/object 참조 무결성은 여러 엔티티를 함께 봐야 하는 문서 단위 불변식이라 여기 남아 있고,
      // 그래서 validateRelationCommon(이름+description을 한 번에)을 통으로 못 쓰고 나눠서 부른다.
      OntologyRules.validateRelationName(r.relation(), r.subject(), r.object());
      if (!seenTypes.contains(r.subject())) {
        throw new IllegalArgumentException("관계가 존재하지 않는 엔티티 타입을 참조합니다(subject): " + r.subject());
      }
      if (!seenTypes.contains(r.object())) {
        throw new IllegalArgumentException("관계가 존재하지 않는 엔티티 타입을 참조합니다(object): " + r.object());
      }
      OntologyRules.validateRelationDescription(r.description(), r.subject(), r.object());
      String tripleKey = r.subject() + "|" + r.relation() + "|" + r.object();
      if (!seenTriples.add(tripleKey)) {
        throw OntologyRules.duplicateTriple(r.subject(), r.relation(), r.object());
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
