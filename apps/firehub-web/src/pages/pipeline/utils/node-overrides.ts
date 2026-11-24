import type { XYPosition } from '@xyflow/react';

import type { StepNodeType } from '../components/StepNode';

/**
 * 기본 노드 목록에 선택·끌기 상태를 덧씌운다 — 바뀌는 노드(최대 2개)만 새 객체로 만들고 나머지는 같은 객체를 돌려준다.
 * 선택할 때마다 N개 노드를 전부 새로 만들면 memo(StepNode)가 무력화돼 선택 비용이 스텝 수에 비례했다(WD-1074).
 * - 선택 노드: selected 는 xyflow 가 화살표 키로 옮길 노드를 판별하는 데 쓰이므로(WD-1046) 우리 상태에서 내려 주고,
 *   data.isSelected 는 선택 강조 표시용이다.
 * - 끄는 노드: position 만 덮어써 끄는 중에도 제자리에 머물지 않게 한다(WD-1057).
 * 바뀔 노드가 없으면 baseNodes 자체를 돌려줘 하위 참조 동일성을 지킨다.
 */
export function applyNodeOverrides(
  baseNodes: StepNodeType[],
  selectedId: string | null | undefined,
  drag: { id: string; position: XYPosition } | null,
): StepNodeType[] {
  // 한 번 훑으며 바뀌는 노드만 새로 만들고, 하나도 없으면 원래 배열을 돌려준다
  let changed = false;
  const out = baseNodes.map((n) => {
    const isSel = selectedId != null && n.id === selectedId;
    const dragPos = drag != null && n.id === drag.id ? drag.position : null;
    if (!isSel && !dragPos) return n;
    changed = true;
    return {
      ...n,
      ...(isSel ? { selected: true, data: { ...n.data, isSelected: true } } : {}),
      ...(dragPos ? { position: dragPos } : {}),
    };
  });
  return changed ? out : baseNodes;
}
