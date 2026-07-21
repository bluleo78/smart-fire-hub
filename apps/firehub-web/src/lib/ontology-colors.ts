// 엔티티 타입 색상 — 스키마·인스턴스·범례가 공유하는 단일 소스.
export const ENTITY_TYPE_COLORS: Record<string, string> = {
  Incident: '#ef4444', Building: '#3b82f6', Cause: '#f59e0b',
  Damage: '#ec4899', Equipment: '#10b981', Regulation: '#8b5cf6',
};
// 온톨로지에 없는 타입 방어용 기본색(회색).
export const DEFAULT_TYPE_COLOR = '#64748b';
export const colorForType = (type: string): string => ENTITY_TYPE_COLORS[type] ?? DEFAULT_TYPE_COLOR;
