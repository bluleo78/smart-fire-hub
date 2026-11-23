import { Bell, FileDown, Mail, MessageSquare, Slack, Webhook } from 'lucide-react';
import { useId, useRef } from 'react';

import { Badge } from '@/components/ui/badge';
import { Checkbox } from '@/components/ui/checkbox';
import { Label } from '@/components/ui/label';
import UserCombobox from '@/components/UserCombobox';
import { useRecipientsByIds } from '@/hooks/queries/useProactiveMessages';
import { countEmailRecipients } from '@/lib/email-recipients';
import { cn } from '@/lib/utils';
import {
  CHANNEL_TYPE_LABELS,
  type ChannelConfigValues,
  UNSUPPORTED_CHANNEL_SAVE_MESSAGE,
  UNSUPPORTED_DELIVERY_CHANNELS,
} from '@/lib/validations/proactive-job';

import EmailTagInput from './EmailTagInput';

interface ChannelRecipientEditorProps {
  channels: ChannelConfigValues[];
  onChange: (channels: ChannelConfigValues[]) => void;
  disabled?: boolean;
}

/** 채널별 표시 정보 */
const CHANNEL_META: Record<
  ChannelConfigValues['type'],
  { label: string; description: string; Icon: React.ElementType }
> = {
  CHAT: {
    label: CHANNEL_TYPE_LABELS.CHAT,
    description: '앱 내 알림 채널 — 항상 활성화됩니다.',
    Icon: Bell,
  },
  EMAIL: {
    label: CHANNEL_TYPE_LABELS.EMAIL,
    description: '이메일로 분석 결과를 전달합니다.',
    Icon: Mail,
  },
  KAKAO: {
    label: CHANNEL_TYPE_LABELS.KAKAO,
    description: '카카오 알림톡으로 분석 결과를 전달합니다.',
    Icon: MessageSquare,
  },
  SLACK: {
    label: CHANNEL_TYPE_LABELS.SLACK,
    description: 'Slack 워크스페이스로 분석 결과를 전달합니다.',
    Icon: Slack,
  },
  // WD-994: API 로 설정된 웹훅 채널 — 화면에서 새로 켜지 않고, 설정에 있을 때만 카드로 보인다.
  WEBHOOK: {
    label: CHANNEL_TYPE_LABELS.WEBHOOK,
    description: 'API 로 설정된 채널입니다 — 아직 스마트 작업 결과를 웹훅으로 보내지 않습니다. 해제하면 설정에서 빠집니다.',
    Icon: Webhook,
  },
};

/** 미지원 채널 카드의 안내 문구 — 선택 여부와 관계없이 항상 보인다 (WD-805) */
const UNSUPPORTED_DESCRIPTION =
  '스마트 작업 결과 전달은 아직 지원하지 않습니다 — 채팅·이메일로 받을 수 있습니다.';

/**
 * 채널 수신자 편집 컴포넌트
 * - CHAT/EMAIL 채널 체크박스 + 수신자 설정, KAKAO/SLACK 은 「준비 중」 카드
 * - CHAT 채널은 항상 활성화 (disabled, 체크 해제 불가)
 * - KAKAO/SLACK: 서버가 받지 않으므로 새로 켤 수 없고 「준비 중」 사유를 보인다.
 *   예전에 저장된 설정에 들어 있으면 해제만 할 수 있다(저장은 폼 스키마가 막아 400 방지) (WD-805)
 */
