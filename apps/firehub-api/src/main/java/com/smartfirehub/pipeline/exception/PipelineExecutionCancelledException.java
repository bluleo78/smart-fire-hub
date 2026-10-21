package com.smartfirehub.pipeline.exception;

/**
 * 협조적 취소 체크포인트가 사용자 취소 요청을 알아챘을 때 던지는 제어 흐름 예외(WD-153).
 *
 * <p>실패가 아니다 — 러너는 이 예외를 일반 예외보다 먼저 잡아 스텝을 FAILED 가 아니라 CANCELLED 로 기록하고 남은 스텝을 실행하지 않는다.
 * 실행기들은 실패 경로와 같은 정리 경로(REPLACE 스테이징 임시 테이블 삭제)를 그대로 타도록 이 예외를 다시 던지기만 한다 — 그래서 원본 데이터를 지키는
 * 코드가 실패·취소 두 갈래로 갈라지지 않는다.
 *
 * <p>{@code rowsKept} 는 취소 시점까지 출력에 <b>실제로 남은</b> 행 수다(APPEND 의 「취소 전까지 N행 적재됨」 문구). REPLACE 스테이징을
 * 버린 경우에는 {@code replaceDiscarded=true} 이고 원본이 그대로이므로 행 수는 의미가 없다.
 */
public class PipelineExecutionCancelledException extends RuntimeException {

  /** 취소 기록의 공통 머리 문구 — 실행·스텝 기록과 화면이 같은 말을 쓴다. */
  public static final String CANCELLED_MESSAGE = "사용자가 실행을 취소했습니다.";

  /**
   * 취소로 실행하지 않은 스텝(SKIPPED)에 남기는 사유. 웹(ExecutionStepPanel.tsx 의 CANCEL_SKIPPED_MESSAGE)이 이 문구로 시작하는지로
   * "취소 때문에 건너뜀"을 가리므로, 바꿀 때 함께 바꾼다.
   */
  public static final String SKIPPED_BY_CANCEL_MESSAGE = "실행이 취소되어 이 스텝은 실행하지 않았습니다.";

  private final long rowsKept;
  private final boolean replaceDiscarded;

  /**
   * @param rowsKept 취소 시점까지 출력 테이블에 남은 행 수(APPEND). 적재 전이면 0
   * @param replaceDiscarded REPLACE 스테이징 임시 테이블을 버려 원본을 지켰는가
   */
  public PipelineExecutionCancelledException(long rowsKept, boolean replaceDiscarded) {
    super(CANCELLED_MESSAGE);
    this.rowsKept = rowsKept;
    this.replaceDiscarded = replaceDiscarded;
  }

  public long rowsKept() {
    return rowsKept;
  }

  public boolean replaceDiscarded() {
    return replaceDiscarded;
  }

  /**
   * 스텝 실행 기록(error_message)에 남길 문구. REPLACE 면 원본을 지켰다는 것을, 아니면 실제로 남은 행 수를 말한다 — 사용자가 "취소했는데 데이터가
   * 반쯤 들어갔나?"를 이 한 줄로 판단할 수 있어야 한다.
   */
  public String stepMessage() {
    if (replaceDiscarded) {
      return CANCELLED_MESSAGE + " 적재 중이던 데이터는 버리고 기존 데이터를 그대로 유지했습니다(REPLACE).";
    }
    if (rowsKept <= 0) {
      // 출력에 쓰기 전(또는 0행 적재 상태)에 멈췄다 — 출력은 이 스텝 실행 전과 같다.
      return CANCELLED_MESSAGE + " 취소 전까지 0행 적재됨(출력 데이터는 바뀌지 않았습니다).";
    }
    return CANCELLED_MESSAGE + " 취소 전까지 " + rowsKept + "행 적재됨.";
  }

  /** 예외 원인 사슬에서 취소 예외를 찾는다 — 실행기가 감싸 던진 경우에도 실패로 오기록하지 않기 위한 방어. */
  public static PipelineExecutionCancelledException findIn(Throwable t) {
    for (Throwable c = t; c != null; c = c.getCause()) {
      if (c instanceof PipelineExecutionCancelledException ce) {
        return ce;
      }
      if (c.getCause() == c) {
        break;
      }
    }
    return null;
  }
}
