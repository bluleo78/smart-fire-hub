package com.smartfirehub.mapping.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.mapping.dto.MappingResponse;
import com.smartfirehub.mapping.dto.MappingSpec;
import com.smartfirehub.mapping.repository.MappingRepository;
import com.smartfirehub.mapping.repository.StoredMapping;
import com.smartfirehub.ontology.binding.DatasetOntologyRepository;
import com.smartfirehub.ontology.dto.OntologyResponse;
import com.smartfirehub.ontology.repository.OntologyRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

// 데이터셋 매핑 서비스 — 저장/활성화 시점에 온톨로지·컬럼·속성타입·트리플 conformance를 검증한다.
// 수동 저작이므로 검수 인박스는 쓰지 않고 매핑 레코드 자체의 draft→active 라이프사이클로 관리한다.
@Service
@RequiredArgsConstructor
public class MappingService {

  private final MappingRepository mappingRepository;
  private final DatasetOntologyRepository bindingRepository;
  private final OntologyRepository ontologyRepository;
  private final DatasetColumnRepository columnRepository;
  private final ObjectMapper objectMapper;

  // 매핑 저장(draft). conformance 검증 통과 시 JSONB로 직렬화해 upsert.
  public MappingResponse save(long datasetId, MappingSpec spec, Long userId) {
    long ontologyId = validate(datasetId, spec);
    mappingRepository.upsert(datasetId, ontologyId, serialize(spec), "draft", userId);
    return new MappingResponse(datasetId, ontologyId, spec, "draft");
  }

  // 매핑 조회(없으면 empty).
  public Optional<MappingResponse> get(long datasetId) {
    return mappingRepository.findByDataset(datasetId)
        .map(m -> new MappingResponse(datasetId, m.ontologyId(), deserialize(m.specJson()), m.status()));
  }

  // draft→active 활성화. 존재 확인 + 재검증 후 상태 전환.
  public MappingResponse activate(long datasetId, Long userId) {
    StoredMapping stored = mappingRepository.findByDataset(datasetId)
        .orElseThrow(() -> new IllegalArgumentException("활성화할 매핑이 없습니다: " + datasetId));
    MappingSpec spec = deserialize(stored.specJson());
    long ontologyId = validate(datasetId, spec); // 활성화 시점 스키마가 바뀌었을 수 있어 재검증
    mappingRepository.updateStatus(datasetId, "active", userId);
    return new MappingResponse(datasetId, ontologyId, spec, "active");
  }

  // conformance 검증 — 위반 시 IllegalArgumentException(→400). 통과하면 바인딩 ontologyId 반환.
  private long validate(long datasetId, MappingSpec spec) {
    long ontologyId = bindingRepository.findOntologyIdByDataset(datasetId)
        .orElseThrow(() -> new IllegalArgumentException("데이터셋이 온톨로지에 바인딩되지 않았습니다: " + datasetId));
    OntologyResponse ontology = ontologyRepository.findById(ontologyId);

    // 컬럼명→컬럼타입. 존재 확인뿐 아니라 속성 dataType 호환 검사에도 원본 타입이 필요하다.
    Map<String, String> columnTypes = columnRepository.findByDatasetId(datasetId).stream()
        .collect(Collectors.toMap(DatasetColumnResponse::columnName,
            c -> c.dataType() == null ? "" : c.dataType()));
    Set<String> columns = columnTypes.keySet();
    Map<String, OntologyResponse.EntityType> typeByName = ontology.entities().stream()
        .collect(Collectors.toMap(OntologyResponse.EntityType::type, Function.identity()));

    List<MappingSpec.EntityMapping> entities = spec.entities() == null ? List.of() : spec.entities();
    for (MappingSpec.EntityMapping em : entities) {
      OntologyResponse.EntityType et = typeByName.get(em.entityType());
      if (et == null) {
        throw new IllegalArgumentException("온톨로지에 없는 엔티티 타입: " + em.entityType());
      }
      if (!columns.contains(em.nameColumn())) {
        throw new IllegalArgumentException("데이터셋에 없는 컬럼(nameColumn): " + em.nameColumn());
      }
      Map<String, OntologyResponse.Property> propByName = et.properties().stream()
          .collect(Collectors.toMap(OntologyResponse.Property::name, Function.identity()));
      List<MappingSpec.PropertyMapping> props = em.properties() == null ? List.of() : em.properties();
      for (MappingSpec.PropertyMapping pm : props) {
        OntologyResponse.Property prop = propByName.get(pm.propertyName());
        if (prop == null) {
          throw new IllegalArgumentException(em.entityType() + "에 없는 속성: " + pm.propertyName());
        }
        if (!columns.contains(pm.column())) {
          throw new IllegalArgumentException("데이터셋에 없는 컬럼(property): " + pm.column());
        }
        checkPropertyType(em.entityType(), prop, pm.column(), columnTypes.get(pm.column()));
      }
    }

    List<MappingSpec.RelationMapping> relations = spec.relations() == null ? List.of() : spec.relations();
    for (MappingSpec.RelationMapping rm : relations) {
      if (rm.subjectRef() < 0 || rm.subjectRef() >= entities.size()
          || rm.objectRef() < 0 || rm.objectRef() >= entities.size()) {
        throw new IllegalArgumentException("relation ref 범위 오류: " + rm.subjectRef() + "," + rm.objectRef());
      }
      String subjectType = entities.get(rm.subjectRef()).entityType();
      String objectType = entities.get(rm.objectRef()).entityType();
      boolean allowed = ontology.relations().stream().anyMatch(t ->
          t.subject().equals(subjectType) && t.relation().equals(rm.relation()) && t.object().equals(objectType));
      if (!allowed) {
        throw new IllegalArgumentException(
            "허용되지 않은 트리플: " + subjectType + "-" + rm.relation() + "->" + objectType);
      }
    }
    return ontologyId;
  }

