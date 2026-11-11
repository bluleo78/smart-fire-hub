package com.smartfirehub.auth.repository;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.smartfirehub.auth.repository.mybatis.LoginIpFailureMapper;
import com.smartfirehub.auth.repository.mybatis.MyBatisLoginIpFailureRepository;
import com.smartfirehub.global.platformdb.PlatformLocks;
import org.junit.jupiter.api.Test;

/**
 * MyBatis 구현의 {@code lockKey} 가 PlatformLocks 에 jOOQ 구현과 같은 이름공간 키를 넘기는지 보는 단위 시험(WD-706). DB 를 띄우지
 * 않는다 — 실제 잠금 동작은 {@code PostgresPlatformLocksTest}·{@code TiberoPlatformLocksLiveTest} 가
 * 고정한다(Tibero 계약 시험 하네스의 런타임 계정에는 DBMS_LOCK 권한이 없어 계약 시험이 가짜 잠금을 쓴다).
 */
class MyBatisLoginIpFailureLockKeyTest {

  @Test
  void lockKey_delegatesNamespacedKeyToPlatformLocks() {
    PlatformLocks locks = mock(PlatformLocks.class);
    MyBatisLoginIpFailureRepository repository =
        new MyBatisLoginIpFailureRepository(mock(LoginIpFailureMapper.class), locks);

    repository.lockKey("203.0.113.7");

    verify(locks).xactLock("login-ip-limit:203.0.113.7");
  }
}
