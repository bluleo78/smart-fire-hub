import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { toast } from 'sonner';

import { securityLevelsApi } from '../../../api/security-levels';
import { Button } from '../../../components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '../../../components/ui/dialog';
import { InlineBanner } from '../../../components/ui/inline-banner';
import { Label } from '../../../components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '../../../components/ui/select';
import { handleApiError } from '../../../lib/api-error';
import type { GrantCandidates, SecurityLevelSummary, SubjectClearance } from '../../../types/security-level';

type GrantType = 'USER' | 'ROLE';

/** 대상 선택지 한 줄 — 등급 부족 경고에 쓰려고 이름·등급·충족 여부를 함께 든다(WD-292). */
interface SubjectOption {
  id: number;
  label: string;
  name: string;
  clearance: SubjectClearance | null | undefined;
  sufficientClearance: boolean | undefined;
}

/**
 * 선택지 라벨 끝에 붙이는 열람 등급 표기(WD-292). 서버가 등급을 주지 않으면(이전 응답) 아무것도 붙이지 않는다.
 * 부족한 대상은 색이 아니라 글자로 「등급 부족」을 덧붙여 훑어볼 때도 구분되게 한다.
 */
function clearanceSuffix(clearance: SubjectClearance | null | undefined, sufficient: boolean | undefined): string {
  if (clearance === undefined) return '';
  const name = clearance === null ? '열람 등급 없음' : `열람 등급 ${clearance.levelName ?? clearance.rank}`;
  return sufficient === false ? ` · ${name} (등급 부족)` : ` · ${name}`;
}

/**
 * 선택한 대상의 열람 등급이 데이터셋 등급보다 낮을 때의 경고 문장(WD-292). 열람 판정은 등급을 허용 목록보다 먼저 보므로,
 * 허용 목록에 넣어도 볼 수 없다는 사실과 고칠 방법(역할은 그 역할 등급, 사용자는 보유 역할 등급)을 함께 알린다.
 * 역할은 같은 사람이 등급 높은 다른 역할로 볼 수도 있으므로 「이 역할만으로는」이라고 말한다.
 */
function insufficientMessage(type: GrantType, o: SubjectOption, levelName: string): string {
  const current = o.clearance?.levelName ?? null;
  if (type === 'ROLE') {
    return current
      ? `${o.name} 역할의 열람 등급은 「${current}」라 허용 목록에 있어도 이 역할만으로는 이 데이터셋(「${levelName}」)을 볼 수 없습니다. 역할의 열람 등급을 「${levelName}」 이상으로 올려야 합니다.`
      : `${o.name} 역할의 열람 등급이 이 데이터셋 등급(「${levelName}」)보다 낮아 이 역할만으로는 볼 수 없습니다. 역할의 열람 등급을 「${levelName}」 이상으로 올려야 합니다.`;
  }
  return current
    ? `${o.name} 님의 열람 등급은 「${current}」라 허용 목록에 있어도 이 데이터셋(「${levelName}」)을 볼 수 없습니다. 보유 역할 중 하나의 열람 등급을 「${levelName}」 이상으로 올려야 합니다.`
    : `${o.name} 님은 열람 등급이 있는 역할이 없어 허용 목록에 있어도 이 데이터셋(「${levelName}」)을 볼 수 없습니다. 열람 등급이 「${levelName}」 이상인 역할을 부여해야 합니다.`;
}

/**
 * 허용 목록 항목 추가(dataset:grant). 후보는 전용 API(판단 사항 15) — user:read 없이도 동작.
 * 동명이인 구분을 위해 사용자 후보는 이메일을 함께 보여 준다.
 * WD-292: 열람은 「열람 등급 ≥ 데이터셋 등급」이면서 허용 목록에 있어야 하므로, 후보마다 열람 등급을 보여 주고
 * 등급이 모자란 대상을 고르면 경고한다. 추가 자체는 막지 않는다 — 등급을 올리기 전에 미리 등록해 두는 것도 정당한 사용이다.
 */