  // 데이터셋 컬럼 타입 → 온톨로지 dataType(text|number|date) 축약.
  // 온톨로지 dataType 셋이 3종뿐이므로 컬럼 타입도 같은 축으로 접어 비교한다.
  // ai-agent의 column-profiler collapseDataType과 같은 대응이지만, 여기선 **모르는 타입은 null**로 둔다
  // — 미래에 추가될 컬럼 타입을 text로 단정해 정상 매핑을 거부하는 쪽이 더 위험하기 때문(과소차단 선택).
  private static String collapseColumnType(String columnType) {
    if (columnType == null) {
      return null;
    }
    // VARCHAR(255)·NUMERIC(18,6)처럼 정밀도가 붙어 올 수 있어 괄호 앞부분만 본다.
    String base = columnType.trim().toUpperCase();
    int paren = base.indexOf('(');
    if (paren >= 0) {
      base = base.substring(0, paren);
    }
    return switch (base) {
      case "INTEGER", "BIGINT", "SMALLINT", "DECIMAL", "NUMERIC", "DOUBLE", "REAL", "FLOAT" -> "number";
      case "DATE", "TIMESTAMP", "TIMESTAMPTZ", "TIME" -> "date";
      // BOOLEAN·GEOMETRY는 숫자도 날짜도 아니므로 text로 접어 number/date 연결을 막는다.
      case "TEXT", "VARCHAR", "CHAR", "BPCHAR", "BOOLEAN", "GEOMETRY" -> "text";
      default -> null; // 미지 타입 → 판정 보류(검증 통과)
    };
  }

  // 컬럼↔속성 dataType 호환 검사.
  // 온톨로지 text 속성은 어떤 컬럼이든 문자열화해 담을 수 있으므로 허용하고,
  // number/date 속성은 같은 축의 컬럼만 허용한다(투영이 SET n += properties로 원본 값을 그대로 쓰기 때문에
  // 문자열이 들어가면 집계·범위 질의가 조용히 어긋난다).
  private static void checkPropertyType(String entityType, OntologyResponse.Property prop,
      String columnName, String columnType) {
    String want = prop.dataType();
    if (want == null || want.isBlank() || "text".equalsIgnoreCase(want)) {
      return; // 미지정 속성은 판정 대상이 아니고, text는 모두 허용
    }
    String actual = collapseColumnType(columnType);
    if (actual == null || actual.equals(want.toLowerCase())) {
      return;
    }
    throw new IllegalArgumentException(String.format(
        "%s의 속성 '%s'(%s)에 %s 컬럼 '%s'(%s)을 연결할 수 없습니다",
        entityType, prop.name(), want, koreanTypeName(actual), columnName, columnType));
  }

  // 오류 메시지용 축약 타입 한글 표기.
  private static String koreanTypeName(String collapsed) {
    return switch (collapsed) {
      case "number" -> "숫자";
      case "date" -> "날짜";
      default -> "텍스트";
    };
  }

  private String serialize(MappingSpec spec) {
    try {
      return objectMapper.writeValueAsString(spec);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("매핑 직렬화 실패", e);
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
