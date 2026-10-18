import groovy.json.JsonSlurper
import java.net.URI
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.openapi.generator")
}
if(file("google-services.json").isFile)apply(plugin="com.google.gms.google-services")
val brandFile = rootProject.file("branding/brand.json")
val brand = JsonSlurper().parse(brandFile) as Map<*, *>
android {
    namespace = "org.spaceworks.app"
    compileSdk = 36
    defaultConfig {
        applicationId = brand["android_application_id"] as String
        manifestPlaceholders["deepLinkHost"] = (brand["server_url"] as? String)?.let {URI(it).host} ?: "unconfigured.invalid"
        manifestPlaceholders["deepLinkVerify"] = ((brand["server_url"] as? String)?.isNotBlank()==true).toString()
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resValue("string", "app_name", brand["app_name"] as String)
        buildConfigField("String", "SERVER_URL", "\"${brand["server_url"] ?: ""}\"")
        // Public Play Integrity Cloud project number (owner build input); 0 keeps production attestation unavailable.
        val integrityProject = (findProperty("spaceworks.playIntegrityProject") as String?)?.toLongOrNull()
            ?: (brand["play_integrity_cloud_project_number"] as? Number)?.toLong() ?: 0L
        require(integrityProject >= 0) { "play_integrity_cloud_project_number must be a positive project number or null" }
        buildConfigField("long", "PLAY_INTEGRITY_PROJECT", "${integrityProject}L")
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
    dependsOn("testBrandLinks","testBrandFonts")
    inputs.files(brand.filterKeys {it.toString().startsWith("logo_")}.values.map {rootProject.file(it as String)})
    inputs.file(rootProject.file("tokens/mobile-motion.json"))
    inputs.file(rootProject.file("scripts/brand_links.py"))
    inputs.file(rootProject.file("scripts/brand_fonts.py"))
    inputs.files(brand.filterKeys {it.toString().startsWith("font_")}.values.map {rootProject.file(it as String)})
    inputs.files(brandFile, rootProject.file(brand["theme_tokens"] as String), rootProject.file(brand["app_icon"] as String), rootProject.file("scripts/prepare.py"))
    outputs.dirs(layout.buildDirectory.dir("generated/source/brand"),layout.buildDirectory.dir("generated/res/brand"))
    outputs.file(layout.buildDirectory.file("generated/brand-fonts.json"))
    commandLine(if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3", rootProject.file("scripts/prepare.py"))
}
val testContractSelection by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    inputs.files(rootProject.file("scripts/contract_selection.py"),rootProject.file("scripts/tests/test_contract_selection.py"))
    commandLine(if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3","-m","unittest","discover","-s","scripts/tests","-p","test_contract_selection.py","-v")
}
val prepareContract by tasks.registering(Exec::class) {
    dependsOn(testContractSelection)
    inputs.files(rootProject.file("api/openapi.json"),rootProject.file("scripts/contract.py"),rootProject.file("scripts/contract_selection.py"))
    outputs.file(layout.buildDirectory.file("generated/mobile-openapi.json"))
    commandLine(if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3", rootProject.file("scripts/contract.py"))
}
val nativeTemplateOutput = layout.buildDirectory.dir("generated/native-templates")
val nativeGeneratorJar = providers.provider {
    file(org.openapitools.codegen.DefaultCodegen::class.java.protectionDomain.codeSource.location.toURI())
}
val prepareNativeTemplates by tasks.registering(Exec::class) {
    inputs.file(rootProject.file("scripts/native_templates.py"))
    inputs.file(nativeGeneratorJar)
    outputs.dir(nativeTemplateOutput)
    doFirst {
        commandLine(if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3",
            rootProject.file("scripts/native_templates.py"), "--jar", nativeGeneratorJar.get(), "--output", nativeTemplateOutput.get().asFile)
    }
}
val verifyNativeTemplates by tasks.registering(Exec::class) {
    inputs.files(rootProject.file("scripts/native_templates.py"),rootProject.file("scripts/tests/test_native_templates.py"),nativeGeneratorJar)
    doFirst {commandLine(if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3",
        rootProject.file("scripts/tests/test_native_templates.py"), "--jar", nativeGeneratorJar.get())}
}
val verifyMobileCopy by tasks.registering(Exec::class) {
    inputs.files(rootProject.file("tokens/mobile-copy.json"),rootProject.file("scripts/check_mobile_copy.py"),rootProject.file("scripts/generate_mobile_copy.py"))
    inputs.files(fileTree("src/main/res/values") { include("*strings.xml") })
    inputs.files(fileTree("src/main/java/org/spaceworks/app") { include("**/*Strings.kt") })
    commandLine(if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3",rootProject.file("scripts/check_mobile_copy.py"))
}
val testMobileCopyGenerator by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    inputs.files(rootProject.file("scripts/generate_mobile_copy.py"),rootProject.file("scripts/tests/test_mobile_copy.py"))
    commandLine(if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3","-m","unittest","discover","-s","scripts/tests","-p","test_mobile_copy.py","-v")
}
val testBrandFonts by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    inputs.files(rootProject.file("scripts/brand_fonts.py"),rootProject.file("scripts/tests/test_brand_fonts.py"))
    inputs.files(brand.filterKeys {it.toString().startsWith("font_")}.values.map {rootProject.file(it as String)})
    commandLine(if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3","-m","unittest","discover","-s","scripts/tests","-p","test_brand_fonts.py","-v")
}
val testBrandLinks by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    inputs.files(rootProject.file("scripts/brand_links.py"),rootProject.file("scripts/prepare.py"),rootProject.file("scripts/tests/test_brand_links.py"))
    commandLine(if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3","-m","unittest","discover","-s","scripts/tests","-p","test_brand_links.py","-v")
}
openApiGenerate {
    workerIsolation.set("process")
    templateDir.set(nativeTemplateOutput.get().asFile.path)
    generatorName.set("kotlin")
    library.set("jvm-retrofit2")
    inputSpec.set(layout.buildDirectory.file("generated/mobile-openapi.json").get().asFile.path)
    outputDir.set(layout.buildDirectory.dir("generated/openapi").get().asFile.path)
    apiPackage.set("org.spaceworks.generated.api")
    modelPackage.set("org.spaceworks.generated.model")
    configOptions.set(mapOf("serializationLibrary" to "kotlinx_serialization", "generateOneOfAnyOfWrappers" to "true", "useCoroutines" to "true", "dateLibrary" to "java8", "omitGradleWrapper" to "true", "useResponseAsReturnType" to "true"))
    typeMappings.set(mapOf("AnyType" to "JsonElement", "Any" to "JsonElement", "object" to "JsonElement"))
    importMappings.set(mapOf("JsonElement" to "kotlinx.serialization.json.JsonElement","String" to "kotlin.String"))
    // The owner wire types remain unchanged: URI-or-empty is a String; unused
    // empty-or-structured opening-hours objects stay lossless JSON. This avoids
    // generator7.26 inline-union scalar serializer defects without editing schema.
    schemaMappings.set(mapOf(
        "TenantBootstrapMakerspace_map_url" to "String",
        "SocialLogin_redirect_uri" to "String",
        "ReauthenticationSocialStart_redirect_uri" to "String",
        "WebsiteSection_url" to "String",
        "WebsiteSection_website_url" to "String",
        "TenantBootstrapMakerspace_opening_hours" to "kotlinx.serialization.json.JsonElement",
        "TenantBootstrapMakerspace_opening_hours_oneOf_weekly" to "kotlinx.serialization.json.JsonElement"
    ))
    enumNameMappings.set(mapOf("name" to "nameAscending", "" to "none"))
    languageSpecificPrimitives.set(listOf("JsonElement"))
    globalProperties.set(mapOf("apis" to "", "models" to "", "apiTests" to "false", "modelTests" to "false", "apiDocs" to "false", "modelDocs" to "false", "supportingFiles" to "CollectionFormats.kt,UUIDAdapter.kt,OffsetDateTimeAdapter.kt,URIAdapter.kt,BigDecimalAdapter.kt"))
}
tasks.named("openApiGenerate") { dependsOn(prepareContract,prepareNativeTemplates); inputs.dir(nativeTemplateOutput); doFirst { delete(layout.buildDirectory.dir("generated/openapi")) } }
tasks.named("preBuild") { dependsOn(prepareBrand,"openApiGenerate",verifyNativeTemplates,verifyMobileCopy,testMobileCopyGenerator,testBrandLinks) }
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach { dependsOn(prepareBrand,"openApiGenerate",verifyNativeTemplates,verifyMobileCopy,testMobileCopyGenerator,testBrandLinks) }
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
    implementation("androidx.exifinterface:exifinterface:1.4.2")
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
