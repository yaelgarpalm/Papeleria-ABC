plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.deteccioncaidas"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.deteccioncaidas"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
