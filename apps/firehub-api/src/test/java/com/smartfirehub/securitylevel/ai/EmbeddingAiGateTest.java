package com.smartfirehub.securitylevel.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.search.DatasetEmbeddingRepository;
import com.smartfirehub.document.dto.Chunk;
import com.smartfirehub.document.repository.DocumentChunkRepository;
import com.smartfirehub.embedding.EmbeddingDimension;
import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.embedding.config.EmbeddingConfig;
import com.smartfirehub.embedding.config.EmbeddingConfigService;
import com.smartfirehub.embedding.config.EmbeddingProviderType;
import com.smartfirehub.embedding.reembed.EmbeddingBacklogService;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import com.smartfirehub.securitylevel.access.DatasetAccessPolicy;
import com.smartfirehub.securitylevel.access.LevelPolicy;
import com.smartfirehub.securitylevel.access.ProviderHosting;
import com.smartfirehub.settings.repository.TenantSettingsRepository;
import com.smartfirehub.support.EmbeddingTestFixtures;
import com.smartfirehub.support.EmbeddingTestFixtures.DocFixture;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 스펙 §4.3 행 검색·메타 임베딩·문서 청크 행: 등급 ai_policy vs 임베딩 공급자 호스팅. 불허 데이터셋은 재임베딩 판정식(카탈로그·청크)에서 빠지고, 호스팅을
 * 자체 호스팅으로 선언하거나 등급을 내리면 다시 들어온다(Review Focus 1·2). 테넌트 문맥 없이는 판정하지 않는다(배경 스레드 fail-open/오판 방지).
 */
class EmbeddingAiGateTest extends IntegrationTestBase {

  private static final EmbeddingSpace SPACE =
      new EmbeddingSpace(EmbeddingDimension.values()[0], "bge-m3");

  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;
  @Autowired private EmbeddingAiGate gate;
  @Autowired private EmbeddingConfigService configService;
  @Autowired private TenantSettingsRepository tenantSettings;
  @Autowired private DatasetEmbeddingRepository embeddingRepository;
  @Autowired private DocumentChunkRepository chunkRepository;
  @Autowired private EmbeddingBacklogService backlogService;

  private SecurityFixture fx;
  private long creator;
  private long pubId;
  private long sensId;
  private final List<Long> users = new ArrayList<>();

