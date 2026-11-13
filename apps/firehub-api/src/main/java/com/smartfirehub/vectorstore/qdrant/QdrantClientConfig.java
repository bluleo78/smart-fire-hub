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

  /** 설정으로 클라이언트를 만든다. 테스트 하네스도 같은 경로로 만들어 접속 규칙(TLS·키·제한 시간)을 함께 검증한다. */
  public static QdrantClient create(QdrantProperties props) {
    QdrantProperties.Endpoint ep = props.endpoint();
    QdrantGrpcClient.Builder builder =
        QdrantGrpcClient.newBuilder(ep.host(), ep.port(), ep.tls()).withTimeout(props.timeout());
    if (props.apiKey() != null && !props.apiKey().isBlank()) {
      builder.withApiKey(props.apiKey());
    }
    return new QdrantClient(builder.build());
  }
}
