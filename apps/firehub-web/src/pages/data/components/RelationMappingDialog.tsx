import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';

import { Button } from '../../../components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '../../../components/ui/dialog';
import { Label } from '../../../components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '../../../components/ui/select';
import type { DraftEntity, DraftRelation } from '../../../lib/mapping-spec';
import { entityLabel } from '../../../lib/mapping-spec';
import type { RelationMappingFormData } from '../../../lib/validations/mapping';
import { relationMappingSchema } from '../../../lib/validations/mapping';
import type { OntologySchema } from '../../../types/ontology';

interface RelationMappingDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  ontology: OntologySchema;
  entities: DraftEntity[];
  /** null이면 추가, 값이 있으면 수정. */
  initial: DraftRelation | null;
  /**
   * 현재 draft의 전체 관계 목록. 제출 시 (subjectId, objectId, relation) 완전 동일 트리플이
   * 이미 있는지 검사하는 데 쓴다(#501) — 구분 불가능한 중복 행이 그대로 저장되는 것을 막는다.
   */
  relations: DraftRelation[];
  onSubmit: (data: RelationMappingFormData) => void;
}

const EMPTY_VALUES: RelationMappingFormData = { subjectId: '', relation: '', objectId: '' };

/**
 * 관계 매핑 추가/수정 폼.
 * 관계 선택지를 **선택된 주어/목적어 타입 조합의 허용 트리플**로 좁혀,
 * 백엔드가 거부할 조합을 애초에 고를 수 없게 한다.
 */
