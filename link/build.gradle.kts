// "Cast this tab" wire format and both ends of the connection (tablet sender, TV receiver), pure Kotlin/JVM so it is
// tested on the JVM end to end (LinkTest). Used by :app (sender) and :tv (receiver).
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { id("org.jetbrains.kotlin.jvm") }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    // 0.5.1 TV updates (RepoIndex.kt): org.json is part of Android; on the JVM (tests) it comes from Maven.
    compileOnly("org.json:json:20240303")
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}
