import { Lock } from 'lucide-react';

import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { PasswordInput } from '@/components/ui/password-input';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { Switch } from '@/components/ui/switch';
import { Textarea } from '@/components/ui/textarea';
import { badgeKindOf, type SettingSpec } from '@/lib/settings-catalog';

export interface SettingFieldProps {
  spec: SettingSpec;
  /** 현재 편집 중인 값. 비밀 키는 "새로 입력한 값"이고 마스크가 아니다(Task 10). */
  value: string;
  onChange: (value: string) => void;
  disabled?: boolean;
  error?: string;
  /** 서버 `SettingResponse.description`. 문구를 프런트에 복제하지 않는다. */
  description?: string | null;
  /** 서버 응답에 이 키의 행이 없다 = 내장 기본값이 적용 중이다. */
  usingBuiltinDefault?: boolean;
  /** 서버가 준 마스크(`****` / `****last4`). 비밀 키에서만 의미가 있고, 힌트에만 쓴다. */
  maskedValue?: string | null;
  /** 이 비밀 키가 `지우기` 표시되어 있는가. */
  cleared?: boolean;
  /** `지우기` 를 눌렀을 때. `spec.clearable === false` 면 호출부가 넘기지 않는다. */
  onClear?: () => void;
}

/**
 * 배지 두 종류는 **항상** 렌더한다 — 배지 유무로 상태를 표현하면 사용자가 "표시가 없는 것"과
 * "재정의 가능"을 구별할 수 없다(firehub-web `SettingStateBadge` 의 규칙을 계승).
 */
function OverrideBadge({ settingKey }: { settingKey: string }) {
  if (badgeKindOf(settingKey) === 'tenant-overridable') {
    return <Badge variant="outline">테넌트 재정의 가능</Badge>;
  }
  return (
    <Badge variant="outline" aria-label="전역 고정: 모든 워크스페이스에 이 값만 적용됩니다">
      <Lock className="h-3 w-3" />
      전역 고정
    </Badge>
  );
}

/** 배지에 딸리는 정적 문구. 툴팁이 아니라 텍스트인 이유: 호버할 수 없는 사용자도 알아야 한다. */
function OverrideNote({ settingKey }: { settingKey: string }) {
  return (
    <p className="text-sm text-muted-foreground">
      {badgeKindOf(settingKey) === 'tenant-overridable'
        ? '워크스페이스가 자기 값으로 재정의할 수 있습니다. 재정의하지 않은 워크스페이스에만 이 값이 적용됩니다.'
        : '모든 워크스페이스에 이 값이 적용됩니다.'}
    </p>
  );
}

export function SettingField({
  spec,
  value,
  onChange,
  disabled,
  error,
  description,
  usingBuiltinDefault,
  maskedValue,
  cleared,
  onClear,
}: SettingFieldProps) {
  const id = `setting-${spec.key.replace(/\./g, '-')}`;

  return (
    <div className="space-y-2">
      <div className="flex flex-wrap items-center gap-2">
        <Label htmlFor={id}>{spec.label}</Label>
        <OverrideBadge settingKey={spec.key} />
        {usingBuiltinDefault && <Badge variant="outline">내장 기본값</Badge>}
      </div>

      {spec.kind === 'textarea' ? (
        <Textarea id={id} rows={6} value={value} disabled={disabled} onChange={(e) => onChange(e.target.value)} />
      ) : spec.kind === 'select' ? (
        <Select value={value} disabled={disabled} onValueChange={onChange}>
          <SelectTrigger id={id} className="w-full max-w-md">
            <SelectValue placeholder={`${spec.label}을(를) 선택하세요`} />
          </SelectTrigger>
          <SelectContent>
            {(spec.options ?? []).map((opt) => (
              <SelectItem key={opt.value} value={opt.value}>
                {opt.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      ) : spec.kind === 'secret' ? (
        <div className="space-y-1.5">
          <PasswordInput
            id={id}
            className="max-w-md"
            autoComplete="new-password"
            placeholder="새 값을 입력하면 교체됩니다"
            value={value}
            disabled={disabled || cleared}
            onChange={(e) => onChange(e.target.value)}
          />
          <div className="flex items-center gap-2">
            <p className="text-sm text-muted-foreground">
              {cleared
                ? '저장하면 이 값이 삭제됩니다.'
                : maskedValue
                  ? spec.clearable
                    ? `현재 설정됨 · ${maskedValue}`
                    : `현재 설정됨 · ${maskedValue} · 삭제할 수 없으며 교체만 가능합니다`
                  : '설정되지 않음'}
            </p>
            {/*
              `지우기` 는 명시적으로 빈 문자열을 보내는 조작이다. `ai.api_key` 는 서버가 빈 값을
              거부하므로 버튼 자체를 렌더하지 않는다 — 보여주고 400 으로 실패시키는 것보다 낫다.
            */}
            {!disabled && !cleared && spec.clearable && maskedValue && onClear && (
              <Button type="button" variant="ghost" size="sm" onClick={onClear}>
                지우기
              </Button>
            )}
          </div>
        </div>
      ) : spec.kind === 'switch' ? (
        <Switch
          id={id}
          // 값은 문자열이다. 서버 `validateValues` 에 이 키의 case 가 하나도 없어 무엇이 와도
          // 그대로 저장되므로, 렌더는 `value === 'true'` 로 좁히고 전송은 리터럴 문자열로 고정한다.
          checked={value === 'true'}
          disabled={disabled}
          onCheckedChange={(checked) => onChange(checked ? 'true' : 'false')}
        />
      ) : (
        <Input
          id={id}
          type={spec.kind === 'number' ? 'number' : 'text'}
          className="max-w-md"
          value={value}
          disabled={disabled}
          onChange={(e) => onChange(e.target.value)}
        />
      )}

      {error && <p className="text-sm text-destructive">{error}</p>}
      {description && <p className="text-sm text-muted-foreground">{description}</p>}
      {usingBuiltinDefault && (
        <p className="text-sm text-muted-foreground">
          아직 저장된 값이 없어 코드 기본값이 적용되고 있습니다. 저장하면 이 값이 플랫폼 기본값이
          됩니다.
        </p>
      )}
      <OverrideNote settingKey={spec.key} />
    </div>
  );
}
