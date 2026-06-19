plugins {
    java
    id("org.springframework.boot")
}

java { sourceCompatibility = JavaVersion.VERSION_21 }

dependencies {
    implementation(project(":shared"))
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.3.1"))
    implementation("org.springframework.boot:spring-boot-starter-amqp")
    // RestClient (GitHub file fetch용) — web-application-type=none으로 HTTP 서버는 비활성화
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Micrometer OTel 브리지: AMQP consumer span 자동 생성 + traceparent 추출
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    // OTLP exporter: OTel Collector로 span 전송
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    // Micrometer OTLP 메트릭 익스포터 — LLM 비용/토큰 메트릭을 OTel Collector로 전송
    implementation("io.micrometer:micrometer-registry-otlp")
    // Anthropic Java SDK — 실제 Claude API 호출
    implementation("com.anthropic:anthropic-java:2.34.0")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> { useJUnitPlatform() }
