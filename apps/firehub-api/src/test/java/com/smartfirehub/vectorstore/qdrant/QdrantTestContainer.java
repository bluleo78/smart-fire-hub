package com.smartfirehub.vectorstore.qdrant;

import io.qdrant.client.QdrantClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.testcontainers.qdrant.QdrantContainer;

/**
 * Qdrant 테스트 하네스(WD-719). JVM 하나에 컨테이너 하나를 띄워 Qdrant 를 쓰는 테스트가 함께 쓴다.
 *
 * <p>운영과 같게 만든다: 버전 고정(v1.20.0), 클러스터 모드(노드 1대여도 {@code cluster.enabled}), API 키. 클러스터 모드로 시작해야 샤드
 * 수가 설정대로 잡힌다(단독 모드는 샤드 1로 고정 — PoC ③).
 */
public final class QdrantTestContainer {

  /** 운영 기준 버전(ADR 0002 결정 11). 올릴 때는 PoC 재현 스크립트·배포 문서와 함께 바꾼다. */
  public static final String IMAGE = "qdrant/qdrant:v1.20.0";

  static final String API_KEY = "test-key";

  private static QdrantContainer container;

  private QdrantTestContainer() {}

  /** 컨테이너를 (처음 한 번) 띄우고 돌려준다. JVM 이 끝날 때 Testcontainers 가 정리한다. */
  public static synchronized QdrantContainer get() {
    if (container == null) {
      QdrantContainer c =
          new QdrantContainer(IMAGE)
              .withApiKey(API_KEY)
              .withEnv("QDRANT__CLUSTER__ENABLED", "true")
              // 클러스터 모드는 자기 p2p 주소가 필요하다. 노드가 하나라 컨테이너 안 주소면 된다.
              .withCommand("./qdrant", "--uri", "http://localhost:6335");
      c.start();
      container = c;
    }
    return container;
  }

  /** 하네스 컨테이너에 붙는 설정. 샤드 수·양자화만 바꿔 「구성 변경」 시나리오를 만든다. */
  public static QdrantProperties properties(int shardNumber, boolean quantization) {
    QdrantContainer c = get();
    return new QdrantProperties(
        "http://" + c.getHost() + ":" + c.getGrpcPort(),
        API_KEY,
        shardNumber,
        quantization,
        Duration.ofSeconds(30));
  }

  /** 모든 컬렉션을 지운다. 테스트마다 빈 저장소에서 시작하게 한다. */
  public static void deleteAllCollections(QdrantClient client) throws Exception {
    List<String> names = client.listCollectionsAsync().get(30, TimeUnit.SECONDS);
    for (String name : names) {
      client.deleteCollectionAsync(name).get(30, TimeUnit.SECONDS);
    }
  }
}
