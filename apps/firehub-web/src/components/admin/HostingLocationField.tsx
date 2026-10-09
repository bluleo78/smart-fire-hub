import { AlertTriangle, Info, Server } from 'lucide-react';
import { useState } from 'react';

import type { HostingLocation } from '../../lib/hosting-location';
import { isLikelyPublicEndpoint } from '../../lib/hosting-location';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '../ui/alert-dialog';
import { InlineBanner } from '../ui/inline-banner';
import { Label } from '../ui/label';
import { RadioGroup, RadioGroupItem } from '../ui/radio-group';
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '../ui/tooltip';

/** 자체 호스팅 선언 권한이 없을 때의 안내 — 툴팁과 라디오 설명(aria-describedby)이 같은 문구를 쓴다. */
const NO_PERMISSION_HINT = '보안 설정 권한이 있어야 자체 호스팅으로 선언할 수 있습니다';

export interface HostingLocationFieldProps {
  /** DOM id 접두어 — 채팅·분류·임베딩 세 인스턴스가 같은 id 를 만들지 않게 한다. */
  id: string;
  value: HostingLocation;
  onChange: (v: HostingLocation) => void;
  /** Claude 계열 — 선택지 없이 "외부 서비스" 고정 표시(잘못 선언할 여지를 원천 차단). */
  readOnlyExternal: boolean;
  /**
   * 자체 호스팅을 고를 수 있는가. 호출부가 서버 규칙(`HostingDeclarationPolicy`)과 같게 계산한다 — security:settings 보유,
   * 또는 이미 저장된 자체 호스팅 선언을 같은 전송 대상 그대로 유지하는 경우. 내리는 것(외부)은 언제나 가능하다.
   */
  canDeclareSelfHosted: boolean;
  /** 공용 주소 경고 판정용 엔드포인트(기본 URL). */
  endpointUrl?: string;
  /** 전송 대상(공급자·주소)이 바뀌어 저장된 자체 호스팅 선언을 외부로 되돌렸는가 — 되돌린 사실을 숨기지 않고 알린다. */
  demoted?: boolean;
}

/**
 * 「호스팅 위치」 필드(S3 §5-5). 자체 호스팅 선언은 '민감' 등 자체 호스팅 전용 등급 데이터를 이 공급자로 보내는 결정이라
 * 확인 창을 거치고, 주소가 공용으로 보이면 그 창 안에서 경고한다(차단하지 않음 — 판정 책임은 관리자 선언).
 */
export function HostingLocationField({
  id,
  value,
  onChange,
  readOnlyExternal,
  canDeclareSelfHosted,
  endpointUrl,
  demoted = false,
}: HostingLocationFieldProps) {
  const [confirming, setConfirming] = useState(false);

  if (readOnlyExternal) {
    // Claude 는 Anthropic 서버에서만 돈다 — 선택지가 아니라 사실을 보여 준다.
    return (
      <div className="space-y-2">
        <Label>호스팅 위치</Label>
        <p className="flex items-center gap-1.5 text-sm">
          <Server className="h-4 w-4 text-muted-foreground" aria-hidden />
          외부 서비스
        </p>
        <p className="text-sm text-muted-foreground">
          Claude 는 Anthropic 서버에서 실행됩니다. &apos;자체 호스팅 모델만&apos; 정책 등급의 데이터에는 사용되지 않습니다.
        </p>
      </div>
    );
  }

  const publicEndpoint = endpointUrl ? isLikelyPublicEndpoint(endpointUrl) : false;
  // 이미 자체 호스팅이면(권한 없이도) 그 값을 그대로 둘 수 있어야 하므로 선택된 항목은 잠그지 않는다.
  const selfHostedDisabled = !canDeclareSelfHosted && value !== 'SELF_HOSTED';
  const hintId = `${id}-self-hint`;

  return (
    // 이 필드는 레이아웃의 TooltipProvider 밖(단위 테스트·다른 화면)에서도 그려지므로 자체 Provider 를 둔다.
    <TooltipProvider>
      <div className="space-y-2">
        <Label id={`${id}-label`}>호스팅 위치</Label>
        <RadioGroup
          aria-labelledby={`${id}-label`}
          value={value}
          // 자체 호스팅은 확인 창을 거쳐서만 반영한다 — 라디오 값은 확인 전까지 그대로다(제어 컴포넌트).
          onValueChange={(v) => (v === 'SELF_HOSTED' ? setConfirming(true) : onChange('EXTERNAL'))}
        >
          <div className="flex items-center gap-2">
            <RadioGroupItem id={`${id}-external`} value="EXTERNAL" />
            <Label htmlFor={`${id}-external`} className="font-normal">
              외부 서비스
            </Label>
          </div>
          <Tooltip>
            {/* 비활성 라디오는 포인터 이벤트를 받지 않아 감싼 div 를 트리거로 쓴다. */}
            <TooltipTrigger asChild>
              <div className="flex w-fit items-center gap-2">
                <RadioGroupItem
                  id={`${id}-self`}
                  value="SELF_HOSTED"
                  disabled={selfHostedDisabled}
                  aria-describedby={selfHostedDisabled ? hintId : undefined}
                />
                <Label htmlFor={`${id}-self`} className="font-normal">
                  자체 호스팅
                </Label>
              </div>
            </TooltipTrigger>
            {selfHostedDisabled && <TooltipContent>{NO_PERMISSION_HINT}</TooltipContent>}
          </Tooltip>
        </RadioGroup>
        <p className="text-sm text-muted-foreground">
          자체 호스팅 전용 등급(예: 민감) 데이터는 자체 호스팅으로 선언된 공급자로만 전송됩니다.
        </p>
        {/* 툴팁은 호버해야 보이므로 비활성 이유를 본문에도 남긴다(키보드·스크린리더 사용자). */}
        {selfHostedDisabled && (
          <p id={hintId} className="text-sm text-muted-foreground">
            {NO_PERMISSION_HINT}.
          </p>
        )}
        {demoted && value === 'EXTERNAL' && (
          <InlineBanner variant="info" icon={<Info />}>
            공급자나 주소가 바뀌어 호스팅 위치를 외부 서비스로 되돌렸습니다. 새 주소가 조직 내부 서버라면 자체 호스팅으로 다시
            선언하세요.
          </InlineBanner>
        )}
        <AlertDialog open={confirming} onOpenChange={setConfirming}>
          <AlertDialogContent>
            <AlertDialogHeader>
              <AlertDialogTitle>자체 호스팅으로 선언할까요?</AlertDialogTitle>
              <AlertDialogDescription>
                자체 호스팅으로 선언하면 &apos;민감&apos; 등 자체 호스팅 전용 등급 데이터가 이 공급자로 전송됩니다. 조직
                내부에서 운영하는 서버인지 확인하세요. 변경 내역은 감사 로그에 기록됩니다.
              </AlertDialogDescription>
            </AlertDialogHeader>
            {publicEndpoint && (
              <InlineBanner variant="warning" icon={<AlertTriangle />}>
                이 주소는 공용 인터넷 주소로 보입니다 — 자체 호스팅이 맞는지 확인하세요.
              </InlineBanner>
            )}
            <AlertDialogFooter>
              <AlertDialogCancel>취소</AlertDialogCancel>
              <AlertDialogAction
                onClick={() => {
                  onChange('SELF_HOSTED');
                  setConfirming(false);
                }}
              >
                자체 호스팅으로 선언
              </AlertDialogAction>
            </AlertDialogFooter>
          </AlertDialogContent>
        </AlertDialog>
      </div>
    </TooltipProvider>
  );
}
