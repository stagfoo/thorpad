plugins {
    id("com.android.application")
}

android {
    namespace = "com.thorpad.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.thorpad.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 20
        versionName = "1.0.19"
    }

    signingConfigs {
        // Committed debug key so a build from any machine installs as an update
        // over a build from any other. Without it AGP invents a fresh throwaway
        // key per environment and Android refuses the install.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        // The stick reader runs in another process, so its interface is AIDL.
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Optional, and only for sticks. Shizuku runs a reader of ours as the shell
    // uid, which is in the `input` group — the one way to see an analog stick
    // before Android 14 without holding focus and pausing the game. Buttons
    // never touch this.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    testImplementation("junit:junit:4.13.2")
    // android.jar's org.json is a stub that throws "not mocked" off-device, so
    // the real implementation goes on the test classpath. The app itself still
    // uses the platform's, which is why there is no implementation() here.
    testImplementation("org.json:json:20250107")
}
