import { useRef, useState } from 'react';

import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { FormField } from '@/components/ui/form-field';
import { Input } from '@/components/ui/input';
import { useCreateOntology } from '@/hooks/queries/useOntology';
import { isImeComposing } from '@/hooks/useOverlayEscape';
import { useSubmitGuard } from '@/hooks/useSubmitGuard';
import { extractApiError } from '@/lib/api-error';
import { validateDomain } from '@/lib/ontology-validation';

interface OntologyCreateDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** 생성 성공 시 새 온톨로지 id를 넘긴다 — 호출부가 그것을 선택 상태로 만든다. */
  onCreated: (id: number) => void;
}

/**
 * 신규 온톨로지 생성 — 도메인명 하나만 받는다.
 * 엔티티·관계는 여기서 받지 않는다. 요소 단위 편집기(수정 모드)에 이미 있는 생성 폼(ModelOutline의
 * "타입 추가"/"관계 추가")을 중복 구현하지 않고, 빈 draft를 만든 뒤 빈 상태 CTA로 편집기에 넘긴다.
 */
export default function OntologyCreateDialog({ open, onOpenChange, onCreated }: OntologyCreateDialogProps) {
  const [domain, setDomain] = useState('');
  const [error, setError] = useState<string | null>(null);
  const createOntology = useCreateOntology();
  /** 오류가 나면 도메인 칸으로 포커스를 옮기기 위한 참조(WD-818) — 로그인·프로필 폼처럼 오류 칸으로 바로 돌아가게 한다. */
  const domainRef = useRef<HTMLInputElement>(null);

  /**
   * 요청 중복 방지용 동기 잠금(WD-819) — 공용 useSubmitGuard 를 쓴다. TanStack useMutation 의 isPending 은
   * 다시 렌더링된 뒤에야 true 가 되므로, Enter 를 빠르게 거듭 누르면 두 keydown 이 모두 isPending === false 를 보고
   * POST 가 두 번 나간다. 가드는 ref 로 즉시 잠가 「만들기」 버튼·Enter 어느 경로든 한 번만 통과시키고,
   * 요청이 끝나면(성공·실패 모두) 스스로 풀려 오류를 고친 뒤 다시 제출할 수 있다.
   */
  const guard = useSubmitGuard();

  /**
   * 닫힘 세대 카운터 — 요청 중에 닫으면(ESC·X·바깥 클릭) 올라간다. 컴포넌트는 계속 마운트되어 있어서,
   * 늦게 도착한 오류가 닫힌 대화상자에 오류를 심으면 다시 열었을 때 묵은 오류가 보이기 때문에
   * submit 시점의 세대와 달라졌으면 그 오류를 버린다.
   */
  const closeGenRef = useRef(0);

  const runSubmit = async () => {
    const trimmed = domain.trim();
    // validateDomain(ontology-validation.ts)을 재사용한다(NEW-4, Task 6 리뷰 라운드2) — 여기서
    // 문구를 따로 인라인해 두면 ModelOutline의 도메인 편집 화면과 문구가 갈라질 수 있다.
    const domainError = validateDomain(trimmed);
    if (domainError) {
      setError(domainError);
      // WD-818: 포커스가 「만들기」 버튼에 남아 있으면 키보드·스크린리더 사용자가 고칠 칸을 다시 찾아야 한다
      domainRef.current?.focus();
      return;
    }
    // 같은 값으로 다시 제출해도(onChange 가 돌지 않음) 요청 중에는 이전 오류가 남지 않게 지운다
    setError(null);
    const gen = closeGenRef.current;
    try {
      // 항상 draft로 만든다 — 사람이 내용을 채우고 활성화하기 전까지 바인딩·적재에서 격리된다.
      const id = await createOntology.mutateAsync({ domain: trimmed, entities: [], relations: [], status: 'draft' });
      setDomain('');
      onOpenChange(false);
      onCreated(id);
    } catch (err) {
      // 도메인 중복(409)은 다이얼로그를 닫지 않고 필드 옆에 보여준다 — 이름을 고쳐 재시도해야 하기 때문.
      // 요청 중에 닫혔다면 늦게 온 오류를 표시하지 않는다
      if (closeGenRef.current !== gen) return;
      setError(extractApiError(err, '온톨로지 생성에 실패했습니다.'));
      // WD-818: 서버 오류(중복 등)도 칸에 이어지므로 같은 칸으로 포커스를 돌려 바로 고칠 수 있게 한다
      domainRef.current?.focus();
    }
  };

  // 가드는 렌더 중이 아니라 제출 때 씌운다 — runSubmit 이 ref(domainRef·closeGenRef)를 읽어서, 렌더 중에 함수로 넘기면
  // react-hooks/refs 규칙에 걸린다. 잠금 자체는 useSubmitGuard 안의 ref 라 매 호출이 같은 잠금을 본다.
  const submit = () => guard(runSubmit)();

  /**
   * 닫힐 때 오류를 지운다(WD-819) — 도메인 값은 유지하되 예전 오류(예: 409 중복)가 다시 열었을 때
   * 남아 있으면 아직 아무것도 하지 않았는데 오류가 보이는 혼란이 생긴다. 「취소」·X·ESC 모두 이 함수를 쓴다.
   */
  const handleOpenChange = (next: boolean) => {
    if (!next) {
      closeGenRef.current += 1;
      setError(null);
    }
    onOpenChange(next);
  };

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="sm:max-w-md" data-testid="ontology-create-dialog">
        <DialogHeader>
          <DialogTitle>새 온톨로지</DialogTitle>
          <DialogDescription>
            도메인명을 정하면 빈 초안이 만들어집니다. 엔티티와 관계는 다음 단계에서 채웁니다.
          </DialogDescription>
        </DialogHeader>
        {/* WD-817: 「만들기」가 늘 켜져 있어 비우고 누르기 전에는 필수인지 알 수 없었다 — FormField 로 * 를 달고,
            오류 문구도 칸에 잇는다(aria-describedby·role=alert — 예전에는 aria-invalid 만 있어 무슨 오류인지 읽히지 않았다) */}
        <FormField label="도메인" htmlFor="ontology-domain" required error={error ?? undefined}>
          <Input
            id="ontology-domain"
            ref={domainRef}
            aria-required
            value={domain}
            // 입력을 고치면 이전 오류를 바로 지운다(WD-819) — 고친 뒤에도 빨간 오류가 남지 않게
            onChange={(e) => {
              setDomain(e.target.value);
              setError(null);
            }}
            onKeyDown={(e) => {
              // 한글 IME 조합 중 Enter 는 글자 확정 키라 제출하면 안 된다(WD-819). 요청 중 중복 Enter 는 useSubmitGuard 가 막는다.
              // Safari 는 조합 확정 Enter 를 isComposing=false·keyCode 229 로 보내므로 공용 isImeComposing 으로 함께 거른다.
              if (e.key !== 'Enter' || isImeComposing(e.nativeEvent)) return;
              void submit();
            }}
            placeholder="예: 건축물 안전점검"
          />
        </FormField>
        <DialogFooter className="gap-2 sm:justify-end">
          <Button variant="outline" onClick={() => handleOpenChange(false)} disabled={createOntology.isPending}>
            취소
          </Button>
          <Button onClick={() => void submit()} disabled={createOntology.isPending}>
            {createOntology.isPending ? '만드는 중...' : '만들기'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
