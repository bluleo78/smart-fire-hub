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
import type { SettingSpec } from '@/lib/settings-catalog';

import { isMaskSentinel } from './build-payload';

export interface SettingFieldProps {
  spec: SettingSpec;
  /** 현재 편집 중인 값. 비밀 키는 "새로 입력한 값"이고 마스크가 아니다(Task 10). */
  value: string;
  onChange: (value: string) => void;
  disabled?: boolean;
  error?: string;
  /** 서버 `SettingResponse.description`. 문구를 프런트에 복제하지 않는다. */
  description?: string | null;
  /** 서버가 준 마스크(`****` / `****last4`). 비밀 키에서만 의미가 있고, 힌트에만 쓴다. */
  maskedValue?: string | null;
  /** 이 비밀 키가 `지우기` 표시되어 있는가. */
  cleared?: boolean;
  /** `지우기` 를 눌렀을 때. `spec.clearable === false` 면 호출부가 넘기지 않는다. */
  onClear?: () => void;
}

/**
 * 설정 필드 하나(라벨·입력·오류·서버 설명).
 *
 * 키별 적용 범위 배지·안내 문구는 두지 않는다(#712): 플랫폼 설정에 남은 키는 전부 모든
 * 워크스페이스에 그대로 적용되므로 키마다 구별할 상태가 없다 — 그 사실은 화면 상단 배너가 한 번 말한다.
 */
export function SettingField({
  spec,
  value,
  onChange,
  disabled,
  error,
  description,
  maskedValue,
  cleared,
  onClear,
}: SettingFieldProps) {
  const id = `setting-${spec.key.replace(/\./g, '-')}`;

  return (
    <div className="space-y-2">
      <Label htmlFor={id}>{spec.label}</Label>

      {spec.kind === 'select' ? (
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
              `지우기` 는 명시적으로 빈 문자열을 보내는 조작이다. 서버가 빈 값을 거부하는 비밀 키
              (`clearable: false`)는 버튼 자체를 렌더하지 않는다 — 보여주고 400 으로 실패시키는 것보다 낫다.
              (리뷰 L4) 눌렀을 때 입력창의 값도 함께 지운다 — 안 지우면 비활성화된 입력창에
              방금 타이핑한 문자가 그대로 남아, 실제로 보내는 값(빈 문자열)과 화면이 어긋난다.
            */}
            {!disabled && !cleared && spec.clearable && maskedValue && onClear && (
              <Button
                type="button"
                variant="ghost"
                size="sm"
                onClick={() => {
                  onClear();
                  onChange('');
                }}
              >
                지우기
              </Button>
            )}
          </div>
          {/*
            (리뷰 L5) 사용자가 우연히 `****`/`****last4` 형태의 값을 타이핑하면 `buildSettingsPayload`
            가 서버 `isMaskSentinel` 과 같은 규칙으로 그 값을 조용히 드롭한다(마스크로 오인되지
            않게 하려는 안전장치가, 여기서는 "왜 저장이 안 되는지 설명 없는 막다른 길"이 된다).
            드롭 자체는 서버 동작과 일치하므로 유지하되, 이유를 알려준다.
          */}
          {!cleared && value !== '' && isMaskSentinel(value) && (
            <p className="text-sm text-destructive">
              이 값은 마스크 표시(****)와 형태가 같아 저장되지 않습니다. 다른 값을 입력하세요.
            </p>
          )}
        </div>
      ) : (
        <Input
          id={id}
          type="text"
          className="max-w-md"
          value={value}
          disabled={disabled}
          onChange={(e) => onChange(e.target.value)}
        />
      )}

      {error && <p className="text-sm text-destructive">{error}</p>}
      {description && <p className="text-sm text-muted-foreground">{description}</p>}
    </div>
  );
}
