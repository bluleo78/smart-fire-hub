import { AlertTriangle,Check, Copy } from 'lucide-react';
import { useId, useState } from 'react';

import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { InlineBanner } from '@/components/ui/inline-banner';
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
  // 접근성: 라벨↔입력 연결용 id 접두사 (#432). 추가/수정 다이얼로그 양쪽에서 렌더되므로 useId.
  const baseId = useId();
  const tokenLabelId = `${baseId}-token-label`;
  const curlLabelId = `${baseId}-curl-label`;
  const ipId = `${baseId}-ip`;
  const ipHelpId = `${baseId}-ip-help`;
  const ipErrorId = `${baseId}-ip-error`;
  const allowedIpsErrorId = `${baseId}-allowed-ips-error`;

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
          <InlineBanner icon={<AlertTriangle />} title="이 토큰은 다시 볼 수 없습니다">
            안전한 곳에 복사하여 저장하세요.
          </InlineBanner>

          <div className="space-y-1.5">
            {/*
              읽기 전용 code 표시라 대응하는 입력 요소가 없다 — Label 컴포넌트 대신 span 으로 두고
              표시 영역에 role="group" + aria-labelledby 로 이름을 준다 (#432).
              className 은 shadcn Label 기본 스타일을 그대로 옮겨 시각 결과를 유지한다.
            */}
            <span
              id={tokenLabelId}
              className="flex items-center gap-2 text-sm leading-none font-medium select-none"
            >
              API 토큰
            </span>
            <div className="flex gap-2" role="group" aria-labelledby={tokenLabelId}>
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
            {/* 위와 같은 이유로 span 요소 + role="group" (대응 입력 요소 없음, #432) */}
            <span
              id={curlLabelId}
              className="flex items-center gap-2 text-sm leading-none font-medium select-none"
            >
              curl 예시
            </span>
            <div className="bg-muted px-3 py-2 rounded-md" role="group" aria-labelledby={curlLabelId}>
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
        <Label htmlFor={ipId}>IP 제한 (선택)</Label>
        <p id={ipHelpId} className="text-xs text-muted-foreground">
          비워두면 모든 IP에서 호출 가능합니다.
        </p>
        <div className="flex gap-2">
          <Input
            id={ipId}
            /* 도움말·형식 오류·서버 검증 오류를 조건부로 모두 연결한다 */
            aria-describedby={
              [
                ipHelpId,
                ipError ? ipErrorId : null,
                errors?.allowedIps ? allowedIpsErrorId : null,
              ]
                .filter(Boolean)
                .join(' ')
            }
            aria-invalid={!!ipError || !!errors?.allowedIps}
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
          <p id={ipErrorId} className="text-sm text-destructive">{ipError}</p>
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
          <p id={allowedIpsErrorId} className="text-sm text-destructive">{errors.allowedIps}</p>
        )}
      </div>
    </div>
  );
}
