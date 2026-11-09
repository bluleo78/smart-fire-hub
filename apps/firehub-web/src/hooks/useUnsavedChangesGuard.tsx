import { useCallback, useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '../components/ui/alert-dialog';
import { isSameLocationPath } from '../lib/location-path';
import { bypassNextPopstateGuard, registerActiveGuard } from '../lib/unsaved-changes-guard-registry';

/**
 * (WD-673) popstate 경로 「이탈」의 history.back() 이 실제로 이동했는지 기다리는 시간(ms).
 * 이 안에 popstate 가 오지 않으면 뒤로 가기가 아무 일도 하지 않은 것으로 보고 가드를 다시 켠다.
 * history.back() 의 popstate 는 보통 수 ms 안에 오므로 넉넉히 잡았다. 너무 늦게 온 popstate 는 다시 확인 창을 띄울 뿐
 * (입력 유실 없음)이라, 짧게 잡아 생기는 위험보다 가드가 꺼진 채 남는 위험을 더 무겁게 봤다.
 */
export const BACK_NOOP_REARM_MS = 1000;

/**
 * useUnsavedChangesGuard
 * - 폼이 dirty 상태일 때 SPA 라우터 이동(사이드바 클릭 등)·브라우저 탭 닫기·새로고침을 가로채
 *   사용자가 작업한 변경값이 무경고로 유실되는 것을 방지한다.
 * - 본 프로젝트는 `BrowserRouter` (legacy router) 를 사용해 react-router v7의 `useBlocker`를 쓸 수 없다.
 *   대신 다음 두 가지로 가드를 구현한다:
 *   1. document 레벨 click capture 핸들러로 `<a>` 클릭(사이드바·메뉴 등 SPA 링크)을 가로채
 *      AlertDialog 노출 후 사용자 결정에 따라 navigate 호출.
 *   2. `popstate`(브라우저 뒤로/앞으로) 가로채기 — `unsaved-changes-guard-registry`의 전역
 *      인터셉터에 현재 페이지를 등록해두면, 해당 인터셉터가 라우터보다 먼저 popstate를 받아
 *      dirty일 때 라우터로 전파되지 않도록 막고 URL을 원복한 뒤 다이얼로그를 띄운다.
 *      (이슈 #562: 페이지 훅 안에서 직접 `window.addEventListener('popstate', ...)`로 가로채면
 *      등록 순서상 항상 `BrowserRouter`의 리스너보다 나중이라 라우터가 먼저 페이지를 언마운트해버려
 *      가로채기가 원천적으로 불가능했다 — 그래서 앱 부팅 시점에 설치되는 전역 리스너로 옮겼다.)
 *   3. `beforeunload`로 브라우저 수준 새로고침/탭 닫기 가드.
 * - 이슈 #86: 관리자 설정 페이지(이메일 탭 등)에서 dirty 상태 이탈 시 가드 부재로 입력값 유실.
 *
 * 사용법:
 *   const { dialog, requestNavigate } = useUnsavedChangesGuard(isDirty);
 *   // isDirty 대신 판정 함수(() => boolean)를 넘기면 이동 시도·beforeunload 때에만 불러 판정한다 (WD-642).
 *   // 두 번째 인자(선택) { onStay } 는 확인창에서 「취소」/Esc 로 남기를 고른 때 한 번 불린다 (WD-662).
 *   return <>{...page UI}{dialog}</>;
 *
 * - (이슈 #634) 위 (1)의 document click 캡처는 클릭 경로에 `<a href>`가 있을 때만 동작한다.
 *   페이지 내 "뒤로가기" 버튼처럼 `<button onClick={() => navigate(...)}>`로 프로그래매틱하게
 *   이동하는 경로는 `<a>` 요소가 없어 가드를 우회한다. 이런 인페이지 이동 버튼은 `navigate()`를
 *   직접 호출하지 말고 이 훅이 반환하는 `requestNavigate(to)`를 통해서만 이동해야 한다 —
 *   dirty 상태면 다이얼로그를 띄우고, 아니면 즉시 navigate 한다.
 */
export function useUnsavedChangesGuard(
  isDirty: boolean | (() => boolean),
  options?: { onStay?: () => void },
) {
  const navigate = useNavigate();
  const location = useLocation();

  // 가로챈 이동 후보 — 사용자가 '이탈' 확정하면 이 곳으로 navigate
  const [pendingTo, setPendingTo] = useState<string | null>(null);
  // dialog 표시 여부
  const [open, setOpen] = useState(false);
  // 열림 여부를 동기적으로도 들고 있는다 (WD-642) — 저장 응답 시점의 열림 여부를 정확히 읽고(isDialogOpen, WD-662),
  // 「취소」 클릭이 onClick 과 Radix onOpenChange(false) 로 handleCancel 을 두 번 부르거나
  // 「이탈」 클릭 뒤 onOpenChange(false) 로 handleCancel 이 이어 불릴 때 이미 닫힌 뒤의 호출을 걸러 낸다.
  const openRef = useRef(false);
  const setOpenState = useCallback((next: boolean) => {
    openRef.current = next;
    setOpen(next);
  }, []);

  // 최신 isDirty 값(또는 판정 함수)을 핸들러에서 참조하기 위한 ref
  // (렌더 중 ref 대입은 이 저장소의 react-hooks/refs 규칙이 오류로 막아 effect 로 갱신한다 — WD-642)
  const isDirtyRef = useRef(isDirty);
  // (WD-651) 「이탈」을 확정해 라우터가 이 페이지에서 떠나는 중인지 여부 — 이 동안만 가드를 끈다.
  // 확정 뒤 다음 화면(지연 로딩 청크)이 준비될 때까지 이 페이지가 마운트된 채 남는다. react-router v7 BrowserRouter 는
  // location 갱신을 startTransition 안에서 하므로, 그동안 이 페이지의 useLocation 은 옛 경로에 머물고
  // window.location 은 이미 새 경로다. 이 틈에 가드가 살아 있으면 브라우저 뒤로 가기를 전역 popstate 인터셉터가 잡아
  // 기록을 하나 더 넣고 다이얼로그를 열어(곧 언마운트돼 사라짐) 주소와 화면이 어긋난다.
  // 그래서 확정 즉시 가드를 끄고(언마운트를 기다리지 않음), 탐색이 이 페이지로 다시 정착해 커밋된 location 과
  // window.location 이 같아지면(settleLeaving) 또는 폼이 깨끗해지면 다시 켠다.
  const leavingRef = useRef(false);

  // 이 컴포넌트가 커밋한 라우터 location. `location`은 렌더 시점 스냅샷이라 이벤트 핸들러(popstate 인터셉터·click 캡처 등)는
  // ref 로 최신 값을 참조한다.
  const locationRef = useRef(location);
  // 이 페이지(커밋된 location)의 경로 — pathname + search + hash. restorePath·click 캡처·settleLeaving 이 함께 쓴다.
  const currentPath = useCallback(
    () => locationRef.current.pathname + locationRef.current.search + locationRef.current.hash,
    [],
  );

  // (WD-651) 커밋된 location 과 window.location 이 같아졌으면(이 페이지로 정착) 이탈 중 상태를 끝낸다 — leavingRef 설명 참고.
  // (WD-673) 한글·공백이 든 경로·쿼리는 한쪽이 원문, 다른 쪽이 퍼센트 인코딩(`+`/`%20` 포함)이면 문자열로는 영영 같지 않아
  // 가드가 다시 켜지지 않았다 — 표기 차이를 정규화해 비교한다(lib/location-path).
  const settleLeaving = useCallback(() => {
    if (!leavingRef.current) return;
    const { pathname, search, hash } = window.location;
    if (isSameLocationPath(currentPath(), pathname + search + hash)) leavingRef.current = false;
  }, [currentPath]);

  // (WD-673) popstate 경로 「이탈」의 뒤로 가기 대기(popstate 리스너·제한 시간 타이머) 정리 함수 — 언마운트 때도 부른다.
  const pendingBackCleanupRef = useRef<(() => void) | null>(null);
  useEffect(() => () => pendingBackCleanupRef.current?.(), []);

  useEffect(() => {
    isDirtyRef.current = isDirty;
    // (WD-651) 폼이 깨끗해지면(같은 컴포넌트가 마운트된 채 새 대상으로 바뀐 경우 등) 가드를 다시 켠다.
    // boolean 으로 넘긴 경우만 여기서 처리한다. 판정 함수(WD-642)는 참조가 바뀌지 않으면 이 effect 가 폼 상태를
    // 따라가지 못하므로, 그 경우의 재무장은 guardActive 가 판정 시점에 맡는다(아래 guardActive 참고).
    if (isDirty === false) leavingRef.current = false;
  }, [isDirty]);
  // (WD-662) 남기 콜백도 최신 것을 ref 로 들고 있어, 페이지가 매 렌더 새 함수를 넘겨도 handleCancel 이 다시 만들어지지 않게 한다.
  const onStayRef = useRef(options?.onStay);
  useEffect(() => {
    onStayRef.current = options?.onStay;
  }, [options?.onStay]);
  // (WD-642) isDirty 로 함수를 넘기면 이동 시도·beforeunload 때에만 불러 판정한다 —
  // 매 렌더마다 폼 전체를 비교하지 않고, 필요한 순간에 최신 값으로 판단하게 한다.
  const readDirty = useCallback(() => {
    const value = isDirtyRef.current;
    return typeof value === 'function' ? value() : value;
  }, []);

  // 커밋된 location 을 ref 에 반영한다. (WD-651) 같은 요소에 새 location 이 커밋되면(같은 페이지로 push·새 id·리다이렉트) settleLeaving.
  useEffect(() => {
    locationRef.current = location;
    settleLeaving();
  }, [location, settleLeaving]);

  // (WD-651) 가드 세 곳(popstate 레지스트리·click 캡처·beforeunload)과 requestNavigate 가 함께 쓰는 판정.
  // dirty 이면서 아직 「이탈」을 확정하지 않았을 때만 가로챈다. dirty 는 readDirty 로 읽어 판정 함수도 지원한다 (WD-642).
  // (WD-651·WD-642) 판정 시점에 폼이 깨끗하면 이탈 중 상태도 함께 끝낸다 — 판정 함수를 넘긴 경우엔 [isDirty] effect 가
  // 폼이 깨끗해진 것을 알 수 없으므로, 가드가 실제로 쓰이는 순간(이동 시도·beforeunload·popstate)에 재무장해 둔다.
  // 깨끗한 동안은 어차피 가로채지 않으므로 이 시점의 해제는 동작을 바꾸지 않고, 이후 다시 dirty 가 되면 곧바로 가드가 켜진다.
  const guardActive = useCallback(() => {
    if (!readDirty()) {
      leavingRef.current = false;
      return false;
    }
    return !leavingRef.current;
  }, [readDirty]);

  // (1) document click 캡처 — 사이드바/AppLayout 등 모든 <a href> 클릭을 가로챈다.
  useEffect(() => {
    const handler = (e: MouseEvent) => {
      // 수정자 키(Cmd/Ctrl/Shift/Alt) 또는 우클릭은 새 탭 등 외부 의도이므로 그대로 둔다.
      if (e.defaultPrevented) return;
      if (e.button !== 0) return;
      if (e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;

      // 클릭 경로에서 <a> 요소 탐색
      const path = e.composedPath();
      const anchor = path.find(
        (n): n is HTMLAnchorElement => n instanceof HTMLAnchorElement && !!n.href,
      );
      if (!anchor) return;
      // target="_blank" 등 외부 이동은 가드하지 않음
      if (anchor.target && anchor.target !== '' && anchor.target !== '_self') return;
      // 다운로드 링크 무시
      if (anchor.hasAttribute('download')) return;

      // 절대 URL → 동일 origin인 경우만 SPA 이동으로 간주
      let url: URL;
      try {
        url = new URL(anchor.href, window.location.href);
      } catch {
        return;
      }
      if (url.origin !== window.location.origin) return;

      const targetPath = url.pathname + url.search + url.hash;
      // 같은 경로(다른 search·hash 포함)면 통과 — pathname 동일 시만 통과로 한정한다.
      if (url.pathname === locationRef.current.pathname) return;
      // 가드 판정(dirty·이탈 중 여부)은 가로챌 링크 이동임이 확인된 뒤에만 한다 (WD-662·WD-651) — 판정 함수가 폼 전체를
      // 비교하므로 화면의 모든 클릭(버튼·입력 칸 등)마다 비교하지 않게 한다. 결과는 이전과 같다.
      if (!guardActive()) return;

      // 이동 가로채기
      e.preventDefault();
      e.stopPropagation();
      setPendingTo(targetPath);
      setOpenState(true);
    };

    // capture 단계에서 처리해 react-router의 Link onClick보다 먼저 동작하게 한다.
    document.addEventListener('click', handler, true);
    return () => document.removeEventListener('click', handler, true);
    // 경로는 locationRef 로 읽고 판정·열기는 안정 콜백(guardActive·setOpenState)이라 마운트당 한 번만 구독한다 (WD-651·WD-642).
  }, [guardActive, setOpenState]);

  // (2) popstate 가로채기 — `unsaved-changes-guard-registry`의 전역 인터셉터(main.tsx에서 1회
  // 설치, 라우터보다 먼저 등록됨)에 현재 페이지를 "활성 가드"로 등록해둔다. 실제 popstate 처리는
  // 그 인터셉터가 담당하며, dirty일 때 라우터로의 전파를 막고(stopImmediatePropagation) URL을
  // 원복한 뒤 `onIntercepted`로 이 다이얼로그를 연다. 원복 경로는 locationRef(currentPath)로
  // popstate 발생 시점의 최신 경로를 참조한다.
  useEffect(() => {
    const unregister = registerActiveGuard({
      isDirty: guardActive,
      restorePath: currentPath,
      onIntercepted: () => {
        // pendingTo는 popstate에서는 알 수 없으므로 확정 시 history.back()을 재실행해야 한다
        // (handleConfirm 참고). 단, 다이얼로그는 즉시 표시해 사용자에게 dirty 상태임을 알린다.
        setPendingTo(null);
        setOpenState(true);
      },
    });
    return unregister;
  }, [guardActive, currentPath, setOpenState]);

  // (2-1) (WD-651) 같은 기록 항목으로 뒤로 가기하면 커밋된 location 이 바뀌지 않아 위 이펙트가 돌지 않으므로 popstate 에서 settleLeaving.
  // 이 리스너는 페이지 마운트 시점에 등록돼 항상 전역 인터셉터·라우터보다 나중에 실행된다(#562 참고) — 그래서 이 뒤로 가기
  // 자체는 이미 가드 없이 통과된 뒤에 재무장된다(먼저 실행됐다면 바로 이 뒤로 가기를 다시 가로챘을 것이다).
  useEffect(() => {
    window.addEventListener('popstate', settleLeaving);
    return () => window.removeEventListener('popstate', settleLeaving);
  }, [settleLeaving]);

  // (3) 브라우저 탭 닫기·새로고침 가드
  useEffect(() => {
    const handler = (e: BeforeUnloadEvent) => {
      if (guardActive()) e.preventDefault();
    };
    window.addEventListener('beforeunload', handler);
    return () => window.removeEventListener('beforeunload', handler);
  }, [guardActive]);

  // (이슈 #634) 페이지 내 "뒤로가기" 등 프로그래매틱 이동 버튼용 헬퍼.
  // dirty면 <a> 클릭 가로채기와 동일하게 다이얼로그를 띄우고, 아니면 바로 navigate 한다.
  // 페이지는 navigate()를 직접 호출하는 대신 이 함수를 통해서만 이동해야 click 캡처의
  // <a> 탐지 사각지대를 우회하지 않는다.
  const requestNavigate = useCallback(
    (to: string) => {
      if (!guardActive()) {
        navigate(to);
        return;
      }
      setPendingTo(to);
      setOpenState(true);
    },
    [navigate, guardActive, setOpenState],
  );

  const handleCancel = useCallback(() => {
    const wasOpen = openRef.current;
    setOpenState(false);
    setPendingTo(null);
    // (WD-662) 사용자가 남기를 골랐음을 페이지에 한 번 알린다(예: 확인창이 떠 있는 동안 끝난 저장의 결과 화면으로 이동).
    // 「취소」 클릭은 onClick 과 Radix onOpenChange(false) 로 두 번 들어오고, 「이탈」 뒤에도 onOpenChange(false) 가 이어지므로
    // 이미 닫힌 뒤의 호출은 wasOpen 으로 거른다.
    if (wasOpen) onStayRef.current?.();
  }, [setOpenState]);

  // 「취소」는 이 페이지에 남는 것이므로 leavingRef 를 건드리지 않는다(가드 유지).
  const handleConfirm = useCallback(() => {
    // (WD-651) 이탈 확정 즉시 가드를 끈다(leavingRef 설명 참고). popstate 경로(history.back())여도 같다.
    // (WD-673) popstate 경로에서 history.back() 이 아무 일도 하지 않으면(되돌아갈 기록이 없는 드문 경우) 주소가 바뀌지 않아
    // settleLeaving 이 불리지 않는다 — 아래 else 분기가 제한 시간 안에 popstate 가 없으면 가드를 다시 켠다.
    leavingRef.current = true;
    setOpenState(false);
    const to = pendingTo;
    setPendingTo(null);
    if (to) {
      // 다이얼로그 닫힘 → 다음 tick에 navigate 호출 (애니메이션 충돌 방지)
      setTimeout(() => navigate(to), 0);
    } else {
      // pendingTo가 없는 경우 = popstate(뒤로/앞으로) 가로채기로 열린 다이얼로그.
      // 인터셉터가 이번 이동을 막아둔 상태이므로, 사용자가 "이탈"을 확정하면
      // 다음 popstate 1회는 가드를 우회하도록 표시한 뒤 실제 뒤로가기를 재실행한다.
      setTimeout(() => {
        pendingBackCleanupRef.current?.();
        const cancelBypass = bypassNextPopstateGuard();
        // (WD-673) 뒤로 가기가 실제로 일어났는지 popstate 한 번으로 확인한다. BACK_NOOP_REARM_MS 안에 오지 않으면
        // 아무 데도 가지 않은 것이므로 가드를 다시 켜고, 쓰이지 않은 우회 표시도 거둔다 — 남겨 두면 다음의 진짜 뒤로 가기가
        // 확인 없이 통과된다. popstate 가 오면(이동함) 기다림만 끝내고 재무장은 기존 settleLeaving 규칙에 맡긴다.
        // 다른 문서로 가는 뒤로 가기는 popstate 없이 문서가 바뀐다 — 그 문서가 늦게 떠 이 타이머가 먼저 돌아도, 새로고침 가드
        // (beforeunload)는 이미 가드가 꺼진 채 지나갔으므로 확인 창이 끼어들지 않는다.
        const finish = () => {
          window.clearTimeout(timer);
          window.removeEventListener('popstate', finish);
          pendingBackCleanupRef.current = null;
        };
        const timer = window.setTimeout(() => {
          finish();
          cancelBypass();
          leavingRef.current = false;
        }, BACK_NOOP_REARM_MS);
        window.addEventListener('popstate', finish);
        pendingBackCleanupRef.current = () => {
          finish();
          cancelBypass();
        };
        window.history.back();
      }, 0);
    }
  }, [navigate, pendingTo, setOpenState]);

  // ESC 등 외부 dismiss는 취소로 처리
  const handleOpenChange = useCallback(
    (next: boolean) => {
      if (!next) handleCancel();
    },
    [handleCancel],
  );

  const dialog = (
    <AlertDialog open={open} onOpenChange={handleOpenChange}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>저장하지 않은 변경사항</AlertDialogTitle>
          <AlertDialogDescription>
            저장하지 않은 변경사항이 있습니다. 이탈하시겠습니까?
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel onClick={handleCancel}>취소</AlertDialogCancel>
          <AlertDialogAction onClick={handleConfirm}>이탈</AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );

  // (WD-662) 확인창이 지금 떠 있는가 — 동기 갱신되는 openRef 를 읽어, 렌더 사이(예: 저장 응답 시점)에도 정확하다.
  // 저장 후 어디로 갈지 같은 화면별 판단은 페이지가 이것과 onStay 로 직접 한다 (공용 훅은 일반 기능만 둔다).
  const isDialogOpen = useCallback(() => openRef.current, []);

  return { dialog, requestNavigate, isDialogOpen };
}

/**
 * dirty 상태를 외부로 보고하는 컴포넌트(예: SmtpSettingsTab)에서
 * 부모(SettingsPage)에 dirty 여부를 알릴 때 쓰는 시그니처.
 */
export type ReportDirty = (dirty: boolean) => void;


/**
 * 여러 자식의 dirty 상태를 합산하는 헬퍼 훅.
 * 각 자식이 makeReporter 로 받은 함수로 보고한 dirty 상태를 모아 OR-합으로 return.
 */
export function useDirtyAggregator() {
  const [dirtyMap, setDirtyMap] = useState<Record<string, boolean>>({});
  // 동일 key에 대해 동일 함수 참조를 유지하여 소비자의 보고 effect 가 매 렌더 재실행되는 것을 방지한다.
  const reportersRef = useRef<Record<string, ReportDirty>>({});

  const makeReporter = useCallback((key: string): ReportDirty => {
    const cached = reportersRef.current[key];
    if (cached) return cached;
    const reporter: ReportDirty = (dirty: boolean) => {
      setDirtyMap((prev) => {
        if ((prev[key] ?? false) === dirty) return prev;
        return { ...prev, [key]: dirty };
      });
    };
    reportersRef.current[key] = reporter;
    return reporter;
  }, []);

  const isAnyDirty = Object.values(dirtyMap).some(Boolean);
  return { isAnyDirty, makeReporter };
}
