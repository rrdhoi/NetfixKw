# Prompt for Agent: Setup `build-logic` Module

**Context:**
We are building a new modular Android application. This task focuses on Phase 1: creating the `build-logic` module for centralized Gradle configurations and convention plugins.

**Role:**
Act as a Senior Android Engineer setting up a production-ready convention plugin module.

**Instructions:**
1. Create a `build-logic` folder at the root of the project.
2. Inside `build-logic`, create a `settings.gradle.kts` file that configures the plugin management and includes the `convention` module.
3. Create the `build-logic/convention` module with its own `build.gradle.kts` applying `kotlin-dsl`.
4. Create the following Convention Plugins in `build-logic/convention/src/main/kotlin/id/co/bri/brimons/convention` (adjust package if necessary):

**File: `build-logic/convention/build.gradle.kts`**
```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { `kotlin-dsl` }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies {
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.android.tools.common)
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.kover.gradle.plugin)
    compileOnly(libs.ksp.gradle.plugin)
    compileOnly(libs.room.gradle.plugin)
    compileOnly(libs.hilt.gradle.plugin)
}

tasks {
    validatePlugins {
        enableStricterValidation = true
        failOnWarning = true
    }
}

gradlePlugin {
    plugins.register("androidApplication") {
        id = "brimo.android.application"
        implementationClass =
            "id.co.bri.brimons.convention.plugins.AndroidApplicationConventionPlugin"
    }
    plugins.register("androidApplicationCompose") {
        id = "brimo.android.application.compose"
        implementationClass =
            "id.co.bri.brimons.convention.plugins.AndroidApplicationComposeConventionPlugin"
    }
    plugins.register("androidApplicationDynamicFeatureCompose") {
        id = "brimo.android.dynamic.feature.compose"
        implementationClass =
            "id.co.bri.brimons.convention.plugins.AndroidDynamicFeatureConventionPlugin"
    }
    plugins.register("androidFeatureCompose") {
        id = "brimo.android.feature.compose"
        implementationClass =
            "id.co.bri.brimons.convention.plugins.AndroidFeatureComposeConventionPlugin"
    }
    plugins.register("androidLibrary") {
        id = "brimo.android.library"
        implementationClass = "id.co.bri.brimons.convention.plugins.AndroidLibraryConventionPlugin"
    }
    plugins.register("androidLibraryFlavors") {
        id = "brimo.android.library.flavors"
        implementationClass =
            "id.co.bri.brimons.convention.plugins.AndroidLibraryFlavorsConventionPlugin"
    }
    plugins.register("androidLibraryCompose") {
        id = "brimo.android.library.compose"
        implementationClass =
            "id.co.bri.brimons.convention.plugins.AndroidLibraryComposeConventionPlugin"
    }
    plugins.register("androidRoomDB") {
        id = "brimo.android.room"
        implementationClass = "id.co.bri.brimons.convention.plugins.AndroidRoomConventionPlugin"
    }
    plugins.register("androidLint") {
        id = "brimo.android.lint"
        implementationClass = "id.co.bri.brimons.convention.plugins.AndroidLintConventionPlugin"
    }
    plugins.register("androidTest") {
        id = "brimo.android.test"
        implementationClass = "id.co.bri.brimons.convention.plugins.AndroidTestConventionPlugin"
    }
    plugins.register("androidHilt") {
        id = "brimo.android.hilt"
        implementationClass = "id.co.bri.brimons.convention.plugins.HiltConventionPlugin"
    }
    plugins.register("androidNetwork") {
        id = "brimo.network.retrofit"
        implementationClass = "id.co.bri.brimons.convention.plugins.NetworkConventionPlugin"
    }
    plugins.register("root") {
        id = "brimo.root"
        implementationClass = "id.co.bri.brimons.convention.plugins.RootPlugin"
    }
}

```

