package com.smartfirehub.vectorstore.qdrant;

import com.smartfirehub.vectorstore.ConditionalOnQdrantVectorStore;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 행 검색 색인용 Qdrant 클라이언트 — 테넌트 컬렉션만 허용한 토큰을 단다(WD-722, 사용자 결정 ③).
 *
 * <p>테넌트 컬렉션 묶음(키워드 + 차원별 dense)마다 클라이언트를 하나 두고, 토큰이 만료되기 전에 새로 만든다. 클라이언트들은 gRPC 채널 하나를 함께 쓴다 —
 * 테넌트마다 채널을 열면 연결이 테넌트 수만큼 는다.
 *
 * <p><b>클라이언트를 만들 때 서버 버전 호환 확인을 끈다.</b> 그 확인은 제한 시간 없는 블로킹 호출이라 Qdrant 가 응답 없이 패킷을 버리면 요청 스레드가 끝없이
 * 멈춘다(WD-724 에서 마스터 클라이언트로 겪은 결함). 채널은 이 빈이 닫으므로 클라이언트를 닫을 때 채널은 닫지 않는다.
 *
 * <p>API 키가 없으면 토큰을 서명할 수 없다. 그때는 {@link #enabled()} 가 거짓이고 행 검색 기능 판정이 꺼진다 — 서버가 강제하는 격리 없이 마스터 키로
 * 대신 쓰지 않는다(2026-11-14 결정).
 */
@Slf4j
@Component
@ConditionalOnQdrantVectorStore
public class QdrantRowClients implements DisposableBean {

  /** 토큰 유효 기간. */
  static final Duration TOKEN_TTL = Duration.ofHours(1);

  /** 이 시간이 지난 클라이언트는 새 토큰으로 다시 만든다(만료 전 여유 10분). */
  static final Duration RENEW_AFTER = Duration.ofMinutes(50);

  private final QdrantProperties props;
  private final Clock clock;
  private final ManagedChannel channel;
  private final Map<String, Entry> clients = new ConcurrentHashMap<>();

  private record Entry(QdrantClient client, Instant issuedAt) {}

  @Autowired
  public QdrantRowClients(QdrantProperties props) {
    this(props, Clock.systemUTC());
  }

  QdrantRowClients(QdrantProperties props, Clock clock) {
    this.props = props;
    this.clock = clock;
    if (enabled(props)) {
      QdrantProperties.Endpoint ep = props.endpoint();
      ManagedChannelBuilder<?> b = ManagedChannelBuilder.forAddress(ep.host(), ep.port());
      if (ep.tls()) {
        b.useTransportSecurity();
      } else {
        b.usePlaintext();
      }
      this.channel = b.build();
    } else {
      this.channel = null;
      log.warn("QDRANT_API_KEY 가 없어 행 검색을 끕니다 — 행 색인 격리(테넌트 컬렉션 토큰)는 API 키로 서명해야 합니다");
    }
  }

  /** 토큰을 서명할 API 키가 있는가. 없으면 행 검색을 쓰지 않는다. */
  public boolean enabled() {
    return channel != null;
  }

  static boolean enabled(QdrantProperties props) {
    return props.apiKey() != null && !props.apiKey().isBlank();
  }

  /**
   * 이 컬렉션들만 읽고 쓸 수 있는 클라이언트.
   *
   * @throws IllegalStateException API 키가 없을 때(기능 판정이 먼저 막으므로 여기까지 오면 버그)
   */
  public QdrantClient forCollections(List<String> collections) {
    if (!enabled()) {
      throw new IllegalStateException("QDRANT_API_KEY 가 없어 행 검색 색인을 쓸 수 없습니다");
    }
    String key = String.join(",", new TreeSet<>(collections));
    Instant now = clock.instant();
    Entry e =
        clients.compute(
            key,
            (k, old) -> {
              if (old != null && old.issuedAt().plus(RENEW_AFTER).isAfter(now)) {
                return old;
              }
              if (old != null) {
                old.client().close(); // 채널은 공유라 닫히지 않는다(shutdownChannelOnClose=false)
              }
              return new Entry(newClient(collections, now), now);
            });
    return e.client();
  }

  private QdrantClient newClient(List<String> collections, Instant now) {
    String token = QdrantCollectionToken.issue(props.apiKey(), collections, now, TOKEN_TTL);
    return new QdrantClient(
        QdrantGrpcClient.newBuilder(channel, false, false)
            .withTimeout(props.timeout())
            .withApiKey(token)
            .build());
  }

  /** 클라이언트들과 공유 채널을 닫는다. */
  @Override
  public void destroy() throws InterruptedException {
    clients.values().forEach(e -> e.client().close());
    clients.clear();
    if (channel != null) {
      channel.shutdown();
      if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
        channel.shutdownNow();
      }
    }
  }
}
