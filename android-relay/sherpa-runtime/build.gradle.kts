import java.security.MessageDigest

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

val sherpaNativeChecksums = mapOf(
    "src/main/jniLibs/arm64-v8a/libonnxruntime.so" to "4d2318b3849abb8862133d3068fc7e807ed8b2671cc6d83657fff2fcb9e1caad",
    "src/main/jniLibs/arm64-v8a/libsherpa-onnx-c-api.so" to "ec09f16e2e5043898d4dd18ec5c316310a39b7ca74ce4db0a33a3b945790e2ab",
    "src/main/jniLibs/arm64-v8a/libsherpa-onnx-cxx-api.so" to "e330684b081ded3d3d694df44abce6de313ccee82e119121d2ea342e5ce0e3d2",
    "src/main/jniLibs/arm64-v8a/libsherpa-onnx-jni.so" to "fc072f201dc1923ee98b594eb61c796b538ef087f7f18d08dcfdf0565167a8bd",
    "src/main/jniLibs/x86_64/libonnxruntime.so" to "2328d9481be5869dd8c4be96eb57b38a0105db17f1fe696a903c02a0988f0bd9",
    "src/main/jniLibs/x86_64/libsherpa-onnx-c-api.so" to "3fea6fe8f81a01e621a885ebcffdb312fe9612e74c6e0352917cfdc7d909e199",
    "src/main/jniLibs/x86_64/libsherpa-onnx-cxx-api.so" to "36aab84e1ffa80ceae741b97b8fc759f194707accf5ed3711e19368b23de8300",
    "src/main/jniLibs/x86_64/libsherpa-onnx-jni.so" to "4070645480f59bbc4990117f3587d48c891947671f5f0d979a771fd8a4c5d73c",
)

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) {
                break
            }
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

val verifySherpaNativeLibs = tasks.register("verifySherpaNativeLibs") {
    doLast {
        sherpaNativeChecksums.forEach { (relativePath, expectedHash) ->
            val file = layout.projectDirectory.file(relativePath).asFile
            if (!file.isFile) {
                throw GradleException("Missing required Sherpa native library: $relativePath")
            }

            val actualHash = sha256(file)
            if (!actualHash.equals(expectedHash, ignoreCase = true)) {
                throw GradleException(
                    "Checksum mismatch for $relativePath. Expected $expectedHash but found $actualHash.",
                )
            }
        }
    }
}

android {
    namespace = "com.openclaw.relay.sherpa"
    compileSdk = 35

    defaultConfig {
        minSdk = 31
        targetSdk = 35

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

tasks.named("preBuild") {
    dependsOn(verifySherpaNativeLibs)
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}