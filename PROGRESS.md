# PROGRESS.md

이 파일은 TaskScope 프로젝트 진행 중 발생하는 모든 트러블슈팅, 설계 결정, 수치화된 성과를 누적 기록합니다.
이력서 작성 시 이 파일을 그대로 참고해 "주요 성과"와 "트러블슈팅" 섹션을 작성합니다.

작업할 때마다 아래 형식으로 항목을 추가해주세요 (최신 항목이 위로 오도록 역순 정렬).

---

## 기록 형식 (템플릿)

### [YYYY-MM-DD] 제목

**카테고리**: 설계 결정 / 트러블슈팅 / 성과 측정

**문제 상황**
무엇이 왜 안 됐는지, 가능하면 에러 로그나 증상 포함

**원인 분석**
어떻게 디버깅했는지, 어떤 가설을 세우고 어떻게 검증했는지

**해결 방법**
무엇을 어떻게 바꿨는지 (코드 변경 핵심, 설계 변경 핵심)

**결과 (수치)**
가능한 한 Before/After 숫자로. 없으면 "수치 미측정"이라고 명시

---

## 누적 성과 요약 (수시 업데이트)

이력서 불릿 작성 시 바로 참고할 수 있도록, 확정된 수치만 이 표에 모아둡니다.

| 항목 | Before | After | 비고 |
|---|---|---|---|
| task당 평균 비용 (premium 3워커 기준) | 제한 없음 시 최대 $1.13 | LARGE $0.217, MEDIUM $0.020, SMALL $0.012 (실측) | Phase 2 단일 호출 기준. 가드레일 강등(premium→standard) 시 ~91% 절감 |
| task당 평균 루프 횟수 | 무제한 (캡 없음) | 최대 5회 (캡), 실측: SMALL/MEDIUM 1회, LARGE code_review 1회, LARGE test_gen 5회(캡) | Phase 4 재실측 (greedy regex + 4096). retry 원인 3가지 분류 완료 |
| trace propagation 정확도 | 0% (워커마다 새 trace 생성) | 100% (19 span 단일 trace) | traceparent 헤더 누락 버그 수정 후 |
| 비용 급증 원인 파악 시간 | 30분+ (로그 전수 grep) | 3분 이내 (Grafana → Jaeger drill-down) | 추정치 vs 실측치 |
| Worker 재기동 시 trace context 생존율 | - | 100% (RabbitMQ 헤더 보존 확인) | e2e 시나리오 3 실측 |
| 복잡도 분류기 피처 수 | 1개 (diffLines) | 3개 (diffLines + 파일 확장자 + 워커 종류) | Phase 5 가설 A+B 구현. 가설 C(import 수)는 교란변수로 기각 |
| 모델 강등 비용 절감 (LARGE code_review, Phase 6) | Premium(Sonnet) $0.0528/건 | Standard(Haiku) $0.0167/건 | 3.2× 절감. 둘 다 1 iteration 완결 (loop cap 없음) |

---

## 기록 시작

---

### [2026-06-20] Phase 6 — 가드레일 강등(premium→standard) 비용·완결성 비교 실측

**카테고리**: 성과 측정 + 트러블슈팅

**목표**

"강등이 실제 버그 탐지에 미치는 영향"을 측정하기 위해 동일한 bug-state 커밋 3개를 premium(Sonnet)과 standard(Haiku)로 각각 dispatch, 비교.

**ground truth 커밋 선정**

| SHA | 버그 내용 | 분류 |
|-----|----------|------|
| `3de0477` | `BaseWorker.extractJson()` lazy regex(`[\s\S]*?`) — 중첩 코드블록에서 조기 종료 | LARGE (368줄) |
| `c23acde` | dispatcher `RabbitMQConfig`에 `setObservationEnabled(true)` 누락 → traceparent 전파 불가 | LARGE (345줄) |
| `8ed2842` | `llm.call` span에 `TASK_TYPE` attribute 누락 | LARGE (448줄) |

**Standard 강제 방법**: `GUARDRAIL_COST_THRESHOLD_USD=0.000001` → Premium 런 직후 Prometheus `[10m]` 윈도우에 avg cost ($0.053) > threshold → CostGuardrailService가 premium→standard 강등 발동. 코드 변경 없이 기존 가드레일 메커니즘 재사용.

**실측 결과**

| 등급 | 모델 | 3건 합계 | 건당 평균 | avg input | avg output | iter | loop cap |
|------|------|---------|----------|----------|-----------|------|----------|
| Premium | claude-sonnet-4-6 | $0.1585 | $0.0528 | 7,436 tok | 2,034 tok | 전부 1 | 없음 |
| Standard | claude-haiku-4-5 | $0.0501 | $0.0167 | 7,436 tok | 1,850 tok | 전부 1 | 없음 |
| **비율** | | **3.2×** | **3.2×** | 동일 | −9% | | |

**버그 감지율 (Standard, 3000자 로그 기준)**

| 커밋 | 버그 유형 | Standard(Haiku) 결과 |
|------|----------|---------------------|
| `3de0477` | lazy regex | **PARTIAL** — `[\s\S]*?` 언급했으나 "성능 저하" 프레임. 중첩 블록 조기 종료 실패 케이스 미식별 |
| `c23acde` | observationEnabled | **MISS** — OTel을 "Strength"로 기재, `setObservationEnabled(true)` 누락 미식별 |
| `8ed2842` | task.type | **MISS** — "Span Attributes Not Validated" 일반 경고만, TASK_TYPE 누락 구체 미식별 |

**Premium(Sonnet) 버그 감지율**: 로깅 한계(300자 컷오프, 2차 시도 발생 전 로깅 미구현)로 **측정 불가** — Phase 5의 가설 C 기각과 같은 패턴으로 데이터 수집 실패로 기록.

### Phase 6 결론

**목표**: 강등(premium→standard)이 실제 결함 탐지에 미치는 영향 측정

**측정 가능했던 것**: 비용·완결성 비교 (Premium $0.0528/건 vs Standard $0.0167/건, 3.2배 차이, 양쪽 모두 1 iteration·loop cap 없음)

**측정 가능했던 것 2**: Standard의 실제 결함 탐지율 — ground truth 3건 중 MISS 2건, PARTIAL 1건, HIT 0건

**측정 불가했던 것**: Premium의 결함 탐지율 (로깅 한계로 텍스트 미캡처, 2회 시도 실패)

