import { useCallback, useEffect, useRef, useState } from 'react';

// 자동 저장 디바운스 간격 — 매 키 입력마다 PATCH를 보내지 않기 위한 유예 시간(브리프 Step 2~4).
export const AUTOSAVE_DEBOUNCE_MS = 400;

/**
 * 텍스트/텍스트영역 필드 자동 저장 — blur 시 즉시 커밋 + 입력 중에는 디바운스로 커밋한다
 * (매 키 입력마다 PATCH를 보내지 않는다, 브리프 Step 2~4).
 *
 * EntityInspector(Task 4)에서 처음 만들어졌고, RelationInspector(Task 5)도 같은 자동 저장 규칙이
 * 필요해 이 파일로 옮겼다(브리프 지시: "재사용하되 필요하면 있는 자리에서 일반화") — 로직 자체는
 * 세 차례 리뷰를 거쳐 굳어졌으므로 변경하지 않았다.
 *
 * committed와 다른 값일 때만 onCommit을 호출해 값이 그대로인 blur(포커스만 왔다 간 경우)에서
 * 불필요한 PATCH가 나가지 않게 한다. validate가 에러를 반환하면 커밋 자체를 막는다 — 예약어 등
 * 서버가 거부할 값은 API를 아예 부르지 않는다(브리프: "API 호출 없이 로컬에서 차단").
 *
 * onCommit은 실패 시 undefined를 반환해야 한다(useOntologyElementMutations의 각 메서드가 이미
 * 그렇게 동작한다) — commit()은 요청이 끝날 때까지 committed를 전진시키지 않고, 실패하면
 * dirty 상태로 되돌려 다음 blur/디바운스가 자연히 재시도하게 한다(Task 4 리뷰 C-1). 이게 없으면
 * 실패한 편집이 "committed"로 확정된 채 남아 재시도를 영영 시도하지 않고, 그 뒤 다른 필드가
 * 성공하면 툴바가 "저장됨"을 보여줘 사용자가 유실을 알아챌 방법이 없어진다.
 *
 * serverValue가 바뀌면(다른 화면의 동시 편집 반영 등) draft/committed를 재동기화하되, **로컬에
 * 미커밋 편집(draft !== committed)이 있으면 draft는 건드리지 않고 committed(서버 기준선)만
 * 갱신한다**(Task 4 리뷰 I-1) — 그렇지 않으면 다른 필드의 PATCH 응답이 엔티티를 통째로 교체하면서
 * (useOntologyElement.ts의 commitMutation) 사용자가 지금 막 타이핑 중인 값을 조용히 되돌려 버린다.
 * 선택 대상 전환은 OntologyPage의 key={entity.id}/{prop.id}/{relation.id} 리마운트가 처리하므로 이
 * 가드가 "이전 대상 draft 누수"를 만들지는 않는다. 단, 이 보장은 호출부마다 key가 있다는 전제에
 * 기대는 것이다 — ModelOutline의 도메인 필드 호출부에는 그런 key가 없고(선택 전환 자체가 없는 단일
 * 필드), 그 보호는 대신 OntologyPage가 온톨로지 전환 시 렌더 중 편집 모드를 꺼 아웃라인을
 * 언마운트시키는 데서 온다(M-6, S2 최종 리뷰).
 *
 * ref로 "이전 값"을 비교하는 흔한 트릭 대신 React 공식 패턴
 * (https://react.dev/reference/react/useState#storing-information-from-previous-renders)대로
 * useState 쌍을 쓴다 — 이 저장소의 react-hooks 규칙(refs/no-set-state-in-effect)이 ref 비교
 * 버전은 "값이 nullable일 때"만 예외로 인정하고, 일반 문자열 비교는 "렌더 중 ref 접근"으로 막는다.
 */