  /** 테스트 전 tenant 1 의 임베딩 설정 원문 — 끝나고 되돌린다(다른 테스트에 OLLAMA 설정이 새지 않게). */
  private Optional<String> savedConfig;

  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    creator = fx.createUser("eag");
    users.add(creator);
    String m = "eag" + System.nanoTime();
    pubId = fx.createDatasetRow(m + "_pub", fx.levelId("공개"), creator);
    sensId = fx.createDatasetRow(m + "_sens", fx.levelId("민감"), creator);
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.execute(
                "insert into dataset_embedding (dataset_id, source_text) values (?, 'a'), (?, 'b')",
                pubId,
                sensId));
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    savedConfig = tenantSettings.findValue(EmbeddingConfigService.KEY);
    store(ProviderHosting.EXTERNAL);
  }

  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    if (savedConfig.isPresent()) {
      tenantSettings.upsert(EmbeddingConfigService.KEY, savedConfig.get(), null);
    } else {
      tenantSettings.delete(EmbeddingConfigService.KEY);
    }
    fx.deleteDatasetRow(pubId);
    fx.deleteDatasetRow(sensId);
    users.forEach(fx::deleteUser);
  }

  private void store(ProviderHosting h) {
    configService.store(
        new EmbeddingConfig(EmbeddingProviderType.OLLAMA, "bge-m3", "http://ollama:11434", "", 0),
        SPACE.dimension(),
        h,
        null);
  }

  private void setLevel(long datasetId, String levelName) {
    long levelId = fx.levelId(levelName);
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.execute(
                "update dataset set security_level_id = ? where id = ?", levelId, datasetId));
  }

  /** 키셋 시작점을 이 테스트 데이터셋 바로 앞에 둔다 — 공유 DB 의 다른 행 수가 증거를 가리지 않게. */
  private List<Long> missingDatasetIds() {
    return embeddingRepository
        .findMissing(
            SPACE, Math.min(pubId, sensId) - 1, 100, gate.allowedDatasetSql("de.dataset_id"))
        .stream()
        .map(DatasetEmbeddingRepository.SourceTextRow::datasetId)
        .toList();
  }

  @Test
  void datasetAllowed_followsLevelAndHosting() {
    assertThat(gate.datasetAllowed(pubId)).isTrue();
    assertThat(gate.datasetAllowed(sensId)).isFalse();
    // 등급 하향(민감→내부): 외부 호스팅이라도 다시 허용된다(Review Focus 2).
    setLevel(sensId, "내부");
    assertThat(gate.datasetAllowed(sensId)).isTrue();
    setLevel(sensId, "민감");
    assertThat(gate.datasetAllowed(sensId)).isFalse();
    // 자체 호스팅 선언: 민감도 허용(Review Focus 1).
    store(ProviderHosting.SELF_HOSTED);
    assertThat(gate.datasetAllowed(sensId)).isTrue();
    // 없는 데이터셋은 fail-closed.
    assertThat(gate.datasetAllowed(Long.MAX_VALUE)).isFalse();
  }

  @Test
  void withoutTenantContext_throwsInsteadOfGuessing() {
    // 배경 스레드에 문맥이 전파되지 않은 경우 — 조용히 false(벡터 삭제·키워드 전용 재색인)나 true(fail-open)로 답하지 않는다.
    TenantContext.clear();
    assertThatThrownBy(() -> gate.datasetAllowed(pubId)).isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> gate.allowedDatasetSql("de.dataset_id"))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> gate.disallowedDatasetIds()).isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> gate.datasetDisallowed(sensId)).isInstanceOf(RuntimeException.class);
  }

  @Test
  void missingQuery_excludesDisallowedDatasets() {
    assertThat(missingDatasetIds()).contains(pubId).doesNotContain(sensId);
    long countExternal =
        embeddingRepository.countMissing(SPACE, gate.allowedDatasetSql("de.dataset_id"));
    long totalExternal = embeddingRepository.countAll(gate.allowedDatasetSql("de.dataset_id"));
    long impactExternal = backlogService.impact(SPACE).datasets();
    store(ProviderHosting.SELF_HOSTED);
    assertThat(missingDatasetIds()).contains(pubId, sensId);
    // 영향도·스윕 투입 판정(EmbeddingBacklogService)도 같은 술어를 쓴다 — 자체 호스팅 선언으로 민감 1건이 "할 일"로 들어온다.
    assertThat(backlogService.impact(SPACE).datasets()).isEqualTo(impactExternal + 1);
    // 판정식·분모 모두 민감 데이터셋 1건만큼 늘어난다(같은 모집단).
    assertThat(embeddingRepository.countMissing(SPACE, gate.allowedDatasetSql("de.dataset_id")))
        .isEqualTo(countExternal + 1);
    assertThat(embeddingRepository.countAll(gate.allowedDatasetSql("de.dataset_id")))
        .isEqualTo(totalExternal + 1);
  }

  @Test
  void chunkMissingQuery_excludesDisallowedDocumentChunks() {
    DocFixture doc =
        inTenantFixture(() -> EmbeddingTestFixtures.createDocumentDataset(dsl, "eagdoc"));
    try {
      setLevel(doc.datasetId(), "민감");
      // 불허 문서는 본문만 저장된다(벡터 없음).
      chunkRepository.insertChunksOnly(
          doc.fileId(), doc.datasetId(), List.of(new Chunk(0, "민감 본문", 3)));
      assertThat(chunkIds(doc.fileId())).isEmpty();
      store(ProviderHosting.SELF_HOSTED);
      // 허용되면 재임베딩 판정식이 그 청크를 집는다.
      assertThat(chunkIds(doc.fileId())).hasSize(1);
    } finally {
      TenantRlsTestSupport.runInTenantTransaction(
          fixtureTransactionTemplate,
          DEFAULT_TEST_TENANT_ID,
          () -> dsl.execute("delete from dataset where id = ?", doc.datasetId()));
      TenantRlsTestSupport.deleteUser(dsl, doc.userId());
    }
  }

  /** 이 테스트 문서의 청크 중 재임베딩 판정식을 만족하는 것(키셋을 그 청크 바로 앞에서 시작 — 공유 DB 규모가 증거를 가리지 않게). */
  private List<Long> chunkIds(long fileId) {
    long chunkId =
        TenantRlsTestSupport.runInTenantTransaction(
            fixtureTransactionTemplate,
            DEFAULT_TEST_TENANT_ID,
            () ->
                dsl.fetchOne("select id from document_chunk where document_file_id = ?", fileId)
                    .get(0, Long.class));
    return chunkRepository
        .findMissing(SPACE, chunkId - 1, 1, gate.allowedDatasetSql("c.dataset_id"))
        .stream()
        .map(DocumentChunkRepository.ChunkContent::chunkId)
        .filter(id -> id == chunkId)
        .toList();
  }

  @Test
  void disallowedDatasetIds_listsOnlyPolicyViolations() {
    assertThat(gate.disallowedDatasetIds()).contains(sensId).doesNotContain(pubId);
    store(ProviderHosting.SELF_HOSTED);
    assertThat(gate.disallowedDatasetIds()).doesNotContain(sensId, pubId);
  }

  /** 단건 불허 판정: 불허 등급만 참, 허용·없는 데이터셋은 거짓(없는 id 를 삭제 대상으로 보지 않는다), 호스팅 변경을 따른다. */
  @Test
  void datasetDisallowed_trueOnlyForExistingPolicyViolation() {
    assertThat(gate.datasetDisallowed(sensId)).isTrue();
    assertThat(gate.datasetDisallowed(pubId)).isFalse();
    assertThat(gate.datasetDisallowed(Long.MAX_VALUE)).isFalse();
    store(ProviderHosting.SELF_HOSTED);
    assertThat(gate.datasetDisallowed(sensId)).isFalse();
  }

  // ---- ai_policy × 호스팅 규칙: 남은 두 구현(jOOQ 술어 · 순수 함수)의 일치 ----

  /** 호스팅 축 — enum 값 + null(호출자가 위치를 모름 = 외부로 본다). */
  private static final List<ProviderHosting> HOSTINGS =
      Arrays.asList(ProviderHosting.EXTERNAL, ProviderHosting.SELF_HOSTED, null);

  /**
   * jOOQ 술어(DatasetAccessGuard#aiPolicyAllows)를 DB 가 실제로 평가한 값이 순수 함수(aiAllowedForLevel)와 모든 정책 ×
   * 호스팅(null 포함)에서 같다. 문자열 SQL 판(EmbeddingAiGate)은 이 술어를 렌더해 쓰므로 규칙 구현은 이 둘뿐이다.
   */
  @Test
  void aiPolicyAllows_agreesWithAiAllowedForLevel_forEveryPolicyAndHosting() {
    for (LevelPolicy.AiPolicy p : LevelPolicy.AiPolicy.values()) {
      for (ProviderHosting h : HOSTINGS) {
        Boolean sql =
            dsl.fetchValue(DSL.field(DatasetAccessGuard.aiPolicyAllows(DSL.inline(p.name()), h)));
        assertThat(sql)
            .as("%s/%s", p, h)
            .isEqualTo(DatasetAccessPolicy.aiAllowedForLevel(level(p), h));
      }
    }
  }

  /**
   * 렌더된 문자열 술어(allowedDatasetSql)가 시드 4등급 × 호스팅(null 포함)에서 순수 함수와 같은 답을 낸다 — 렌더(별칭·조인·인라인)가 술어 의미를
   * 바꾸지 않는다는 증거. 시드가 ALL·SELF_HOSTED_ONLY 를 모두 갖는지도 고정한다(공허한 일치 방지).
   */
  @Test
  void allowedDatasetSql_agreesWithAiAllowedForLevel_forSeedLevelsAndHostings() {
    List<Long> created = new ArrayList<>();
    java.util.Set<LevelPolicy.AiPolicy> seen = new java.util.HashSet<>();
    try {
      for (String name : List.of("공개", "내부", "민감", "기밀")) {
        long levelId = fx.levelId(name);
        long ds = fx.createDatasetRow("eagm" + System.nanoTime(), levelId, creator);
        created.add(ds);
        LevelPolicy.AiPolicy p =
            LevelPolicy.AiPolicy.valueOf(
                inTenantFixture(
                    () ->
                        dsl.fetchValue("select ai_policy from security_level where id = ?", levelId)
                            .toString()));
        seen.add(p);
        for (ProviderHosting h : HOSTINGS) {
          String frag = gate.allowedDatasetSql("x.id", h);
          Boolean sql =
              inTenantFixture(
                  () ->
                      (Boolean)
                          dsl.fetchValue(
                              "select " + frag + " from (select ?::bigint as id) x", ds));
          assertThat(sql)
              .as("%s(%s)/%s", name, p, h)
              .isEqualTo(DatasetAccessPolicy.aiAllowedForLevel(level(p), h));
        }
      }
      assertThat(seen).contains(LevelPolicy.AiPolicy.ALL, LevelPolicy.AiPolicy.SELF_HOSTED_ONLY);
    } finally {
      created.forEach(fx::deleteDatasetRow);
    }
  }

  /** ai_policy 만 의미 있는 등급 값(나머지 축은 판정과 무관). */
  private static LevelPolicy level(LevelPolicy.AiPolicy p) {
    return new LevelPolicy(
        1L,
        "L",
        1,
        false,
        false,
        false,
        LevelPolicy.ExportPolicy.ALLOW,
        p,
        LevelPolicy.SharePolicy.ALLOW,
        false);
  }
}
