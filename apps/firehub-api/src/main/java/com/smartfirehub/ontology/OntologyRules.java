package com.smartfirehub.ontology;

import java.util.List;
import java.util.Set;

// 온톨로지 본문(엔티티 타입·속성·관계) 검증 규칙·상수의 단일 소유자.
// OntologyService.validateCore(전체 문서 PUT)와 element 패키지(요소 단위 편집)가 함께 쓴다 —
// 문구를 두 곳에 복제하면 한쪽만 고쳤을 때 프론트 e2e의 문구 단언이 조용히 깨진다.
// 요소 하나로 판정 가능한 규칙만 여기 있다. 전체 문서 단위 불변식(완전성, 목록 전체 중복 스캔,
// 관계 참조 무결성)은 여러 요소를 동시에 봐야 하므로 OntologyService.validateCore에 남아 있다.
public final class OntologyRules {

  private OntologyRules() {}

  // Neo4j 노드 예약 필드(loader.ts 모델 (:Entity{key,type,name,sourceChunkIds,schemaVersion}))와 겹치는
  // 속성명은 적재 시 SET n += props 가 노드 정체성 필드를 덮어쓰므로 편집 시점에 차단한다.
  // ai-agent loader.ts 의 동일 상수와 노드 모델이 바뀌면 함께 갱신해야 한다(서비스 경계상 공유 불가).
  public static final Set<String> RESERVED_PROPERTY_NAMES =
      Set.of("key", "type", "name", "sourceChunkIds", "schemaVersion");

  // 속성 dataType 허용값 — DB CHECK(text|number|date)와 일치해야 한다.
  public static final List<String> DATA_TYPES = List.of("text", "number", "date");

  // 엔티티 타입 resolution 허용값.
  public static final List<String> RESOLUTIONS = List.of("embedding", "exact");

  // 엔티티 타입명 blank 차단.
  public static void validateEntityTypeName(String type) {
    if (type == null || type.isBlank()) {
      throw new IllegalArgumentException("엔티티 타입명은 비어 있을 수 없습니다.");
    }
  }

  // resolution 열거 검증. typeName은 에러 문구에만 쓰인다(리네임 중이면 새 이름, 아니면 현재 이름을
  // 호출부가 골라 넘긴다) — updateEntityType의 부분 필드 경로도 이 메서드로 통일해 문구 복제를 없앤다.
  public static void validateResolution(String resolution, String typeName) {
    if (!RESOLUTIONS.contains(resolution)) {
      throw new IllegalArgumentException("resolution은 embedding 또는 exact여야 합니다: " + typeName);
    }
  }

  // 엔티티 타입 공통 검증 — 타입명, resolution 열거, description/naming null 차단(#305).
  public static void validateEntityTypeCommon(
      String type, String description, String naming, String resolution) {
    validateEntityTypeName(type);
    validateResolution(resolution, type);
    // description/naming 컬럼은 NOT NULL(기본값 없음)이라 null이 그대로 INSERT되면 제약 위반으로
    // 500이 새어나간다(#305). null만 막고 빈 문자열은 허용한다 — 컬럼 제약이 NOT NULL일 뿐이고
    // 실제로 설명을 비워 저장한 기존 데이터가 정상 왕복(GET→PUT)돼야 하기 때문이다.
    if (description == null) {
      throw new IllegalArgumentException("엔티티 설명(description)은 null일 수 없습니다: " + type);
    }
    if (naming == null) {
      throw new IllegalArgumentException("엔티티 명명 규칙(naming)은 null일 수 없습니다: " + type);
    }
  }

