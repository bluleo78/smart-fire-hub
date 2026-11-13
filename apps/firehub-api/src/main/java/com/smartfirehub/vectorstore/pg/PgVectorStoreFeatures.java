package com.smartfirehub.vectorstore.pg;

import com.smartfirehub.global.platformdb.ConditionalOnPostgresPlatformDb;
import com.smartfirehub.global.platformdb.PlatformDbFeature;
import com.smartfirehub.vectorstore.ConditionalOnPlatformVectorStore;
import com.smartfirehub.vectorstore.VectorStoreFeatures;
import org.springframework.stereotype.Component;

/**
 * platform+PostgreSQL 조합의 {@link VectorStoreFeatures} 구현. pgvector·pg_trgm 이 있으므로 벡터·키워드 검색을 모두
 * 지원한다.
 *
 * <p>예전 {@code PostgresPlatformDbFeatures} 를 대신한다. 운영 동작을 바꾸지 않도록 예전과 같은 답을 낸다.
 */
@Component
@ConditionalOnPostgresPlatformDb
@ConditionalOnPlatformVectorStore
public class PgVectorStoreFeatures implements VectorStoreFeatures {

  @Override
  public boolean supports(PlatformDbFeature feature) {
    return true;
  }
}
