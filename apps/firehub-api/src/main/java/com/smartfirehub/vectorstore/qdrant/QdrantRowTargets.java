package com.smartfirehub.vectorstore.qdrant;

import com.smartfirehub.apiconnection.service.EncryptionService;
import com.smartfirehub.vectorstore.ConditionalOnQdrantVectorStore;
import com.smartfirehub.vectorstore.tenant.TenantVectorStore;
import com.smartfirehub.vectorstore.tenant.TenantVectorStoreRepository;
import com.smartfirehub.vectorstore.tenant.TenantVectorStoreTargets;
import io.qdrant.client.QdrantClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/**
 * 테넌트 행 검색 색인이 놓이는 Qdrant 인스턴스를 고른다(WD-742). 설정 행({@code tenant_vector_store})이 없으면 설치본 공유 Qdrant,
 * 있으면 그 테넌트 전용 인스턴스다.
 *
 * <p>두 대상은 같은 일을 한다 — 테넌트 행 컬렉션 목록·준비(관리 권한), 테넌트 컬렉션만 허용한 토큰 클라이언트({@link QdrantRowClients}), 지금
 * 응답하는지. 그래서 색인·아웃박스 코드는 대상만 바꿔 같은 경로를 탄다. 전용 인스턴스도 토큰 격리를 그대로 쓴다(연결 확인이 JWT RBAC 를 요구한다) — 경로를 하나로
 * 두고, 고객 인프라에서도 키 노출 범위를 좁힌다.
 *
 * <p>장애 격리: 전용 인스턴스마다 채널·관리 클라이언트·상태 캐시가 따로라, 한 인스턴스가 내려가도 다른 테넌트(공유·다른 전용)의 호출은 그 채널을 타지 않는다. 전용
 * 설정은 테넌트를 만들 때만 정한다(전환 없음) — 그래서 인스턴스를 한 번 만들면 앱이 끝날 때까지 쓴다.
 */
@Slf4j
@Component
@ConditionalOnQdrantVectorStore
public class QdrantRowTargets implements TenantVectorStoreTargets, DisposableBean {

  /** 행 검색 색인 대상 인스턴스 하나. */
  interface RowTarget {

    /** 이 테넌트의 행 컬렉션 이름들(키워드 + 있는 dense 전부, 정렬). 관리 권한 호출. */
    List<String> rowCollections(long tenantId);

    /** 테넌트 행 컬렉션을 준비한다({@link QdrantVectorStoreLifecycle#ensureRowCollections}). */
    void ensureRowCollections(long tenantId, int dim);

    /** 이 컬렉션들만 허용한 토큰 클라이언트. */
    QdrantClient forCollections(List<String> collections);

    /** 지금 응답하는가(짧게 캐시). */
    boolean available();

    /** 전용 인스턴스인가. */
    boolean dedicated();
  }

  private final QdrantProperties installation;
  private final TenantVectorStoreRepository repository;
  private final EncryptionService encryption;
  private final RowTarget shared;

  /** 테넌트 → 전용 설정(없으면 empty). 테넌트 생성 커밋 뒤 {@link #invalidate} 로 지운다. */
  private final Map<Long, Optional<TenantVectorStore>> configs = new ConcurrentHashMap<>();

  /** 테넌트 → 전용 인스턴스(만든 것만). */
  private final Map<Long, Dedicated> dedicated = new ConcurrentHashMap<>();

  public QdrantRowTargets(
      QdrantProperties installation,
      QdrantVectorStoreLifecycle lifecycle,
      QdrantRowClients rowClients,
      TenantVectorStoreRepository repository,
      EncryptionService encryption) {
    this.installation = installation;
    this.repository = repository;
    this.encryption = encryption;
    this.shared = new Shared(lifecycle, rowClients);
  }

  /** 공유 Qdrant 대상만 쓰는 경우(시험 등)를 위한 공유 대상 — 운영은 {@link #forTenant} 가 고른다. */
  static RowTarget shared(QdrantVectorStoreLifecycle lifecycle, QdrantRowClients rowClients) {
    return new Shared(lifecycle, rowClients);
  }

  /** 이 테넌트의 행 검색 색인 대상. */
  RowTarget forTenant(long tenantId) {
    Optional<TenantVectorStore> cfg = configs.computeIfAbsent(tenantId, repository::find);
    if (cfg.isEmpty()) return shared;
    return dedicated.computeIfAbsent(
        tenantId, id -> new Dedicated(installation, cfg.get(), encryption));
  }

  /** 이 테넌트의 행 검색 대상이 지금 응답하는가 — 전용이면 그 인스턴스, 아니면 공유 Qdrant. */
  public boolean available(long tenantId) {
    return forTenant(tenantId).available();
  }

