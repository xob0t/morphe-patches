extension {
    name = "extensions/extension.mpe"
}

android {
    namespace = "app.template.extension"
}

// The extension is pure Java and must not bundle the Kotlin runtime: the merged
// .mpe dex would otherwise ship a second copy of kotlin.* (e.g.
// kotlin.annotation.AnnotationTarget) that collides with the host app's Kotlin
// runtime and crashes it with NoSuchFieldError. Strip the Kotlin stdlib so the
// extension dex contains only app.avito.blacklist.* classes.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-common")
}
