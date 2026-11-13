package com.smartfirehub.vectorstore.qdrant;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Qdrant 접속·컬렉션 구성 설정(ADR 0002 결정 7·11). 벡터 저장소가 {@code qdrant} 일 때만 읽는다.
 *
 * @param url gRPC 주소. {@code https://} 면 TLS 로 붙는다(서버 {@code service.enable_tls}). 포트를 빼면 6334(gRPC
 *     기본).
 * @param apiKey 읽기·쓰기 API 키. v1.20 은 노드 간 호출도 이 키를 쓰므로 읽기 전용 키를 주면 쓰기가 403 이 된다.
 * @param shardNumber 새 컬렉션의 샤드 수. 오픈소스판은 리샤딩이 없어 노드를 늘릴 때를 대비해 넉넉히 잡는다(기본 6).
 * @param quantization 원본 dense 를 디스크에 두고 int8 양자화본을 메모리에 고정할지(기본 켬, 결정 7 「메모리」).
 * @param timeout 호출 하나의 제한 시간.
 */
@ConfigurationProperties(prefix = "firehub.vector-store.qdrant")
public record QdrantProperties(
    @DefaultValue("http://localhost:6334") String url,
    String apiKey,
    @DefaultValue("6") int shardNumber,
    @DefaultValue("true") boolean quantization,
    @DefaultValue("10s") Duration timeout) {

  /** gRPC 기본 포트. REST(6333)와 다르다. */
  static final int DEFAULT_GRPC_PORT = 6334;

  /** 설정값을 검사한다. 잘못된 값으로 컬렉션을 만들면 나중에 바꿀 수 없어서(샤드 수 고정) 기동 때 막는다. */
  public QdrantProperties {
    if (shardNumber < 1) {
      throw new IllegalStateException(
          "firehub.vector-store.qdrant.shard-number 는 1 이상이어야 합니다: " + shardNumber);
    }
    endpoint(url);
  }

  /** {@link #url} 에서 뽑은 접속 대상. */
  public Endpoint endpoint() {
    return endpoint(url);
  }

  private static Endpoint endpoint(String url) {
    URI uri;
    try {
      uri = URI.create(url == null ? "" : url.trim());
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("QDRANT_URL 형식이 잘못됐습니다: " + url, e);
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!scheme.equals("http") && !scheme.equals("https") || uri.getHost() == null) {
      throw new IllegalStateException("QDRANT_URL 은 http(s)://호스트[:포트] 형식이어야 합니다: " + url);
    }
    int port = uri.getPort() == -1 ? DEFAULT_GRPC_PORT : uri.getPort();
    return new Endpoint(uri.getHost(), port, scheme.equals("https"));
  }

  /** gRPC 접속 대상(호스트·포트·TLS). */
  public record Endpoint(String host, int port, boolean tls) {}
}