export function AddAccessGrantDialog({
  datasetId,
  level,
  open,
  onOpenChange,
}: {
  datasetId: number;
  /** 데이터셋 보안 등급 — 설명 문장과 등급 부족 경고에 이름을 쓴다(WD-292). */
  level: SecurityLevelSummary;
  open: boolean;
  onOpenChange: (o: boolean) => void;
}) {
  const { data } = useQuery({
    queryKey: ['datasets', datasetId, 'access-grants', 'candidates'],
    queryFn: () => securityLevelsApi.grantCandidates(datasetId).then((r) => r.data),
    enabled: open,
  });

  // 입력(유형·대상)은 내부 폼이 갖는다 — DialogContent 는 닫히면 내용을 언마운트하므로 다시 열면 빈 폼에서 시작한다(닫기마다 초기화 불필요).
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <AddAccessGrantForm
          datasetId={datasetId}
          level={level}
          candidates={data}
          onClose={() => onOpenChange(false)}
        />
      </DialogContent>
    </Dialog>
  );
}

/** 다이얼로그 내용 — 열릴 때 마운트되어 유형(역할)·대상(없음)으로 시작한다. */
function AddAccessGrantForm({
  datasetId,
  level,
  candidates,
  onClose,
}: {
  datasetId: number;
  level: SecurityLevelSummary;
  candidates: GrantCandidates | undefined;
  onClose: () => void;
}) {
  const qc = useQueryClient();
  const [type, setType] = useState<GrantType>('ROLE');
  const [subject, setSubject] = useState('');

  const add = useMutation({
    mutationFn: () =>
      securityLevelsApi.addGrant(
        datasetId,
        type === 'USER' ? { userId: Number(subject) } : { roleId: Number(subject) },
      ),
    onSuccess: () => {
      toast.success('허용 목록에 추가했습니다');
      void qc.invalidateQueries({ queryKey: ['datasets', datasetId, 'access-grants'] });
      onClose();
    },
    // GRANT_SUBJECT_INVALID 등은 서버가 한국어 message 를 싣는다.
    onError: (e) => handleApiError(e, '허용 목록 추가에 실패했습니다.'),
  });

  const options: SubjectOption[] =
    type === 'USER'
      ? (candidates?.users ?? []).map((u) => ({
          id: u.id,
          label: `${u.name} (${u.email})${clearanceSuffix(u.clearance, u.sufficientClearance)}`,
          name: u.name,
          clearance: u.clearance,
          sufficientClearance: u.sufficientClearance,
        }))
      : (candidates?.roles ?? []).map((r) => ({
          id: r.id,
          label: `${r.name}${clearanceSuffix(r.clearance, r.sufficientClearance)}`,
          name: r.name,
          clearance: r.clearance,
          sufficientClearance: r.sufficientClearance,
        }));
  // 고른 대상이 등급 부족이면 경고한다 — 서버 판정값(sufficientClearance)이 명시적으로 false 일 때만(이전 응답이면 판단하지 않음).
  const selected = options.find((o) => String(o.id) === subject);
  const insufficient = selected?.sufficientClearance === false ? selected : undefined;

  return (
    <>
      <DialogHeader>
        <DialogTitle>허용 목록 추가</DialogTitle>
        {/* WD-292: 허용 목록만으로 볼 수 있는 것이 아니다 — 등급 조건을 함께 밝힌다(판정은 등급을 먼저 본다). */}
        <DialogDescription>
          {`이 데이터셋을 볼 수 있게 할 역할 또는 사용자를 추가합니다. 열람 등급이 데이터셋 등급(${level.name}) 이상인 대상만 실제로 볼 수 있습니다.`}
        </DialogDescription>
      </DialogHeader>
      <div className="space-y-4">
        <div className="space-y-1.5">
          <Label htmlFor="grant-type">유형</Label>
          <Select
            value={type}
            onValueChange={(v) => {
              setType(v as GrantType);
              setSubject('');
            }}
          >
            <SelectTrigger id="grant-type" aria-label="유형" className="w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="ROLE">역할</SelectItem>
              <SelectItem value="USER">사용자</SelectItem>
            </SelectContent>
          </Select>
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="grant-subject">대상</Label>
          <Select value={subject} onValueChange={setSubject}>
            <SelectTrigger id="grant-subject" aria-label="대상" className="w-full">
              <SelectValue placeholder="선택…" />
            </SelectTrigger>
            <SelectContent>
              {options.map((o) => (
                <SelectItem key={o.id} value={String(o.id)}>
                  {o.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        {insufficient && (
          <InlineBanner variant="warning" data-testid="grant-insufficient-clearance">
            {insufficientMessage(type, insufficient, level.name)}
          </InlineBanner>
        )}
      </div>
      <DialogFooter className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
        <Button variant="outline" onClick={onClose}>
          취소
        </Button>
        <Button disabled={!subject || add.isPending} onClick={() => add.mutate()}>
          추가
        </Button>
      </DialogFooter>
    </>
  );
}
