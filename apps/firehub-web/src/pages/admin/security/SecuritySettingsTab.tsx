import { useMutation, useQueryClient } from "@tanstack/react-query";
import axios from "axios";
import { Plus } from "lucide-react";
import { useMemo, useState } from "react";
import { toast } from "sonner";

import { securityLevelsApi } from "../../../api/security-levels";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "../../../components/ui/alert-dialog";
import { Button } from "../../../components/ui/button";
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
} from "../../../components/ui/card";
import { Checkbox } from "../../../components/ui/checkbox";
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "../../../components/ui/dialog";
import { InlineBanner } from "../../../components/ui/inline-banner";
import { Input } from "../../../components/ui/input";
import { Label } from "../../../components/ui/label";
import { Skeleton } from "../../../components/ui/skeleton";
import {
  useSecurityLevels,
  useSecurityLevelUsage,
} from "../../../hooks/queries/useSecurityLevels";
import { handleApiError } from "../../../lib/api-error";
import type {
  ReorderPreview,
  SecurityLevel,
  SecurityLevelRequest,
} from "../../../types/security-level";
import { ReassignDeleteDialog } from "./ReassignDeleteDialog";
import { SecurityLevelRow } from "./SecurityLevelRow";

/** 이름 중복 — 백엔드 409 + code. 토스트 대신 이름 입력란 아래에 인라인으로 보여 준다. */
const NAME_DUPLICATE_CODE = "SECURITY_LEVEL_NAME_DUPLICATE";
const NAME_DUPLICATE_MESSAGE = "같은 이름의 보안 등급이 이미 있습니다.";
const isNameDuplicate = (e: unknown) =>
  axios.isAxiosError(e) &&
  e.response?.status === 409 &&
  e.response.data?.code === NAME_DUPLICATE_CODE;

/** 델타 표기 — 음수는 유니코드 마이너스(목업 "−4"). */
const formatDelta = (n: number) => (n > 0 ? `+${n}` : `−${Math.abs(n)}`);

/**
 * 설정 › 데이터 보안 탭(화면 1). 등급 목록(rank 오름차순 = 화면 위→아래), 순서 변경은 로컬에서 바꾼 뒤 "적용" 확인 단계(영향 수치)를 거친다.
 */
