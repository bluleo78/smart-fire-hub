package com.smartfirehub.mapping.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.mapping.dto.MappingSpec;
import com.smartfirehub.mapping.dto.OntologyMappingUsage;
import com.smartfirehub.mapping.repository.MappingRepository;
import com.smartfirehub.mapping.repository.MappingRepository.LiveMappingRow;
import com.smartfirehub.mapping.repository.MappingRepository.OntologyMappingRow;
import com.smartfirehub.ontology.dto.OntologyResponse;
import com.smartfirehub.ontology.repository.OntologyRepository;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 데이터셋 매핑이 온톨로지 요소를 참조하는 관계를 지키는 서비스(WD-129).
 *
 * <p>왜 필요한가: 매핑 spec 은 엔티티 타입·속성·관계를 <b>이름 문자열</b>로 가리킨다. 지식 모델에서 이름을 바꾸거나 요소를 지워도 매핑은 아무도 고치지 않아,
 * active 매핑이 옛 이름을 든 채 남고(재저장 400, 그래프 투영 실패·옛 이름 적재) 사용자는 원인을 알 수 없었다. 사람 결정(A안)에 따라
 *
 * <ul>
 *   <li>이름 변경은 같은 온톨로지의 모든 매핑(draft·active)에 같은 트랜잭션으로 연쇄 반영하고,
 *   <li>삭제는 매핑이 쓰고 있으면 409 로 막는다.
 *   <li>속성 자료형 변경은 매핑이 그 속성에 연결한 컬럼 타입과 맞지 않으면 409 로 막는다(WD-185).
 * </ul>
 *
 * <p>호출자(OntologyElementService)의 트랜잭션에 합류한다 — 지식 모델 변경과 매핑 갱신이 함께 커밋되거나 함께 롤백돼야 한다.
 *
 * <p>스코프: 매핑은 dataset_mapping.ontology_id 로 온톨로지에 묶인다(저장 시 바인딩에서 파생). 같은 이름의 요소가 다른 온톨로지에 있어도 그
 * 온톨로지의 매핑은 건드리지 않는다. 테넌트 격리는 리포지토리 쿼리가 RLS(GUC)로 받는다.
 */
@Service
@RequiredArgsConstructor
public class MappingReferenceService {

  /** 삭제 차단 409 의 기계용 코드 — 웹이 문구가 아니라 이 코드로 "매핑 사용 중"을 가린다. */
  public static final String IN_USE_CODE = "ONTOLOGY_ELEMENT_IN_USE_BY_MAPPING";

  /**
   * WD-185: 속성 자료형 변경이 매핑에 연결된 컬럼 타입과 맞지 않아 거절된 409 의 기계용 코드. 삭제 차단(IN_USE_CODE)과 나눈 이유: 웹이 삭제 확인
   * 창용 문구·사용처 갱신을 IN_USE_CODE 에 묶어 두었고, 이 거절은 "삭제"가 아니라 "연결된 컬럼과의 충돌"이다.
   */
  public static final String TYPE_CONFLICT_CODE = "ONTOLOGY_PROPERTY_TYPE_CONFLICTS_WITH_MAPPING";

  /** 문구에 이름을 다 나열하면 토스트가 길어지므로 앞의 몇 개만 보이고 나머지는 "외 n개"로 줄인다(웹 삭제 영향 안내와 같은 규칙). */
  private static final int MAX_NAMES = 5;

  private final MappingRepository mappingRepository;
  private final OntologyRepository ontologyRepository;
  private final DatasetAccessGuard datasetAccessGuard;
  // WD-185: 자료형 호환 판정에 매핑이 연결한 컬럼의 실제 타입이 필요하다(매핑 spec 에는 컬럼 이름만 있다).
  private final DatasetColumnRepository columnRepository;
  private final ObjectMapper objectMapper;

  // ── 이름 변경 연쇄 ─────────────────────────────────────────────────────────

