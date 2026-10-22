package com.smartfirehub.ontology.element;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.dto.UpdateColumnRequest;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.service.DataTableService;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.mapping.dto.MappingSpec;
import com.smartfirehub.mapping.dto.MappingSpec.EntityMapping;
import com.smartfirehub.mapping.dto.MappingSpec.PropertyMapping;
import com.smartfirehub.mapping.dto.MappingSpec.RelationMapping;
import com.smartfirehub.mapping.dto.OntologyMappingUsage;
import com.smartfirehub.mapping.service.MappingReferenceService;
import com.smartfirehub.mapping.service.MappingService;
import com.smartfirehub.ontology.binding.DatasetOntologyRepository;
import com.smartfirehub.ontology.dto.CreateOntologyRequest;
import com.smartfirehub.ontology.dto.OntologyResponse;
import com.smartfirehub.ontology.element.dto.ElementDtos.CreateEntityTypeRequest;
import com.smartfirehub.ontology.element.dto.ElementDtos.CreatePropertyRequest;
import com.smartfirehub.ontology.element.dto.ElementDtos.CreateRelationRequest;
import com.smartfirehub.ontology.element.dto.ElementDtos.UpdateEntityTypeRequest;
import com.smartfirehub.ontology.element.dto.ElementDtos.UpdatePropertyRequest;
import com.smartfirehub.ontology.element.dto.ElementDtos.UpdateRelationRequest;
import com.smartfirehub.support.OntologyTestSupport;
import com.smartfirehub.support.PausedTransactionRace;
import com.smartfirehub.support.SecurityFixture;
import com.smartfirehub.support.TestUsers;
import java.util.ArrayList;
import java.util.List;
import org.jooq.JSONB;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * WD-129: 지식 모델 요소 편집이 데이터셋 매핑(이름 참조)을 깨지 않는지 검증한다.
 *
 * <ul>
 *   <li>이름 변경(타입·속성·관계)은 같은 온톨로지의 draft·active 매핑에 연쇄 반영된다 — 다른 타입의 같은 이름 속성, 다른 타입 쌍의 같은 이름 관계, 다른
 *       온톨로지의 매핑은 건드리지 않는다.
 *   <li>매핑이 쓰는 요소의 삭제는 409(데이터셋 이름 포함)로 막히고 아무것도 지워지지 않는다. 쓰지 않으면 그대로 지워진다.
 *   <li>WD-185: 속성 자료형 변경은 매핑이 그 속성에 연결한 컬럼 타입과 맞지 않으면 409 로 막히고 아무것도 바뀌지 않는다.
 *   <li>WD-186: 반대쪽 끝 — 데이터셋 필드 이름 변경은 그 데이터셋의 매핑에 연쇄 반영되고, 매핑이 쓰는 필드 삭제·연결된 속성 자료형과 맞지 않는 필드 타입
 *       변경은 409 로 막힌다. 필드 변경과 매핑 저장은 데이터셋 행 잠금으로 직렬화된다.
 * </ul>
 *
 * <p>픽스처 온톨로지(Sensor·Building, Sensor-INSTALLED_IN-Building)는 베이스가 만들고 지운다. 매핑 행은 ontology FK
 * CASCADE 로 함께 사라지므로 데이터셋 행만 직접 지운다.
 */
class OntologyElementMappingRefTest extends OntologyElementTestSupport {

  @Autowired private ObjectMapper objectMapper;
  @Autowired private PasswordEncoder encoder;
  @Autowired private MappingReferenceService mappingReferences;
  // WD-186: 필드 변경 경로(실제 물리 테이블 DDL 포함)와 매핑 저장을 함께 검증한다.
  @Autowired private DatasetService datasetService;
  @Autowired private DataTableService dataTableService;
  @Autowired private DatasetColumnRepository columnRepository;
  @Autowired private DatasetRepository datasetRepository;
  @Autowired private DatasetOntologyRepository bindingRepository;
  @Autowired private MappingService mappingService;

  private long userId;
  private final List<Long> datasetIds = new ArrayList<>();
  // WD-186: DatasetService 로 만든 데이터셋의 물리 테이블(data 스키마) — 데이터셋 행 삭제로는 사라지지 않아 따로 지운다.
  private final List<String> tableNames = new ArrayList<>();
  private Long otherOntologyId;

