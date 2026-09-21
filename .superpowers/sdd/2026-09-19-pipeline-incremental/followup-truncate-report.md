# 후속 결함: API_CALL / AI_CLASSIFY 자동 임시 데이터셋 선(先)비우기 제거

브랜치 `worktree-pipeline-incremental`, 베이스 `4ac96f3d`.

## 문제

`PipelineAsyncRunner.java` 의 두 자리(API_CALL ~:744, AI_CLASSIFY ~:869)가 자동 임시
데이터셋(`ptmp_*`) 테이블명을 알아낸 직후 **무조건** `dataTableRowService.truncateTable()` 을
불렀다. 그 호출은

1. **로드 전략을 보지 않았다** — APPEND 스텝의 재사용 임시 데이터셋이 매 실행 통째로 비워졌다.
2. **작업보다 먼저 커밋됐다** — `DataTableRowService` 에는 `@Transactional` 이 없어 truncate 가
   즉시 커밋된다. 그 뒤 API 호출 / AI 분류가 실패하면 출력 데이터셋이 **빈 채로** 남았다. 이 브랜치가
   SQL 경로(pre-statement 원자화)에서 없앤 바로 그 결함이다.
3. **#685 빈 결과 가드를 무력화했다** — 실행기들의 `finishReplace` 는 0행이면 맞바꾸지 않고 원본을
   지키는데, 앞에서 이미 비웠으면 지킬 원본이 없다.

## 네 조합 검증 (`:314` 주석의 "내부에서 로드 전략을 처리한다" 주장)

주석의 주장은 **네 조합 모두 참**이다. 넷 다 `{table}_tmp` 스테이징 → 맞바꿈으로 REPLACE 를
스스로 원자적으로 구현한다.

| # | 조합 | 근거 | 결론 |
|---|---|---|---|
| 1 | API_CALL `executorEnabled=true` | `PipelineAsyncRunner.java:758-790` — `createTempTable` → executor INSERT → `finishReplace(table, rowsLoaded)`; catch 에서 `dropTempTable` | REPLACE 자체 처리 ✔ |
| 2 | API_CALL `executorEnabled=false` | `ApiCallExecutor.java:86-95`(isReplace → `createTempTable`, insertTarget = `_tmp`), `:196-201`(`finishReplace`), `:205-210`(catch → `dropTempTable`) | REPLACE 자체 처리 ✔ |
| 3 | AI_CLASSIFY | `AiClassifyExecutor.java:129-134` — `step.loadStrategy()` 를 **직접 읽어** isReplace 판단 후 `createTempTable`; `:268-269` 은 `finishReplace` 가 아니라 `swapTable` 을 **직접** 부른다(0행이면 `:255` 가 먼저 던지므로 가드가 필요 없다), `:273` catch → `dropTempTable`. 러너가 넘기는 `resolvedStep` 은 `step.loadStrategy()` 를 그대로 전달한다(`PipelineAsyncRunner.java` resolvedStep 생성부) | REPLACE 자체 처리 ✔ — truncate 를 지워도 REPLACE 가 APPEND 로 바뀌지 않는다 |
| 4 | `executorEnabled && "PYTHON"` | `PipelineAsyncRunner.java:665-700` — 동일한 `createTempTable` → `finishReplace` → catch `dropTempTable` | REPLACE 자체 처리 ✔. **같은 구멍 없음** — PYTHON 의 임시 데이터셋 생성 블록(~:616-648)은 테이블명만 얻고 truncate 를 부르지 않는다. 손대지 않았다 |

따라서 두 truncate 는 **중복이자 유해**했고, 원자성 대체물을 새로 만들 필요 없이 삭제가 올바른 수정이다.

## 수정

- `PipelineAsyncRunner.java` 두 자리의 `truncateTable` 호출 삭제. 각 자리에 "다시 넣지 말 것 +
  이유 3가지 + REPLACE 비우기는 어디서 처리되는지"를 한국어 주석으로 고정.
- `:774-775` 의 "여기에는 빈 결과 가드가 없었다" 주석은 **낡았다** — 바로 아래 코드가
  `finishReplace` 를 부르고, `DataTableService.finishReplace`(`:387-393`)가 `rowsWritten > 0`
  가드를 갖는다. 즉 0행 API 응답은 이미 원본을 덮지 않는다. 과거 상태를 서술한 문장이라 현재 동작을
  오해하게 만들므로 문구만 정정했다(동작 변경 없음). **태스크가 지목한 "선재 결함"은 존재하지 않는다.**

