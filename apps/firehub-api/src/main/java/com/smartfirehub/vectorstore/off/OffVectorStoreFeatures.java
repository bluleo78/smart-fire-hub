package com.smartfirehub.vectorstore.off;

import com.smartfirehub.global.platformdb.ConditionalOnTiberoPlatformDb;
import com.smartfirehub.global.platformdb.PlatformDbFeature;
import com.smartfirehub.vectorstore.ConditionalOnPlatformVectorStore;
import com.smartfirehub.vectorstore.VectorStoreFeatures;
import org.springframework.stereotype.Component;

/**
 * platform+Tibero 조합의 {@link VectorStoreFeatures} 구현. 벡터 검색과 키워드(트라이그램) 검색을 끈다.
 *
 * <p>예전 {@code TiberoPlatformDbFeatures} 를 대신한다. 두 기능 모두 PostgreSQL 확장(pgvector·pg_trgm)에 기대므로
 * Tibero 플랫폼 DB 에는 대응하는 기능이 없다(ADR 0001 결정 3). 벡터 저장소를 qdrant 로 고르면 켤 수 있다(ADR 0002).
 *
 * <p>행 검색 색인은 플랫폼 DB 가 아니라 테넌트 데이터 저장소의 pgvector 에 있다. 그래서 플랫폼이 Tibero 여도 데이터 저장소가 PostgreSQL 이면 행
 * 검색은 켜진다.
 */
@Component
@ConditionalOnTiberoPlatformDb
@ConditionalOnPlatformVectorStore
public class OffVectorStoreFeatures implements VectorStoreFeatures {

  @Override
  public boolean supports(PlatformDbFeature feature) {
    return switch (feature) {
      case VECTOR_SEARCH, TRIGRAM_SEARCH -> false;
    };
  }
}
