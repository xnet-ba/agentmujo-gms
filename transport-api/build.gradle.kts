plugins { kotlin("jvm") version "2.0.21" }
tasks.withType<Test> { useJUnitPlatform() }
dependencies { testImplementation(kotlin("test")) }
