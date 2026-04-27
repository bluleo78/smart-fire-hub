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

/**
 * useUnsavedChangesGuard
 * - 폼이 dirty 상태일 때 SPA 라우터 이동(사이드바 클릭 등)·브라우저 탭 닫기·새로고침을 가로채
 *   사용자가 작업한 변경값이 무경고로 유실되는 것을 방지한다.
 * - 본 프로젝트는 `BrowserRouter` (legacy router) 를 사용해 react-router v7의 `useBlocker`를 쓸 수 없다.
 *   대신 다음 두 가지로 가드를 구현한다:
 *   1. document 레벨 click capture 핸들러로 `<a>` 클릭(사이드바·메뉴 등 SPA 링크)을 가로채
 *      AlertDialog 노출 후 사용자 결정에 따라 navigate 호출.
 *   2. `popstate`(브라우저 뒤로/앞으로) 가로채기 — history.pushState로 현재 URL 재고정 후 다이얼로그 노출.
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

  // (2) popstate 가로채기 — 브라우저 뒤로/앞으로 시 현재 URL을 다시 push해 위치를 고정한 뒤 dialog
  useEffect(() => {
    const onPop = () => {
      if (!isDirtyRef.current) return;
      // 현재 URL로 다시 push해 위치를 원복
      const here = location.pathname + location.search + location.hash;
      window.history.pushState(null, '', here);
      // pendingTo는 popstate에서는 알 수 없으므로 사용자가 다시 시도해야 한다.
      // 단, 다이얼로그는 한 번 표시해 사용자에게 dirty 상태임을 알린다.
      setPendingTo(null);
      setOpen(true);
    };
    window.addEventListener('popstate', onPop);
    return () => window.removeEventListener('popstate', onPop);
  }, [location.hash, location.pathname, location.search]);

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
 * 자식 컴포넌트가 자체 dirty 상태를 부모에 보고할 때 onReportDirty를 호출한다.
 * 이 헬퍼는 단순한 useEffect 래퍼로, 컴포넌트 본문에서 dirty 변화 시 자동 보고한다.
 */
export function useReportDirty(isDirty: boolean, onReportDirty?: ReportDirty) {
  useEffect(() => {
    onReportDirty?.(isDirty);
    // 언마운트 시 dirty=false로 클리어 (탭 전환·페이지 이탈 시 유령 dirty 방지)
    return () => onReportDirty?.(false);
  }, [isDirty, onReportDirty]);
}

/**
 * 여러 자식의 dirty 상태를 합산하는 헬퍼 훅.
 * 각 자식이 useReportDirty로 보고한 dirty 상태를 모아 OR-합으로 return.
 */
export function useDirtyAggregator() {
  const [dirtyMap, setDirtyMap] = useState<Record<string, boolean>>({});
  // 동일 key에 대해 동일 함수 참조를 유지하여 useReportDirty의 effect가 매 렌더 재실행되는 것을 방지한다.
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