**결론**: 비용·완결성 지표만으로는 강등이 "무해"해 보이지만, 실제 ground truth 비교에서는 standard가 설정 누락성 결함(observationEnabled, task.type)을 일관되게 놓침. 비용 가드레일은 결함 탐지 품질을 함께 고려한 정책으로 보완 필요 — 예: 보안/설정 관련 diff는 비용 임계값과 무관하게 premium 유지하는 예외 규칙.

**트러블슈팅 — 로깅 300자 컷오프**

**문제**: `BaseWorker.invokeLlm()`에 result 텍스트 로깅 없어 1차·2차 Premium 결과(~$0.27) 텍스트 미캡처.

**원인**: 초기 구현 시 응답 텍스트 로깅 필요성 미설계. LLM 호출 비용만 기록하고 내용 불기록.

**해결**: `log.info("[{}] result_preview={}", ..., parsed.result().substring(0, 3000))` 추가. Spring 파일 로깅(`WORKERS_LOG_FILE=workers.log`)으로 영구 캡처.

**교훈**: LLM 워커에서 응답 텍스트를 평가해야 하는 실험을 설계할 때는 반드시 로깅 → 테스트 → 실험 순서를 지켜야 함 (지금은 역순으로 실험 → 로깅 추가 → 재실험이 됨).

---

### [2026-06-20] Phase 5 시작 — 복잡도 분류 고도화 (need_context 피처 탐색)

**카테고리**: 설계 결정 + 성과 측정

**시작 전 체크리스트 확인 결과 (2026-06-20)**

| 항목 | 결과 |
|---|---|
| Java 프로세스 정리 | 3개 발견 → 전부 종료, 최종 0개 확인 |
| RabbitMQ consumers | 각 큐 consumers=0 확인 (워커 미기동 상태) |
| Docker 스택 | `docker compose up -d` 재기동 완료 |

→ 깨끗한 상태로 Phase 5 시작

---

### [2026-06-20] Phase 5 — 가설 A+B 복잡도 분류기 구현

**카테고리**: 설계 결정 + 구현

**구현 내용**

기존 `ComplexityRouter.route(int diffLines)` (단일 임계값 200) → 파일 확장자 + 워커 종류를 반영한 멀티-피처 라우팅으로 확장.

**가설 A — 파일 확장자(context-heavy types)**

| 변경 전 | 변경 후 |
|---|---|
| 확장자 무관 `diffLines ≥ 200` → premium | `.sh`, `.yml`, `.yaml`, `.tf`, `Dockerfile` 포함 시 `diffLines` 무관 → premium |

**근거**: Phase 3 need_context 실측에서 `.sh` 파일 100%(2/2), `.yml` 포함 커밋 100%(2/2) need_context 발생. 이 파일들은 외부 스크립트/설정을 참조해 diff만으로 완전한 리뷰 불가.

**가설 B — 워커 종류별 LARGE 임계값**

| 워커 | Before | After |
|---|---|---|
| `code_review`, `security` | 200줄 → premium | 200줄 → premium (유지) |
| `test_gen` | 200줄 → premium | **150줄 → premium** |

**근거**: Phase 4 재실측에서 `test_gen`이 동일 LARGE 커밋에서 출력 토큰 >4096 발생(루프 캡 도달). `code_review`/`security`는 동일 커밋 1회 완료. 즉 test_gen은 더 낮은 임계값에서 premium(Sonnet) 모델이 필요.

**추가 버그 수정**: `ComplexityRouter.modelFor()` 반환값 `claude-haiku-4-5-20251001` → `claude-haiku-4-5` (날짜 suffix 제거)

**API 변경**: `CommitTaskService`에서 task type별로 `route(diffLines, changedFiles, taskType)` 호출. diff에서 변경 파일 목록 파싱(`diff --git a/... b/...` 라인 추출).

**테스트**: `ComplexityRouterTest` 8개 케이스 추가 + 전체 빌드 통과

**결과 (수치)**
- 비용: $0 (코드 변경, LLM 호출 없음)
- 예상 효과: `.sh`/`.yml` 포함 커밋 → Sonnet 라우팅으로 need_context 루프 1회 감소 예상
  (Phase 3 실측 기준 need_context 비용: Haiku ~$0.003/iteration → Sonnet으로 줄이면 iteration↓, 총비용↓)

---

### [2026-06-19] 실제 API 비용 급증 원인 분석 — 구 worker 프로세스 누수

**카테고리**: 트러블슈팅 + 운영 교훈

**문제 상황**

오늘 실측 기록상 측정된 LLM 호출 비용은 $0.3~0.4 수준이었으나 실제 청구액은 약 $5. 측정값의 10~15배.

**원인 분석**

RabbitMQ 통계에서 핵심 단서 발견:
```
code-review queue: publish=55, deliver=358, redeliver=303  ← 재소비 비율 6.5×
security queue:    publish=24, deliver=323, redeliver=299
test-gen queue:    publish=24, deliver=312, redeliver=288
```

원인 1 (주요): **이전 세션의 Java worker 프로세스 13개가 살아있는 채로 API key 포함 환경변수를 상속해 RabbitMQ consumer로 계속 붙어 있었음.**
- `consumers: 4`가 각 큐에 동시 접속 (정상: 1)
- Spring AMQP 기본 동작: `@RabbitListener` 에서 예외 발생 → 메시지 requeue → 다른 worker가 동일 메시지 수신 → 또 LLM 호출
- 55개 task × 6.5배 재시도 × 평균 $0.007 ≈ **$2.5** (이것만으로)

원인 2: **test_gen LARGE Sonnet loop cap 반복** — $0.083 × 5 = $0.415/회. 디버깅 중 여러 번 발생.

원인 3: **need_context → retry×4 패턴** — $0.073/회, 같은 스크립트 커밋을 여러 번 dispatch.

**해결 방법**

실험 세션 시작 전 체크리스트:
1. `Get-Process java | Stop-Process -Force` — 이전 세션 프로세스 전수 정리
2. RabbitMQ Management(`localhost:15672`) → 큐 consumers 수 확인 (각 큐 1개여야 정상)
3. 새 worker 기동 후 로그에서 `maxTokens=`, `client initialized` 확인 후 dispatch

**결과 (수치)**

- 측정된 비용: $0.3~0.4
- 실제 청구: ~$5
- 갭 원인: 구 worker 프로세스 누수로 인한 메시지 6.5× 재소비
- 이후: 프로세스 정리 후 단일 consumer 확인 → 예상 범위 내 비용 통제 가능

**향후 비용 추정 (잔여 작업)**

