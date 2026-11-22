import { createContext, useCallback, useContext, useMemo, useRef, useState } from 'react';

/**
 * 숫자 칸(IntegerTextInput)의 「무효 원문」 보관소 (WD-906 후속 반려 수정).
 *
 * 왜: 파이프라인 편집기에서 오류 칸(1e 등)이 언마운트되면(다른 스텝 클릭·고급 설정 접기·탭 전환) 칸이 들고 있던 원문과
 * 무효 상태가 함께 사라져, 저장 잠금이 풀리고 마지막 올바른 값이 말없이 저장됐다. 그래서 무효 원문을 칸 밖(편집기)에 맡겨 두고,
 * 칸이 다시 마운트되면 그 원문으로 시작해 사용자가 오류를 다시 보고 고칠 수 있게 한다(원문 없이 막힘만 남는 교착 방지).
 */
export interface IntegerDraftStore {
  /** 칸 키로 맡겨 둔 무효 원문 — 없으면 undefined (칸 마운트 때 초기 원문으로 읽는다) */
  get(key: string): string | undefined;
  /** 무효 원문을 맡기거나(raw) 지운다(null). 같은 값이면 아무것도 하지 않는다 */
  set(key: string, raw: string | null): void;
  /** 저장 차단 때 「숨은 오류 칸을 다시 펴 달라」는 요청 번호 — 바뀌면 오류 칸이 든 접힌 절이 펴진다 */
  revealSeq: number;
}

/** 편집기가 내려 주는 보관소. 없으면(편집기 밖) 칸은 예전처럼 원문을 혼자 들고 있다 */
export const IntegerDraftContext = createContext<IntegerDraftStore | null>(null);

/** 가장 가까운 보관소(스텝 범위로 좁혀진 것일 수 있다) */
export function useIntegerDraftStore(): IntegerDraftStore | null {
  return useContext(IntegerDraftContext);
}

/**
 * 키 앞에 접두사(예: 「스텝 tempId/스텝 종류」)를 붙이는 범위 보관소를 만든다.
 * 스텝 설정 컴포넌트는 자기 칸 이름(timeoutMs 등)만 알면 되고, 편집기는 접두사로 어느 스텝·어느 종류의 칸인지 가린다.
 */
export function scopeIntegerDrafts(parent: IntegerDraftStore | null, prefix: string): IntegerDraftStore | null {
  if (!parent) return null;
  return {
    get: (key) => parent.get(`${prefix}/${key}`),
    set: (key, raw) => parent.set(`${prefix}/${key}`, raw),
    revealSeq: parent.revealSeq,
  };
}

/**
 * 파이프라인 스텝 범위 접두사 — 「스텝 tempId/스텝 종류」. 종류를 넣는 이유: 스텝 종류를 바꾸면(API 호출 → SQL) 그 칸은 저장되지 않으므로
 * 편집기가 지금 종류와 맞는 원문만 저장 차단에 세야 한다(종류를 되돌리면 원문이 다시 보인다).
 */
export function integerDraftStepPrefix(tempId: string, scriptType: string): string {
  return `${tempId}/${scriptType}`;
}

/**
 * 편집기 수준 보관소를 만든다. drafts(키 → 무효 원문)는 저장 차단 판단에, store 는 IntegerDraftContext 로 내려 준다.
 * get 은 칸 마운트 중(렌더) 바로 읽혀야 해서 최신 맵을 ref 로도 들고 있다(set 이 ref·상태를 함께 바꾼다).
 */
export function useIntegerDrafts() {
  const [drafts, setDrafts] = useState<ReadonlyMap<string, string>>(() => new Map());
  const draftsRef = useRef(drafts);
  const [revealSeq, setRevealSeq] = useState(0);

  const set = useCallback((key: string, raw: string | null) => {
    const cur = draftsRef.current;
    // 이미 그 상태면 다시 그리지 않는다(칸 effect 가 같은 값을 반복해 맡겨도 안전)
    if (raw === null ? !cur.has(key) : cur.get(key) === raw) return;
    const next = new Map(cur);
    if (raw === null) next.delete(key);
    else next.set(key, raw);
    draftsRef.current = next;
    setDrafts(next);
  }, []);

  /** 맡긴 원문을 모두 버린다 — 편집 취소·최신 내용 다시 불러오기처럼 편집 내용 자체를 버릴 때 */
  const clear = useCallback(() => {
    if (draftsRef.current.size === 0) return;
    draftsRef.current = new Map();
    setDrafts(draftsRef.current);
  }, []);

  /** 숨은 오류 칸이 든 절을 펴 달라고 요청한다 */
  const reveal = useCallback(() => setRevealSeq((n) => n + 1), []);

  const store = useMemo<IntegerDraftStore>(
    () => ({ get: (key) => draftsRef.current.get(key), set, revealSeq }),
    [set, revealSeq],
  );

  return { drafts, store, clear, reveal, revealSeq };
}
