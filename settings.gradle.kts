pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "agentmujo-gms"
include(":core-mesh", ":transport-api", ":service-chat", ":service-files", ":agent", ":sim", ":transport-ble", ":app-demo")
