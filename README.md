# TaskScope — AI Agentic Task Tracer

멀티 에이전트 시스템이 PR 단위 작업을 처리할 때 **어떤 작업에서 비용이 급증했는지**를 추적할 수 없던 문제를 해결하는 분산 트레이싱 + 가드레일 시스템.

---

## 배경 — 어떤 문제를 푸는가

기존 멀티 에이전트 시스템(코드리뷰/보안/테스트생성 에이전트)을 운영하면서 Langfuse로 "어떤 에이전트가 비용을 썼는지"는 알 수 있었지만, **"어떤 PR에서 비용이 급증했는지, 왜 급증했는지"** 는 로그를 전부 뒤져야 알 수 있어 사실상 불가능했다.

또한 에이전트 루프가 무제한으로 돌면서 비용이 폭증하는 문제와, 모든 작업에 동일한 고성능 모델을 쓰는 비효율도 존재했다.

**TaskScope**는 이 세 가지 문제를 한 번에 다룬다:

| 문제 | 해결 |
|------|------|
| "어떤 PR이 비쌌나?" 파악 불가 | PR = trace 루트, task 단위 drill-down |
| 에이전트 루프 폭증 | 루프 캡 가드레일 (MAX 5회, span 기록) |
| 고비용 모델 과다 배정 | Prometheus 과거 데이터 기반 모델 자동 강등 |

---

## 아키텍처

```
Client
  │  POST /tasks {repoUrl, prNumber, diffLines, taskTypes}
  ▼
┌─────────────────────────────────────────┐
│           Dispatcher (:8080)            │
│                                         │
│  ComplexityRouter                       │
│  (diffLines 기반 정적 등급 결정)         │
│       +                                 │
│  CostGuardrailService                   │
│  (Prometheus 10분 평균 비용 조회 →      │
│   임계값 초과 시 premium→standard 강등) │
│                                         │
│  task.dispatch span 생성 후 publish     │
└────────────────┬────────────────────────┘
                 │  traceparent 헤더 포함 AMQP 메시지
                 ▼
┌────────────────────────────────────────┐
│              RabbitMQ                  │
│  taskscope.queue.code-review           │
│  taskscope.queue.security              │
│  taskscope.queue.test-gen              │
└──────┬───────────────┬────────────────┬┘
       │               │                │
       ▼               ▼                ▼
 CodeReviewWorker  SecurityWorker  TestGenWorker
  (loop 1~5회)     (loop 1~5회)    (loop 1~5회)
  llm.call span    llm.call span   llm.call span
  × 반복           × 반복           × 반복
       │               │                │
       └───────────────┴────────────────┘
                        │  OTLP
                        ▼
┌───────────────────────────────────────────┐
│            OTel Collector                 │
│                                           │
│  traces  ──────────────► Jaeger (:16686)  │
│  spanmetrics connector                    │
│  + Micrometer OTLP push ► Prometheus      │
│                           (:19090)        │
└───────────────────────────────────────────┘
                        │
                        ▼
              Grafana Dashboard (:3003)
```

### Trace 계층 구조

PR 하나(task)를 루트로, 모든 워커 호출이 단일 trace에 귀속된다:

```
http post /tasks          ← 자동 계측 (HTTP root span)
  └─ task.dispatch        ← 수동 span (PR 단위 루트)
       ├─ AMQP send × 3   ← 자동 계측 (Micrometer)
       ├─ AMQP receive → worker.code_review
       │                    └─ llm.call [iter 1]
       │                    └─ llm.call [iter 2, finished]
       ├─ AMQP receive → worker.security
       │                    └─ llm.call [iter 1~3, finished]
       └─ AMQP receive → worker.test_gen
                            └─ llm.call [iter 1~3, finished]
```

---

## 주요 기능

### 1. Task 단위 분산 트레이싱
- PR(task) = trace 루트. 모든 워커 span이 단일 traceID로 귀속
- Jaeger에서 task.id로 검색 → 전체 비용/루프/지연 drill-down
- `traceparent` W3C 헤더를 RabbitMQ 메시지에 자동 전파 (Micrometer Tracing 브리지)

### 2. 루프 캡 가드레일
- 워커당 최대 5회 루프 하드 캡
- 캡 도달 시 강제 종료 + span에 기록:
  ```
  agent.loop_cap_hit    = true
  guardrail.action      = force_terminate
  guardrail.reason      = max_iterations_5_reached
  ```

### 3. 비용 기반 모델 자동 강등
- Prometheus에 쌓인 과거 10분 비용 데이터로 task type별 평균 비용 계산
- 임계값(기본 $0.05/call) 초과 시 `premium → standard` 자동 강등
- Prometheus 미응답 시 fail-open (기존 등급 유지)
- 강등 시 span에 기록:
  ```
  guardrail.action = model_downgrade
  guardrail.reason = avg_cost_0.0630_exceeds_threshold_0.0500
  ```

### 4. Prometheus + Grafana 메트릭 파이프라인
- `spanmetrics` connector: span → call count, duration histogram 자동 변환
- Micrometer OTLP push: `llm.cost.usd`, `llm.input.tokens`, `llm.output.tokens` 직접 전송
- Grafana 대시보드: task type별 비용/호출률, 모델별 누적 비용, P95 워커 지연, complexity 분포

---

## 검증 결과 (수치)

