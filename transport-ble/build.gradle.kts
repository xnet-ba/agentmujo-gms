plugins { id("com.android.library"); kotlin("android") }
android {
    namespace = "mujo.ble"; compileSdk = 34
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":transport-api"))
    implementation("androidx.core:core:1.13.1")
}
