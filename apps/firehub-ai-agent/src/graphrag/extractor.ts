// 청크 텍스트를 LLM에 넘겨 온톨로지 준수 엔티티/관계를 추출한다.
// LLM 호출은 CompleteFn으로 주입받는다(기본 구현은 llm-cli.ts의 인증된 claude CLI 헤드리스 호출).
// axios로 x-api-key를 직접 호출하던 방식은 prod에 유효한 API 키가 없어 제거했다.
import {
  ExtractionResult, EntityType, RelationType, Ontology, PropertyReviewCandidate,
  isEntityType, isRelationType, isAllowedTriple, buildExtractionPrompt,
} from './ontology.js';
import type { CompleteFn } from './llm-cli.js';
import { normalizePropertyChecked } from './property-normalizer.js';

export interface ExtractOptions { complete: CompleteFn; ontology: Ontology; }

// ```json 코드블록(없으면 전체)에서 JSON을 파싱한다. 실패 시 null. (mapping-inference.ts도 재사용)
export function parseJsonBlock(text: string): unknown | null {
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
  // 온톨로지 타입별 속성 정의 맵(정규화·화이트리스트에 사용).
  const propDefsByType = new Map(opts.ontology.entities.map((e) => [e.type, e.properties ?? []]));

  // 정규화에 실패한(원문은 있으나 파싱 불가) 속성을 사람 검수 큐로 넘기기 위해 수집한다.
  const propertyReviewCandidates: PropertyReviewCandidate[] = [];
  const entities = (Array.isArray(parsed.entities) ? parsed.entities : [])
    .filter((e: unknown): e is { type: EntityType; name: string; properties?: Record<string, unknown>; confidence?: unknown; reason?: unknown } => {
      const rec = e as Record<string, unknown> | null;
      return !!rec && typeof rec.name === 'string' && typeof rec.type === 'string'
        && isEntityType(opts.ontology, rec.type);
    })
    .map((e) => {
      // 온톨로지에 정의된 속성만 정규화해 채운다. 미정의 키는 폐기, 정규화 실패는 검수 후보로 수집.
      const defs = propDefsByType.get(e.type) ?? [];
      const props: Record<string, number | string> = {};
      for (const def of defs) {
        const raw = e.properties?.[def.name];
        if (typeof raw !== 'string') continue;
        const { value, status } = normalizePropertyChecked(def.dataType, def.unit, raw);
        if (value !== null) {
          props[def.name] = value;
        } else if (status === 'failed') {
          propertyReviewCandidates.push({
            entityType: e.type, entityName: e.name, propertyName: def.name, dataType: def.dataType, rawText: raw,
          });
        }
      }
      const hasProps = Object.keys(props).length > 0;
      // confidence는 0~1 숫자만 채택(그 외는 미신고=undefined). reason은 문자열만.
      const confidence = typeof e.confidence === 'number' && e.confidence >= 0 && e.confidence <= 1 ? e.confidence : undefined;
      const reason = typeof e.reason === 'string' && e.reason.trim() !== '' ? e.reason : undefined;
      return {
        type: e.type, name: e.name,
        ...(hasProps ? { properties: props } : {}),
        ...(confidence !== undefined ? { confidence } : {}),
        ...(reason ? { reason } : {}),
      };
    });
  const typeByName = new Map<string, EntityType>(entities.map((e) => [e.name, e.type]));

  // 관계: 관계타입 유효 + 주어·목적어가 추출된 엔티티 + 허용 트리플이어야 함.
  const relations = (Array.isArray(parsed.relations) ? parsed.relations : [])
    .filter((r: unknown): r is { subject: string; type: RelationType; object: string; confidence?: unknown; reason?: unknown } => {
      const rec = r as Record<string, unknown> | null;
      if (!rec || typeof rec.subject !== 'string' || typeof rec.object !== 'string'
        || typeof rec.type !== 'string' || !isRelationType(opts.ontology, rec.type)) return false;
      const subjectType = typeByName.get(rec.subject);
      const objectType = typeByName.get(rec.object);
      return !!subjectType && !!objectType && isAllowedTriple(opts.ontology, subjectType, rec.type, objectType);
    })
    .map((r) => {
      // confidence는 0~1 숫자만 채택(그 외 미신고=undefined). reason은 비어있지 않은 문자열만.
      const confidence = typeof r.confidence === 'number' && r.confidence >= 0 && r.confidence <= 1 ? r.confidence : undefined;
      const reason = typeof r.reason === 'string' && r.reason.trim() !== '' ? r.reason : undefined;
      return {
        subject: r.subject, type: r.type, object: r.object,
        ...(confidence !== undefined ? { confidence } : {}),
        ...(reason ? { reason } : {}),
      };
    });

  return {
    entities,
    relations,
    ...(propertyReviewCandidates.length > 0 ? { propertyReviewCandidates } : {}),
  };
}