**File: `build-logic/convention/src/main/kotlin/id/co/bri/brimons/convention/AndroidApplication.kt`**
```kotlin
package id.co.bri.brimons.convention

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Project

internal fun Project.configureAndroidApplication(extension: ApplicationExtension) {
    extension.apply {
        configureFlavors(extension)

        defaultConfig {
            targetSdk = AndroidBuildConfig.targetSdkVersion

            testOptions { unitTests.all { it.useJUnitPlatform() } }
        }

        buildFeatures { viewBinding = true }

        val isDebugBuild = gradle.startParameter.taskNames.any { it.contains("Debug") }

        splits {
            abi {
                isEnable = isDebugBuild
                reset()
                isUniversalApk = false
                //noinspection ChromeOsAbiSupport
                include("arm64-v8a")
            }
        }

        val keystoreFile = file("${project.rootDir}/devkeytore/dev_key_nbm.keystore")

        signingConfigs {
            getByName("debug") {
                storeFile = keystoreFile
                keyAlias = "nbm_dev"
                keyPassword = "jakarta123"
                storePassword = "jakarta123"
            }
        }

        buildTypes {
            debug {
                isDebuggable = true
                signingConfig = signingConfigs.getByName("debug")
            }

            release {
                isDebuggable = false
                isMinifyEnabled = false
                isShrinkResources = false
                signingConfig = signingConfigs.getByName("debug")
            }
        }

        externalNativeBuild {
            cmake {
                path = file("CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }
}

```

**File: `build-logic/convention/src/main/kotlin/id/co/bri/brimons/convention/AndroidBuildConfig.kt`**
```kotlin
package id.co.bri.brimons.convention

import java.io.FileInputStream
import java.io.InputStreamReader
import java.util.Properties
import org.gradle.api.Project

object AndroidBuildConfig {
    const val compileSdkVersion = 36
    const val minSdkVersion = 24
    const val targetSdkVersion = 36
}

fun Project.getPropertiesByFile(path: String): Properties {
    val properties = Properties()
    val keyPropertiesFile = rootProject.file(path)

    if (keyPropertiesFile.isFile) {
        InputStreamReader(FileInputStream(keyPropertiesFile), Charsets.UTF_8).use { reader ->
            properties.load(reader)
        }
    }
    return properties
}

```

**File: `build-logic/convention/src/main/kotlin/id/co/bri/brimons/convention/AndroidCompose.kt`**
```kotlin
package id.co.bri.brimons.convention

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

internal fun Project.configureAndroidCompose(commonExtension: CommonExtension<*, *, *, *, *, *>) {
    commonExtension.apply { buildFeatures { compose = true } }

    dependencies {
        // Compose BOM
        libs.findLibrary("androidx-compose-bom").ifPresent { bom ->
            implementation(platform(bom))
            androidTestImplementation(platform(bom))
        }

        // Core compose
        implementation(libs.findLibrary("androidx-core-ktx"))
        implementation(libs.findLibrary("androidx-compose-ui"))
        implementation(libs.findLibrary("androidx-compose-ui-graphics"))
        implementation(libs.findLibrary("androidx-compose-ui-tooling-preview"))
        implementation(libs.findLibrary("androidx-material3"))

        // Debug
        debugImplementation(libs.findLibrary("androidx-compose-ui-tooling"))
        debugImplementation(libs.findLibrary("androidx-compose-ui-test-manifest"))

        // Android test
        androidTestImplementation(libs.findLibrary("androidx-compose-ui-test-junit4"))
    }
}

```

**File: `build-logic/convention/src/main/kotlin/id/co/bri/brimons/convention/AndroidLibrary.kt`**
```kotlin
package id.co.bri.brimons.convention

import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.android.build.gradle.LibraryExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

internal fun Project.configureAndroidLibrary(extension: LibraryExtension) = extension.apply {
    configureKotlinAndroidLibrary(this)
    defaultConfig.targetSdk = AndroidBuildConfig.targetSdkVersion
    testOptions {
        animationsDisabled = true
        unitTests.all { it.useJUnitPlatform() }
    }

    // The resource prefix is derived from the module name,
    // so resources inside ":core:module1" must be prefixed with "core_module1_"
    resourcePrefix =
        path
            .split("""\W""".toRegex())
            .drop(1)
            .distinct()
            .joinToString(separator = "_")
            .lowercase() + "_"

    if (path != ":core:testing") {
        dependencies { testImplementation(project(":core:testing")) }
    }
}

internal fun applyMissingApiDimensionStrategy(extension: LibraryExtension) = extension.apply {
    defaultConfig.missingDimensionStrategy(
        FlavorDimension.API.value,
        Flavor.qittaErangel.name,
        Flavor.production.name,
    )
}

internal fun Project.applyAndroidLibraryBasePlugins() {
    with(pluginManager) {
        apply("com.android.library")
        apply("org.jetbrains.kotlin.android")
        apply("brimo.android.lint")
    }
}

internal fun LibraryAndroidComponentsExtension.disableUnnecessaryAndroidTest(project: Project) =
    beforeVariants {
        it.enableAndroidTest =
            it.enableAndroidTest && project.projectDir.resolve("src/androidTest").exists()
    }

```

