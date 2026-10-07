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
import { Label } from '../../../components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '../../../components/ui/select';
import { handleApiError } from '../../../lib/api-error';
import type { GrantCandidates } from '../../../types/security-level';

type GrantType = 'USER' | 'ROLE';

/**
 * 허용 목록 항목 추가(dataset:grant). 후보는 전용 API(판단 사항 15) — user:read 없이도 동작.
 * 동명이인 구분을 위해 사용자 후보는 이메일을 함께 보여 준다.
 */
export function AddAccessGrantDialog({
  datasetId,
  open,
  onOpenChange,
}: {
  datasetId: number;
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
        <AddAccessGrantForm datasetId={datasetId} candidates={data} onClose={() => onOpenChange(false)} />
      </DialogContent>
    </Dialog>
  );
}

/** 다이얼로그 내용 — 열릴 때 마운트되어 유형(역할)·대상(없음)으로 시작한다. */
function AddAccessGrantForm({
  datasetId,
  candidates,
  onClose,
}: {
  datasetId: number;
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

  const options =
    type === 'USER'
      ? (candidates?.users ?? []).map((u) => ({ id: u.id, label: `${u.name} (${u.email})` }))
      : (candidates?.roles ?? []).map((r) => ({ id: r.id, label: r.name }));

  return (
    <>
      <DialogHeader>
        <DialogTitle>허용 목록 추가</DialogTitle>
        <DialogDescription>이 데이터셋을 볼 수 있는 역할 또는 사용자를 추가합니다.</DialogDescription>
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
