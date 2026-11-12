plugins {
	kotlin("jvm") version "2.4.10"
	kotlin("plugin.spring") version "2.4.10"
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
	id("org.sonarqube") version "7.5.0.8588"
	jacoco
}

group = "com.soat.tech.challenge.oficina"
version = "0.0.1"

// Security fixes beyond the Spring Boot 4.1.1 BOM; keep embedded Tomcat modules aligned.
// https://tomcat.apache.org/security-11.html#Fixed_in_Apache_Tomcat_11.0.25
extra["tomcat.version"] = "11.0.25"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(17)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
	implementation("com.nimbusds:nimbus-jose-jwt:9.40")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-amqp")
	implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.0")
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
	implementation("org.jetbrains.kotlin:kotlin-reflect")

	// Persistence (PostgreSQL). The Execution service swaps this block for spring-boot-starter-data-mongodb.
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.flywaydb:flyway-database-postgresql")
	runtimeOnly("org.postgresql:postgresql")

	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.springframework.security:spring-security-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation("io.mockk:mockk:1.13.10")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testImplementation("org.testcontainers:testcontainers-rabbitmq")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict")
	}
}

springBoot {
	// Exposes BuildProperties (version) to GET /api/info.
	buildInfo()
}

tasks.bootRun {
	// Local profile by default (development JWT secret); SPRING_PROFILES_ACTIVE overrides it.
	systemProperty(
		"spring.profiles.active",
		providers.environmentVariable("SPRING_PROFILES_ACTIVE").orElse("local").get(),
	)
}

// Only the Spring Boot entry point is excluded from coverage; keep in sync with sonar.coverage.exclusions.
val coverageExclusions = listOf("**/*ServiceApplication.class", "**/*ServiceApplicationKt.class")

tasks.withType<Test> {
	useJUnitPlatform()
	finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
	dependsOn(tasks.test)
	classDirectories.setFrom(
		sourceSets.main.get().output.classesDirs.asFileTree.matching { exclude(coverageExclusions) },
	)
	reports {
		xml.required.set(true)
		html.required.set(true)
	}
}

tasks.jacocoTestCoverageVerification {
	dependsOn(tasks.test)
	classDirectories.setFrom(
		sourceSets.main.get().output.classesDirs.asFileTree.matching { exclude(coverageExclusions) },
	)
	violationRules {
		rule {
			// Whole service, not per package.
			element = "BUNDLE"
			limit {
				counter = "LINE"
				value = "COVEREDRATIO"
				minimum = "0.80".toBigDecimal()
			}
		}
	}
}

tasks.check {
	dependsOn(tasks.jacocoTestReport, tasks.jacocoTestCoverageVerification)
}

sonar {
	properties {
		property("sonar.projectKey", "DevEgF_soat-oficina-${rootProject.name}")
		property("sonar.organization", "devegf-soat-oficina")
		property("sonar.host.url", "https://sonarcloud.io")
		property(
			"sonar.coverage.jacoco.xmlReportPaths",
			layout.buildDirectory.file("reports/jacoco/test/jacocoTestReport.xml").get().asFile.path,
		)
		property("sonar.coverage.exclusions", "**/*ServiceApplication.kt")
	}
}
