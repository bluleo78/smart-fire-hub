import { useState } from "react";

import { Button } from "../../../components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "../../../components/ui/dialog";
import { Label } from "../../../components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "../../../components/ui/select";
import { Textarea } from "../../../components/ui/textarea";
import type {
  SecurityLevel,
  SecurityLevelUsage,
} from "../../../types/security-level";

const MIN_REASON = 10;

interface Props {
  level: SecurityLevel | null;
  levels: SecurityLevel[];
  usage?: SecurityLevelUsage;
  /** 사용량 조회 상태 — ready 가 아니면 사용 중 여부를 모르므로 삭제를 막는다. */
  usageState: "loading" | "error" | "ready";
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onConfirm: (reassignToLevelId: number | null, reason: string | null) => void;
  pending: boolean;
}

/**
 * 등급 삭제(목업 s1 ReassignDeleteDialog) — 사용 중이면 옮길 등급 필수, 하향이면 사유(10자 이상) 필수. 입력 완료 전 삭제 비활성.
 * 영향은 개수만(이름 비노출).
 */
export function ReassignDeleteDialog({
  level,
  levels,
  usage,
  usageState,
  open,
  onOpenChange,
  onConfirm,
  pending,
}: Props) {
  const [target, setTarget] = useState<string>("");
  const [reason, setReason] = useState("");
  if (!level) return null;
  const usageReady = usageState === "ready";
  const inUse = (usage?.datasetCount ?? 0) + (usage?.roleCount ?? 0) > 0;
  const targetLevel = levels.find((l) => String(l.id) === target);
  const downward = !!targetLevel && targetLevel.rank < level.rank;
  const valid =
    (!inUse || !!targetLevel) &&
    (!downward || !inUse || reason.trim().length >= MIN_REASON);
  // 닫히는 모든 경로(X·ESC·취소·삭제 성공)에서 입력을 비운다. 호출처의 key 와 이중 안전망.
  const handleOpenChange = (o: boolean) => {
    if (!o) {
      setTarget("");
      setReason("");
    }
    onOpenChange(o);
  };

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent aria-describedby="reassign-desc">
        <DialogHeader>
          <DialogTitle>{`'${level.name}' 등급 삭제`}</DialogTitle>
          <DialogDescription id="reassign-desc">
            {usageState === "loading"
              ? "사용 현황을 불러오는 중입니다…"
              : usageState === "error"
                ? "사용 현황을 확인하지 못해 삭제할 수 없습니다. 잠시 후 다시 시도하세요."
                : inUse
                  ? `데이터셋 ${usage?.datasetCount ?? 0}개와 역할 ${usage?.roleCount ?? 0}개를 다른 등급으로 옮겨야 삭제할 수 있습니다.`
                  : "사용 중이 아닌 등급입니다. 삭제할까요?"}
          </DialogDescription>
        </DialogHeader>
        {usageReady && inUse && (
          <div className="space-y-4">
            <div className="space-y-1.5">
              <Label htmlFor="reassign-target">옮길 등급 *</Label>
              <Select value={target} onValueChange={setTarget}>
                <SelectTrigger id="reassign-target" aria-label="옮길 등급">
                  <SelectValue placeholder="선택…" />
                </SelectTrigger>
                <SelectContent>
                  {levels
                    .filter((l) => l.id !== level.id)
                    .map((l) => (
                      <SelectItem key={l.id} value={String(l.id)}>
                        {l.rank < level.rank ? `${l.name} (하향)` : l.name}
                      </SelectItem>
                    ))}
                </SelectContent>
              </Select>
            </div>
            {downward && (
              <div className="space-y-1.5">
                <Label htmlFor="reassign-reason">하향 사유 *</Label>
                <Textarea
                  id="reassign-reason"
                  value={reason}
                  onChange={(e) => setReason(e.target.value)}
                  placeholder="10자 이상"
                />
              </div>
            )}
          </div>
        )}
        <DialogFooter className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          <Button variant="outline" onClick={() => handleOpenChange(false)}>
            취소
          </Button>
          <Button
            variant="destructive"
            disabled={!usageReady || !valid || pending}
            onClick={() =>
              onConfirm(
                targetLevel ? targetLevel.id : null,
                downward ? reason.trim() : null,
              )
            }
          >
            삭제
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
