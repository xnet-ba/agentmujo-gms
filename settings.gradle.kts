pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "agentmujo-gms"
include(":core-mesh", ":transport-api", ":service-chat", ":agent", ":sim", ":transport-ble", ":app-demo")
