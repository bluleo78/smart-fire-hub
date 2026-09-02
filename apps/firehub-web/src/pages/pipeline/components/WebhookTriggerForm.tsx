import { AlertTriangle,Check, Copy } from 'lucide-react';
import { useId, useState } from 'react';

import { Button } from '@/components/ui/button';
import { InlineBanner } from '@/components/ui/inline-banner';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';

interface WebhookTriggerFormProps {
  config: {
    webhookId?: string;
    secret?: string;
  };
  onChange: (config: WebhookTriggerFormProps['config']) => void;
  isEditMode?: boolean;
  errors?: Record<string, string>;
}

export default function WebhookTriggerForm({ config, onChange, isEditMode, errors }: WebhookTriggerFormProps) {
  const [copied, setCopied] = useState(false);
  // 접근성: 라벨↔입력 연결용 id 접두사 (#432). 추가/수정 다이얼로그 양쪽에서 렌더되므로 useId.
  const baseId = useId();
  const urlLabelId = `${baseId}-url-label`;
  const secretId = `${baseId}-secret`;
  const secretNoteId = `${baseId}-secret-note`;
  const secretErrorId = `${baseId}-secret-error`;

  const webhookUrl = config.webhookId
    ? `${window.location.origin}/api/v1/triggers/webhook/${config.webhookId}`
    : '(생성 후 URL이 표시됩니다)';

  const handleCopy = async () => {
    if (config.webhookId) {
      await navigator.clipboard.writeText(webhookUrl);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    }
  };

  return (
    <div className="space-y-4">
      {config.webhookId && (
        <div className="space-y-1.5">
          {/*
            읽기 전용 code 표시라 대응하는 입력 요소가 없다 — Label 컴포넌트 대신 span 으로 둔다 (#432).
            표시 영역에 role="group" + aria-labelledby 를 걸어 스크린리더가 이름을 읽게 한다.
            className 은 shadcn Label 기본 스타일을 그대로 옮겨 시각 결과를 유지한다.
          */}
          <span
            id={urlLabelId}
            className="flex items-center gap-2 text-sm leading-none font-medium select-none"
          >
            웹훅 URL
          </span>
          <div className="flex gap-2" role="group" aria-labelledby={urlLabelId}>
            <code className="flex-1 bg-muted px-3 py-2 rounded-md text-sm font-mono break-all">
              {webhookUrl}
            </code>
            <Button
              type="button"
              variant="outline"
              size="icon"
              onClick={handleCopy}
            >
              {copied ? <Check className="h-4 w-4" /> : <Copy className="h-4 w-4" />}
            </Button>
          </div>
        </div>
      )}

      <div className="space-y-1.5">
        <Label htmlFor={secretId}>시크릿 키 (선택)</Label>
        {!isEditMode && (
          <InlineBanner icon={<AlertTriangle />} className="mb-2">
            시크릿 키는 생성 시 한 번만 설정할 수 있습니다. 이후에는 다시 볼 수 없습니다.
          </InlineBanner>
        )}
        <Input
          id={secretId}
          type="password"
          value={config.secret ?? ''}
          onChange={(e) => onChange({ ...config, secret: e.target.value })}
          placeholder="HMAC-SHA256 서명 검증에 사용할 시크릿 키"
          disabled={isEditMode}
          /* 수정 모드 안내문과 오류 문구를 조건부로 연결한다 */
          aria-describedby={
            [isEditMode ? secretNoteId : null, errors?.secret ? secretErrorId : null]
              .filter(Boolean)
              .join(' ') || undefined
          }
          aria-invalid={!!errors?.secret}
        />
        {isEditMode && (
          <p id={secretNoteId} className="text-xs text-muted-foreground">
            시크릿 키는 수정할 수 없습니다. 변경이 필요하면 트리거를 삭제 후 재생성하세요.
          </p>
        )}
        {errors?.secret && (
          <p id={secretErrorId} className="text-sm text-destructive">{errors.secret}</p>
        )}
      </div>

      <div className="text-xs text-muted-foreground space-y-1">
        <p>시크릿 키를 설정하면 수신 시 <code className="bg-muted px-1 rounded">X-Hub-Signature</code> 헤더로 HMAC-SHA256 서명을 검증합니다.</p>
      </div>
    </div>
  );
}
