import { useState } from 'react';

import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { copyText } from '@/lib/clipboard';

interface DataStoreScriptDialogProps {
  /** null 이면 닫힘. 스크립트는 비밀번호를 담고 있어 이 컴포넌트 밖에 보관하지 않는다. */
  script: string | null;
  /** "확인" 을 눌렀을 때. 생성 흐름은 상세로 이동, 재발급 흐름은 그냥 닫는다. */
  onConfirm: () => void;
}

/**
 * DBA 실행용 저장소 설정 스크립트를 **한 번만** 보여 주는 대화상자(R2).
 *
 * 스크립트에는 계정 비밀번호가 들어 있고 서버는 다시 보여 주지 않는다. 그래서 바깥 클릭·ESC·닫기 X 로
 * 닫히지 않게 하고 "확인" 만 닫는 경로로 둔다 — 복사하기 전에 실수로 닫아 버리면 재발급(비밀번호 교체)
 * 밖에 방법이 없다. 생성 직후와 상세의 재발급이 같은 컴포넌트를 쓰며, 이동 여부는 `onConfirm` 이 정한다.
 */
export function DataStoreScriptDialog({ script, onConfirm }: DataStoreScriptDialogProps) {
  const [copied, setCopied] = useState(false);

  const copy = async () => {
    if (!script) return;
    // 실패하면 클립보드 권한이 없는 환경 — 스크립트는 다시 볼 수 없으므로 본문을 직접 선택해 복사하도록 안내한다.
    const ok = await copyText(
      script,
      '스크립트를 복사했습니다.',
      '복사하지 못했습니다. 스크립트를 직접 선택해 복사하세요.',
    );
    if (ok) setCopied(true);
  };

  return (
    <Dialog
      open={script !== null}
      // 닫힘 요청은 전부 무시한다 — 오직 "확인" 버튼만 닫는다.
      onOpenChange={() => undefined}
    >
      <DialogContent
        showCloseButton={false}
        className="sm:max-w-2xl"
        onInteractOutside={(e) => e.preventDefault()}
        onEscapeKeyDown={(e) => e.preventDefault()}
      >
        <DialogHeader>
          <DialogTitle>저장소 설정 스크립트</DialogTitle>
          <DialogDescription>
            DBA 계정으로 이 스크립트를 실행하면 테넌트 전용 계정이 만들어집니다.
          </DialogDescription>
        </DialogHeader>
        <p className="text-sm font-medium text-destructive">
          이 스크립트는 다시 표시되지 않습니다. 잃어버리면 테넌트 상세에서 재발급하세요.
        </p>
        <pre
          role="region"
          aria-label="스크립트 본문"
          tabIndex={0}
          className="max-h-80 overflow-auto rounded-md border bg-muted p-3 font-mono text-[13px] whitespace-pre-wrap break-all"
        >
          {script}
        </pre>
        <DialogFooter>
          <Button type="button" variant="outline" onClick={() => void copy()}>
            {copied ? '복사됨' : '복사'}
          </Button>
          <Button
            type="button"
            onClick={() => {
              setCopied(false);
              onConfirm();
            }}
          >
            확인
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
