interface InlineFieldErrorProps {
  /** 입력의 aria-describedby 로 연결할 id */
  id: string;
  /** 오류 문구 — 없으면 아무것도 그리지 않는다 */
  message?: string;
}

/**
 * 파이프라인 스텝 설정의 행 단위 입력(출력 컬럼명·대상 컬럼 등) 바로 아래에 붙는 인라인 오류 문구.
 * 입력 테두리는 공용 Input 의 aria-invalid 스타일이 맡으므로 여기서는 문구만 그린다.
 */
export function InlineFieldError({ id, message }: InlineFieldErrorProps) {
  if (!message) return null;
  return (
    <p id={id} className="text-[0.75rem] text-destructive pl-0.5" role="alert" aria-live="polite">
      {message}
    </p>
  );
}
