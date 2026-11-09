interface InlineFieldErrorProps {
  /** 입력의 aria-describedby 로 연결할 id(오류 문구 요소에 붙는다) */
  id: string;
  /** 오류 문구 — 없으면 문구를 그리지 않는다 */
  message?: string;
  /** 입력 중에 생기는 오류면 true — 문구가 없어도 polite 라이브 영역을 그려 두어, 새로 생긴 오류만 읽힌다 */
  live?: boolean;
}

/**
 * 파이프라인 스텝 설정 칸 아래의 인라인 오류 문구(입력 테두리는 공용 Input 의 aria-invalid 스타일이 맡는다).
 * 스텝 설정의 칸별 오류 문구는 role="alert" 를 쓰지 않는다(WD-633) — 패널이 스텝을 고를 때마다 다시 그려져, 오류 스텝을 다시 고를 때마다
 * 같은 오류가 또 읽혔다. 저장 실패는 토스트와 첫 오류 칸 포커스(aria-describedby)가 알리고, 입력 중 오류만 live 영역으로 읽힌다.
 */
export function InlineFieldError({ id, message, live = false }: InlineFieldErrorProps) {
  const text = message ? (
    <p id={id} className="text-[0.75rem] text-destructive pl-0.5">
      {message}
    </p>
  ) : null;
  if (!live) return text;
  return <div aria-live="polite">{text}</div>;
}
