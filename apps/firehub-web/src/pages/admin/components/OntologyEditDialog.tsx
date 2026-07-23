import { useState } from 'react';
import { toast } from 'sonner';

import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Textarea } from '@/components/ui/textarea';
import { useUpdateOntology } from '@/hooks/queries/useOntology';
import { extractApiError } from '@/lib/api-error';
import type { EntityTypeDef, OntologySchema } from '@/types/ontology';

interface OntologyEditDialogProps {
  schema: OntologySchema;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

/**
 * 지식 모델 편집 다이얼로그(B-2b 슬라이스 5-1) — 수직 얇은 슬라이스.
 * 편집 대상은 domain + 각 엔티티 타입의 description/naming/resolution뿐이다.
 * relations와 각 엔티티의 properties는 이 슬라이스에서 편집 UI가 없으므로,
 * 원본 스키마에서 그대로 가져와 PUT payload에 손대지 않고 포함한다(전체 삭제·재삽입 방식이라
 * 빠뜨리면 조용히 유실되므로, "편집 대상 필드만 갈아끼우고 나머지는 원본을 그대로 복사"가 핵심 불변식).
 *
 * 폼 state는 useState 초기값으로만 schema를 반영한다(useEffect로 동기화하지 않음) — 대신
 * 호출부(OntologyPage)가 다이얼로그가 열릴 때마다 이 컴포넌트에 key를 바꿔 리마운트시켜
 * 항상 최신 원본으로 새로 시작하게 한다.
 */
export default function OntologyEditDialog({ schema, open, onOpenChange }: OntologyEditDialogProps) {
  const [domain, setDomain] = useState(schema.domain);
  const [entities, setEntities] = useState<EntityTypeDef[]>(schema.entities);
  const updateOntology = useUpdateOntology();

  const updateEntity = (type: string, patch: Partial<EntityTypeDef>) =>
    setEntities((prev) => prev.map((e) => (e.type === type ? { ...e, ...patch } : e)));

  const handleSave = () => {
    updateOntology.mutate(
      {
        domain,
        schemaVersion: schema.schemaVersion,
        entities, // properties는 각 엔티티에 원본 그대로 보존됨(편집 UI 없음)
        relations: schema.relations, // 이 슬라이스는 관계 편집 범위 밖 — 원본 그대로 왕복
      },
      {
        onSuccess: () => {
          toast.success('지식 모델이 저장되었습니다.');
          onOpenChange(false);
        },
        onError: (error) => {
          toast.error(extractApiError(error, '지식 모델 저장에 실패했습니다.'));
        },
      },
    );
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[85vh] max-w-2xl overflow-y-auto" data-testid="ontology-edit-dialog">
        <DialogHeader>
          <DialogTitle>지식 모델 편집</DialogTitle>
          <DialogDescription>
            엔티티 타입의 설명·명명 규칙·해상도 정책을 수정합니다. 관계와 속성은 이 화면에서 변경되지 않습니다.
          </DialogDescription>
        </DialogHeader>

        <div className="flex flex-col gap-6 py-2">
          <div className="flex flex-col gap-2">
            <Label htmlFor="ontology-domain">도메인</Label>
            <Input id="ontology-domain" value={domain} onChange={(e) => setDomain(e.target.value)} />
          </div>

          {entities.map((entity) => (
            <div key={entity.type} className="flex flex-col gap-3 rounded-md border p-4" data-testid={`entity-edit-${entity.type}`}>
              <h3 className="text-sm font-semibold">{entity.type}</h3>

              <div className="flex flex-col gap-2">
                <Label htmlFor={`desc-${entity.type}`}>설명</Label>
                <Textarea
                  id={`desc-${entity.type}`}
                  value={entity.description}
                  onChange={(e) => updateEntity(entity.type, { description: e.target.value })}
                />
              </div>

              <div className="flex flex-col gap-2">
                <Label htmlFor={`naming-${entity.type}`}>명명 규칙</Label>
                <Textarea
                  id={`naming-${entity.type}`}
                  value={entity.naming}
                  onChange={(e) => updateEntity(entity.type, { naming: e.target.value })}
                />
              </div>

              <div className="flex flex-col gap-2">
                <Label htmlFor={`resolution-${entity.type}`}>해상도 정책</Label>
                <Select
                  value={entity.resolution}
                  onValueChange={(v: 'exact' | 'embedding') => updateEntity(entity.type, { resolution: v })}
                >
                  <SelectTrigger id={`resolution-${entity.type}`}>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="exact">정확 매칭</SelectItem>
                    <SelectItem value="embedding">임베딩 해소</SelectItem>
                  </SelectContent>
                </Select>
              </div>
            </div>
          ))}
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={updateOntology.isPending}>
            취소
          </Button>
          <Button onClick={handleSave} disabled={updateOntology.isPending}>
            {updateOntology.isPending ? '저장 중...' : '저장'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