| 단계 | 내용 | 예상 비용 |
|---|---|---|
| Phase 5: 복잡도 분류 고도화 | 10~20개 커밋 샘플, 거의 Haiku | $0.5~1.0 |
| Phase 6: 강등 전/후 품질 비교 | 5~8개 커밋 × 2모델 | $0.3~0.6 |
| test_gen LARGE 문제 해결 검증 | Sonnet 5~10회 | $0.2~0.4 |
| **합계** | | **$1.0~2.0** |

비용 절감 원칙: Sonnet은 LARGE 비교 포인트(1~2개)에만. 샘플은 "패턴을 보여줄 수 있는 최소 수"로.

---

### [2026-06-19] maxTokens 2048→4096 조정 + lazy regex 버그 수정 — Before/After 재실측

**카테고리**: 트러블슈팅 (원인 분석 → 가설 수정 → 검증)

**문제 상황**

Phase 3 실측에서 retry 33%, loop cap 20% 발생. 최초 가설: "maxTokens=2048 초과로 JSON 잘림".  
그러나 maxTokens를 4096으로 올려도 SMALL(4 diffLines) 커밋에서 iter=4까지 retry 발생:
```
iter=1: out=524  → JSON parse failed: Unexpected end-of-input: was expecting closing quote
iter=2: out=597  → JSON parse failed (same)
iter=3: out=553  → JSON parse failed (same)
iter=4: out=464  → complete
```
out=464~597은 4096과 무관 → **maxTokens가 원인이 아니었음**.

**원인 분석**

`BaseWorker.extractJson()`의 lazy 정규식이 실제 원인:
```java
// Before (bug): lazy *? → 내부 코드블록 ``` 에서 조기 종료
Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```")
```