**File: `build-logic/convention/src/main/kotlin/id/co/bri/brimons/convention/DependencyHandlerExt.kt`**
```kotlin
package id.co.bri.brimons.convention

import java.util.Optional
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.provider.Provider

internal fun DependencyHandler.implementation(dependencyNotation: Any): Dependency? =
    add("implementation", dependencyNotation)

internal fun DependencyHandler.implementation(
    dependency: Optional<Provider<MinimalExternalModuleDependency>>
) {
    dependency.ifPresent { implementation(it) }
}

internal fun DependencyHandler.debugImplementation(dependencyNotation: Any): Dependency? =
    add("debugImplementation", dependencyNotation)

internal fun DependencyHandler.debugImplementation(
    dependency: Optional<Provider<MinimalExternalModuleDependency>>
) {
    dependency.ifPresent { debugImplementation(it) }
}

internal fun DependencyHandler.androidTestImplementation(dependencyNotation: Any): Dependency? =
    add("androidTestImplementation", dependencyNotation)

internal fun DependencyHandler.androidTestImplementation(
    dependency: Optional<Provider<MinimalExternalModuleDependency>>
) {
    dependency.ifPresent { androidTestImplementation(it) }
}

internal fun DependencyHandler.testImplementation(dependencyNotation: Any): Dependency? =
    add("testImplementation", dependencyNotation)

internal fun DependencyHandler.ksp(dependencyNotation: Any): Dependency? =
    add("ksp", dependencyNotation)

```

**File: `build-logic/convention/src/main/kotlin/id/co/bri/brimons/convention/Flavor.kt`**
```kotlin
package id.co.bri.brimons.convention

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.ApplicationProductFlavor
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.LibraryProductFlavor
import com.android.build.api.dsl.ProductFlavor

enum class FlavorDimension(val value: String) {
    API("api")
}

enum class Flavor(
    val dimension: FlavorDimension,
    val applicationIdSuffix: String? = null,
    val isDefault: Boolean = false,
) {
    qittaErangel(FlavorDimension.API, isDefault = true),
    production(FlavorDimension.API),
}

fun configureFlavors(
    commonExtension: CommonExtension<*, *, *, *, *, *>,
    flavorConfigurationBlock: ProductFlavor.(flavor: Flavor) -> Unit = {},
) {
    commonExtension.apply {
        flavorDimensions += FlavorDimension.API.value

        productFlavors {
            Flavor.values().forEach { flavor ->
                create(flavor.name) {
                    dimension = flavor.dimension.value
                    if (flavor.isDefault) {
                        when (this) {
                            is ApplicationProductFlavor -> isDefault = true
                            is LibraryProductFlavor -> isDefault = true
                        }
                    }
                    flavorConfigurationBlock(this, flavor)
                    if (this@apply is ApplicationExtension && this is ApplicationProductFlavor) {
                        flavor.applicationIdSuffix?.let { applicationIdSuffix = it }
                    }
                }
            }
        }
    }
}

```

