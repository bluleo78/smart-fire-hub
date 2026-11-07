/**
 * 탭 제목 덮어쓰기 저장소 (WD-580).
 *
 * URL 은 그대로인데 라우트와 다른 화면을 그리는 경우가 있다 — 테넌트 미선택이면 ProtectedRoute 가 워크스페이스 선택 화면을,
 * 화면 권한이 없으면 AdminOrPermissionRoute 가 권한 안내를 그 자리에서 그린다. 경로만 보는 제목은 이때 엉뚱한 화면 이름이 된다.
 * 그런 컴포넌트가 마운트돼 있는 동안 화면 이름을 덮어쓰고, 언마운트되면 풀어 준다.
 *
 * 모듈 수준 저장소 + useSyncExternalStore 로 두는 이유: PageTitleSync 가 경로·브랜드·덮어쓰기를 한 렌더에서 함께 읽어
 * 제목을 한 번에 계산하므로, 브랜드·경로 변경으로 PageTitleSync 가 다시 돌아도 덮어쓰기를 지우는 effect 순서 경합이 없다.
 * 여럿이 동시에 걸리면 가장 나중에 건 것이 이긴다(안쪽 컴포넌트의 effect 가 나중에 돈다).
 */

interface OverrideEntry {
  label: string;
}

let entries: OverrideEntry[] = [];
const listeners = new Set<() => void>();

function emit() {
  listeners.forEach((listener) => listener());
}

/** 덮어쓰기를 걸고, 해제 함수를 돌려준다 — useEffect cleanup 으로 그대로 쓸 수 있다. */
export function pushPageTitleOverride(label: string): () => void {
  const entry: OverrideEntry = { label };
  entries = [...entries, entry];
  emit();
  return () => {
    // 같은 label 을 건 다른 컴포넌트가 있어도 자기 항목만 지우도록 참조로 비교한다
    entries = entries.filter((e) => e !== entry);
    emit();
  };
}

/** 현재 유효한 덮어쓰기 화면 이름(없으면 null) — useSyncExternalStore 의 getSnapshot */
export function getPageTitleOverride(): string | null {
  return entries.length > 0 ? entries[entries.length - 1].label : null;
}

/** useSyncExternalStore 의 subscribe */
export function subscribePageTitleOverride(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}