  /**
   * 엔티티 타입 이름 변경을 매핑에 반영한다. 관계 매핑은 엔티티를 이름이 아니라 entities 인덱스(subjectRef/objectRef)로 가리키므로
   * entityType 만 바꾸면 관계 쪽도 자동으로 새 이름을 따른다.
   */
  public void renameEntityType(long ontologyId, String oldName, String newName) {
    rewriteAll(
        ontologyId,
        spec ->
            new MappingSpec(
                mapEntities(
                    spec,
                    em ->
                        oldName.equals(em.entityType())
                            ? new MappingSpec.EntityMapping(
                                newName, em.nameColumn(), em.properties())
                            : em),
                spec.relations()));
  }

  /** 속성 이름 변경을 매핑에 반영한다. 속성명은 엔티티 타입 안에서만 유일하므로 그 타입의 엔티티 매핑 안에서만 바꾼다. */
  public void renameProperty(long ontologyId, String typeName, String oldName, String newName) {
    rewriteAll(
        ontologyId,
        spec ->
            new MappingSpec(
                mapEntities(
                    spec,
                    em -> {
                      if (!typeName.equals(em.entityType()) || em.properties() == null) {
                        return em;
                      }
                      List<MappingSpec.PropertyMapping> props =
                          em.properties().stream()
                              .map(
                                  pm ->
                                      oldName.equals(pm.propertyName())
                                          ? new MappingSpec.PropertyMapping(pm.column(), newName)
                                          : pm)
                              .toList();
                      return new MappingSpec.EntityMapping(em.entityType(), em.nameColumn(), props);
                    }),
                spec.relations()));
  }

  /**
   * 관계 이름 변경을 매핑에 반영한다. 관계는 (subject, relation, object) 트리플이 정체성이라 같은 관계명이 다른 타입 쌍에도 있을 수 있다 — 끝점
   * 타입까지 맞는 관계 매핑만 바꾼다.
   */
  public void renameRelation(
      long ontologyId, String subject, String oldName, String newName, String object) {
    rewriteAll(
        ontologyId,
        spec -> {
          if (spec.relations() == null) {
            return spec;
          }
          List<MappingSpec.RelationMapping> relations =
              spec.relations().stream()
                  .map(
                      rm ->
                          matchesTriple(spec, rm, subject, oldName, object)
                              ? new MappingSpec.RelationMapping(
                                  rm.subjectRef(), newName, rm.objectRef())
                              : rm)
                  .toList();
          return new MappingSpec(spec.entities(), relations);
        });
  }

  // 이 온톨로지의 매핑을 잠그고 읽어 변환하고, 실제로 바뀐 것만 다시 쓴다.
  // 바뀌지 않은 행까지 쓰면 updated_at 이 의미 없이 움직여 "누가 언제 매핑을 고쳤나"가 흐려진다.
  private void rewriteAll(long ontologyId, UnaryOperator<MappingSpec> transform) {
    for (OntologyMappingRow row : mappingRepository.findByOntologyForUpdate(ontologyId)) {
      MappingSpec before = deserialize(row.specJson());
      MappingSpec after = transform.apply(before);
      if (!Objects.equals(before, after)) {
        mappingRepository.updateSpec(row.datasetId(), serialize(after));
      }
    }
  }

  private static List<MappingSpec.EntityMapping> mapEntities(
      MappingSpec spec, UnaryOperator<MappingSpec.EntityMapping> fn) {
    return spec.entities() == null ? null : spec.entities().stream().map(fn).toList();
  }

  // ── 사용처 판정(삭제 차단·화면 미리보기 공용) ─────────────────────────────────────

  // 매핑이 이 엔티티 타입을 쓰는가. 타입 삭제 차단은 이것 하나로 충분하다 — 그 타입의 속성 매핑은 같은 타입의 엔티티 매핑 안에만 있고,
  // 타입 삭제로 함께 지워지는(FK CASCADE) 관계를 매핑한 관계 매핑도 끝점으로 그 타입의 엔티티 매핑을 반드시 가리킨다.
  private static boolean usesType(MappingSpec spec, String typeName) {
    return spec.entities() != null
        && spec.entities().stream().anyMatch(em -> typeName.equals(em.entityType()));
  }

