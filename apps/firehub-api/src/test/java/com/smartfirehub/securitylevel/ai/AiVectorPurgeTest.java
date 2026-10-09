package com.smartfirehub.securitylevel.ai;

import static com.smartfirehub.support.EmbeddingTestFixtures.axis;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.smartfirehub.dataset.rowsearch.RowSearchSyncService;
import com.smartfirehub.dataset.rowsearch.SearchIndexStateRepository;
import com.smartfirehub.dataset.search.DatasetEmbeddingRepository;
import com.smartfirehub.document.dto.Chunk;
import com.smartfirehub.document.repository.DocumentChunkRepository;
import com.smartfirehub.embedding.EmbeddingDimension;
import com.smartfirehub.embedding.EmbeddingProviderFactory;
import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.embedding.config.EmbeddingConfig;
import com.smartfirehub.embedding.config.EmbeddingConfigService;
import com.smartfirehub.embedding.config.EmbeddingProviderType;
import com.smartfirehub.embedding.config.EmbeddingSettingsService;
import com.smartfirehub.embedding.config.dto.EmbeddingConfigRequest;
import com.smartfirehub.embedding.reembed.TenantReembedJob;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.securitylevel.access.ProviderHosting;
import com.smartfirehub.securitylevel.event.DatasetSecurityLevelChangedEvent;
import com.smartfirehub.securitylevel.event.EmbeddingHostingChangedEvent;
import com.smartfirehub.securitylevel.event.SecurityLevelsChangedEvent;
import com.smartfirehub.settings.repository.TenantSettingsRepository;
import com.smartfirehub.settings.service.SettingsOverridePolicy;
import com.smartfirehub.support.EmbeddingTestFixtures;
import com.smartfirehub.support.EmbeddingTestFixtures.DocFixture;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * 스펙 §4.3 외부 벡터 정리 트리거 + 보충 §2.1 배포 시점 1회 정리. 정책 위반 벡터(메타·청크·행 검색)만 지우고 텍스트는 남기며, 멱등이고, 이벤트 리스너는 발행
 * 스레드가 아니라 이벤트의 테넌트로 돈다. 임베딩을 자체 호스팅으로 선언하면 재임베딩이 다시 투입된다(Review Focus 1).
 *
 * <p>목·동적 속성 구성은 AiHostingDeclarationTest 와 같게 둔다 — 같은 스프링 컨텍스트를 재사용하기 위해서다.
 */
@RecordApplicationEvents
class AiVectorPurgeTest extends IntegrationTestBase {

  private static final String OLLAMA = "http://ollama-hd.internal:11434";
  private static final EmbeddingSpace SPACE =
      new EmbeddingSpace(EmbeddingDimension.values()[0], "bge-m3");

  @DynamicPropertySource
  static void allowOllama(DynamicPropertyRegistry r) {
    r.add("app.embedding.ollama-allowed-base-urls", () -> OLLAMA);
  }

  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;
  @Autowired private AiVectorPurgeService purgeService;
  @Autowired private AiVectorPurgeStartupRunner startupRunner;
  @Autowired private EmbeddingConfigService configService;
  @Autowired private EmbeddingSettingsService settingsService;
  @Autowired private DatasetEmbeddingRepository embeddingRepository;
  @Autowired private DocumentChunkRepository chunkRepository;
  @Autowired private SearchIndexStateRepository searchStates;
  @Autowired private TenantSettingsRepository tenantSettings;
  @Autowired private EmbeddingAiGate gate;
  @Autowired private ApplicationEventPublisher publisher;
  @Autowired private ApplicationEvents events;

  /** 임베딩 저장의 probe(외부 호출)를 차원만 돌려주게 바꾼다. */
  @MockitoSpyBean private EmbeddingProviderFactory providerFactory;

  /** 재임베딩 투입 여부만 본다(실제 배경 작업은 띄우지 않는다). */
  @MockitoBean private TenantReembedJob reembedJob;

  private SecurityFixture fx;
  private final List<Long> users = new ArrayList<>();
  private final List<Long> roles = new ArrayList<>();
  private long pubId;
  private long sensId;
  private DocFixture doc;