모델이 `"result"` 필드 안에 코드 예시(` ```java ... ``` `)를 포함하면:
```
```json
{
  "result": "Use this:\n```java\ncode\n```\nmore"
}
```
```
lazy `*?`가 첫 번째 내부 ` ``` `에서 멈춤 → 캡처 결과: `{\n  "result": "Use this:\n` (미완성)  
→ Jackson 파싱 에러: "Unexpected end-of-input: was expecting closing quote for a string value"

**해결 방법 (2가지 동시 적용)**

1. **lazy `*?` → greedy `*`** (`BaseWorker.java:29`): 마지막 ` ``` `까지 캡처
```java
// After (fixed): greedy * → 마지막 ``` 까지 캡처, 내부 코드블록 무시
Pattern.compile("```(?:json)?\\s*([\\s\\S]*)```")
```
2. **maxTokens 2048 → 4096** 환경변수화 (`application.yml` + `AnthropicLlmClient.java`):
   - `anthropic.max-tokens: ${LLM_MAX_TOKENS:4096}` (기본값 4096, 실험 시 코드 수정 불필요)
   - LARGE(Sonnet) 커밋의 자연스러운 출력 길이(~2100 토큰)가 2048을 초과하는 케이스 해소

**결과 — Before/After 실측표 (동일 커밋 크기 기준)**

| 지표 | Before (2048 + lazy) | After (4096 + greedy) | 변화 |
|---|---|---|---|
| SMALL code_review (iter) | 4회 (retry×3) | **1회** | -75% |
| SMALL code_review 비용 | $0.012 (4 iter 합산) | **$0.002** | -83% |
| MEDIUM code_review (iter) | 1~3회 | **1회** | 안정화 |
| MEDIUM test_gen out=1960 (iter) | retry(2048 초과 예측) | **1회** | 잘림 해소 ✓ |
| LARGE code_review Sonnet out=2139 (iter) | retry(2048 초과) | **1회** | 잘림 해소 ✓ |
| LARGE test_gen Sonnet (iter) | loop cap(5) | **loop cap(5)** | 변화 없음 |

**LARGE test_gen Sonnet 잔존 문제 (신규 발견)**

`LARGE test_gen (d1249d8, 465 diffLines, Sonnet, in=7203)`:
- iter=1~5: out=4096 매번 maxTokens 도달 → loop cap
- 비용: $0.083 × 5회 = **$0.415** (maxTokens=2048보다 오히려 5배 증가)
- 원인: 테스트 코드는 구조적으로 장문 — 4096도 부족한 "본질적 장문 task 유형"
- 결론: **이 케이스는 maxTokens 증가로 해결 불가**. 다음 단계 설계 필요:
  - 옵션 A: `test_gen`에 한해 "파일 분할 → 워커별 테스트 케이스 분리" 프롬프트 전략
  - 옵션 B: 복잡도 분류 고도화 — LARGE test_gen을 별도 task 유형으로 분리

**원인 분류 재정리 (retry 3가지 유형)**

| 유형 | 원인 | 수정 후 |
|---|---|---|
| lazy regex 잘림 | 모델 응답 내 코드블록 backtick 충돌 | ✅ greedy regex로 해소 |
| maxTokens=2048 초과 | LARGE 커밋(Sonnet) output ~2100 토큰 | ✅ 4096으로 해소 |
| 본질적 장문 (test_gen LARGE) | 테스트 코드 생성 자체가 4096+ 토큰 필요 | ❌ 미해결 (다음 단계 과제) |

**커밋**: `e3f4d29` (`feat/max-tokens-4096-remeasure` → main PR #14)

**알려진 한계 (Known Limitation)**

> `test_gen` 워커 + LARGE 커밋(diffLines ≥ 300) 조합에서 출력이 4096 토큰을 초과해 loop cap(5회)에 도달하고 완성된 테스트 코드를 반환하지 못함.
>
> - 근본 원인: 테스트 케이스 생성은 구현 코드보다 장문이 되는 구조적 특성 (JUnit boilerplate + assertion × n cases)
> - 영향 범위: LARGE 커밋의 `test_gen` task — code_review/security는 동일 크기에서 1회 완료
> - maxTokens를 4096 이상으로 올려도 비용 증가 대비 효과 불분명 (loop cap까지 총 $0.415/task)
> - 해결 방향: (1) test_gen 프롬프트 전략 변경 — "3가지 핵심 케이스만 작성" 등 출력 범위 제한, (2) 복잡도 분류 고도화 — LARGE test_gen을 별도 task 유형으로 분리해 전용 처리 전략 적용
> - 현재 동작: loop cap 도달 → span에 `guardrail.reason=max_iterations_5_reached (last_status=retry)` 기록, 불완전 결과는 반환하지 않음 (데이터 누출 방지는 유지)

---

### [2026-06-19] Code Review — PR #11 멀티 앵글 리뷰 및 HIGH 버그 4개 수정

**카테고리**: 트러블슈팅 + 코드 품질

**리뷰 방식**

Phase 3 PR(#11)에 대해 10개 앵글 병렬 에이전트 리뷰 실행:
A(라인별 스캔) · B(제거 동작 감사) · C(크로스파일 추적) · D(Java 함정) · E(래퍼 정확성) + Reuse · Simplification · Efficiency · Altitude · Conventions(CLAUDE.md) → verify(1-vote) → sweep.

**발견된 버그 (10건, 4 HIGH confirmed)**

| # | Severity | 파일 | 이슈 |
|---|----------|------|------|
| 1 | 🔴 HIGH ✅ | `BaseWorker.java:92` | `parseRepo()` `IllegalArgumentException` 미처리 → AMQP 무한 requeue |
| 2 | 🔴 HIGH ✅ | `LlmResponse.java:34` | `parseError()` null-status 응답을 `"complete"` 처리 → 루프 조기 종료 |
| 3 | 🔴 HIGH ✅ | `BaseWorker.java:100` | 최종 이터레이션 `need_context` 반환 시 `GUARDRAIL_ACTION=force_terminate` 오기록 |
| 4 | 🔴 HIGH ✅ | `GitHubFileClient.java:59` | `ref.substring(0,7)` — SHA < 7자 시 `StringIndexOutOfBoundsException`, 성공한 fetch 결과 버려짐 |
| 5 | 🟡 MED ⚠️ | `GitHubFileClient.java:61` | `e.getMessage()` null → `StringBuilder.append(null)` → LLM 컨텍스트에 리터럴 `"null"` 삽입 |
| 6 | 🟡 MED ⚠️ | `GitHubFileClient.java:60` | 404/403 silently swallowed → 파일 fetch 실패가 `(fetch failed: ...)` 텍스트로 LLM에 전달 |
| 7 | 🔵 LOW ✅ | `GitHubFileClient.java` + `LlmResponse.java` | 단위 테스트 없음 (CLAUDE.md 위반) |
| 8 | 🔵 LOW ⚠️ | `BaseWorker.java:79` | `AGENT_LOOP_ITERATION` 매 이터레이션 덮어씀 → 마지막 번호 vs 총 횟수 구분 불가 |
| 9 | 🔵 LOW ⚠️ | `BaseWorker.java:42` | `ThreadLocal` 인스턴스 필드 (비-static) → context refresh 시 AMQP 스레드 풀 slot 누수 |
| 10 | 🔵 LOW ⚠️ | `BaseWorker.java:154` | `invokeLlm()`이 model을 독립적으로 재결정 → 서브클래스 `resolveModel()` 오버라이드 시 span label과 API 호출 불일치 가능 |

**수정 내용 (HIGH 4개 즉시 반영)**

1. `ref.substring(0,7)` → `ref.length() >= 7 ? ref.substring(0,7) : ref` (crash 방지)
2. `e.getMessage()` null 가드: null 메시지 → `e.getClass().getSimpleName()`으로 fallback
3. `LlmResponse.parseError()` → `LlmResponse.missingStatus()`: null-status 응답을 `"complete"` 대신 `"retry"` 반환
4. `parseRepo()` `IllegalArgumentException` catch: `runAgentLoop` 내에서 잡아 span에 `guardrail.reason=invalid_repo_url` 기록 후 조기 종료 (무한 requeue 방지)
5. 루프 캡 `GUARDRAIL_REASON`에 `last_status` 포함: `"max_iterations_5_reached (last_status=need_context)"` → Grafana에서 런어웨이 루프 vs 합법적 context 요청 구분 가능

**결과 (수치)**

- 리뷰 앵글: 10개 → 후보 ~30개 → 중복 제거 후 10개 최종 (REFUTED 2건: URI template 확장, resolveModel 결정론적)
- 수정 커밋: `13f0255` (3 files, 27 insertions, 12 deletions)
- 빌드: `BUILD SUCCESSFUL`, 기존 테스트 전체 통과
- PR #11 머지 완료 (2026-06-19T05:39:05Z)

---

### [2026-06-19] Phase 3 — LLM 판단 기반 루프 + JSON 잘림 문제 해결

**카테고리**: 트러블슈팅 + 성과 측정

**구현 내용**

- `LlmResponse`: Claude JSON 응답 파싱 (`status: complete|need_context|retry`)
- `GitHubFileClient`: `need_context` 발동 시 GitHub Contents API로 파일 실시간 fetch (최대 3개)
- `BaseWorker`: ThreadLocal로 iteration 간 extraContext 전달, `agent.loop_reason` span attribute 기록
- 워커별 system prompt: JSON 응답 형식 강제 + status 판단 기준 명시

**트러블슈팅 — JSON 잘림 문제**

Phase 3 첫 테스트에서 MEDIUM 커밋(in=2348) 처리 시 JSON parse 실패:
```
JSON parse failed, treating as complete: Unexpected end-of-input: was expecting closing quote
at line: 4, column: 1267
```
`out=728` 토큰인데 JSON의 `result` 문자열 필드 중간에서 종료. maxTokens=1024 제한 + Claude의 상세 리뷰 시도로 JSON 구조가 완성되지 못한 채 잘림.

기존 `parseError` 처리는 잘린 응답을 `complete`로 처리 → 불완전한 리뷰 결과 반환.

**해결 방법 (2가지)**

1. `maxTokens 1024 → 2048`: 출력 예산 확대로 JSON이 완성될 가능성 증가
2. `parseResponse()` 수정:
   - `stopReason == "max_tokens"` → `retry` 반환 (출력 잘림 명시적 감지)
   - JSON parse 예외 → `retry` 반환 (이전: `complete` fallback, 불완전 결과 누출 방지)

이후 재시도 시 LLM이 동일 diff에 대해 더 간결한 JSON을 생성해 완료.

**실측 루프 횟수 데이터 (Phase 3 — 전체)**

초기 테스트 (JSON 잘림 수정 후):

| 커밋 크기 | 모델 | Input | Output | 이터레이션 | status |
|---|---|---|---|---|---|
| SMALL | claude-haiku-4-5 | 526 | 498 | 1 | complete |
| MEDIUM | claude-haiku-4-5 | 2,344 | 697 | 1 | complete |
| SMALL | claude-haiku-4-5 | 530 | 387 | 1 | complete |

추가 테스트 (HIGH 버그 수정 후 — `retry` 및 `need_context` 실제 발동 확인):

| 커밋 크기 | 워커 | 모델 | Input | 이터레이션 | 상세 |
|---|---|---|---|---|---|
| MEDIUM | code_review | claude-haiku-4-5 | 1,726 | 3 | iter 1→retry(잘림), iter 2→retry(잘림), iter 3→complete |
| SMALL | code_review | claude-haiku-4-5 | 2,281 | 2 | iter 1→retry(잘림), iter 2→complete |
| SMALL (script diff) | test_gen | claude-haiku-4-5 | 2,467→7,055 | 5 (캡 도달) | iter 1→**need_context** (파일 3개 fetch), iter 2~5→retry(잘림), **loop cap** |

**`need_context` 실측 상세 (2026-06-19 19:48)**

```
[worker.test_gen] task=27c77625 iter=1 in=2,467 out=210 status=need_context
[worker.test_gen] task=27c77625 need_context → fetching 5 files (MAX_FILES_PER_REQUEST=3으로 3개 제한)
[github-file] fetched scripts/context-loader.sh @ 17271c3 (4,286 chars)
[github-file] fetched scripts/prompt-selector.sh @ 17271c3 (2,419 chars)
[github-file] fetched scripts/constraint-check.sh @ 17271c3 (3,128 chars)
[worker.test_gen] task=27c77625 iter=2 in=7,055 out=2,048 status=retry  ← 컨텍스트 추가 후 토큰 폭증
[worker.test_gen] task=27c77625 iter=3,4,5 in=7,055 out=2,048 status=retry
[worker.test_gen] hit loop cap (5, last_status=retry)  ← 수정된 GUARDRAIL_REASON 포함
```

**결과 (수치)**

- `retry` 분기 실제 발동: JSON 잘림(`Unexpected end-of-input`) → retry 재시도 ✅
- `need_context` 분기 실제 발동: iter=1에 need_context → GitHub Contents API로 파일 3개 즉시 fetch ✅
- `last_status=retry` GUARDRAIL_REASON 포맷 동작 확인 ✅ (HIGH 버그 수정 효과)
- fetch 후 input 토큰: 2,467 → 7,055 (+186%) — 컨텍스트 파일이 대폭 추가됨
- 루프 캡 도달: fetch된 컨텍스트로도 output이 maxTokens(2048)를 초과 → 루프 캡까지 retry 반복
  - **후속 개선 필요**: maxTokens 2048 → 4096 상향 또는 fetch 파일 요약 후 전달 검토
- Phase 2 stub 가정(2~3회) vs Phase 3 실측: SMALL/MEDIUM 1~3회 일치, need_context 케이스는 5회 캡 도달

---

### [2026-06-19] Phase 2 — 실제 Claude API 연동 및 실측 비용 데이터 수집

**카테고리**: 성과 측정

**구현 내용**

- Anthropic Java SDK(`com.anthropic:anthropic-java:2.34.0`) 도입
- `AnthropicLlmClient`: Spring `@Value("${anthropic.api-key}")` 우선, 비어 있으면 `ANTHROPIC_API_KEY` 환경변수 자동 폴백
- `BaseWorker.invokeLlm()` 구체 구현: systemPrompt(추상) + diff 기반 user message → 단일 Claude 호출, finished=true
- 모델 라우팅 수정: `standard → claude-haiku-4-5`, `premium → claude-sonnet-4-6`
- 워커별 system prompt: code-review(코드품질/버그/컨벤션), security(하드코딩 시크릿/인젝션 취약점), test-gen(JUnit 5 테스트 케이스 제안)

**트러블슈팅 — ANTHROPIC_API_KEY 전달 실패**

workers 기동 후 API 호출마다 `401: x-api-key header is required` 반복 발생.

원인: `ANTHROPIC_API_KEY` 환경변수를 다른 PowerShell 세션에서 설정했거나 `$env:` 범위로만 설정 → Gradle 데몬 프로세스는 데몬 기동 시점의 환경을 상속하므로 나중에 설정한 env var를 인식 못함 (GITHUB_TOKEN 때와 동일한 패턴).

해결: `workers/src/main/resources/application-local.yml`(gitignore)에 키를 직접 기재, `SPRING_PROFILES_ACTIVE=local`로 기동 → `[anthropic] client initialized (apiKey from config)` 로그 확인.

**실측 비용 데이터 (Phase 2, 단일 호출 기준)**

| 커밋 크기 | 모델 | 워커 | Input 토큰 | Output 토큰 | 호출당 비용 |
|---|---|---|---|---|---|
| LARGE | claude-sonnet-4-6 | test_gen | 19,004 | 1,024 | $0.0724 |
| LARGE | claude-sonnet-4-6 | security | 19,025 | 1,024 | $0.0724 |
| SMALL | claude-haiku-4-5 | code_review | 645 | 534 | $0.0033 |
| SMALL | claude-haiku-4-5 | code_review | 269 | 207 | $0.0013 |
| SMALL | claude-haiku-4-5 | security | 658 | 503 | $0.0032 |
| MEDIUM | claude-haiku-4-5 | code_review | 1,875 | 1,024 | $0.0070 |
| MEDIUM | claude-haiku-4-5 | test_gen | 1,610 | 1,024 | $0.0067 |
| MEDIUM | claude-haiku-4-5 | security | 1,631 | 1,023 | $0.0067 |

**결과 (수치)**

단일 task 기준 Phase 2 실측 비용 (3 워커 합산):
- LARGE 커밋 (premium/Sonnet): ~**$0.217/task** (test_gen $0.072 + security $0.072 + code_review ~$0.073)
- MEDIUM 커밋 (standard/Haiku): ~**$0.020/task** (3 워커 합산)
- SMALL 커밋 (standard/Haiku): ~**$0.012/task** (3 워커 합산)

Phase 1 stub 대비 premium 모델 실측이 약 35% 저렴 (stub: $0.62, 실측 단일호출: $0.217). stub은 멀티 루프(2~3회) 가정이었고, Phase 2는 단일 호출이므로 loop 도입 후 비교 필요.

Phase 3(루프 로직 도입) 후 반복 횟수 실측하면 stub 가정치와 직접 비교 가능.

---

### [2026-06-19] Prometheus PromQL 쿼리 파싱 에러 — RestClient URL 이중 인코딩

**카테고리**: 트러블슈팅

**문제 상황**

`CostGuardrailService`가 Prometheus에 PromQL 쿼리를 보낼 때 400 에러 반복 발생:
```
bad number or duration syntax: "28"
parse error at 1:5
```
기능은 fail-open이라 서비스 중단은 없었으나 task당 15번씩 WARN 로그 폭발.

**원인 분석**

기존 코드: `URLEncoder.encode(query, UTF_8)`로 수동 인코딩 후 문자열 연결로 URI 구성:
```java
.uri("/api/v1/query?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8))
```
`sum(`의 `(`는 `%28`로 인코딩됨. 그런데 Spring `RestClient.uri(String)` 메서드가 내부적으로 URL을 다시 파싱할 때 `%`를 `%25`로 재인코딩 → Prometheus가 받는 쿼리: `sum%28increase%28...` (URL 디코딩 전 상태). Prometheus PromQL 파서가 `sum%`를 보고 `%28`의 `28`을 잘못된 숫자 토큰으로 해석 → parse error.

**해결 방법 (2단계)**

1차 시도 — `uri(b -> b.path(...).queryParam("query", query).build())`:
PromQL 쿼리 값 안에 `{task_type="code_review"}` 중괄호가 있어, Spring이 이를 URI 템플릿 변수 `{task_type}`으로 오해하고 `build()` 시 `IllegalArgumentException` 발생 → 500으로 터짐 (RestClientException이 아니라 catch 우회).

최종 수정 — 쿼리 값을 명시적 템플릿 변수 `{q}`로 분리:
```java
.uri(b -> b.path("/api/v1/query")
           .queryParam("query", "{q}")
           .build(Map.of("q", query)))
```
Spring이 `{q}`를 실제 query 문자열로 치환하면서 RFC 3986 인코딩 적용 → `{task_type}`는 값으로 취급되어 `%7Btask_type%3D...`로 안전하게 인코딩됨.
catch 블록도 `Exception`으로 확장해 URI 빌더 예외도 fail-open 처리.

**결과 (수치)**

- 수정 전: task당 15회 WARN 로그 (400 Bad Request), 잘못된 1차 수정 시 500 폭발
- 수정 후: Prometheus WARN 로그 0건, Prometheus 데이터 없을 때 null 반환(fail-open) 정상 동작
- 두 repo 30개 커밋 fetch → SMALL=3/MEDIUM=2/LARGE=1 샘플링 → 18 LLM task dispatch 확인

---

### [2026-06-19] GitHub API rate limit — 비인증 호출 즉시 차단

**카테고리**: 트러블슈팅

**문제 상황**

`GitHubClient` 초기 구현 시 PAT 없이 GitHub REST API(`GET /repos/{owner}/{repo}/commits`) 호출 테스트:
```
HTTP 403 Forbidden
X-RateLimit-Limit: 60
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 1750330412
message: "API rate limit exceeded for <IP>. ..."
```
두 repo에서 각 15개 커밋 상세 조회(listCommits 1 + getCommit 15 = 16 req/repo × 2 = 32 req) 시도 시 비인증 60 req/h 제한에 즉시 도달.

**원인 분석**

GitHub REST API의 비인증 rate limit은 IP당 **시간당 60 요청**. CommitSampler가 원하는 최소 커밋 수(각 repo 15개 × 상세 조회)만으로도 30 req/h를 소비 — 2번 실행하면 즉시 소진. 개발/테스트 반복 상황에서 사실상 사용 불가.

**해결 방법**

GitHub PAT(Personal Access Token) 발급 — public repo 읽기 권한(`public_repo` scope)만으로 충분. `.env`에 `GITHUB_TOKEN`으로 관리, `GitHubClient` 생성자에서 `"Bearer " + token` 헤더 주입:
```java
this.restClient = RestClient.builder()
    .defaultHeader("Authorization", "Bearer " + token)
    .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
    .build();
```
`.env.example`에 `GITHUB_TOKEN=ghp_your_token_here` 추가, `.env` 자체는 `.gitignore`로 커밋 제외.

**결과 (수치)**

- PAT 인증 후 rate limit: **5,000 req/h** (비인증 대비 83배)
- 두 repo 각 15개 커밋 상세 조회 = 32 API 호출 → 제한의 0.64% 소비
- 하루 종일 반복 테스트해도 제한 도달 불가 (5000 / 32 = 156회 실행 가능/h)

---

### [2026-06-19] End-to-end 검증 — 3개 시나리오 실측

**카테고리**: 성과 측정

**시나리오 1 — 정상 케이스 (standard 등급, diffLines=100)**

`POST /tasks` → 3개 워커 동시 처리. Jaeger에서 단일 trace 확인:

```
http post /tasks [734ms, root]            ← dispatcher HTTP span
  └── task.dispatch [467ms]               ← 수동 span (TaskService)
        ├── AMQP send × 3 [1~19ms]       ← Micrometer 자동 계측
        ├── AMQP receive code-review [165ms]
        │     └── worker.code_review [2.6ms]
        │           ├── llm.call [iter 1]
        │           └── llm.call [iter 2, finished]
        ├── AMQP receive security [165ms]
        │     └── worker.security [3.2ms]
        │           ├── llm.call [iter 1~3, finished]
        └── AMQP receive test-gen [165ms]
              └── worker.test_gen [3.1ms]
                    └── llm.call [iter 1~3, finished]
```

- **19개 span 단일 trace 귀속** (traceID: `6556dd30...`)
- trace 끊김 없음. dispatcher → AMQP → 3개 워커 → llm.call 전체 연결

---

**시나리오 2 — 루프 캡 유도 (MAX_LOOP_ITERATIONS=2로 임시 조정)**

`security`, `test_gen`은 iteration 3에 완료되므로 캡(2)에 걸림. Jaeger 확인:

```
worker.security
  tags: agent.loop_cap_hit=true
        guardrail.action=force_terminate
        guardrail.reason=max_iterations_2_reached

worker.test_gen
  tags: agent.loop_cap_hit=true
        guardrail.action=force_terminate
        guardrail.reason=max_iterations_2_reached
```

- 루프 캡 발동 → span에 `force_terminate` 기록 확인
- `worker.code_review`는 iteration 2 정상 완료 (캡 미발동)
- 검증 후 `MAX_LOOP_ITERATIONS=5` 원복

---

**시나리오 3 — Worker 재기동 중 trace context 생존**

절차: Workers 종료 → `POST /tasks` (메시지 큐 대기) → Workers 재기동

| 상태 | Jaeger span 수 |
|------|---------------|
| Workers 종료 직후 | 5개 (dispatcher + AMQP send만) |
| Workers 재기동 후 | **19개** (동일 traceID에 worker span 합류) |

- traceID `13a49781...` 동일 trace에 worker span 14개 추가 확인
- RabbitMQ가 메시지 requeue 시 `traceparent` 헤더 보존 → trace context 생존율 **100%**

---

### [2026-06-19] 가드레일 구현 — Prometheus 비용 집계 기반 모델 자동 강등

**카테고리**: 설계 결정 + 성과 측정

**설계 배경**

루프 캡(loop cap)으로 루프 폭증은 막을 수 있지만, "복잡하지 않은 task에 premium 모델이 과도하게 배정되는 문제"는 static 휴리스틱(diffLines 기준)만으로는 해결 안 됨. 실제 운영 데이터(Prometheus) 기반 동적 재조정이 필요.

**구현 내용**

`CostGuardrailService.apply(grade, taskType)`:
1. Prometheus `/api/v1/query` 호출 — 최근 10분간 task type별 LLM 호출 1회당 평균 비용 쿼리
   ```
   sum(increase(llm_cost_usd_total{task_type="X"}[10m]))
   / sum(increase(llm_calls_total{task_type="X"}[10m]))
   ```
2. 임계값(기본 $0.05) 초과 시 `premium → standard` 강등
3. Prometheus 미응답 / 데이터 없음 → **fail-open** (기존 등급 유지)
4. 강등 발동 시 span에 기록: `guardrail.action=model_downgrade`, `guardrail.reason=avg_cost_X_exceeds_threshold_Y`

**설계 선택 — fail-open**

가드레일이 Prometheus에 의존하므로 모니터링 시스템 장애가 서비스 장애로 전파되면 안 됨.
`RestClientException` → null 반환 → 기존 등급 유지로 처리.

**결과 (수치)**

stub 기준 premium → standard 강등 시:
- premium 3워커 비용: $0.62/task
- standard 3워커 비용: $0.023/task
- **절감률 ~96%** (비용 스파이크 차단 시나리오 기준)

단위 테스트 5케이스 통과 (강등/유지/fail-open/standard 패스스루/경계값)

---

### [2026-06-19] Prometheus 메트릭 파이프라인 — spanmetrics 한계 발견 및 해결

**카테고리**: 트러블슈팅

**문제 상황**

OTel Collector의 `spanmetrics` 커넥터로 Prometheus에 `taskscope_calls_total`, `taskscope_duration_milliseconds` 메트릭이 생성됨. 그런데 `llm.cost_usd` 같은 **span attribute 값**은 Prometheus 메트릭으로 변환되지 않음.

`taskscope_calls_total{span_name="llm.call"}` 쿼리 → llm_model 레이블은 있지만 비용 집계 불가.

**원인 분석**

`spanmetrics` 커넥터의 역할은 span 수(call count)와 지속시간(duration histogram)을 메트릭으로 변환하는 것. span attribute에 기록된 `llm.cost_usd=0.063` 같은 숫자값은 메트릭으로 내보내는 기능 없음.

**해결 방법**

앱에서 Micrometer 카운터를 직접 OTel Collector로 push:
- `micrometer-registry-otlp` 의존성 추가
- `BaseWorker.callLlm()` 내부에서 LLM 호출 후 4개 카운터 기록:
  - `llm.calls` (task_type, llm_model, task_complexity 태그)
  - `llm.cost.usd` (누적 비용)
  - `llm.input.tokens` / `llm.output.tokens`
- OTel Collector metrics pipeline에 `otlp` receiver 추가

**결과 (수치)**

- Prometheus에서 `llm_cost_usd_total`, `llm_calls_total` 등 4개 메트릭 집계 가능
- Grafana 대시보드 7개 패널 구성: task type별 비용/호출률, 모델별 누적 비용, P95 워커 지연, complexity 분포
- `llm.call` span에 `task.type` attribute 누락 버그도 함께 수정 → spanmetrics에서 task_type 레이블 생성 가능

---

### [2026-06-18] workers 구현 완료 — dispatcher→worker end-to-end trace 확인

**카테고리**: 성과 측정 + 트러블슈팅

**문제 상황: trace context 끊김**
workers 최초 기동 시 로그에서 trace ID가 dispatcher와 달랐음:
- dispatcher: `6ec8de407fccdc5aa4da5ef243ba8250`
- CodeReviewWorker: `530d98c80018a3ae077d378018a5c541` ← 새 trace 생성됨

**원인 분석**
dispatcher의 `RabbitTemplate` 빈이 커스텀으로 정의되어 있었는데, `observationEnabled=true`를 설정하지 않아 메시지 발행 시 `traceparent` 헤더가 AMQP 메시지에 주입되지 않음. Workers 수신 시 헤더가 없으므로 새 root trace 생성.

**해결 방법**
dispatcher `RabbitMQConfig.amqpTemplate()`에 `template.setObservationEnabled(true)` 추가.
Workers는 `WorkersConfig`에서 `factory.setObservationEnabled(true)` 이미 설정됨.

**결과 (수치)**

Jaeger trace `c586d34aee7baf78c0fff249557475aa` — **하나의 trace에 19개 span 귀속**:

```
http post /tasks [289ms, root]
  └── task.dispatch [159ms]
        ├── send code-review [10ms]  ← Micrometer AMQP publish span
        ├── send security [0.9ms]
        ├── send test-gen [1.5ms]
        ├── receive code-review [317ms]  ← Micrometer AMQP consume span
        │     └── worker.code_review [9ms]
        │           ├── llm.call [iter 1]
        │           └── llm.call [iter 2, finished]
        ├── receive security [314ms]
        │     └── worker.security [6ms]
        │           ├── llm.call [iter 1]
        │           ├── llm.call [iter 2]
        │           └── llm.call [iter 3, finished]
        └── receive test-gen [316ms]
              └── worker.test_gen [13ms]
                    ├── llm.call [iter 1]
                    ├── llm.call [iter 2]
                    └── llm.call [iter 3, finished]
```

- trace propagation 정확도: 3/3 워커 100% 동일 trace ID 공유
- 루프 캡(MAX 5회) 단위 테스트 3개 통과
- Stub LLM 비용 기록: premium/Opus 기준 code_review $0.126, security $0.147, test_gen $0.180

---

### [2026-06-18] dispatcher 구현 완료 — POST /tasks → Jaeger trace 확인

**카테고리**: 성과 측정

**구현 내용**
- `POST /tasks` 엔드포인트: PR 정보 수신 → OTel span 생성 → RabbitMQ publish
- `ComplexityRouter`: diffLines 기반 정적 휴리스틱 (< 200: standard/Haiku, ≥ 200: premium/Opus)
- `TaskService`: `task.dispatch` OTel span 생성 후 모든 worker publish를 그 span 컨텍스트 안에서 실행 → 워커들이 같은 trace에 묶임
- Micrometer Tracing bridge: Spring AMQP 메시지 헤더에 `traceparent` 자동 주입

**결과 (수치)**

Jaeger trace `6ec8de407fccdc5aa4da5ef243ba8250` 확인:
```
http post /tasks  [320ms, root]
  └── task.dispatch  [130ms]
        tags: task.id, task.complexity=premium, llm.model=claude-opus-4-8
```

- `POST /tasks` 응답 HTTP 202 (QUEUED)
- span attribute 전체 기록 확인: `task.id`, `task.complexity`, `llm.model`, `task.type`
- 로그 MDC에 traceId 자동 주입 확인 (`[6ec8de407fccdc5aa4da5ef243ba8250-8759695cd37ee076]`)
- Jaeger UI에서 `taskscope-dispatcher` 서비스 drill-down 가능

---

### [2026-06-18] Gradle 멀티 모듈 빌드 설정 — 3단계 트러블슈팅

**카테고리**: 트러블슈팅

**문제 상황 1: `dependencyManagement {}` Kotlin DSL 컴파일 에러**
루트 `build.gradle.kts`의 `subprojects {}` 블록 안에서 `dependencyManagement {}` DSL을 쓰자 컴파일 실패:
```
e: Unresolved reference: dependencyManagement
```

**원인 분석**
`io.spring.dependency-management` 플러그인이 추가하는 DSL 확장은 런타임에 동적으로 등록된다. Kotlin DSL은 컴파일 타임에 타입을 정적으로 resolve하기 때문에, 루트 스크립트의 `subprojects {}` 블록에서는 이 확장을 인식하지 못함.

**해결 방법**
`io.spring.dependency-management` 플러그인 제거. Gradle 네이티브 `platform()` BOM 방식으로 교체.

---

**문제 상황 2: `opentelemetry-spring-boot-starter:2.5.0` 아티팩트 없음**
```
Could not find io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter:2.5.0
```

**원인 분석**
`opentelemetry-spring-boot-starter`는 alpha 아티팩트 (버전이 `2.5.0-alpha`)로 분리 배포됨. stable `opentelemetry-instrumentation-bom`에 포함되지 않고, alpha BOM에만 포함되어 있음.

**해결 방법**
OTel Spring Boot Starter 대신 Spring Boot 3.x 네이티브 방식으로 전환:
- `io.micrometer:micrometer-tracing-bridge-otel` — HTTP/AMQP span 자동 생성, W3C traceparent 자동 전파
- `io.opentelemetry:opentelemetry-exporter-otlp` — OTel Collector로 span 전송
- Spring Boot BOM이 OTel 버전 통합 관리 → 별도 OTel BOM 불필요

**부가 이점**
Spring AMQP + Micrometer Tracing 조합이 RabbitMQ message 헤더의 traceparent 주입/추출을 자동으로 처리. CLAUDE.md 아키텍처 원칙(trace context 메시지 헤더 전파)이 프레임워크 레벨에서 보장됨.

---

**문제 상황 3: `org.springframework.boot` 플러그인이 `java` 플러그인을 자동 apply하지 않음**
```
Expression 'java' cannot be invoked as a function
Unresolved reference: implementation
```

**원인 분석**
Spring Boot Gradle 플러그인은 `java` 플러그인을 자동으로 apply하지 않음. `java {}` 블록과 `dependencies {}` 내 `implementation` 등은 `java` 플러그인이 있어야 사용 가능.

**해결 방법**
dispatcher, workers의 `plugins {}` 블록에 `java` 명시적 추가.

**결과 (수치)**
`BUILD SUCCESSFUL` — shared, dispatcher, workers 3개 모듈 전체 컴파일 성공 (43s)

---

### [2026-06-18] docker-compose 포트 충돌 해결

**카테고리**: 트러블슈팅

**문제 상황**
`docker compose up -d` 실행 시 otel-collector 컨테이너가 기동 실패:
```
Bind for 0.0.0.0:4317 failed: port is already allocated
```
이후 prometheus(9090), grafana(3000)도 동일하게 충돌.

**원인 분석**
`docker ps -a`로 확인하니 7주 전 구축한 `loggingandmonitoring_by_llm` 프로젝트의 컨테이너들이 계속 실행 중이었음:
- `otel-collector` (v0.99.0): 4317, 4318, 8888, 8889 점유
- `prometheus` (v2.51.0): 9090 점유
- `grafana` (v10.4.2): 3001 점유
- `tempo`: 14317, 14318 점유

**해결 방법**
구 프로젝트 컨테이너를 건드리지 않고 TaskScope용 호스트 포트만 변경:

| 서비스 | 변경 전 | 변경 후 |
|---|---|---|
| OTel Collector gRPC | 4317 | 14320 |
| OTel Collector HTTP | 4318 | 14321 |
| OTel Collector metrics | 8889 | 18889 |
| Prometheus | 9090 | 19090 |
| Grafana | 3000 | 3003 |

컨테이너 내부 포트는 그대로 유지 — Docker 네트워크 내부 통신(otel-collector → jaeger 등)에는 영향 없음.

**결과 (수치)**
5개 컨테이너 모두 정상 기동: RabbitMQ(healthy), Jaeger, OTel Collector, Prometheus, Grafana

---

### [2026-06-18] 기술 스택 선택 및 로컬 인프라 스택 구성

**카테고리**: 설계 결정

**결정 내용**
- 언어/프레임워크: Java 21 + Spring Boot 3.x (Gradle 8.x Kotlin DSL, 멀티 모듈)
- Python FastAPI 대비 초기 보일러플레이트는 많으나, Spring AMQP의 `@RabbitListener` 추상화와 OTel Java Agent 자동 계측이 장기적으로 유리하다고 판단
- 트레이스 백엔드: Jaeger (로컬 셀프호스팅, all-in-one 이미지)
- 메트릭 파이프라인: OTel Collector spanmetrics connector → Prometheus → Grafana

**로컬 스택 구성 (docker-compose)**

| 서비스 | 이미지 | 역할 |
|---|---|---|
| RabbitMQ | 3.13-management | 메시지 큐 + Management UI (15672) |
| OTel Collector | contrib 0.102.0 | span 수집, Jaeger 전달, spanmetrics → Prometheus |
| Jaeger | all-in-one 1.58 | 트레이스 drill-down UI (16686) |
| Prometheus | 2.52.0 | 비용/토큰 집계 메트릭 스크랩 (9090) |
| Grafana | 11.0.0 | 대시보드 (3000) |

**핵심 설계 포인트**
- OTel Collector의 `spanmetrics` connector가 span에서 자동으로 Prometheus 메트릭 생성 — `task.type`, `task.complexity`, `llm.model` 차원으로 집계
- 트레이스(Jaeger)와 메트릭(Prometheus)의 역할 명확히 분리: drill-down은 Jaeger, 집계/대시보드는 Prometheus+Grafana
- 앱 → OTel Collector(4317) → Jaeger(trace) + Prometheus(metrics) 단방향 파이프라인

**결과 (수치)**
수치 미측정 (인프라 구성 단계)