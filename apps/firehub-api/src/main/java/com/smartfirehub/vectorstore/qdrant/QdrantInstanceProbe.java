package com.smartfirehub.vectorstore.qdrant;

import com.smartfirehub.vectorstore.ConditionalOnQdrantVectorStore;
import com.smartfirehub.vectorstore.tenant.DedicatedVectorStoreProbe;
import com.smartfirehub.vectorstore.tenant.VectorStoreConnectionTestResult;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.QdrantOuterClass.HealthCheckReply;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 테넌트 전용 Qdrant 연결 확인(WD-742). 순서대로 ① 상태 점검(버전) ② API 키로 컬렉션 목록(키가 맞는지 — 상태 점검은 키 없이도 답할 수 있다) ③
 * 컬렉션 토큰(JWT RBAC) 확인을 한다. ③ 은 공유 Qdrant 기동 확인과 같은 판정이다({@link
 * QdrantVectorStoreLifecycle#verifyCollectionTokens}) — 전용 인스턴스도 행 검색 클라이언트는 테넌트 컬렉션만 허용한 토큰으로
 * 붙는다(공유와 같은 코드 경로).
 */
@Slf4j
@Component
@ConditionalOnQdrantVectorStore
public class QdrantInstanceProbe implements DedicatedVectorStoreProbe {

  private final QdrantProperties installation;

  public QdrantInstanceProbe(QdrantProperties installation) {
    this.installation = installation;
  }

  @Override
  public VectorStoreConnectionTestResult probe(
      String host, int grpcPort, boolean tls, String apiKey) {
    QdrantProperties target =
        QdrantRowTargets.dedicatedProperties(installation, host, grpcPort, tls, apiKey);
    String version = null;
    try (QdrantClient c = QdrantClientConfig.create(target)) {
      try {
        HealthCheckReply reply =
            QdrantSupport.await(
                c.healthCheckAsync(target.timeout()), target.timeout(), "Qdrant 상태 점검");
        version = reply.getVersion();
      } catch (QdrantAccessException e) {
        return new VectorStoreConnectionTestResult(
            false, null, "Qdrant 에 접속하지 못했습니다: " + e.getMessage());
      }
      try {
        QdrantSupport.await(c.listCollectionsAsync(), target.timeout(), "Qdrant 컬렉션 목록");
      } catch (QdrantAccessException e) {
        String why =
            QdrantVectorStoreLifecycle.isTransient(e) ? "Qdrant 가 응답하지 않습니다: " : "API 키가 맞지 않습니다: ";
        return new VectorStoreConnectionTestResult(false, version, why + e.getMessage());
      }
    }
    try {
      QdrantVectorStoreLifecycle.verifyCollectionTokens(
          target.endpoint(), apiKey, target.timeout());
    } catch (IllegalStateException e) {
      return new VectorStoreConnectionTestResult(false, version, e.getMessage());
    } catch (QdrantAccessException e) {
      return new VectorStoreConnectionTestResult(
          false, version, "컬렉션 토큰 확인 중 응답이 없습니다: " + e.getMessage());
    }
    return new VectorStoreConnectionTestResult(
        true, version, "연결됨 — API 키와 컬렉션 토큰(JWT RBAC)이 맞습니다");
  }
}
