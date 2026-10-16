import groovy.json.JsonSlurper
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.openapi.generator")
}
val brandFile = rootProject.file("branding/brand.json")
val brand = JsonSlurper().parse(brandFile) as Map<*, *>
android {
    namespace = "org.spaceworks.app"
    compileSdk = 36
    defaultConfig {
        applicationId = brand["android_application_id"] as String
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resValue("string", "app_name", brand["app_name"] as String)
        buildConfigField("String", "SERVER_URL", "\"${brand["server_url"] ?: ""}\"")
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].java.srcDirs(layout.buildDirectory.dir("generated/source/brand"), layout.buildDirectory.dir("generated/openapi/src/main/kotlin"))
    sourceSets["main"].res.srcDir(layout.buildDirectory.dir("generated/res/brand"))
    testOptions { unitTests.isReturnDefaultValues = true }
    lint { abortOnError = true }
}
val prepareBrand by tasks.registering(Exec::class) {
    inputs.files(brand.filterKeys {it.toString().startsWith("logo_")}.values.map {rootProject.file(it as String)})
    inputs.file(rootProject.file("design/tokens/mobile-motion.json"))
    inputs.files(brandFile, rootProject.file(brand["theme_tokens"] as String), rootProject.file(brand["app_icon"] as String), rootProject.file("scripts/prepare.py"))
    outputs.dirs(layout.buildDirectory.dir("generated/source/brand"),layout.buildDirectory.dir("generated/res/brand"))
    commandLine(if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3", rootProject.file("scripts/prepare.py"))
}
val prepareContract by tasks.registering(Exec::class) {
    inputs.files(rootProject.file("api/openapi.json"),rootProject.file("scripts/contract.py"))
    outputs.file(layout.buildDirectory.file("generated/mobile-openapi.json"))
    commandLine(if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3", rootProject.file("scripts/contract.py"))
}
openApiGenerate {
    generatorName.set("kotlin")
    library.set("jvm-retrofit2")
    inputSpec.set(layout.buildDirectory.file("generated/mobile-openapi.json").get().asFile.path)
    outputDir.set(layout.buildDirectory.dir("generated/openapi").get().asFile.path)
    apiPackage.set("org.spaceworks.generated.api")
    modelPackage.set("org.spaceworks.generated.model")
    configOptions.set(mapOf("serializationLibrary" to "kotlinx_serialization", "generateOneOfAnyOfWrappers" to "true", "useCoroutines" to "true", "dateLibrary" to "java8", "omitGradleWrapper" to "true", "useResponseAsReturnType" to "true"))
    typeMappings.set(mapOf("AnyType" to "JsonElement", "Any" to "JsonElement"))
    importMappings.set(mapOf("JsonElement" to "kotlinx.serialization.json.JsonElement"))
    languageSpecificPrimitives.set(listOf("JsonElement"))
    globalProperties.set(mapOf("apis" to "", "models" to "", "apiTests" to "false", "modelTests" to "false", "apiDocs" to "false", "modelDocs" to "false", "supportingFiles" to "CollectionFormats.kt,UUIDAdapter.kt,OffsetDateTimeAdapter.kt"))
}
tasks.named("openApiGenerate") { dependsOn(prepareContract); doFirst { delete(layout.buildDirectory.dir("generated/openapi")) } }
tasks.named("preBuild") { dependsOn(prepareBrand,"openApiGenerate") }
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach { dependsOn(prepareBrand,"openApiGenerate") }
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.credentials:credentials:1.6.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.6.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.navigation:navigation-compose:2.9.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.android.play:integrity:1.6.0")
    implementation(platform("com.google.firebase:firebase-bom:34.4.0"))
    implementation("com.google.firebase:firebase-messaging")
    implementation("androidx.camera:camera-core:1.5.3")
    implementation("androidx.camera:camera-camera2:1.5.3")
    implementation("androidx.camera:camera-lifecycle:1.5.3")
    implementation("androidx.camera:camera-view:1.5.3")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("com.stripe:stripe-android:22.8.1")
    implementation("com.razorpay:checkout:1.6.41")
    implementation("com.razorpay:standard-core") {version {strictly("1.7.20")}}
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
