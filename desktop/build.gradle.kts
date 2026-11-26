plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    application
}
kotlin {
    jvmToolchain(17)
    sourceSets.main { kotlin.srcDir(rootProject.file("shared/src/main/kotlin")) }
}
application { mainClass.set("app.pagereprise.DesktopKt") }
dependencies {
    implementation(project(":core"))
    implementation("org.jetbrains.compose.desktop:desktop-jvm-windows-x64:1.7.3")
    implementation("org.jetbrains.compose.material3:material3-desktop:1.7.3")
    implementation("androidx.room:room-common:2.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")
    implementation("net.java.dev.jna:jna-platform:5.14.0")
    implementation("com.drewnoakes:metadata-extractor:2.19.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.compose.ui:ui-test-junit4-desktop:1.7.3")
}
