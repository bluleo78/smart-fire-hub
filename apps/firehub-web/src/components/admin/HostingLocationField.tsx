import { AlertTriangle, Info, Server } from 'lucide-react';
import { useState } from 'react';

import type { HostingLocation } from '../../lib/hosting-location';
import { isLikelyPublicEndpoint } from '../../lib/hosting-location';
import { cn } from '../../lib/utils';
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

/**
 * 보안 탭 「AI 분석」 정책의 선택지 라벨(SecurityLevelRow) — 같은 정책을 이 필드의 설명·확인 창도 같은 이름으로 부른다.
 * 등급별 정책은 테넌트가 바꿀 수 있으므로 특정 등급 이름('민감')을 단정하지 않고 정책 이름으로 말한다.
 */
const SELF_HOSTED_ONLY_POLICY = "AI 분석 정책이 '자체 호스팅 모델만'인 등급";

/** 자체 호스팅 선언 권한이 없을 때의 안내 — 라디오 설명(aria-describedby)으로 연결되는 본문 문구. */
const NO_PERMISSION_HINT = '보안 설정 권한이 있어야 자체 호스팅으로 선언할 수 있습니다.';

/** 전송 대상이 바뀌어 외부로 되돌렸다는 안내 — 뒤 문장은 권한 유무에 따라 "다시 선언"과 "관리자에게 요청"으로 갈린다. */
const DEMOTED_NOTICE = '공급자나 주소가 바뀌어 호스팅 위치를 외부 서비스로 되돌렸습니다.';
const DEMOTED_REDECLARE = '새 주소가 조직 내부 서버라면 자체 호스팅으로 다시 선언하세요.';
const DEMOTED_ASK_ADMIN = '새 주소가 조직 내부 서버라면 보안 설정 권한이 있는 관리자에게 자체 호스팅 선언을 요청하세요.';

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
          Claude 는 Anthropic 서버에서 실행됩니다. {SELF_HOSTED_ONLY_POLICY}의 데이터에는 사용되지 않습니다.
        </p>
      </div>
    );
  }

  const publicEndpoint = endpointUrl ? isLikelyPublicEndpoint(endpointUrl) : false;
  // 이미 자체 호스팅이면(권한 없이도) 그 값을 그대로 둘 수 있어야 하므로 선택된 항목은 잠그지 않는다.
  const selfHostedDisabled = !canDeclareSelfHosted && value !== 'SELF_HOSTED';
  // 비활성 이유는 본문 한 곳에만 둔다(툴팁 없음) — 비활성 라디오는 포커스를 받지 않아 키보드 사용자에게 툴팁이
  // 보이지 않고, 툴팁이 라벨·선택값을 가린다. 본문은 항상 보이고 aria-describedby 로 라디오에 연결된다.
  const hintId = `${id}-self-hint`;
  const showDemoted = demoted && value === 'EXTERNAL';

  return (
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
        <div className="flex items-center gap-2">
          <RadioGroupItem
            id={`${id}-self`}
            value="SELF_HOSTED"
            disabled={selfHostedDisabled}
            aria-describedby={selfHostedDisabled ? hintId : undefined}
          />
          {/* Label 의 peer-disabled 는 RadioGroupItem 에 peer 클래스가 없어 듣지 않는다 — 디자인 시스템 09 Disabled(50%)
              를 직접 건다. */}
          <Label
            htmlFor={`${id}-self`}
            className={cn('font-normal', selfHostedDisabled && 'cursor-not-allowed opacity-50')}
          >
            자체 호스팅
          </Label>
        </div>
      </RadioGroup>
      <p className="text-sm text-muted-foreground">
        {SELF_HOSTED_ONLY_POLICY}의 데이터는 자체 호스팅으로 선언된 공급자로만 전송됩니다.
      </p>
      {showDemoted ? (
        // 되돌림 안내 — 권한이 없으면 "다시 선언하세요" 대신 요청 경로를 알리고, 이 배너가 비활성 이유도 겸한다
        // (권한 안내를 별도 줄로 또 쓰지 않는다).
        <InlineBanner id={selfHostedDisabled ? hintId : undefined} variant="info" icon={<Info />}>
          {DEMOTED_NOTICE} {selfHostedDisabled ? DEMOTED_ASK_ADMIN : DEMOTED_REDECLARE}
        </InlineBanner>
      ) : (
        selfHostedDisabled && (
          <p id={hintId} className="text-sm text-muted-foreground">
            {NO_PERMISSION_HINT}
          </p>
        )
      )}
      <AlertDialog open={confirming} onOpenChange={setConfirming}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>자체 호스팅으로 선언할까요?</AlertDialogTitle>
            <AlertDialogDescription>
              자체 호스팅으로 선언하면 {SELF_HOSTED_ONLY_POLICY}(예: 기본 설정의 민감·기밀)의 데이터가 이 공급자로
              전송됩니다. 조직 내부에서 운영하는 서버인지 확인하세요. 변경 내역은 감사 로그에 기록됩니다.
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
  );
}
