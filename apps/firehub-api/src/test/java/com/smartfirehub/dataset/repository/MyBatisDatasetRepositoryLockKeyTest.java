package com.smartfirehub.dataset.repository;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.smartfirehub.dataset.repository.mybatis.DatasetFavoriteMapper;
import com.smartfirehub.dataset.repository.mybatis.DatasetMapper;
import com.smartfirehub.dataset.repository.mybatis.MyBatisDatasetRepository;
import com.smartfirehub.global.platformdb.PlatformLocks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * MyBatis 구현의 이름·테이블명 직렬화 잠금이 PlatformLocks 에 jOOQ 구현과 같은 키를 넘기는지 보는 단위 시험(WD-707). DB 를 띄우지 않는다 —
 * Tibero 계약 시험은 잠금 창구를 가짜로 쓰므로 키는 여기서 고정한다.
 */
class MyBatisDatasetRepositoryLockKeyTest {

  @Test
  @DisplayName("acquireNameLock·acquireTableNameLock 은 jOOQ 판과 같은 접두어·테넌트·원문 이름 키로 잠근다")
  void locks_delegateSameKeysAsJooqImplementation() {
    PlatformLocks locks = mock(PlatformLocks.class);
    MyBatisDatasetRepository repository =
        new MyBatisDatasetRepository(
            mock(DatasetMapper.class), mock(DatasetFavoriteMapper.class), locks);

    repository.acquireNameLock(7L, "화재 Data");
    repository.acquireTableNameLock(7L, "t_fire");

    verify(locks).xactLock("dataset-name:7:화재 Data");
    verify(locks).xactLock("dataset-table:7:t_fire");
  }
}
