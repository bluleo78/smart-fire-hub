import { toast } from 'sonner';

import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { DeleteConfirmDialog } from '@/components/ui/delete-confirm-dialog';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { useDeleteOntology, useOntologyList, useOntologyStatusTransition } from '@/hooks/queries/useOntology';
import { handleApiError } from '@/lib/api-error';
import { formatDate } from '@/lib/formatters';
import { ONTOLOGY_STATUS_LABEL, type OntologyStatus, type OntologySummary } from '@/types/ontology';

interface OntologyManageDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** 행을 클릭하면 그 온톨로지를 선택 상태로 만들고 다이얼로그를 닫는다. */
  onSelect: (id: number) => void;
}

// 삭제할 수 없는 이유. null이면 삭제 가능.
// 기본 온톨로지 판정(o.isDefault)은 서버(OntologyService.DEFAULT_ONTOLOGY_ID)가 내려준다 —
// "기본 온톨로지"의 기준이 바뀌어도 프론트가 매직넘버를 따로 들고 있다가 조용히 틀린 UI를 보여주지 않게 한다.
// 사유 문구 자체는 프론트가 표현한다.
function deleteBlockReason(o: OntologySummary): string | null {
  if (o.isDefault) return '기본 온톨로지';
  if (o.datasetCount > 0) return `${o.datasetCount}개 데이터셋이 사용 중`;
  return null;
}

/**
 * 온톨로지 관리 — 목록 테이블과 생명주기 액션.
 * 드롭다운만으로는 엔티티 수·바인딩 수·수정일을 볼 수 없어, "지워도 되는지"를 판단할 수 없다.
 * 전용 페이지(라우트·사이드바 메뉴 신설) 대신 다이얼로그로 둬서 진입 비용을 낮췄다.
 */
export default function OntologyManageDialog({ open, onOpenChange, onSelect }: OntologyManageDialogProps) {
  const { data: ontologies } = useOntologyList('all');
  const deleteOntology = useDeleteOntology();

  const handleDelete = async (o: OntologySummary) => {
    try {
      await deleteOntology.mutateAsync(o.id);
      toast.success(`온톨로지 "${o.domain}"이(가) 삭제되었습니다.`);
    } catch (error) {
      handleApiError(error, '온톨로지 삭제에 실패했습니다.');
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-3xl" data-testid="ontology-manage-dialog">
        <DialogHeader>
          <DialogTitle>온톨로지 관리</DialogTitle>
          <DialogDescription>
            참조 중인 온톨로지는 삭제할 수 없습니다. 운영을 마쳤다면 은퇴시키세요 — 기존 적재 데이터는 보존됩니다.
          </DialogDescription>
        </DialogHeader>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>도메인</TableHead>
              <TableHead>상태</TableHead>
              <TableHead>엔티티</TableHead>
              <TableHead>바인딩</TableHead>
              <TableHead>수정</TableHead>
              <TableHead className="text-right">액션</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {(ontologies ?? []).map((o) => {
              const blocked = deleteBlockReason(o);
              return (
                <TableRow
                  key={o.id}
                  className="cursor-pointer"
                  onClick={() => {
                    onSelect(o.id);
                    onOpenChange(false);
                  }}
                >
                  <TableCell>{o.domain}</TableCell>
                  <TableCell>
                    <Badge variant={o.status === 'active' ? 'secondary' : o.status === 'draft' ? 'warning' : 'outline'}>
                      {ONTOLOGY_STATUS_LABEL[o.status]}
                    </Badge>
                  </TableCell>
                  <TableCell>{o.entityCount}</TableCell>
                  <TableCell className="text-muted-foreground">
                    {o.datasetCount > 0 ? `${o.datasetCount}개 데이터셋` : '없음'}
                  </TableCell>
                  <TableCell className="text-muted-foreground">{formatDate(o.updatedAt)}</TableCell>
                  <TableCell className="text-right" onClick={(e) => e.stopPropagation()}>
                    <div className="flex justify-end gap-2">
                      <OntologyLifecycleAction ontology={o} />
                      {blocked ? (
                        <span className="text-xs text-muted-foreground">{blocked}</span>
                      ) : (
                        <DeleteConfirmDialog
                          entityName="온톨로지"
                          itemName={o.domain}
                          onConfirm={() => handleDelete(o)}
                          trigger={
                            <Button variant="ghost" size="sm">
                              삭제
                            </Button>
                          }
                        />
                      )}
                    </div>
                  </TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      </DialogContent>
    </Dialog>
  );
}

/**
 * 상태 전이 버튼. active면 은퇴, archived면 복귀. draft는 활성화를 배너에서 하므로 버튼이 없다
 * (활성화 전에 내용을 봐야 하는데 이 표는 내용을 보여주지 않는다).
 */
function OntologyLifecycleAction({ ontology }: { ontology: OntologySummary }) {
  const { transition, isPending } = useOntologyStatusTransition();

  if (ontology.status === 'draft') return null;
  // 기본 온톨로지는 은퇴도 금지 — 문서 적재가 의존한다. 판정은 서버(isDefault)가 내려준다.
  if (ontology.status === 'active' && ontology.isDefault) return null;

  const target: OntologyStatus = ontology.status === 'active' ? 'archived' : 'active';
  const label = target === 'archived' ? '은퇴' : '복귀';

  const run = () =>
    transition({
      id: ontology.id,
      status: target,
      successMessage: `온톨로지 "${ontology.domain}"이(가) ${label}되었습니다.`,
      failureMessage: `온톨로지 ${label}에 실패했습니다.`,
    });

  return (
    <Button variant="ghost" size="sm" onClick={run} disabled={isPending}>
      {label}
    </Button>
  );
}
