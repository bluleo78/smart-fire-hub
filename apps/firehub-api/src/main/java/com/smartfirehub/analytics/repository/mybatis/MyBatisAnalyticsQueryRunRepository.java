package com.smartfirehub.analytics.repository.mybatis;

import com.smartfirehub.analytics.repository.AnalyticsQueryRunRepository;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * AnalyticsQueryRunRepository 의 MyBatis 구현(WD-710) — {@code JooqAnalyticsQueryRunRepository} 와 같은
 * 의미.
 *
 * <p>트랜잭션 속성은 jOOQ 구현 그대로 클래스 단위 plain {@code @Transactional}, {@code findOwned} 만 readOnly 다 —
 * 컨트롤러·주기 정리가 트랜잭션 없이 부르므로 이 경계가 있어야 테넌트 컨텍스트가 심겨 정책·tenant_id DEFAULT 가 동작한다.
 *
 * <p>id 는 자바 {@link UUID#randomUUID()} 로 만들어 넣는다 — Tibero 에는 RETURNING 이 없고 문자열 키라 JDBC 생성 키로도 받을 수
 * 없어서다. PG(gen_random_uuid DEFAULT)와 같은 무작위 v4 UUID 다.
 */
@PlatformMyBatisRepository
@Transactional
public class MyBatisAnalyticsQueryRunRepository implements AnalyticsQueryRunRepository {

  private final AnalyticsQueryRunMapper mapper;

  public MyBatisAnalyticsQueryRunRepository(AnalyticsQueryRunMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public UUID insert(long userId, String sqlText, int maxRows) {
    UUID id = UUID.randomUUID();
    mapper.insert(id.toString(), userId, sqlText, maxRows);
    return id;
  }

  @Override
  public int deleteExpired() {
    return mapper.deleteExpired();
  }

  @Transactional(readOnly = true)
  @Override
  public Optional<Run> findOwned(UUID id, long userId) {
    // WHERE 가 id 일치라 돌려줄 id 는 인자와 같다 — uuid·문자열 열 타입 차이를 결과 매핑에 들이지 않으려고 다시 읽지 않는다.
    return Optional.ofNullable(mapper.selectOwned(id.toString(), userId))
        .map(row -> new Run(id, row.sqlText(), row.maxRows()));
  }
}