export function RelationMappingDialog({
  open,
  onOpenChange,
  ontology,
  entities,
  initial,
  relations,
  onSubmit,
}: RelationMappingDialogProps) {
  const form = useForm<RelationMappingFormData>({
    resolver: zodResolver(relationMappingSchema),
    defaultValues: EMPTY_VALUES,
  });

  useEffect(() => {
    if (!open) return;
    form.reset(
      initial
        ? { subjectId: initial.subjectId, relation: initial.relation, objectId: initial.objectId }
        : EMPTY_VALUES,
    );
  }, [open, initial, form]);

  const subjectId = form.watch('subjectId');
  const objectId = form.watch('objectId');
  const subjectType = entities.find((e) => e.id === subjectId)?.entityType;
  const objectType = entities.find((e) => e.id === objectId)?.entityType;

  // 같은 relation 이름이 여러 트리플에 나올 수 있으므로 중복을 제거한다.
  const availableRelations =
    subjectType && objectType
      ? Array.from(
          new Set(
            ontology.relations
              .filter((t) => t.subject === subjectType && t.object === objectType)
              .map((t) => t.relation),
          ),
        )
      : [];

  // 끝점이 바뀌면 허용 트리플 집합이 달라지므로 이미 고른 관계를 초기화한다.
  const handleEndpointChange = (field: 'subjectId' | 'objectId', value: string) => {
    form.setValue(field, value, { shouldValidate: true });
    form.setValue('relation', '');
  };

  const submit = (data: RelationMappingFormData) => {
    // 완전 동일 트리플(주어·목적어·관계) 중복 추가를 여기서 막는다(#501). 수정 모드에서는
    // 자기 자신(initial)을 제외해야 원래 값 그대로 "수정 확인"을 눌러도 중복으로 오탐하지 않는다.
    const isDuplicate = relations.some(
      (r) =>
        r.id !== initial?.id &&
        r.subjectId === data.subjectId &&
        r.objectId === data.objectId &&
        r.relation === data.relation,
    );
    if (isDuplicate) {
      form.setError('relation', { type: 'duplicate', message: '이미 동일한 관계 매핑이 있습니다.' });
      document.getElementById('relation-type')?.focus();
      return;
    }
    onSubmit(data);
    onOpenChange(false);
  };

  // 제출 실패 시 첫 오류 필드로 포커스 이동(#330) — EntityMappingDialog와 동일한 이유.
  // Radix Select 트리거는 react-hook-form의 자동 포커스 대상이 아니라 직접 옮긴다.
  const FIELD_ORDER: [keyof RelationMappingFormData, string][] = [
    ['subjectId', 'relation-subject'],
    ['objectId', 'relation-object'],
    ['relation', 'relation-type'],
  ];
  const focusFirstError = (errors: Record<string, unknown>) => {
    const first = FIELD_ORDER.find(([name]) => errors[name]);
    if (first) document.getElementById(first[1])?.focus();
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent data-testid="relation-mapping-dialog">
        <DialogHeader>
          <DialogTitle>{initial ? '관계 매핑 수정' : '관계 매핑 추가'}</DialogTitle>
          <DialogDescription className="sr-only">
            두 엔티티 매핑 사이에 온톨로지가 허용하는 관계를 연결합니다.
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={form.handleSubmit(submit, focusFirstError)} className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="relation-subject">주어 엔티티 *</Label>
            <Select value={subjectId} onValueChange={(v) => handleEndpointChange('subjectId', v)}>
              <SelectTrigger
                id="relation-subject"
                data-testid="relation-subject-select"
                aria-invalid={form.formState.errors.subjectId ? true : undefined}
                aria-describedby={form.formState.errors.subjectId ? 'relation-subject-error' : undefined}
              >
                <SelectValue placeholder="주어 엔티티를 선택하세요" />
              </SelectTrigger>
              <SelectContent>
                {entities.map((e) => (
                  <SelectItem key={e.id} value={e.id}>
                    {entityLabel(e)}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            {form.formState.errors.subjectId && (
              <p id="relation-subject-error" className="text-sm text-destructive">
                {form.formState.errors.subjectId.message}
              </p>
            )}
          </div>

          <div className="space-y-2">
            <Label htmlFor="relation-object">목적어 엔티티 *</Label>
            <Select value={objectId} onValueChange={(v) => handleEndpointChange('objectId', v)}>
              <SelectTrigger
                id="relation-object"
                data-testid="relation-object-select"
                aria-invalid={form.formState.errors.objectId ? true : undefined}
                aria-describedby={form.formState.errors.objectId ? 'relation-object-error' : undefined}
              >
                <SelectValue placeholder="목적어 엔티티를 선택하세요" />
              </SelectTrigger>
              <SelectContent>
                {entities.map((e) => (
                  <SelectItem key={e.id} value={e.id}>
                    {entityLabel(e)}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            {form.formState.errors.objectId && (
              <p id="relation-object-error" className="text-sm text-destructive">
                {form.formState.errors.objectId.message}
              </p>
            )}
          </div>

          <div className="space-y-2">
            <Label htmlFor="relation-type">관계 *</Label>
            <Select
              value={form.watch('relation')}
              onValueChange={(v) => form.setValue('relation', v, { shouldValidate: true })}
            >
              <SelectTrigger
                id="relation-type"
                data-testid="relation-type-select"
                aria-invalid={form.formState.errors.relation ? true : undefined}
                aria-describedby={form.formState.errors.relation ? 'relation-type-error' : undefined}
              >
                <SelectValue placeholder="관계를 선택하세요" />
              </SelectTrigger>
              <SelectContent>
                {availableRelations.map((r) => (
                  <SelectItem key={r} value={r}>
                    {r}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            {subjectType && objectType && availableRelations.length === 0 && (
              <p className="text-sm text-muted-foreground">
                {subjectType} → {objectType} 조합에 허용된 관계가 온톨로지에 없습니다.
              </p>
            )}
            {form.formState.errors.relation && (
              <p id="relation-type-error" className="text-sm text-destructive">
                {form.formState.errors.relation.message}
              </p>
            )}
          </div>

          <div className="flex gap-2">
            <Button type="submit" className="flex-1">
              확인
            </Button>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
              취소
            </Button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}
