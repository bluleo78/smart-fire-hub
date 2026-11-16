import { AlertTriangle, CheckCircle2, Copy } from 'lucide-react';
import { useEffect, useRef } from 'react';

import { Button } from '@/components/ui/button';
import { DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { InlineBanner } from '@/components/ui/inline-banner';
import { copyText } from '@/lib/clipboard';

interface CredentialsResultProps {
  /** 대화상자 제목(예: 「계정을 만들었습니다」). */
  title: string;
  /** 화면에는 숨기고 보조기기에만 읽히는 설명. */
  description: string;
  /** 경고 배너 제목 — 비밀번호를 다시 볼 수 없다는 안내. */
  warningTitle: string;
  /** 아이디 줄의 이름(예: 「아이디」「이메일」). 함께 복사 버튼 문구·복사 내용에도 쓴다. */
  idLabel: string;
  /** 로그인 아이디 값. */
  idValue: string;
  /** 방금 쓴 임시 비밀번호. 이 화면이 닫히면(언마운트) 버려진다. */
  password: string;
  /** 완료 버튼 문구. */
  finishLabel: string;
  /** 완료 버튼 — 이 버튼만이 결과 화면을 닫는다. */
  onFinish: () => void;
}

/**
 * 새 계정의 자격 증명을 한 번만 보여 주는 대화상자 결과 화면(WD-750 에서 공용으로 뽑음). 운영자 계정 생성(`CreateAccountDialog`)과
 * 테넌트 멤버 추가(`AddTenantMemberDialog`)가 함께 쓴다 — 경고·복사 동작이 두 곳에서 갈라지지 않게 한다.
 *
 * `DialogContent` 안에 헤더·본문·푸터를 그린다. 닫기 막기(X 숨김·바깥 클릭·Esc)는 대화상자 쪽 책임이다.
 */
export function CredentialsResult({
  title,
  description,
  warningTitle,
  idLabel,
  idValue,
  password,
  finishLabel,
  onFinish,
}: CredentialsResultProps) {
  const copyAllRef = useRef<HTMLButtonElement | null>(null);

  // 결과 화면으로 바뀌면 제출 버튼이 사라져 포커스가 컨테이너로 떨어진다 — 다음 행동(복사)으로 옮긴다.
  useEffect(() => {
    copyAllRef.current?.focus();
  }, []);

  return (
    <>
      <DialogHeader>
        <DialogTitle className="flex items-center gap-2">
          <CheckCircle2 className="h-5 w-5 text-success" aria-hidden="true" />
          {title}
        </DialogTitle>
        <DialogDescription className="sr-only">{description}</DialogDescription>
      </DialogHeader>
      <div className="min-h-0 flex-1 space-y-4 overflow-y-auto">
        <InlineBanner variant="warning" icon={<AlertTriangle />} title={warningTitle}>
          <span className="break-keep">지금 복사해 본인에게 직접 전달하세요. 첫 로그인 시 비밀번호를 바꿔야 합니다.</span>
        </InlineBanner>
        <dl className="grid grid-cols-[auto_1fr] items-center gap-x-4 gap-y-2 text-sm">
          <dt className="text-muted-foreground">{idLabel}</dt>
          <dd className="min-w-0">
            <code className="rounded bg-muted px-1.5 py-0.5 font-mono text-sm break-all select-all">{idValue}</code>
          </dd>
          <dt className="text-muted-foreground">임시 비밀번호</dt>
          <dd className="min-w-0">
            <code className="rounded bg-muted px-1.5 py-0.5 font-mono text-sm break-all select-all">{password}</code>
          </dd>
        </dl>
        <Button
          ref={copyAllRef}
          type="button"
          variant="outline"
          size="sm"
          onClick={() => void copyText(`${idLabel}: ${idValue}\n임시 비밀번호: ${password}`)}
        >
          <Copy className="h-4 w-4" aria-hidden="true" />
          {`${idLabel}·비밀번호 함께 복사`}
        </Button>
      </div>
      <DialogFooter>
        <Button type="button" onClick={onFinish}>
          {finishLabel}
        </Button>
      </DialogFooter>
    </>
  );
}
