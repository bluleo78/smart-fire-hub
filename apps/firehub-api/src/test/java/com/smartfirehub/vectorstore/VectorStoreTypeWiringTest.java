package com.smartfirehub.vectorstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.global.platformdb.PlatformDbFeature;
import com.smartfirehub.global.platformdb.PlatformDbStartupGuardConfig;
import com.smartfirehub.vectorstore.off.OffVectorStoreFeatures;
import com.smartfirehub.vectorstore.pg.PgVectorStoreFeatures;
import com.smartfirehub.vectorstore.qdrant.QdrantClientConfig;
import com.smartfirehub.vectorstore.qdrant.QdrantVectorStoreFeatures;
import com.smartfirehub.vectorstore.qdrant.QdrantVectorStoreLifecycle;
import io.qdrant.client.QdrantClient;
import java.util.TimeZone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 벡터 저장소 종류 설정({@code firehub.vector-store.type}, ADR 0002 결정 1)과 플랫폼 DB 종류를 함께 보고 구현 빈을 고르는지 확인한다.
 * 잘못된 설정은 기동 검사가 맨 앞에서 막아야 한다.
 *
 * <p>포트는 {@code ..repository} 패키지 밖({@code vectorstore})에 있어 짝 검사(PlatformRepositoryPairTest)를 받지
 * 않는다. 포트의 Tibero 쪽은 MyBatis 가 아니라 꺼짐 구현이기 때문이다. 그래서 짝 검사 대신 네 조합의 배선을 이 테스트로 고정한다.
 *
 * <ul>
 *   <li>postgres+platform → pgvector
 *   <li>tibero+platform → 꺼짐
 *   <li>qdrant(플랫폼 DB 무관) → Qdrant 구현, 행 검색은 PG 데이터 저장소 + API 키일 때만(WD-722)
 * </ul>
 */
class VectorStoreTypeWiringTest {

  /** 아무도 듣지 않는 Qdrant 주소 — 접속 거부(일시 장애)로 기동하는 길을 탄다. */
  private static final String UNREACHABLE = "firehub.vector-store.qdrant.url=http://127.0.0.1:1";

  private final TimeZone original = TimeZone.getDefault();

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(
              PlatformDbStartupGuardConfig.class,
              PgVectorStoreFeatures.class,
              OffVectorStoreFeatures.class,
              QdrantClientConfig.class,
              // 기능 판정이 가용성을 묻는다(WD-724). Qdrant 가 없어도 일시 장애라 경고만 남기고 뜬다.
              QdrantVectorStoreLifecycle.class,
              // 행 검색 판정이 API 키 유무를 묻는다(WD-722).
              com.smartfirehub.vectorstore.qdrant.QdrantRowClients.class,
              QdrantVectorStoreFeatures.class);

  @AfterEach
  void restoreTimeZone() {
    TimeZone.setDefault(original);
  }

  @Test
  void 설정이_없으면_platform_이고_PostgreSQL_이면_pgvector_구현이_뜬다() {
    runner.run(
        ctx -> {
          assertThat(ctx).hasNotFailed();
          assertThat(ctx.getBean(VectorStoreFeatures.class))
              .isInstanceOf(PgVectorStoreFeatures.class);
          assertThat(ctx).doesNotHaveBean(OffVectorStoreFeatures.class);
          // Qdrant 클라이언트·기동 준비는 qdrant 일 때만 — platform 설치본은 Qdrant 에 접속하지 않는다(WD-719)
          assertThat(ctx).doesNotHaveBean(QdrantClient.class);
          assertThat(ctx).doesNotHaveBean(VectorStoreLifecycle.class);
        });
  }