  // 속성명 검증 — blank 차단 후 예약어 차단(#302). 중복은 호출부가 자기 스코프(같은 타입 안)로 판정한다.
  public static void validatePropertyName(String name, String typeName) {
    // blank 검사를 예약어/중복보다 먼저 둔다 — 이름이 빈 속성이 2개면 ''끼리 충돌해
    // "중복된 속성명"으로 오진단되고(실제 원인은 미입력), null이면 Set.of#contains가 NPE를 던져 500이 된다.
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("속성명은 비어 있을 수 없습니다: " + typeName);
    }
    if (RESERVED_PROPERTY_NAMES.contains(name)) {
      throw new IllegalArgumentException("예약어는 속성명으로 쓸 수 없습니다: " + name);
    }
  }

  // 속성 공통 검증 — description null 차단(#305) + dataType 열거.
  public static void validatePropertyCommon(
      String description, String dataType, String typeName, String name) {
    // 속성 description도 NOT NULL 컬럼 — 엔티티와 동일하게 null만 차단한다(#305).
    if (description == null) {
      throw new IllegalArgumentException(
          "속성 설명(description)은 null일 수 없습니다(" + typeName + "): " + name);
    }
    // dataType은 NOT NULL + CHECK(text|number|date)라 null은 애초에 저장 불가하다.
    // 기존 코드가 null을 통과시켜 제약 위반 500이 났으므로 null도 400으로 거른다(#305).
    if (dataType == null || !DATA_TYPES.contains(dataType)) {
      throw invalidDataType(name);
    }
  }

  // 잘못된 dataType 문구 생성기. PATCH 경로(부분 수정이라 description==null이 "변경 없음"이므로
  // validatePropertyCommon 전체를 호출할 수 없다)도 이 팩토리를 불러 문구를 여기 한 곳에서만 관리한다.
  public static IllegalArgumentException invalidDataType(String name) {
    return new IllegalArgumentException("데이터 타입은 text|number|date 중 하나여야 합니다: " + name);
  }

  // 관계명 blank 차단. subject/object exists 검사(여러 요소를 봐야 하는 문서 단위 불변식)를
  // 이 검사와 description 검사 사이에 끼워 넣어야 하는 OntologyService.validateCore가 따로 부른다.
  public static void validateRelationName(String relation, String subjectName, String objectName) {
    // 관계명 blank도 중복(tripleKey) 검사보다 먼저 — 빈 관계명 2건은 tripleKey가 같아
    // "중복된 관계"로 오진단된다. 이름 없는 관계는 LLM 추출·표 투영이 참조할 수 없어 무의미하다.
    if (relation == null || relation.isBlank()) {
      throw new IllegalArgumentException("관계명은 비어 있을 수 없습니다: " + subjectName + " → " + objectName);
    }
  }

  // 관계 description null 차단.
  public static void validateRelationDescription(String description, String subjectName, String objectName) {
    // 관계 description도 NOT NULL 컬럼 — 엔티티/속성과 동일하게 null만 차단한다(#305).
    if (description == null) {
      throw new IllegalArgumentException(
          "관계 설명(description)은 null일 수 없습니다: " + subjectName + " → " + objectName);
    }
  }

  // 관계 공통 검증 — 관계명 blank → description null 순서. 참조 무결성(subject/object exists) 검사를
  // 끼워 넣을 필요가 없는 호출부(요소 단위 관계 편집, Task 5)용 편의 메서드다. validateCore는 subject/
  // object exists 검사를 사이에 둬야 하므로 validateRelationName/validateRelationDescription을 나눠 부른다.
  public static void validateRelationCommon(
      String relation, String description, String subjectName, String objectName) {
    validateRelationName(relation, subjectName, objectName);
    validateRelationDescription(description, subjectName, objectName);
  }

  // 중복 엔티티 타입명 문구 생성기. 호출부가 `throw OntologyRules.duplicateEntityTypeName(...)` 형태로 쓴다.
  public static IllegalArgumentException duplicateEntityTypeName(String type) {
    return new IllegalArgumentException("중복된 엔티티 타입명: " + type);
  }

  // 중복 속성명 문구 생성기.
  public static IllegalArgumentException duplicatePropertyName(String typeName, String name) {
    return new IllegalArgumentException("중복된 속성명(" + typeName + "): " + name);
  }

  // 중복 관계(트리플) 문구 생성기.
  public static IllegalArgumentException duplicateTriple(String subject, String relation, String object) {
    return new IllegalArgumentException("중복된 관계: " + subject + "|" + relation + "|" + object);
  }
}
