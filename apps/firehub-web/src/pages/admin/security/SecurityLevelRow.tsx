import { ArrowDown, ArrowUp, ChevronDown, MoreHorizontal } from 'lucide-react';
import { useId, useState } from 'react';

import { Badge } from '../../../components/ui/badge';
import { Button } from '../../../components/ui/button';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '../../../components/ui/dropdown-menu';
import { Input } from '../../../components/ui/input';
import { Label } from '../../../components/ui/label';
import { RadioGroup, RadioGroupItem } from '../../../components/ui/radio-group';
import { SecurityLevelBadge } from '../../../components/ui/SecurityLevelBadge';
import { Switch } from '../../../components/ui/switch';
import { toRequest } from '../../../lib/security-level';
import type {
  SecurityLevel,
  SecurityLevelRequest,
  SecurityLevelUsage,
} from '../../../types/security-level';

interface Props {
  level: SecurityLevel;
  usage?: SecurityLevelUsage;
  isFirst: boolean;
  isLast: boolean;
  onMoveUp: () => void;
  onMoveDown: () => void;
  onSave: (req: SecurityLevelRequest, wasAllowlistRequired: boolean) => void;
  onSetDefault: () => void;
  onDelete: () => void;
  saving: boolean;
  /** 이름 중복(409) 등 이름 필드 인라인 오류 — 토스트가 아니라 입력란 아래에 표시한다. */
  nameError?: string;
  /** 이름을 고치기 시작하면 부모가 오류를 지우도록 알린다. */
  onNameEdit?: () => void;
}

/** 라디오 묶음 — 레이블 클릭 영역을 넓히려고 Label 로 감싼다. */
function PolicyRadio<T extends string>({
  label,
  value,
  options,
  onChange,
  hint,
}: {
  label: string;
  value: T;
  options: { value: T; label: string }[];
  onChange: (v: T) => void;
  hint?: string;
}) {
  // 라디오 라벨-입력 연결용 id 접두(09-form-patterns §J). 등급 행마다 같은 정책 라디오가 반복돼 하드코딩 id 는 충돌한다.
  const baseId = useId();
  return (
    <div className="space-y-1.5">
      <p className="text-sm font-medium">{label}</p>
      <RadioGroup
        value={value}
        onValueChange={(v) => onChange(v as T)}
        className="flex flex-wrap gap-4"
        aria-label={label}
      >
        {options.map((o) => (
          <Label
            key={o.value}
            htmlFor={`${baseId}-${o.value}`}
            className="flex items-center gap-1.5 font-normal"
          >
            <RadioGroupItem
              id={`${baseId}-${o.value}`}
              value={o.value}
              aria-label={o.label}
            />
            {o.label}
          </Label>
        ))}
      </RadioGroup>
      {hint && <p className="text-xs text-muted-foreground">{hint}</p>}
    </div>
  );
}

/**
 * 등급 한 행(목업 s1 "등급별 펼침 행"). 접힌 상태: ↑↓·배지·기본·사용량·⋯. 펼치면 정책 폼 + 명시 저장(자동저장 없음 —
 * 접근 범위를 바꾸는 변경이라 영향 확인을 거친다).
 */
