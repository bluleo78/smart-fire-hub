import { Fragment } from 'react';
import { toast } from 'sonner';

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from '@/components/ui/alert-dialog';
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
import { groupOntologiesByStatus } from '@/lib/ontology-grouping';
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
  // 활성/초안/은퇴가 섞인 채 임의 순서로 나열되던 불일치(#417) — 헤더의 OntologySelect와
  // 동일한 그룹핑 기준(groupOntologiesByStatus)을 적용해 활성 → 초안 → 은퇴 순 섹션으로 보여준다.
  const groups = groupOntologiesByStatus(ontologies ?? []);

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
            {groups.map((group) => (
              <Fragment key={`group-${group.status}`}>
                {/* 섹션 헤더 — 상태 그룹 경계를 표로 보여준다. colSpan은 헤더 컬럼 수(6)와 맞춘다. */}
                <TableRow key={`group-${group.status}`} className="hover:bg-transparent">
                  <TableCell colSpan={6} className="bg-muted/50 py-1.5 text-xs font-medium text-muted-foreground">
                    {group.label} {group.items.length}
                  </TableCell>
                </TableRow>
                {group.items.map((o) => {
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
                      {/* 도메인명 길이 상한이 없어(#416, #409의 동일 증상) 긴 이름이 그대로 렌더되면
                          테이블/다이얼로그 레이아웃이 무너진다 — max-width + truncate 로 한 줄
                          말줄임 처리하고, 잘린 전체 이름은 title로 노출한다. */}
                      <TableCell className="max-w-[240px] truncate" title={o.domain}>
                        {o.domain}
                      </TableCell>
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
              </Fragment>
            ))}
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

  // 은퇴(archived)는 삭제만큼 되돌리기 번거로운 전이(재활성화 필요)이고, 참조 중인 데이터셋이
  // 있어도 서버가 항상 허용하므로 실수로 누르는 걸 막을 게이트가 프론트에 없으면 사고가 난다(#399).
  // 삭제(DeleteConfirmDialog)와 동일하게 확인 다이얼로그를 거치되, 차단하지는 않고 영향 범위(참조
  // 데이터셋 수)만 고지한다 — 차단 여부는 백엔드 정책 결정 사안이라 이슈 수정 범위 밖이다.
  // 복귀(active로 되돌리기)는 파괴적이지 않으므로 기존처럼 즉시 실행한다.
  if (target !== 'archived') {
    return (
      <Button variant="ghost" size="sm" onClick={run} disabled={isPending}>
        {label}
      </Button>
    );
  }

  return (
    <AlertDialog>
      <AlertDialogTrigger asChild onClick={(e) => e.stopPropagation()}>
        <Button variant="ghost" size="sm" disabled={isPending}>
          {label}
        </Button>
      </AlertDialogTrigger>
      <AlertDialogContent onClick={(e) => e.stopPropagation()}>
        <AlertDialogHeader>
          <AlertDialogTitle>온톨로지 은퇴</AlertDialogTitle>
          <AlertDialogDescription>
            &quot;{ontology.domain}&quot; 온톨로지를 은퇴시키겠습니까? 은퇴 후에는 신규 데이터셋 바인딩이
            불가능하며, 기존 적재 데이터는 보존됩니다.
            {ontology.datasetCount > 0 && (
              <> {ontology.datasetCount}개 데이터셋이 이 온톨로지를 사용 중입니다.</>
            )}
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>취소</AlertDialogCancel>
          <AlertDialogAction onClick={run}>은퇴</AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
