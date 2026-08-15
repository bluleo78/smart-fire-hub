package com.smartfirehub.ontology.element;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.ontology.dto.OntologyResponse;
import com.smartfirehub.ontology.element.dto.ElementDtos.CreatePropertyRequest;
import com.smartfirehub.ontology.element.dto.ElementDtos.UpdatePropertyRequest;
import com.smartfirehub.ontology.exception.OntologyElementNotFoundException;
import com.smartfirehub.support.OntologyTestSupport;
import java.util.List;
import org.junit.jupiter.api.Test;

// 속성 요소 CRUD 테스트. 픽스처(ontologyId, setUp, typeId)는 베이스가 소유하므로 다시 쓰지 않는다.
class PropertyElementTest extends OntologyElementTestSupport {

  @Test
  void 속성을_추가하면_id와_증가된_버전을_반환하고_맨_뒤에_붙는다() {
    long sensorId = typeId("Sensor");
    elementService.addProperty(ontologyId, sensorId,
        new CreatePropertyRequest("model", "모델명", "text", null));

    int before = ontologyRepository.currentSchemaVersion(ontologyId);
    var result = elementService.addProperty(ontologyId, sensorId,
        new CreatePropertyRequest("설치일", "설치 일자를 YYYY-MM-DD로", "date", null));

    assertThat(result.property().id()).isNotNull();
    assertThat(result.schemaVersion()).isEqualTo(before + 1);
    assertThat(properties("Sensor")).extracting(OntologyResponse.Property::name)
        .containsExactly("model", "설치일");
  }

