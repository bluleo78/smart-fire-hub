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

// 데이터셋 매핑 서비스 — 저장/활성화 시점에 온톨로지·컬럼·트리플 conformance를 검증한다.
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

    Set<String> columns = columnRepository.findByDatasetId(datasetId).stream()
        .map(DatasetColumnResponse::columnName).collect(Collectors.toSet());
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
      Set<String> propNames = et.properties().stream()
          .map(OntologyResponse.Property::name).collect(Collectors.toSet());
      List<MappingSpec.PropertyMapping> props = em.properties() == null ? List.of() : em.properties();
      for (MappingSpec.PropertyMapping pm : props) {
        if (!propNames.contains(pm.propertyName())) {
          throw new IllegalArgumentException(em.entityType() + "에 없는 속성: " + pm.propertyName());
        }
        if (!columns.contains(pm.column())) {
          throw new IllegalArgumentException("데이터셋에 없는 컬럼(property): " + pm.column());
        }
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
