package com.smartfirehub.vectorstore;

import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.global.platformdb.PlatformDbFeature;

/**
 * 현재 설치본이 벡터·키워드·행 검색을 지원하는지 알려 주는 포트(ADR 0002 결정 3). 이 판정을 한 곳에 모은다.
 *
 * <p>예전에는 플랫폼 DB 가 판정했다({@code PlatformDbFeatures}). 이제 벡터 저장소를 플랫폼 DB 밖(Qdrant)에 둘 수 있어서 "플랫폼 DB 가
 * Tibero 면 꺼짐" 이 더는 맞지 않는다. 그래서 벡터 저장소 조합이 답하도록 옮겼다. 기능이 꺼져 있으면 서비스는 그 단계만 건너뛴다(검색은 빈 목록, 적재는 벡터
 * 쓰기만 생략). 5xx 로 터뜨리지 않는다.
 *
 * <p>벡터 테이블을 읽는 상태 경로는 서비스 계층이 가드한다(WD-485). EmbeddingStatusService 는 분자(countEmbedded)만 0 으로 둔다.
 * EmbeddingBacklogService 는 countMissing 을 가드해 영향도 0, hasWork false 를 낸다. findMissing 은
 * TenantReembedJob 진입점 가드 뒤에서만 불린다. 기반 테이블만 읽는 경로(진행률 분모 countAll·countAllChunks,
 * DocumentChunkController 의 findChunkContentsByDataset)는 가드하지 않는다.
 */
public interface VectorStoreFeatures {

  /**
   * @param feature 확인할 기능(벡터 검색 또는 키워드 검색)
   * @return 현재 벡터 저장소 조합이 그 기능을 지원하면 true
   */
  boolean supports(PlatformDbFeature feature);

  /**
   * 테넌트 데이터 저장소에 행 검색을 둘 수 있는지 판정한다(WD-80). 관문(422), 기능 응답, 배경 스윕이 모두 이 판정을 쓴다.
   *
   * <p>platform 조합에서는 행 검색 색인이 데이터 저장소의 pgvector 에 있다. 그래서 플랫폼 DB 와 상관없이 데이터 저장소가 PostgreSQL 일 때만
   * true 다. 기본 구현이 이 platform 규칙이다. Qdrant 조합은 데이터 저장소 종류와 상관없이 켤 수 있어 재정의한다(ADR 0002 결정 9, 단계
   * 7·8).
   *
   * @param backend 테넌트의 데이터 저장소 백엔드
   */
  default boolean supportsRowSearch(DataStoreBackend backend) {
    return backend.supportsRowSearch();
  }
}