## 동작 차이 (의도된 일탈 1건)

성공/실패 대부분에서 최종 결과는 동일하다. 단 하나 달라지는 경우:

> **재사용 임시 데이터셋 + REPLACE + 성공했지만 0행**
> 이전: truncate 커밋 → `finishReplace(0)` 가 맞바꾸지 않고 tmp 만 버림 → 데이터셋 **빈 상태**
> 이후: 이전 행 **유지**

이는 요구사항의 "성공 시 결과 동일"을 문자 그대로는 어기지만, #685 제품 결정("빈 결과는 기존
데이터를 파괴하지 않는다")과 "빈 결과 가드를 약화하지 말 것"에 맞추는 방향이다 — 선비우기가 그
가드를 조용히 무력화하고 있었다. 의도된 정렬로 기록한다.

**더 흔한 발동 조건은 "API 가 0행을 돌려줄 때"가 아니라 입력이 빈 AI_CLASSIFY 다** —
`AiClassifyExecutor.java:107-110` 은 입력 행이 없으면 `createTempTable` **전에** 그냥 반환하고
스텝은 COMPLETED 로 끝난다. 이제 그 경우 ptmp 출력에는 **이전 실행의 행이 그대로 남는다**(예전에는
선비우기 탓에 비었다). #685 와 일관되고 받아들일 만하지만, 부작용을 명시해 둔다: **후속 스텝은
초록색 상태(COMPLETED) 아래에서 낡은 행을 읽는다.** 빈 상태를 읽는 것보다 낫다는 판단이지만,
"이번 실행 결과"와 "남아 있는 행"이 다를 수 있음을 아는 채로 쓰는 게 안전하다.

## 테스트

`PipelineAsyncRunnerTest` 에 7건 추가(목 수준). 실제 DB 를 건널 필요가 없다 — 이 결함은 러너가 거는
**여분의 호출** 하나이기 때문이다.

"맞바꿈이 이전 행을 실제로 지킨다"는 행(row) 수준 보장은 실제 DB 테스트가 이미 갖고 있다:
`DataTableServiceTest.java:746` `finishReplace_withZeroRows_keepsOriginalAndDropsTemp`(0행이면
원본 `keep-me` 가 남고 tmp 는 정리된다), `:771` `finishReplace_withRows_swapsTempOverOriginal`
(행이 있으면 평소대로 맞바꾼다). 둘 다 `data` 스키마에 실제 테이블을 만들고 SELECT 로 확인한다.

> 정정(리뷰 지적 3): 이전 판에서 근거로 적었던 `ApiCallExecutorTest:754` ·
> `AiClassifyExecutorTest:447/485/595/684/750` 은 **Mockito 전용**이라 목으로 갈아끼운
> `DataTableService` 에 대한 **호출 순서**만 검증한다 — 행 결과의 증거가 아니다. 호출부 계약
> (실패 시 `dropTempTable` 을 부른다)의 증거로는 유효하지만, 보존 자체의 증거는 위 두 건이다.

실패 재현용(수정 없으면 깨짐):
- `실행기_꺼진_API_CALL이_실패해도_임시_데이터셋을_비우지_않는다`
- `실행기_켜진_API_CALL이_실패하면_임시_테이블만_버리고_원본을_비우지_않는다` (+ `dropTempTable` 호출 / `finishReplace` 미호출)
- `AI_CLASSIFY가_실패해도_임시_데이터셋을_비우지_않는다`
- `APPEND_API_CALL은_재사용_임시_데이터셋을_비우지_않는다`
- `APPEND_AI_CLASSIFY는_재사용_임시_데이터셋을_비우지_않는다` — 여기에는 `ArgumentCaptor` 로
  `resolvedStep.loadStrategy() == "APPEND"` 도 함께 못박았다(리뷰 지적 2). 선비우기를 없앤 뒤로
  APPEND 보장 전체가 실행기에 넘어갔는데, 15개 위치 인자 래퍼에서 `outputDatasetName` 과
  `loadStrategy` 가 뒤바뀌면 컴파일은 통과하고 null→"REPLACE" 기본값이 APPEND 를 조용히
  파괴적 REPLACE 로 바꾼다 — 그 이음매를 덮는 테스트가 없었다.

