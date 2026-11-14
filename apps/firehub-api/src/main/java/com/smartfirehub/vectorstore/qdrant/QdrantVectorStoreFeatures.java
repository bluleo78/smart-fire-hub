package com.smartfirehub.vectorstore.qdrant;

import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.global.platformdb.PlatformDbFeature;
import com.smartfirehub.vectorstore.ConditionalOnQdrantVectorStore;
import com.smartfirehub.vectorstore.VectorStoreFeatures;
import org.springframework.stereotype.Component;

/**
 * qdrant 조합의 기능 판정(ADR 0002 결정 3, WD-720). 플랫폼 DB 가 PG 든 Tibero 든 데이터셋·문서 청크의 벡터·키워드 검색이 켜진다.
 *
 * <p><b>행 검색은 아직 끈다.</b> qdrant 설치본의 행 색인은 Qdrant 에 둔다(ADR 결정 9, WD-722). 그 구현 전에 기본 판정(데이터 저장소가 PG
 * 면 켜짐)을 그대로 쓰면, 행 색인이 데이터 저장소의 pgvector 에 쌓이기 시작하고 나중에 옮길 도구가 없다(새 설치만 지원 — 사용자 결정 4). 그래서 WD-722
 * 까지는 모든 데이터 저장소에서 꺼짐으로 답한다.
 *
 * <p>저장소가 지금 응답하는지(「저장소 일시 중단」)는 WD-724 에서 더한다.
 */
@Component
@ConditionalOnQdrantVectorStore
public class QdrantVectorStoreFeatures implements VectorStoreFeatures {

  @Override
  public boolean supports(PlatformDbFeature feature) {
    return switch (feature) {
      case VECTOR_SEARCH, TRIGRAM_SEARCH -> true;
    };
  }

  /** 행 검색은 WD-722(Qdrant 행 색인) 전까지 꺼짐. */
  @Override
  public boolean supportsRowSearch(DataStoreBackend backend) {
    return false;
  }
}
