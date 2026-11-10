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
        versionName = "0.1.0"
    }
}

dependencies {
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
}
