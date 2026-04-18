import { Bell, FileDown, Info, Mail, MessageSquare, Slack } from 'lucide-react';

import { Badge } from '@/components/ui/badge';
import { Checkbox } from '@/components/ui/checkbox';
import { Label } from '@/components/ui/label';
import UserCombobox from '@/components/UserCombobox';
import type { ChannelConfigValues } from '@/lib/validations/proactive-job';

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
    label: '채팅',
    description: '앱 내 알림 채널 — 항상 활성화됩니다.',
    Icon: Bell,
  },
  EMAIL: {
    label: '이메일',
    description: '이메일로 분석 결과를 전달합니다.',
    Icon: Mail,
  },
  KAKAO: {
    label: '카카오 알림톡',
    description: '카카오 알림톡으로 분석 결과를 전달합니다.',
    Icon: MessageSquare,
  },
  SLACK: {
    label: 'Slack',
    description: 'Slack 워크스페이스로 분석 결과를 전달합니다.',
    Icon: Slack,
  },
};

/**
 * 채널 수신자 편집 컴포넌트
 * - CHAT/EMAIL/KAKAO/SLACK 4개 채널 체크박스 + 수신자 설정
 * - CHAT 채널은 항상 활성화 (disabled, 체크 해제 불가)
 * - KAKAO/SLACK: 수신자 사용자 ID 지정 + 미연동 안내 문구 표시
 *   (TODO: 백엔드 bulk binding 조회 API 추가 시 "N명 미연동" 배지로 교체)
 */
export default function ChannelRecipientEditor({
  channels,
  onChange,
  disabled,
}: ChannelRecipientEditorProps) {
  const getChannel = (type: ChannelConfigValues['type']) =>
    channels.find((c) => c.type === type);

  const isEnabled = (type: ChannelConfigValues['type']) => !!getChannel(type);

  /**
   * 채널 토글 — CHAT은 항상 켜져 있어야 하므로 비활성화 불가
   */
  const toggleChannel = (type: ChannelConfigValues['type'], enabled: boolean) => {
    if (type === 'CHAT') return; // CHAT은 항상 활성화
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
      {renderOAuthChannel('KAKAO')}

      {/* Slack 채널 */}
      {renderOAuthChannel('SLACK')}
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

    return (
      <div className="rounded-lg border p-4">
        <div className="flex items-center gap-2">
          <Checkbox
            id="ch-email"
            checked={enabled}
            disabled={disabled}
            onCheckedChange={(checked) => toggleChannel(type, !!checked)}
          />
          <Label htmlFor="ch-email" className="flex items-center gap-1.5 cursor-pointer font-medium">
            <Icon className="h-4 w-4" />
            {label}
          </Label>
          {enabled && channel && (channel.recipientUserIds.length + channel.recipientEmails.length) > 0 && (
            <Badge variant="secondary" className="ml-1">
              {channel.recipientUserIds.length + channel.recipientEmails.length}
            </Badge>
          )}
        </div>

        <p className="mt-1.5 text-xs text-muted-foreground">{description}</p>

        {enabled && channel && (
          <div className="mt-3 space-y-3">
            <p className="text-xs text-muted-foreground">
              수신자를 지정하지 않으면 본인에게만 전달됩니다
            </p>

            <div className="space-y-1">
              <Label className="text-xs">등록 사용자</Label>
              <UserCombobox
                selectedUserIds={channel.recipientUserIds}
                onChange={(ids) => updateChannel(type, { recipientUserIds: ids })}
                placeholder="사용자 검색 (이름 또는 이메일)"
                disabled={disabled}
              />
            </div>

            <div className="space-y-1">
              <Label className="text-xs">외부 이메일</Label>
              <EmailTagInput
                emails={channel.recipientEmails}
                onChange={(emails) => updateChannel(type, { recipientEmails: emails })}
                disabled={disabled}
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
   * OAuth 채널(KAKAO/SLACK) 렌더링
   * - 수신자 사용자 ID 지정 가능
   * - 미연동 사용자 안내: MVP 단계에서는 설정 페이지 안내 문구로 대체
   *   TODO: 백엔드 bulk binding 조회 API(POST /api/v1/channels/users/binding-summary) 추가 시
   *         "수신자 중 N명 미연동" 배지로 교체
   */
  function renderOAuthChannel(type: 'KAKAO' | 'SLACK') {
    const channel = getChannel(type);
    const enabled = isEnabled(type);
    const { label, description, Icon } = CHANNEL_META[type];
    const checkboxId = `ch-${type.toLowerCase()}`;

    return (
      <div className="rounded-lg border p-4">
        <div className="flex items-center gap-2">
          <Checkbox
            id={checkboxId}
            checked={enabled}
            disabled={disabled}
            onCheckedChange={(checked) => toggleChannel(type, !!checked)}
          />
          <Label
            htmlFor={checkboxId}
            className="flex items-center gap-1.5 cursor-pointer font-medium"
          >
            <Icon className="h-4 w-4" />
            {label}
          </Label>
          {enabled && channel && channel.recipientUserIds.length > 0 && (
            <Badge variant="secondary" className="ml-1">
              {channel.recipientUserIds.length}
            </Badge>
          )}
        </div>

        <p className="mt-1.5 text-xs text-muted-foreground">{description}</p>

        {enabled && channel && (
          <div className="mt-3 space-y-2">
            <p className="text-xs text-muted-foreground">
              {channel.recipientUserIds.length === 0
                ? '수신자를 지정하지 않으면 본인에게만 전달됩니다'
                : `${channel.recipientUserIds.length}명에게 전달됩니다`}
            </p>
            <UserCombobox
              selectedUserIds={channel.recipientUserIds}
              onChange={(ids) => updateChannel(type, { recipientUserIds: ids })}
              placeholder="사용자 검색 (이름 또는 이메일)"
              disabled={disabled}
            />

            {/* 미연동 안내 — 백엔드 bulk API 추가 전 임시 안내 문구 */}
            <div className="flex items-start gap-1.5 rounded-md bg-muted/50 px-3 py-2">
              <Info className="h-3.5 w-3.5 mt-0.5 shrink-0 text-muted-foreground" />
              <p className="text-xs text-muted-foreground">
                수신자의 {label} 연동 상태는{' '}
                <a
                  href="/settings/channels"
                  target="_blank"
                  rel="noopener noreferrer"
                  className="underline underline-offset-2 hover:text-foreground"
                >
                  설정 &gt; 채널 연동
                </a>
                에서 확인하세요.
              </p>
            </div>
          </div>
        )}
      </div>
    );
  }
}
