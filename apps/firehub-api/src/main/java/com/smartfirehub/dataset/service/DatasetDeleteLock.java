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
 * <p><b>트랜잭션에 묶임.</b> 행 잠금은 증표를 만든 트랜잭션만 쥔다. 그 밖의 곳에서 증표를 쓰면 {@link #requireActive} 가 {@link
 * IllegalStateException} 으로 거부한다. 트랜잭션 밖에서는 만들 수 없다.
 *
 * <ul>
 *   <li>만든 트랜잭션이 끝난 뒤(커밋·롤백 — 잠금이 풀림): 다른 트랜잭션에서 다시 쓰면 잠금 없이 지우게 된다.
 *   <li>만든 트랜잭션이 살아 있는데 다른 트랜잭션 안(WD-680): REQUIRES_NEW 안쪽 트랜잭션은 다른 연결이라 바깥의 FOR UPDATE 를 쥐지 않는다 —
 *       같은 행을 지우려다 바깥이 끝날 때까지(곧 잠금 시간 초과까지) 기다린다. NOT_SUPPORTED 로 트랜잭션 밖에 나간 경우와 다른 스레드도 같다.
 * </ul>
 *
 * <p><b>왜 동기화 콜백으로 판별하나(WD-680).</b> 증표는 만들 때 현재 트랜잭션에 {@link TransactionSynchronization} 을 하나 등록하고
 * 그 콜백으로 자기 트랜잭션의 상태를 따라간다. Spring 트랜잭션 관리자(AbstractPlatformTransactionManager)는 REQUIRES_NEW·
 * NOT_SUPPORTED 로 바깥 트랜잭션을 일시 중단할 때 등록된 동기화마다 {@code suspend()} 를 부르고 목록을 비우며, 바깥이 재개될 때 {@code
 * resume()}, 끝날 때 {@code afterCompletion()} 을 부른다. 그래서 「중단됨·끝남」 표시만으로 「지금 실행 중인 트랜잭션이 증표를 만든 그
 * 트랜잭션인가」를 정확히 안다 — 어떤 관리자·DataSource 든 같은 규칙으로 동작하고, 같은 연결을 쓰는 REQUIRED 합류·NESTED(세이브포인트)는 그대로
 * 허용된다. (직접 묶은 자원 키는 일시 중단 때 풀리지 않아 안쪽 트랜잭션에서도 보이므로 쓸 수 없고, 연결 자원으로 판별하려면 DataSource 를 알아야 해 관리자가
 * 여럿인 설치본에서 깨지기 쉽다.) 동기화는 스레드 로컬이라 다른 스레드에서는 콜백이 오지 않으므로 만든 스레드도 함께 확인한다.
 *
 * <p><b>제약(WD-680).</b> 증표는 주(플랫폼) 트랜잭션에서 만들고 같은 트랜잭션에서만 쓴다. 동기화 콜백은 트랜잭션 관리자를 가리지 않으므로 다음은 잠금이 살아
 * 있어도 거부된다.
 *
 * <ul>
 *   <li>다른 트랜잭션 관리자(테넌트 파이프라인·별도 데이터 저장소 등)의 트랜잭션 안에서 만든 증표 — 그 안쪽 트랜잭션이 끝날 때 완료 콜백이 와 {@code
 *       completed} 가 켜진다.
 *   <li>증표 트랜잭션 안에서 다른 관리자가 새 트랜잭션을 연 동안의 사용 — Spring 이 새 트랜잭션을 열며 동기화를 일시 중단하므로 {@code suspended}
 *       가 켜진다. 그래서 {@link DatasetService} 는 삭제 본문 맨 앞에서 한 번만 확인한다.
 *   <li>참고(기존 동작): NESTED 세이브포인트로 롤백해도 트랜잭션은 이어지므로 증표는 유효하게 보인다. 다만 세이브포인트 뒤에 잡은 행 잠금은 그 롤백에서
 *       풀리므로(PG) NESTED 안에서 만든 증표를 롤백 뒤에 쓰지 않는다.
 * </ul>
 *
 * <p><b>잠금 순서.</b> 항상 「데이터셋 행(이 증표) → 테넌트 PYTHON 읽기 권한 동기화 잠금(advisory) → 테이블」이다. 동기화 잠금은 기존 테이블을
 * 잠그는 DDL 앞에서만 필요하므로 테이블 없는 데이터셋(DOCUMENT/FILE)만 지우면 잡지 않는다 — 잡으면 같은 테넌트의 동기화가 끝날 때까지 쓸데없이 기다린다.
 *
 * <p>WD-670: 테이블 있는 데이터셋은 행 잠금과 동기화 잠금 사이에 구조 잠금({@link DatasetDataWriteLock#lockStructure} — 데이터
 * 저장소에 직접 쓰는 파이프라인과 직렬화, 시간 넘으면 409)을 테이블마다 한 번 잡는다 — 순서는 「데이터셋 행 → 구조 → 동기화 → 테이블」. 일괄 삭제는 동기화 잠금
 * 직전에 모두(id 오름차순), 단건 삭제는 동기화 잠금 직전에 잡는다.
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

  /** 증표를 만든 스레드 — Spring 트랜잭션은 스레드에 묶이므로 다른 스레드에서 쓰면 다른 트랜잭션이다(WD-680). */
  private final Thread owner = Thread.currentThread();

  /**
   * 증표를 만든 트랜잭션이 끝났는지 — 끝났으면 잠금이 풀렸으므로 더는 쓸 수 없다. 아래 두 상태는 만든 스레드에서만 바뀌고 읽힌다(다른 스레드는 {@link #owner}
   * 확인에서 먼저 거부) — volatile 불필요.
   */
  private boolean completed;

  /**
   * 증표를 만든 트랜잭션이 REQUIRES_NEW·NOT_SUPPORTED 로 일시 중단됐는지 — 중단 중에 도는 코드는 다른 트랜잭션(또는 트랜잭션 밖)이다(WD-680).
   */
  private boolean suspended;

  private DatasetDeleteLock(List<Long> lockedIds) {
    this.lockedIds = List.copyOf(lockedIds);
  }

  /**
   * 지울 데이터셋 행을 id 오름차순으로 삭제 잠금(FOR UPDATE)하고 그 증표를 돌려준다 — 증표를 만드는 유일한 길이다(단건·일괄 삭제 공용). 오름차순이라 두
   * 삭제가 서로 거꾸로 기다리지 않는다. 같은 id 는 한 번만 잠근다(WD-680 — 증표에 두 번 들면 삭제 본문이 같은 데이터셋을 두 번 지우려다 실패한다). 기다리는
   * 동안 이미 지워진 행(잠금 문장이 빈 결과)은 증표에서 빠진다 — 호출자는 그 id 를 건너뛴다.
   *
   * @throws IllegalStateException 트랜잭션 밖에서 부를 때 — 잠금이 문장 끝에서 바로 풀려 아무것도 막지 못한다
   */
  public static DatasetDeleteLock acquire(
      DatasetRepository datasetRepository, Collection<Long> datasetIds) {
    DatasetDataWriteLock.requireTransaction("데이터셋 삭제 잠금은 트랜잭션 안에서만 잡을 수 있습니다");
    DatasetDeleteLock lock =
        new DatasetDeleteLock(
            datasetIds.stream()
                .sorted()
                .distinct()
                .filter(datasetRepository::lockForDelete)
                .toList());
    // 만든 트랜잭션의 상태를 콜백으로 따라간다 — 판별 원리는 클래스 문서 「왜 동기화 콜백으로 판별하나」
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          // REQUIRES_NEW·NOT_SUPPORTED 로 바깥(만든 트랜잭션)이 중단됨 — 그동안 도는 코드는 다른 트랜잭션이다
          @Override
          public void suspend() {
            lock.suspended = true;
          }

          // 안쪽이 끝나 만든 트랜잭션으로 돌아옴 — 다시 쓸 수 있다
          @Override
          public void resume() {
            lock.suspended = false;
          }

          // 커밋·롤백 모두 행 잠금이 풀리므로 증표도 무효로 한다
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

  /**
   * 지금 실행 중인 트랜잭션이 증표를 만든 그 트랜잭션인지 확인한다. 끝난 트랜잭션의 증표(잠금 풀림)와 다른 트랜잭션·다른 스레드에서의 사용(잠금을 쥐지 않아 같은 행에서
   * 잠금 시간 초과까지 기다림, WD-680)을 거부한다.
   */
  void requireActive() {
    if (completed) {
      throw new IllegalStateException("삭제 잠금 증표를 만든 트랜잭션이 이미 끝났습니다 — 잠금이 풀린 증표는 쓸 수 없습니다");
    }
    if (suspended || Thread.currentThread() != owner) {
      throw new IllegalStateException(
          "삭제 잠금 증표는 만든 트랜잭션 안에서만 쓸 수 있습니다 — 다른 트랜잭션(REQUIRES_NEW 등)·다른 스레드는 그 잠금을 쥐지 않습니다");
    }
  }
}
