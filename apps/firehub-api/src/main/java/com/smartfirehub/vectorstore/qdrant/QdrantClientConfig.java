package com.smartfirehub.vectorstore.qdrant;

import com.smartfirehub.vectorstore.ConditionalOnQdrantVectorStore;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Qdrant 공식 gRPC 클라이언트를 빈으로 둔다(ADR 0002 결정 11). 벡터 저장소가 qdrant 일 때만 뜬다. */
@Configuration
@ConditionalOnQdrantVectorStore
@EnableConfigurationProperties(QdrantProperties.class)
public class QdrantClientConfig {

  /** 클라이언트는 gRPC 채널 하나를 쥐고 스레드 안전하다. 앱 종료 때 채널을 닫는다. */
  @Bean(destroyMethod = "close")
  public QdrantClient qdrantClient(QdrantProperties props) {
    return create(props);
  }

  /**
   * 설정으로 클라이언트를 만든다. 테스트 하네스도 같은 경로로 만들어 접속 규칙(TLS·키·제한 시간)을 함께 검증한다.
   *
   * <p>클라이언트의 서버 버전 호환 확인은 끈다(WD-724). 그 확인은 만들 때 제한 시간 없는 블로킹 호출이라, Qdrant 가 응답 없이 패킷을 버리면 빈 생성에서
   * api 기동이 끝없이 멈춘다 — 「내려가 있어도 기동하고 일시 중단으로 안내」가 그 앞에서 막힌다. 버전은 배포에서 고정하고(ADR 0002 결정 11), 컬렉션 구성은
   * {@link QdrantVectorStoreLifecycle} 이 대조한다.
   */
  public static QdrantClient create(QdrantProperties props) {
    QdrantProperties.Endpoint ep = props.endpoint();
    QdrantGrpcClient.Builder builder =
        QdrantGrpcClient.newBuilder(ep.host(), ep.port(), ep.tls(), false)
            .withTimeout(props.timeout());
    if (props.apiKey() != null && !props.apiKey().isBlank()) {
      builder.withApiKey(props.apiKey());
    }
    return new QdrantClient(builder.build());
  }
}