| 항목 | Before | After |
|------|--------|-------|
| trace propagation 정확도 | 0% (워커마다 새 trace 생성) | **100%** (19 span 단일 trace) |
| Worker 재기동 시 trace context 생존 | 미확인 | **100%** (RabbitMQ 헤더 보존) |
| 비용 급증 원인 파악 시간 | 30분+ (로그 전수 grep) | **3분 이내** (대시보드 drill-down) |
| 가드레일 강등 시 task 비용 | $0.62/task (premium) | **$0.023/task** (standard, ~96% 절감) |
| task당 루프 상한 | 무제한 | **최대 5회** (평균 2.67회/워커) |

> stub LLM 기준 (실제 Claude API 연동 시 동일 구조로 측정 가능)

---

## 기술 스택

| 분류 | 기술 |
|------|------|
| 언어/런타임 | Java 21 |
| 프레임워크 | Spring Boot 3.3 |
| 빌드 | Gradle 8.x (Kotlin DSL), 멀티 모듈 |
| 메시지 큐 | RabbitMQ 3.13 (Spring AMQP) |
| 분산 트레이싱 | OpenTelemetry (Micrometer Tracing 브리지) |
| Trace 백엔드 | Jaeger 1.58 |
| 메트릭 | Micrometer + OTel Collector spanmetrics → Prometheus 2.52 |
| 대시보드 | Grafana 11.0 |
| 컨테이너 | Docker Compose |

---

## 프로젝트 구조

```
taskscope/
├── shared/                         # 공통 상수 및 모델
│   └── SpanAttributes.java         # 모든 span attribute 키 상수
│   └── TaskMessage.java            # AMQP 메시지 레코드
│
├── dispatcher/                     # PR 접수 및 라우팅
│   └── controller/TaskController   # POST /tasks
│   └── service/TaskService         # task.dispatch span + AMQP publish
│   └── service/ComplexityRouter    # diffLines 기반 정적 모델 등급
│   └── service/CostGuardrailService# Prometheus 기반 동적 강등
│
├── workers/                        # 비동기 LLM 워커
│   └── worker/BaseWorker           # 루프 캡, llm.call span, Micrometer 카운터
│   └── worker/CodeReviewWorker     # @RabbitListener
│   └── worker/SecurityWorker
│   └── worker/TestGenWorker
│
└── infra/
    ├── otel-collector-config.yaml  # spanmetrics + OTLP 파이프라인
    ├── prometheus.yml
    └── grafana/provisioning/       # 자동 프로비저닝 datasource + dashboard
```

---

## 실행 방법

### 사전 요구사항
- Java 21+
- Docker Desktop

### 1. 인프라 스택 기동

```bash
docker compose up -d
```

| 서비스 | 주소 |
|--------|------|
| RabbitMQ Management | http://localhost:15672 (taskscope / taskscope) |
| Jaeger UI | http://localhost:16686 |
| Prometheus | http://localhost:19090 |
| Grafana | http://localhost:3003 (admin / admin) |

### 2. 앱 빌드

```bash
./gradlew build
```

### 3. Dispatcher 기동

```bash
java -jar dispatcher/build/libs/dispatcher-0.0.1-SNAPSHOT.jar
```

### 4. Workers 기동

```bash
java -jar workers/build/libs/workers-0.0.1-SNAPSHOT.jar
```

### 5. 작업 전송

```bash
curl -X POST http://localhost:8080/tasks \
  -H "Content-Type: application/json" \
  -d '{
    "repoUrl": "https://github.com/example/repo",
    "prNumber": "42",
    "diffLines": 250,
    "taskTypes": ["code_review", "security", "test_gen"]
  }'
```

`diffLines >= 200` → premium(Opus), `< 200` → standard(Haiku)

### 6. Trace 확인

Jaeger UI → `taskscope-dispatcher` 서비스 선택 → 트레이스 클릭 → task.dispatch 아래 전체 워커 span 확인

### 7. 메트릭 확인

Grafana → Dashboards → TaskScope → LLM Cost & Tracing 대시보드

---

## 환경변수

`.env.example` 복사 후 수정:

```bash
cp .env.example .env
```

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `RABBITMQ_USER` | taskscope | RabbitMQ 사용자 |
| `RABBITMQ_PASS` | taskscope | RabbitMQ 비밀번호 |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | http://localhost:14321 | OTel Collector HTTP 엔드포인트 |
| `PROMETHEUS_URL` | http://localhost:19090 | 가드레일용 Prometheus 주소 |
| `GUARDRAIL_COST_THRESHOLD_USD` | 0.05 | 모델 강등 임계값 ($/call) |

---

## 핵심 설계 결정

**OTel Spring Boot Starter 대신 Micrometer Tracing 브리지 채택**
`opentelemetry-spring-boot-starter`는 alpha 아티팩트로 안정 BOM에 없음. `micrometer-tracing-bridge-otel`은 Spring Boot BOM에 포함되며 Spring AMQP의 traceparent 자동 전파를 지원.

**spanmetrics + Micrometer 이중 파이프라인**
`spanmetrics`는 span 횟수/지연만 변환. `llm.cost_usd` 같은 attribute 값 집계는 Micrometer 카운터로 앱에서 직접 push.

**가드레일 fail-open 원칙**
모니터링 시스템(Prometheus) 장애가 서비스 장애로 전파되지 않도록, 미응답 시 기존 등급 유지.
