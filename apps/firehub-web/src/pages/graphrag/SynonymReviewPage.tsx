import { useState } from 'react';
import { toast } from 'sonner';

import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '@/components/ui/table';
import {
  useApproveSynonymDecision, useRejectSynonymDecision, useSynonymDecisionsPending,
} from '@/hooks/queries/useSynonymDecisions';
import { handleApiError } from '@/lib/api-error';

// 근접쌍(코사인 0.5~0.78) LLM "같다" 판정 검수 대기열 — 승인하면 지식그래프에 즉시 병합, 거부하면 별개로 유지.
export default function SynonymReviewPage() {
  const { data: pending, isLoading } = useSynonymDecisionsPending();
  const approveMutation = useApproveSynonymDecision();
  const rejectMutation = useRejectSynonymDecision();
  // 승인/거부 진행 중인 행 id — 버튼 중복 클릭 방지 및 개별 로딩 표시용.
  const [processingId, setProcessingId] = useState<number | null>(null);

  const handleApprove = async (id: number) => {
    setProcessingId(id);
    try {
      await approveMutation.mutateAsync(id);
      toast.success('병합을 승인했습니다.');
    } catch (err) {
      handleApiError(err, '승인 처리에 실패했습니다.');
    } finally {
      setProcessingId(null);
    }
  };

  const handleReject = async (id: number) => {
    setProcessingId(id);
    try {
      await rejectMutation.mutateAsync(id);
      toast.success('병합을 거부했습니다.');
    } catch (err) {
      handleApiError(err, '거부 처리에 실패했습니다.');
    } finally {
      setProcessingId(null);
    }
  };

  return (
    <div className="space-y-4 p-6">
      <div>
        <h1 className="text-2xl font-semibold">근접쌍 동의어 검수</h1>
        <p className="text-muted-foreground text-sm">
          LLM이 같은 대상으로 판정한 근접 이름쌍입니다. 승인하면 지식그래프에서 즉시 하나로 병합됩니다.
        </p>
      </div>
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>타입</TableHead>
            <TableHead>이름 A</TableHead>
            <TableHead>이름 B</TableHead>
            <TableHead>유사도</TableHead>
            <TableHead>LLM 근거</TableHead>
            <TableHead className="text-right">조치</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {isLoading && (
            <TableRow>
              <TableCell colSpan={6} className="text-muted-foreground text-center">불러오는 중...</TableCell>
            </TableRow>
          )}
          {!isLoading && (pending?.length ?? 0) === 0 && (
            <TableRow>
              <TableCell colSpan={6} className="text-muted-foreground text-center">검수 대기 중인 근접쌍이 없습니다.</TableCell>
            </TableRow>
          )}
          {pending?.map((row) => (
            <TableRow key={row.id}>
              <TableCell><Badge variant="secondary">{row.entityType}</Badge></TableCell>
              <TableCell>{row.nameA}</TableCell>
              <TableCell>{row.nameB}</TableCell>
              <TableCell>{row.similarity != null ? row.similarity.toFixed(3) : '-'}</TableCell>
              <TableCell className="max-w-xs truncate" title={row.rationale ?? ''}>{row.rationale ?? '-'}</TableCell>
              <TableCell className="text-right space-x-2">
                <Button
                  size="sm"
                  disabled={processingId === row.id}
                  onClick={() => handleApprove(row.id)}
                >
                  승인
                </Button>
                <Button
                  size="sm"
                  variant="outline"
                  disabled={processingId === row.id}
                  onClick={() => handleReject(row.id)}
                >
                  거부
                </Button>
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
}