  /** 테스트 전 tenant 1 의 임베딩 설정 원문 — 끝나고 되돌린다. */
  private Optional<String> savedConfig;

  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    long creator = fx.createUser("avp");
    users.add(creator);
    String m = "avp" + System.nanoTime();
    pubId = fx.createDatasetRow(m + "_pub", fx.levelId("공개"), creator);
    // 처음엔 내부(허용)로 만들어 벡터를 갖게 한 뒤, 테스트가 민감으로 올린다.
    sensId = fx.createDatasetRow(m + "_sens", fx.levelId("내부"), creator);
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.execute(
                "insert into dataset_embedding (dataset_id, source_text) values (?, 'a'), (?, 'b')",
                pubId,
                sensId));
    doc = inTenantFixture(() -> EmbeddingTestFixtures.createDocumentDataset(dsl, "avpdoc"));
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    savedConfig = tenantSettings.findValue(EmbeddingConfigService.KEY);
    store(ProviderHosting.EXTERNAL);
    int dim = SPACE.dimension().size();
    embeddingRepository.upsertEmbeddings(
        SPACE, List.of(pubId, sensId), List.of(axis(dim, 0), axis(dim, 1)));
    chunkRepository.insertBatch(
        doc.fileId(),
        doc.datasetId(),
        List.of(new Chunk(0, "문서 본문", 3)),
        List.of(axis(dim, 2)),
        SPACE);
    tenantSettings.delete(AiVectorPurgeStartupRunner.FLAG_KEY);
    doReturn(dim).when(providerFactory).probeDimension(any());
  }

  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    tenantSettings.delete(AiVectorPurgeStartupRunner.FLAG_KEY);
    if (savedConfig.isPresent()) {
      tenantSettings.upsert(EmbeddingConfigService.KEY, savedConfig.get(), null);
    } else {
      tenantSettings.delete(EmbeddingConfigService.KEY);
    }
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () -> dsl.execute("delete from dataset where id = ?", doc.datasetId()));
    TenantRlsTestSupport.deleteUser(dsl, doc.userId());
    fx.deleteDatasetRow(pubId);
    fx.deleteDatasetRow(sensId);
    users.forEach(fx::deleteUser);
    roles.forEach(fx::deleteRole);
  }

  private void store(ProviderHosting h) {
    configService.store(
        new EmbeddingConfig(EmbeddingProviderType.OLLAMA, "bge-m3", OLLAMA, "", 0),
        SPACE.dimension(),
        h,
        null);
  }

  /** 서비스 경로 저장(권한 판정·감사·이벤트·재임베딩 투입 포함). */
  private void save(String hosting, long userId) {
    settingsService.save(
        new EmbeddingConfigRequest("OLLAMA", "bge-m3", OLLAMA, null, hosting), userId);
  }

  private long securityAdmin() {
    long uid = fx.createUser("avp_u");
    users.add(uid);
    fx.removeUserRole(uid);
    long rid =
        fx.createRole(
            "avp_r_" + System.nanoTime(), fx.levelId("내부"), "ai:settings", "security:settings");
    roles.add(rid);
    fx.assignRole(uid, rid);
    return uid;
  }

  private boolean hasVector(long datasetId) {
    return TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.fetchExists(
                dsl.selectOne()
                    .from(org.jooq.impl.DSL.table(SPACE.dimension().datasetTable()))
                    .where(org.jooq.impl.DSL.field("dataset_id").eq(datasetId))));
  }

  private boolean hasChunkVector() {
    return TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.fetchExists(
                dsl.selectOne()
                    .from(org.jooq.impl.DSL.table(SPACE.dimension().chunkTable()))
                    .where(org.jooq.impl.DSL.field("dataset_id").eq(doc.datasetId()))));
  }

  private int chunkTextCount() {
    return TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.fetchCount(
                org.jooq.impl.DSL.table("document_chunk"),
                org.jooq.impl.DSL.field("document_file_id").eq(doc.fileId())));
  }

  private void setLevel(long datasetId, String level) {
    long levelId = fx.levelId(level);
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.execute(
                "update dataset set security_level_id = ? where id = ?", levelId, datasetId));
  }

  /** 비동기 리스너 대기(awaitility 없음) — 최대 10초 폴링. */
  private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 10_000;
    while (!condition.getAsBoolean()) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("10초 안에 조건이 만족되지 않았다");
      }
      Thread.sleep(100);
    }
  }

  @Test
  void purge_deletesOnlyPolicyViolatingVectors_keepsText_andIsIdempotent() {
    setLevel(sensId, "민감");
    setLevel(doc.datasetId(), "민감");
    var first = purgeService.purgeDisallowed();
    assertThat(hasVector(sensId)).isFalse();
    assertThat(hasChunkVector()).isFalse();
    assertThat(hasVector(pubId)).isTrue();
    // 키워드 검색용 텍스트는 남는다.
    assertThat(chunkTextCount()).isEqualTo(1);
    assertThat(first.datasetVectors()).isGreaterThanOrEqualTo(1);
    assertThat(first.chunkVectors()).isGreaterThanOrEqualTo(1);
    var second = purgeService.purgeDisallowed();
    assertThat(second.datasetVectors()).isZero();
    assertThat(second.chunkVectors()).isZero();
  }

  @Test
  void purge_marksRowSearchStateKeywordOnly_immediately_andOnlyOnce() {
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.execute(
                "insert into dataset_search_index (dataset_id, embedding_model, embedding_dim, config_hash)"
                    + " values (?, 'bge-m3', 1024, 'h')",
                sensId));
    setLevel(sensId, "민감");
    var first = purgeService.purgeDisallowed();
    var state = searchStates.find(sensId).orElseThrow();
    assertThat(state.embeddingModel()).isEqualTo(RowSearchSyncService.KEYWORD_ONLY_MODEL);
    // 해시를 비워 다음 스윕이 키워드 전용으로 전체 재색인한다.
    assertThat(state.configHash()).isEmpty();
    assertThat(first.rowIndexes()).isGreaterThanOrEqualTo(1);
    assertThat(first.failures()).isZero();
    // 이미 키워드 전용이면 다시 건드리지 않는다(멱등).
    assertThat(purgeService.purgeDisallowed().rowIndexes()).isZero();
  }

  /** 적재 이력(dataset_graph_ingest) 1행을 남긴다 — 문서 GraphRAG 적재가 있었던 것처럼. */
  private void recordGraphIngest(long datasetId) {
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.execute(
                "insert into dataset_graph_ingest (dataset_id, schema_version_at_ingest) values (?, 1)",
                datasetId));
  }

  /** 출처 기록(graph_ontology_source)을 남긴다 — 표 투영·매핑처럼 적재 이력 없이 그래프에 쓰인 경우. 온톨로지 id 를 돌려준다. */
  private long recordGraphSource(long datasetId) {
    return TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () -> {
          long ontologyId =
              dsl.fetchOne(
                      "insert into ontology (domain, status) values (?, 'active') returning id",
                      "avp_onto_" + System.nanoTime())
                  .get(0, Long.class);
          dsl.execute(
              "insert into graph_ontology_source (ontology_id, dataset_id) values (?, ?)",
              ontologyId,
              datasetId);
          return ontologyId;
        });
  }

  private void deleteGraphHistory(List<Long> datasetIds, List<Long> ontologyIds) {
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () -> {
          datasetIds.forEach(
              id -> dsl.execute("delete from dataset_graph_ingest where dataset_id = ?", id));
          // 출처 행은 온톨로지 삭제 CASCADE 로 지워진다.
          ontologyIds.forEach(id -> dsl.execute("delete from ontology where id = ?", id));
        });
  }

  @Test
  void purge_flagsDisallowedDatasetsWithGraphHistory_asManualCleanupTargets() {
    // 불허(민감) + 적재 이력 → 표시, 불허(민감) + 출처 기록 → 표시, 허용(공개) + 이력 → 미표시, 불허 + 이력 없음 → 미표시.
    long creator = users.get(0);
    long srcOnlyId =
        fx.createDatasetRow("avp" + System.nanoTime() + "_src", fx.levelId("민감"), creator);
    List<Long> ontologies = new ArrayList<>();
    try {
      setLevel(sensId, "민감");
      setLevel(doc.datasetId(), "민감");
      recordGraphIngest(sensId);
      recordGraphIngest(pubId);
      ontologies.add(recordGraphSource(srcOnlyId));
      ontologies.add(recordGraphSource(pubId));

      var result = purgeService.purgeDisallowed();

      assertThat(result.graphResidueDatasetIds())
          .contains(sensId, srcOnlyId)
          .doesNotContain(pubId, doc.datasetId());
      // 회수하지 않으므로 다시 정리해도 계속 표시된다(멱등 — 완료 판정에는 영향 없음).
      var second = purgeService.purgeDisallowed();
      assertThat(second.graphResidueDatasetIds()).contains(sensId, srcOnlyId);
      assertThat(second.failures()).isZero();
    } finally {
      deleteGraphHistory(List.of(sensId, pubId), ontologies);
      fx.deleteDatasetRow(srcOnlyId);
    }
  }

  @Test
  void levelChangedEvent_runsInEventTenant_notPublisherThread() throws Exception {
    setLevel(sensId, "민감");
    // 발행 스레드에는 테넌트 문맥이 없다 — 리스너가 이벤트의 tenantId 로 문맥을 세워야만 정리된다.
    TenantContext.clear();
    publisher.publishEvent(
        new DatasetSecurityLevelChangedEvent(
            DEFAULT_TEST_TENANT_ID,
            sensId,
            fx.levelId("내부"),
            fx.levelId("민감"),
            DatasetSecurityLevelChangedEvent.Cause.MANUAL));
    awaitTrue(() -> !hasVector(sensId));
    assertThat(hasVector(pubId)).isTrue();
  }

  @Test
  void levelsChangedEvent_runsInEventTenant() throws Exception {
    setLevel(sensId, "민감");
    TenantContext.clear();
    publisher.publishEvent(
        new SecurityLevelsChangedEvent(
            DEFAULT_TEST_TENANT_ID, SecurityLevelsChangedEvent.Kind.UPDATED, fx.levelId("민감")));
    awaitTrue(() -> !hasVector(sensId));
    assertThat(hasVector(pubId)).isTrue();
  }

  @Test
  void embeddingHostingChangedEvent_runsInEventTenant() throws Exception {
    setLevel(sensId, "민감");
    TenantContext.clear();
    publisher.publishEvent(new EmbeddingHostingChangedEvent(DEFAULT_TEST_TENANT_ID));
    awaitTrue(() -> !hasVector(sensId));
    assertThat(hasVector(pubId)).isTrue();
  }

  @Test
  void embeddingSave_publishesHostingEventOnlyOnChange_andSelfToExternalPurges() throws Exception {
    long sec = securityAdmin();
    save("SELF_HOSTED", sec);
    assertThat(events.stream(EmbeddingHostingChangedEvent.class))
        .singleElement()
        .extracting(EmbeddingHostingChangedEvent::tenantId)
        .isEqualTo(DEFAULT_TEST_TENANT_ID);
    // 같은 값 재저장은 발행하지 않는다.
    save("SELF_HOSTED", sec);
    assertThat(events.stream(EmbeddingHostingChangedEvent.class)).hasSize(1);

    // 자체 호스팅 동안 민감 데이터셋 벡터는 정책 위반이 아니다.
    setLevel(sensId, "민감");
    assertThat(hasVector(sensId)).isTrue();
    // 외부로 되돌리면(네 번째 트리거) 커밋 후 비동기 정리가 민감 벡터를 지운다.
    save("EXTERNAL", sec);
    assertThat(events.stream(EmbeddingHostingChangedEvent.class)).hasSize(2);
    awaitTrue(() -> !hasVector(sensId));
    assertThat(hasVector(pubId)).isTrue();
  }

  @Test
  void embeddingHostingToSelfHosted_enqueuesReembed() {
    long sec = securityAdmin();
    setLevel(sensId, "민감");
    purgeService.purgeDisallowed();
    assertThat(hasVector(sensId)).isFalse();
    // 외부 호스팅인 동안 민감 데이터셋은 판정식(할 일)에 없다.
    assertThat(missingDatasetIds()).doesNotContain(sensId);
    clearInvocations(reembedJob);

    // 관리자가 임베딩을 자체 호스팅으로 선언하면 — 민감 데이터셋이 판정식에 들어오고 저장이 재임베딩 잡을 투입한다(Review Focus 1).
    save("SELF_HOSTED", sec);
    assertThat(missingDatasetIds()).contains(sensId);
    verify(reembedJob).enqueue(DEFAULT_TEST_TENANT_ID);
  }

  @Test
  void embeddingSave_externalSameTarget_doesNotEnqueueForDisallowedDataset() {
    // 위 테스트의 대조군: 민감 벡터가 빠진 상태에서 외부 그대로 재저장하면 판정식에 민감 데이터셋이 없다.
    long sec = securityAdmin();
    setLevel(sensId, "민감");
    purgeService.purgeDisallowed();
    clearInvocations(reembedJob);
    save("EXTERNAL", sec);
    assertThat(missingDatasetIds()).doesNotContain(sensId);
    verify(reembedJob, never()).enqueue(anyLong());
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
  void startupRunner_runsOncePerTenant_thenFlagBlocksRerun() {
    setLevel(sensId, "민감");
    startupRunner.runForCurrentTenant();
    assertThat(hasVector(sensId)).isFalse();
    assertThat(tenantSettings.findValue(AiVectorPurgeStartupRunner.FLAG_KEY)).contains("done");
    // 플래그가 있으면 다시 돌지 않는다 — 벡터를 되살려도 그대로 남는다.
    int dim = SPACE.dimension().size();
    embeddingRepository.upsertEmbeddings(SPACE, List.of(sensId), List.of(axis(dim, 1)));
    startupRunner.runForCurrentTenant();
    assertThat(hasVector(sensId)).isTrue();
  }

  @Test
  void flagKey_isNotWritableThroughGenericSettings() {
    assertThat(SettingsOverridePolicy.isTenantOverridable(AiVectorPurgeStartupRunner.FLAG_KEY))
        .isFalse();
  }
}
