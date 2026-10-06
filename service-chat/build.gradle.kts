plugins { kotlin("jvm") version "2.0.21" }
dependencies { implementation(project(":core-mesh")); testImplementation(kotlin("test")) }
tasks.withType<Test> { useJUnitPlatform() }