  // 매핑이 이 타입의 이 속성을 쓰는가 — 다른 타입의 같은 이름 속성은 별개다.
  private static boolean usesProperty(MappingSpec spec, String typeName, String propertyName) {
    return spec.entities() != null
        && spec.entities().stream()
            .filter(em -> typeName.equals(em.entityType()) && em.properties() != null)
            .flatMap(em -> em.properties().stream())
            .anyMatch(pm -> propertyName.equals(pm.propertyName()));
  }

  // 매핑이 이 트리플을 쓰는가.
  private static boolean usesRelation(
      MappingSpec spec, String subject, String relation, String object) {
    return spec.relations() != null
        && spec.relations().stream()
            .anyMatch(rm -> matchesTriple(spec, rm, subject, relation, object));
  }

  // 관계 매핑 한 건이 주어진 트리플인가. 인덱스가 범위를 벗어난 매핑(저장 검증을 우회한 경우)은 어느 트리플에도 해당하지 않는다.
  private static boolean matchesTriple(
      MappingSpec spec,
      MappingSpec.RelationMapping rm,
      String subject,
      String relation,
      String object) {
    List<MappingSpec.EntityMapping> entities = spec.entities();
    if (entities == null
        || !relation.equals(rm.relation())
        || rm.subjectRef() < 0
        || rm.subjectRef() >= entities.size()
        || rm.objectRef() < 0
        || rm.objectRef() >= entities.size()) {
      return false;
    }
    return subject.equals(entities.get(rm.subjectRef()).entityType())
        && object.equals(entities.get(rm.objectRef()).entityType());
  }

  // ── 삭제 차단 ─────────────────────────────────────────────────────────────

  /** 엔티티 타입을 쓰는 매핑이 있으면 409. 그 타입의 속성·함께 지워질 관계까지 덮는다(usesType 주석). */
  public void assertEntityTypeUnused(long ontologyId, String typeName) {
    assertUnused(ontologyId, spec -> usesType(spec, typeName), "「" + typeName + "」 타입은");
  }

  /** 속성을 쓰는 매핑이 있으면 409. */
  public void assertPropertyUnused(long ontologyId, String typeName, String propertyName) {
    assertUnused(
        ontologyId,
        spec -> usesProperty(spec, typeName, propertyName),
        "「" + typeName + "." + propertyName + "」 속성은");
  }

  /** 관계(트리플)를 쓰는 매핑이 있으면 409. */
  public void assertRelationUnused(
      long ontologyId, String subject, String relation, String object) {
    assertUnused(
        ontologyId,
        spec -> usesRelation(spec, subject, relation, object),
        "「" + subject + " → " + relation + " → " + object + "」 관계는");
  }

  // 사용 중인 데이터셋이 하나라도 있으면 막는다. 조회자가 볼 수 없는 데이터셋이어도 막는다 — 이름은 물론 id 도 싣지 않고 개수로만
  // 알린다. 응답 errors 에 id 를 싣지 않는 이유: DatasetAccessGuard 는 볼 수 없는 데이터셋을 404 로 숨긴다(존재 은닉) — 여기서 id 를
  // 주면 그 존재가 새어 나간다. 웹은 message 만 쓴다.
  private void assertUnused(long ontologyId, Predicate<MappingSpec> uses, String subjectPhrase) {
    OntologyMappingUsage.ElementUsage usage = usageOf(liveMappings(ontologyId), uses);
    if (usage == null) {
      return;
    }
    throw new CodedApiException(
        HttpStatus.CONFLICT, IN_USE_CODE, inUseMessage(subjectPhrase, usage));
  }

