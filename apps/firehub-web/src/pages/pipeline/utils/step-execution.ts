import type { StepExecutionResponse } from '@/types/pipeline';

/**
 * 편집기 스텝(현재 정의)에 해당하는 실행 기록을 찾는다(WD-108).
 *
 * 스텝 id 로만 찾는다 — 이름으로 찾으면, 실행 이후 정의에서 지운 스텝 "A" 의 기록(stepId=null, 이름 스냅샷 "A")이
 * 나중에 새로 만든 같은 이름의 스텝 "A" 에 붙어, 실행된 적 없는 스텝이 실행된 것처럼 보인다. id 가 없는 스텝(아직 저장 전 새 스텝)은
 * 어떤 실행에도 기록이 없으므로 찾지 않는다.
 */
export function findStepExecution(
  stepExecutions: StepExecutionResponse[] | undefined,
  stepId: number | undefined,
): StepExecutionResponse | undefined {
  if (stepId === undefined || !stepExecutions) return undefined;
  return stepExecutions.find((se) => se.stepId === stepId);
}

/** 실행 이후 정의에서 삭제된 스텝의 기록(stepId=null) — DAG 에 노드가 없으므로 실행 정보 패널에 따로 보여 준다. */
export function deletedStepExecutions(stepExecutions: StepExecutionResponse[]): StepExecutionResponse[] {
  return stepExecutions.filter((se) => se.stepId === null);
}
