import { z } from 'zod';

// 엔티티 매핑 폼 — 값의 "유효성"(온톨로지에 존재하는 타입인지 등)은 드롭다운이 이미 제약하므로,
// 여기서는 비어 있지 않은지만 본다. 최종 권위는 백엔드 컨포먼스 검증이다.
export const entityMappingSchema = z.object({
  entityType: z.string().min(1, '엔티티 타입을 선택하세요'),
  nameColumn: z.string().min(1, '이름 컬럼을 선택하세요'),
  properties: z.array(
    z.object({
      column: z.string().min(1, '컬럼을 선택하세요'),
      propertyName: z.string().min(1, '속성을 선택하세요'),
    }),
  ),
});

export type EntityMappingFormData = z.infer<typeof entityMappingSchema>;

// 관계 매핑 폼 — 끝점은 편집 모델의 안정 ID로 참조한다(인덱스가 아니다).
// 허용 트리플 제약은 드롭다운이 걸고, 최종 검증은 백엔드가 한다.
export const relationMappingSchema = z.object({
  subjectId: z.string().min(1, '주어 엔티티를 선택하세요'),
  relation: z.string().min(1, '관계를 선택하세요'),
  objectId: z.string().min(1, '목적어 엔티티를 선택하세요'),
});

export type RelationMappingFormData = z.infer<typeof relationMappingSchema>;
