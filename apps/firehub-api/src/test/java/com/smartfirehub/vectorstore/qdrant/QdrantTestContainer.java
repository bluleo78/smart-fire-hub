package com.smartfirehub.vectorstore.qdrant;

import com.smartfirehub.support.PostgresTestContainer;
import com.smartfirehub.vectorstore.VectorStoreInstallKindCallback;
import io.qdrant.client.QdrantClient;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.testcontainers.qdrant.QdrantContainer;

/**
 * Qdrant 테스트 하네스(WD-719). JVM 하나에 컨테이너 하나를 띄워 Qdrant 를 쓰는 테스트가 함께 쓴다.
 *
 * <p>운영과 같게 만든다: 버전 고정(v1.20.0), 클러스터 모드(노드 1대여도 {@code cluster.enabled}), API 키, JWT RBAC(WD-722).
 * 클러스터 모드로 시작해야 샤드 수가 설정대로 잡힌다(단독 모드는 샤드 1로 고정 — PoC ③).
 */
public final class QdrantTestContainer {

  /** 운영 기준 버전(ADR 0002 결정 11). 올릴 때는 PoC 재현 스크립트·배포 문서와 함께 바꾼다. */
  public static final String IMAGE = "qdrant/qdrant:v1.20.0";

  static final String API_KEY = "test-key";

  /**
   * 시험 컨테이너의 저장소를 메모리(tmpfs)에 둔다(WD-758).
   *
   * <p>클러스터 모드는 컬렉션 생성·삭제마다 합의 로그를 디스크에 동기 쓰기(fsync)한다. 여러 세션의 시험이 겹치는 공유 개발 서버에서는 동기 쓰기가 건당 100ms
   * 를 넘어 컬렉션 하나 만드는 데 5~10초가 걸리고, 10초 합의 제한에 걸려 Qdrant 시험이 모두 실패했다. 시험 데이터는 컨테이너 수명 동안만 필요하고 멈췄다 다시
   * 띄워 이어 쓰는 시험도 없으므로 디스크에 둘 까닭이 없다. 운영 설정과는 무관하다.
   */
  public static final Map<String, String> STORAGE_TMPFS = Map.of("/qdrant/storage", "rw");

  /**
   * 시험에서 Qdrant 호출 한 번을 기다리는 상한(WD-803).
   *
   * <p>여러 세션이 겹치는 공유 개발 서버(load average 128)에서는 gRPC 응답이 30초를 넘겨 {@code
   * QdrantVectorStoreLifecycleTest} 의
   * restartWithSameConfigPassesVerification·missingPayloadIndexesAreFilledOnNextStartup 이 실패했다. 하네스
   * 클라이언트 호출의 대기 상한을 한 곳으로 모아 넉넉히(120초) 잡는다. 시험 전용이며 운영 기본값(QDRANT_TIMEOUT 10초)과는 무관하다. 호출마다 상한이
   * 있으므로 진짜 멈춤이 있어도 전체 실행이 끝없이 걸리지는 않는다.
   */
  public static final Duration CALL_TIMEOUT = Duration.ofSeconds(120);

  private static QdrantContainer container;

  private QdrantTestContainer() {}

  /** 컨테이너를 (처음 한 번) 띄우고 돌려준다. JVM 이 끝날 때 Testcontainers 가 정리한다. */
  public static synchronized QdrantContainer get() {
    if (container == null) {
      QdrantContainer c =
          new QdrantContainer(IMAGE)
              .withApiKey(API_KEY)
              .withTmpFs(STORAGE_TMPFS)
              .withEnv("QDRANT__CLUSTER__ENABLED", "true")
              // 행 검색 테넌트 컬렉션 토큰(WD-722) — 운영과 같게 켠다. 꺼져 있으면 기동 확인이 설정 오류로 멈춘다.
              .withEnv("QDRANT__SERVICE__JWT_RBAC", "true")
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
        CALL_TIMEOUT);
  }

  /** 모든 컬렉션을 지운다. 테스트마다 빈 저장소에서 시작하게 한다. */
  public static void deleteAllCollections(QdrantClient client) throws Exception {
    List<String> names =
        client.listCollectionsAsync().get(CALL_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    for (String name : names) {
      client.deleteCollectionAsync(name).get(CALL_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    }
  }

  /**
   * 공유 테스트 DB 의 설치 종류 기록을 소유자 계정으로 바꾼다. Flyway 가 아직 돌지 않은 새 DB(테이블 없음)면 아무것도 하지 않는다 — 그때는 콜백이 빈
   * 설치본으로 보고 이번 설정값(qdrant)을 기록한다.
   */
  public static void recordInstallType(String type) {
    try (Connection c = PostgresTestContainer.platformOwnerDataSource().getConnection()) {
      try (ResultSet rs =
          c.createStatement().executeQuery("SELECT to_regclass('public.system_settings')")) {
        if (!rs.next() || rs.getString(1) == null) {
          return;
        }
      }
      try (PreparedStatement ps =
          c.prepareStatement(
              "INSERT INTO system_settings (key, value, description) VALUES (?, ?, 'test')"
                  + " ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value")) {
        ps.setString(1, VectorStoreInstallKindCallback.KEY);
        ps.setString(2, type);
        ps.executeUpdate();
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }
}
