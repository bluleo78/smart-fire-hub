package com.smartfirehub.pipeline.dto;

/**
 * 트리거 발화 한 번의 결과(WD-152). 외부 호출자(API·웹훅 트리거)에게 "실제로 실행됐는지"를 정직하게 돌려주기 위해 {@code
 * TriggerService#fireTrigger} 가 돌려준다. 예전에는 발화가 {@code void} 라 컨트롤러가 결과와 무관하게 200 「triggered」를 응답했고,
 * 실행되지 않은 호출도 호출자는 실행됐다고 믿었다.
 *
 * <p>스케줄·연쇄·데이터 변경 트리거처럼 HTTP 호출자가 없는 경로는 이 값을 쓰지 않는다 — 그쪽 결과는 이벤트 로그로만 남는다.
 *
 * @param outcome 발화 결과 구분
 * @param executionId 실행이 시작됐을 때의 실행 id ({@link Outcome#FIRED} 일 때만 값이 있다)
 * @param reason 실행하지 않았을 때 사용자에게 보일 한국어 사유 ({@link Outcome#FIRED} 이면 null)
 */
public record TriggerFireResult(Outcome outcome, Long executionId, String reason) {

  /** 발화 결과 구분. */
  public enum Outcome {
    /** 실행을 시작했다. */
    FIRED,
    /** 조건(비활성 파이프라인·동시 실행 정책·연쇄 깊이)에 걸려 실행하지 않았다 — 건너뜀 이벤트가 남는다. */
    SKIPPED,
    /** 트리거가 꺼져 있어 실행하지 않았다 — 기존 동작대로 이벤트는 남기지 않는다. */
    DISABLED,
    /**
     * 워크스페이스(테넌트)가 정지돼 실행하지 않았다(WD-174) — 이벤트·알림을 남기지 않는다. 정지는 운영자가 의도한 상태라 매 주기 건너뜀 행을 쌓을 이유가 없고,
     * 구성원은 어차피 들어와 볼 수 없다. 외부 호출자에게는 409 「skipped」+사유로 알린다.
     */
    TENANT_SUSPENDED,
    /** 트리거가 없다(발화 직전 삭제 등) — 이벤트를 남길 대상이 없다. */
    NOT_FOUND,
    /** 실행을 시작하려다 실패했다 — 오류 이벤트가 남는다. */
    ERROR
  }

  /** 실행을 시작한 결과. */
  public static TriggerFireResult fired(Long executionId) {
    return new TriggerFireResult(Outcome.FIRED, executionId, null);
  }

  /** 실행하지 않은 결과(사유 포함). */
  public static TriggerFireResult notFired(Outcome outcome, String reason) {
    return new TriggerFireResult(outcome, null, reason);
  }
}
