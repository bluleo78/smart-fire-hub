package com.smartfirehub.support;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.smartfirehub.dataset.service.DatasetDataWriteLock;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 단위 테스트용 출력 쓰기 잠금(WD-666) — 잠그지 않고 본문만 그대로 실행한다. 실행기·러너를 목으로 조립하는 단위 테스트는 실제 트랜잭션·DB 가 없어 진짜 잠금을
 * 잡을 수 없으므로, 「잠금 안에서 본문이 실행된다」만 흉내 낸다. 잠금 동작 자체는 DatasetDataWriteLockConcurrencyTest 가 실제 DB 로
 * 검증한다.
 *
 * <p>WD-674: 가져오기 쓰기({@code writeToDataset})도 본문을 실행한다 — 맨 목은 null 을 돌려줘 본문이 조용히 건너뛰어지므로 단언이 공허하게
 * 통과했다. 이 통과 판은 모든 데이터셋이 남아 있다고 보므로 {@code onGone}(잠글 때 데이터셋 없음 → 던질 예외)을 부르지 않는다. 그 계약은 실제 잠금 구현으로
 * 서비스 흐름을 도는 DataImportDatasetGoneFlowTest(가져오기 중 데이터셋 삭제 → 삭제 안내)·DataImportWriteTargetTest 가 본다
 * (WD-675).
 *
 * <p>스텁은 lenient 다 — 쓰기에 닿지 않는 테스트(엄격 스텁 확장)에서 「쓰지 않은 스텁」으로 실패하지 않게 한다.
 */
public final class PassThroughWriteLock {

  private PassThroughWriteLock() {}

  /** 쓰기 본문을 그대로 실행하는 목을 새로 만든다. */
  public static DatasetDataWriteLock create() {
    return stub(mock(DatasetDataWriteLock.class));
  }

  /** 이미 있는 목(@Mock 필드 등)에 같은 통과 동작을 건다. 나머지 잠금 메서드는 아무것도 하지 않는다. */
  @SuppressWarnings("unchecked")
  public static DatasetDataWriteLock stub(DatasetDataWriteLock lock) {
    lenient()
        .doAnswer(inv -> ((Supplier<Object>) inv.getArgument(1)).get())
        .when(lock)
        .writeToOutput(anyString(), any(Supplier.class));
    lenient()
        .doAnswer(
            inv -> {
              ((Runnable) inv.getArgument(1)).run();
              return null;
            })
        .when(lock)
        .writeToOutput(anyString(), any(Runnable.class));
    lenient()
        .doAnswer(inv -> ((Supplier<Object>) inv.getArgument(2)).get())
        .when(lock)
        .writeToDataset(anyLong(), any(Function.class), any(Supplier.class));
    return lock;
  }
}
