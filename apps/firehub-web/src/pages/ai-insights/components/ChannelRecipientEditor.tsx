import { Mail, MessageSquare } from 'lucide-react';

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

export default function ChannelRecipientEditor({
  channels,
  onChange,
  disabled,
}: ChannelRecipientEditorProps) {
  const chatChannel = channels.find((c) => c.type === 'CHAT');
  const emailChannel = channels.find((c) => c.type === 'EMAIL');
  const chatEnabled = !!chatChannel;
  const emailEnabled = !!emailChannel;

  const toggleChannel = (type: 'CHAT' | 'EMAIL', enabled: boolean) => {
    if (enabled) {
      onChange([...channels, { type, recipientUserIds: [], recipientEmails: [] }]);
    } else {
      onChange(channels.filter((c) => c.type !== type));
    }
  };

  const updateChannel = (type: 'CHAT' | 'EMAIL', patch: Partial<ChannelConfigValues>) => {
    onChange(
      channels.map((c) =>
        c.type === type ? { ...c, ...patch } : c,
      ),
    );
  };

  return (
    <div className="space-y-3">
      {/* 채팅 채널 */}
      <div className="rounded-lg border p-4">
        <div className="flex items-center gap-2">
          <Checkbox
            id="ch-chat"
            checked={chatEnabled}
            disabled={disabled}
            onCheckedChange={(checked) => toggleChannel('CHAT', !!checked)}
          />
          <Label htmlFor="ch-chat" className="flex items-center gap-1.5 cursor-pointer font-medium">
            <MessageSquare className="h-4 w-4" />
            채팅
          </Label>
          {chatEnabled && chatChannel!.recipientUserIds.length > 0 && (
            <Badge variant="secondary" className="ml-1">
              {chatChannel!.recipientUserIds.length}
            </Badge>
          )}
        </div>

        {chatEnabled && (
          <div className="mt-3 space-y-2">
            <p className="text-xs text-muted-foreground">
              {chatChannel!.recipientUserIds.length === 0
                ? '수신자를 지정하지 않으면 본인에게만 전달됩니다'
                : `${chatChannel!.recipientUserIds.length}명에게 전달됩니다`}
            </p>
            <UserCombobox
              selectedUserIds={chatChannel!.recipientUserIds}
              onChange={(ids) => updateChannel('CHAT', { recipientUserIds: ids })}
              placeholder="사용자 검색 (이름 또는 이메일)"
              disabled={disabled}
            />
          </div>
        )}
      </div>

      {/* 이메일 채널 */}
      <div className="rounded-lg border p-4">
        <div className="flex items-center gap-2">
          <Checkbox
            id="ch-email"
            checked={emailEnabled}
            disabled={disabled}
            onCheckedChange={(checked) => toggleChannel('EMAIL', !!checked)}
          />
          <Label htmlFor="ch-email" className="flex items-center gap-1.5 cursor-pointer font-medium">
            <Mail className="h-4 w-4" />
            이메일
          </Label>
          {emailEnabled && (emailChannel!.recipientUserIds.length + emailChannel!.recipientEmails.length) > 0 && (
            <Badge variant="secondary" className="ml-1">
              {emailChannel!.recipientUserIds.length + emailChannel!.recipientEmails.length}
            </Badge>
          )}
        </div>

        {emailEnabled && (
          <div className="mt-3 space-y-3">
            <p className="text-xs text-muted-foreground">
              수신자를 지정하지 않으면 본인에게만 전달됩니다
            </p>

            <div className="space-y-1">
              <Label className="text-xs">등록 사용자</Label>
              <UserCombobox
                selectedUserIds={emailChannel!.recipientUserIds}
                onChange={(ids) => updateChannel('EMAIL', { recipientUserIds: ids })}
                placeholder="사용자 검색 (이름 또는 이메일)"
                disabled={disabled}
              />
            </div>

            <div className="space-y-1">
              <Label className="text-xs">외부 이메일</Label>
              <EmailTagInput
                emails={emailChannel!.recipientEmails}
                onChange={(emails) => updateChannel('EMAIL', { recipientEmails: emails })}
                disabled={disabled}
              />
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
