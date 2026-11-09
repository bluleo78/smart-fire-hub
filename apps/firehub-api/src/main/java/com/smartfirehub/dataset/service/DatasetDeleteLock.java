package com.smartfirehub.dataset.service;

import com.smartfirehub.dataset.repository.DatasetRepository;
import java.util.Collection;
import java.util.List;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 데이터셋 삭제 잠금 증표(WD-675) — 「이 트랜잭션이 이 데이터셋 행들을 삭제 잠금(FOR UPDATE)했다」는 사실을 값으로 들고 다닌다. 삭제 잠금 규칙은 이 클래스
 * 문서 한 곳에 둔다.
 *
 * <p><b>왜 증표인가.</b> 삭제 본문({@link DatasetService#deleteLockedDatasets})은 행 잠금을 이미 쥐었다고 가정하고 지운다.
 * 예전(WD-674)에는 그 선행 조건이 주석뿐이었다. 이제 증표는 행을 실제로 FOR UPDATE 하는 {@link #acquire} 로만 만들어지고(생성자는
 * private), 삭제 본문은 증표에 든 id 만 지운다.
 *
 * <p><b>트랜잭션에 묶임.</b> 행 잠금은 증표를 만든 트랜잭션이 끝나면(커밋·롤백) 풀린다. 끝난 뒤의 증표를 다른 트랜잭션에서 다시 쓰면 잠금 없이 지우게 되므로,
 * 증표는 만든 트랜잭션의 완료 콜백에서 무효가 되고 무효인 증표는 {@link #requireActive} 에서 {@link IllegalStateException} 이다.
 * 트랜잭션 밖에서는 만들 수 없다.
 *
 * <p><b>잠금 순서.</b> 항상 「데이터셋 행(이 증표) → 테넌트 PYTHON 읽기 권한 동기화 잠금(advisory) → 테이블」이다. 동기화 잠금은 기존 테이블을
 * 잠그는 DDL 앞에서만 필요하므로 테이블 없는 데이터셋(DOCUMENT/FILE)만 지우면 잡지 않는다 — 잡으면 같은 테넌트의 동기화가 끝날 때까지 쓸데없이 기다린다.
 *
 * <ul>
 *   <li>일괄 삭제({@link DatasetService#deleteLockedDatasets}): 지울 목록에 테이블 있는 데이터셋이 하나라도 있으면 행 잠금 직후·첫
 *       삭제 전에 한 번 잡는다. 없으면 잡지 않는다.
 *   <li>단건 삭제({@link DatasetService#deleteDataset}): 테이블 있는 데이터셋이면 테이블 DROP 직전에 잡는다(참조 거부 409 로 끝나면
 *       잡지 않는다).
 * </ul>
 */
public final class DatasetDeleteLock {

  /** 실제로 삭제 잠금한(잠글 때 아직 남아 있던) 데이터셋 id — 오름차순(잠근 순서). */
  private final List<Long> lockedIds;

  /** 증표를 만든 트랜잭션이 끝났는지 — 끝났으면 잠금이 풀렸으므로 더는 쓸 수 없다(트랜잭션은 한 스레드라 volatile 불필요). */
  private boolean completed;

  private DatasetDeleteLock(List<Long> lockedIds) {
    this.lockedIds = List.copyOf(lockedIds);
  }

  /**
   * 지울 데이터셋 행을 id 오름차순으로 삭제 잠금(FOR UPDATE)하고 그 증표를 돌려준다 — 증표를 만드는 유일한 길이다(단건·일괄 삭제 공용). 오름차순이라 두
   * 삭제가 서로 거꾸로 기다리지 않는다. 기다리는 동안 이미 지워진 행(잠금 문장이 빈 결과)은 증표에서 빠진다 — 호출자는 그 id 를 건너뛴다.
   *
   * @throws IllegalStateException 트랜잭션 밖에서 부를 때 — 잠금이 문장 끝에서 바로 풀려 아무것도 막지 못한다
   */
  public static DatasetDeleteLock acquire(
      DatasetRepository datasetRepository, Collection<Long> datasetIds) {
    DatasetDataWriteLock.requireTransaction();
    DatasetDeleteLock lock =
        new DatasetDeleteLock(
            datasetIds.stream().sorted().filter(datasetRepository::lockForDelete).toList());
    // 트랜잭션이 끝나면(커밋·롤백 모두) 행 잠금이 풀리므로 증표도 무효로 한다
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            lock.completed = true;
          }
        });
    return lock;
  }

  /** 실제로 잠근 데이터셋 id 들(오름차순). 기다리는 동안 이미 지워진 행은 빠져 있다. */
  public List<Long> lockedIds() {
    return lockedIds;
  }

  /** 만든 트랜잭션이 끝난 증표는 잠금이 풀렸으므로 거부한다 — 다른 트랜잭션에서 다시 쓰는 버그를 막는다. */
  void requireActive() {
    if (completed) {
      throw new IllegalStateException("삭제 잠금 증표를 만든 트랜잭션이 이미 끝났습니다 — 잠금이 풀린 증표는 쓸 수 없습니다");
    }
  }
}