  @Test
  void platform_과_postgres_를_명시해도_pgvector_구현이다() {
    runner
        .withPropertyValues(
            "firehub.vector-store.type=platform", "firehub.platform-db.type=postgres")
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              VectorStoreFeatures features = ctx.getBean(VectorStoreFeatures.class);
              assertThat(features).isInstanceOf(PgVectorStoreFeatures.class);
              assertThat(features.supports(PlatformDbFeature.VECTOR_SEARCH)).isTrue();
              assertThat(features.supports(PlatformDbFeature.TRIGRAM_SEARCH)).isTrue();
            });
  }

  /** 두 조건(플랫폼 DB·벡터 저장소)을 함께 단 빈이 한쪽만 맞을 때 뜨지 않는지까지 본다. */
  @Test
  void platform_과_tibero_면_꺼짐_구현만_뜬다() {
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    runner
        .withPropertyValues("firehub.vector-store.type=platform", "firehub.platform-db.type=tibero")
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              VectorStoreFeatures features = ctx.getBean(VectorStoreFeatures.class);
              assertThat(features).isInstanceOf(OffVectorStoreFeatures.class);
              assertThat(features.supports(PlatformDbFeature.VECTOR_SEARCH)).isFalse();
              assertThat(features.supports(PlatformDbFeature.TRIGRAM_SEARCH)).isFalse();
              assertThat(ctx).doesNotHaveBean(PgVectorStoreFeatures.class);
            });
  }

  /**
   * qdrant 는 플랫폼 DB 와 무관하게 Qdrant 기능 판정이 뜨고 pgvector·꺼짐 판정은 뜨지 않는다(WD-720). 컬렉션 준비(Qdrant 접속)는 실제
   * 컨테이너가 필요해 {@code QdrantPlatformSearchIntegrationTest} 가 본다.
   *
   * <p>여기서는 닿지 않는 주소를 준다 — Qdrant 가 내려가 있어도 컨텍스트가 뜨고 가용성만 거짓이어야 한다(WD-724, 일시 장애로 기동).
   */
  @Test
  void qdrant_는_PostgreSQL_플랫폼에서_Qdrant_구현이_뜬다() {
    runner
        .withPropertyValues("firehub.vector-store.type=qdrant", UNREACHABLE)
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              VectorStoreFeatures features = ctx.getBean(VectorStoreFeatures.class);
              assertThat(features).isInstanceOf(QdrantVectorStoreFeatures.class);
              assertThat(features.supports(PlatformDbFeature.VECTOR_SEARCH)).isTrue();
              assertThat(features.supports(PlatformDbFeature.TRIGRAM_SEARCH)).isTrue();
              assertThat(features.available()).isFalse();
              assertThat(ctx).hasSingleBean(QdrantClient.class);
              assertThat(ctx).doesNotHaveBean(PgVectorStoreFeatures.class);
            });
  }

  @Test
  void qdrant_는_Tibero_플랫폼에서도_Qdrant_구현이_뜬다() {
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    runner
        .withPropertyValues(
            "firehub.vector-store.type=qdrant", "firehub.platform-db.type=tibero", UNREACHABLE)
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              assertThat(ctx.getBean(VectorStoreFeatures.class))
                  .isInstanceOf(QdrantVectorStoreFeatures.class);
              assertThat(ctx).doesNotHaveBean(OffVectorStoreFeatures.class);
            });
  }

  /**
   * qdrant 조합의 행 검색(WD-722)은 데이터 저장소가 PostgreSQL·Tibero(WD-723)이고 API 키가 있을 때만 켜진다. 원본 읽기 구현이 없는
   * 저장소(MySQL)는 꺼짐, 키가 없으면 테넌트 컬렉션 토큰을 서명할 수 없어 꺼짐.
   */
  @Test
  void qdrant_조합의_행_검색은_PG_Tibero_데이터_저장소와_API_키가_있을_때만() {
    var withKey =
        new com.smartfirehub.vectorstore.qdrant.QdrantRowClients(
            new com.smartfirehub.vectorstore.qdrant.QdrantProperties(
                "http://localhost:1", "k", 6, true, java.time.Duration.ofSeconds(1)));
    var noKey =
        new com.smartfirehub.vectorstore.qdrant.QdrantRowClients(
            new com.smartfirehub.vectorstore.qdrant.QdrantProperties(
                "http://localhost:1", "", 6, true, java.time.Duration.ofSeconds(1)));
    try {
      for (DataStoreBackend backend : DataStoreBackend.values()) {
        assertThat(new QdrantVectorStoreFeatures(null, withKey).supportsRowSearch(backend))
            .as("%s", backend)
            .isEqualTo(
                backend == DataStoreBackend.POSTGRESQL || backend == DataStoreBackend.TIBERO);
        assertThat(new QdrantVectorStoreFeatures(null, noKey).supportsRowSearch(backend))
            .as("키 없음 %s", backend)
            .isFalse();
      }
    } finally {
      try {
        withKey.destroy();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  @Test
  void 알_수_없는_값이면_기동이_멈춘다() {
    runner
        .withPropertyValues("firehub.vector-store.type=milvus")
        .run(ctx -> assertThat(ctx).getFailure().hasStackTraceContaining("platform 또는 qdrant"));
  }

  /** 플랫폼 DB 종류와 같은 규칙이다. 대소문자만 무시하고, 빈 값·앞뒤 공백은 거절한다. */
  @Test
  void 값은_대소문자만_가리지_않고_공백은_거절한다() {
    assertThat(VectorStoreType.resolve(null)).isEqualTo(VectorStoreType.PLATFORM);
    assertThat(VectorStoreType.resolve("PLATFORM")).isEqualTo(VectorStoreType.PLATFORM);
    for (String value : new String[] {"", " platform", "platform ", "\tplatform"}) {
      assertThatThrownBy(() -> VectorStoreType.resolve(value))
          .as("값 '%s'", value)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("platform 또는 qdrant");
    }
  }

  /** platform 조합의 행 검색은 플랫폼 DB 와 상관없이 데이터 저장소가 PostgreSQL 일 때만 켜진다(예전 판정 그대로). */
  @Test
  void platform_조합의_행_검색은_데이터_저장소가_PostgreSQL_일_때만() {
    for (VectorStoreFeatures features :
        new VectorStoreFeatures[] {new PgVectorStoreFeatures(), new OffVectorStoreFeatures()}) {
      for (DataStoreBackend backend : DataStoreBackend.values()) {
        assertThat(features.supportsRowSearch(backend))
            .as("%s / %s", features.getClass().getSimpleName(), backend)
            .isEqualTo(backend.supportsRowSearch());
      }
    }
  }
}
