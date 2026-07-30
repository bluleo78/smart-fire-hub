import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect } from 'react';
import { useFieldArray, useForm } from 'react-hook-form';

import { Button } from '../../../components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '../../../components/ui/dialog';
import { Label } from '../../../components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '../../../components/ui/select';
import type { DraftEntity } from '../../../lib/mapping-spec';
import { propertyTypeMismatch } from '../../../lib/mapping-spec';
import type { EntityMappingFormData } from '../../../lib/validations/mapping';
import { entityMappingSchema } from '../../../lib/validations/mapping';
import type { DatasetColumnResponse } from '../../../types/dataset';
import type { OntologySchema } from '../../../types/ontology';

interface EntityMappingDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  ontology: OntologySchema;
  columns: DatasetColumnResponse[];
  /** null이면 추가, 값이 있으면 수정. */
  initial: DraftEntity | null;
  onSubmit: (data: EntityMappingFormData) => void;
}

const EMPTY_VALUES: EntityMappingFormData = { entityType: '', nameColumn: '', properties: [] };

/**
 * 엔티티 매핑 추가/수정 폼.
 * 엔티티 타입·컬럼·속성명을 모두 드롭다운으로 제약해 백엔드 컨포먼스 위반(400)을 예방한다.
 * 특히 속성명은 **선택된 엔티티 타입에 정의된 것**만 노출한다.
 */