**File: `build-logic/convention/src/main/kotlin/id/co/bri/brimons/convention/GitHooks.kt`**
```kotlin
package id.co.bri.brimons.convention

import java.io.File
import org.gradle.api.Project

internal fun Project.configureGitHooks() {
    check(this == rootProject) { "configureGitHooks() must be called on the root project." }

    val gitHooksDir = resolveGitHooksDir(rootDir)
    val hooksSource = rootDir.resolve("config/git-hooks")

    val installGitHooks =
        tasks.register("installGitHooks") {
            group = "git hooks"
            description = "Installs git hooks from config/git-hooks into .git/hooks"

            onlyIf { hooksSource.isDirectory && gitHooksDir != null && gitHooksDir.isDirectory }

            inputs.dir(hooksSource)
            outputs.files(
                hooksSource
                    .listFiles()
                    ?.filter { it.isFile }
                    ?.map { gitHooksDir!!.resolve(it.name) } ?: emptyList<File>()
            )

            doLast {
                var installed = 0

                var updated = 0

                hooksSource.listFiles()?.forEach { hook ->
                    if (!hook.isFile) return@forEach

                    val target = gitHooksDir!!.resolve(hook.name)
                    val existed = target.exists()
                    val sameContent = existed && target.readBytes().contentEquals(hook.readBytes())

                    if (sameContent) return@forEach

                    hook.copyTo(target, overwrite = true)
                    target.setExecutable(true, false)

                    if (existed) {
                        updated++
                        logger.lifecycle("Updated git hook: ${hook.name}")
                    } else {
                        installed++
                        logger.lifecycle("Installed git hook: ${hook.name}")
                    }
                }

                if (installed == 0 && updated == 0) {
                    logger.info("Git hooks already up to date.")
                }
            }
        }

    subprojects {
        tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(installGitHooks) }
    }
}

private fun resolveGitHooksDir(projectDir: File): File? {
    val gitEntry =
        generateSequence(projectDir) { it.parentFile }
            .map { it.resolve(".git") }
            .firstOrNull { it.exists() } ?: return null

    // Regular repo: .git is a directory
    if (gitEntry.isDirectory) {
        return gitEntry.resolve("hooks")
    }

    // Submodule / worktree: .git is a file containing "gitdir: <path>"
    if (gitEntry.isFile) {
        val gitdirLine =
            gitEntry.readLines().firstOrNull { it.startsWith("gitdir:") } ?: return null
        val gitdirPath = gitdirLine.removePrefix("gitdir:").trim()
        val resolvedGitDir = gitEntry.parentFile.resolve(gitdirPath).canonicalFile
        return resolvedGitDir.resolve("hooks")
    }

    return null
}

```

**File: `build-logic/convention/src/main/kotlin/id/co/bri/brimons/convention/KotlinAndroid.kt`**
```kotlin
package id.co.bri.brimons.convention

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.provideDelegate
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

/** Configure base Kotlin with Android options */
internal fun Project.configureKotlinAndroid(commonExtension: CommonExtension<*, *, *, *, *, *>) {
    commonExtension.apply {
        compileSdk = AndroidBuildConfig.compileSdkVersion

        defaultConfig {
            minSdk = AndroidBuildConfig.minSdkVersion
            @Suppress("DEPRECATION")
            renderscriptTargetApi = 25
            @Suppress("DEPRECATION")
            renderscriptSupportModeEnabled = true
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            externalNativeBuild {
                cmake {
                    cppFlags("-std=c++11", "-O3", "-s", "-fvisibility=hidden", "-flto", "-fno-rtti")
                }
            }
        }

        packaging {
            resources {
                excludes +=
                    setOf(
                        "META-INF/androidx.localbroadcastmanager_localbroadcastmanager.version",
                        "META-INF/androidx.preference_preference.version",
                    )
            }
            jniLibs {
                pickFirsts +=
                    setOf(
                        "lib/arm64-v8a/libc++_shared.so",
                        "lib/armeabi/libc++_shared.so",
                        "lib/armeabi-v7a/libc++_shared.so",
                        "lib/arm64-v8a/libv8jni.so",
                        "lib/arm64-v8a/libhermes.so",
                        "lib/armeabi-v7a/libhermes.so",
                    )
            }
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }

        configureKotlin()
    }
}

internal fun Project.configureKotlinAndroidLibrary(
    commonExtension: CommonExtension<*, *, *, *, *, *>
) {
    commonExtension.apply {
        compileSdk = AndroidBuildConfig.compileSdkVersion

        defaultConfig {
            minSdk = AndroidBuildConfig.minSdkVersion
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }

        packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }

        configureKotlin()
    }
}

private fun Project.configureKotlin() {
    extensions.configure<KotlinAndroidProjectExtension> {
        jvmToolchain(17)
        compilerOptions {
            val warningsAsErrors: String? by project
            allWarningsAsErrors.set(warningsAsErrors.toBoolean())
            freeCompilerArgs.add("-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi")
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
}

internal fun Project.configureKotlinJvm() {
    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    configureKotlin()
}

```

