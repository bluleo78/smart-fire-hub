package com.smartfirehub.pipeline.service.executor;

import com.smartfirehub.pipeline.exception.PipelineExecutionCancelledException;
import java.util.function.BooleanSupplier;

/**
 * 파이프라인 실행의 협조적 취소 확인(WD-153).
 *
 * <p>러너가 실행마다 하나 만들어 실행기에 <b>인자로</b> 넘긴다(생성자 의존이 아니다) — 미리보기(ApiCallPreviewService)처럼 실행 기록이 없는 호출은
 * {@link #NONE} 을 쓰는 기존 오버로드로 그대로 돈다. 판정 자체는 DB 의 {@code pipeline_execution.cancel_requested_at} 을
 * 읽는다(여러 API 인스턴스 중 어디서 취소를 받아도 보인다).
 *
 * <p>진행 중인 호출·문장을 끊지 않는다 — 스레드 interrupt 도 쓰지 않는다(기존 interrupt 경로는 「중단됨」 실패로 기록된다). 체크포인트에서만 멈춘다.
 */
@FunctionalInterface
public interface ExecutionCancelCheck {

  /** 재시도 대기 중 취소를 확인하는 간격 — 대기를 다 채우지 않고 이 간격 안에 깨어난다. */
  long SLEEP_SLICE_MS = 200;

  /** 취소를 확인하지 않는 확인(미리보기·단독 호출용). */
  ExecutionCancelCheck NONE = () -> false;

  /** 지금 이 실행에 취소 요청이 있는가. */
  boolean isCancelRequested();

  /**
   * 이 확인이 가리키는 파이프라인 실행 id(WD-466) — 외부 실행기가 출력 잠금을 얻은 뒤 사용자 SQL 전에 api 에 취소 여부를 되물을 때 쓴다(실행기는 별도
   * 프로세스라 이 확인 객체를 받을 수 없다). 실행 기록이 없는 확인({@link #NONE}·미리보기 람다)은 null — 실행기는 되묻지 않는다.
   */
  default Long executionId() {
    return null;
  }

  /**
   * 실행 id 를 실은 확인을 만든다(WD-466). 러너가 실행마다 하나 만든다 — 판정은 {@code stopRequested} 가, 실행기 되묻기용 id 는 {@link
   * #executionId()} 가 준다.
   */
  static ExecutionCancelCheck of(long executionId, BooleanSupplier stopRequested) {
    return new ExecutionCancelCheck() {
      @Override
      public boolean isCancelRequested() {
        return stopRequested.getAsBoolean();
      }

      @Override
      public Long executionId() {
        return executionId;
      }
    };
  }

  /**
   * 체크포인트 — 취소 요청이 있으면 {@link PipelineExecutionCancelledException} 을 던진다.
   *
   * @param rowsKept 지금까지 출력에 남은 행 수(APPEND 문구용)
   * @param replaceDiscarded 이 예외로 REPLACE 스테이징을 버리게 되는가(호출부의 실패 정리 경로가 버린다)
   */
  default void checkpoint(long rowsKept, boolean replaceDiscarded) {
    if (isCancelRequested()) {
      throw new PipelineExecutionCancelledException(rowsKept, replaceDiscarded);
    }
  }

  /**
   * 취소를 확인하며 잔다(재시도 백오프용). {@link #SLEEP_SLICE_MS} 마다 깨어 취소를 확인하므로 긴 백오프 중에 취소해도 바로 멈춘다 — 예전에는
   * {@code Thread.sleep} 한 번이라 대기 시간을 다 채워야 했다.
   *
   * @throws InterruptedException 스레드가 interrupt 되면(기존 처리 그대로 호출부가 다룬다)
   */
  default void sleep(long millis, long rowsKept, boolean replaceDiscarded)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + Math.max(0, millis);
    checkpoint(rowsKept, replaceDiscarded);
    while (true) {
      long remaining = deadline - System.currentTimeMillis();
      if (remaining <= 0) {
        return;
      }
      Thread.sleep(Math.min(remaining, SLEEP_SLICE_MS));
      checkpoint(rowsKept, replaceDiscarded);
    }
  }
}
