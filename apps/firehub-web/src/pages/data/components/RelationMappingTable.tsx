import type { RefObject } from 'react';

import { Button } from '../../../components/ui/button';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '../../../components/ui/table';
import type { DraftEntity, DraftRelation } from '../../../lib/mapping-spec';
import { entityLabel } from '../../../lib/mapping-spec';

interface RelationMappingTableProps {
  relations: DraftRelation[];
  entities: DraftEntity[];
  /**
   * 편집 콜백. 셋 다 없으면 조회 전용 — 추가 버튼과 액션 열을 그리지 않는다(WD-131: dataset:write 가 없으면
   * 누르면 늘 403 인 편집 버튼을 보여 주지 않는다).
   */
  onAdd?: () => void;
  onEdit?: (relation: DraftRelation) => void;
  onDelete?: (relation: DraftRelation) => void;
  /** 삭제 확인 다이얼로그가 닫힐 때 사라진 행 대신 포커스를 받을 지점(#328). */
  addButtonRef?: RefObject<HTMLButtonElement | null>;
}

/** 관계 매핑 목록. 끝점은 ID로 저장돼 있으므로 표시 시 엔티티를 조회해 라벨로 바꾼다. */
export function RelationMappingTable({
  relations,
  entities,
  onAdd,
  onEdit,
  onDelete,
  addButtonRef,
}: RelationMappingTableProps) {
  const labelOf = (id: string) => {
    const entity = entities.find((e) => e.id === id);
    return entity ? entityLabel(entity) : '(삭제됨)';
  };

  // 끝점으로 쓸 엔티티가 2개 미만이면 관계를 만들 수 없다.
  // 비활성 버튼은 이유가 없으면 "고장난 버튼"으로 읽히므로 사유 문장을 함께 노출한다(#300).
  const addDisabled = entities.length < 2;
  const editable = onAdd != null && onEdit != null && onDelete != null;

  return (
    <div className="space-y-2" data-testid="relation-mapping-table">
      <div className="flex items-center justify-between">
        <h3 className="text-base leading-6 font-semibold">관계 매핑 ({relations.length}개)</h3>
        {editable && (
          <Button
            ref={addButtonRef}
            variant="outline"
            size="sm"
            onClick={onAdd}
            disabled={addDisabled}
            aria-describedby={addDisabled ? 'relation-add-hint' : undefined}
          >
            관계 매핑 추가
          </Button>
        )}
      </div>

      {editable && addDisabled && (
        // 오류가 아니라 안내이므로 destructive를 쓰지 않는다.
        <p id="relation-add-hint" className="text-sm text-muted-foreground">
          엔티티 매핑을 2개 이상 추가하면 관계를 연결할 수 있습니다.
        </p>
      )}

      <div className="rounded-md border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>주어</TableHead>
              <TableHead>관계</TableHead>
              <TableHead>목적어</TableHead>
              {editable && <TableHead className="w-32">액션</TableHead>}
            </TableRow>
          </TableHeader>
          <TableBody>
            {relations.length === 0 ? (
              <TableRow>
                <TableCell colSpan={editable ? 4 : 3} className="text-center text-sm text-muted-foreground">
                  아직 관계 매핑이 없습니다.
                </TableCell>
              </TableRow>
            ) : (
              relations.map((r) => (
                <TableRow key={r.id} data-testid={`relation-row-${r.relation}`}>
                  <TableCell>{labelOf(r.subjectId)}</TableCell>
                  <TableCell className="font-medium">{r.relation}</TableCell>
                  <TableCell>{labelOf(r.objectId)}</TableCell>
                  {editable && (
                    <TableCell>
                      <div className="flex gap-1">
                        <Button variant="ghost" size="sm" onClick={() => onEdit(r)}>
                          수정
                        </Button>
                        <Button variant="ghost" size="sm" onClick={() => onDelete(r)}>
                          삭제
                        </Button>
                      </div>
                    </TableCell>
                  )}
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
      </div>
    </div>
  );
}