  /**
   * 삭제 차단 문구. 예: 「Incident」 타입은 「화재사건표」 데이터셋 매핑에서 사용 중이라 삭제할 수 없습니다. 매핑에서 먼저 빼 주세요.
   *
   * <p>웹 삭제 확인 창(mappingUsageMessage, lib/ontology-mapping-usage.ts)이 같은 규칙으로 문구를 만든다 — 한쪽을 고치면 다른
   * 쪽도 고칠 것.
   */
  static String inUseMessage(String subjectPhrase, OntologyMappingUsage.ElementUsage usage) {
    List<String> shown =
        usage.datasets().stream()
            .limit(MAX_NAMES)
            .map(d -> "「" + d.datasetName() + "」")
            .collect(Collectors.toList());
    int rest = usage.datasets().size() - shown.size();
    int hidden = usage.hiddenCount();

    String visiblePart =
        shown.isEmpty()
            ? null
            : String.join(", ", shown) + (rest > 0 ? " 외 " + rest + "개" : "") + " 데이터셋";
    String hiddenPart = hidden > 0 ? "볼 수 없는 데이터셋 " + hidden + "개" : null;
    String where;
    if (visiblePart != null && hiddenPart != null) {
      where = visiblePart + "과 " + hiddenPart + "의 매핑에서";
    } else if (visiblePart != null) {
      where = visiblePart + " 매핑에서";
    } else {
      where = hiddenPart + "의 매핑에서";
    }
    return subjectPhrase + " " + where + " 사용 중이라 삭제할 수 없습니다. 매핑에서 먼저 빼 주세요.";
  }

  // ── 자료형 변경 차단(WD-185) ─────────────────────────────────────────────────

  /**
   * 속성 자료형을 newDataType 으로 바꿔도 이 속성을 쓰는 매핑들의 연결 컬럼과 맞는지 확인하고, 하나라도 맞지 않으면 409 로 막는다.
   *
   * <p>왜: 자료형만 바꾸고 매핑을 그대로 두면 그 매핑의 재저장·활성화가 400 으로 막히고(MappingService.checkPropertyType), 그래프 투영은
   * 숫자·날짜 속성에 문자열을 그대로 넣는다. 삭제 차단과 같은 이유·범위다 — 살아 있는 데이터셋의 매핑(상태 무관)만 보고, 고아 매핑은 사용자가 손댈 수 없으므로 막지
   * 않는다. 호환 판정은 매핑 저장 검증과 같은 규칙(MappingService.incompatibleColumnKind)이다.
   *
   * <p>propertyName 은 <b>현재(변경 전) 이름</b>이어야 한다 — 이름과 자료형을 함께 바꾸는 요청에서 매핑은 아직 옛 이름을 들고 있다(이름 연쇄 갱신은
   * 이 검사 뒤에 일어난다). 잠금: 호출자가 이미 온톨로지 행을 배타 잠금했다(assertEditable) — 매핑 저장은 같은 행을 공유
   * 잠금하므로(MappingService.validate) 이 판정 뒤에 새 연결이 끼어들지 못한다.
   */
  public void assertDataTypeCompatible(
      long ontologyId, String typeName, String propertyName, String newDataType) {
    List<TypeConflict> visible = new ArrayList<>();
    int hidden = 0;
    for (ParsedRow r : liveMappings(ontologyId)) {
      List<String> columns = mappedColumns(r.spec(), typeName, propertyName);
      if (columns.isEmpty()) {
        continue;
      }
      // 컬럼 타입은 데이터셋 컬럼 정의에서 읽는다. 이미 지워진 컬럼은 타입을 모르므로 막지 않는다(저장 검증과 같은 과소차단).
      Map<String, String> columnTypes =
          columnRepository.findByDatasetId(r.row().datasetId()).stream()
              .collect(
                  Collectors.toMap(
                      DatasetColumnResponse::columnName,
                      c -> c.dataType() == null ? "" : c.dataType(),
                      (a, b) -> a));
      boolean datasetConflicts = false;
      for (String column : columns) {
        String kind = MappingService.incompatibleColumnKind(newDataType, columnTypes.get(column));
        if (kind == null) {
          continue;
        }
        datasetConflicts = true;
        if (r.row().datasetName() != null) {
          visible.add(new TypeConflict(r.row().datasetName(), column, kind));
        }
      }
      // 볼 수 없는 데이터셋은 이름은 물론 컬럼 이름·타입도 싣지 않는다 — 그것만으로도 어느 데이터셋인지 짐작할 수 있다. 개수로만 알린다.
      if (datasetConflicts && r.row().datasetName() == null) {
        hidden++;
      }
    }
    if (visible.isEmpty() && hidden == 0) {
      return;
    }
    throw new CodedApiException(
        HttpStatus.CONFLICT,
        TYPE_CONFLICT_CODE,
        typeConflictMessage(typeName + "." + propertyName, newDataType, visible, hidden));
  }

