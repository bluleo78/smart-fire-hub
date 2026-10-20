package com.smartfirehub.dataset.storage.tibero;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 테넌트 Tibero 테이블별 물리 컬럼 타입({@code ALL_TAB_COLUMNS}) 캐시(WD-74).
 *
 * <p>왜 필요한가: 행 저장소는 검색할 때마다 카탈로그를 한 번 읽어 컬럼별 문자열 식을 정한다(검색·건수 호출마다 질의 하나 추가). 같은 테이블을 연달아 검색하면 결과가
 * 같으므로 짧게(30초) 캐시한다.
 *
 * <p>왜 별도 컴포넌트인가: 컬럼 구조를 바꾸는 쪽은 DDL 저장소({@link TiberoDataTableStore})다. 캐시를 행 저장소 안에 두면 DDL 저장소가 행
 * 저장소에 의존해야 하므로, 두 저장소가 함께 쓰는 작은 컴포넌트로 뺐다. DDL 저장소는 컬럼 구조를 바꾼 뒤 {@link #invalidate} 를 부른다.
 *
 * <p>무효화가 닿지 않는 경우(다른 API 인스턴스의 DDL, 무효화와 겹친 적재 경합)에는 최대 30초 동안 낡은 타입으로 검색 식을 만든다 — 결과는 검색 일치 여부가
 * 잠시 어긋나는 정도이고 오류·데이터 손상은 없다. 그래서 만료 시간을 짧게 둔다.
 */
@Component
public class TiberoColumnTypeCache {

  /** 캐시 키: 테넌트(= 스키마) + 소문자 논리 테이블 이름. */
  private record Key(long tenantId, String table) {}

  private final Cache<Key, Map<String, TiberoColumnType>> cache =
      Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(30)).maximumSize(1_000).build();

  /**
   * 캐시된 컬럼 타입을 돌려주고, 없으면 주어진 커넥션에서 카탈로그를 읽어 채운다. 읽기는 호출자의 커넥션(묶인 DML 커넥션)에서 한다 — 별도 커넥션을 열지 않는다.
   */
  Map<String, TiberoColumnType> get(Connection conn, long tenantId, String schema, String table)
      throws SQLException {
    Key key = key(tenantId, table);
    Map<String, TiberoColumnType> cached = cache.getIfPresent(key);
    if (cached == null) {
      // get(key, loader) 대신 직접 채운다 — 로더 안에서는 SQLException 을 던질 수 없다.
      cached = Collections.unmodifiableMap(TiberoCatalog.columnTypes(conn, schema, table));
      cache.put(key, cached);
    }
    return cached;
  }

  /** 테이블의 컬럼 구조가 바뀌었으니 다음 검색이 카탈로그를 다시 읽게 한다. */
  public void invalidate(long tenantId, String table) {
    cache.invalidate(key(tenantId, table));
  }

  private static Key key(long tenantId, String table) {
    return new Key(tenantId, table.toLowerCase(Locale.ROOT));
  }
}
