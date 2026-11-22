/**
 * 컨테이너 안에서 유효하지 않은 정수 칸(표기 오류·하한 미만) 중 첫 칸에 초점을 준다.
 * 저장을 막을 때 사용자를 오류가 보이는 칸으로 안내하기 위함. 찾으면 true.
 */
export function focusFirstInvalidInteger(root: ParentNode = document): boolean {
  const el = root.querySelector<HTMLInputElement>('input[data-integer-invalid="true"]');
  el?.focus();
  return !!el;
}
