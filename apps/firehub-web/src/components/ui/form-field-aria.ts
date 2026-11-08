/**
 * 입력의 aria-describedby·aria-invalid 연결 규칙(WD-660) — FormField 와 FormField 를 쓰지 못하는 칸(설정 화면 Textarea 등)이
 * 같은 규칙을 쓰도록 나눈 파일. 컴포넌트 파일(form-field.tsx)은 컴포넌트만 내보내야 Fast Refresh 가 동작해서 따로 둔다.
 */

export type AriaInputProps = {
  id?: string;
  'aria-invalid'?: boolean | 'true' | 'false';
  'aria-describedby'?: string;
};

/** 입력에 이을 문구 id — 실제로 그려지는 문구만 담는다(없으면 undefined). */
export type LinkedIds = { error?: string; desc?: string; hint?: string };

/**
 * 입력에 줄 aria-describedby·aria-invalid 를 FormField 와 같은 규칙으로 만든다(WD-660 — FormField 를 쓰지 못하는 칸도 같은 규칙을 쓰게 내보낸다).
 * 순서: 입력이 원래 가진 id → 오류 → 설명 → 안내(WD-643 — 오류를 먼저 읽게 한다). 이미 있는 id 는 겹쳐 넣지 않는다.
 * aria-invalid 는 오류가 있을 때만 다룬다 — 화면이 직접 정했으면(예: 중복 이름) 그대로 두고, 정하지 않았으면 true 로 둔다.
 * 이을 id 가 하나도 없으면 aria-describedby 는 undefined(빈 속성을 그리지 않는다).
 */
export function linkedAriaProps(
  linked: LinkedIds,
  own: Pick<AriaInputProps, 'aria-describedby' | 'aria-invalid'> = {},
): Pick<AriaInputProps, 'aria-describedby' | 'aria-invalid'> {
  const extra = [linked.error, linked.desc, linked.hint].filter((id): id is string => Boolean(id));
  const ids = (own['aria-describedby'] ?? '').split(' ').filter(Boolean);
  const described = [...ids, ...extra.filter((id) => !ids.includes(id))].join(' ');
  return {
    'aria-describedby': described || undefined,
    ...(linked.error ? { 'aria-invalid': own['aria-invalid'] ?? true } : {}),
  };
}
