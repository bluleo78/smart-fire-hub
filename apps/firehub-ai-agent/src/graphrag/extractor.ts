// 청크 텍스트를 LLM에 넘겨 온톨로지 준수 엔티티/관계를 추출한다.
// LLM 호출은 CompleteFn으로 주입받는다(기본 구현은 llm-cli.ts의 인증된 claude CLI 헤드리스 호출).
// axios로 x-api-key를 직접 호출하던 방식은 prod에 유효한 API 키가 없어 제거했다.
import {
  ExtractionResult, EntityType, RelationType, Ontology,
  isEntityType, isRelationType, isAllowedTriple, buildExtractionPrompt,
} from './ontology.js';
import type { CompleteFn } from './llm-cli.js';

export interface ExtractOptions { complete: CompleteFn; ontology: Ontology; }

// 응답 텍스트에서 첫 JSON 코드블록을 추출해 파싱한다. 실패 시 null.
function parseJsonBlock(text: string): unknown | null {
  const m = text.match(/```json\s*([\s\S]*?)```/);
  const raw = m ? m[1] : text;
  try { return JSON.parse(raw.trim()); } catch { return null; }
}

export async function extractGraph(text: string, opts: ExtractOptions): Promise<ExtractionResult> {
  // 전달된 ontology 로 시스템 프롬프트를 조립한다(정적 상수 → 동적, 소스 플립 대응).
  const systemPrompt = buildExtractionPrompt(opts.ontology);
  let content = '';
  try {
    content = await opts.complete(systemPrompt, text);
  } catch (err) {
    // API 키 오설정/타임아웃 등을 진단할 수 있도록 경고 로그를 남기고, 배치는 계속 진행한다.
    console.warn('[graphrag] extractGraph LLM 호출 실패, 빈 결과로 계속:', err);
    return { entities: [], relations: [] }; // LLM 호출 실패 → 빈 결과(호출부에서 배치 계속)
  }

  const parsed = parseJsonBlock(content) as { entities?: unknown[]; relations?: unknown[] } | null;
  if (!parsed) return { entities: [], relations: [] };

  // 엔티티: 온톨로지 타입에 없는 것은 폐기. 이름→타입 맵을 만들어 관계 검증에 사용.
  // parsed.entities가 배열이 아닌 경우(형식 오류)에도 배치가 죽지 않도록 방어.
  const entities = (Array.isArray(parsed.entities) ? parsed.entities : [])
    .filter((e: unknown): e is { type: EntityType; name: string } => {
      const rec = e as Record<string, unknown> | null;
      return !!rec && typeof rec.name === 'string' && typeof rec.type === 'string' && isEntityType(rec.type);
    })
    .map((e) => ({ type: e.type, name: e.name }));
  const typeByName = new Map<string, EntityType>(entities.map((e) => [e.name, e.type]));

  // 관계: 관계타입 유효 + 주어·목적어가 추출된 엔티티 + 허용 트리플이어야 함.
  const relations = (Array.isArray(parsed.relations) ? parsed.relations : [])
    .filter((r: unknown): r is { subject: string; type: RelationType; object: string } => {
      const rec = r as Record<string, unknown> | null;
      if (!rec || typeof rec.subject !== 'string' || typeof rec.object !== 'string'
        || typeof rec.type !== 'string' || !isRelationType(rec.type)) return false;
      const subjectType = typeByName.get(rec.subject);
      const objectType = typeByName.get(rec.object);
      return !!subjectType && !!objectType && isAllowedTriple(opts.ontology, subjectType, rec.type, objectType);
    })
    .map((r) => ({ subject: r.subject, type: r.type, object: r.object }));

  return { entities, relations };
}