// trim=true인 필드(타입 이름/속성 이름)는 검증도 전송도 항상 같은 trim된 문자열을 기준으로 한다
// (Task 4 리뷰 M-1) — 검증 함수(validatePropertyName 등)는 내부에서 trim해 통과시키지만 전송은
// raw draft였던 예전 구현에서는 "  type  "처럼 공백이 붙은 예약어가 검증은 통과하고(trim 비교)
// 서버에는 공백 그대로 저장돼(서버도 trim하지 않음) 예약어 검사를 사실상 우회할 수 있었다.
export function useAutosaveText(
  serverValue: string,
  onCommit: (value: string) => unknown,
  validate?: (value: string) => string | null,
  trim = false,
) {
  const [draft, setDraft] = useState(serverValue);
  const [committed, setCommitted] = useState(serverValue);
  const [prevServerValue, setPrevServerValue] = useState(serverValue);
  // 디바운스 타이머 핸들만 담는다 — 이벤트 핸들러(onChange)와 commit(blur/타이머 콜백)에서만
  // 읽고 쓰므로 "렌더 중 ref 접근" 규칙에 걸리지 않는다.
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  if (prevServerValue !== serverValue) {
    setPrevServerValue(serverValue);
    if (draft === committed) {
      // 로컬 편집이 없다 — 서버 기준선과 draft를 함께 최신화해도 안전하다.
      setDraft(serverValue);
      setCommitted(serverValue);
    } else {
      // 사용자가 지금 타이핑 중이다 — draft를 되돌리면 입력이 사라진다. 서버 기준선(committed)만
      // 갱신해, 이 편집이 끝난 뒤의 dirty 비교(draft !== committed)는 여전히 정확하게 유지한다.
      setCommitted(serverValue);
    }
  }

  // draft를 deps에 넣어 항상 최신값을 커밋한다 — 클로저 캡처 문제를 ref 없이 deps로 해결한다.
  const commit = useCallback(() => {
    if (timerRef.current) {
      clearTimeout(timerRef.current);
      timerRef.current = null;
    }
    // 검증·비교·전송 모두 같은 정규화된 값을 기준으로 한다(M-1) — 입력창에 보이는 draft 자체는
    // 타이핑 중 트리밍하지 않는다(커서가 튀는 걸 막기 위해), 커밋 시점에만 trim한다.
    const value = trim ? draft.trim() : draft;
    // trim 필드는 draft 자체도 정규화된 값으로 맞춘다(Task 4 리뷰 N-1) — 그렇지 않으면 "committed는
    // trim된 값인데 draft는 공백이 붙은 채"로 남아, 이후 어떤 blur도 draft(trim 전)!==committed(trim
    // 후)라 다시 커밋을 시도하지만 매번 같은 trim된 값을 보내는 무한 dirty 상태가 된다. 서버 갱신
    // 재동기화(위 prevServerValue 분기)도 draft===committed가 아니게 되어 더는 draft를 갱신하지
    // 않으므로, 필드가 사실상 영구히 "저장은 됐지만 화면은 낡은" 상태로 고착된다.
    if (trim && value !== draft) setDraft(value);
    if (value === committed) return;
    if (validate?.(value)) return;
    // 실패 시 되돌릴 기준값은 "직전 committed"가 아니라 "마지막으로 관측한 서버 값"이어야 한다
    // (Task 4 리뷰 N-2). 겹친 실패 시퀀스(커밋 X 진행 중 → 커밋 Y 시작 → X 실패 → Y도 실패)에서
    // Y의 되돌림이 prevCommitted를 쓰면 X(서버에 없는 값)로 되돌아가 버린다 — committed===X가
    // 되면 사용자가 나중에 X를 다시 입력해도 "값이 같다"며 재전송이 조용히 스킵된다.
    // prevServerValue(이 훅이 마지막으로 실제 관측한 서버 상태)로 되돌리면 이 상태 자체가 생기지
    // 않는다. serverValue 파라미터를 직접 쓰지 않는 이유는 exhaustive-deps가 그 값을 deps에
    // 요구해 매 서버값 변경마다 commit이 재생성되기 때문이다 — prevServerValue는 이미 이 훅의
    // state라 deps에 자연스럽게 넣을 수 있다.
    setCommitted(value);
    void (async () => {
      const result = await onCommit(value);
      if (result === undefined) {
        // 실패 — 이 값을 dirty로 되돌려 다음 blur/디바운스가 재시도하게 한다. 함수형 업데이트로
        // 감싸는 이유: 그 사이 사용자가 또 편집해 committed가 이미 더 앞으로 갔다면(current !== value)
        // 그 최신 상태를 덮어쓰지 않기 위해서다.
        setCommitted((current) => (current === value ? prevServerValue : current));
      }
    })();
  }, [draft, committed, onCommit, validate, trim, prevServerValue]);

  // commit은 draft가 바뀔 때마다 새로 생성되는데, 이미 예약된 setTimeout 콜백은 예약 시점의
  // commit을 캡처하고 있어 그 사이 draft가 더 바뀌면 낡은 값을 커밋할 수 있다 — commitRef로
  // "타이머가 실제로 실행되는 시점의 최신 commit"을 항상 가리키게 한다. 렌더 중 ref를 직접
  // 대입할 수 없으므로(react-hooks 규칙) 커밋 이후 effect에서 동기화한다(useOntologyElement.ts의
  // runMutationRef와 같은 패턴).
  const commitRef = useRef(commit);
  useEffect(() => {
    commitRef.current = commit;
  }, [commit]);

  // 언마운트 시 타이머를 정리하지 않는다(의도적, Task 4 리뷰 M-3) — entity.id/prop.id/relation.id는
  // 클로저에 그대로 잡혀 있어 늦게 도착한 커밋도 올바른 대상으로 나가고, SaveStatusChip은 showEditor
  // 밖에 항상 마운트돼 있어(Task 3 IMP-2) 편집 모드를 꺼도 저장 상태가 계속 보인다 — "언마운트 후에도
  // 커밋을 끝까지 시도한다"가 여기서는 안전하고 바람직한 동작이다.
  const onChange = (value: string) => {
    setDraft(value);
    if (timerRef.current) clearTimeout(timerRef.current);
    timerRef.current = setTimeout(() => commitRef.current(), AUTOSAVE_DEBOUNCE_MS);
  };

  // 에러 표시도 커밋과 같은 정규화 규칙을 쓴다 — 그렇지 않으면 "  type  "이 화면엔 에러 없이
  // 보이다가 blur 시점에만 조용히 저장이 막혀 사용자가 원인을 알 수 없다.
  return { value: draft, onChange, onBlur: commit, error: validate?.(trim ? draft.trim() : draft) ?? null };
}