**File: `build-logic/convention/src/main/kotlin/id/co/bri/brimons/convention/ProjectExt.kt`**
```kotlin
package id.co.bri.brimons.convention

import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

internal val Project.libs
    get(): VersionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.library(alias: String) = findLibrary(alias).get()

internal fun VersionCatalog.bundles(alias: String) = findBundle(alias).get()

```

**File: `build-logic/convention/src/main/kotlin/id/co/bri/brimons/convention/Quality.kt`**
```kotlin
package id.co.bri.brimons.convention

import kotlinx.kover.gradle.plugin.dsl.GroupingEntityType
import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure

internal fun Project.configureKover() {
    apply(plugin = "org.jetbrains.kotlinx.kover")
    configureKoverFilters()
}

internal fun Project.configureKoverFilters() {
    extensions.configure<KoverProjectExtension> {
        reports {
            filters {
                excludes {
                    androidGeneratedClasses()

                    classes(
                        // Hilt & Dagger generated
                        "*.Dagger*",
                        "Hilt_*",
                        "*_HiltModules*",
                        "*_Factory*",
                        "*_MembersInjector*",
                        "*_Provide*Factory",

                        // Compose generated
                        "*ComposableSingletons*",

                        // Android generated
                        "*BuildConfig*",
                        "*Manifest*",

                        // Development fixtures — hardcoded fake data, no logic
                        "*Dummy*",

                        // Pure data holders — domain/UI/network models, request
                        // payloads, navigation params, and Room entities.
                        "*Model*",
                        "*Request*",
                        "*Param*",
                        "*Entity*",

                        // Compose UI screens — not unit-testable, no business logic
                        "*Screen*",

                        // Navigation wiring — declarative route registration, no logic
                        "*NavGraph*",
                        "*NavArg*",
                        "*Arg*",
                        "*NavConfig*",

                        // Sealed interface UI contracts — pure type declarations
                        "*Event*",
                        "*Effect*",

                        // Android UI entry points — not unit-testable without
                        // Robolectric/instrumented tests
                        "*Activity*",
                        "*Fragment*",

                        // DI modules — declarative @Binds/@Provides, no runtime logic
                        "*Module*",

                        // Pure constant holders — only const val declarations
                        "*Constant*",
                        "*Constants*",
                    )

                    // Exclude generated Hilt packages
                    packages("dagger.hilt.internal.aggregatedroot.codegen", "hilt_aggregated_deps")

                    // Exclude by annotation
                    annotatedBy(
                        "*Composable*",
                        "*Preview*",
                        "javax.annotation.processing.Generated",
                        "dagger.internal.DaggerGenerated",
                        "dagger.hilt.android.internal.lifecycle.HiltViewModelMap\$KeySet",
                    )
                }
            }
            verify {
                rule {
                    groupBy.set(GroupingEntityType.PACKAGE)
                    minBound(50)
                }
            }
            total {
                xml {
                    onCheck.set(true)
                    xmlFile.set(layout.buildDirectory.file("reports/kover/report.xml"))
                }
                html {
                    onCheck.set(true)
                    htmlDir.set(layout.buildDirectory.dir("reports/kover/html"))
                }
            }
        }
    }
}

internal fun Project.configureKoverVariant() {
    extensions.configure<KoverProjectExtension> {
        currentProject {
            createVariant("merge") {
                add("debug", optional = true)
                add("productionDebug", optional = true)
            }
        }
        reports {
            variant("merge") {
                xml {
                    onCheck.set(true)
                    xmlFile.set(layout.buildDirectory.file("reports/kover/report.xml"))
                }
                html {
                    onCheck.set(true)
                    htmlDir.set(layout.buildDirectory.dir("reports/kover/html"))
                }
            }
        }
    }
}

```

