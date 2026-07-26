package com.smartfirehub.mapping.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// 매핑 conformance 검증 + save/get/activate 단위 테스트(리포지토리 mock).
class MappingServiceTest {

  private MappingRepository mappingRepository;
  private DatasetOntologyRepository bindingRepository;
  private OntologyRepository ontologyRepository;
  private DatasetColumnRepository columnRepository;
  private MappingService service;

  private static final long DS = 5001L;

  @BeforeEach
  void setUp() {
    mappingRepository = mock(MappingRepository.class);
    bindingRepository = mock(DatasetOntologyRepository.class);
    ontologyRepository = mock(OntologyRepository.class);
    columnRepository = mock(DatasetColumnRepository.class);
    service = new MappingService(mappingRepository, bindingRepository, ontologyRepository,
        columnRepository, new ObjectMapper());

    // 기본 fixture: DS는 온톨로지 1에 바인딩, 온톨로지는 Incident/Building + OCCURRED_AT, 컬럼 id/bld/loss.
    when(bindingRepository.findOntologyIdByDataset(DS)).thenReturn(Optional.of(1L));
    when(ontologyRepository.findById(1L)).thenReturn(new OntologyResponse("화재조사", 1,
        List.of(
            new OntologyResponse.EntityType("Incident", "사건", "명명", "exact",
                List.of(new OntologyResponse.Property("피해액", "d", "number", "원")), 10L),
            new OntologyResponse.EntityType("Building", "건물", "명명", "embedding", List.of(), 11L)),
        List.of(new OntologyResponse.Triple("Incident", "OCCURRED_AT", "Building", "d"))));
    when(columnRepository.findByDatasetId(DS)).thenReturn(List.of(
        col("id"), col("bld"), col("loss")));
  }

  private static DatasetColumnResponse col(String nm) {
    return new DatasetColumnResponse(1L, nm, nm, "TEXT", null, true, false, null, 0, false);
  }

  private static MappingSpec validSpec() {
    return new MappingSpec(
        List.of(
            new MappingSpec.EntityMapping("Incident", "id",
                List.of(new MappingSpec.PropertyMapping("loss", "피해액"))),
            new MappingSpec.EntityMapping("Building", "bld", List.of())),
        List.of(new MappingSpec.RelationMapping(0, "OCCURRED_AT", 1)));
  }

  @Test
  void save_유효매핑은_draft로_저장하고_응답반환() {
    MappingResponse res = service.save(DS, validSpec(), 42L);
    assertThat(res.status()).isEqualTo("draft");
    assertThat(res.ontologyId()).isEqualTo(1L);
    verify(mappingRepository).upsert(eq(DS), eq(1L), anyString(), eq("draft"), eq(42L));
  }

  @Test
  void save_미바인딩_데이터셋은_400() {
    when(bindingRepository.findOntologyIdByDataset(DS)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.save(DS, validSpec(), 42L))
        .isInstanceOf(IllegalArgumentException.class);
    verify(mappingRepository, never()).upsert(anyLong(), anyLong(), anyString(), anyString(), any());
  }

  @Test
  void save_온톨로지에_없는_엔티티타입은_400() {
    MappingSpec bad = new MappingSpec(
        List.of(new MappingSpec.EntityMapping("NoSuch", "id", List.of())), List.of());
    assertThatThrownBy(() -> service.save(DS, bad, 42L)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void save_데이터셋에_없는_nameColumn은_400() {
    MappingSpec bad = new MappingSpec(
        List.of(new MappingSpec.EntityMapping("Incident", "nope", List.of())), List.of());
    assertThatThrownBy(() -> service.save(DS, bad, 42L)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void save_엔티티타입에_없는_속성은_400() {
    MappingSpec bad = new MappingSpec(
        List.of(new MappingSpec.EntityMapping("Incident", "id",
            List.of(new MappingSpec.PropertyMapping("loss", "없는속성")))), List.of());
    assertThatThrownBy(() -> service.save(DS, bad, 42L)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void save_허용되지않은_트리플은_400() {
    MappingSpec bad = new MappingSpec(
        List.of(new MappingSpec.EntityMapping("Incident", "id", List.of()),
            new MappingSpec.EntityMapping("Building", "bld", List.of())),
        List.of(new MappingSpec.RelationMapping(1, "OCCURRED_AT", 0))); // Building-OCCURRED_AT->Incident 없음
    assertThatThrownBy(() -> service.save(DS, bad, 42L)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void save_relation_ref_범위밖이면_400() {
    MappingSpec bad = new MappingSpec(
        List.of(new MappingSpec.EntityMapping("Incident", "id", List.of())),
        List.of(new MappingSpec.RelationMapping(0, "OCCURRED_AT", 5))); // objectRef 범위 초과
    assertThatThrownBy(() -> service.save(DS, bad, 42L)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void get_저장된_매핑을_역직렬화해_반환() {
    when(mappingRepository.findByDataset(DS)).thenReturn(Optional.of(new StoredMapping(
        1L, "{\"entities\":[{\"entityType\":\"Incident\",\"nameColumn\":\"id\",\"properties\":[]}],\"relations\":[]}", "draft")));
    Optional<MappingResponse> res = service.get(DS);
    assertThat(res).isPresent();
    assertThat(res.get().spec().entities()).hasSize(1);
    assertThat(res.get().spec().entities().get(0).entityType()).isEqualTo("Incident");
  }

  @Test
  void get_없으면_empty() {
    when(mappingRepository.findByDataset(DS)).thenReturn(Optional.empty());
    assertThat(service.get(DS)).isEmpty();
  }

  @Test
  void activate_재검증후_active로_전환() {
    when(mappingRepository.findByDataset(DS)).thenReturn(Optional.of(new StoredMapping(
        1L, "{\"entities\":[{\"entityType\":\"Incident\",\"nameColumn\":\"id\",\"properties\":[]}],\"relations\":[]}", "draft")));
    MappingResponse res = service.activate(DS, 44L);
    assertThat(res.status()).isEqualTo("active");
    verify(mappingRepository).updateStatus(DS, "active", 44L);
  }

  @Test
  void activate_매핑없으면_400() {
    when(mappingRepository.findByDataset(DS)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.activate(DS, 44L)).isInstanceOf(IllegalArgumentException.class);
    verify(mappingRepository, never()).updateStatus(anyLong(), anyString(), any());
  }
}
