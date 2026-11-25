/**
 * 의존 그래프에서 한 스텝의 자손(그 스텝을 직접·간접으로 의존하는 스텝) tempId 를 모은다 (WD-1089).
 *
 * 왜: 스텝 입력 후보에서 「뒤 스텝」은 목록 위치가 아니라 실제로 이 스텝 뒤에 실행되는 스텝이어야 한다 —
 * 「스텝 삽입」은 새 스텝을 목록 끝에 붙이므로 위치로 정하면 실제 선행이 뒤로 보인다.
 * 어떻게: dependsOnTempIds 를 한 번 훑어 역방향 인접 목록(선행 → 후행들)을 만들고 그 위를 따라간다(O(스텝+간선)).
 * 시작 스텝 자신은 결과에 넣지 않는다(사이클이 있어도 무한 반복하지 않는다).
 */
export function collectDescendantTempIds(
  steps: ReadonlyArray<{ tempId: string; dependsOnTempIds: readonly string[] }>,
  startTempId: string,
): Set<string> {
  const dependents = new Map<string, string[]>();
  for (const s of steps) {
    for (const dep of s.dependsOnTempIds) {
      const list = dependents.get(dep);
      if (list) list.push(s.tempId);
      else dependents.set(dep, [s.tempId]);
    }
  }
  const result = new Set<string>();
  const stack = [startTempId];
  for (let tempId = stack.pop(); tempId !== undefined; tempId = stack.pop()) {
    for (const next of dependents.get(tempId) ?? []) {
      if (next === startTempId || result.has(next)) continue;
      result.add(next);
      stack.push(next);
    }
  }
  return result;
}

/**
 * 의존 그래프에서 한 스텝의 조상(그 스텝이 직접·간접으로 의존하는 스텝) tempId 를 모은다 (WD-1139).
 *
 * 왜: 스텝 입력 후보에서 「앞 스텝」은 목록 위치가 아니라 실제로 이 스텝보다 먼저 실행되는 스텝이어야 한다 —
 * 「스텝 삽입」으로 목록 앞쪽이 뒤바뀌어도 의존 관계로만 판정해야 오류가 없다.
 * 어떻게: 각 스텝의 dependsOnTempIds 를 스택으로 따라가며 이미 본 tempId 는 건너뛰고 모은다(O(스텝+간선)).
 * 시작 스텝 자신은 결과에 넣지 않으며(사이클이 있어도 무한 반복하지 않는다),
 * 목록에 없는 선행(고스트)도 집합에 남긴다 — 후보 표시 판단을 값 유무로 하기 때문이다.
 */
export function collectAncestorTempIds(
  steps: ReadonlyArray<{ tempId: string; dependsOnTempIds: readonly string[] }>,
  startTempId: string,
): Set<string> {
  const byTempId = new Map(steps.map((s) => [s.tempId, s]));
  const result = new Set<string>();
  const stack = [startTempId];
  for (let tempId = stack.pop(); tempId !== undefined; tempId = stack.pop()) {
    for (const dep of byTempId.get(tempId)?.dependsOnTempIds ?? []) {
      if (dep === startTempId || result.has(dep)) continue;
      result.add(dep);
      stack.push(dep);
    }
  }
  return result;
}