  // 매핑에서 이 타입의 이 속성에 연결된 컬럼들. 한 매핑 안에서 같은 속성을 두 컬럼에 연결한 경우(검증이 막지 않는다)도 모두 본다.
  private static List<String> mappedColumns(
      MappingSpec spec, String typeName, String propertyName) {
    if (spec.entities() == null) {
      return List.of();
    }
    return spec.entities().stream()
        .filter(em -> typeName.equals(em.entityType()) && em.properties() != null)
        .flatMap(em -> em.properties().stream())
        .filter(pm -> propertyName.equals(pm.propertyName()))
        .map(MappingSpec.PropertyMapping::column)
        .toList();
  }

  // 자료형 충돌 한 건(볼 수 있는 데이터셋만). columnKind 는 축약 타입(number|date|text).
  record TypeConflict(String datasetName, String columnName, String columnKind) {}

  /**
   * 자료형 변경 거절 문구. 예: 「Building.note」 속성은 「건물대장」 데이터셋 매핑의 텍스트 컬럼 「memo」에 연결되어 있어 숫자로 바꿀 수 없습니다. 매핑에서
   * 연결을 먼저 바꾸세요.
   *
   * <p>나열 규칙은 삭제 차단 문구(inUseMessage)와 같다 — 앞의 몇 건만 보이고 "외 n개", 볼 수 없는 데이터셋은 개수로만.
   */
  static String typeConflictMessage(
      String propertyLabel, String newDataType, List<TypeConflict> visible, int hidden) {
    List<String> shown =
        visible.stream()
            .limit(MAX_NAMES)
            .map(
                c ->
                    "「"
                        + c.datasetName()
                        + "」 데이터셋 매핑의 "
                        + MappingService.koreanTypeName(c.columnKind())
                        + " 컬럼 「"
                        + c.columnName()
                        + "」")
            .collect(Collectors.toList());
    int rest = visible.size() - shown.size();
    String visiblePart =
        shown.isEmpty() ? null : String.join(", ", shown) + (rest > 0 ? " 외 " + rest + "개" : "");
    String hiddenPart = hidden > 0 ? "볼 수 없는 데이터셋 " + hidden + "개의 매핑 컬럼" : null;
    String where;
    if (visiblePart != null && hiddenPart != null) {
      where = visiblePart + " 및 " + hiddenPart;
    } else if (visiblePart != null) {
      where = visiblePart;
    } else {
      where = hiddenPart;
    }
    return "「"
        + propertyLabel
        + "」 속성은 "
        + where
        + "에 연결되어 있어 "
        + MappingService.koreanTypeName(newDataType.toLowerCase())
        + "로 바꿀 수 없습니다. 매핑에서 연결을 먼저 바꾸세요.";
  }

  // ── 화면 미리보기 ──────────────────────────────────────────────────────────

