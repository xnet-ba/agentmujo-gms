plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "mujo.app"; compileSdk = 34
    defaultConfig { applicationId = "mujo.app.demo"; minSdk = 26; targetSdk = 34; versionCode = 1; versionName = "0.1" }
    buildTypes { getByName("debug") {} }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":core-mesh"))
    implementation(project(":transport-api"))
    implementation(project(":transport-ble"))
    implementation("androidx.core:core:1.13.1")
}