**File: `build-logic/convention/src/main/kotlin/id/co/bri/brimons/convention/plugins/AndroidApplicationComposeConventionPlugin.kt`**
```kotlin
package id.co.bri.brimons.convention.plugins

import com.android.build.api.dsl.ApplicationExtension
import id.co.bri.brimons.convention.configureAndroidCompose
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.getByType

class AndroidApplicationComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            apply("org.jetbrains.kotlin.android")
            apply(plugin = "org.jetbrains.kotlin.plugin.compose")
            apply(plugin = "com.joetr.compose.guard")

            val extension = extensions.getByType<ApplicationExtension>()

            configureAndroidCompose(extension)
        }
    }
}

```

**File: `build-logic/convention/src/main/kotlin/id/co/bri/brimons/convention/plugins/AndroidApplicationConventionPlugin.kt`**
```kotlin
package id.co.bri.brimons.convention.plugins

import com.android.build.api.dsl.ApplicationExtension
import id.co.bri.brimons.convention.bundles
import id.co.bri.brimons.convention.configureAndroidApplication
import id.co.bri.brimons.convention.configureKotlinAndroid
import id.co.bri.brimons.convention.library
import id.co.bri.brimons.convention.libs
import id.co.bri.brimons.convention.testImplementation
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("com.android.application")
                apply("org.jetbrains.kotlin.android")
                apply("brimo.android.lint")
            }

            extensions.configure<ApplicationExtension> {
                configureKotlinAndroid(this)
                configureAndroidApplication(this)

                compileOptions { isCoreLibraryDesugaringEnabled = true }

                dependencies {
                    "coreLibraryDesugaring"(libs.library("android.desugarJdkLibs"))

                    testImplementation(libs.bundles("kotest"))
                    testImplementation(libs.library("mockk"))
                }
            }
        }
    }
}

```

**File: `build-logic/convention/src/main/kotlin/id/co/bri/brimons/convention/plugins/AndroidDynamicFeatureConventionPlugin.kt`**
```kotlin
package id.co.bri.brimons.convention.plugins

import com.android.build.api.dsl.DynamicFeatureExtension
import id.co.bri.brimons.convention.configureAndroidCompose
import id.co.bri.brimons.convention.configureFlavors
import id.co.bri.brimons.convention.configureKotlinAndroidLibrary
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.getByType

class AndroidDynamicFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            apply(plugin = "com.android.dynamic-feature")
            apply(plugin = "org.jetbrains.kotlin.android")
            apply(plugin = "com.joetr.compose.guard")
            val extension = extensions.getByType<DynamicFeatureExtension>()
            configureKotlinAndroidLibrary(extension)
            configureFlavors(extension)
            configureAndroidCompose(extension)
        }
    }
}

```

**File: `build-logic/convention/src/main/kotlin/id/co/bri/brimons/convention/plugins/AndroidFeatureComposeConventionPlugin.kt`**
```kotlin
package id.co.bri.brimons.convention.plugins

import com.android.build.gradle.LibraryExtension
import id.co.bri.brimons.convention.implementation
import id.co.bri.brimons.convention.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidFeatureComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            apply(plugin = "brimo.android.library.compose")

            extensions.configure<LibraryExtension> {
                dependencies {
                    add("lintChecks", project(":lint"))

                    implementation(project(":core:util"))
                    implementation(project(":core:analytic"))
                    implementation(project(":uikit"))
                    implementation(project(":core:ui"))

                    implementation(libs.findLibrary("androidx-activity-compose"))
                    implementation(libs.findLibrary("androidx-lifecycle-runtime-compose"))
                    implementation(libs.findLibrary("androidx-navigation-compose"))

                    implementation(libs.findLibrary("coil"))
                    implementation(libs.findLibrary("coil-svg"))
                    implementation(libs.findLibrary("coil-compose"))
                    implementation(libs.findLibrary("lottie-compose"))
                    implementation(libs.findLibrary("compose-material-icons"))

                    implementation(libs.findLibrary("bri-designsystem"))

                    // temporary, need remove accompanist permissions
                    implementation(libs.findLibrary("accompanist-permissions"))
                }
            }
        }
    }
}

```