  // 문구는 OntologyService.validateCore와 글자 그대로 같아야 한다(프론트 e2e가 문구로 단언한다).
  @Test
  void 예약어는_속성명으로_거부된다() {
    assertThatThrownBy(() -> elementService.addProperty(ontologyId, typeId("Sensor"),
        new CreatePropertyRequest("type", "x", "text", null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("예약어는 속성명으로 쓸 수 없습니다: type");
  }

  // blank 검사가 중복 검사보다 먼저여야 한다(#302) — 빈 이름 2개는 서로 같은 키라서
  // 중복으로 오진단되고, 사용자는 "미입력"이라는 진짜 원인을 못 본다.
  @Test
  void 빈_속성명은_중복이_아니라_빈_이름으로_진단된다() {
    assertThatThrownBy(() -> elementService.addProperty(ontologyId, typeId("Sensor"),
        new CreatePropertyRequest("  ", "x", "text", null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("속성명은 비어 있을 수 없습니다: Sensor");
  }

  @Test
  void 같은_타입_안의_중복_속성명은_거부된다() {
    long sensorId = typeId("Sensor");
    elementService.addProperty(ontologyId, sensorId, new CreatePropertyRequest("model", "x", "text", null));

    assertThatThrownBy(() -> elementService.addProperty(ontologyId, sensorId,
        new CreatePropertyRequest("model", "y", "text", null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("중복된 속성명(Sensor): model");
  }

  @Test
  void 잘못된_dataType은_거부된다() {
    assertThatThrownBy(() -> elementService.addProperty(ontologyId, typeId("Sensor"),
        new CreatePropertyRequest("model", "x", "float", null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("데이터 타입은 text|number|date 중 하나여야 합니다: model");
  }

  // (Task 7 리뷰 I-1) description은 NOT NULL 컬럼(#305) — null이 그대로 INSERT되면 제약 위반 500이
  // 새어나간다. OntologyRules.validatePropertyCommon이 요소 경로(addProperty)에서도 이 규칙을 막는지 고정한다.
  @Test
  void null_description을_가진_속성_추가는_거부된다() {
    assertThatThrownBy(() -> elementService.addProperty(ontologyId, typeId("Sensor"),
        new CreatePropertyRequest("model", null, "text", null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("속성 설명(description)은 null일 수 없습니다");
  }

  // (Task 7 리뷰 I-2) 컬럼 제약은 NOT NULL일 뿐 NOT BLANK가 아니다 — 빈 문자열 description은 허용해야
  // 기존에 그렇게 저장된 데이터가 요소 경로로도 정상 왕복된다.
  @Test
  void 빈_문자열_description은_허용된다() {
    var result = elementService.addProperty(ontologyId, typeId("Sensor"),
        new CreatePropertyRequest("model", "", "text", null));

    assertThat(result.property().description()).isEmpty();
  }

  @Test
  void 속성_수정은_지정한_필드만_바꾸고_버전을_올린다() {
    long sensorId = typeId("Sensor");
    long propId = elementService.addProperty(ontologyId, sensorId,
        new CreatePropertyRequest("피해액", "설명", "number", "원")).property().id();

    int before = ontologyRepository.currentSchemaVersion(ontologyId);
    var result = elementService.updateProperty(ontologyId, sensorId, propId,
        new UpdatePropertyRequest(null, "바뀐 설명", null, null));

    assertThat(result.schemaVersion()).isEqualTo(before + 1);
    assertThat(result.property().name()).isEqualTo("피해액");
    assertThat(result.property().description()).isEqualTo("바뀐 설명");
    assertThat(result.property().dataType()).isEqualTo("number");
    assertThat(result.property().unit()).isEqualTo("원");
  }

  // PATCH 경로도 add 경로와 같은 dataType 열거를 지켜야 한다 — 아니면 잘못된 값이 DB
  // CHECK(text|number|date) 제약까지 그대로 흘러가 400이 아니라 500으로 새어나간다.
  @Test
  void 속성_수정의_잘못된_dataType은_거부된다() {
    long sensorId = typeId("Sensor");
    long propId = elementService.addProperty(ontologyId, sensorId,
        new CreatePropertyRequest("model", "x", "text", null)).property().id();

    assertThatThrownBy(() -> elementService.updateProperty(ontologyId, sensorId, propId,
        new UpdatePropertyRequest(null, null, "float", null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("데이터 타입은 text|number|date 중 하나여야 합니다: model");
  }

  // unit이 없다는 것은 항상 같은 저장 형태(NULL)여야 한다 — 생성 시점과 수정 시점에
  // 서로 다른 표현("" vs NULL)을 가지면 나중에 읽는 쪽이 둘 다 처리해야 한다.
  @Test
  void 빈_문자열_단위는_생성_시점에도_null로_저장된다() {
    long sensorId = typeId("Sensor");
    long propId = elementService.addProperty(ontologyId, sensorId,
        new CreatePropertyRequest("model", "x", "text", "")).property().id();

    assertThat(properties("Sensor")).filteredOn(p -> p.id() == propId)
        .extracting(OntologyResponse.Property::unit).containsExactly((String) null);
  }

  @Test
  void 빈_문자열_단위로_수정하면_null로_저장되고_실제_값은_그대로_왕복한다() {
    long sensorId = typeId("Sensor");
    long propId = elementService.addProperty(ontologyId, sensorId,
        new CreatePropertyRequest("피해액", "x", "number", "원")).property().id();

    var cleared = elementService.updateProperty(ontologyId, sensorId, propId,
        new UpdatePropertyRequest(null, null, null, ""));
    assertThat(cleared.property().unit()).isNull();

    var restored = elementService.updateProperty(ontologyId, sensorId, propId,
        new UpdatePropertyRequest(null, null, null, "톤"));
    assertThat(restored.property().unit()).isEqualTo("톤");
  }

  @Test
  void 속성_삭제는_해당_행만_없애고_버전을_올린다() {
    long sensorId = typeId("Sensor");
    long a = elementService.addProperty(ontologyId, sensorId,
        new CreatePropertyRequest("a", "x", "text", null)).property().id();
    elementService.addProperty(ontologyId, sensorId, new CreatePropertyRequest("b", "y", "text", null));

    int before = ontologyRepository.currentSchemaVersion(ontologyId);
    var result = elementService.deleteProperty(ontologyId, sensorId, a);

    assertThat(result.schemaVersion()).isEqualTo(before + 1);
    assertThat(properties("Sensor")).extracting(OntologyResponse.Property::name).containsExactly("b");
  }

  // 다른 타입에 속한 속성 id를 넘기면 거부되어야 한다 — id만 알면 남의 속성을 고칠 수 있으면 안 된다.
  @Test
  void 소속이_다른_속성_id는_거부된다() {
    long propId = elementService.addProperty(ontologyId, typeId("Sensor"),
        new CreatePropertyRequest("model", "x", "text", null)).property().id();

    assertThatThrownBy(() -> elementService.deleteProperty(ontologyId, typeId("Building"), propId))
        .isInstanceOf(OntologyElementNotFoundException.class)
        .hasMessageContaining("존재하지 않는 속성입니다");
  }

  // 삭제 경로만 검증하면 수정 경로의 같은 스코프 검사가 빠질 수 있다 — 별도로 확인한다.
  @Test
  void 소속이_다른_속성_id는_수정도_거부된다() {
    long propId = elementService.addProperty(ontologyId, typeId("Sensor"),
        new CreatePropertyRequest("model", "x", "text", null)).property().id();

    assertThatThrownBy(() -> elementService.updateProperty(ontologyId, typeId("Building"), propId,
        new UpdatePropertyRequest(null, "바뀐 설명", null, null)))
        .isInstanceOf(OntologyElementNotFoundException.class)
        .hasMessageContaining("존재하지 않는 속성입니다");
  }

  // entityTypeId 자체가 다른 온톨로지에 속한 경우도 404여야 한다 — requireType이 ontologyId
  // 스코프로 이미 걸러야 하고, id만 알면 남의 온톨로지 타입 아래에 속성을 붙일 수 없어야 한다.
  @Test
  void 다른_온톨로지의_엔티티_타입_id는_거부된다() {
    long otherOntologyId = OntologyTestSupport.createWithStatus(
        ontologyService, "요소편집테스트-남의온톨로지-" + System.nanoTime(), "draft");
    try {
      long otherTypeId = ontologyRepository.findById(otherOntologyId).entities().get(0).id();

      assertThatThrownBy(() -> elementService.addProperty(ontologyId, otherTypeId,
          new CreatePropertyRequest("model", "x", "text", null)))
          .isInstanceOf(OntologyElementNotFoundException.class)
          .hasMessageContaining("존재하지 않는 엔티티 타입입니다");
    } finally {
      OntologyTestSupport.deleteRowAsDefaultTenant(tx, dsl, otherOntologyId);
    }
  }

  private List<OntologyResponse.Property> properties(String type) {
    return ontologyRepository.findById(ontologyId).entities().stream()
        .filter(e -> e.type().equals(type)).findFirst().orElseThrow().properties();
  }
}
