package com.smartfirehub.global.platformdb.postgres;

import com.smartfirehub.global.platformdb.PlatformDbFeature;
import com.smartfirehub.global.platformdb.PlatformDbFeatures;
import org.springframework.stereotype.Component;

/**
 * {@link PlatformDbFeatures} 의 PostgreSQL 구현 — pgvector·pg_trgm 이 있으므로 모든 기능을 지원한다.
 *
 * <p>운영 동작을 바꾸지 않기 위해 항상 true 다.
 */
@Component
public class PostgresPlatformDbFeatures implements PlatformDbFeatures {

  @Override
  public boolean supports(PlatformDbFeature feature) {
    return true;
  }
}
