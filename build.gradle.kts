plugins {
    kotlin("jvm") version "2.2.0"
    id("com.gradleup.shadow") version "8.3.6"
}

group = "cn.kerong"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaCompile> {
    options.release.set(17)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // HuHoBot 主插件（含 kloping QQ SDK 与 fastjson，均不重定位）——仅编译期
    compileOnly(files("libs/HuHoBot-Penguin_Spigot-1.21.0.jar"))
    compileOnly("io.papermc.paper:paper-api:1.20.4-R0.1-SNAPSHOT")
    implementation(kotlin("stdlib"))
}

tasks.processResources {
    val ver = project.version.toString()
    inputs.property("version", ver)
    filesMatching("plugin.yml") {
        expand("version" to ver)
    }
}

tasks.shadowJar {
    archiveBaseName.set("KERONGPenguin-Render")
    archiveClassifier.set("")
    // Kotlin 标准库重定位：避免与其他捆绑 kotlin 的插件在 Spigot 全局类查找下互相污染
    relocate("kotlin", "cn.kerong.render.libs.kotlin")
    relocate("kotlinx", "cn.kerong.render.libs.kotlinx")
    relocate("org.jetbrains.annotations", "cn.kerong.render.libs.annotations")
    minimize {
        exclude(dependency("org.jetbrains.kotlin:.*:.*"))
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

// 冒烟渲染任务：独立 JVM 运行五类卡片渲染与结构校验（./gradlew smokeRender）
// 复用标准 test 源集（Kotlin 测试编译对 main 开放 internal 可见性）
tasks.register<JavaExec>("smokeRender") {
    group = "verification"
    description = "独立 JVM 渲染五类卡片并做结构校验"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("cn.kerong.render.smoke.SmokeRender")
    workingDir = projectDir
    dependsOn(tasks.named("compileTestKotlin"))
}
