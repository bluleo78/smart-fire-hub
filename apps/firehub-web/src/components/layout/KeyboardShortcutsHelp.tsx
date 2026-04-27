import { useEffect, useState } from 'react';

import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '../ui/dialog';

/**
 * 전역 단축키 안내 다이얼로그 (#100).
 * - "?" 키 입력 시 표시 (단, 입력 요소에 포커스가 있거나 modifier 키가 함께 눌리면 무시).
 * - Esc로 닫힘 (Radix Dialog 기본 동작).
 *
 * Cmd/Ctrl+K는 AI 채팅 토글에 이미 사용 중이므로 별도 명령 팔레트는 도입하지 않는다.
 * (후속 이슈로 명령 팔레트와 단축키 충돌 정책 분리 권고)
 */
export function KeyboardShortcutsHelp() {
  const [open, setOpen] = useState(false);

  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      // 입력 요소 포커스 시 무시 — input/textarea/contenteditable 입력에서 "?" 타이핑 방해 방지
      const target = e.target as HTMLElement | null;
      if (target) {
        const tag = target.tagName;
        if (
          tag === 'INPUT' ||
          tag === 'TEXTAREA' ||
          tag === 'SELECT' ||
          target.isContentEditable
        ) {
          return;
        }
      }
      // modifier 키 조합은 다른 단축키로 예약되어 있을 수 있으므로 단독 "?"만 처리
      if (e.metaKey || e.ctrlKey || e.altKey) {
        return;
      }
      // Shift+/ → "?" — 키보드 레이아웃 상관없이 안전하게 e.key === '?' 비교
      if (e.key === '?') {
        e.preventDefault();
        setOpen((prev) => !prev);
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, []);

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>키보드 단축키</DialogTitle>
          <DialogDescription>
            앱 어디에서나 사용할 수 있는 단축키 목록입니다.
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-3 text-sm">
          <ShortcutRow keys={['?']} description="이 도움말 토글" />
          <ShortcutRow keys={['Cmd/Ctrl', 'K']} description="AI 채팅 패널 열기/닫기" />
          <ShortcutRow keys={['Cmd/Ctrl', 'S']} description="현재 폼 저장 (지원 페이지: 데이터셋 생성)" />
          <ShortcutRow keys={['Esc']} description="다이얼로그/패널 닫기" />
        </div>
        <p className="text-xs text-muted-foreground">
          입력 필드에 포커스가 있을 때는 단축키가 비활성화됩니다.
        </p>
      </DialogContent>
    </Dialog>
  );
}

// 단축키 한 줄을 키 배지 + 설명으로 렌더 — 디자인 시스템 일관성을 위해 단순 inline 마크업 사용
function ShortcutRow({ keys, description }: { keys: string[]; description: string }) {
  return (
    <div className="flex items-center justify-between gap-3">
      <span className="text-foreground">{description}</span>
      <span className="flex items-center gap-1">
        {keys.map((k, i) => (
          <kbd
            key={i}
            className="inline-flex h-6 min-w-[1.5rem] items-center justify-center rounded border bg-muted px-1.5 font-mono text-xs text-muted-foreground"
          >
            {k}
          </kbd>
        ))}
      </span>
    </div>
  );
}
