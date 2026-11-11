package com.smartfirehub.user.repository;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.smartfirehub.global.platformdb.PlatformLocks;
import com.smartfirehub.user.repository.mybatis.MyBatisUserRepository;
import com.smartfirehub.user.repository.mybatis.UserMapper;
import org.junit.jupiter.api.Test;

/**
 * MyBatis 구현의 두 직렬화 잠금이 PlatformLocks 에 jOOQ 구현과 같은 키를 넘기는지 보는 단위 시험(WD-706). DB 를 띄우지 않는다 — 실제 잠금
 * 동작은 {@code PostgresPlatformLocksTest}·{@code TiberoPlatformLocksLiveTest} 가 고정한다.
 */
class MyBatisUserRepositoryLockKeyTest {

  @Test
  void locks_delegateSameKeysAsJooqImplementation() {
    PlatformLocks locks = mock(PlatformLocks.class);
    MyBatisUserRepository repository = new MyBatisUserRepository(mock(UserMapper.class), locks);

    repository.acquireFirstUserLock();
    repository.acquireMemberAdminGuardLock(42L);

    verify(locks).xactLock(1L);
    verify(locks).xactLock("member-admin-guard:42");
  }
}