**File: `build-logic/convention/src/main/kotlin/id/co/bri/brimons/convention/plugins/AndroidLibraryComposeConventionPlugin.kt`**
```kotlin
package id.co.bri.brimons.convention.plugins

import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.android.build.gradle.LibraryExtension
import id.co.bri.brimons.convention.applyMissingApiDimensionStrategy
import id.co.bri.brimons.convention.configureAndroidCompose
import id.co.bri.brimons.convention.configureAndroidLibrary
import id.co.bri.brimons.convention.disableUnnecessaryAndroidTest
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidLibraryComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")
            pluginManager.apply("org.jetbrains.kotlin.android")
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
            pluginManager.apply("com.joetr.compose.guard")

            extensions.configure<LibraryExtension> {
                configureAndroidLibrary(this)
                configureAndroidCompose(this)
                applyMissingApiDimensionStrategy(this)
            }

            extensions.configure<LibraryAndroidComponentsExtension> {
                disableUnnecessaryAndroidTest(target)
            }
        }
    }
}

```

**File: `build-logic/convention/src/main/kotlin/id/co/bri/brimons/convention/plugins/AndroidLibraryConventionPlugin.kt`**
```kotlin
package id.co.bri.brimons.convention.plugins

import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.android.build.gradle.LibraryExtension
import id.co.bri.brimons.convention.applyAndroidLibraryBasePlugins
import id.co.bri.brimons.convention.applyMissingApiDimensionStrategy
import id.co.bri.brimons.convention.configureAndroidLibrary
import id.co.bri.brimons.convention.disableUnnecessaryAndroidTest
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            applyAndroidLibraryBasePlugins()

            extensions.configure<LibraryExtension> {
                configureAndroidLibrary(this)
                applyMissingApiDimensionStrategy(this)
            }

            extensions.configure<LibraryAndroidComponentsExtension> {
                disableUnnecessaryAndroidTest(target)
            }
        }
    }
}

```

**File: `build-logic/convention/src/main/kotlin/id/co/bri/brimons/convention/plugins/AndroidLibraryFlavorsConventionPlugin.kt`**
```kotlin
package id.co.bri.brimons.convention.plugins

import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.android.build.gradle.LibraryExtension
import id.co.bri.brimons.convention.applyAndroidLibraryBasePlugins
import id.co.bri.brimons.convention.configureAndroidLibrary
import id.co.bri.brimons.convention.configureFlavors
import id.co.bri.brimons.convention.disableUnnecessaryAndroidTest
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidLibraryFlavorsConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            applyAndroidLibraryBasePlugins()

            extensions.configure<LibraryExtension> {
                configureAndroidLibrary(this)
                configureFlavors(this)

                buildFeatures { buildConfig = true }
            }

            extensions.configure<LibraryAndroidComponentsExtension> {
                disableUnnecessaryAndroidTest(target)
            }
        }
    }
}

```

**File: `build-logic/convention/src/main/kotlin/id/co/bri/brimons/convention/plugins/AndroidLintConventionPlugin.kt`**
```kotlin
package id.co.bri.brimons.convention.plugins

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.dsl.Lint
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidLintConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            when {
                pluginManager.hasPlugin("com.android.application") ->
                    configure<ApplicationExtension> { lint(Lint::configure) }

                pluginManager.hasPlugin("com.android.library") ->
                    configure<LibraryExtension> { lint(Lint::configure) }

                else -> {
                    pluginManager.apply("com.android.lint")
                    configure<Lint>(Lint::configure)
                }
            }
        }
    }
}

private fun Lint.configure() {
    xmlReport = true
    checkDependencies = true
}

```

**File: `build-logic/convention/src/main/kotlin/id/co/bri/brimons/convention/plugins/AndroidRoomConventionPlugin.kt`**
```kotlin
package id.co.bri.brimons.convention.plugins

import androidx.room.gradle.RoomExtension
import id.co.bri.brimons.convention.implementation
import id.co.bri.brimons.convention.ksp
import id.co.bri.brimons.convention.library
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType

class AndroidRoomConventionPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("androidx.room")
                apply("com.google.devtools.ksp")
            }
            val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

            extensions.configure<RoomExtension> {
                // The schemas directory contains a schema file for each version of the Room
                // database.
                // This is required to enable Room auto migrations.
                // See https://developer.android.com/reference/kotlin/androidx/room/AutoMigration.
                schemaDirectory("$projectDir/schemas")
            }

            dependencies {
                implementation(libs.library("androidx-room-runtime"))
                implementation(libs.library("androidx-room-rxjava2"))
                implementation(libs.library("androidx-room-ktx"))
                ksp(libs.library("androidx-room-compiler"))
            }
        }
    }
}

```

