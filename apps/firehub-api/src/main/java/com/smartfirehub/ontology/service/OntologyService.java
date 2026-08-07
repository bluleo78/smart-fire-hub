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

  // id 스코프 스키마 편집 — 검증 + 낙관적 잠금(버전 불일치 IllegalStateException→409).
  // 상태 전이는 여기서 다루지 않는다(changeStatus 전용).
  public OntologyResponse updateOntology(long ontologyId, UpdateOntologyRequest req) {
    String current = ontologyRepository.findStatusById(ontologyId);

    // 은퇴한 온톨로지의 스키마는 고칠 수 없다 — 그 스키마로 이미 적재된 데이터와 어긋나기 때문.
    // 고쳐야 한다면 먼저 복귀(archived→active)시킨다.
    if ("archived".equals(current)) {
      throw new IllegalStateException("은퇴한 온톨로지는 편집할 수 없습니다. 먼저 복귀시키세요.");
    }

    // active 온톨로지의 편집은 완전성까지 지켜야 한다 — 이미 운영 중인 스키마를 빈 껍데기로 만들 수 없다.
    // draft는 미완성인 채로 저장할 수 있다(완전성은 활성화 시점에 changeStatus가 검사한다).
    validate(req, "active".equals(current));

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

  // Neo4j 노드 예약 필드(loader.ts 모델 (:Entity{key,type,name,sourceChunkIds,schemaVersion}))와 겹치는
  // 속성명은 적재 시 SET n += props 가 노드 정체성 필드를 덮어쓰므로 편집 시점에 차단한다.
  // ai-agent loader.ts 의 동일 상수와 노드 모델이 바뀌면 함께 갱신해야 한다(서비스 경계상 공유 불가).
  private static final Set<String> RESERVED_PROPERTY_NAMES =
      Set.of("key", "type", "name", "sourceChunkIds", "schemaVersion");

  // 온톨로지 본문 공통 검증(생성/편집 공용) — domain, entity 타입, resolution, property, relation 참조 무결성.
  // requireComplete=false(draft)면 "엔티티 최소 1개" 같은 완전성 규칙을 건너뛰고 형식 규칙만 본다.
  // draft는 정의상 미완성이고, 완전성은 active로 전이할 때 게이트로 검사한다.
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
      if (e.type() == null || e.type().isBlank()) {
        throw new IllegalArgumentException("엔티티 타입명은 비어 있을 수 없습니다.");
      }
      if (!seenTypes.add(e.type())) {
        throw new IllegalArgumentException("중복된 엔티티 타입명: " + e.type());
      }
      if (!"embedding".equals(e.resolution()) && !"exact".equals(e.resolution())) {
        throw new IllegalArgumentException("resolution은 embedding 또는 exact여야 합니다: " + e.type());
      }
      // description/naming 컬럼은 NOT NULL(기본값 없음)이라 null이 그대로 INSERT되면 제약 위반으로
      // 500이 새어나간다(#305). null만 막고 빈 문자열은 허용한다 — 컬럼 제약이 NOT NULL일 뿐이고
      // 실제로 설명을 비워 저장한 기존 데이터가 정상 왕복(GET→PUT)돼야 하기 때문이다.
      if (e.description() == null) {
        throw new IllegalArgumentException("엔티티 설명(description)은 null일 수 없습니다: " + e.type());
      }
      if (e.naming() == null) {
        throw new IllegalArgumentException("엔티티 명명 규칙(naming)은 null일 수 없습니다: " + e.type());
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
          // 속성 description도 NOT NULL 컬럼 — 엔티티와 동일하게 null만 차단한다(#305).
          if (p.description() == null) {
            throw new IllegalArgumentException(
                "속성 설명(description)은 null일 수 없습니다(" + e.type() + "): " + p.name());
          }
          // dataType은 NOT NULL + CHECK(text|number|date)라 null은 애초에 저장 불가하다.
          // 기존 코드가 null을 통과시켜 제약 위반 500이 났으므로 null도 400으로 거른다(#305).
          if (p.dataType() == null || !List.of("text", "number", "date").contains(p.dataType())) {
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
      // 관계 description도 NOT NULL 컬럼 — 엔티티/속성과 동일하게 null만 차단한다(#305).
      if (r.description() == null) {
        throw new IllegalArgumentException(
            "관계 설명(description)은 null일 수 없습니다: " + r.subject() + " → " + r.object());
      }
      String tripleKey = r.subject() + "|" + r.relation() + "|" + r.object();
      if (!seenTriples.add(tripleKey)) {
        throw new IllegalArgumentException("중복된 관계: " + tripleKey);
      }
    }
  }

  // 편집 페이로드 검증 — DB CHECK/UNIQUE 제약보다 먼저 걸러 명확한 400을 반환한다(500/DataIntegrity 방지).
  private void validate(UpdateOntologyRequest req) {
    validate(req, true);
  }

  // requireComplete=false는 draft를 유지한 채(활성화 없이) 스키마만 편집하는 경로용 — 완전성 게이트 없이
  // 형식 검증만 적용한다. id 스코프 updateOntology가 결과 상태에 따라 이 플래그를 결정한다.
  private void validate(UpdateOntologyRequest req, boolean requireComplete) {
    validateCore(req.domain(), req.entities(), req.relations(), requireComplete);

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
