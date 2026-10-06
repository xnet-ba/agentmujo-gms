pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "agentmujo-gms"
include(":core-mesh", ":transport-api", ":service-chat", ":service-files", ":service-web", ":service-loc", ":agent", ":sim", ":transport-ble", ":network-api", ":app-demo")
