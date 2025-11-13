import com.google.protobuf.gradle.id

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.protobuf)
}

android {
    namespace = "net.secretshield.encryptedprefsapp"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "net.secretshield.encryptedprefsapp"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }

}

// see example: https://github.com/google/protobuf-gradle-plugin/blob/master/testProjectAndroidKotlinDsl/build.gradle.kts
protobuf {
    protoc {
        //"com.google.protobuf:protoc:4.33.0"
        artifact = libs.protoc.compiler.asProvider().get().toString()
    }
    plugins {
        id("javalite") {
            //"com.google.protobuf:protoc-gen-javalite:3.0.0"
            artifact = libs.protoc.compiler.java.lite.get().toString()
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                //create("kotlin")
                id("kotlin") {
                    option("lite")
                }
                id("java") {
                    option("lite")
                }
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.activity.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.google.tink)
    implementation(libs.protoc.compiler)
    implementation(libs.protoc.compiler.java.lite)
    implementation(libs.protoc.java.lite)
    implementation(libs.protoc.kotlin.lite)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
