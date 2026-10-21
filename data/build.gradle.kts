plugins {
    alias(mihonx.plugins.android.library)
    alias(mihonx.plugins.spotless)

    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

android {
    namespace = "tachiyomi.data"

    sqldelight {
        databases {
            create("Database") {
                packageName.set("tachiyomi.data")
                dialect(libs.sqldelight.sqliteDialect338)
                schemaOutputDirectory.set(project.file("./src/main/sqldelight"))
                generateAsync.set(true)
            }
            create("OcrCacheDatabase") {
                packageName.set("tachiyomi.data.ocr")
                dialect(libs.sqldelight.sqliteDialect338)
                schemaOutputDirectory.set(project.file("./src/main/sqldelight-ocr"))
                srcDirs.setFrom("src/main/sqldelight-ocr")
                generateAsync.set(true)
            }
        }
    }
}

kotlin {
    compilerOptions {
        optIn.add("kotlinx.serialization.ExperimentalSerializationApi")
    }
}

dependencies {
    implementation(projects.sourceApi)
    implementation(projects.domain)
    implementation(projects.core.common)

    implementation(libs.androidx.sqlite.bundled)
    implementation(libs.litert)
    // KOMA-путь кириллицы: ONNX Runtime + PP-OCRv4 det / PP-OCRv5 East Slavic rec.
    // ПОЛНАЯ сборка ONNX Runtime, а не onnxruntime-mobile.
    // Mobile-сборка идёт с урезанным набором операторов (109 штук, без
    // BatchNormalization/HardSwish/Constant): любая модель вне списка либо не
    // открывается, либо роняет процесс нативно, без Java-трейса. На этом уже
    // обожглись дважды, поэтому здесь 17 МБ вместо 3.6 — зато без сюрпризов.
    implementation(libs.onnxruntime.android)
    implementation(libs.mlkit.text.recognition)
    // Страховочный движок кириллицы: Tesseract с русской моделью (в APK).
    implementation(libs.tesseract4android)
    implementation(libs.anki.android)
    implementation(libs.hoshidicts)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.jsonOkio)
    implementation(libs.kotlinx.serialization.protobuf)

    api(libs.bundles.sqldelight)

    testImplementation(libs.bundles.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.core.ktx)
    androidTestImplementation(libs.runner)
}
