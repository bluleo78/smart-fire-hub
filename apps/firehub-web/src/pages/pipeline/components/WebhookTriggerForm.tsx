import { useState } from 'react';
import { Copy, Check, AlertTriangle } from 'lucide-react';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Button } from '@/components/ui/button';

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
          <Label>웹훅 URL</Label>
          <div className="flex gap-2">
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
        <Label>시크릿 키 (선택)</Label>
        {!isEditMode && (
          <div className="rounded-md bg-amber-50 border border-amber-200 p-3 flex items-start gap-2 mb-2">
            <AlertTriangle className="h-4 w-4 text-amber-600 mt-0.5 shrink-0" />
            <p className="text-sm text-amber-800">
              시크릿 키는 생성 시 한 번만 설정할 수 있습니다. 이후에는 다시 볼 수 없습니다.
            </p>
          </div>
        )}
        <Input
          type="password"
          value={config.secret ?? ''}
          onChange={(e) => onChange({ ...config, secret: e.target.value })}
          placeholder="HMAC-SHA256 서명 검증에 사용할 시크릿 키"
          disabled={isEditMode}
        />
        {isEditMode && (
          <p className="text-xs text-muted-foreground">
            시크릿 키는 수정할 수 없습니다. 변경이 필요하면 트리거를 삭제 후 재생성하세요.
          </p>
        )}
        {errors?.secret && (
          <p className="text-sm text-destructive">{errors.secret}</p>
        )}
      </div>

      <div className="text-xs text-muted-foreground space-y-1">
        <p>시크릿 키를 설정하면 수신 시 <code className="bg-muted px-1 rounded">X-Hub-Signature</code> 헤더로 HMAC-SHA256 서명을 검증합니다.</p>
      </div>
    </div>
  );
}
