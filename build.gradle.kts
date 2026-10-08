import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "io.github.innoc99"
version = "1.2.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0") {
        // Kotlin stdlib은 IDE가 제공 — 플러그인에 중복 번들하지 않음
        exclude(group = "org.jetbrains.kotlin")
    }
    implementation("com.google.code.gson:gson:2.10.1")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.1")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.1")

    intellijPlatform {
        // 최소 지원 버전(2024.1)으로 컴파일 — 상위 버전 호환은 verifyPlugin으로 확인
        intellijIdeaCommunity("2024.1.7")
        bundledPlugins("Git4Idea", "org.jetbrains.plugins.github")
        pluginVerifier()
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // 2024.1이 번들하는 Kotlin stdlib(1.9) 기준으로 API 사용 제한
        apiVersion.set(KotlinVersion.KOTLIN_1_9)
        languageVersion.set(KotlinVersion.KOTLIN_1_9)
        // 플랫폼 인터페이스 기본 메서드에 브리지를 만들지 않음 — 만들면 Verifier가 internal/deprecated 메서드 override로 보고
        jvmDefault.set(JvmDefaultMode.NO_COMPATIBILITY)
    }
}

intellijPlatform {
    pluginConfiguration {
        changeNotes = """
            <h3>1.2.0</h3>
            <ul>
              <li>GitHub.com support (api.github.com) in addition to GitHub Enterprise Server</li>
              <li>Re-run all jobs, re-run failed jobs, and cancel runs from the run context menu</li>
              <li>Runs are loaded per selected workflow, so rarely-run workflows are no longer missing</li>
              <li>Job annotations (errors/warnings) shown above the log</li>
              <li>New editor-based log viewer: step and group folding, error highlighting, faster on large logs</li>
              <li>Version tags on runs, last successful run per workflow, and redeploy (rollback) via <code>redeploy_tag</code></li>
              <li>Personal Access Token is now stored in the IDE password safe</li>
              <li>Stability: no polling while the IDE is inactive or the tool window is hidden, no stacked requests on slow networks,
                  proper cleanup on project close, dispatch no longer blocks the UI</li>
            </ul>
        """.trimIndent()

        ideaVersion {
            sinceBuild = "241"
            untilBuild = "262.*"
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    pluginVerification {
        ides {
            // 최소 지원 / 마지막 Community / 최신 통합판 — 전체(recommended)는 다운로드가 10GB 이상
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2024.1.7")
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2025.2.6.3")
            create(IntelliJPlatformType.IntellijIdeaUltimate, "2026.2.3")
        }
    }
}

tasks {
    test {
        useJUnitPlatform()
    }
}
