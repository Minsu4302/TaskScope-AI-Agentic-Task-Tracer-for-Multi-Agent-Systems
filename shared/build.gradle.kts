plugins {
    `java-library`
}

java { sourceCompatibility = JavaVersion.VERSION_21 }

dependencies {
    // Spring Boot BOM이 opentelemetry-api 버전 관리 (별도 OTel BOM 불필요)
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.3.1"))
    api("io.opentelemetry:opentelemetry-api")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> { useJUnitPlatform() }
