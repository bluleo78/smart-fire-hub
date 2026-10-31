import { useEffect, useRef } from 'react';

/** 편집 영역 안에서 처음 포커스할 수 있는 칸 — 숨김·비활성은 건너뛴다 */
const FOCUSABLE =
  'input:not([type="hidden"]):not([disabled]), textarea:not([disabled]), select:not([disabled]), [role="combobox"]:not([disabled]), [contenteditable="true"], button:not([disabled]):not([role="tab"])';

/**
 * 같은 자리를 보기 ↔ 편집 폼으로 바꾸는 화면의 포커스 이동 (WD-415).
 *
 * 무엇: 「수정」을 누르면 그 버튼이 사라지고 폼이 그려지는데, 포커스를 옮기는 코드가 없어
 *       키보드·스크린리더 사용자의 포커스가 body 로 떨어졌다(다음 Tab 은 페이지 맨 처음부터).
 *       편집으로 바뀌면 편집 영역의 첫 칸으로, 보기로 돌아오면 다시 그려진 「수정」 버튼으로 옮긴다.
 * 왜 조건부인가: 저장이 비동기로 끝나는 사이 사용자가 다른 곳에 포커스를 두었으면 빼앗지 않는다 —
 *       포커스가 body(사라진 버튼 자리)나 이 화면 영역 안에 있을 때만 옮긴다.
 *
 * @param isEditing 지금 편집 모드인지
 * @returns editButtonRef — 「수정」 버튼, editRegionRef — 편집 폼을 감싼 영역(첫 칸을 여기서 찾는다),
 *          scopeRef — 포커스를 옮겨도 되는 화면 영역(없으면 editRegionRef 로 판단)
 */
export function useEditModeFocus(isEditing: boolean) {
  const editButtonRef = useRef<HTMLButtonElement>(null);
  const editRegionRef = useRef<HTMLElement>(null);
  const scopeRef = useRef<HTMLElement>(null);
  const prevEditing = useRef(isEditing);

  useEffect(() => {
    if (prevEditing.current === isEditing) return;
    prevEditing.current = isEditing;

    const active = document.activeElement;
    const scope = scopeRef.current ?? editRegionRef.current;
    const focusLost = !active || active === document.body;
    if (!focusLost && !(scope && scope.contains(active))) return;

    if (isEditing) {
      editRegionRef.current?.querySelector<HTMLElement>(FOCUSABLE)?.focus();
    } else {
      editButtonRef.current?.focus();
    }
  }, [isEditing]);

  return { editButtonRef, editRegionRef, scopeRef };
}
