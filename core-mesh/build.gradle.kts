plugins { kotlin("jvm") version "2.0.21" }
// bez jvmToolchain: koristi JDK koji pokreće Gradle (21 ovdje); Android moduli kasnije nose svoj toolchain
tasks.withType<Test> { useJUnitPlatform() }
dependencies { testImplementation(kotlin("test")) }
