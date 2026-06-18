plugins {
    java
    id("org.springframework.boot")
}

java { sourceCompatibility = JavaVersion.VERSION_21 }

dependencies {
    implementation(project(":shared"))
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.3.1"))
    implementation("org.springframework.boot:spring-boot-starter-amqp")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Micrometer OTel 브리지: AMQP consumer span 자동 생성 + traceparent 추출
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    // OTLP exporter: OTel Collector로 span 전송
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> { useJUnitPlatform() }