export function SecurityLevelRow({
  level,
  usage,
  isFirst,
  isLast,
  onMoveUp,
  onMoveDown,
  onSave,
  onSetDefault,
  onDelete,
  saving,
  nameError,
  onNameEdit,
}: Props) {
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState<SecurityLevelRequest>(() => toRequest(level));
  const set = <K extends keyof SecurityLevelRequest>(
    k: K,
    v: SecurityLevelRequest[K],
  ) => setForm((f) => ({ ...f, [k]: v }));

  return (
    <div data-testid="security-level-row" className="rounded-md border">
      <div className="flex items-center gap-2 px-3 py-2">
        <Button
          variant="ghost"
          size="icon"
          className="h-7 w-7"
          aria-label={`${level.name} 위로`}
          disabled={isFirst}
          onClick={onMoveUp}
        >
          <ArrowUp className="h-4 w-4" />
        </Button>
        <Button
          variant="ghost"
          size="icon"
          className="h-7 w-7"
          aria-label={`${level.name} 아래로`}
          disabled={isLast}
          onClick={onMoveDown}
        >
          <ArrowDown className="h-4 w-4" />
        </Button>
        <SecurityLevelBadge level={level} />
        {level.isDefault && <Badge variant="outline">기본</Badge>}
        <span className="ml-auto text-sm text-muted-foreground">
          {usage
            ? `데이터셋 ${usage.datasetCount} · 역할 ${usage.roleCount}`
            : ''}
        </span>
        <Button
          variant="ghost"
          size="icon"
          className="h-7 w-7"
          aria-label={`${level.name} 펼치기`}
          aria-expanded={open}
          onClick={() => setOpen((o) => !o)}
        >
          <ChevronDown
            className={`h-4 w-4 transition-transform ${open ? 'rotate-180' : ''}`}
          />
        </Button>
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button
              variant="ghost"
              size="icon"
              className="h-7 w-7"
              aria-label={`${level.name} 메뉴`}
            >
              <MoreHorizontal className="h-4 w-4" />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end">
            <DropdownMenuItem
              disabled={level.isDefault}
              onSelect={onSetDefault}
            >
              기본으로 지정
            </DropdownMenuItem>
            <DropdownMenuItem
              disabled={level.isDefault}
              onSelect={onDelete}
              className="text-destructive"
            >
              삭제
            </DropdownMenuItem>
            {level.isDefault && (
              <p className="px-2 py-1 text-xs text-muted-foreground">
                다른 등급을 기본으로 지정한 뒤 삭제하세요
              </p>
            )}
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
      {open && (
        <div className="space-y-4 border-t px-4 py-4">
          <div className="space-y-1.5">
            <Label htmlFor={`level-name-${level.id}`}>이름</Label>
            <Input
              id={`level-name-${level.id}`}
              value={form.name}
              maxLength={50}
              aria-invalid={nameError ? true : undefined}
              aria-describedby={
                nameError ? `level-name-error-${level.id}` : undefined
              }
              onChange={(e) => {
                set('name', e.target.value);
                onNameEdit?.();
              }}
            />
            {nameError && (
              <p
                id={`level-name-error-${level.id}`}
                role="alert"
                className="text-xs text-destructive"
              >
                {nameError}
              </p>
            )}
          </div>
          <div className="flex items-center justify-between">
            <Label htmlFor={`allowlist-${level.id}`}>허용 목록 필요</Label>
            <Switch
              id={`allowlist-${level.id}`}
              aria-label="허용 목록 필요"
              checked={form.allowlistRequired}
              onCheckedChange={(v) => set('allowlistRequired', v)}
            />
          </div>
          <div className="flex items-center justify-between">
            <div>
              <Label htmlFor={`bypass-${level.id}`}>ADMIN 우회</Label>
              <p className="text-xs text-muted-foreground">
                허용 목록 꺼지면 비활성
              </p>
            </div>
            <Switch
              id={`bypass-${level.id}`}
              aria-label="ADMIN 우회"
              disabled={!form.allowlistRequired}
              checked={form.adminBypass}
              onCheckedChange={(v) => set('adminBypass', v)}
            />
          </div>
          <PolicyRadio
            label="내보내기"
            value={form.exportPolicy}
            onChange={(v) => set('exportPolicy', v)}
            options={[
              { value: 'ALLOW', label: '허용' },
              { value: 'PERMISSION', label: '권한 필요' },
              { value: 'DENY', label: '차단' },
            ]}
          />
          <PolicyRadio
            label="AI 분석"
            value={form.aiPolicy}
            onChange={(v) => set('aiPolicy', v)}
            options={[
              { value: 'ALL', label: '전체' },
              { value: 'SELF_HOSTED_ONLY', label: '자체 호스팅 모델만' },
              { value: 'DENY', label: '차단' },
            ]}
          />
          <PolicyRadio
            label="외부 공유·전달"
            value={form.sharePolicy}
            onChange={(v) => set('sharePolicy', v)}
            hint="GraphRAG·리포트 메일·Slack"
            options={[
              { value: 'ALLOW', label: '허용' },
              { value: 'DENY', label: '차단' },
            ]}
          />
          <div className="flex items-center justify-between">
            <Label htmlFor={`audit-${level.id}`}>감사 기록</Label>
            <Switch
              id={`audit-${level.id}`}
              aria-label="감사 기록"
              checked={form.auditAccess}
              onCheckedChange={(v) => set('auditAccess', v)}
            />
          </div>
          <div className="flex justify-end">
            <Button
              disabled={saving || !form.name.trim()}
              onClick={() =>
                onSave(
                  {
                    ...form,
                    name: form.name.trim(),
                    adminBypass: form.allowlistRequired && form.adminBypass,
                  },
                  level.allowlistRequired,
                )
              }
            >
              저장
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}
