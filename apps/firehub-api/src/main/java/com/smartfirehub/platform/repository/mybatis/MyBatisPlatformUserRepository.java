package com.smartfirehub.platform.repository.mybatis;

import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.global.util.LikePatternUtils;
import com.smartfirehub.platform.dto.PlatformAccountResponse;
import com.smartfirehub.platform.dto.PlatformUserResponse;
import com.smartfirehub.platform.repository.PlatformUserRepository;
import java.util.List;

/**
 * PlatformUserRepository 의 MyBatis 구현(WD-706) — {@code JooqPlatformUserRepository} 와 같은 의미.
 *
 * <p>jOOQ 구현처럼 {@code @Transactional} 을 달지 않는다(호출자 트랜잭션에 참여, 없으면 자동 커밋). user 는 전역 테이블이라 테넌트 컨텍스트가
 * 빈 운영자 토큰으로도 전 테넌트 사용자가 보인다. 정렬·상한·페이지 의미는 인터페이스 javadoc 그대로이고, 대소문자 무시 비교는 jOOQ 의 ILIKE·
 * equalIgnoreCase 대신 두 벤더 공통 {@code LOWER()} 로 쓴다.
 */
@PlatformMyBatisRepository
public class MyBatisPlatformUserRepository implements PlatformUserRepository {

  private final PlatformUserMapper mapper;

  public MyBatisPlatformUserRepository(PlatformUserMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public List<PlatformUserResponse> search(String q, int limit) {
    // LIKE 메타문자(%, _, \)를 이스케이프하지 않으면 "%" 한 글자가 전 사용자를 긁는다.
    return mapper.search(q, LikePatternUtils.containsPattern(q), limit, 0L);
  }

  @Override
  public PageResponse<PlatformAccountResponse> findAccounts(String q, int page, int size) {
    String pattern = q == null ? null : LikePatternUtils.containsPattern(q);
    long total = mapper.countAccounts(pattern);
    List<PlatformAccountResponse> content =
        mapper.selectAccounts(q, pattern, size, (long) page * size);
    int totalPages = (int) ((total + size - 1) / size);
    return new PageResponse<>(content, page, size, total, totalPages);
  }
}
