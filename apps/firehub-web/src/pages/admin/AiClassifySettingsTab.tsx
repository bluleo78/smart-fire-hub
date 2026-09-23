import { Info, Save } from 'lucide-react';
import { useState } from 'react';

import { Button } from '../../components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '../../components/ui/card';
import { InlineBanner } from '../../components/ui/inline-banner';
import { Label } from '../../components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '../../components/ui/select';
import { Separator } from '../../components/ui/separator';
import type { UseAiClassifyFormResult } from '../../hooks/useAiClassifyForm';
import type { AgentType } from '../../lib/ai-credential';
import { AGENT_TYPE_LABELS, CLAUDE_MODEL_OPTIONS, withPreservedValue } from '../../lib/ai-credential-screen';
import { AiCredentialFieldset, OpencodeModelField } from './AiCredentialFieldset';
import { ClearConfirmDialog } from './ClearConfirmDialog';

const CLEAR_LABEL = '분류 전용 설정 해제';
const CLEAR_BODY =
  '분류 전용 인증·키·모델이 삭제되고, AI 분류는 AI 에이전트 설정으로 실행됩니다. 저장된 API 키는 복구할 수 없습니다.';

/**
 * "AI 분류" 탭(#707) — AI_CLASSIFY 스텝 전용 인증·키·모델 묶음. **표현 전용**: 상태는
 * `SettingsPage` 가 `useAiClassifyForm()` 으로 소유한다(탭 전환에도 편집이 살아남는다).
 *
 * - 미설정: AI 에이전트(채팅) 설정을 통째로 쓰고 있다는 배너 + 그 유형·모델 + "분류 전용 설정하기".
 * - 설정/편집: 자격증명 fieldset(채팅 탭과 같은 컴포넌트, id 접두어 `ai-classify`) + 모델(필수).
 *   설정된 상태에서만 하단 좌측에 "분류 전용 설정 해제"(되돌릴 수 없으므로 확인창).
 * - 저장 후 재조회 실패(`cred.staleNotice`): 저장은 됐지만 서버 `configured` 를 아직 못 읽은
 *   상태라 좌측에는 "해제"도 "취소"도 내주지 않는다(아래 주석 참고).
 */
export default function AiClassifySettingsTab({
  state,
  chatAgentType,
  chatConfigured,
  chatModel,
}: {
  state: UseAiClassifyFormResult;
  chatAgentType: AgentType;
  chatConfigured: boolean;
  chatModel: string;
}) {
  const { cred } = state;
  const [clearOpen, setClearOpen] = useState(false);

  // `cred.isLoading` 은 조회가 시작되기 전에도 true 다 — 그래서 `activated` 를 먼저 본다(탭이
  // 열리기 전에는 조회 자체를 하지 않는다).
  if (!state.activated || cred.isLoading) {
    return <div className="py-8 text-center text-muted-foreground text-sm">불러오는 중...</div>;
  }

  // 하단 좌측 버튼 — 설정됨이면 "해제", 미설정 편집이면 "취소".
  const renderFooterLeft = () => {
    if (cred.configured) {
      return (
        <Button
          type="button"
          variant="ghost"
          onClick={() => setClearOpen(true)}
          disabled={state.isClearing || state.isSaving}
        >
          {CLEAR_LABEL}
        </Button>
      );
    }
    // 저장은 됐는데 재조회가 실패한 상태(위 경고 배너). "취소"는 폼을 접어 실제로 쓰이는 분류 전용
    // 설정을 "미설정"으로 보이게 하고, "해제"는 서버 상태를 몰라 내주지 않는다. 좌측은 자리만
    // 채운다(justify-between 에 자식이 하나면 "저장"이 왼쪽으로 붙는다).
    if (cred.staleNotice) return <span aria-hidden />;
    return (
      <Button type="button" variant="ghost" onClick={state.cancelEditing} disabled={state.isSaving}>
        취소
      </Button>
    );
  };

  return (
    <Card className="card-hover">
      <CardHeader>
        <CardTitle>분류 모델 설정</CardTitle>
      </CardHeader>
      <CardContent className="space-y-6">
        {cred.staleNotice && <InlineBanner variant="warning">{cred.staleNotice}</InlineBanner>}

        {/* 조회 실패·권한 없음은 fieldset 이 스스로 그린다 — 여기서는 미설정 요약을 그리지 않는다
            (모르는 상태를 "채팅 설정 사용 중"이라고 단정하지 않기 위해). */}
        {!state.editing && !cred.loadFailed && !cred.isLocked ? (
          <div className="space-y-4">
            <InlineBanner variant="info" icon={<Info />}>
              AI 에이전트 설정을 사용 중입니다.
            </InlineBanner>
            <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
              <dt className="text-muted-foreground">에이전트 유형</dt>
              <dd>{chatConfigured ? AGENT_TYPE_LABELS[chatAgentType] : '설정되지 않음'}</dd>
              <dt className="text-muted-foreground">모델</dt>
              <dd>{chatModel}</dd>
            </dl>
            <Button type="button" variant="outline" onClick={state.startEditing}>
              분류 전용 설정하기
            </Button>
          </div>
        ) : (
          <>
            <AiCredentialFieldset
              cred={cred}
              idPrefix="ai-classify"
              agentTypeDescription="AI 분류에 사용할 에이전트 유형"
              unconfiguredNotice={null}
            />
            {!cred.loadFailed && !cred.isLocked && (
              <>
                <Separator />
                <div className="space-y-2">
                  <Label htmlFor="ai-classify-model">분류 모델</Label>
                  {cred.agentType === 'opencode' ? (
                    <OpencodeModelField
                      id="ai-classify-model"
                      cred={cred}
                      modelValue={state.model}
                      onModelChange={state.setModel}
                    />
                  ) : (
                    <Select value={state.model === '' ? undefined : state.model} onValueChange={state.setModel}>
                      <SelectTrigger id="ai-classify-model" className="w-full max-w-md">
                        <SelectValue placeholder="모델을 선택하세요" />
                      </SelectTrigger>
                      <SelectContent>
                        {/* 저장된 모델이 후보 목록에 없으면 맨 앞에 끼워 보존한다(설계서 §201) —
                            목록 밖 값이 저장돼 있을 때 칸이 빈 것처럼 보이지 않게 한다. */}
                        {withPreservedValue(CLAUDE_MODEL_OPTIONS, state.model).map((opt) => (
                          <SelectItem key={opt.value} value={opt.value}>
                            {opt.label}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  )}
                  {state.modelError && <p className="text-sm text-destructive">{state.modelError}</p>}
                  <p className="text-sm text-muted-foreground">AI 분류 스텝이 사용할 모델 (필수)</p>
                </div>

                <div className="flex items-center justify-between gap-3">
                  {renderFooterLeft()}
                  <Button
                    type="button"
                    onClick={() => void state.save()}
                    disabled={state.isSaving || state.isClearing || !state.hasUnsavedInput}
                  >
                    <Save className="h-4 w-4" />
                    {state.isSaving ? '저장 중...' : '저장'}
                  </Button>
                </div>
              </>
            )}
          </>
        )}
      </CardContent>

      {/* 해제 확인 — 저장된 키가 복구 불가로 지워지므로 파괴적 확인 버튼을 쓴다. */}
      <ClearConfirmDialog
        open={clearOpen}
        onOpenChange={setClearOpen}
        title={CLEAR_LABEL}
        description={CLEAR_BODY}
        onConfirm={() => void state.clear()}
      />
    </Card>
  );
}
