plugins {
    id("org.springframework.boot") version "3.5.8"
    id("io.spring.dependency-management") version "1.1.7"
    java
}

group = "com"
version = "0.0.1"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.bootJar {
    archiveBaseName.set("app")
    archiveVersion.set("")
    archiveClassifier.set("")
}

tasks.jar {
    enabled = false
}


if (!System.getenv("NEXUS_PUBLIC_URL").isNullOrBlank()) {
    repositories {
        maven {
            url = uri(System.getenv("NEXUS_PUBLIC_URL"))
        }
        maven {
            url = uri(System.getenv("NEXUS_RELEASE_URL"))
            credentials {
                username = System.getenv("NEXUS_USERNAME")
                password = System.getenv("NEXUS_PASSWORD")
            }
        }
        maven {
            url = uri(System.getenv("NEXUS_SNAPSHOT_URL"))
            credentials {
                username = System.getenv("NEXUS_USERNAME")
                password = System.getenv("NEXUS_PASSWORD")
            }
        }
    }
} else {
    repositories {
        mavenLocal()
        mavenCentral()
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
