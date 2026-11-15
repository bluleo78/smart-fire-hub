package com.smartfirehub.vectorstore.qdrant;

import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.global.platformdb.PlatformDbFeature;
import com.smartfirehub.vectorstore.ConditionalOnQdrantVectorStore;
import com.smartfirehub.vectorstore.VectorStoreFeatures;
import org.springframework.stereotype.Component;

/**
 * qdrant 조합의 기능 판정(ADR 0002 결정 3, WD-720). 플랫폼 DB 가 PG 든 Tibero 든 데이터셋·문서 청크의 벡터·키워드 검색이 켜진다.
 *
 * <p><b>행 검색</b>은 Qdrant 테넌트 컬렉션에 둔다({@link QdrantRowSearchIndex}, WD-722). 원본 읽기·필터 평가는 데이터 저장소별
 * 구현이 있어(PostgreSQL·Tibero, WD-723) 두 저장소 테넌트 모두 켠다. API 키가 없으면 테넌트 컬렉션 토큰을 서명할 수 없어 끈다 — 서버가 강제하는
 * 격리 없이 마스터 키로 대신하지 않는다.
 *
 * <p>저장소가 지금 응답하는지(「저장소 일시 중단」, WD-724)는 {@link QdrantVectorStoreLifecycle#available()} 에 묻는다 — 상태
 * 점검과 컬렉션 준비가 둘 다 끝나야 참이고, 결과는 몇 초 캐시된다.
 */
@Component
@ConditionalOnQdrantVectorStore
public class QdrantVectorStoreFeatures implements VectorStoreFeatures {

  private final QdrantVectorStoreLifecycle lifecycle;
  private final QdrantRowClients rowClients;

  public QdrantVectorStoreFeatures(
      QdrantVectorStoreLifecycle lifecycle, QdrantRowClients rowClients) {
    this.lifecycle = lifecycle;
    this.rowClients = rowClients;
  }

  @Override
  public boolean supports(PlatformDbFeature feature) {
    return switch (feature) {
      case VECTOR_SEARCH, TRIGRAM_SEARCH -> true;
    };
  }

  /**
   * API 키가 있으면 켜짐(클래스 설명). 데이터 저장소는 PostgreSQL·Tibero 둘 다 — 색인은 Qdrant 에 있고 원본 읽기·필터 평가는 {@code
   * SearchSourceReader} 가 저장소별로 한다(WD-723). 외부 저장소처럼 원본 읽기 구현이 없는 백엔드는 막는다.
   */
  @Override
  public boolean supportsRowSearch(DataStoreBackend backend) {
    return (backend == DataStoreBackend.POSTGRESQL || backend == DataStoreBackend.TIBERO)
        && rowClients.enabled();
  }

  /** 저장소가 지금 응답하고 컬렉션 준비가 끝났는가(캐시). */
  @Override
  public boolean available() {
    return lifecycle.available();
  }
}
