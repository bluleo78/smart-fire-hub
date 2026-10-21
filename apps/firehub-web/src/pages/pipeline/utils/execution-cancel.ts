/**
 * 실행 취소 버튼 표시 상태(WD-153).
 * - hidden: 이미 끝난 실행(COMPLETED/FAILED/CANCELLED) — 취소할 것이 없으므로 버튼을 숨긴다(서버도 409).
 * - available: 진행 중(PENDING/RUNNING)이고 아직 취소 요청 전 — 「실행 취소」를 누를 수 있다.
 * - requested: RUNNING 인데 취소 요청이 이미 기록됨 — 실행기가 다음 점검 지점에서 멈출 때까지 「취소 요청됨」으로 비활성 표시.
 */
export type ExecutionCancelState = 'hidden' | 'available' | 'requested';

/** 실행 상태와 취소 요청 시각으로 취소 버튼 상태를 정한다 — 상세 헤더와 실행 이력 목록이 같은 규칙을 쓰도록 한 곳에 둔다. */
export function getExecutionCancelState(execution: {
  status: string;
  cancelRequestedAt?: string | null;
}): ExecutionCancelState {
  if (execution.status !== 'PENDING' && execution.status !== 'RUNNING') return 'hidden';
  return execution.cancelRequestedAt ? 'requested' : 'available';
}
