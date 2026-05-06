import { AlertTriangle,Check, Copy } from 'lucide-react';
import { useState } from 'react';

import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';

interface ApiTriggerFormProps {
  config: {
    allowedIps: string[];
  };
  onChange: (config: ApiTriggerFormProps['config']) => void;
  /** Set after creation to display the one-time token */
  generatedToken?: string;
  isEditMode?: boolean;
  errors?: Record<string, string>;
}

/**
 * IPv4 단독 주소 또는 IPv4 CIDR 표기법 검증 정규식.
 * 각 옥텟은 0~255, CIDR 프리픽스는 0~32만 허용.
 */
const IP_CIDR_REGEX =
  /^(25[0-5]|2[0-4]\d|1\d{2}|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d{2}|[1-9]?\d)){3}(\/([0-9]|[12]\d|3[0-2]))?$/;

export default function ApiTriggerForm({ config, onChange, generatedToken, isEditMode, errors }: ApiTriggerFormProps) {
  const [copied, setCopied] = useState(false);
  const [ipInput, setIpInput] = useState('');
  // IP 입력 값이 유효하지 않을 때 표시할 에러 메시지
  const [ipError, setIpError] = useState('');

  const handleCopy = async (text: string) => {
    await navigator.clipboard.writeText(text);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  const handleAddIp = () => {
    const ip = ipInput.trim();
    if (!ip) return;

    // IPv4 또는 IPv4/CIDR 형식 검증
    if (!IP_CIDR_REGEX.test(ip)) {
      setIpError('올바른 IPv4 주소 또는 CIDR 표기법을 입력하세요 (예: 192.168.1.1 또는 10.0.0.0/24)');
      return;
    }

    if (config.allowedIps.includes(ip)) {
      setIpError('이미 추가된 IP입니다');
      return;
    }

    setIpError('');
    onChange({ ...config, allowedIps: [...config.allowedIps, ip] });
    setIpInput('');
  };

  const handleRemoveIp = (ip: string) => {
    onChange({ ...config, allowedIps: config.allowedIps.filter((i) => i !== ip) });
  };

  const curlExample = generatedToken
    ? `curl -X POST ${window.location.origin}/api/v1/triggers/api/${generatedToken}`
    : '';

  return (
    <div className="space-y-4">
      {generatedToken && (
        <div className="space-y-2">
          <div className="rounded-md bg-warning-subtle border border-warning/20 p-3 flex items-start gap-2">
            <AlertTriangle className="h-4 w-4 text-warning mt-0.5 shrink-0" />
            <div className="text-sm text-warning">
              <p className="font-medium">이 토큰은 다시 볼 수 없습니다</p>
              <p className="mt-1">안전한 곳에 복사하여 저장하세요.</p>
            </div>
          </div>

          <div className="space-y-1.5">
            <Label>API 토큰</Label>
            <div className="flex gap-2">
              <code className="flex-1 bg-muted px-3 py-2 rounded-md text-sm font-mono break-all">
                {generatedToken}
              </code>
              <Button
                type="button"
                variant="outline"
                size="icon"
                onClick={() => handleCopy(generatedToken)}
              >
                {copied ? <Check className="h-4 w-4" /> : <Copy className="h-4 w-4" />}
              </Button>
            </div>
          </div>

          <div className="space-y-1.5">
            <Label>curl 예시</Label>
            <div className="bg-muted px-3 py-2 rounded-md">
              <code className="text-xs font-mono break-all">{curlExample}</code>
            </div>
          </div>
        </div>
      )}

      {isEditMode && !generatedToken && (
        <div className="text-sm text-muted-foreground">
          API 토큰은 생성 시에만 표시됩니다. 필요 시 트리거를 삭제 후 재생성하세요.
        </div>
      )}

      <div className="space-y-2">
        <Label>IP 제한 (선택)</Label>
        <p className="text-xs text-muted-foreground">
          비워두면 모든 IP에서 호출 가능합니다.
        </p>
        <div className="flex gap-2">
          <Input
            value={ipInput}
            onChange={(e) => {
              setIpInput(e.target.value);
              // 입력값 변경 시 에러 초기화
              if (ipError) setIpError('');
            }}
            placeholder="192.168.1.0/24"
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                e.preventDefault();
                handleAddIp();
              }
            }}
          />
          <Button type="button" variant="outline" onClick={handleAddIp}>
            추가
          </Button>
        </div>
        {/* IP 형식 오류 메시지 */}
        {ipError && (
          <p className="text-sm text-destructive">{ipError}</p>
        )}
        {config.allowedIps.length > 0 && (
          <div className="flex flex-wrap gap-1">
            {config.allowedIps.map((ip) => (
              <Badge key={ip} variant="secondary" className="cursor-pointer" onClick={() => handleRemoveIp(ip)}>
                {ip} &times;
              </Badge>
            ))}
          </div>
        )}
        {errors?.allowedIps && (
          <p className="text-sm text-destructive">{errors.allowedIps}</p>
        )}
      </div>
    </div>
  );
}
