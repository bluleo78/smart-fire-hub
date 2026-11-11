package com.smartfirehub.tenant.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.tenant.repository.TenantRepository;
import java.util.List;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * TenantRepository 의 MyBatis 구현(WD-706) — {@code JooqTenantRepository} 와 같은 의미.
 *
 * <p>전파 속성은 jOOQ 구현의 메서드 애너테이션 그대로다. 조회 둘은 읽기 전용 트랜잭션(테넌트 없이 불려도 전역 테이블이라 결과가 같다), 시드 호출은 MANDATORY
 * — 호출자(TenantProvisioningService)의 트랜잭션에 합류해야 하고, 밖에서 부르면 스스로 열지 않고 예외로 거부한다(시드가 부분 커밋되는 것을 막는다).
 */
@PlatformMyBatisRepository
public class MyBatisTenantRepository implements TenantRepository {

  private final TenantMapper mapper;

  public MyBatisTenantRepository(TenantMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  @Transactional(readOnly = true)
  public List<Long> findActiveTenantIds() {
    return mapper.selectActiveIds();
  }

  @Override
  @Transactional(readOnly = true)
  public boolean isActive(long tenantId) {
    return mapper.countActive(tenantId) > 0;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void provisionDefaults(long tenantId) {
    mapper.provisionDefaults(tenantId);
  }
}
