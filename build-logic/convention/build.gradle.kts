plugins {
    `kotlin-dsl`
}

group = "com.netflixkw.buildlogic"

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.ksp.gradlePlugin)
}

tasks {
    validatePlugins {
        enableStricterValidation = true
        failOnWarning = true
    }
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "netflixkw.android.application"
            implementationClass = "com.netflixkw.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "netflixkw.android.library"
            implementationClass = "com.netflixkw.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("androidHilt") {
            id = "netflixkw.android.hilt"
            implementationClass = "com.netflixkw.buildlogic.AndroidHiltConventionPlugin"
        }
    }
}
