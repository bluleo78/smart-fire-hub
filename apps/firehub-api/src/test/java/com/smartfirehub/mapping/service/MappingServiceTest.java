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

    // 기본 fixture: DS는 온톨로지 1에 바인딩, 온톨로지는 Incident/Building + OCCURRED_AT.
    // 속성은 dataType 축(number/date/text/미지정)별로 하나씩 두어 타입 conformance 검증을 커버한다.
    when(bindingRepository.findOntologyIdByDataset(DS)).thenReturn(Optional.of(1L));
    when(ontologyRepository.findById(1L)).thenReturn(new OntologyResponse("화재조사", 1,
        List.of(
            new OntologyResponse.EntityType("Incident", "사건", "명명", "exact",
                List.of(new OntologyResponse.Property("피해액", "d", "number", "원"),
                    new OntologyResponse.Property("발생일시", "d", "date", null),
                    new OntologyResponse.Property("비고", "d", "text", null),
                    new OntologyResponse.Property("미지정", "d", null, null)), 10L),
            new OntologyResponse.EntityType("Building", "건물", "명명", "embedding", List.of(), 11L)),
        List.of(new OntologyResponse.Triple("Incident", "OCCURRED_AT", "Building", "d"))));
    // 컬럼도 타입 축별로: 문자열(id/bld/email), 숫자(loss), 날짜(at), 미지 타입(weird).
    when(columnRepository.findByDatasetId(DS)).thenReturn(List.of(
        col("id", "TEXT"), col("bld", "TEXT"), col("loss", "DECIMAL"),
        col("at", "TIMESTAMP"), col("email", "VARCHAR"), col("weird", "MONEY")));
  }

  private static DatasetColumnResponse col(String nm, String type) {
    return new DatasetColumnResponse(1L, nm, nm, type, null, true, false, null, 0, false);
  }

  // 단일 속성 매핑만 담은 최소 스펙(타입 검증 케이스용).
  private static MappingSpec specWithProperty(String column, String propertyName) {
    return new MappingSpec(
        List.of(new MappingSpec.EntityMapping("Incident", "id",
            List.of(new MappingSpec.PropertyMapping(column, propertyName)))),
        List.of());
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

  // --- 속성 dataType conformance (#324) ---

  @Test
  void save_number속성에_문자열컬럼_연결시_400_메시지에_엔티티_속성_컬럼_명시() {
    assertThatThrownBy(() -> service.save(DS, specWithProperty("email", "피해액"), 42L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Incident")
        .hasMessageContaining("피해액")
        .hasMessageContaining("number")
        .hasMessageContaining("email")
        .hasMessageContaining("VARCHAR");
    verify(mappingRepository, never()).upsert(anyLong(), anyLong(), anyString(), anyString(), any());
  }

  @Test
  void save_date속성에_숫자컬럼_연결시_400() {
    assertThatThrownBy(() -> service.save(DS, specWithProperty("loss", "발생일시"), 42L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("발생일시");
  }

  @Test
  void save_number속성에_숫자컬럼은_통과() {
    assertThat(service.save(DS, specWithProperty("loss", "피해액"), 42L).status()).isEqualTo("draft");
  }

  @Test
  void save_date속성에_TIMESTAMP컬럼은_통과() {
    assertThat(service.save(DS, specWithProperty("at", "발생일시"), 42L).status()).isEqualTo("draft");
  }

  @Test
  void save_text속성에는_숫자컬럼도_통과() {
    // text는 어떤 값이든 문자열화해 담을 수 있으므로 제한하지 않는다.
    assertThat(service.save(DS, specWithProperty("loss", "비고"), 42L).status()).isEqualTo("draft");
  }

  @Test
  void save_dataType_미지정속성은_타입검사_생략() {
    assertThat(service.save(DS, specWithProperty("email", "미지정"), 42L).status()).isEqualTo("draft");
  }

  @Test
  void save_미지_컬럼타입은_판정보류하고_통과() {
    // 알 수 없는 컬럼 타입까지 문자열로 단정하면 정상 매핑을 거부할 수 있어 과소차단을 택했다.
    assertThat(service.save(DS, specWithProperty("weird", "피해액"), 42L).status()).isEqualTo("draft");
  }

  @Test
  void activate_속성타입_위반이면_400이고_상태전환없음() {
    when(mappingRepository.findByDataset(DS)).thenReturn(Optional.of(new StoredMapping(1L,
        "{\"entities\":[{\"entityType\":\"Incident\",\"nameColumn\":\"id\","
            + "\"properties\":[{\"column\":\"email\",\"propertyName\":\"피해액\"}]}],\"relations\":[]}",
        "draft")));
    assertThatThrownBy(() -> service.activate(DS, 44L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("피해액");
    verify(mappingRepository, never()).updateStatus(anyLong(), anyString(), any());
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
