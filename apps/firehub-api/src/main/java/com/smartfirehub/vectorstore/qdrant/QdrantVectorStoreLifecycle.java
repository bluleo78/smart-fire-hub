package com.smartfirehub.vectorstore.qdrant;

import com.smartfirehub.embedding.EmbeddingDimension;
import com.smartfirehub.vectorstore.ConditionalOnQdrantVectorStore;
import com.smartfirehub.vectorstore.VectorStoreLifecycle;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.CollectionInfo;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Qdrant 컬렉션을 기동 때 만들거나 확인한다(ADR 0002 결정 7, WD-719).
 *
 * <ul>
 *   <li>미리 만드는 것: {@link EmbeddingDimension} 의 모든 값에 대해 {@code fh_datasets_{dim}}·{@code
 *       fh_chunks_{dim}}, 그리고 키워드 전용 {@code fh_rows_kw}. 새 차원은 열거형에 값을 더하면 다음 기동에 생긴다.
 *   <li>필요할 때 만드는 것: {@code fh_rows_{dim}} — 행 검색 차원은 테넌트 공급자를 따르므로 {@link #ensureRowCollection} 으로
 *       색인을 만들 때 준비한다.
 *   <li>이미 있으면 {@link QdrantCollectionSpec#mismatches} 로 대조하고 다르면 기동을 멈춘다. 빠진 payload 색인만은 채운다(컬렉션을
 *       만든 직후 죽은 경우).
 * </ul>
 *
 * <p>여러 api 파드가 동시에 떠도 된다. 만들기가 「이미 있음」으로 실패하면 다시 읽어 대조한다.
 */
@Slf4j
@Component
@ConditionalOnQdrantVectorStore
public class QdrantVectorStoreLifecycle implements VectorStoreLifecycle, InitializingBean {

  /** 상태 점검 제한 시간. 검색 화면 안내용이라 짧게 둔다. */
  private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(2);

  private final QdrantClient client;
  private final QdrantProperties props;

  /** 이 프로세스에서 이미 준비를 마친 컬렉션. 행 색인마다 원격 확인을 되풀이하지 않게 한다. */
  private final Set<String> ready = ConcurrentHashMap.newKeySet();

  public QdrantVectorStoreLifecycle(QdrantClient client, QdrantProperties props) {
    this.client = client;
    this.props = props;
  }

  /**
   * 빈이 만들어질 때 준비한다. 여기서 던지면 컨텍스트가 뜨지 않아 기동이 멈춘다(구성 불일치는 설계 의도).
   *
   * <p>지금은 Qdrant 에 <b>접속하지 못해도</b> 똑같이 멈춘다. 내려가 있는 동안에도 기동하고 「저장소 일시 중단」으로 안내하다가 복구되면 준비하도록
   * 나누는 일은 WD-724 에서 한다(2026-11-13 사용자 결정). 그 전까지 qdrant 설정은 {@code VectorStoreType} 이 기동을 거부하므로 운영 영향은
   * 없다.
   */
  @Override
  public void afterPropertiesSet() {
    prepare();
  }

  @Override
  public void prepare() {
    List<QdrantCollectionSpec> specs = new ArrayList<>();
    for (EmbeddingDimension dim : EmbeddingDimension.values()) {
      specs.add(QdrantCollectionSpec.datasets(dim));
      specs.add(QdrantCollectionSpec.chunks(dim));
    }
    specs.add(QdrantCollectionSpec.rowsKeyword());
    for (QdrantCollectionSpec spec : specs) {
      ensure(spec);
    }
    log.info("Qdrant 컬렉션 준비 완료: {}개(스키마 버전 {})", specs.size(), QdrantCollectionSpec.SCHEMA_VERSION);
  }

  /** 행 검색 dense 컬렉션({@code fh_rows_{dim}})을 준비하고 이름을 돌려준다. */
  public String ensureRowCollection(int dim) {
    QdrantCollectionSpec spec = QdrantCollectionSpec.rows(dim);
    ensure(spec);
    return spec.name();
  }

  @Override
  public boolean available() {
    try {
      await(client.healthCheckAsync(HEALTH_TIMEOUT));
      return true;
    } catch (RuntimeException e) {
      log.debug("Qdrant 상태 점검 실패: {}", e.getMessage());
      return false;
    }
  }

  /** 컬렉션 하나를 만들거나 대조한다. */
  void ensure(QdrantCollectionSpec spec) {
    if (ready.contains(spec.name())) {
      return;
    }
    if (!await(client.collectionExistsAsync(spec.name()))) {
      try {
        await(client.createCollectionAsync(spec.toCreateRequest(props)));
        log.info("Qdrant 컬렉션 생성: {}", spec.name());
      } catch (RuntimeException e) {
        // 다른 파드가 먼저 만들었으면 아래 대조로 넘어간다. 정말 없는데 실패했으면 그대로 던진다.
        if (!await(client.collectionExistsAsync(spec.name()))) {
          throw e;
        }
        log.info("Qdrant 컬렉션 {} 은 다른 프로세스가 먼저 만들었다 — 대조로 넘어간다", spec.name());
      }
    }
    CollectionInfo info = await(client.getCollectionInfoAsync(spec.name()));
    List<String> mismatches = spec.mismatches(info, props);
    if (!mismatches.isEmpty()) {
      throw new IllegalStateException(
          "Qdrant 컬렉션 "
              + spec.name()
              + " 구성이 기대와 다릅니다 — 기동을 멈춥니다(설치 후 구성 변경은 지원하지 않습니다, ADR 0002 결정 10): "
              + String.join("; ", mismatches));
    }
    for (QdrantCollectionSpec.PayloadField field : spec.missingPayloadFields(info)) {
      await(
          client.createPayloadIndexAsync(
              spec.name(), field.name(), field.type(), field.params(), true, null, null));
      log.info("Qdrant payload 색인 생성: {}.{}", spec.name(), field.name());
    }
    ready.add(spec.name());
  }

  /** 비동기 호출을 설정의 제한 시간 안에 기다린다. 실패는 원인을 담은 {@link IllegalStateException} 으로 바꾼다. */
  private <T> T await(Future<T> future) {
    try {
      return future.get(props.timeout().toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Qdrant 호출이 중단됐습니다", e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause() == null ? e : e.getCause();
      throw new IllegalStateException("Qdrant 호출 실패: " + cause.getMessage(), cause);
    } catch (TimeoutException e) {
      future.cancel(true);
      throw new IllegalStateException("Qdrant 호출이 " + props.timeout() + " 안에 끝나지 않았습니다", e);
    }
  }
}
