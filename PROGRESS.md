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
| task당 평균 비용 | - | - | 가드레일 도입 전/후 |
| task당 평균 루프 횟수 | - | - | |
| trace propagation 정확도 | - | - | 끊김 없이 끝까지 추적된 task 비율 |
| 비용 급증 원인 파악 시간 | (로그 전수 검사 추정) | (대시보드 drill-down) | |
| 가드레일로 차단된 비정상 루프 수 | - | - | 일정 기간 기준 |

---

## 기록 시작

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
- 언어/프레임워크: Java 21 + Spring Boot 3.x (Maven 멀티 모듈)
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