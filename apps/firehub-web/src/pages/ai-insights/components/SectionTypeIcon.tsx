import type { SectionTypeDefinition } from '@/lib/template-section-types';

/**
 * 섹션 타입 아이콘 (WD-893) — 트리 줄·미리보기 헤더가 같은 크기·색·접근성 규칙으로 그리도록 한 곳에 둔다.
 * - 장식 아이콘이라 aria-hidden (라벨이 접근 이름을 맡는다)
 * - 알 수 없는 타입(def 없음)이면 아무것도 그리지 않는다 — 라벨만 남아 화면이 깨지지 않게
 */
export function SectionTypeIcon({ def }: { def: SectionTypeDefinition | undefined }) {
  if (!def) return null;
  const Icon = def.icon;
  return <Icon aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />;
}