성공 결과 고정(가드):
- `REPLACE_API_CALL_성공은_여전히_임시테이블_맞바꿈으로_전량_교체한다` (createTempTable + finishReplace(,7))
- `REPLACE_API_CALL은_실행기_꺼진_경로에서도_전략을_그대로_위임한다` (`execute(..., "REPLACE", ...)`)

### 변이(mutation) 기록 — 비공허성 증명

**변이 1** — API_CALL 자리에 `dataTableRowService.truncateTable(outputTableName);` 복원:

```
PipelineAsyncRunnerTest > APPEND_API_CALL은_재사용_임시_데이터셋을_비우지_않는다() FAILED
PipelineAsyncRunnerTest > 실행기_켜진_API_CALL이_실패하면_임시_테이블만_버리고_원본을_비우지_않는다() FAILED
PipelineAsyncRunnerTest > 실행기_꺼진_API_CALL이_실패해도_임시_데이터셋을_비우지_않는다() FAILED
PipelineAsyncRunnerTest > REPLACE_API_CALL은_실행기_꺼진_경로에서도_전략을_그대로_위임한다() FAILED
PipelineAsyncRunnerTest > REPLACE_API_CALL_성공은_여전히_임시테이블_맞바꿈으로_전량_교체한다() FAILED
51 tests completed, 5 failed
```

실패 사유(XML 원문):
```
org.mockito.exceptions.verification.NeverWantedButInvoked:
dataTableRowService.truncateTable(<any string>);
Never wanted here:
-> at com.smartfirehub.dataset.service.DataTableRowService.truncateTable(DataTableRowService.java:1035)
But invoked here:
-> at com.smartfirehub.pipeline.service.PipelineAsyncRunner.executeStep(PipelineAsyncRunner.java:744) with arguments: [ptmp_api_append]
```

**변이 2** — AI_CLASSIFY 자리에 복원:
```
PipelineAsyncRunnerTest > AI_CLASSIFY가_실패해도_임시_데이터셋을_비우지_않는다() FAILED
PipelineAsyncRunnerTest > APPEND_AI_CLASSIFY는_재사용_임시_데이터셋을_비우지_않는다() FAILED
51 tests completed, 2 failed
```

**변이 3** (리뷰 지적 2 의 새 단언 검증) — `resolvedStep` 래퍼의 `step.loadStrategy()` 를
`null` 로 바꿔 "전략이 실행기까지 도달하지 않는" 상황을 흉내:
```
PipelineAsyncRunnerTest > APPEND_AI_CLASSIFY는_재사용_임시_데이터셋을_비우지_않는다() FAILED
51 tests completed, 1 failed
```
새 `ArgumentCaptor` 단언만 정확히 걸렸다 — 목 스텁이 `any()` 라 truncate 단언만으로는 잡히지
않던 구멍이다.

세 변이 모두 되돌렸다(`grep MUTATION` 결과 0).

### 검증 스위트

`./gradlew --stop` 후 `./gradlew cleanTest test --tests "com.smartfirehub.pipeline.*" --tests
"com.smartfirehub.dataset.*"` → **868 tests, 0 failures** (기존 861 + 신규 7). 연결 고갈 없음.

이어서 전체 백엔드 스위트(`./gradlew --stop` 후 `cleanTest test`) → **2818 tests, 0 failures,
1 skipped**. `too many clients` 없음. (#394 는 이번 실행에서 재현되지 않았다.)

리뷰 반영(주석 정정 + AI_CLASSIFY 전략 캡처 단언) 후 **전체 스위트 재실행: 2818 tests,
0 failures, 1 skipped** — 동일. 단언을 보탠 것이라 건수는 그대로다.

## 범위 밖 / 남은 것

- `hasSchemaChanged == true` → `deleteTempDataset` → `createTempDataset` 경로는 여전히 실행 전
  이전 데이터를 버린다. 컬럼이 바뀌었으니 불가피하다(옛 행을 새 스키마로 옮길 방법이 없다). 이번
  결함과 별개.
- SQL 스텝 경로(isSelect / preStatements / MERGE)는 손대지 않았다.
- `finishReplace` 의 0행 가드는 이미 네 호출부 모두에 적용돼 있다 — 추가 작업 없음.