  /**
   * 요소별 매핑 사용처. 삭제 확인 창이 확정 전에 "어느 데이터셋 매핑이 쓰는지"를 보여 주고 확인 버튼을 막는 데 쓴다. 최종 방어선은 여전히 삭제 API 의 409
   * 다(조회와 삭제 사이에 매핑이 바뀔 수 있다). 사용처가 있는 요소만 담는다.
   *
   * <p>트랜잭션: 온톨로지·매핑·데이터셋이 모두 RLS 대상이라 GUC 가 주입된 트랜잭션 안에서 읽어야 한다(밖이면 조용히 0행).
   */
  @Transactional(readOnly = true)
  public OntologyMappingUsage usage(long ontologyId) {
    OntologyResponse ontology = ontologyRepository.findById(ontologyId);
    List<ParsedRow> rows = liveMappings(ontologyId);

    Map<Long, OntologyMappingUsage.ElementUsage> types = new LinkedHashMap<>();
    Map<Long, OntologyMappingUsage.ElementUsage> props = new LinkedHashMap<>();
    Map<Long, OntologyMappingUsage.ElementUsage> rels = new LinkedHashMap<>();
    for (OntologyResponse.EntityType et : ontology.entities()) {
      putIfUsed(types, et.id(), usageOf(rows, spec -> usesType(spec, et.type())));
      for (OntologyResponse.Property p : et.properties()) {
        putIfUsed(props, p.id(), usageOf(rows, spec -> usesProperty(spec, et.type(), p.name())));
      }
    }
    for (OntologyResponse.Triple t : ontology.relations()) {
      putIfUsed(
          rels,
          t.id(),
          usageOf(rows, spec -> usesRelation(spec, t.subject(), t.relation(), t.object())));
    }
    return new OntologyMappingUsage(types, props, rels);
  }

  private static void putIfUsed(
      Map<Long, OntologyMappingUsage.ElementUsage> target,
      Long elementId,
      OntologyMappingUsage.ElementUsage usage) {
    if (elementId != null && usage != null) {
      target.put(elementId, usage);
    }
  }

  // 요소 하나의 사용처 — 볼 수 있는 데이터셋은 id·이름으로, 볼 수 없는 데이터셋은 개수로만 접는다. 사용처가 없으면 null.
  // 데이터셋당 매핑은 1건(dataset_id 유니크)이라 따로 중복을 걸러낼 필요가 없다.
  private static OntologyMappingUsage.ElementUsage usageOf(
      List<ParsedRow> rows, Predicate<MappingSpec> uses) {
    List<OntologyMappingUsage.UsageDataset> visible = new ArrayList<>();
    int hidden = 0;
    for (ParsedRow r : rows) {
      if (!uses.test(r.spec())) {
        continue;
      }
      if (r.row().datasetName() == null) {
        hidden++;
      } else {
        visible.add(
            new OntologyMappingUsage.UsageDataset(r.row().datasetId(), r.row().datasetName()));
      }
    }
    return visible.isEmpty() && hidden == 0
        ? null
        : new OntologyMappingUsage.ElementUsage(List.copyOf(visible), hidden);
  }

  // ── 내부 ─────────────────────────────────────────────────────────────────

  private record ParsedRow(LiveMappingRow row, MappingSpec spec) {}

  // 살아 있는 데이터셋의 매핑 + 조회자 기준 이름 가시성(보안 등급).
  private List<ParsedRow> liveMappings(long ontologyId) {
    return mappingRepository
        .findLiveByOntology(ontologyId, datasetAccessGuard.visibleCondition())
        .stream()
        .map(r -> new ParsedRow(r, deserialize(r.specJson())))
        .toList();
  }

  private String serialize(MappingSpec spec) {
    try {
      return objectMapper.writeValueAsString(spec);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("매핑 직렬화 실패", e);
    }
  }

  private MappingSpec deserialize(String json) {
    try {
      return objectMapper.readValue(json, MappingSpec.class);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("매핑 역직렬화 실패", e);
    }
  }
}
