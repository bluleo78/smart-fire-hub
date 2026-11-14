package com.smartfirehub.vectorstore.qdrant;

import com.google.common.util.concurrent.ListenableFuture;
import com.smartfirehub.embedding.EmbeddingDimension;
import com.smartfirehub.vectorstore.ConditionalOnQdrantVectorStore;
import com.smartfirehub.vectorstore.VectorStoreLifecycle;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.CollectionInfo;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Qdrant 컬렉션을 기동 때 만들거나 확인하고, 저장소가 지금 쓸 수 있는지 판정한다(ADR 0002 결정 7·11, WD-719·WD-724).
 *
 * <ul>
 *   <li>미리 만드는 것: {@link EmbeddingDimension} 의 모든 값에 대해 {@code fh_datasets_{dim}}·{@code
 *       fh_chunks_{dim}}, 키워드 전용 컬렉션들. 새 차원은 열거형에 값을 더하면 다음 기동에 생긴다.
 *   <li>필요할 때 만드는 것: {@code fh_rows_{dim}} — 행 검색 차원은 테넌트 공급자를 따르므로 {@link #ensureRowCollection} 으로
 *       색인을 만들 때 준비한다.
 *   <li>이미 있으면 {@link QdrantCollectionSpec#mismatches} 로 대조하고 다르면 기동을 멈춘다. 빠진 payload 색인만은 채운다(컬렉션을
 *       만든 직후 죽은 경우).
 * </ul>
 *
 * <p><b>기동 때 실패를 두 갈래로 나눈다(2026-11-13·11-14 사용자 결정).</b>
 *
 * <ul>
 *   <li>일시 장애(접속 불가·제한 시간 초과 등, {@link #isTransient}) — 경고만 남기고 「준비 안 됨」으로 뜬다. 배포·노드 재부팅으로 Qdrant 가
 *       잠깐 내려가 있어도 api 가 재시작을 반복하지 않게 하려는 것이다. 그동안 {@link #available()} 이 거짓이라 기능 응답이 「저장소 일시 중단」을
 *       알리고, {@link #retryPrepare()} 가 주기마다 다시 준비한다.
 *   <li>설정 오류(구성 불일치, API 키 거부 UNAUTHENTICATED·PERMISSION_DENIED, 잘못된 요청) — 기다려도 저절로 풀리지 않으므로 기동을
 *       멈춰 배포 때 바로 드러나게 한다.
 * </ul>
 *
 * <p>실행 중에 다시 준비하다 구성 불일치를 만나면 앱을 멈출 수 없다. ERROR 로그를 한 번 남기고 재기동 전까지 「사용 불가」로 둔다.
 *
 * <p>여러 api 파드가 동시에 떠도 된다. 만들기가 「이미 있음」으로 실패하면 다시 읽어 대조한다.
 */
@Slf4j
@Component
@ConditionalOnQdrantVectorStore
public class QdrantVectorStoreLifecycle implements VectorStoreLifecycle, InitializingBean {

  /** 상태 점검 제한 시간. 검색 화면 안내용이라 짧게 둔다. */
  private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(2);

  /**
   * {@link #available()} 결과를 기억하는 시간. 기능 응답·검색·배경 작업이 요청마다 묻는데, 저장소가 응답 없이 패킷을 버리는 상태면 점검 한 번이
   * {@link #HEALTH_TIMEOUT} 만큼 막힌다. 그래서 몇 초 동안은 앞 결과를 쓴다.
   */
  static final long AVAILABILITY_CACHE_MS = 5_000;

  /**
   * 일시 장애가 아닌 gRPC 상태 — 같은 설정으로 다시 해도 결과가 같다. 키 거부(UNAUTHENTICATED·PERMISSION_DENIED)와 잘못된 요청·미지원은
   * 설정 오류로 보고 기동을 멈춘다. 그 밖의 상태(UNAVAILABLE·DEADLINE_EXCEEDED·INTERNAL 등)는 일시 장애로 본다 — 모르는 실패로 재시작
   * 반복을 되살리지 않으려는 것이다.
   */
  private static final EnumSet<Status.Code> FATAL_CODES =
      EnumSet.of(
          Status.Code.UNAUTHENTICATED,
          Status.Code.PERMISSION_DENIED,
          Status.Code.INVALID_ARGUMENT,
          Status.Code.UNIMPLEMENTED);

  private final QdrantClient client;
  private final QdrantProperties props;
  private final LongSupplier clock;

  /** 이 프로세스에서 이미 준비를 마친 컬렉션. 행 색인마다 원격 확인을 되풀이하지 않게 한다. */
  private final Set<String> ready = ConcurrentHashMap.newKeySet();

  /** 미리 만드는 컬렉션 전부를 준비했는가. 거짓이면 {@link #ensureReady()} 가 다시 준비한다. */
  private volatile boolean prepared;

  /** 실행 중 재준비에서 만난 구성 불일치. 한 번 생기면 재기동 전까지 다시 시도하지 않는다(설치 후 구성 변경은 지원하지 않음, 결정 10). */
  private volatile String misconfiguration;

  /** {@link #available()} 의 마지막 결과와 그 시각(clock 기준 밀리초). */
  private volatile boolean lastAvailable;

  private volatile long lastCheckedAt = Long.MIN_VALUE;

  /**
   * 재준비 잠금. 요청 경로는 이 잠금을 <b>기다리지 않는다</b>({@code tryLock}) — 저장소가 패킷을 버리는 장애에서 재준비 한 번이 제한 시간({@code
   * QDRANT_TIMEOUT})만큼 걸리는데, 그동안 기능 응답·검색·커밋 뒤 쓰기가 줄을 서면 안 된다.
   */
  private final ReentrantLock prepareLock = new ReentrantLock();

  /** 가용성 점검이 도는 중인가. 도는 동안 다른 호출은 기다리지 않고 마지막 결과를 쓴다. */
  private final AtomicBoolean checking = new AtomicBoolean();

  @Autowired
  public QdrantVectorStoreLifecycle(QdrantClient client, QdrantProperties props) {
    this(client, props, System::currentTimeMillis);
  }

  /** 시험용 — 가용성 캐시의 시계를 바꾼다. */
  QdrantVectorStoreLifecycle(QdrantClient client, QdrantProperties props, LongSupplier clock) {
    this.client = client;
    this.props = props;
    this.clock = clock;
  }

  /**
   * 빈이 만들어질 때 준비한다. 설정 오류면 던져 기동을 멈추고, 일시 장애면 경고만 남기고 「준비 안 됨」으로 뜬다(클래스 설명).
   *
   * @throws IllegalStateException 구성 불일치이거나 저장소가 설정 오류로 거부할 때
   */
  @Override
  public void afterPropertiesSet() {
    try {
      prepare();
    } catch (QdrantAccessException e) {
      if (!isTransient(e)) {
        throw new IllegalStateException(
            "Qdrant 가 요청을 거부했습니다 — API 키·주소 설정을 확인하세요. 기동을 멈춥니다: " + e.getMessage(), e);
      }
      log.warn("Qdrant 에 접속하지 못했습니다 — 「저장소 일시 중단」으로 기동하고 복구되면 컬렉션을 준비합니다: {}", e.getMessage());
    }
  }

  @Override
  public void prepare() {
    List<QdrantCollectionSpec> specs = new ArrayList<>();
    for (EmbeddingDimension dim : EmbeddingDimension.values()) {
      specs.add(QdrantCollectionSpec.datasets(dim));
      specs.add(QdrantCollectionSpec.chunks(dim));
    }
    // 키워드 컬렉션은 차원과 무관해 하나씩만 둔다(A안, WD-720).
    specs.add(QdrantCollectionSpec.datasetsKeyword());
    specs.add(QdrantCollectionSpec.chunksKeyword());
    specs.add(QdrantCollectionSpec.rowsKeyword());
    for (QdrantCollectionSpec spec : specs) {
      ensure(spec);
    }
    prepared = true;
    log.info("Qdrant 컬렉션 준비 완료: {}개(스키마 버전 {})", specs.size(), QdrantCollectionSpec.SCHEMA_VERSION);
  }

  /**
   * 준비가 끝났으면 바로 참, 아니면 한 번 다시 준비해 본다(WD-724). 기동 때 Qdrant 가 내려가 있어 컬렉션을 못 만든 새 설치본이, 복구 뒤 첫 쓰기에서
   * 「컬렉션 없음」으로 실패하지 않게 하려는 것이다. 다른 스레드가 이미 준비 중이면 기다리지 않고 거짓을 돌려준다.
   *
   * @return 지금 컬렉션이 모두 준비돼 있으면 참
   */
  public boolean ensureReady() {
    if (prepared) return true;
    if (misconfiguration != null) return false;
    if (!prepareLock.tryLock()) return false;
    try {
      if (prepared) return true;
      try {
        prepare();
        log.info("Qdrant 가 복구돼 컬렉션을 준비했습니다");
        return true;
      } catch (QdrantAccessException e) {
        log.debug("Qdrant 재준비 실패(아직 내려가 있음): {}", e.getMessage());
        return false;
      } catch (IllegalStateException e) {
        // 실행 중에는 앱을 멈출 수 없다 — 한 번만 크게 알리고 재기동 전까지 사용 불가로 둔다.
        misconfiguration = e.getMessage();
        log.error("Qdrant 재준비 중 구성 불일치 — 재기동 전까지 벡터 저장소를 쓰지 않습니다: {}", e.getMessage());
        return false;
      }
    } finally {
      prepareLock.unlock();
    }
  }

  /**
   * 준비가 안 된 동안 주기마다 다시 준비한다. 아웃박스 워커·스윕을 설정으로 꺼 둔 설치본도 복구를 놓치지 않게 따로 둔다. 준비가 끝나면 아무 일도 하지 않는다. 상태
   * 점검(짧은 제한 시간)을 먼저 해, 내려가 있는 동안 재준비 잠금을 긴 제한 시간만큼 쥐지 않게 한다.
   */
  @Scheduled(
      initialDelayString = "${firehub.vector-store.qdrant.retry-prepare-interval-ms:15000}",
      fixedDelayString = "${firehub.vector-store.qdrant.retry-prepare-interval-ms:15000}")
  public void retryPrepare() {
    if (!prepared && healthy()) ensureReady();
  }

  /** 행 검색 dense 컬렉션({@code fh_rows_{dim}})을 준비하고 이름을 돌려준다. */
  public String ensureRowCollection(int dim) {
    QdrantCollectionSpec spec = QdrantCollectionSpec.rows(dim);
    ensure(spec);
    return spec.name();
  }

  /**
   * 상태 점검이 성공하고 컬렉션 준비가 끝났으면 참. 결과는 {@link #AVAILABILITY_CACHE_MS} 동안 기억한다. 점검이 성공했는데 준비가 안 돼 있으면
   * 여기서 다시 준비한다 — 복구를 가장 먼저 알아채는 곳이 여기다.
   *
   * <p>요청 경로에서 불리므로 다른 점검·재준비를 기다리지 않는다. 점검이 이미 도는 중이면 마지막 결과를 쓰고, 재준비가 도는 중이면 거짓이다. 그래서 한 호출이 막히는
   * 시간은 많아야 상태 점검 제한 시간({@link #HEALTH_TIMEOUT})이다.
   */
  @Override
  public boolean available() {
    long now = clock.getAsLong();
    if (lastCheckedAt != Long.MIN_VALUE && now - lastCheckedAt < AVAILABILITY_CACHE_MS) {
      return lastAvailable;
    }
    if (!checking.compareAndSet(false, true)) {
      return lastAvailable;
    }
    try {
      boolean ok = healthy() && ensureReady();
      lastAvailable = ok;
      lastCheckedAt = clock.getAsLong();
      return ok;
    } finally {
      checking.set(false);
    }
  }

  /** 상태 점검 한 번. 실패는 거짓. */
  private boolean healthy() {
    try {
      QdrantSupport.await(client.healthCheckAsync(HEALTH_TIMEOUT), HEALTH_TIMEOUT, "Qdrant 상태 점검");
      return true;
    } catch (QdrantAccessException e) {
      log.debug("Qdrant 상태 점검 실패: {}", e.getMessage());
      return false;
    }
  }

  /**
   * 저장소 실패가 기다리면 풀릴 수 있는 일시 장애인가. 원인 사슬에서 gRPC 상태를 찾아 {@link #FATAL_CODES} 면 거짓, 그 밖의 상태·제한 시간
   * 초과·중단은 참이다.
   */
  static boolean isTransient(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof StatusRuntimeException sre) {
        return !FATAL_CODES.contains(sre.getStatus().getCode());
      }
      if (t instanceof TimeoutException || t instanceof InterruptedException) {
        return true;
      }
    }
    return true;
  }

  /**
   * 컬렉션 하나를 만들거나 대조한다. 저장소 호출 실패는 {@link QdrantAccessException}, 구성 불일치는 {@link
   * IllegalStateException}.
   */
  void ensure(QdrantCollectionSpec spec) {
    if (ready.contains(spec.name())) {
      return;
    }
    if (!await(client.collectionExistsAsync(spec.name()), "컬렉션 확인")) {
      try {
        await(client.createCollectionAsync(spec.toCreateRequest(props)), "컬렉션 생성");
        log.info("Qdrant 컬렉션 생성: {}", spec.name());
      } catch (QdrantAccessException e) {
        // 다른 파드가 먼저 만들었으면 아래 대조로 넘어간다. 정말 없는데 실패했으면 그대로 던진다.
        if (!await(client.collectionExistsAsync(spec.name()), "컬렉션 확인")) {
          throw e;
        }
        log.info("Qdrant 컬렉션 {} 은 다른 프로세스가 먼저 만들었다 — 대조로 넘어간다", spec.name());
      }
    }
    CollectionInfo info = await(client.getCollectionInfoAsync(spec.name()), "컬렉션 정보 읽기");
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
              spec.name(), field.name(), field.type(), field.params(), true, null, null),
          "payload 색인 생성");
      log.info("Qdrant payload 색인 생성: {}.{}", spec.name(), field.name());
    }
    ready.add(spec.name());
  }

  /** 설정의 제한 시간 안에 기다린다. 실패는 {@link QdrantAccessException}(색인 구현과 같은 종류). */
  private <T> T await(ListenableFuture<T> future, String what) {
    return QdrantSupport.await(future, props.timeout(), "Qdrant " + what);
  }
}
