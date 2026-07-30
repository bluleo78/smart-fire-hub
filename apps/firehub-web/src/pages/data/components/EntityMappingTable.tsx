import type { RefObject } from 'react';

import { Button } from '../../../components/ui/button';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '../../../components/ui/table';
import type { DraftEntity } from '../../../lib/mapping-spec';

interface EntityMappingTableProps {
  entities: DraftEntity[];
  onAdd: () => void;
  onEdit: (entity: DraftEntity) => void;
  onDelete: (entity: DraftEntity) => void;
  /** 삭제 확인 다이얼로그가 닫힐 때 사라진 행 대신 포커스를 받을 지점(#328). */
  addButtonRef?: RefObject<HTMLButtonElement | null>;
}

/** 엔티티 매핑 목록. 상태는 전부 상위(DatasetMappingTab)가 소유하고, 여기서는 렌더와 콜백만 담당한다. */
export function EntityMappingTable({
  entities,
  onAdd,
  onEdit,
  onDelete,
  addButtonRef,
}: EntityMappingTableProps) {
  return (
    <div className="space-y-2" data-testid="entity-mapping-table">
      <div className="flex items-center justify-between">
        <h3 className="text-base leading-6 font-semibold">엔티티 매핑 ({entities.length}개)</h3>
        <Button ref={addButtonRef} variant="outline" size="sm" onClick={onAdd}>
          엔티티 매핑 추가
        </Button>
      </div>

      <div className="rounded-md border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>엔티티 타입</TableHead>
              <TableHead>이름 컬럼</TableHead>
              <TableHead>속성</TableHead>
              <TableHead className="w-32">액션</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {entities.length === 0 ? (
              <TableRow>
                <TableCell colSpan={4} className="text-center text-sm text-muted-foreground">
                  아직 엔티티 매핑이 없습니다.
                </TableCell>
              </TableRow>
            ) : (
              entities.map((e) => (
                <TableRow key={e.id} data-testid={`entity-row-${e.entityType}`}>
                  <TableCell className="font-medium">{e.entityType}</TableCell>
                  <TableCell>{e.nameColumn}</TableCell>
                  <TableCell className="text-sm text-muted-foreground">
                    {e.properties.length === 0
                      ? '-'
                      : e.properties.map((p) => `${p.column} → ${p.propertyName}`).join(', ')}
                  </TableCell>
                  <TableCell>
                    <div className="flex gap-1">
                      <Button variant="ghost" size="sm" onClick={() => onEdit(e)}>
                        수정
                      </Button>
                      <Button variant="ghost" size="sm" onClick={() => onDelete(e)}>
                        삭제
                      </Button>
                    </div>
                  </TableCell>
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
      </div>
    </div>
  );
}