export default function ChannelRecipientEditor({
  channels,
  onChange,
  disabled,
}: ChannelRecipientEditorProps) {
  // 접근성: 그룹 라벨↔위젯 연결용 id 접두사 (#432)
  const baseId = useId();
  // WD-994: 웹훅 카드가 사라진 뒤 초점을 받을 이메일 체크박스
  const emailCheckboxRef = useRef<HTMLButtonElement>(null);

  const getChannel = (type: ChannelConfigValues['type']) =>
    channels.find((c) => c.type === type);

  const isEnabled = (type: ChannelConfigValues['type']) => !!getChannel(type);

  // 이메일 채널에서 「등록 사용자」로 고른 사람의 주소 — 외부 이메일과의 중복을 막고 받는 사람 수를 바르게 세는 데 쓴다(WD-299).
  // 조회가 끝나기 전에는 비어 있어 그 사이 넣은 같은 주소는 막지 못하지만, 서버가 발송할 때 한 번만 보낸다.
  const emailUserIds = getChannel('EMAIL')?.recipientUserIds ?? [];
  const { data: emailUsers } = useRecipientsByIds(emailUserIds);
  const registeredEmails = (emailUsers ?? []).map((u) => u.email).filter(Boolean);

  /**
   * 채널 토글 — CHAT은 항상 켜져 있어야 하므로 비활성화 불가
   */
  const toggleChannel = (type: ChannelConfigValues['type'], enabled: boolean) => {
    if (type === 'CHAT') return; // CHAT은 항상 활성화
    // 미지원 채널(Slack·카카오)은 새로 켤 수 없고 해제만 가능하다 (WD-805)
    if (enabled && UNSUPPORTED_DELIVERY_CHANNELS.has(type)) return;
    if (enabled) {
      onChange([...channels, { type, recipientUserIds: [], recipientEmails: [] }]);
    } else {
      onChange(channels.filter((c) => c.type !== type));
    }
  };

  const updateChannel = (
    type: ChannelConfigValues['type'],
    patch: Partial<ChannelConfigValues>,
  ) => {
    onChange(channels.map((c) => (c.type === type ? { ...c, ...patch } : c)));
  };

  return (
    <div className="space-y-3">
      {/* CHAT 채널 — 항상 활성화 */}
      {renderChatChannel()}

      {/* 이메일 채널 */}
      {renderEmailChannel()}

      {/* 카카오 채널 */}
      {renderUnsupportedChannel('KAKAO')}

      {/* Slack 채널 */}
      {renderUnsupportedChannel('SLACK')}

      {/* 웹훅 채널 — 설정에 있을 때만 (WD-994) */}
      {channels.map((c, index) => (c.type === 'WEBHOOK' ? renderWebhookChannel(c, index) : null))}
    </div>
  );

  /** CHAT 채널 렌더링 — 항상 체크, disabled */
  function renderChatChannel() {
    const channel = getChannel('CHAT');
    const { label, description, Icon } = CHANNEL_META.CHAT;

    return (
      <div className="rounded-lg border p-4">
        <div className="flex items-center gap-2">
          {/* CHAT은 항상 체크 + 비활성화 */}
          <Checkbox id="ch-chat" checked disabled aria-label="채팅 채널 (항상 활성화)" />
          <Label htmlFor="ch-chat" className="flex items-center gap-1.5 cursor-default font-medium">
            <Icon className="h-4 w-4" />
            {label}
          </Label>
          {channel && channel.recipientUserIds.length > 0 && (
            <Badge variant="secondary" className="ml-1">
              {channel.recipientUserIds.length}
            </Badge>
          )}
        </div>

        <p className="mt-1.5 text-xs text-muted-foreground">{description}</p>

        {channel && (
          <div className="mt-3 space-y-2">
            <p className="text-xs text-muted-foreground">
              {channel.recipientUserIds.length === 0
                ? '수신자를 지정하지 않으면 본인에게만 전달됩니다'
                : `${channel.recipientUserIds.length}명에게 전달됩니다`}
            </p>
            <UserCombobox
              selectedUserIds={channel.recipientUserIds}
              onChange={(ids) => updateChannel('CHAT', { recipientUserIds: ids })}
              placeholder="사용자 검색 (이름 또는 이메일)"
              // 콤보박스 접근 이름 — 채널 라벨 + 「수신자」(WD-512)
              aria-label={`${label} 수신자`}
              disabled={disabled}
            />
          </div>
        )}
      </div>
    );
  }

  /** 이메일 채널 렌더링 */
  function renderEmailChannel() {
    const type = 'EMAIL' as const;
    const channel = getChannel(type);
    const enabled = isEnabled(type);
    const { label, description, Icon } = CHANNEL_META[type];
    const recipientCount = channel
      ? countEmailRecipients(channel.recipientUserIds, channel.recipientEmails, registeredEmails)
      : 0;

    return (
      <div className="rounded-lg border p-4">
        <div className="flex items-center gap-2">
          <Checkbox
            ref={emailCheckboxRef}
            id="ch-email"
            checked={enabled}
            disabled={disabled}
            onCheckedChange={(checked) => toggleChannel(type, !!checked)}
          />
          <Label htmlFor="ch-email" className="flex items-center gap-1.5 cursor-pointer font-medium">
            <Icon className="h-4 w-4" />
            {label}
          </Label>
          {enabled && channel && recipientCount > 0 && (
            <Badge variant="secondary" className="ml-1">
              {recipientCount}
            </Badge>
          )}
        </div>

        <p className="mt-1.5 text-xs text-muted-foreground">{description}</p>

        {enabled && channel && (
          <div className="mt-3 space-y-3">
            <p className="text-xs text-muted-foreground">
              {recipientCount === 0
                ? '수신자를 지정하지 않으면 본인에게만 전달됩니다'
                : `${recipientCount}명에게 전달됩니다`}
            </p>

            {/* UserCombobox / EmailTagInput 은 id prop 을 받지 않는 복합 위젯이라
                htmlFor 를 걸 대상이 없다. 대신 제목을 span 으로 두고 래퍼를 role=group +
                aria-labelledby 로 묶어 스크린리더가 이름을 읽게 한다 (#432, 명세 규칙 3 폴백) */}
            <div className="space-y-1" role="group" aria-labelledby={`${baseId}-email-users`}>
              <span id={`${baseId}-email-users`} className="block text-xs leading-none font-medium">
                등록 사용자
              </span>
              <UserCombobox
                selectedUserIds={channel.recipientUserIds}
                onChange={(ids) => updateChannel(type, { recipientUserIds: ids })}
                placeholder="사용자 검색 (이름 또는 이메일)"
                // 그룹 제목 「등록 사용자」와 같은 문구로 콤보박스 이름을 준다(WD-512)
                aria-label="등록 사용자"
                disabled={disabled}
              />
            </div>

            <div className="space-y-1" role="group" aria-labelledby={`${baseId}-email-external`}>
              <span id={`${baseId}-email-external`} className="block text-xs leading-none font-medium">
                외부 이메일
              </span>
              <EmailTagInput
                emails={channel.recipientEmails}
                onChange={(emails) => updateChannel(type, { recipientEmails: emails })}
                disabled={disabled}
                registeredEmails={registeredEmails}
              />
            </div>

            {/* PDF 첨부 옵션 */}
            <div className="flex items-center gap-2 pt-1 border-t">
              <Checkbox
                id="attach-pdf"
                checked={channel.attachPdf ?? false}
                disabled={disabled}
                onCheckedChange={(checked) =>
                  updateChannel(type, { attachPdf: !!checked })
                }
              />
              <Label
                htmlFor="attach-pdf"
                className="flex items-center gap-1.5 text-xs cursor-pointer"
              >
                <FileDown className="h-3.5 w-3.5" />
                리포트를 PDF로 첨부
              </Label>
            </div>
          </div>
        )}
      </div>
    );
  }

  /**
   * 웹훅 채널 카드 (WD-994)
   * - 서버는 WEBHOOK 을 받아 저장하지만 이 화면에는 주소 입력이 없고 아직 발송도 하지 않는다.
   *   그래서 설정에 이미 있을 때만 카드를 그려 주소를 보이고(편집 불가), 해제만 허용한다.
   * - 주소는 폼 값에 그대로 남아 저장 요청에 다시 실린다(채널이 사라지지 않도록).
   */
  function renderWebhookChannel(channel: ChannelConfigValues, index: number) {
    const { label, description, Icon } = CHANNEL_META.WEBHOOK;
    // WD-994: 웹훅이 여러 개일 수 있어 카드마다 id 를 따로 둔다(index 는 channels 배열 위치)
    const checkboxId = `ch-webhook-${index}`;
    const urlId = `${checkboxId}-url`;
    const descId = `${checkboxId}-desc`;

    return (
      <div key={checkboxId} className="rounded-lg border p-4" data-testid="channel-card-webhook">
        <div className="flex items-center gap-2">
          <Checkbox
            id={checkboxId}
            checked
            disabled={disabled}
            aria-describedby={`${urlId} ${descId}`}
            onCheckedChange={(checked) => {
              // 새로 켜기는 없다 — 해제만. 같은 타입이 여럿일 수 있어 type 이 아니라 index 로 그 항목만 뺀다.
              if (checked) return;
              onChange(channels.filter((_, i) => i !== index));
              // WD-994: 해제하면 카드째 사라져 포커스가 있던 체크박스도 DOM 에서 빠지고 초점이 body 로 떨어진다(WCAG 2.4.3).
              // 항상 렌더되는 이메일 체크박스로 초점을 옮긴다. 제거 렌더가 끝난 뒤에 옮겨야 하므로 rAF 로 미룬다.
              requestAnimationFrame(() => emailCheckboxRef.current?.focus());
            }}
          />
          <Label
            htmlFor={checkboxId}
            className={cn('flex items-center gap-1.5 font-medium', disabled ? 'cursor-default' : 'cursor-pointer')}
          >
            <Icon className="h-4 w-4" />
            {label}
          </Label>
          <Badge variant="secondary" className="ml-1">
            전달 안 됨
          </Badge>
        </div>

        <p id={urlId} className="mt-1.5 text-xs text-muted-foreground break-all">
          웹훅 주소: {channel.url || '(없음)'}
        </p>
        <p id={descId} className="mt-1 text-xs text-muted-foreground">
          {description}
        </p>
      </div>
    );
  }

  /**
   * 서버가 받지 않는 채널(KAKAO/SLACK) 렌더링 (WD-805)
   * - 서버 화이트리스트(#594)가 거절하고 기본 발송 경로에 구현이 없어 새로 켤 수 없다 — 「준비 중」 배지와 사유만 보인다.
   * - 예전에 저장된 설정에 들어 있으면 체크가 남아 있어, 해제만 할 수 있고 경고 문구를 아래에 붙인다
   *   (남은 채 저장하는 것은 폼 스키마가 막는다).
   * - 수신자 UI·연동 안내는 지원 채널이 되면 그때 다시 만든다(git 이력 참고).
   */
  function renderUnsupportedChannel(type: 'KAKAO' | 'SLACK') {
    const enabled = isEnabled(type);
    const { label, Icon } = CHANNEL_META[type];
    const checkboxId = `ch-${type.toLowerCase()}`;
    const descId = `${checkboxId}-desc`;
    const warnId = `${checkboxId}-warn`;
    // 켤 수 없는 상태(미선택)에서는 클릭이 먹지 않으므로 커서도 기본으로 둔다
    const canToggle = !disabled && enabled;

    return (
      <div className="rounded-lg border p-4" data-testid={`channel-card-${type.toLowerCase()}`}>
        <div className="flex items-center gap-2">
          <Checkbox
            id={checkboxId}
            checked={enabled}
            // 새로 켤 수 없다 — 이미 켜진 것은 해제할 수 있어야 하므로 enabled 일 때만 활성
            disabled={!canToggle}
            aria-describedby={enabled ? `${descId} ${warnId}` : descId}
            onCheckedChange={(checked) => toggleChannel(type, !!checked)}
          />
          <Label
            htmlFor={checkboxId}
            className={cn('flex items-center gap-1.5 font-medium', canToggle ? 'cursor-pointer' : 'cursor-default')}
          >
            <Icon className="h-4 w-4" />
            {label}
          </Label>
          <Badge variant="secondary" className="ml-1">
            준비 중
          </Badge>
        </div>

        <p id={descId} className="mt-1.5 text-xs text-muted-foreground">
          {UNSUPPORTED_DESCRIPTION}
        </p>

        {/* 예전에 저장된 미지원 채널 — 정적 안내(role=alert 아님) */}
        {enabled && (
          <p id={warnId} className="mt-1.5 text-xs text-destructive">
            {UNSUPPORTED_CHANNEL_SAVE_MESSAGE}
          </p>
        )}
      </div>
    );
  }
}
