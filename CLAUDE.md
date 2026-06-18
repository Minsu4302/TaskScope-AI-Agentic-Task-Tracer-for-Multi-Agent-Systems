# CLAUDE.md

이 파일은 Claude Code가 이 프로젝트에서 작업할 때 항상 참고해야 하는 고정 컨텍스트입니다.

## 프로젝트 개요

**TaskScope** — AI Agentic Task Tracer for Multi-Agent Systems

멀티 에이전트 시스템(코드리뷰/보안/테스트생성 에이전트)이 PR 단위 작업을 처리할 때, 비용·토큰·LLM 호출 루프를 작업(task) 단위로 추적 가능하게 만드는 분산 트레이싱 시스템.

### 이 프로젝트가 푸는 문제
기존 멀티 에이전트 시스템(하네스)을 운영하면서 Langfuse로 "어떤 에이전트가 비용을 썼는지"는 알 수 있었지만, "어떤 작업에서 비용이 급증했는지, 왜 급증했는지"는 로그를 전부 뒤져야 알 수 있어 사실상 불가능했음. 또한 토큰 절감과 추론 능력, 모델 성능과 비용 사이의 트레이드오프 문제도 함께 겪음.

### 메인 목표
1. PR(작업)을 루트 span으로 하는 분산 트레이스 — 작업 단위 drill-down 가능하게
2. 메시지 큐(RabbitMQ) 기반 비동기 워커 + OTel trace context propagation
3. OTel Collector → Prometheus(집계) + Grafana(대시보드) 파이프라인

### 서브 목표
1. 정적 휴리스틱 기반 사전 복잡도 분류 → 모델 등급 라우팅
2. 워커별 루프 횟수 하드 캡 — 캡 도달 시 강제 종료 + span에 기록

## 아키텍처 원칙

- task(PR) = trace의 루트. 모든 워커 호출은 이 루트 아래 child span으로 귀속되어야 함.
- 모든 LLM 호출 span에는 최소한 다음 attribute를 기록: `task.id`, `task.type`, `llm.model`, `llm.input_tokens`, `llm.output_tokens`, `llm.cost_usd`, `agent.loop_iteration`
- 메시지 큐 메시지 헤더에 trace context(traceparent)를 반드시 실어서 워커 간 전파. 이게 끊기면 트레이스가 끊긴 것으로 간주하고 버그로 취급.
- 비용 집계(Prometheus)와 트레이스 drill-down(Jaeger/Langfuse)은 역할을 분리. Prometheus에 트레이스 트리 전체를 넣으려 하지 말 것.
- 가드레일(루프 캡, 모델 라우팅)은 가시성 시스템이 쌓은 과거 trace 집계 데이터를 입력으로 재사용. 가드레일 작동 자체도 span에 기록해 가시성 시스템에서 보이게 할 것.

## 기술 스택

- **언어/런타임**: Java 21
- **프레임워크**: Spring Boot 3.x
- **빌드**: Gradle 8.x, Kotlin DSL (`build.gradle.kts`) — 멀티 모듈: `shared`, `dispatcher`, `workers`
- **패키지 루트**: `com.taskscope`
- **주요 의존성**:
  - `spring-boot-starter-amqp` — RabbitMQ consumer/producer
  - `spring-boot-starter-web` — dispatcher REST API
  - `spring-boot-starter-actuator` — health/metrics 엔드포인트
  - `opentelemetry-spring-boot-starter` — OTel 자동 계측 기반
  - `opentelemetry-api` — 수동 span 생성 (LLM 호출 계측용)

## 코딩 컨벤션

- 패키지 구조: `com.taskscope.<module>.<layer>` (예: `com.taskscope.dispatcher.service`)
- Span attribute 키는 반드시 `shared` 모듈의 `SpanAttributes.java` 상수로만 참조 — 문자열 하드코딩 금지
- LLM 호출 span은 반드시 `try-with-resources` 또는 `try-finally`로 span.end() 보장
- RabbitMQ 메시지 헤더에서 traceparent 추출 실패 시 예외 던지지 말고 새 trace 생성 + span attribute에 `trace.propagation_broken=true` 기록
- `@RabbitListener` 메서드는 비즈니스 로직 직접 구현 금지 — 반드시 서비스 계층 위임
- 커밋 메시지: 변경 이유가 드러나게 작성 (예: `fix: trace context가 RabbitMQ 헤더에서 누락되는 문제 수정`)
- 모든 신규 모듈에는 최소 단위 테스트 동반 (JUnit 5 + Mockito)
- 환경변수/시크릿은 `.env`로 분리, 절대 커밋하지 않음 (`.env.example`만 커밋)

- 커밋 메시지: 변경 이유가 드러나게 작성 (예: "fix: trace context가 RabbitMQ 헤더에서 누락되는 문제 수정")
- 모든 신규 모듈에는 최소 단위 테스트 동반
- 환경변수/시크릿은 `.env`로 분리, 절대 커밋하지 않음

## 작업 진행 방식

- 한 번에 전체를 구현하지 말고 단계별로 진행: docker-compose 스택 → 디스패처 → 워커 → span 계측 → 집계/대시보드 → 가드레일
- 각 단계 완료 시 사용자에게 확인받고 다음 단계로 진행
- 트러블슈팅(에러, 설계 변경, 막혔던 지점)이 발생하면 **즉시 PROGRESS.md에 기록**. 이 기록은 이력서 작성에 직접 쓰일 자료이므로, 다음 형식을 지킬 것:
  - 문제 상황 (무엇이 왜 안 됐는지, 가능하면 에러 로그/원인 포함)
  - 원인 분석 과정 (어떻게 디버깅했는지)
  - 해결 방법 (무엇을 어떻게 바꿨는지)
  - 결과 (가능하면 수치로: 처리 시간 변화, 비용 변화, 커버리지 변화 등)

## 수치화 원칙

이력서에 쓸 데이터이므로, 다음을 항상 측정 가능한 형태로 남길 것:
- 가드레일 도입 전/후 평균 비용 (task당, 또는 시간당)
- 가드레일 도입 전/후 평균 루프 횟수
- trace context propagation 정확도 (몇 %의 task가 끊김 없이 끝까지 추적되는지)
- 비용 급증 원인 파악까지 걸리는 시간 (도입 전: 로그 전수 검사 추정 시간 / 도입 후: 대시보드 drill-down 시간)