package com.smartfirehub.ontology.element.dto;

import com.smartfirehub.ontology.dto.OntologyResponse;
import java.util.List;

// 요소 단위 편집 API의 요청/응답 계약 모음. 레코드 하나하나가 파일을 차지할 만큼 크지 않고
// 서로 붙어 읽히는 편이 낫다(전체 스키마 계약은 OntologyResponse가 따로 소유한다).
public final class ElementDtos {

  private ElementDtos() {}

  // 모든 뮤테이션 응답은 새 schemaVersion을 싣는다 — 클라이언트가 "내가 유발한 증가분"과 비교해
  // 남의 변경을 감지하는 근거다(409 낙관적 잠금을 대신하는 장치).

  // 반환할 요소가 없는 뮤테이션(도메인 수정, 속성·관계 삭제)용.
  public record VersionOnly(int schemaVersion) {}

  // PATCH /ontology/{id} — 부분 갱신. null 필드는 "변경 없음"이다(빈 문자열과 구분된다).
  public record PatchOntologyRequest(String domain) {}

  public record CreateEntityTypeRequest(String type, String description, String naming, String resolution) {}

  // null 필드는 변경 없음. type만 담아 보내면 순수 리네임이 된다.
  public record UpdateEntityTypeRequest(String type, String description, String naming, String resolution) {}

  // 생성·수정 결과. properties는 항상 현재 상태 전체를 담는다.
  public record EntityTypeMutation(int schemaVersion, OntologyResponse.EntityType entityType) {}

  // 삭제 결과. deletedRelationIds는 FK CASCADE로 함께 사라진 관계들 — 클라이언트가 낙관적 갱신에서
  // 무엇을 지워야 할지 알 수 있게 서버가 삭제 직전에 조회해 담는다(DB cascade는 조용하다).
  public record EntityTypeDeletion(int schemaVersion, List<Long> deletedRelationIds) {}

  public record CreatePropertyRequest(String name, String description, String dataType, String unit) {}

  // null 필드는 변경 없음. unit은 원래 nullable이라 "null로 지우기"를 표현할 수 없다 —
  // 단위 제거가 필요하면 빈 문자열을 보내고 서비스가 null로 정규화한다.
  public record UpdatePropertyRequest(String name, String description, String dataType, String unit) {}

  public record PropertyMutation(int schemaVersion, OntologyResponse.Property property) {}

  // 끝점은 타입 id로 지정한다 — 이름으로 받으면 리네임과 경합할 때 어느 타입을 가리켰는지 알 수 없다.
  public record CreateRelationRequest(Long subjectTypeId, String relation, Long objectTypeId, String description) {}

  // 끝점(subject/object)은 수정 대상이 아니다. 끝점이 바뀌면 그것은 다른 관계이므로
  // 삭제 후 재생성으로 표현한다 — UNIQUE 제약과 감사 로그가 그만큼 단순해진다.
  public record UpdateRelationRequest(String relation, String description) {}

  public record RelationMutation(int schemaVersion, OntologyResponse.Triple relation) {}
}
