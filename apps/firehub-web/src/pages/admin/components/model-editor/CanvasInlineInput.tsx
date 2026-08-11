import { useEffect, useId, useRef, useState } from 'react';

import { Input } from '@/components/ui/input';

/**
 * 캔버스 위 인라인 텍스트 입력 — 관계 드래그 연결(S3 Task 2)의 관계명 입력이 첫 소비자이지만,
 * 관계 전용이 아니다(Task 3가 타입 이름 입력에 재사용한다). 그래서 검증 규칙·커밋 로직은 전부
 * props로 주입받고, 이 컴포넌트 자신은 "위치 지정 + 키보드 상호작용 + 에러 표시"만 담당한다.
 */
export interface CanvasInlineInputProps {
  // 캔버스 컨테이너(부모의 relative 박스) 기준 rendered 좌표(px) — cytoscape의 renderedPosition()과
  // 같은 좌표계다. 호출부가 absolute 배치를 위해 넘긴다.
  x: number;
  y: number;
  initialValue?: string;
  placeholder?: string;
  // 접근성 이름(aria-label) — 캔버스 위 플로팅 입력이라 <Label htmlFor>로 짝지을 고정 DOM이 없다.
  label: string;
  onCommit: (value: string) => void;
  onCancel: () => void;
  // null이면 통과, 문자열이면 에러 문구. 커밋 시도 시(Enter) 호출하며, 실패하면 입력을 닫지 않고
  // 에러만 보여준다(브리프 Step 4) — 사용자가 값을 고쳐 바로 재시도할 수 있어야 하기 때문이다.
  validate?: (value: string) => string | null;
  // 이중 제출 가드(S2 최종 리뷰 I-2와 동일한 결함 클래스) — 커밋이 서버 응답을 기다리는 동안 호출부가
  // true로 넘겨 입력을 잠근다. 이 컴포넌트 자신은 onCommit이 비동기인지 알 방법이 없으므로(시그니처가
  // void) 잠금 여부는 항상 호출부가 판단해 내려준다.
  disabled?: boolean;
  'data-testid'?: string;
}

export default function CanvasInlineInput({
  x,
  y,
  initialValue = '',
  placeholder,
  label,
  onCommit,
  onCancel,
  validate,
  disabled,
  'data-testid': dataTestId,
}: CanvasInlineInputProps) {
  const [value, setValue] = useState(initialValue);
  const [error, setError] = useState<string | null>(null);
  const errorId = useId();
  const inputRef = useRef<HTMLInputElement>(null);
  // onCommit 호출 직후 발생할 수 있는 blur(포커스 이동)가 onCancel까지 잇달아 부르는 것을 막는다 —
  // 커밋과 취소가 같은 제스처에서 동시에 나가면 호출부가 "커밋했는데 곧바로 취소됨"을 함께 받는다.
  const committingRef = useRef(false);

  // 마운트 시 자동 포커스 + 전체 선택(브리프 Step 4) — 드롭 직후 바로 타이핑을 시작할 수 있어야 한다.
  useEffect(() => {
    inputRef.current?.focus();
    inputRef.current?.select();
  }, []);

  // disabled가 true→false로 내려오면(커밋 실패로 호출부가 재활성화한 경우) committingRef를
  // 되돌린다(리뷰 C-1) — pendingConnection/pendingRename/pendingCreate 중 무엇이 살아있든(S3 Task 3
  // 이후 세 흐름 모두 같은 계약을 쓴다) 이 컴포넌트는 remount되지 않으므로(호출부가 사용자가 친
  // 값을 지키려고 같은 인스턴스를 유지한다), committingRef가 true로 남으면 onBlur의 "취소" 분기가
  // 영구히 죽는다. 재활성화 시 포커스도 되돌려 Esc로도 즉시 취소할 수 있게 한다 — disabled 구간에
  // 브라우저가 포커스를 이미 밀어냈을 수 있어서다.
  const prevDisabledRef = useRef(disabled);
  useEffect(() => {
    if (prevDisabledRef.current && !disabled) {
      committingRef.current = false;
      inputRef.current?.focus();
    }
    prevDisabledRef.current = disabled;
  }, [disabled]);

  const commit = () => {
    if (disabled) return;
    const trimmed = value.trim();
    // 빈 값 커밋은 취소로 간주한다(스펙 §상호작용) — 아무것도 남기지 않는다.
    if (!trimmed) {
      onCancel();
      return;
    }
    const validationError = validate?.(trimmed) ?? null;
    if (validationError) {
      setError(validationError);
      return;
    }
    committingRef.current = true;
    onCommit(trimmed);
  };

  return (
    <div className="absolute z-10 -translate-x-1/2" style={{ left: x, top: y }}>
      <Input
        ref={inputRef}
        className="h-8 w-40 text-sm"
        aria-label={label}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? errorId : undefined}
        placeholder={placeholder}
        value={value}
        disabled={disabled}
        data-testid={dataTestId}
        onChange={(e) => {
          setValue(e.target.value);
          setError(null);
        }}
        onKeyDown={(e) => {
          if (disabled) return;
          if (e.key === 'Enter') {
            e.preventDefault();
            commit();
          } else if (e.key === 'Escape') {
            e.preventDefault();
            onCancel();
          }
        }}
        onBlur={() => {
          // disabled 상태에서 blur가 나는 경우(제출 잠금 자체가 포커스를 밀어내는 브라우저가 있다)는
          // 사용자가 바깥을 클릭한 게 아니라 우리가 스스로 잠근 결과이므로 취소로 취급하지 않는다.
          if (!committingRef.current && !disabled) onCancel();
        }}
      />
      {/* role="alert"만 남긴다(리뷰 N-3) — 암묵적으로 assertive라 aria-live="polite"와 같이 걸면
          서로 상충한다. aria-describedby(위)로 입력 자체에도 묶어 포커스가 입력에 머문 채로도
          스크린리더가 에러를 읽을 수 있게 한다 — RelationInspector의 aria-invalid+aria-describedby
          패턴과 동일. */}
      {error && (
        <p id={errorId} role="alert" className="mt-1 text-xs text-destructive" data-testid={dataTestId ? `${dataTestId}-error` : undefined}>
          {error}
        </p>
      )}
    </div>
  );
}
