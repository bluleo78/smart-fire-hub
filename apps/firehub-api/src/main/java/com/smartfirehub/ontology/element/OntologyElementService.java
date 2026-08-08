package com.smartfirehub.ontology.element;

import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.ontology.OntologyRules;
import com.smartfirehub.ontology.dto.OntologyResponse;
import com.smartfirehub.ontology.element.dto.ElementDtos.CreateEntityTypeRequest;
import com.smartfirehub.ontology.element.dto.ElementDtos.EntityTypeDeletion;
import com.smartfirehub.ontology.element.dto.ElementDtos.EntityTypeMutation;
import com.smartfirehub.ontology.element.dto.ElementDtos.CreatePropertyRequest;
import com.smartfirehub.ontology.element.dto.ElementDtos.CreateRelationRequest;
import com.smartfirehub.ontology.element.dto.ElementDtos.PatchOntologyRequest;
import com.smartfirehub.ontology.element.dto.ElementDtos.PropertyMutation;
import com.smartfirehub.ontology.element.dto.ElementDtos.RelationMutation;
import com.smartfirehub.ontology.element.dto.ElementDtos.UpdateEntityTypeRequest;
import com.smartfirehub.ontology.element.dto.ElementDtos.UpdatePropertyRequest;
import com.smartfirehub.ontology.element.dto.ElementDtos.UpdateRelationRequest;
import com.smartfirehub.ontology.element.dto.ElementDtos.VersionOnly;
import com.smartfirehub.ontology.exception.OntologyElementNotFoundException;
import com.smartfirehub.ontology.repository.OntologyRepository;
import com.smartfirehub.user.repository.UserRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 지식 모델의 요소(타입·속성·관계) 단위 편집. 전체 스키마를 왕복시키던 원샷 PUT(OntologyService의
// updateOntology — S2 Task 7에서 삭제됨)과 달리 한 요소만 바꾸므로 낙관적 잠금(baseVersion/409)이
// 필요 없다 — 서로 다른 요소를 고치는 것은 충돌이 아니고, 같은 요소는 last-write-wins로 충분하다
// (편집자가 ADMIN 소수).
// 대신 모든 뮤테이션이 schema_version을 올려, 클라이언트가 "남이 바꿨는지"를 감지할 수 있게 한다.
@Service
@RequiredArgsConstructor
public class OntologyElementService {

  private final OntologyElementRepository elementRepository;
  private final OntologyRepository ontologyRepository;
  private final AuditLogService auditLogService;
  private final UserRepository userRepository;

  // 검증 규칙·상수·문구는 OntologyRules가 단일 소유한다 — 여기에 다시 선언하지 않는다.
  // 프론트 e2e가 문구로 단언하므로, 두 서비스가 같은 문구를 쓴다는 보장이 주석 규약이 아니라
  // 같은 코드를 부른다는 사실에서 나와야 한다.

  // 편집 가능 상태인지 확인하고 현재 status를 돌려준다.
  // archived 거부 사유: 그 스키마로 이미 적재된 데이터와 어긋나기 때문이며, 고치려면 먼저 복귀시켜야
  // 한다(전체 스키마 PUT 시절에도 같은 이유로 거부했다 — 이제 이 규칙의 유일한 소유자가 여기다).
  public String assertEditable(long ontologyId) {
    String status = ontologyRepository.findStatusById(ontologyId);
    if ("archived".equals(status)) {
      throw new IllegalStateException("은퇴한 온톨로지는 편집할 수 없습니다. 먼저 복귀시키세요.");
    }
    return status;
  }

  public int bumpVersion(long ontologyId) {
    return elementRepository.bumpVersion(ontologyId);
  }

  // 요소 단위 감사 로그. 기존 ONTOLOGY_UPDATE 한 줄로 뭉뚱그리던 것을 행위별로 남긴다 —
  // 자동 저장에서는 "누가 언제 무엇을 바꿨나"가 유일한 추적 수단이다.
  public void audit(String action, long ontologyId, String detail) {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !(auth.getPrincipal() instanceof Long userId)) return;
    userRepository.findById(userId).ifPresent(u ->
        auditLogService.log(userId, u.username(), action, "ontology",
            String.valueOf(ontologyId), detail, null, null, "SUCCESS", null, null));
  }

  @Transactional
  public VersionOnly patchDomain(long ontologyId, PatchOntologyRequest req) {
    assertEditable(ontologyId);
    if (req.domain() == null || req.domain().isBlank()) {
      throw new IllegalArgumentException("domain은 비어 있을 수 없습니다.");
    }
    // OntologyService.createOntology와 동일한 사전 검사 — 없으면 V79 부분 유니크 인덱스 위반이
    // 영문 "Data integrity violation: duplicate entry" 409로 새어나간다(createOntology 주석 참조).
    // 자기 자신의 현재 도메인으로 재제출(no-op 변경)하는 경우는 예외로 둔다 — existsLiveDomain은
    // 이 온톨로지 자신도 살아있으면 포함해서 세므로, 새 값이 현재 값과 같을 때 검사하면 항상
    // 자기 자신과 충돌해 정상적인 재제출까지 거부하게 된다.
    String currentDomain = ontologyRepository.findById(ontologyId).domain();
    if (!req.domain().equals(currentDomain) && ontologyRepository.existsLiveDomain(req.domain())) {
      throw new IllegalStateException("이미 같은 도메인의 온톨로지가 있습니다: " + req.domain());
    }
    elementRepository.updateDomain(ontologyId, req.domain());
    int version = bumpVersion(ontologyId);
    audit("ONTOLOGY_DOMAIN_UPDATE", ontologyId, "도메인 변경 — " + req.domain());
    return new VersionOnly(version);
  }

  @Transactional
  public EntityTypeMutation addEntityType(long ontologyId, CreateEntityTypeRequest req) {
    assertEditable(ontologyId);
    OntologyRules.validateEntityTypeCommon(req.type(), req.description(), req.naming(), req.resolution());
    if (elementRepository.findTypeNames(ontologyId).contains(req.type())) {
      throw OntologyRules.duplicateEntityTypeName(req.type());
    }
    long entityTypeId = elementRepository.insertEntityType(ontologyId, req);
    int version = bumpVersion(ontologyId);
    audit("ONTOLOGY_TYPE_ADD", ontologyId, "엔티티 타입 추가 — " + req.type());
    return new EntityTypeMutation(version, findEntityType(ontologyId, entityTypeId));
  }

  @Transactional
  public EntityTypeMutation updateEntityType(long ontologyId, long entityTypeId, UpdateEntityTypeRequest req) {
    assertEditable(ontologyId);
    String currentName = requireType(ontologyId, entityTypeId);

    // null = 변경 없음. 값이 온 필드만 검증한다.
    if (req.type() != null) {
      OntologyRules.validateEntityTypeName(req.type());
      // 자기 자신으로의 "리네임"은 중복이 아니다.
      if (!req.type().equals(currentName) && elementRepository.findTypeNames(ontologyId).contains(req.type())) {
        throw OntologyRules.duplicateEntityTypeName(req.type());
      }
    }
    if (req.resolution() != null) {
      OntologyRules.validateResolution(req.resolution(), req.type() == null ? currentName : req.type());
    }

    elementRepository.updateEntityType(entityTypeId, req);
    int version = bumpVersion(ontologyId);
    audit("ONTOLOGY_TYPE_UPDATE", ontologyId,
        "엔티티 타입 수정 — " + currentName + (req.type() == null ? "" : " → " + req.type()));
    return new EntityTypeMutation(version, findEntityType(ontologyId, entityTypeId));
  }

  @Transactional
  public EntityTypeDeletion deleteEntityType(long ontologyId, long entityTypeId) {
    String status = assertEditable(ontologyId);
    String name = requireType(ontologyId, entityTypeId);

    // active의 완전성 게이트 — 운영 중인 스키마를 빈 껍데기로 만들 수 없다.
    // draft는 미완성인 채로 둘 수 있다(완전성은 활성화 시점에 검사된다).
    if ("active".equals(status) && elementRepository.countEntityTypes(ontologyId) <= 1) {
      throw new IllegalArgumentException("엔티티 타입은 최소 1개 이상이어야 합니다.");
    }

    // FK CASCADE가 지우기 "전에" 무엇이 함께 사라지는지 확보한다.
    List<Long> deletedRelationIds = elementRepository.findRelationIdsTouching(entityTypeId);
    elementRepository.deleteEntityType(entityTypeId);
    int version = bumpVersion(ontologyId);
    audit("ONTOLOGY_TYPE_DELETE", ontologyId,
        "엔티티 타입 삭제 — " + name + " (참조 관계 " + deletedRelationIds.size() + "건 함께 삭제)");
    return new EntityTypeDeletion(version, deletedRelationIds);
  }

  // 대상 타입이 이 온톨로지에 실제로 있는지 확인하고 현재 이름을 돌려준다.
  // 없으면 404다 — 낡은 화면이 이미 사라진 요소를 지목한 경우이고, 클라이언트는 이 코드를 보고
  // 사용자에게 에러를 띄우는 대신 조용히 온톨로지를 재조회해 화면을 맞춘다(400이면 구분이 안 된다).
  // 다른 온톨로지에 속한 id도 같은 경로다 — 이 온톨로지 기준으로는 존재하지 않는 것이 맞다.
  private String requireType(long ontologyId, long entityTypeId) {
    String name = elementRepository.findTypeName(ontologyId, entityTypeId);
    if (name == null) {
      throw new OntologyElementNotFoundException("존재하지 않는 엔티티 타입입니다: " + entityTypeId);
    }
    return name;
  }

  private OntologyResponse.EntityType findEntityType(long ontologyId, long entityTypeId) {
    return ontologyRepository.findById(ontologyId).entities().stream()
        .filter(e -> entityTypeId == e.id())
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("방금 저장한 엔티티 타입을 찾을 수 없습니다: " + entityTypeId));
  }

  @Transactional
  public PropertyMutation addProperty(long ontologyId, long entityTypeId, CreatePropertyRequest req) {
    assertEditable(ontologyId);
    String typeName = requireType(ontologyId, entityTypeId);
    validatePropertyName(req.name(), typeName, elementRepository.findPropertyNames(entityTypeId), null);
    OntologyRules.validatePropertyCommon(req.description(), req.dataType(), typeName, req.name());

    long propertyId = elementRepository.insertProperty(entityTypeId, req);
    int version = bumpVersion(ontologyId);
    audit("ONTOLOGY_PROPERTY_ADD", ontologyId, "속성 추가 — " + typeName + "." + req.name());
    return new PropertyMutation(version, findProperty(ontologyId, entityTypeId, propertyId));
  }

  @Transactional
  public PropertyMutation updateProperty(
      long ontologyId, long entityTypeId, long propertyId, UpdatePropertyRequest req) {
    assertEditable(ontologyId);
    String typeName = requireType(ontologyId, entityTypeId);
    String currentName = requireProperty(entityTypeId, propertyId);

    if (req.name() != null) {
      validatePropertyName(req.name(), typeName, elementRepository.findPropertyNames(entityTypeId), currentName);
    }
    if (req.dataType() != null && !OntologyRules.DATA_TYPES.contains(req.dataType())) {
      throw OntologyRules.invalidDataType(req.name() == null ? currentName : req.name());
    }

    elementRepository.updateProperty(propertyId, req);
    int version = bumpVersion(ontologyId);
    audit("ONTOLOGY_PROPERTY_UPDATE", ontologyId, "속성 수정 — " + typeName + "." + currentName);
    return new PropertyMutation(version, findProperty(ontologyId, entityTypeId, propertyId));
  }

  @Transactional
  public VersionOnly deleteProperty(long ontologyId, long entityTypeId, long propertyId) {
    assertEditable(ontologyId);
    String typeName = requireType(ontologyId, entityTypeId);
    String name = requireProperty(entityTypeId, propertyId);

    elementRepository.deleteProperty(propertyId);
    int version = bumpVersion(ontologyId);
    audit("ONTOLOGY_PROPERTY_DELETE", ontologyId, "속성 삭제 — " + typeName + "." + name);
    return new VersionOnly(version);
  }

  private String requireProperty(long entityTypeId, long propertyId) {
    String name = elementRepository.findPropertyName(entityTypeId, propertyId);
    if (name == null) {
      throw new OntologyElementNotFoundException("존재하지 않는 속성입니다: " + propertyId);
    }
    return name;
  }

  // blank → 예약어 → 중복 순서를 지킨다(#302). 순서를 바꾸면 빈 이름이 "중복"으로 오진단된다.
  // 앞 두 단계는 OntologyRules가 소유하고(문구 단일 소유), 중복만 이 스코프에서 판정한다 —
  // "무엇이 중복인가"는 대상 엔티티 타입을 아는 호출부만 답할 수 있기 때문이다.
  // excludeName은 수정 시 자기 자신을 중복 후보에서 빼기 위한 것이다.
  private void validatePropertyName(String name, String typeName, List<String> existing, String excludeName) {
    OntologyRules.validatePropertyName(name, typeName);
    if (!name.equals(excludeName) && existing.contains(name)) {
      throw OntologyRules.duplicatePropertyName(typeName, name);
    }
  }

  private OntologyResponse.Property findProperty(long ontologyId, long entityTypeId, long propertyId) {
    return findEntityType(ontologyId, entityTypeId).properties().stream()
        .filter(p -> propertyId == p.id())
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("방금 저장한 속성을 찾을 수 없습니다: " + propertyId));
  }

  @Transactional
  public RelationMutation addRelation(long ontologyId, CreateRelationRequest req) {
    assertEditable(ontologyId);
    // 끝점이 이 온톨로지의 타입인지 확인한다 — id만 맞으면 남의 온톨로지 타입을 끌어와
    // 온톨로지 경계를 넘는 관계가 생긴다. requireType이 소속까지 함께 본다.
    if (req.subjectTypeId() == null || req.objectTypeId() == null) {
      throw new IllegalArgumentException("관계의 subject/object 타입 id는 필수입니다.");
    }
    String subjectName = requireType(ontologyId, req.subjectTypeId());
    String objectName = requireType(ontologyId, req.objectTypeId());

    // blank 검사가 중복 검사보다 먼저다 — 빈 관계명 2건은 트리플 키가 같아 "중복"으로 오진단된다.
    OntologyRules.validateRelationCommon(req.relation(), req.description(), subjectName, objectName);
    if (elementRepository.existsTriple(ontologyId, req.subjectTypeId(), req.relation(), req.objectTypeId())) {
      throw OntologyRules.duplicateTriple(subjectName, req.relation(), objectName);
    }

    long relationId = elementRepository.insertRelation(ontologyId, req);
    int version = bumpVersion(ontologyId);
    audit("ONTOLOGY_RELATION_ADD", ontologyId,
        "관계 추가 — " + subjectName + " -[" + req.relation() + "]-> " + objectName);
    return new RelationMutation(version, findRelation(ontologyId, relationId));
  }

  @Transactional
  public RelationMutation updateRelation(long ontologyId, long relationId, UpdateRelationRequest req) {
    assertEditable(ontologyId);
    OntologyResponse.Triple current = requireRelation(ontologyId, relationId);

    if (req.relation() != null) {
      // blank 검사 문구는 OntologyRules가 단일 소유한다 — 여기서 다시 선언하지 않는다.
      OntologyRules.validateRelationName(req.relation(), current.subject(), current.object());
      // 관계명만 바뀌어도 트리플 키가 바뀐다 — 다른 기존 관계와 겹치는지 확인해야 UNIQUE 위반 500을 막는다.
      if (!req.relation().equals(current.relation())
          && elementRepository.existsTriple(
              ontologyId, current.subjectTypeId(), req.relation(), current.objectTypeId())) {
        throw OntologyRules.duplicateTriple(current.subject(), req.relation(), current.object());
      }
    }

    elementRepository.updateRelation(relationId, req);
    int version = bumpVersion(ontologyId);
    audit("ONTOLOGY_RELATION_UPDATE", ontologyId,
        "관계 수정 — " + current.subject() + " -[" + current.relation() + "]-> " + current.object());
    return new RelationMutation(version, findRelation(ontologyId, relationId));
  }

  @Transactional
  public VersionOnly deleteRelation(long ontologyId, long relationId) {
    assertEditable(ontologyId);
    OntologyResponse.Triple current = requireRelation(ontologyId, relationId);

    elementRepository.deleteRelation(relationId);
    int version = bumpVersion(ontologyId);
    audit("ONTOLOGY_RELATION_DELETE", ontologyId,
        "관계 삭제 — " + current.subject() + " -[" + current.relation() + "]-> " + current.object());
    return new VersionOnly(version);
  }

  // 사라진/소속이 다른 관계는 404다 — 사용자 입력 오류(낡은 화면이 이미 지운 관계를 지목한 경우).
  private OntologyResponse.Triple requireRelation(long ontologyId, long relationId) {
    return ontologyRepository.findById(ontologyId).relations().stream()
        .filter(t -> relationId == t.id())
        .findFirst()
        .orElseThrow(() -> new OntologyElementNotFoundException("존재하지 않는 관계입니다: " + relationId));
  }

  // requireRelation과 본문이 같지만 의도적으로 분리한다 — 이쪽은 저장 직후 자기 데이터가
  // 사라진 경우(서버 결함)이므로 사용자 입력 오류(404)가 아니라 500으로 다뤄야 한다.
  private OntologyResponse.Triple findRelation(long ontologyId, long relationId) {
    return ontologyRepository.findById(ontologyId).relations().stream()
        .filter(t -> relationId == t.id())
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("방금 저장한 관계를 찾을 수 없습니다: " + relationId));
  }
}
