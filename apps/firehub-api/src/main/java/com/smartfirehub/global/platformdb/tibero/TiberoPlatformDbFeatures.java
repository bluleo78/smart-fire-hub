package com.smartfirehub.global.platformdb.tibero;

import com.smartfirehub.global.platformdb.ConditionalOnTiberoPlatformDb;
import com.smartfirehub.global.platformdb.PlatformDbFeature;
import com.smartfirehub.global.platformdb.PlatformDbFeatures;
import org.springframework.stereotype.Component;

/**
 * {@link PlatformDbFeatures} 의 Tibero 구현(WD-496) — 벡터·키워드(트라이그램) 검색을 끈다.
 *
 * <p>둘 다 PostgreSQL 확장(pgvector·pg_trgm)에 기대는 기능이라 Tibero 에는 대응물이 없다. 새 벡터 DB 를 들이기 전까지 Tibero
 * 설치본에서는 비활성이다(WD-70 결정 ②, ADR 0001 결정 3). 서비스는 이 답을 보고 해당 단계를 건너뛴다(5xx 로 터뜨리지 않는다).
 */
@Component
@ConditionalOnTiberoPlatformDb
public class TiberoPlatformDbFeatures implements PlatformDbFeatures {

  @Override
  public boolean supports(PlatformDbFeature feature) {
    return switch (feature) {
      case VECTOR_SEARCH, TRIGRAM_SEARCH -> false;
    };
  }
}
