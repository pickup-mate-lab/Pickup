plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.pickuppleos"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.pickuppleos"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }
}

// Pleos Vehicle SDK가 필요해지면 공식 가이드의 버전을 확인하고 아래처럼 추가하세요.
// dependencies { implementation("ai.pleos.playground:Vehicle:2.3.1") }
