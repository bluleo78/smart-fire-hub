package com.smartfirehub.securitylevel.repository;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.smartfirehub.global.platformdb.PlatformLocks;
import com.smartfirehub.securitylevel.repository.mybatis.MyBatisSecurityLevelRepository;
import com.smartfirehub.securitylevel.repository.mybatis.SecurityLevelMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * MyBatis 구현의 등급 정의 변경 잠금이 PlatformLocks 에 jOOQ 구현과 같은 키를 넘기는지 보는 단위 시험(WD-711). DB 를 띄우지 않는다 —
 * Tibero 계약 시험은 잠금 창구를 가짜로 쓰므로 키는 여기서 고정한다.
 */
class MyBatisSecurityLevelRepositoryLockKeyTest {

  @Test
  @DisplayName("lockTenantLevelDefinitions 는 jOOQ 판과 같은 security_level_defs_t<테넌트> 키로 트랜잭션 잠금을 건다")
  void lockTenantLevelDefinitions_sameKeyAsJooq() {
    PlatformLocks locks = mock(PlatformLocks.class);
    MyBatisSecurityLevelRepository repository =
        new MyBatisSecurityLevelRepository(mock(SecurityLevelMapper.class), locks);

    repository.lockTenantLevelDefinitions(42L);

    verify(locks).xactLock("security_level_defs_t42");
  }
}