export function EntityMappingDialog({
  open,
  onOpenChange,
  ontology,
  columns,
  initial,
  onSubmit,
}: EntityMappingDialogProps) {
  const form = useForm<EntityMappingFormData>({
    resolver: zodResolver(entityMappingSchema),
    defaultValues: EMPTY_VALUES,
  });
  const { fields, append, remove, replace } = useFieldArray({ control: form.control, name: 'properties' });

  // 열릴 때마다 대상(추가/수정)에 맞춰 폼을 초기화한다.
  useEffect(() => {
    if (!open) return;
    form.reset(
      initial
        ? {
            entityType: initial.entityType,
            nameColumn: initial.nameColumn,
            properties: initial.properties.map((p) => ({ column: p.column, propertyName: p.propertyName })),
          }
        : EMPTY_VALUES,
    );
  }, [open, initial, form]);

  const entityType = form.watch('entityType');
  const availableProperties = ontology.entities.find((e) => e.type === entityType)?.properties ?? [];

  // 엔티티 타입이 바뀌면 이전 타입의 속성은 그 타입에 존재하지 않으므로 비운다(무효 조합 방지).
  // properties는 useFieldArray로 관리되는 배열이므로, 직접 setValue하는 대신
  // 필드 배열 전용 API인 replace를 사용해 배열 초기화 의도를 명확히 한다.
  const handleEntityTypeChange = (value: string) => {
    form.setValue('entityType', value, { shouldValidate: true });
    replace([]);
  };

  // 속성 행별 컬럼↔속성 타입 호환 검사(#324).
  // 서버 conformance가 활성화 시점에 400으로 막지만, 그때 알면 이미 편집을 다 끝낸 뒤다 —
  // 선택 즉시 행 아래에 사유를 보여주고 확인도 막는다. 드롭다운 자체를 disabled 하지 않는 이유는
  // 컬럼·속성 중 무엇을 먼저 고르는지에 따라 막히는 쪽이 달라져 오히려 혼란스럽기 때문.
  const watchedProperties = form.watch('properties');
  const typeErrors = (watchedProperties ?? []).map((p) => {
    if (!p?.column || !p?.propertyName) return null; // 둘 다 고르기 전에는 판정하지 않는다
    const columnType = columns.find((c) => c.columnName === p.column)?.dataType;
    const propertyDataType = availableProperties.find((ap) => ap.name === p.propertyName)?.dataType;
    return propertyTypeMismatch(columnType, propertyDataType);
  });
  const hasTypeError = typeErrors.some((e) => e !== null);

  const submit = (data: EntityMappingFormData) => {
    if (hasTypeError) return; // 타입 위반 행이 남아 있으면 저장 대상 초안에 넣지 않는다
    onSubmit(data);
    onOpenChange(false);
  };

  // 제출 실패 시 첫 오류 필드로 포커스를 옮긴다(#330).
  // react-hook-form의 shouldFocusError는 register된 네이티브 입력만 대상이라 Radix Select 트리거에는
  // 적용되지 않는다. 포커스가 가야 이미 연결된 aria-describedby 오류 문구가 읽힌다.
  // 필드 순서는 errors 객체 키 순서(보장되지 않음)가 아니라 화면 순서대로 명시한다.
  const FIELD_ORDER: [keyof EntityMappingFormData, string][] = [
    ['entityType', 'entity-type'],
    ['nameColumn', 'entity-name-column'],
  ];
  const focusFirstError = (errors: Record<string, unknown>) => {
    const first = FIELD_ORDER.find(([name]) => errors[name]);
    // properties 배열만 오류인 경우는 대응 트리거가 없어 포커스를 그대로 둔다(엉뚱한 곳으로 옮기지 않음).
    if (first) document.getElementById(first[1])?.focus();
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[90vh] overflow-y-auto" data-testid="entity-mapping-dialog">
        <DialogHeader>
          <DialogTitle>{initial ? '엔티티 매핑 수정' : '엔티티 매핑 추가'}</DialogTitle>
          <DialogDescription className="sr-only">
            데이터셋 컬럼을 온톨로지 엔티티 타입에 연결합니다.
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={form.handleSubmit(submit, focusFirstError)} className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="entity-type">엔티티 타입 *</Label>
            <Select value={entityType} onValueChange={handleEntityTypeChange}>
              <SelectTrigger
                id="entity-type"
                data-testid="entity-type-select"
                // 오류가 있을 때만 무효 상태와 설명 대상을 연결한다(없을 때 매달면 존재하지 않는 노드를 가리킨다).
                aria-invalid={form.formState.errors.entityType ? true : undefined}
                aria-describedby={form.formState.errors.entityType ? 'entity-type-error' : undefined}
              >
                <SelectValue placeholder="엔티티 타입을 선택하세요" />
              </SelectTrigger>
              <SelectContent>
                {ontology.entities.map((e) => (
                  <SelectItem key={e.type} value={e.type}>
                    {e.type}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            {form.formState.errors.entityType && (
              <p id="entity-type-error" className="text-sm text-destructive">
                {form.formState.errors.entityType.message}
              </p>
            )}
          </div>

          <div className="space-y-2">
            <Label htmlFor="entity-name-column">이름 컬럼 *</Label>
            <Select
              value={form.watch('nameColumn')}
              onValueChange={(v) => form.setValue('nameColumn', v, { shouldValidate: true })}
            >
              <SelectTrigger
                id="entity-name-column"
                data-testid="entity-name-column-select"
                aria-invalid={form.formState.errors.nameColumn ? true : undefined}
                aria-describedby={form.formState.errors.nameColumn ? 'entity-name-column-error' : undefined}
              >
                <SelectValue placeholder="이름으로 쓸 컬럼을 선택하세요" />
              </SelectTrigger>
              <SelectContent>
                {columns.map((c) => (
                  <SelectItem key={c.id} value={c.columnName}>
                    {c.columnName}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            {form.formState.errors.nameColumn && (
              <p id="entity-name-column-error" className="text-sm text-destructive">
                {form.formState.errors.nameColumn.message}
              </p>
            )}
          </div>

          <div className="space-y-2">
            <div className="flex items-center justify-between">
              {/* 섹션 제목은 특정 입력을 가리키지 않으므로 htmlFor 없이 둔다. */}
              <Label>속성 매핑</Label>
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => append({ column: '', propertyName: '' })}
                // 선택된 타입에 정의된 속성이 없으면 추가할 것이 없다.
                disabled={availableProperties.length === 0}
              >
                속성 추가
              </Button>
            </div>
            {availableProperties.length === 0 && entityType !== '' && (
              <p className="text-sm text-muted-foreground">이 엔티티 타입에는 정의된 속성이 없습니다.</p>
            )}
            {fields.map((field, index) => (
              <div key={field.id} className="space-y-1">
                <div className="flex items-end gap-2">
                <div className="flex-1 space-y-1">
                  <Label className="text-xs" htmlFor={`property-column-${index}`}>
                    데이터셋 컬럼
                  </Label>
                  <Select
                    value={form.watch(`properties.${index}.column`)}
                    onValueChange={(v) => form.setValue(`properties.${index}.column`, v, { shouldValidate: true })}
                  >
                    {/* 행 내부 셀렉트는 반폭(flex-1)이라 지시문 전문이 잘린다 — 이 화면 한정으로 명사구 축약. */}
                    <SelectTrigger id={`property-column-${index}`} data-testid={`property-column-select-${index}`}>
                      <SelectValue placeholder="컬럼 선택" />
                    </SelectTrigger>
                    <SelectContent>
                      {columns.map((c) => (
                        <SelectItem key={c.id} value={c.columnName}>
                          {c.columnName}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
                <div className="flex-1 space-y-1">
                  <Label className="text-xs" htmlFor={`property-name-${index}`}>
                    온톨로지 속성
                  </Label>
                  <Select
                    value={form.watch(`properties.${index}.propertyName`)}
                    onValueChange={(v) =>
                      form.setValue(`properties.${index}.propertyName`, v, { shouldValidate: true })
                    }
                  >
                    <SelectTrigger
                      id={`property-name-${index}`}
                      data-testid={`property-name-select-${index}`}
                      aria-invalid={typeErrors[index] ? true : undefined}
                      aria-describedby={typeErrors[index] ? `property-type-error-${index}` : undefined}
                    >
                      <SelectValue placeholder="속성 선택" />
                    </SelectTrigger>
                    <SelectContent>
                      {availableProperties.map((p) => (
                        <SelectItem key={p.name} value={p.name}>
                          {p.name}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
                <Button type="button" variant="ghost" size="sm" onClick={() => remove(index)}>
                  제거
                </Button>
                </div>
                {typeErrors[index] && (
                  <p
                    id={`property-type-error-${index}`}
                    data-testid={`property-type-error-${index}`}
                    className="text-sm text-destructive"
                  >
                    {typeErrors[index]}
                  </p>
                )}
              </div>
            ))}
            {form.formState.errors.properties && (
              // properties 에러는 행별 배열이거나(각 행의 column/propertyName 누락) 배열 자체 에러일 수 있어
              // 상세 위치 대신 공통 안내 메시지 하나로 사용자가 원인을 알 수 있게 한다.
              <p className="text-sm text-destructive">
                모든 속성 행에서 데이터셋 컬럼과 온톨로지 속성을 선택해야 합니다.
              </p>
            )}
          </div>

          <div className="flex gap-2">
            <Button type="submit" className="flex-1" disabled={hasTypeError}>
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