  // 데이터셋 이름은 조회자의 보안 등급 가시성으로 걸러진다 — 기본 등급 데이터셋을 볼 수 있는 테넌트 멤버로 인증 컨텍스트를 세운다.
  @BeforeEach
  void authenticate() {
    long suffix = System.nanoTime();
    userId =
        TestUsers.createMember(
                dsl,
                tx,
                encoder,
                "wd129_" + suffix,
                "wd129_" + suffix + "@example.com",
                "Password123",
                "WD129 사용자",
                DEFAULT_TEST_TENANT_ID)
            .id();
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(userId, null, List.of()));
  }

  @AfterEach
  void cleanup() {
    SecurityContextHolder.clearContext();
    OntologyTestSupport.deleteRowAsDefaultTenant(tx, dsl, otherOntologyId);
    otherOntologyId = null;
    inTenantFixture(
        () -> {
          for (String t : tableNames) {
            dataTableService.dropTable(t);
          }
          for (Long id : datasetIds) {
            // 고아 매핑 시나리오는 데이터셋을 먼저 지운다 — 매핑 행은 ontology CASCADE 로 정리된다.
            dsl.deleteFrom(table(name("dataset_mapping")))
                .where(field(name("dataset_id"), Long.class).eq(id))
                .execute();
            dsl.deleteFrom(table(name("dataset")))
                .where(field(name("id"), Long.class).eq(id))
                .execute();
          }
        });
    datasetIds.clear();
    tableNames.clear();
    TestUsers.cleanup(dsl, tx, userId, DEFAULT_TEST_TENANT_ID);
  }

  // ── 픽스처 ────────────────────────────────────────────────────────────

  // 데이터셋 행만 만든다 — 매핑 연쇄는 dataset 메타데이터(이름)만 읽으므로 data 스키마 테이블은 필요 없다.
  // 보안 등급은 테넌트 기본 등급(컬럼 DEFAULT)이다 — 테스트 사용자가 볼 수 있다.
  private long dataset(String datasetName) {
    long id =
        inTenantFixture(
            () ->
                dsl.insertInto(table(name("dataset")))
                    .set(field(name("name"), String.class), datasetName + "-" + System.nanoTime())
                    .set(field(name("table_name"), String.class), "wd129_" + System.nanoTime())
                    .set(field(name("storage_type"), String.class), "TABLE")
                    .set(field(name("origin_type"), String.class), "SOURCE")
                    .set(field(name("created_by"), Long.class), userId)
                    .returning(field(name("id"), Long.class))
                    .fetchOne()
                    .get(field(name("id"), Long.class)));
    datasetIds.add(id);
    return id;
  }

  // 테스트 사용자(USER 역할)의 자격보다 높은 등급의 데이터셋 — 목록·상세에서 404 로 숨겨지는 데이터셋이다.
  private long datasetAbove(String datasetName, String levelName) {
    long ds = dataset(datasetName);
    long levelId = new SecurityFixture(dsl, tx, encoder).levelId(levelName);
    inTenantFixture(
        () ->
            dsl.update(table(name("dataset")))
                .set(field(name("security_level_id"), Long.class), levelId)
                .where(field(name("id"), Long.class).eq(ds))
                .execute());
    return ds;
  }

  // WD-185: 자료형 호환 판정은 매핑이 연결한 컬럼의 실제 타입(dataset_column.data_type)을 읽는다. 데이터셋 삭제 시 CASCADE 로 함께
  // 지워진다. dataType 은 V2 CHECK 값(TEXT·INTEGER·DECIMAL·BOOLEAN·DATE·TIMESTAMP) 중 하나.
  private void column(long datasetId, String columnName, String dataType) {
    inTenantFixture(
        () ->
            dsl.insertInto(table(name("dataset_column")))
                .set(field(name("dataset_id"), Long.class), datasetId)
                .set(field(name("column_name"), String.class), columnName)
                .set(field(name("data_type"), String.class), dataType)
                .set(field(name("is_nullable"), Boolean.class), true)
                .set(field(name("is_indexed"), Boolean.class), false)
                .set(field(name("is_primary_key"), Boolean.class), false)
                .set(field(name("column_order"), Integer.class), 1)
                .execute());
  }

  private String datasetName(long datasetId) {
    return inTenantFixture(
        () ->
            dsl.select(field(name("name"), String.class))
                .from(table(name("dataset")))
                .where(field(name("id"), Long.class).eq(datasetId))
                .fetchOne(0, String.class));
  }

  // 매핑 행을 직접 넣는다 — MappingService.save 는 바인딩·컬럼 검증을 요구해 픽스처가 커진다. 여기서 보는 것은 "저장된 spec 이 어떻게
  // 바뀌는가"뿐이다.
  private void mapping(long datasetId, long ontology, MappingSpec spec, String status) {
    inTenantFixture(
        () ->
            dsl.insertInto(table(name("dataset_mapping")))
                .set(field(name("dataset_id"), Long.class), datasetId)
                .set(field(name("ontology_id"), Long.class), ontology)
                .set(field(name("spec"), JSONB.class), JSONB.valueOf(write(spec)))
                .set(field(name("status"), String.class), status)
                .execute());
  }

  private MappingSpec specOf(long datasetId) {
    return read(storedRow(datasetId).get(field(name("spec"), JSONB.class)).data());
  }

  private Record storedRow(long datasetId) {
    return inTenantFixture(
        () ->
            dsl.select(field(name("spec"), JSONB.class), field(name("status"), String.class))
                .from(table(name("dataset_mapping")))
                .where(field(name("dataset_id"), Long.class).eq(datasetId))
                .fetchOne());
  }

  private String write(MappingSpec spec) {
    try {
      return objectMapper.writeValueAsString(spec);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private MappingSpec read(String json) {
    try {
      return objectMapper.readValue(json, MappingSpec.class);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  // Sensor(속성 model) + Building(속성 model) 를 INSTALLED_IN 으로 잇는 기본 매핑.
  private static MappingSpec sensorBuildingSpec() {
    return new MappingSpec(
        List.of(
            new EntityMapping("Sensor", "sensor_id", List.of(new PropertyMapping("m1", "model"))),
            new EntityMapping(
                "Building", "building_id", List.of(new PropertyMapping("m2", "model")))),
        List.of(new RelationMapping(0, "INSTALLED_IN", 1)));
  }

  private void addModelProperties() {
    elementService.addProperty(
        ontologyId, typeId("Sensor"), new CreatePropertyRequest("model", "모델", "text", null));
    elementService.addProperty(
        ontologyId, typeId("Building"), new CreatePropertyRequest("model", "모델", "text", null));
  }

  private long propertyId(String type, String property) {
    return ontologyRepository.findById(ontologyId).entities().stream()
        .filter(e -> e.type().equals(type))
        .flatMap(e -> e.properties().stream())
        .filter(p -> p.name().equals(property))
        .findFirst()
        .orElseThrow()
        .id();
  }

  private long relationId(String subject, String relation, String object) {
    return ontologyRepository.findById(ontologyId).relations().stream()
        .filter(
            t ->
                t.subject().equals(subject)
                    && t.relation().equals(relation)
                    && t.object().equals(object))
        .findFirst()
        .orElseThrow()
        .id();
  }

  // 같은 타입 이름(Sensor·Building)과 같은 관계명을 가진 별도 온톨로지 — 온톨로지 스코프 검증용.
  private long otherOntology() {
    otherOntologyId =
        ontologyService.createOntology(
            new CreateOntologyRequest(
                "요소편집-타온톨로지-" + System.nanoTime(),
                List.of(
                    new OntologyResponse.EntityType(
                        "Sensor",
                        "센서",
                        "표기 그대로",
                        "embedding",
                        List.of(new OntologyResponse.Property("model", "모델", "text", null))),
                    new OntologyResponse.EntityType(
                        "Building",
                        "건물",
                        "표기 그대로",
                        "embedding",
                        List.of(new OntologyResponse.Property("model", "모델", "text", null)))),
                List.of(new OntologyResponse.Triple("Sensor", "INSTALLED_IN", "Building", "d")),
                "draft"));
    return otherOntologyId;
  }

  // ── 이름 변경 연쇄 ──────────────────────────────────────────────────────

  @Test
  void 타입_이름을_바꾸면_draft와_active_매핑의_entityType이_함께_바뀌고_상태는_그대로다() {
    addModelProperties();
    long draftDs = dataset("draft표");
    long activeDs = dataset("active표");
    mapping(draftDs, ontologyId, sensorBuildingSpec(), "draft");
    mapping(activeDs, ontologyId, sensorBuildingSpec(), "active");

    elementService.updateEntityType(
        ontologyId, typeId("Sensor"), new UpdateEntityTypeRequest("Detector", null, null, null));

    for (long ds : List.of(draftDs, activeDs)) {
      MappingSpec spec = specOf(ds);
      assertThat(spec.entities())
          .extracting(EntityMapping::entityType)
          .containsExactly("Detector", "Building");
      // 관계는 인덱스 참조라 손대지 않아도 새 이름의 엔티티를 가리킨다.
      assertThat(spec.relations()).containsExactly(new RelationMapping(0, "INSTALLED_IN", 1));
      assertThat(spec.entities().get(0).properties())
          .containsExactly(new PropertyMapping("m1", "model"));
    }
    // 이름만 맞추는 갱신이 active 매핑을 draft 로 떨어뜨리면 안 된다.
    assertThat(storedRow(activeDs).get(field(name("status"), String.class))).isEqualTo("active");
    assertThat(storedRow(draftDs).get(field(name("status"), String.class))).isEqualTo("draft");
  }

  @Test
  void 다른_온톨로지의_같은_이름_매핑은_바뀌지_않는다() {
    addModelProperties();
    long mine = dataset("내표");
    long others = dataset("남의표");
    mapping(mine, ontologyId, sensorBuildingSpec(), "active");
    mapping(others, otherOntology(), sensorBuildingSpec(), "active");

    elementService.updateEntityType(
        ontologyId, typeId("Sensor"), new UpdateEntityTypeRequest("Detector", null, null, null));
    elementService.updateProperty(
        ontologyId,
        typeId("Building"),
        propertyId("Building", "model"),
        new UpdatePropertyRequest("모델명", null, null, null));
    elementService.updateRelation(
        ontologyId,
        relationId("Detector", "INSTALLED_IN", "Building"),
        new UpdateRelationRequest("LOCATED_IN", null));

    assertThat(specOf(mine).entities().get(0).entityType()).isEqualTo("Detector");
    assertThat(specOf(others)).isEqualTo(sensorBuildingSpec());
  }

  @Test
  void 속성_이름을_바꾸면_그_타입의_속성_참조만_바뀐다() {
    addModelProperties();
    long ds = dataset("속성표");
    mapping(ds, ontologyId, sensorBuildingSpec(), "active");

    // Sensor.model 만 바꾼다 — Building.model 은 이름이 같아도 다른 속성이다.
    elementService.updateProperty(
        ontologyId,
        typeId("Sensor"),
        propertyId("Sensor", "model"),
        new UpdatePropertyRequest("모델명", null, null, null));

    MappingSpec spec = specOf(ds);
    assertThat(spec.entities().get(0).properties())
        .containsExactly(new PropertyMapping("m1", "모델명"));
    assertThat(spec.entities().get(1).properties())
        .containsExactly(new PropertyMapping("m2", "model"));
  }

  @Test
  void 관계_이름을_바꾸면_끝점까지_같은_관계_매핑만_바뀐다() {
    // 같은 관계명 INSTALLED_IN 이 Sensor→Room 에도 있다 — 트리플이 다르므로 그쪽 매핑은 그대로여야 한다.
    elementService.addEntityType(
        ontologyId, new CreateEntityTypeRequest("Room", "방", "표기 그대로", "embedding"));
    elementService.addRelation(
        ontologyId,
        new CreateRelationRequest(typeId("Sensor"), "INSTALLED_IN", typeId("Room"), "d"));
    long ds = dataset("관계표");
    mapping(
        ds,
        ontologyId,
        new MappingSpec(
            List.of(
                new EntityMapping("Sensor", "s", List.of()),
                new EntityMapping("Building", "b", List.of()),
                new EntityMapping("Room", "r", List.of())),
            List.of(
                new RelationMapping(0, "INSTALLED_IN", 1),
                new RelationMapping(0, "INSTALLED_IN", 2))),
        "draft");

    elementService.updateRelation(
        ontologyId,
        relationId("Sensor", "INSTALLED_IN", "Building"),
        new UpdateRelationRequest("LOCATED_IN", null));

    assertThat(specOf(ds).relations())
        .containsExactly(
            new RelationMapping(0, "LOCATED_IN", 1), new RelationMapping(0, "INSTALLED_IN", 2));
  }

  @Test
  void 이름이_그대로인_수정은_매핑을_다시_쓰지_않는다() {
    long ds = dataset("무변경표");
    mapping(ds, ontologyId, sensorBuildingSpec(), "active");
    var before =
        inTenantFixture(
            () ->
                dsl.select(field(name("updated_at"), java.time.OffsetDateTime.class))
                    .from(table(name("dataset_mapping")))
                    .where(field(name("dataset_id"), Long.class).eq(ds))
                    .fetchOne(0, java.time.OffsetDateTime.class));

    // 설명만 바꾼다(이름 null).
    elementService.updateEntityType(
        ontologyId, typeId("Sensor"), new UpdateEntityTypeRequest(null, "새 설명", null, null));

    var after =
        inTenantFixture(
            () ->
                dsl.select(field(name("updated_at"), java.time.OffsetDateTime.class))
                    .from(table(name("dataset_mapping")))
                    .where(field(name("dataset_id"), Long.class).eq(ds))
                    .fetchOne(0, java.time.OffsetDateTime.class));
    assertThat(after).isEqualTo(before);
  }

  // ── 삭제 차단 ───────────────────────────────────────────────────────────

  @Test
  void 매핑이_쓰는_타입은_409로_삭제되지_않고_데이터셋_이름이_문구에_실린다() {
    long ds = dataset("화재사건표");
    mapping(ds, ontologyId, sensorBuildingSpec(), "draft");
    int versionBefore = ontologyRepository.currentSchemaVersion(ontologyId);

    assertThatThrownBy(() -> elementService.deleteEntityType(ontologyId, typeId("Sensor")))
        .isInstanceOfSatisfying(
            CodedApiException.class,
            e -> {
              assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
              assertThat(e.code()).isEqualTo(MappingReferenceService.IN_USE_CODE);
              assertThat(e.getMessage())
                  .isEqualTo(
                      "「Sensor」 타입은 「"
                          + datasetName(ds)
                          + "」 데이터셋 매핑에서 사용 중이라 삭제할 수 없습니다. 매핑에서 먼저 빼 주세요.");
              // 존재 은닉: 볼 수 있는 데이터셋이어도 id 를 응답에 싣지 않는다(웹은 message 만 쓴다).
              assertThat(e.details()).isNull();
            });

    // 아무것도 지워지지 않았다 — 타입도, 함께 지워졌을 관계도, 스키마 버전도 그대로.
    OntologyResponse after = ontologyRepository.findById(ontologyId);
    assertThat(after.entities()).extracting(OntologyResponse.EntityType::type).contains("Sensor");
    assertThat(after.relations()).hasSize(1);
    assertThat(ontologyRepository.currentSchemaVersion(ontologyId)).isEqualTo(versionBefore);
  }

  @Test
  void 매핑이_쓰는_속성은_409로_삭제되지_않는다() {
    addModelProperties();
    long ds = dataset("속성사용표");
    mapping(ds, ontologyId, sensorBuildingSpec(), "active");
    long sensorModel = propertyId("Sensor", "model");

    assertThatThrownBy(
            () -> elementService.deleteProperty(ontologyId, typeId("Sensor"), sensorModel))
        .isInstanceOf(CodedApiException.class)
        .hasMessageContaining("「Sensor.model」 속성은 「" + datasetName(ds) + "」 데이터셋 매핑에서 사용 중");

    assertThat(propertyId("Sensor", "model")).isEqualTo(sensorModel);
  }

  @Test
  void 매핑이_쓰는_관계는_409로_삭제되지_않는다() {
    long ds = dataset("관계사용표");
    mapping(ds, ontologyId, sensorBuildingSpec(), "active");
    long rel = relationId("Sensor", "INSTALLED_IN", "Building");

    assertThatThrownBy(() -> elementService.deleteRelation(ontologyId, rel))
        .isInstanceOf(CodedApiException.class)
        .hasMessageContaining(
            "「Sensor → INSTALLED_IN → Building」 관계는 「" + datasetName(ds) + "」 데이터셋 매핑에서 사용 중");

    assertThat(ontologyRepository.findById(ontologyId).relations()).hasSize(1);
  }

  @Test
  void 매핑이_쓰지_않는_요소는_그대로_삭제된다() {
    addModelProperties();
    long ds = dataset("부분사용표");
    // Sensor 만 매핑 — Building.model·관계·Building 타입은 쓰지 않는다.
    mapping(
        ds,
        ontologyId,
        new MappingSpec(List.of(new EntityMapping("Sensor", "s", List.of())), List.of()),
        "active");

    elementService.deleteProperty(ontologyId, typeId("Building"), propertyId("Building", "model"));
    elementService.deleteRelation(ontologyId, relationId("Sensor", "INSTALLED_IN", "Building"));
    elementService.deleteEntityType(ontologyId, typeId("Building"));

    assertThat(ontologyRepository.findById(ontologyId).entities())
        .extracting(OntologyResponse.EntityType::type)
        .containsExactly("Sensor");
  }

  @Test
  void 다른_온톨로지의_매핑은_삭제를_막지_않는다() {
    long ds = dataset("타온톨로지표");
    mapping(ds, otherOntology(), sensorBuildingSpec(), "active");

    elementService.deleteRelation(ontologyId, relationId("Sensor", "INSTALLED_IN", "Building"));
    elementService.deleteEntityType(ontologyId, typeId("Sensor"));

    assertThat(ontologyRepository.findById(ontologyId).entities())
        .extracting(OntologyResponse.EntityType::type)
        .containsExactly("Building");
  }

  @Test
  void 데이터셋이_지워진_고아_매핑은_삭제를_막지_않는다() {
    long ds = dataset("지워진표");
    mapping(ds, ontologyId, sensorBuildingSpec(), "active");
    // 데이터셋 삭제는 dataset_mapping 을 지우지 않는다 — 사용자가 손댈 수 없는 매핑이 남는다.
    inTenantFixture(
        () ->
            dsl.deleteFrom(table(name("dataset")))
                .where(field(name("id"), Long.class).eq(ds))
                .execute());

    elementService.deleteEntityType(ontologyId, typeId("Sensor"));

    assertThat(ontologyRepository.findById(ontologyId).entities())
        .extracting(OntologyResponse.EntityType::type)
        .containsExactly("Building");
  }

  @Test
  void 볼_수_없는_데이터셋은_이름을_숨기고_개수로_알리되_삭제는_막는다() {
    // 테스트 사용자는 기본 등급 데이터셋은 보지만 '기밀' 데이터셋은 못 본다 — 자격 없는 인증(fail-closed)이 아니라 실제 등급 차이다.
    long visible = dataset("공개표");
    long secret = datasetAbove("비밀표", "기밀");
    mapping(visible, ontologyId, sensorBuildingSpec(), "active");
    mapping(secret, ontologyId, sensorBuildingSpec(), "active");
    String secretName = datasetName(secret);

    assertThatThrownBy(() -> elementService.deleteEntityType(ontologyId, typeId("Sensor")))
        .isInstanceOfSatisfying(
            CodedApiException.class,
            e -> {
              assertThat(e.getMessage())
                  .isEqualTo(
                      "「Sensor」 타입은 「"
                          + datasetName(visible)
                          + "」 데이터셋과 볼 수 없는 데이터셋 1개의 매핑에서 사용 중이라 삭제할 수 없습니다. 매핑에서 먼저 빼 주세요.")
                  .doesNotContain(secretName);
              assertThat(e.details()).isNull();
            });

    // 사용처 응답도 숨긴 데이터셋은 이름은 물론 id 도 없이 개수로만 싣는다.
    OntologyMappingUsage.ElementUsage sensor =
        mappingReferences.usage(ontologyId).entityTypes().get(typeId("Sensor"));
    assertThat(sensor.datasets())
        .containsExactly(new OntologyMappingUsage.UsageDataset(visible, datasetName(visible)));
    assertThat(sensor.hiddenCount()).isEqualTo(1);
    assertThat(sensor.datasets())
        .extracting(OntologyMappingUsage.UsageDataset::datasetId)
        .doesNotContain(secret);
  }

  // ── 동시성: 요소 편집과 매핑 저장의 직렬화 ───────────────────────────────────────

  // 매핑 저장(MappingService.validate 가 잡는 온톨로지 FOR SHARE)이 진행 중일 때 타입 삭제는 그 커밋을 기다렸다가, 방금 생긴 참조를
  // 보고 409 로 막혀야 한다. 잠금이 없으면 삭제가 커밋 전 매핑을 못 본 채 통과해 매핑이 사라진 타입을 가리키게 된다.
  @Test
  void 진행_중인_매핑_저장이_끝날_때까지_타입_삭제가_기다렸다가_409로_막힌다() throws Exception {
    long ds = dataset("동시저장표");
    long sensorId = typeId("Sensor");

    PausedTransactionRace.Outcome<Object> out =
        PausedTransactionRace.run(
            tx,
            DEFAULT_TEST_TENANT_ID,
            () -> {
              ontologyRepository.lockForShare(ontologyId);
              dsl.insertInto(table(name("dataset_mapping")))
                  .set(field(name("dataset_id"), Long.class), ds)
                  .set(field(name("ontology_id"), Long.class), ontologyId)
                  .set(field(name("spec"), JSONB.class), JSONB.valueOf(write(sensorBuildingSpec())))
                  .set(field(name("status"), String.class), "draft")
                  .execute();
            },
            () -> elementService.deleteEntityType(ontologyId, sensorId));

    assertThat(out.secondBlocked()).isTrue();
    assertThat(out.secondError()).isInstanceOf(CodedApiException.class);
    assertThat(ontologyRepository.findById(ontologyId).entities())
        .extracting(OntologyResponse.EntityType::type)
        .contains("Sensor");
  }

  // 같은 구도에서 이름 변경은 매핑 저장 커밋 뒤의 매핑까지 새 이름으로 맞춰야 한다(옛 이름이 남지 않음).
  @Test
  void 진행_중인_매핑_저장이_끝난_뒤_이름_변경이_그_매핑까지_반영한다() throws Exception {
    long ds = dataset("동시리네임표");
    long sensorId = typeId("Sensor");

    PausedTransactionRace.Outcome<Object> out =
        PausedTransactionRace.run(
            tx,
            DEFAULT_TEST_TENANT_ID,
            () -> {
              ontologyRepository.lockForShare(ontologyId);
              dsl.insertInto(table(name("dataset_mapping")))
                  .set(field(name("dataset_id"), Long.class), ds)
                  .set(field(name("ontology_id"), Long.class), ontologyId)
                  .set(field(name("spec"), JSONB.class), JSONB.valueOf(write(sensorBuildingSpec())))
                  .set(field(name("status"), String.class), "active")
                  .execute();
            },
            () ->
                elementService.updateEntityType(
                    ontologyId,
                    sensorId,
                    new UpdateEntityTypeRequest("Detector", null, null, null)));

    assertThat(out.secondBlocked()).isTrue();
    assertThat(out.secondError()).isNull();
    assertThat(specOf(ds).entities())
        .extracting(EntityMapping::entityType)
        .containsExactly("Detector", "Building");
  }

  // ── 자료형 변경 차단(WD-185) ─────────────────────────────────────────────

  // Building 에 text 속성 note 를 두고, 데이터셋 컬럼 memo(columnType) 를 note 에 연결한 매핑을 만든다.
  private long buildingNoteMapped(String datasetName, String columnType, String status) {
    elementService.addProperty(
        ontologyId, typeId("Building"), new CreatePropertyRequest("note", "메모", "text", null));
    long ds = dataset(datasetName);
    column(ds, "memo", columnType);
    mapping(ds, ontologyId, buildingNoteSpec(), status);
    return ds;
  }

  private static MappingSpec buildingNoteSpec() {
    return new MappingSpec(
        List.of(
            new EntityMapping(
                "Building", "building_id", List.of(new PropertyMapping("memo", "note")))),
        List.of());
  }

  private String dataTypeOf(String type, String property) {
    return ontologyRepository.findById(ontologyId).entities().stream()
        .filter(e -> e.type().equals(type))
        .flatMap(e -> e.properties().stream())
        .filter(p -> p.name().equals(property))
        .findFirst()
        .orElseThrow()
        .dataType();
  }

  @Test
  void 매핑된_컬럼과_맞지_않는_자료형으로_바꾸면_409로_막히고_아무것도_바뀌지_않는다() {
    long ds = buildingNoteMapped("건물대장", "TEXT", "active");
    long noteId = propertyId("Building", "note");
    int versionBefore = ontologyRepository.currentSchemaVersion(ontologyId);

    assertThatThrownBy(
            () ->
                elementService.updateProperty(
                    ontologyId,
                    typeId("Building"),
                    noteId,
                    new UpdatePropertyRequest(null, null, "number", null)))
        .isInstanceOfSatisfying(
            CodedApiException.class,
            e -> {
              assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
              assertThat(e.code()).isEqualTo(MappingReferenceService.TYPE_CONFLICT_CODE);
              assertThat(e.getMessage())
                  .isEqualTo(
                      "「Building.note」 속성은 「"
                          + datasetName(ds)
                          + "」 데이터셋 매핑의 텍스트 컬럼 「memo」에 연결되어 있어 숫자로 바꿀 수 없습니다. 매핑에서 연결을 먼저 바꾸세요.");
              // 삭제 차단과 같은 존재 은닉 규칙 — id 를 응답에 싣지 않는다.
              assertThat(e.details()).isNull();
            });

    // 자료형·매핑·스키마 버전 모두 그대로다.
    assertThat(dataTypeOf("Building", "note")).isEqualTo("text");
    assertThat(specOf(ds)).isEqualTo(buildingNoteSpec());
    assertThat(storedRow(ds).get(field(name("status"), String.class))).isEqualTo("active");
    assertThat(ontologyRepository.currentSchemaVersion(ontologyId)).isEqualTo(versionBefore);
  }

  @Test
  void draft_매핑도_자료형_변경을_막는다() {
    buildingNoteMapped("초안대장", "TEXT", "draft");

    assertThatThrownBy(
            () ->
                elementService.updateProperty(
                    ontologyId,
                    typeId("Building"),
                    propertyId("Building", "note"),
                    new UpdatePropertyRequest(null, null, "date", null)))
        .isInstanceOf(CodedApiException.class)
        .hasMessageContaining("텍스트 컬럼 「memo」에 연결되어 있어 날짜로 바꿀 수 없습니다");
    assertThat(dataTypeOf("Building", "note")).isEqualTo("text");
  }

  @Test
  void 매핑된_컬럼과_맞는_자료형으로는_바꿀_수_있다() {
    // 정수 컬럼에 연결된 text 속성 → number 는 맞는 쪽으로 바꾸는 것이다. 이어서 date 는 막히고, text 는 언제나 허용된다.
    long ds = buildingNoteMapped("수치대장", "INTEGER", "active");
    long noteId = propertyId("Building", "note");

    elementService.updateProperty(
        ontologyId,
        typeId("Building"),
        noteId,
        new UpdatePropertyRequest(null, null, "number", null));
    assertThat(dataTypeOf("Building", "note")).isEqualTo("number");

    assertThatThrownBy(
            () ->
                elementService.updateProperty(
                    ontologyId,
                    typeId("Building"),
                    noteId,
                    new UpdatePropertyRequest(null, null, "date", null)))
        .isInstanceOf(CodedApiException.class)
        .hasMessageContaining("숫자 컬럼 「memo」에 연결되어 있어 날짜로 바꿀 수 없습니다");

    elementService.updateProperty(
        ontologyId,
        typeId("Building"),
        noteId,
        new UpdatePropertyRequest(null, null, "text", null));
    assertThat(dataTypeOf("Building", "note")).isEqualTo("text");
    assertThat(specOf(ds)).isEqualTo(buildingNoteSpec());
  }

  @Test
  void 매핑이_연결하지_않은_속성은_자료형을_바꿀_수_있다() {
    // Building.note 만 매핑돼 있다 — Building.floors 와, 이름이 같아도 다른 타입인 Sensor.note 는 막히지 않는다.
    buildingNoteMapped("부분대장", "TEXT", "active");
    elementService.addProperty(
        ontologyId, typeId("Building"), new CreatePropertyRequest("floors", "층수", "text", null));
    elementService.addProperty(
        ontologyId, typeId("Sensor"), new CreatePropertyRequest("note", "메모", "text", null));

    elementService.updateProperty(
        ontologyId,
        typeId("Building"),
        propertyId("Building", "floors"),
        new UpdatePropertyRequest(null, null, "number", null));
    elementService.updateProperty(
        ontologyId,
        typeId("Sensor"),
        propertyId("Sensor", "note"),
        new UpdatePropertyRequest(null, null, "date", null));

    assertThat(dataTypeOf("Building", "floors")).isEqualTo("number");
    assertThat(dataTypeOf("Sensor", "note")).isEqualTo("date");
  }

  @Test
  void 데이터셋이_지워진_고아_매핑은_자료형_변경을_막지_않는다() {
    long ds = buildingNoteMapped("지워진대장", "TEXT", "active");
    inTenantFixture(
        () ->
            dsl.deleteFrom(table(name("dataset")))
                .where(field(name("id"), Long.class).eq(ds))
                .execute());

    elementService.updateProperty(
        ontologyId,
        typeId("Building"),
        propertyId("Building", "note"),
        new UpdatePropertyRequest(null, null, "number", null));

    assertThat(dataTypeOf("Building", "note")).isEqualTo("number");
  }

  @Test
  void 같은_자료형_재제출은_이미_어긋난_매핑이_있어도_막지_않는다() {
    // 이 수정 전에 만들어진 어긋난 상태(number 속성 ↔ 텍스트 컬럼)를 흉내 낸다 — 무변경 저장까지 막으면 다른 필드도 못 고친다.
    long ds = buildingNoteMapped("옛대장", "TEXT", "draft");
    long noteId = propertyId("Building", "note");
    inTenantFixture(
        () ->
            dsl.update(table(name("ontology_entity_property")))
                .set(field(name("data_type"), String.class), "number")
                .where(field(name("id"), Long.class).eq(noteId))
                .execute());

    elementService.updateProperty(
        ontologyId,
        typeId("Building"),
        noteId,
        new UpdatePropertyRequest(null, "새 설명", "number", null));

    assertThat(dataTypeOf("Building", "note")).isEqualTo("number");
    assertThat(specOf(ds)).isEqualTo(buildingNoteSpec());
  }

  @Test
  void 볼_수_없는_데이터셋은_이름과_컬럼을_숨기고_개수로_알리되_자료형_변경은_막는다() {
    long visible = buildingNoteMapped("공개대장", "TEXT", "active");
    long secret = datasetAbove("비밀대장", "기밀");
    column(secret, "secret_memo", "TEXT");
    mapping(
        secret,
        ontologyId,
        new MappingSpec(
            List.of(
                new EntityMapping(
                    "Building", "b", List.of(new PropertyMapping("secret_memo", "note")))),
            List.of()),
        "active");
    String secretName = datasetName(secret);

    assertThatThrownBy(
            () ->
                elementService.updateProperty(
                    ontologyId,
                    typeId("Building"),
                    propertyId("Building", "note"),
                    new UpdatePropertyRequest(null, null, "number", null)))
        .isInstanceOfSatisfying(
            CodedApiException.class,
            e -> {
              assertThat(e.getMessage())
                  .isEqualTo(
                      "「Building.note」 속성은 「"
                          + datasetName(visible)
                          + "」 데이터셋 매핑의 텍스트 컬럼 「memo」 및 볼 수 없는 데이터셋 1개의 매핑 컬럼에 연결되어 있어 숫자로 바꿀 수 없습니다. 매핑에서 연결을 먼저 바꾸세요.")
                  .doesNotContain(secretName)
                  .doesNotContain("secret_memo");
              assertThat(e.details()).isNull();
            });
    assertThat(dataTypeOf("Building", "note")).isEqualTo("text");
  }

  @Test
  void 볼_수_없는_데이터셋만_막으면_개수만_알린다() {
    elementService.addProperty(
        ontologyId, typeId("Building"), new CreatePropertyRequest("note", "메모", "text", null));
    long secret = datasetAbove("비밀대장", "기밀");
    column(secret, "secret_memo", "TEXT");
    mapping(
        secret,
        ontologyId,
        new MappingSpec(
            List.of(
                new EntityMapping(
                    "Building", "b", List.of(new PropertyMapping("secret_memo", "note")))),
            List.of()),
        "active");

    assertThatThrownBy(
            () ->
                elementService.updateProperty(
                    ontologyId,
                    typeId("Building"),
                    propertyId("Building", "note"),
                    new UpdatePropertyRequest(null, null, "number", null)))
        .isInstanceOf(CodedApiException.class)
        .hasMessage(
            "「Building.note」 속성은 볼 수 없는 데이터셋 1개의 매핑 컬럼에 연결되어 있어 숫자로 바꿀 수 없습니다. 매핑에서 연결을 먼저 바꾸세요.");
  }

  @Test
  void 이름과_자료형을_함께_바꿀_때_자료형이_맞지_않으면_이름도_바뀌지_않는다() {
    // 매핑은 아직 옛 이름(note)을 들고 있다 — 새 이름(memo_text)으로 찾으면 연결을 못 보고 통과해 버린다.
    long ds = buildingNoteMapped("동시변경대장", "TEXT", "active");
    long noteId = propertyId("Building", "note");

    assertThatThrownBy(
            () ->
                elementService.updateProperty(
                    ontologyId,
                    typeId("Building"),
                    noteId,
                    new UpdatePropertyRequest("memo_text", null, "number", null)))
        .isInstanceOf(CodedApiException.class)
        .hasMessageContaining("「Building.note」 속성은");

    assertThat(propertyId("Building", "note")).isEqualTo(noteId);
    assertThat(dataTypeOf("Building", "note")).isEqualTo("text");
    assertThat(specOf(ds)).isEqualTo(buildingNoteSpec());
  }

  @Test
  void 이름과_자료형을_함께_바꿀_때_자료형이_맞으면_둘_다_반영되고_매핑_이름도_바뀐다() {
    long ds = buildingNoteMapped("동시허용대장", "DECIMAL", "active");
    long noteId = propertyId("Building", "note");

    elementService.updateProperty(
        ontologyId,
        typeId("Building"),
        noteId,
        new UpdatePropertyRequest("amount", null, "number", null));

    assertThat(propertyId("Building", "amount")).isEqualTo(noteId);
    assertThat(dataTypeOf("Building", "amount")).isEqualTo("number");
    assertThat(specOf(ds).entities().get(0).properties())
        .containsExactly(new PropertyMapping("memo", "amount"));
  }

  // 매핑 저장(MappingService.validate 의 온톨로지 FOR SHARE)이 진행 중이면 자료형 변경은 그 커밋을 기다렸다가 방금 생긴 연결을 보고
  // 409 로 막혀야 한다 — 삭제 차단과 같은 잠금 순서(온톨로지 행 → 매핑)다.
  @Test
  void 진행_중인_매핑_저장이_끝날_때까지_자료형_변경이_기다렸다가_409로_막힌다() throws Exception {
    elementService.addProperty(
        ontologyId, typeId("Building"), new CreatePropertyRequest("note", "메모", "text", null));
    long ds = dataset("동시자료형표");
    column(ds, "memo", "TEXT");
    long buildingId = typeId("Building");
    long noteId = propertyId("Building", "note");

    PausedTransactionRace.Outcome<Object> out =
        PausedTransactionRace.run(
            tx,
            DEFAULT_TEST_TENANT_ID,
            () -> {
              ontologyRepository.lockForShare(ontologyId);
              dsl.insertInto(table(name("dataset_mapping")))
                  .set(field(name("dataset_id"), Long.class), ds)
                  .set(field(name("ontology_id"), Long.class), ontologyId)
                  .set(field(name("spec"), JSONB.class), JSONB.valueOf(write(buildingNoteSpec())))
                  .set(field(name("status"), String.class), "draft")
                  .execute();
            },
            () ->
                elementService.updateProperty(
                    ontologyId,
                    buildingId,
                    noteId,
                    new UpdatePropertyRequest(null, null, "number", null)));

    assertThat(out.secondBlocked()).isTrue();
    assertThat(out.secondError()).isInstanceOf(CodedApiException.class);
    assertThat(dataTypeOf("Building", "note")).isEqualTo("text");
  }

  // ── 사용처 조회(삭제 확인 창) ──────────────────────────────────────────────

  @Test
  void 사용처는_요소_id별로_데이터셋을_돌려준다() {
    addModelProperties();
    long ds = dataset("사용처표");
    mapping(
        ds,
        ontologyId,
        new MappingSpec(
            List.of(
                new EntityMapping("Sensor", "s", List.of(new PropertyMapping("m", "model"))),
                new EntityMapping("Building", "b", List.of())),
            List.of(new RelationMapping(0, "INSTALLED_IN", 1))),
        "draft");

    OntologyMappingUsage usage = mappingReferences.usage(ontologyId);

    OntologyMappingUsage.ElementUsage used =
        new OntologyMappingUsage.ElementUsage(
            List.of(new OntologyMappingUsage.UsageDataset(ds, datasetName(ds))), 0);
    assertThat(usage.entityTypes())
        .containsEntry(typeId("Sensor"), used)
        .containsEntry(typeId("Building"), used);
    // Building.model 은 매핑하지 않았다 — Sensor.model 만 사용처가 있다(사용처 없는 요소는 담지 않는다).
    assertThat(usage.properties())
        .containsOnlyKeys(propertyId("Sensor", "model"))
        .containsEntry(propertyId("Sensor", "model"), used);
    assertThat(usage.relations())
        .containsEntry(relationId("Sensor", "INSTALLED_IN", "Building"), used);
  }

  // ── 데이터셋 필드 변경(WD-186) ─────────────────────────────────────────────

  // 물리 테이블까지 갖춘 빈 데이터셋 — 필드 이름·타입 변경은 데이터가 있으면 거절되므로 행을 넣지 않는다. 필드: bname·memo(TEXT),
  // amt(INTEGER), extra(TEXT).
  private long tableDataset(String label) {
    String table = "wd186_" + System.nanoTime();
    long id =
        datasetService
            .createDataset(
                new CreateDatasetRequest(
                    label + "-" + System.nanoTime(),
                    table,
                    null,
                    null,
                    "TABLE",
                    "SOURCE",
                    List.of(
                        new DatasetColumnRequest("bname", "건물명", "TEXT", null, true, false, null),
                        new DatasetColumnRequest("memo", "메모", "TEXT", null, true, false, null),
                        new DatasetColumnRequest("amt", "금액", "INTEGER", null, true, false, null),
                        new DatasetColumnRequest("extra", "기타", "TEXT", null, true, false, null)),
                    null),
                userId)
            .id();
    datasetIds.add(id);
    tableNames.add(table);
    return id;
  }

  // Building 에 note(text)·amount(number) 속성을 둔다.
  private void addBuildingFieldProperties() {
    elementService.addProperty(
        ontologyId, typeId("Building"), new CreatePropertyRequest("note", "메모", "text", null));
    elementService.addProperty(
        ontologyId, typeId("Building"), new CreatePropertyRequest("amount", "금액", "number", null));
  }

  // 이름 필드 bname, memo→note, amt→amount 를 연결한 매핑.
  private static MappingSpec buildingFieldSpec() {
    return new MappingSpec(
        List.of(
            new EntityMapping(
                "Building",
                "bname",
                List.of(
                    new PropertyMapping("memo", "note"), new PropertyMapping("amt", "amount")))),
        List.of());
  }

  private List<DatasetColumnResponse> columnsOf(long datasetId) {
    return inTenantFixture(() -> columnRepository.findByDatasetId(datasetId));
  }

  private long columnId(long datasetId, String columnName) {
    return columnsOf(datasetId).stream()
        .filter(c -> c.columnName().equals(columnName))
        .findFirst()
        .orElseThrow()
        .id();
  }

  private String columnType(long datasetId, String columnName) {
    return columnsOf(datasetId).stream()
        .filter(c -> c.columnName().equals(columnName))
        .findFirst()
        .orElseThrow()
        .dataType();
  }

  private java.time.OffsetDateTime mappingUpdatedAt(long datasetId) {
    return inTenantFixture(
        () ->
            dsl.select(field(name("updated_at"), java.time.OffsetDateTime.class))
                .from(table(name("dataset_mapping")))
                .where(field(name("dataset_id"), Long.class).eq(datasetId))
                .fetchOne(0, java.time.OffsetDateTime.class));
  }

  private static UpdateColumnRequest rename(String newName) {
    return new UpdateColumnRequest(newName, null, null, null, null, null, null);
  }

  private static UpdateColumnRequest retype(String newName, String dataType) {
    return new UpdateColumnRequest(newName, null, dataType, null, null, null, null);
  }

  @Test
  void 필드_이름을_바꾸면_그_데이터셋_매핑의_필드_참조만_바뀌고_상태는_그대로다() {
    addBuildingFieldProperties();
    long activeDs = tableDataset("필드리네임-active");
    long draftDs = tableDataset("필드리네임-draft");
    mapping(activeDs, ontologyId, buildingFieldSpec(), "active");
    mapping(draftDs, ontologyId, buildingFieldSpec(), "draft");

    // 속성 연결 필드(memo)와 이름 필드(bname)를 각각 바꾼다.
    datasetService.updateColumn(activeDs, columnId(activeDs, "memo"), rename("remark"));
    datasetService.updateColumn(activeDs, columnId(activeDs, "bname"), rename("building_name"));

    assertThat(specOf(activeDs))
        .isEqualTo(
            new MappingSpec(
                List.of(
                    new EntityMapping(
                        "Building",
                        "building_name",
                        List.of(
                            new PropertyMapping("remark", "note"),
                            new PropertyMapping("amt", "amount")))),
                List.of()));
    // 이름만 맞추는 갱신이 active 매핑을 draft 로 떨어뜨리면 안 된다.
    assertThat(storedRow(activeDs).get(field(name("status"), String.class))).isEqualTo("active");
    // 같은 필드 이름을 쓰는 다른 데이터셋의 매핑은 건드리지 않는다.
    assertThat(specOf(draftDs)).isEqualTo(buildingFieldSpec());
    assertThat(storedRow(draftDs).get(field(name("status"), String.class))).isEqualTo("draft");

    // draft 매핑도 같은 규칙 — 상태는 draft 그대로.
    datasetService.updateColumn(draftDs, columnId(draftDs, "amt"), rename("price"));
    assertThat(specOf(draftDs).entities().get(0).properties())
        .containsExactly(
            new PropertyMapping("memo", "note"), new PropertyMapping("price", "amount"));
    assertThat(storedRow(draftDs).get(field(name("status"), String.class))).isEqualTo("draft");
  }

  @Test
  void 매핑이_쓰지_않는_필드의_이름_변경은_매핑을_다시_쓰지_않는다() {
    addBuildingFieldProperties();
    long ds = tableDataset("무관리네임");
    mapping(ds, ontologyId, buildingFieldSpec(), "active");
    var before = mappingUpdatedAt(ds);

    datasetService.updateColumn(ds, columnId(ds, "extra"), rename("extra2"));

    assertThat(columnsOf(ds)).extracting(DatasetColumnResponse::columnName).contains("extra2");
    assertThat(specOf(ds)).isEqualTo(buildingFieldSpec());
    assertThat(mappingUpdatedAt(ds)).isEqualTo(before);
  }

  @Test
  void 매핑이_쓰는_필드는_409로_삭제되지_않고_필드와_매핑이_그대로다() {
    addBuildingFieldProperties();
    long ds = tableDataset("필드삭제");
    mapping(ds, ontologyId, buildingFieldSpec(), "active");

    assertThatThrownBy(() -> datasetService.deleteColumn(ds, columnId(ds, "memo")))
        .isInstanceOfSatisfying(
            CodedApiException.class,
            e -> {
              assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
              assertThat(e.code()).isEqualTo(MappingReferenceService.COLUMN_IN_USE_CODE);
              assertThat(e.getMessage())
                  .isEqualTo(
                      "'memo' 필드는 이 데이터셋의 지식그래프 매핑(Building.note)에 연결되어 있어 삭제할 수 없습니다."
                          + " 매핑 탭에서 연결을 먼저 해제하세요.");
            });
    // 엔티티 이름 필드도 매핑이 쓰는 필드다.
    assertThatThrownBy(() -> datasetService.deleteColumn(ds, columnId(ds, "bname")))
        .isInstanceOf(CodedApiException.class)
        .hasMessageContaining("'bname' 필드는 이 데이터셋의 지식그래프 매핑(Building 이름)에 연결되어 있어 삭제할 수 없습니다");

    assertThat(columnsOf(ds))
        .extracting(DatasetColumnResponse::columnName)
        .containsExactlyInAnyOrder("bname", "memo", "amt", "extra");
    assertThat(specOf(ds)).isEqualTo(buildingFieldSpec());
  }

  @Test
  void 매핑이_쓰지_않는_필드는_삭제된다() {
    addBuildingFieldProperties();
    long ds = tableDataset("무관삭제");
    mapping(ds, ontologyId, buildingFieldSpec(), "draft");

    datasetService.deleteColumn(ds, columnId(ds, "extra"));

    assertThat(columnsOf(ds))
        .extracting(DatasetColumnResponse::columnName)
        .containsExactlyInAnyOrder("bname", "memo", "amt");
    assertThat(specOf(ds)).isEqualTo(buildingFieldSpec());
  }

  @Test
  void 매핑_속성_자료형과_맞지_않는_타입으로_바꾸면_409로_막히고_타입은_그대로다() {
    addBuildingFieldProperties();
    long ds = tableDataset("타입충돌");
    mapping(ds, ontologyId, buildingFieldSpec(), "draft");

    assertThatThrownBy(
            () -> datasetService.updateColumn(ds, columnId(ds, "amt"), retype(null, "TEXT")))
        .isInstanceOfSatisfying(
            CodedApiException.class,
            e -> {
              assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
              assertThat(e.code()).isEqualTo(MappingReferenceService.COLUMN_TYPE_CONFLICT_CODE);
              assertThat(e.getMessage())
                  .isEqualTo(
                      "'amt' 필드는 이 데이터셋의 지식그래프 매핑에서 숫자 속성 Building.amount에 연결되어 있어 텍스트 타입(TEXT)으로"
                          + " 바꿀 수 없습니다. 매핑 탭에서 연결을 먼저 해제하세요.");
            });
    assertThat(columnType(ds, "amt")).isEqualTo("INTEGER");
  }

  @Test
  void 매핑_속성_자료형과_맞는_타입으로는_바꿀_수_있다() {
    addBuildingFieldProperties();
    long ds = tableDataset("타입호환");
    mapping(ds, ontologyId, buildingFieldSpec(), "active");

    // number 속성에 연결된 필드를 다른 숫자 타입으로, text 속성에 연결된 필드는 어떤 타입으로든 바꿀 수 있다.
    datasetService.updateColumn(ds, columnId(ds, "amt"), retype(null, "DECIMAL"));
    datasetService.updateColumn(ds, columnId(ds, "memo"), retype(null, "INTEGER"));

    assertThat(columnType(ds, "amt")).isEqualTo("DECIMAL");
    assertThat(columnType(ds, "memo")).isEqualTo("INTEGER");
    assertThat(specOf(ds)).isEqualTo(buildingFieldSpec());
  }

  @Test
  void 이름과_타입을_함께_바꿀_때_타입이_맞지_않으면_이름도_매핑도_바뀌지_않는다() {
    addBuildingFieldProperties();
    long ds = tableDataset("동시변경거절");
    mapping(ds, ontologyId, buildingFieldSpec(), "active");

    assertThatThrownBy(
            () -> datasetService.updateColumn(ds, columnId(ds, "amt"), retype("price", "DATE")))
        .isInstanceOf(CodedApiException.class)
        .hasMessageContaining("'amt' 필드는");

    assertThat(columnsOf(ds)).extracting(DatasetColumnResponse::columnName).contains("amt");
    assertThat(specOf(ds)).isEqualTo(buildingFieldSpec());
  }

  @Test
  void 이름과_타입을_함께_바꿀_때_타입이_맞으면_둘_다_반영되고_매핑_이름도_바뀐다() {
    addBuildingFieldProperties();
    long ds = tableDataset("동시변경허용");
    mapping(ds, ontologyId, buildingFieldSpec(), "active");

    datasetService.updateColumn(ds, columnId(ds, "amt"), retype("price", "DECIMAL"));

    assertThat(columnType(ds, "price")).isEqualTo("DECIMAL");
    assertThat(specOf(ds).entities().get(0).properties())
        .containsExactly(
            new PropertyMapping("memo", "note"), new PropertyMapping("price", "amount"));
    assertThat(storedRow(ds).get(field(name("status"), String.class))).isEqualTo("active");
  }

  // 매핑 저장(온톨로지·데이터셋 FOR SHARE 뒤 삽입)이 커밋 전일 때 필드 이름 변경은 그 커밋을 기다렸다가 방금 생긴 매핑까지 새 이름으로
  // 맞춰야 한다. 데이터셋 행 잠금이 없으면 이름 변경이 커밋 전 매핑을 못 본 채 끝나고, 매핑은 사라진 옛 이름으로 남는다.
  @Test
  void 진행_중인_매핑_저장이_끝난_뒤_필드_이름_변경이_그_매핑까지_반영한다() throws Exception {
    addBuildingFieldProperties();
    long ds = tableDataset("동시필드리네임");
    long memoId = columnId(ds, "memo");

    PausedTransactionRace.Outcome<Object> out =
        PausedTransactionRace.run(
            tx,
            DEFAULT_TEST_TENANT_ID,
            () -> {
              ontologyRepository.lockForShare(ontologyId);
              datasetRepository.lockForShare(ds);
              dsl.insertInto(table(name("dataset_mapping")))
                  .set(field(name("dataset_id"), Long.class), ds)
                  .set(field(name("ontology_id"), Long.class), ontologyId)
                  .set(field(name("spec"), JSONB.class), JSONB.valueOf(write(buildingFieldSpec())))
                  .set(field(name("status"), String.class), "active")
                  .execute();
            },
            () -> {
              datasetService.updateColumn(ds, memoId, rename("remark"));
              return null;
            });

    assertThat(out.secondBlocked()).isTrue();
    assertThat(out.secondError()).isNull();
    assertThat(specOf(ds).entities().get(0).properties())
        .containsExactly(
            new PropertyMapping("remark", "note"), new PropertyMapping("amt", "amount"));
  }

  // 같은 구도에서 필드 삭제는 매핑 저장 커밋을 기다렸다가 방금 생긴 연결을 보고 409 로 막혀야 한다.
  @Test
  void 진행_중인_매핑_저장이_끝날_때까지_필드_삭제가_기다렸다가_409로_막힌다() throws Exception {
    addBuildingFieldProperties();
    long ds = tableDataset("동시필드삭제");
    long memoId = columnId(ds, "memo");

    PausedTransactionRace.Outcome<Object> out =
        PausedTransactionRace.run(
            tx,
            DEFAULT_TEST_TENANT_ID,
            () -> {
              ontologyRepository.lockForShare(ontologyId);
              datasetRepository.lockForShare(ds);
              dsl.insertInto(table(name("dataset_mapping")))
                  .set(field(name("dataset_id"), Long.class), ds)
                  .set(field(name("ontology_id"), Long.class), ontologyId)
                  .set(field(name("spec"), JSONB.class), JSONB.valueOf(write(buildingFieldSpec())))
                  .set(field(name("status"), String.class), "draft")
                  .execute();
            },
            () -> {
              datasetService.deleteColumn(ds, memoId);
              return null;
            });

    assertThat(out.secondBlocked()).isTrue();
    assertThat(out.secondError()).isInstanceOf(CodedApiException.class);
    assertThat(columnsOf(ds)).extracting(DatasetColumnResponse::columnName).contains("memo");
  }

  // 지식 모델의 속성 자료형 변경(WD-185, 온톨로지 행 NO KEY UPDATE)이 커밋 전일 때 필드 타입 변경은 그 커밋을 기다렸다가 바뀐 자료형으로
  // 판정해야 한다. 필드 변경 경로의 온톨로지 공유 잠금이 없으면 둘 다 옛 상태를 보고 통과해 숫자 속성이 텍스트 필드에 연결된 채 남는다.
  @Test
  void 진행_중인_속성_자료형_변경이_끝날_때까지_필드_타입_변경이_기다렸다가_409로_막힌다() throws Exception {
    addBuildingFieldProperties();
    long ds = tableDataset("동시자료형교차");
    // text 속성 note 에 INTEGER 필드 amt 를 연결 — 지금은 맞는 쌍이다.
    mapping(
        ds,
        ontologyId,
        new MappingSpec(
            List.of(
                new EntityMapping(
                    "Building", "bname", List.of(new PropertyMapping("amt", "note")))),
            List.of()),
        "active");
    long buildingId = typeId("Building");
    long noteId = propertyId("Building", "note");
    long amtId = columnId(ds, "amt");

    PausedTransactionRace.Outcome<Object> out =
        PausedTransactionRace.run(
            tx,
            DEFAULT_TEST_TENANT_ID,
            // note 를 number 로 — amt(INTEGER) 와 맞으므로 WD-185 검사를 통과한다.
            () ->
                elementService.updateProperty(
                    ontologyId,
                    buildingId,
                    noteId,
                    new UpdatePropertyRequest(null, null, "number", null)),
            () -> {
              datasetService.updateColumn(ds, amtId, retype(null, "TEXT"));
              return null;
            });

    assertThat(out.secondBlocked()).isTrue();
    assertThat(out.secondError())
        .isInstanceOfSatisfying(
            CodedApiException.class,
            e -> assertThat(e.code()).isEqualTo(MappingReferenceService.COLUMN_TYPE_CONFLICT_CODE));
    assertThat(dataTypeOf("Building", "note")).isEqualTo("number");
    assertThat(columnType(ds, "amt")).isEqualTo("INTEGER");
  }

  // 반대 방향: 필드 이름 변경이 커밋 전일 때 매핑 저장은 그 커밋을 기다렸다가 바뀐 필드 목록으로 검증해, 사라진 옛 이름을 400 으로 거절해야
  // 한다. 매핑 저장 쪽 데이터셋 잠금(MappingService.validate)이 없으면 커밋 전 필드 목록(옛 이름)으로 검증을 통과해 옛 이름을 저장한다.
  @Test
  void 진행_중인_필드_이름_변경이_끝날_때까지_매핑_저장이_기다렸다가_옛_이름을_400으로_거절한다() throws Exception {
    addBuildingFieldProperties();
    long ds = tableDataset("동시매핑저장");
    long memoId = columnId(ds, "memo");
    inTenantFixture(() -> bindingRepository.bind(ds, ontologyId, userId));

    PausedTransactionRace.Outcome<Object> out =
        PausedTransactionRace.run(
            tx,
            DEFAULT_TEST_TENANT_ID,
            () -> datasetService.updateColumn(ds, memoId, rename("remark")),
            () -> mappingService.save(ds, buildingFieldSpec(), userId));

    assertThat(out.secondBlocked()).isTrue();
    assertThat(out.secondError())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("데이터셋에 없는 필드입니다: memo (Building.note)");
    assertThat(storedRow(ds)).isNull();
  }
}
