import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.pronote.clay"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pronote.clay"
        minSdk = 26
        targetSdk = 35
        versionCode = 27
        versionName = "1.6.14"
    }

    // Publication (Google Play et site) : la release est signée avec la clé
    // d'upload `upload-keystore.jks`, jamais avec la clé de debug que Google
    // Play refuse. Les mots de passe vivent dans `keystore.properties`, un
    // fichier local ignoré par git : sans lui, on retombe sur la clé de debug
    // pour ne pas casser les builds, avec un avertissement explicite.
    val keystorePropertiesFile = file("keystore.properties")
    if (keystorePropertiesFile.exists()) {
        val keystoreProperties = Properties()
        keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
        signingConfigs {
            create("upload") {
                storeFile = file("upload-keystore.jks")
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = if (keystorePropertiesFile.exists()) {
                signingConfigs.getByName("upload")
            } else {
                logger.warn("keystore.properties absent : la release utilisera la clé de debug (refusée par Google Play).")
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // JVM unit tests: android.util.Log calls return defaults instead of throwing.
    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric needs the merged resources to inflate the real theme.
        unitTests.isIncludeAndroidResources = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")

    // Compose 1.8+ : indispensable pour l'autofill sémantique (ContentType),
    // qui laisse le gestionnaire de mots de passe Android remplir le formulaire.
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Keystore-backed encrypted storage for the Pronote credentials.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Periodic background synchronisation.
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Scan du QR code PRONOTE (« Code à flasher ») : l'interface de scan est
    // fournie par Google Code Scanner, sans permission caméra à demander.
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    // Code Scanner tire une version ancienne de `androidx.fragment`, qui
    // redescendait sous 1.3.0 : `registerForActivityResult` devient alors une
    // erreur fatale de lint en release. On fixe donc le plancher exigé par
    // l'API ActivityResult plutôt que de masquer l'avertissement.
    implementation("androidx.fragment:fragment-ktx:1.8.5")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // Real Android runtime on the JVM: launches MainActivity and renders the
    // Compose tree, which is the closest thing to an emulator in this CI.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
