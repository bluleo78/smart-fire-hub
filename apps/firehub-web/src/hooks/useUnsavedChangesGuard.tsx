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
import { bypassNextPopstateGuard, registerActiveGuard } from '../lib/unsaved-changes-guard-registry';

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
 *   const { dialog } = useUnsavedChangesGuard(isDirty);
 *   return <>{...page UI}{dialog}</>;
 */
export function useUnsavedChangesGuard(isDirty: boolean) {
  const navigate = useNavigate();
  const location = useLocation();

  // 가로챈 이동 후보 — 사용자가 '이탈' 확정하면 이 곳으로 navigate
  const [pendingTo, setPendingTo] = useState<string | null>(null);
  // dialog 표시 여부
  const [open, setOpen] = useState(false);

  // 최신 isDirty 값을 핸들러에서 참조하기 위한 ref
  const isDirtyRef = useRef(isDirty);
  useEffect(() => {
    isDirtyRef.current = isDirty;
  }, [isDirty]);

  // (1) document click 캡처 — 사이드바/AppLayout 등 모든 <a href> 클릭을 가로챈다.
  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (!isDirtyRef.current) return;
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
      const currentPath = location.pathname + location.search + location.hash;
      if (targetPath === currentPath) return;
      // 같은 경로(다른 hash)면 통과 — pathname 동일 시만 통과로 한정한다.
      if (url.pathname === location.pathname) return;

      // 이동 가로채기
      e.preventDefault();
      e.stopPropagation();
      setPendingTo(targetPath);
      setOpen(true);
    };

    // capture 단계에서 처리해 react-router의 Link onClick보다 먼저 동작하게 한다.
    document.addEventListener('click', handler, true);
    return () => document.removeEventListener('click', handler, true);
  }, [location.pathname, location.search, location.hash]);

  // (2) popstate 가로채기 — `unsaved-changes-guard-registry`의 전역 인터셉터(main.tsx에서 1회
  // 설치, 라우터보다 먼저 등록됨)에 현재 페이지를 "활성 가드"로 등록해둔다. 실제 popstate 처리는
  // 그 인터셉터가 담당하며, dirty일 때 라우터로의 전파를 막고(stopImmediatePropagation) URL을
  // 원복한 뒤 `onIntercepted`로 이 다이얼로그를 연다. `location`은 렌더 시점 스냅샷이라 ref로
  // 감싸 popstate 발생 시점의 최신 경로를 참조한다.
  const locationRef = useRef(location);
  useEffect(() => {
    locationRef.current = location;
  }, [location]);

  useEffect(() => {
    const unregister = registerActiveGuard({
      isDirty: () => isDirtyRef.current,
      restorePath: () =>
        locationRef.current.pathname + locationRef.current.search + locationRef.current.hash,
      onIntercepted: () => {
        // pendingTo는 popstate에서는 알 수 없으므로 확정 시 history.back()을 재실행해야 한다
        // (handleConfirm 참고). 단, 다이얼로그는 즉시 표시해 사용자에게 dirty 상태임을 알린다.
        setPendingTo(null);
        setOpen(true);
      },
    });
    return unregister;
  }, []);

  // (3) 브라우저 탭 닫기·새로고침 가드
  useEffect(() => {
    const handler = (e: BeforeUnloadEvent) => {
      if (isDirtyRef.current) e.preventDefault();
    };
    window.addEventListener('beforeunload', handler);
    return () => window.removeEventListener('beforeunload', handler);
  }, []);

  const handleCancel = useCallback(() => {
    setOpen(false);
    setPendingTo(null);
  }, []);

  const handleConfirm = useCallback(() => {
    setOpen(false);
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
        bypassNextPopstateGuard();
        window.history.back();
      }, 0);
    }
  }, [navigate, pendingTo]);

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

  return { dialog };
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