export default function SecuritySettingsTab() {
  const qc = useQueryClient();
  const { data: levels, isLoading } = useSecurityLevels();
  const { data: usage } = useSecurityLevelUsage(true);
  // 로컬에서 바꾼 순서. null 이면 서버 순서 그대로(이펙트로 동기화하지 않고 파생한다 — 적용·되돌리기 후엔 null 로).
  const [localOrder, setLocalOrder] = useState<number[] | null>(null);
  const [reorderPreview, setReorderPreview] = useState<ReorderPreview | null>(
    null,
  );
  const [pendingAllowlist, setPendingAllowlist] = useState<{
    level: SecurityLevel;
    req: SecurityLevelRequest;
    impact: number;
  } | null>(null);
  const [seedViewers, setSeedViewers] = useState(true);
  const [deleting, setDeleting] = useState<SecurityLevel | null>(null);
  const [addOpen, setAddOpen] = useState(false);
  const [newName, setNewName] = useState("");
  // 이름 중복 인라인 오류 — 행 저장은 등급 id, 추가 다이얼로그는 'new' 로 구분한다.
  const [nameErrors, setNameErrors] = useState<Record<string, string>>({});
  const setNameError = (key: string, msg?: string) =>
    setNameErrors((m) => {
      if (!msg && !(key in m)) return m;
      const next = { ...m };
      if (msg) next[key] = msg;
      else delete next[key];
      return next;
    });

  const serverOrder = useMemo(() => (levels ?? []).map((l) => l.id), [levels]);
  // 편집 중 서버 목록이 바뀌어도(등급 추가·삭제) 사라진 id 는 빼고 새 id 는 끝에 붙여 일관성을 유지한다.
  const order = useMemo(() => {
    if (!localOrder) return serverOrder;
    const known = new Set(serverOrder);
    const kept = localOrder.filter((id) => known.has(id));
    const keptSet = new Set(kept);
    return [...kept, ...serverOrder.filter((id) => !keptSet.has(id))];
  }, [localOrder, serverOrder]);
  const byId = useMemo(
    () => new Map((levels ?? []).map((l) => [l.id, l])),
    [levels],
  );
  const usageById = useMemo(
    () => new Map((usage ?? []).map((u) => [u.levelId, u])),
    [usage],
  );
  const dirtyOrder = order.join(",") !== serverOrder.join(",");
  const invalidate = () =>
    qc.invalidateQueries({ queryKey: ["security-levels"] });

  const save = useMutation({
    mutationFn: ({ id, req }: { id: number; req: SecurityLevelRequest }) =>
      securityLevelsApi.update(id, req),
    onSuccess: (_d, { id }) => {
      toast.success("보안 등급을 저장했습니다");
      setNameError(String(id));
      void invalidate();
    },
    onError: (e, { id }) => {
      if (isNameDuplicate(e)) setNameError(String(id), NAME_DUPLICATE_MESSAGE);
      else handleApiError(e, "보안 등급 저장에 실패했습니다.");
    },
  });
  const reorder = useMutation({
    mutationFn: (ids: number[]) => securityLevelsApi.reorder(ids),
    onSuccess: () => {
      toast.success("등급 순서를 적용했습니다");
      setReorderPreview(null);
      setLocalOrder(null);
      void invalidate();
    },
    onError: (e) => handleApiError(e, "순서 적용에 실패했습니다."),
  });
  const remove = useMutation({
    mutationFn: ({
      id,
      to,
      reason,
    }: {
      id: number;
      to: number | null;
      reason: string | null;
    }) => securityLevelsApi.remove(id, { reassignToLevelId: to, reason }),
    onSuccess: () => {
      toast.success("보안 등급을 삭제했습니다");
      setDeleting(null);
      void invalidate();
    },
    onError: (e) => handleApiError(e, "보안 등급 삭제에 실패했습니다."),
  });
  const setDefault = useMutation({
    mutationFn: (id: number) => securityLevelsApi.setDefault(id),
    onSuccess: () => {
      toast.success("기본 등급을 변경했습니다");
      void invalidate();
    },
    onError: (e) => handleApiError(e, "기본 등급 변경에 실패했습니다."),
  });
  const create = useMutation({
    mutationFn: (name: string) =>
      securityLevelsApi.create({
        name,
        allowlistRequired: false,
        adminBypass: false,
        exportPolicy: "ALLOW",
        aiPolicy: "ALL",
        sharePolicy: "ALLOW",
        auditAccess: false,
      }),
    onSuccess: () => {
      toast.success("보안 등급을 추가했습니다");
      setAddOpen(false);
      setNewName("");
      setNameError("new");
      void invalidate();
    },
    onError: (e) => {
      if (isNameDuplicate(e)) setNameError("new", NAME_DUPLICATE_MESSAGE);
      else handleApiError(e, "보안 등급 추가에 실패했습니다.");
    },
  });

  /** 허용 목록을 새로 켜는 저장은 영향 수를 먼저 보여준다(스펙 §4.7). */
  const handleSave = async (
    level: SecurityLevel,
    req: SecurityLevelRequest,
    wasAllowlist: boolean,
  ) => {
    if (req.allowlistRequired && !wasAllowlist) {
      try {
        const { data } = await securityLevelsApi.allowlistImpact(level.id);
        setSeedViewers(true);
        setPendingAllowlist({
          level,
          req,
          impact: data.datasetsWithoutAllowlist,
        });
      } catch (e) {
        handleApiError(e, "영향 범위를 확인하지 못했습니다.");
      }
      return;
    }
    save.mutate({ id: level.id, req });
  };

  const move = (index: number, delta: -1 | 1) =>
    setLocalOrder(() => {
      const next = [...order];
      [next[index], next[index + delta]] = [next[index + delta], next[index]];
      return next;
    });

  const openReorderConfirm = async () => {
    try {
      const { data } = await securityLevelsApi.previewReorder(order);
      setReorderPreview(data);
    } catch (e) {
      handleApiError(e, "순서 변경 영향을 확인하지 못했습니다.");
    }
  };

  if (isLoading || !levels) return <Skeleton className="h-64 w-full" />;

  return (
    <div className="space-y-4">
      <InlineBanner variant="info">
        등급은 아래로 갈수록 높습니다. 사용자는 자기 역할의 최대 열람 등급 이하
        데이터셋만 볼 수 있습니다.
      </InlineBanner>
      {dirtyOrder && (
        <InlineBanner
          variant="info"
          title="저장되지 않은 순서 변경"
          actions={
            <>
              <Button
                variant="outline"
                size="sm"
                onClick={() => setLocalOrder(null)}
              >
                되돌리기
              </Button>
              <Button size="sm" onClick={() => void openReorderConfirm()}>
                적용
              </Button>
            </>
          }
        />
      )}
      <Card className="card-hover">
        <CardHeader className="flex flex-row items-center justify-between">
          <CardTitle>보안 등급</CardTitle>
          <Button size="sm" variant="outline" onClick={() => setAddOpen(true)}>
            <Plus className="h-4 w-4" />
            등급 추가
          </Button>
        </CardHeader>
        <CardContent className="space-y-2">
          {order.map((id, i) => {
            const level = byId.get(id);
            if (!level) return null;
            return (
              <SecurityLevelRow
                key={level.id}
                level={level}
                usage={usageById.get(level.id)}
                isFirst={i === 0}
                isLast={i === order.length - 1}
                onMoveUp={() => move(i, -1)}
                onMoveDown={() => move(i, 1)}
                onSave={(req, was) => void handleSave(level, req, was)}
                onSetDefault={() => setDefault.mutate(level.id)}
                onDelete={() => setDeleting(level)}
                saving={save.isPending}
                nameError={nameErrors[String(level.id)]}
                onNameEdit={() => setNameError(String(level.id))}
              />
            );
          })}
        </CardContent>
      </Card>

      {/* 허용 목록 켜기 확인 */}
      <AlertDialog
        open={!!pendingAllowlist}
        onOpenChange={(o) => !o && setPendingAllowlist(null)}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>허용 목록을 켤까요?</AlertDialogTitle>
            <AlertDialogDescription>
              {`이 등급 데이터셋 ${pendingAllowlist?.impact ?? 0}개의 허용 목록이 비어 있어 저장 즉시 아무도 볼 수 없게 됩니다.`}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <Label className="flex items-center gap-2 font-normal">
            <Checkbox
              checked={seedViewers}
              onCheckedChange={(v) => setSeedViewers(v === true)}
              aria-label="현재 열람 가능한 역할로 허용 목록 채우기"
            />
            현재 열람 가능한 역할로 허용 목록 채우기
          </Label>
          <AlertDialogFooter>
            <AlertDialogCancel>취소</AlertDialogCancel>
            <AlertDialogAction
              onClick={() => {
                if (pendingAllowlist)
                  save.mutate({
                    id: pendingAllowlist.level.id,
                    req: {
                      ...pendingAllowlist.req,
                      seedAllowlistFromViewers: seedViewers,
                    },
                  });
                setPendingAllowlist(null);
              }}
            >
              저장
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      {/* 순서 적용 확인 — 영향은 개수만 */}
      <AlertDialog
        open={!!reorderPreview}
        onOpenChange={(o) => !o && setReorderPreview(null)}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>순서를 적용할까요?</AlertDialogTitle>
            <AlertDialogDescription>
              {reorderPreview && reorderPreview.roles.length > 0
                ? `역할 ${reorderPreview.roles.length}개의 열람 범위가 달라집니다.`
                : "열람 범위가 달라지는 역할이 없습니다."}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <ul className="space-y-1 text-sm">
            {reorderPreview?.roles.map((r) => (
              <li
                key={r.roleId}
              >{`· ${r.roleName}: 데이터셋 ${formatDelta(r.datasetDelta)}`}</li>
            ))}
          </ul>
          {/* 델타는 역할 단위 데이터셋 수다 — 허용 목록 등급은 역할에 직접 허용된 경우만 세며, 사용자 개별 허용·사용자 수는 포함하지 않는다. */}
          {reorderPreview && reorderPreview.roles.length > 0 && (
            <p className="text-xs text-muted-foreground">
              역할 기준 데이터셋 수 변화입니다. 허용 목록 등급은 역할에 직접
              허용된 데이터셋만 반영하며, 사용자 개별 허용은 포함하지 않습니다.
            </p>
          )}
          <AlertDialogFooter>
            <AlertDialogCancel>취소</AlertDialogCancel>
            <AlertDialogAction onClick={() => reorder.mutate(order)}>
              적용
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      <ReassignDeleteDialog
        level={deleting}
        levels={levels}
        usage={deleting ? usageById.get(deleting.id) : undefined}
        open={!!deleting}
        onOpenChange={(o) => !o && setDeleting(null)}
        onConfirm={(to, reason) =>
          deleting && remove.mutate({ id: deleting.id, to, reason })
        }
        pending={remove.isPending}
      />

      <Dialog
        open={addOpen}
        onOpenChange={(o) => {
          setAddOpen(o);
          if (!o) setNameError("new");
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>등급 추가</DialogTitle>
          </DialogHeader>
          <div className="space-y-1.5">
            <Label htmlFor="new-level-name">이름</Label>
            <Input
              id="new-level-name"
              value={newName}
              maxLength={50}
              aria-invalid={nameErrors.new ? true : undefined}
              aria-describedby={
                nameErrors.new ? "new-level-name-error" : undefined
              }
              onChange={(e) => {
                setNewName(e.target.value);
                setNameError("new");
              }}
            />
            {nameErrors.new && (
              <p
                id="new-level-name-error"
                role="alert"
                className="text-xs text-destructive"
              >
                {nameErrors.new}
              </p>
            )}
            <p className="text-xs text-muted-foreground">
              새 등급은 가장 높은 등급으로 추가됩니다. 순서는 ↑↓ 로 조정하세요.
            </p>
          </div>
          <DialogFooter className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
            <Button variant="outline" onClick={() => setAddOpen(false)}>
              취소
            </Button>
            <Button
              disabled={!newName.trim() || create.isPending}
              onClick={() => create.mutate(newName.trim())}
            >
              추가
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