  @Override
  public void invalidate(long tenantId) {
    configs.remove(tenantId);
    Dedicated old = dedicated.remove(tenantId);
    if (old != null) old.close();
  }

  @Override
  public void destroy() {
    dedicated.values().forEach(Dedicated::close);
    dedicated.clear();
  }

  /** 전용 인스턴스 접속 설정 — 주소·키만 그 인스턴스 것이고 샤드·양자화·제한 시간은 설치본 설정을 따른다(컬렉션 모양을 공유 Qdrant 와 같게). */
  static QdrantProperties dedicatedProperties(
      QdrantProperties installation, String host, int grpcPort, boolean tls, String apiKey) {
    String url = (tls ? "https" : "http") + "://" + host + ":" + grpcPort;
    return new QdrantProperties(
        url,
        apiKey,
        installation.shardNumber(),
        installation.quantization(),
        installation.timeout(),
        installation.rowShardNumber());
  }

  /** 설치본 공유 Qdrant — 기존 기동 준비({@link QdrantVectorStoreLifecycle})·토큰 클라이언트 그대로. */
  private record Shared(QdrantVectorStoreLifecycle lifecycle, QdrantRowClients clients)
      implements RowTarget {
    @Override
    public List<String> rowCollections(long tenantId) {
      return lifecycle.rowCollections(tenantId);
    }

    @Override
    public void ensureRowCollections(long tenantId, int dim) {
      lifecycle.ensureRowCollections(tenantId, dim);
    }

    @Override
    public QdrantClient forCollections(List<String> collections) {
      return clients.forCollections(collections);
    }

    @Override
    public boolean available() {
      return lifecycle.available();
    }

    @Override
    public boolean dedicated() {
      return false;
    }
  }

  /**
   * 테넌트 전용 인스턴스. 관리 클라이언트(그 인스턴스 API 키)로 컬렉션을 준비·나열하고, 행 읽기·쓰기는 그 키로 서명한 테넌트 컬렉션 토큰으로 한다. 공유
   * 컬렉션(카탈로그·청크)은 만들지 않는다 — 기동 준비({@code prepare})를 부르지 않고 행 컬렉션만 다룬다.
   */
  static final class Dedicated implements RowTarget {

    /** 상태 점검 제한 시간 — 요청 경로가 오래 막히지 않게 짧게(공유 쪽과 같음). */
    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(2);

    private final QdrantClient admin;
    private final QdrantRowClients clients;
    private final QdrantVectorStoreLifecycle collections;
    private final String label;
    private volatile long lastCheckedAt = Long.MIN_VALUE;
    private volatile boolean lastAvailable;

    Dedicated(QdrantProperties installation, TenantVectorStore cfg, EncryptionService encryption) {
      QdrantProperties props =
          dedicatedProperties(
              installation,
              cfg.host(),
              cfg.grpcPort(),
              cfg.tls(),
              encryption.decrypt(cfg.apiKeyEnc()));
      this.admin = QdrantClientConfig.create(props);
      this.clients = new QdrantRowClients(props);
      this.collections = new QdrantVectorStoreLifecycle(admin, props);
      this.label = "tenant=" + cfg.tenantId() + " " + cfg.host() + ":" + cfg.grpcPort();
      log.info("테넌트 전용 Qdrant 연결 준비: {}", label);
    }

    @Override
    public List<String> rowCollections(long tenantId) {
      return collections.rowCollections(tenantId);
    }

    @Override
    public void ensureRowCollections(long tenantId, int dim) {
      collections.ensureRowCollections(tenantId, dim);
    }

    @Override
    public QdrantClient forCollections(List<String> names) {
      return clients.forCollections(names);
    }

    /** 상태 점검(캐시 {@link QdrantVectorStoreLifecycle#AVAILABILITY_CACHE_MS}). */
    @Override
    public boolean available() {
      long now = System.currentTimeMillis();
      if (lastCheckedAt != Long.MIN_VALUE
          && now - lastCheckedAt < QdrantVectorStoreLifecycle.AVAILABILITY_CACHE_MS) {
        return lastAvailable;
      }
      boolean ok;
      try {
        QdrantSupport.await(
            admin.healthCheckAsync(HEALTH_TIMEOUT), HEALTH_TIMEOUT, "전용 Qdrant 상태 점검");
        ok = true;
      } catch (QdrantAccessException e) {
        log.debug("전용 Qdrant 상태 점검 실패({}): {}", label, e.getMessage());
        ok = false;
      }
      lastAvailable = ok;
      lastCheckedAt = System.currentTimeMillis();
      return ok;
    }

    @Override
    public boolean dedicated() {
      return true;
    }

    void close() {
      try {
        clients.destroy();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      admin.close();
    }
  }
}