**File: `build-logic/convention/src/main/kotlin/id/co/bri/brimons/convention/plugins/AndroidTestConventionPlugin.kt`**
```kotlin
package id.co.bri.brimons.convention.plugins

import com.android.build.gradle.TestExtension
import id.co.bri.brimons.convention.AndroidBuildConfig
import id.co.bri.brimons.convention.configureKotlinAndroidLibrary
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidTestConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("com.android.test")
                apply("org.jetbrains.kotlin.android")
            }

            extensions.configure<TestExtension> {
                configureKotlinAndroidLibrary(this)
                defaultConfig.targetSdk = AndroidBuildConfig.targetSdkVersion
            }
        }
    }
}

```

**File: `build-logic/convention/src/main/kotlin/id/co/bri/brimons/convention/plugins/HiltConventionPlugin.kt`**
```kotlin
package id.co.bri.brimons.convention.plugins

import dagger.hilt.android.plugin.HiltExtension
import id.co.bri.brimons.convention.implementation
import id.co.bri.brimons.convention.ksp
import id.co.bri.brimons.convention.library
import id.co.bri.brimons.convention.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class HiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("com.google.devtools.ksp")
            pluginManager.apply("com.google.dagger.hilt.android")
            extensions.configure<HiltExtension> { enableAggregatingTask = true }
            dependencies {
                implementation(libs.library("hilt-android"))
                implementation(libs.library("hilt-navigation-compose"))
                ksp(libs.library("hilt-compiler"))
            }
        }
}

```

**File: `build-logic/convention/src/main/kotlin/id/co/bri/brimons/convention/plugins/NetworkConventionPlugin.kt`**
```kotlin
package id.co.bri.brimons.convention.plugins

import id.co.bri.brimons.convention.bundles
import id.co.bri.brimons.convention.debugImplementation
import id.co.bri.brimons.convention.implementation
import id.co.bri.brimons.convention.library
import id.co.bri.brimons.convention.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

class NetworkConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            dependencies {
                implementation(libs.bundles("network-bundles"))
                implementation(libs.library("gson"))
                debugImplementation(libs.library("wormaceptor-persistence"))
                implementation(libs.library("wormaceptor-client"))
            }
        }
}

```

**File: `build-logic/convention/src/main/kotlin/id/co/bri/brimons/convention/plugins/RootPlugin.kt`**
```kotlin
package id.co.bri.brimons.convention.plugins

import id.co.bri.brimons.convention.configureGitHooks
import id.co.bri.brimons.convention.configureKover
import id.co.bri.brimons.convention.configureKoverFilters
import id.co.bri.brimons.convention.configureKoverVariant
import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

abstract class RootPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            if (this != rootProject)
                error("RootQualityPlugin must only be applied to the root project.")

            val koverSubprojects = subprojects.filter {
                it.buildFile.exists() && it.path != ":core:testing"
            }

            koverSubprojects.forEach { subproject ->
                subproject.configureKover()
                subproject.configureKoverVariant()
            }

            dependencies {
                koverSubprojects.forEach { subproject -> add("kover", project(subproject.path)) }
            }

            configureKoverFilters()
            configureGitHooks()

            configure<KoverProjectExtension> { currentProject { createVariant("merge") {} } }
        }
    }
}

```

**File: `build-logic/gradle.properties`**
```kotlin
# Gradle properties are not passed to included builds https://github.com/gradle/gradle/issues/2534

org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configureondemand=true
org.gradle.configuration-cache=true
org.gradle.configuration-cache.parallel=true
```

**File: `build-logic/settings.gradle.kts`**
```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }

    versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }
}

rootProject.name = "build-logic"

include(":convention")

```

**Expected Output:**
- An exact replication of this structure. Do not invent new code for these specific files.
