plugins { kotlin("jvm") version "2.0.21" }
dependencies { implementation(project(":core-mesh")); implementation(project(":service-chat")); testImplementation(kotlin("test")) }
tasks.withType<Test> { useJUnitPlatform() }
