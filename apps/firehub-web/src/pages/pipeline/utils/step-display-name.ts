/**
 * 스텝의 표시 이름 — 이름이 없으면 「스텝 #순번」으로 부른다.
 * 캔버스 노드·연결선 이름·이동 안내가 같은 이름을 쓰게 해 스크린리더가 어디서나 같은 말을 듣게 한다 (WD-1049).
 * 스텝 설정 패널도 같은 이름을 쓰도록 캔버스(PipelineCanvas)에서 공용 유틸로 옮겼다 (WD-1059).
 */
export function stepDisplayName(name: string | undefined | null, index: number): string {
  // 표시 이름 자체는 자르지 않는다 — 비었는지만 isUnnamedStep 으로 본다
  return isUnnamedStep(name) ? `스텝 #${index + 1}` : (name as string);
}

/** 이름이 없는 스텝인가 — 공백뿐인 이름도 없는 것으로 본다. 표시 이름·흐린 색·배지 숨김이 같은 판정을 쓴다 (WD-1049). */
export function isUnnamedStep(name: string | undefined | null): boolean {
  return !name?.trim();
}
