plugins { id("com.android.library"); kotlin("android") }
android {
    namespace = "mujo.net"; compileSdk = 34
    defaultConfig { minSdk = 26 }
    buildFeatures { aidl = true } // AGP 8 ne kompajlira AIDL po defaultu
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":core-mesh"))
    implementation(project(":service-chat"))
}
