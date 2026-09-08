# Prompt for Agent: Setup `core` Module

**Context:**
We are building a new modular Android application. This task focuses on Phase 1: creating the `core` module which acts as the foundational infrastructure (Network, Database, DI) for the entire app. 

**Role:**
Act as a Senior Android Engineer setting up a highly cohesive and decoupled core module.

**Instructions:**
1. Create a `core` directory at the project root.
2. Implement the `core` module and its submodules exactly as provided below to ensure the new app uses the exact same core standards as the existing monorepo.

**File: `core/analytic/build.gradle.kts`**
```kotlin
plugins { alias(libs.plugins.brimo.android.library) }

android { namespace = "id.co.bri.brimons.core.analytic" }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.insider)
    implementation(libs.appsflyer)
    implementation(libs.install.referrer)
}

```

**File: `core/analytic/src/main/kotlin/id/co/bri/brimons/core/analytic/AnalyticsHub.kt`**
```kotlin
package id.co.bri.brimons.core.analytic

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import com.appsflyer.AppsFlyerConversionListener
import com.appsflyer.AppsFlyerLib
import com.appsflyer.attribution.AppsFlyerRequestListener
import com.useinsider.insider.Insider
import com.useinsider.insider.InsiderCallbackType
import com.useinsider.insider.InsiderIdentifiers
import id.co.bri.brimons.core.analytic.constant.global.GlobalTrackerEventConstants
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object AnalyticsHub {

    private const val LOG_TAG = "AnalyticsHub"
    private const val INSIDER_PARTNER_PROD = "brimoprod"
    private const val INSIDER_PARTNER_UAT = "brimouat"

    internal const val DEFAULT_EVENT_VERSION = GlobalTrackerEventConstants.V1

    @Volatile private var initialized = false
    private val initLock = Any()
    private var isProd = false
    private var insiderEnabled = true
    private lateinit var appContext: Context
    private lateinit var deviceInfoMap: Map<String, Any>

    private val gmt7Formatter = ThreadLocal.withInitial {
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).apply {
            timeZone = TimeZone.getTimeZone("GMT+7")
        }
    }

    private val isoFormatter = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.getDefault())
    }

    // ---------- Lifecycle ----------

    @JvmStatic
    fun init(
        application: Application,
        appsFlyerDevKey: String,
        isProd: Boolean,
        splashScreen: Class<*>,
        insiderEnabled: Boolean,
    ) {
        if (initialized) {
            Log.d(LOG_TAG, "Analytics already initialized")
            return
        }

        synchronized(initLock) {
            if (initialized) return
            try {
                this.isProd = isProd
                this.insiderEnabled = insiderEnabled
                appContext = application.applicationContext

                if (insiderEnabled) initInsider(application, splashScreen)
                initAppsFlyer(application, appsFlyerDevKey)

                deviceInfoMap =
                    mapOf(
                        GlobalTrackerEventConstants.DEVICE_TYPE to "Android",
                        GlobalTrackerEventConstants.MANUFACTURER to Build.MANUFACTURER,
                        GlobalTrackerEventConstants.MODEL to Build.MODEL,
                        GlobalTrackerEventConstants.ANDROID_VERSION to Build.VERSION.RELEASE,
                        GlobalTrackerEventConstants.SDK_LEVEL to Build.VERSION.SDK_INT,
                        GlobalTrackerEventConstants.LOCALE to Locale.getDefault().toString(),
                    )

                initialized = true
                if (!isProd) Log.d(LOG_TAG, "Analytics initialized")
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Init failed", e)
            }
        }
    }

    @JvmStatic fun isInitialized(): Boolean = initialized

    // ---------- User ----------

    @JvmStatic
    @JvmOverloads
    fun setUser(email: String? = null, phoneNumber: String? = null, userId: String? = null) {
        if (!guardReady() || !insiderEnabled) return
        try {
            val identifiers =
                InsiderIdentifiers().apply {
                    email?.let { addEmail(it) }
                    phoneNumber?.let { addPhoneNumber(it) }
                    userId?.let { addUserID(it) }
                }
            Insider.Instance.currentUser.login(identifiers)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "setUser failed", e)
        }
    }

    @JvmStatic
    fun removeUser() {
        if (!guardReady() || !insiderEnabled) return
        runCatching { Insider.Instance.currentUser.logout() }
            .onFailure { Log.e(LOG_TAG, "removeUser failed", it) }
    }

    @JvmStatic
    fun setOnboardingId(onboardingId: String?) {
        if (!guardReady() || !insiderEnabled || onboardingId.isNullOrEmpty()) return
        runCatching {
                Insider.Instance.currentUser.setCustomAttributeWithString(
                    "onboarding_id",
                    onboardingId,
                )
            }
            .onFailure { Log.e(LOG_TAG, "setOnboardingId failed", it) }
    }

    @JvmStatic
    fun insiderId(): String =
        if (insiderEnabled) runCatching { Insider.Instance.insiderID }.getOrDefault("") else ""

    // ---------- Device info / time ----------

    @JvmStatic
    fun deviceInfo(key: String): Any? =
        if (::deviceInfoMap.isInitialized) deviceInfoMap[key] else null

    @JvmStatic fun nowGmt7(): String = gmt7Formatter.get().format(Date())

    @JvmStatic fun nowIso(): String = isoFormatter.get().format(Date())

    // ---------- Tracking ----------

    @JvmStatic
    @JvmOverloads
    fun track(
        eventName: String,
        parameters: Map<String, Any>? = null,
        platform: AnalyticsPlatform = AnalyticsPlatform.BOTH,
        withBaseParams: Boolean = true,
        eventVersion: String = DEFAULT_EVENT_VERSION,
    ) {
        if (!guardReady() || eventName.isEmpty()) return

        val merged: Map<String, Any> = buildMap {
            if (withBaseParams) {
                put(GlobalTrackerEventConstants.TIMESTAMP_WIB, nowGmt7())
                put(GlobalTrackerEventConstants.EVENT_VERSION, eventVersion)
            }
            parameters?.let(::putAll)
        }

        when (platform) {
            AnalyticsPlatform.BOTH -> {
                sendAppsFlyer(eventName, merged)
                sendInsider(eventName, merged)
            }
            AnalyticsPlatform.APPS_FLYER -> sendAppsFlyer(eventName, merged)
            AnalyticsPlatform.INSIDER -> sendInsider(eventName, merged)
        }
    }

    // ---------- Private: SDK glue ----------

    private fun initInsider(app: Application, splash: Class<*>) {
        Insider.Instance.init(app, if (isProd) INSIDER_PARTNER_PROD else INSIDER_PARTNER_UAT)
        Insider.Instance.setSplashActivity(splash)
        Insider.Instance.registerInsiderCallback { data, type ->
            if (isProd) return@registerInsiderCallback
            when (type) {
                InsiderCallbackType.NOTIFICATION_OPEN,
                InsiderCallbackType.INAPP_SEEN,
                InsiderCallbackType.TEMP_STORE_CUSTOM_ACTION,
                InsiderCallbackType.INAPP_BUTTON_CLICK,
                InsiderCallbackType.TEMP_STORE_PURCHASE,
                InsiderCallbackType.TEMP_STORE_ADDED_TO_CART,
                InsiderCallbackType.SESSION_STARTED -> Log.d(LOG_TAG, "[$type]: $data")
                else -> Unit
            }
        }
    }

    private fun initAppsFlyer(app: Application, devKey: String) {
        val listener =
            object : AppsFlyerConversionListener {
                override fun onConversionDataSuccess(map: MutableMap<String, Any>?) = Unit

                override fun onConversionDataFail(s: String?) = Unit

                override fun onAppOpenAttribution(map: MutableMap<String, String>?) = Unit

                override fun onAttributionFailure(s: String?) = Unit
            }
        AppsFlyerLib.getInstance().apply {
            setDebugLog(!isProd)
            init(devKey, listener, appContext)
            start(app)
        }
    }

    private fun sendInsider(eventName: String, params: Map<String, Any>) {
        if (!insiderEnabled) return
        try {
            val event = Insider.Instance.tagEvent(eventName)
            if (params.isNotEmpty()) {
                @Suppress("UNCHECKED_CAST")
                event.addParameters(params.mapValues { it.value as Object } as Map<String, Object>)
            }
            event.build()
            if (!isProd) Log.d(LOG_TAG, "Insider tracked: $eventName")
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Insider track failed: $eventName", e)
        }
    }

    private fun sendAppsFlyer(eventName: String, params: Map<String, Any>) {
        try {
            AppsFlyerLib.getInstance().setCustomerUserId(insiderId())
            AppsFlyerLib.getInstance()
                .logEvent(
                    appContext,
                    eventName,
                    params.takeIf { it.isNotEmpty() },
                    object : AppsFlyerRequestListener {
                        override fun onSuccess() = Unit

                        override fun onError(code: Int, msg: String) = Unit
                    },
                )
            if (!isProd) Log.d(LOG_TAG, "AppsFlyer tracked: $eventName")
        } catch (e: Exception) {
            Log.e(LOG_TAG, "AppsFlyer track failed: $eventName", e)
        }
    }

    private fun guardReady(): Boolean {
        if (!initialized) Log.d(LOG_TAG, "Analytics not initialized")
        return initialized
    }
}

fun String.track(
    parameters: Map<String, Any>? = null,
    platform: AnalyticsPlatform = AnalyticsPlatform.BOTH,
    withBaseParams: Boolean = true,
    eventVersion: String = AnalyticsHub.DEFAULT_EVENT_VERSION,
) = AnalyticsHub.track(this, parameters, platform, withBaseParams, eventVersion)

```

**File: `core/analytic/src/main/kotlin/id/co/bri/brimons/core/analytic/AnalyticsPlatform.kt`**
```kotlin
package id.co.bri.brimons.core.analytic

import androidx.annotation.Keep

@Keep
enum class AnalyticsPlatform {
    BOTH,
    APPS_FLYER,
    INSIDER,
}

```

**File: `core/analytic/src/main/kotlin/id/co/bri/brimons/core/analytic/constant/FeatureTrackerEventConstants.kt`**
```kotlin
package id.co.bri.brimons.core.analytic.constant

/** Created by Renaldi on 03/07/26. */
object FeatureTrackerEventConstants {
    const val FEATURE_CC = "feature_cc"
    const val CC_FEATURE_CLICK = "cc_feature_click"
}

```

**File: `core/analytic/src/main/kotlin/id/co/bri/brimons/core/analytic/constant/bill/EStatementTrackerEventConstants.kt`**
```kotlin
package id.co.bri.brimons.core.analytic.constant.bill

object EStatementTrackerEventConstants {
    const val ESTATEMENT_LIST_PAGE_OPENED = "estatement_list_page"
    const val ESTATEMENT_CREATE_CLICKED = "estatement_button_click_create_estatement"
    const val PDF_COUNT = "pdf_count"
    const val CSV_COUNT = "csv_count"
    const val RIWAYAT_GLOBAL = "riwayat_global"
    const val DETAIL_CARD = "detail_card"
}

```

**File: `core/analytic/src/main/kotlin/id/co/bri/brimons/core/analytic/constant/bill/PdamTrackerEventConstants.kt`**
```kotlin
package id.co.bri.brimons.core.analytic.constant.bill

object PdamTrackerEventConstants {
    const val PDAM_INQUIRY_START = "pdam_inquiry_start"
    const val PDAM_FAVORIT_START = "pdam_favorit_start"
    const val PDAM_RIWAYAT_START = "pdam_riwayat_start"
    const val PDAM_CONFIRM = "pdam_confirm"
    const val PDAM_SUCCESS = "pdam_success"
    const val PDAM_FAVORIT_PIN = "pdam_favorit_pin"
    const val PDAM_FAVORIT_UBAHNAMA = "pdam_favorit_ubahnama"
    const val PDAM_FAVORIT_HAPUS = "pdam_favorit_hapus"
}

```

**File: `core/analytic/src/main/kotlin/id/co/bri/brimons/core/analytic/constant/creditcard/CcBindingTrackerEventConstants.kt`**
```kotlin
package id.co.bri.brimons.core.analytic.constant.creditcard

object CcBindingTrackerEventConstants {
    const val CC_PAY_START = "cc_pay_start"
    const val CC_PAY_CONFIRMATION = "cc_pay_confirmation"
    const val CC_PAY_SUCCESS = "cc_pay_success"
    const val CC_BINDING_START = "cc_binding_start"
    const val CC_BINDING_FORM = "cc_binding_form"
    const val CC_BINDING_SUBMIT = "cc_binding_submit"
    const val CC_BINDING_OTP = "cc_binding_otp"
    const val CC_BINDING_RESEND_OTP = "cc_binding_resend_otp"

    const val CC_PAYMENT_TYPE = "payment_type"
    const val CC_BANK_NAME = "bank_name"
    const val CC_IS_FAVORITE = "is_favorite"
    const val CC_TRIGGER_BUTTON = "trigger_button"
    const val CC_DETAIL_ENTRY = "CC detail"
    const val CC_BUTTON_DETAIL = "detail tagihan"
    const val CC_BUTTON_FEATURE = "feature button"
    const val CC_FEATURE_TOP = "Bayar tagihan"
    const val CC_FEATURE_BOTTOM = "Bayar detail tagihan"
    const val CC_START_RESULT = "start_result"
    const val CC_BINDING_RESULT = "binding_result"
}

```

**File: `core/analytic/src/main/kotlin/id/co/bri/brimons/core/analytic/constant/creditcard/CreditCardTrackerEventConstants.kt`**
```kotlin
package id.co.bri.brimons.core.analytic.constant.creditcard

object CreditCardTrackerEventConstants {
    const val CC_ACTIVATION_START = "cc_activation_start"
    const val CC_ACTIVATION_SNK = "cc_activation_snk"
    const val CC_ACTIVATION_SUBMIT = "cc_activation_submit"
    const val CC_ACTIVATION_OTP = "cc_activation_otp"
    const val CC_ACTIVATION_RESEND_OTP = "cc_activation_resend_otp"
    const val BILL_CC_START = "bill_cc_start"
    const val CC_CHOOSE_BANK = "choose_bank_cc"
    const val CC_PAY_PIN = "cc_pay_pin"
    const val CC_PAY_FORM = "cc_pay_form"
    const val CC_PAY_FORM_OPEN = "cc_pay_form_open"
    const val CC_PAY_FAIL = "cc_pay_fail"

    const val START_RESULT = "start_result"
    const val CHECKBOX_PRIVACY_POLICY = "checkbox_privacy_policy"
    const val VIEW_DURATION = "view_duration"
    const val ACTIVATION_RESULT = "activation_result"
    const val OTP_METHOD = "otp_method"

    const val CC_BILL_PAYMENT = "Bill Payment"
    const val CC_OPEN = "Open"
}

```

**File: `core/analytic/src/main/kotlin/id/co/bri/brimons/core/analytic/constant/dailybanking/AccountManagementTrackerEventConstants.kt`**
```kotlin
package id.co.bri.brimons.core.analytic.constant.dailybanking

object AccountManagementTrackerEventConstants {
    const val UBAH_PIN_START = "ubah_pin_start"
    const val UBAH_PIN_INPUT_OLD = "ubah_pin_input_old"
    const val UBAH_PIN_INPUT_NEW = "ubah_pin_input_new"
    const val UBAH_PIN_SUCCESS = "ubah_pin_success"
    const val UBAH_PASSWORD_START = "ubah_password_start"
    const val UBAH_PASSWORD_INPUT_OLD = "ubah_password_input_old"
    const val UBAH_PASSWORD_INPUT_NEW = "ubah_password_input_new"
    const val UBAH_PASSWORD_SUCCESS = "ubah_password_success"
    const val PENGELOLAAN_AKUN_LOGOUT_START = "pengelolaan_akun_logout_start"
    const val PENGELOLAAN_AKUN_LOGOUT_CONFIRM = "pengelolaan_akun_logout_confirm"
    const val PENGELOLAAN_AKUN_LOGOUT_SUCCESS = "pengelolaan_akun_logout_success"
    const val LOGIN_BIOMETRIK_TOGGLE = "login_biometrik_toggle"
}

```

**File: `core/analytic/src/main/kotlin/id/co/bri/brimons/core/analytic/constant/dailybanking/HomepageTrackerEventConstants.kt`**
```kotlin
package id.co.bri.brimons.core.analytic.constant.dailybanking

object HomepageTrackerEventConstants {
    const val HOMEPAGE_PORTOFOLIO = "homepage_portofolio"
    const val HOMEPAGE_QRIS = "homepage_qris"
    const val HOMEPAGE_RIWAYAT = "homepage_riwayat"
    const val HOMEPAGE_PENGATURAN = "homepage_pengaturan"
}

```

**File: `core/analytic/src/main/kotlin/id/co/bri/brimons/core/analytic/constant/dailybanking/LoginLogoutTrackerEventConstants.kt`**
```kotlin
package id.co.bri.brimons.core.analytic.constant.dailybanking

object LoginLogoutTrackerEventConstants {
    const val LOGIN_PASSWORD_START = "login_password_start"
    const val LOGIN_PASSWORD_INPUT = "login_password_input"
    const val LOGIN_PASSWORD_SUCCESS = "login_password_success"
    const val LOGIN_BIOMETRIK_START = "login_biometrik_start"
    const val LOGIN_BIOMETRIK_INPUT_ANDROID = "login_biometrik_input_android"
    const val LOGIN_BIOMETRIK_INPUT_IOS = "login_biometrik_input_ios"
    const val LOGIN_BIOMETRIK_SUCCESS = "login_biometrik_success"
    const val LOGOUT_BUTTON_BACK = "logout_button_back"
    const val LUPA_PIN_TRANSAKSI_START = "lupa_pin_transaksi_start"
    const val LUPA_PIN_TRANSAKSIBLOKIR_START = "lupa_pin_transaksiblokir_start"
    const val LUPA_PIN_LOGIN_START = "lupa_pin_login_start"
    const val LUPA_PIN_INPUT_PASSWORD = "lupa_pin_input_password"
    const val LUPA_PIN_METODE = "lupa_pin_metode"
    const val LUPA_PIN_OTPHP_SEND = "lupa_pin_otphp_send"
    const val LUPA_PIN_OTPHP_RESEND = "lupa_pin_otphp_resend"
    const val LUPA_PIN_PANDUAN_LIVENESS = "lupa_pin_panduan_liveness"
    const val LUPA_PIN_LIVENESS_TAKE = "lupa_pin_liveness_take"
    const val LUPA_PIN_LIVENESS_RETAKE = "lupa_pin_liveness_retake"
    const val LUPA_PIN_LIVENESS_SUCCESS = "lupa_pin_liveness_success"
    const val LUPA_PIN_INPUT_NEW = "lupa_pin_input_new"
    const val LUPA_PIN_CONFIRM = "lupa_pin_confirm"
    const val LUPA_PIN_SUCCESS = "lupa_pin_success"
    const val LUPA_PASSWORD_LOGIN_START = "lupa_password_login_start"
    const val LUPA_PASSWORD_FORGOTPIN_START = "lupa_password_forgotpin_start"
    const val LUPA_PASSWORD_INPUT_PIN = "lupa_password_input_pin"
    const val LUPA_PASSWORD_METODE = "lupa_password_metode"
    const val LUPA_PASSWORD_OTPHP_SEND = "lupa_password_otphp_send"
    const val LUPA_PASSWORD_OTPHP_RESEND = "lupa_password_otphp_resend"
    const val LUPA_PASSWORD_PANDUAN_LIVENESS = "lupa_password_panduan_liveness"
    const val LUPA_PASSWORD_LIVENESS_TAKE = "lupa_password_liveness_take"
    const val LUPA_PASSWORD_LIVENESS_RETAKE = "lupa_password_liveness_retake"
    const val LUPA_PASSWORD_LIVENESS_SUCCESS = "lupa_password_liveness_success"
    const val LUPA_PASSWORD_INPUT_NEW = "lupa_password_input_new"

    const val LUPA_PASSWORD_INPUT_PASSWORD_NEW = "lupa_password_input_password_new"
    const val LUPA_PASSWORD_SUCCESS = "lupa_password_success"

    // -- login nonce flags --
    const val LOGIN_NONCE_INFO = "login_nonce_info"
    const val LOGIN_NONCE_REDIRECT = "login_nonce_redirect"

    // force update
    const val LOGIN_FORCEUPDATE_INFO = "login_forceupdate_info"
    const val LOGIN_FORCEUPDATE_REDIRECT = "onboarding_forceupdate_redirect"
    const val LOGIN_TYPE = "login_type"
    const val LOGIN_TYPE_BIOMETRIC = "biometrik"
    const val LOGIN_TYPE_PASSWORD = "password"

    object ParamName {
        const val FLOW_TYPE = "flow_type"
        const val APP_SOURCE = "app_source"
    }

    object ParamValue {
        const val FORGOT_PASSWORD = "forgot_password"
        const val FORGOT_PIN = "forgot_pin"
        const val APP_SOURCE = "qita"
    }
}

```

**File: `core/analytic/src/main/kotlin/id/co/bri/brimons/core/analytic/constant/dailybanking/MutasiTrackerEventConstants.kt`**
```kotlin
package id.co.bri.brimons.core.analytic.constant.dailybanking

object MutasiTrackerEventConstants {
    const val MUTASI_OPENED = "mutasi_opened"
    const val MUTASI_REKENING = "mutasi_rekening"
    const val MUTASI_SCROLL = "mutasi_scroll"
    const val MUTASI_FILTER = "mutasi_filter"
    const val AKTIVITAS_OPENED = "aktivitas_opened"
    const val AKTIVITAS_TRANSAKSI = "aktivitas_transaksi"
    const val AKTIVITAS_SCROLL = "aktivitas_scroll"
    const val AKTIVITAS_FILTER = "aktivitas_filter"
}

```

**File: `core/analytic/src/main/kotlin/id/co/bri/brimons/core/analytic/constant/dailybanking/PortfolioTrackerEventConstants.kt`**
```kotlin
package id.co.bri.brimons.core.analytic.constant.dailybanking

object PortfolioTrackerEventConstants {
    const val PORTOFOLIO_REKENING_DETAIL = "portofolio_rekening_detail"
    const val PORTOFOLIO_REKENING_COPY = "portofolio_rekening_copy"
    const val PORTOFOLIO_REKENING_TAMPILKAN_NOMINAL = "portofolio_rekening_tampilkan_nominal"
    const val PORTOFOLIO_INFORMASI_REKENING = "portofolio_informasi_rekening"
    const val PORTOFOLIO_REKENING_DETAIL_TRX = "portofolio_rekening_detail_trx"
    const val PORTOFOLIO_REKENING_SET_PROXY = "portofolio_rekening_set_proxy"
    const val PORTOFOLIO_REKENING_SET_ALIAS = "portofolio_rekening_set_alias"
    const val PORTOFOLIO_REKENING_STATUS_FINANSIAL = "portofolio_rekening_status_finansial"
    const val PORTOFOLIO_REKENING_STATUS_REKENING = "portofolio_rekening_status_rekening"
    const val PORTOFOLIO_REKENING_NOTIFIKASI_TRX = "portofolio_rekening_notifikasi_trx"
}

```

**File: `core/analytic/src/main/kotlin/id/co/bri/brimons/core/analytic/constant/dailybanking/QurbanSavingTrackerEventConstants.kt`**
```kotlin
package id.co.bri.brimons.core.analytic.constant.dailybanking

object QurbanSavingTrackerEventConstants {
    // ENTRY POINT
    const val QURBAN_ENTRY_POINT = "tabungan_qurban_entry_point"

    // TOP UP
    const val TOP_UP_CLICKED = "tabungan_qurban_tambah_dana"
    const val TOP_UP_INPUT_AMOUNT_CONTINUE_CLICKED =
        "tabungan_qurban_tambah_dana_input_dana_click_btn_continue"
    const val TOP_UP_CONFIRMATION_CLICKED = "tabungan_qurban_tambah_dana_click_btn_confirmation"
    const val TOP_UP_PIN_CONFIRMATION = "tabungan_qurban_tambah_dana_pin_confirmation"
    const val TOP_UP_SUCCESS = "tabungan_qurban_tambah_dana_success"
    const val TOP_UP_PENDING = "tabungan_qurban_tambah_dana_pending"
    const val TOP_UP_FAILED = "tabungan_qurban_tambah_dana_failed"

    // WITHDRAWAL
    const val WITHDRAWAL_CLICKED = "tabungan_qurban_cairkan_tabungan"
    const val WITHDRAWAL_CLOSURE_REASON_CONTINUE_CLICKED =
        "tabungan_qurban_cairkan_tabungan_alasan_penutupan_click_btn_continue"
    const val WITHDRAWAL_TNC_CONTINUE_CLICKED =
        "tabungan_qurban_cairkan_tabungan_tnc_click_btn_continue"
    const val WITHDRAWAL_CONFIRMATION_CLICKED =
        "tabungan_qurban_cairkan_tabungan_confirmation_click_btn_cairkan"
    const val WITHDRAWAL_PIN_CONFIRMATION = "tabungan_qurban_cairkan_tabungan_pin_confirmation"
    const val WITHDRAWAL_SUCCESS = "tabungan_qurban_cairkan_tabungan_success"
    const val WITHDRAWAL_PENDING = "tabungan_qurban_cairkan_tabungan_pending"
    const val WITHDRAWAL_FAILED = "tabungan_qurban_cairkan_tabungan_failed"

    // OPEN SAVING
    const val OPEN_SAVING_CLICKED = "tabungan_qurban_info_click_btn_open"
    const val SET_GOALS_CLICKED = "tabungan_qurban_set_goals_click_btn_continue"
    const val QURBAN_SAVING_TNC_CLICKED = "tabungan_qurban_tnc"
    const val QURBAN_SAVING_CONFIRMATION_CLICKED =
        "tabungan_qurban_confirmation_page_click_btn_open"
    const val QURBAN_SAVING_CONFIRMATION_PIN = "tambah_qurban_pin_confirmation"
    const val QURBAN_SAVING_RECEIPT_SUCCESS = "tabungan_qurban_success"

    // CHANGE PLAN
    const val QURBAN_SAVING_CHANGE_PLAN_CLICKED = "tabungan_qurban_ubah_rencana"
    const val QURBAN_SAVING_CHANGE_PLAN_CONTINUE_CLICKED =
        "tabungan_qurban_ubah_rencana_atur_target_dana_click_btn_continue"
    const val QURBAN_SAVING_CHANGE_PLAN_SAVE_CHANGES =
        "tabungan_qurban_ubah_rencana_confirmation_click_btn_save_changes"
    const val QURBAN_SAVING_CHANGE_PLAN_CONFIRMATION =
        "tabungan_qurban_ubah_rencana_pin_confirmation"
    const val QURBAN_SAVING_CHANGE_PLAN_SUCCESS = "tabungan_qurban_ubah_rencana_success"
    const val QURBAN_SAVING_CHANGE_PLAN_FAILED = "tabungan_qurban_ubah_rencana_failed"

    // DASHBOARD
    const val QURBAN_SAVING_HISTORY_CLICKED = "tabungan_qurban_riwayat_trx"
    const val QURBAN_SAVING_CERTIFICATE_DOWNLOAD_CLICKED = "tabungan_qurban_download_sertifikat"

    // PARAMS
    const val QURBAN_TYPE = "qurban_type"
    const val QURBAN_TARGET_YEAR = "qurban_target_year"
    const val ERROR = "error"
    const val CLOSURE_REASON = "closure_reason"
    const val QURBAN_TYPE_NEW = "new_qurban_type"
    const val QURBAN_TARGET_YEAR_NEW = "new_qurban_target_year"
}

```

**File: `core/analytic/src/main/kotlin/id/co/bri/brimons/core/analytic/constant/dailybanking/SavingTrackerEventConstants.kt`**
```kotlin
package id.co.bri.brimons.core.analytic.constant.dailybanking

object SavingTrackerEventConstants {
    const val OPEN_ACCOUNT_CONTINUE = "tambah_tabungan_click_btn_select_account_type"
    const val JUNIO_CREATE = "junio_tabungan_button_create"
    const val JUNIO_CHILD_DATA = "junio_button_data_anak"
    const val JUNIO_DETAIL_DEPOSIT = "junio_button_detail_setoran"
    const val JUNIO_S_AND_K = "junio_button_s&k"
    const val JUNIO_CONFIRMATION = "junio_button_konfirmasi"
    const val JUNIO_SUCCESS = "junio_create_sukses"
    const val INITIAL_DEPOSIT = "tambah_tabungan_setoran_awal_click_btn_continue"
    const val TNC = "tambah_tabungan_setoran_awal_click_btn_continue"
    const val PIN_CONFIRMATION = "tambah_tabungan_pin_confirmation"
    const val PENDING = "tambah_tabungan_pending"
    const val FAILED = "tambah_tabungan_failed"
    const val BRITAMA_SUCCESS = "tambah_tabungan_success"
}

```

**File: `core/analytic/src/main/kotlin/id/co/bri/brimons/core/analytic/constant/dailybanking/StatusFinancialTrackerEventConstants.kt`**
```kotlin
package id.co.bri.brimons.core.analytic.constant.dailybanking

object StatusFinancialTrackerEventConstants {
    const val FA_CLICK = "financial_activation_click"
    const val FA_CONFIRM_BOTTOM =
        "financial_activation_button_click_activate_on_bottomsheet_confirmation"
    const val FA_PIN_CONFIRM = "financial_activation_pin_confirmation"
    const val FA_OTP_METHOD = "financial_activation_otp_method"
    const val FA_OTP_INPUT = "financial_activation_otp_input"
    const val FA_OTP_RESEND = "financial_activation_otp_resend"
    const val FA_CHECKPOINT_CONTINUE =
        "financial_activation_button_click_continue_on_checkpoint_page"
    const val FA_CHECKPOINT_DISCONTINUE =
        "financial_activation_button_click_discontinue_on_checkpoint_page"
    const val FA_KTP_SCAN_START =
        "financial_activation_button_click_start_ktp_scan_on_scan_guidance"
    const val FA_KTP_SCAN_TAKE = "financial_activation_take_ktp_scan"
    const val FA_KTP_OCR_VERIFY = "financial_activation_button_click_verification_ocr_ktp_scan"
    const val FA_LIVENESS_CONTINUE =
        "financial_activation_button_click_continue_on_liveness_guidance"
    const val FA_LIVENESS_TAKE = "financial_activation_take_liveness"
    const val FA_STATUS_UPDATE = "financial_activation_status_update"
    const val FA_LOAD_PAGE_CHECKPOINT = "financial_activation_load_page_checkpoint"

    const val FINANCIAL_STATUS = "financial_status"
    const val COUNTRY_CODE = "country_code"
    const val ERROR_REASON = "error_reason"
    const val DELIVERY_STATUS = "delivery_status"
    const val VERIFICATION_STATUS = "verification_status"
    const val CHANNEL_OTP = "channel_otp"
    const val CHECK_POINT = "checkpoint"
    const val KTP_SCAN_ATTEMPT = "ktp_scan_attempt"
    const val VERIFICATION_ATTEMPT = "verification_attempt"
    const val NIK_EDITED = "nik_edited"
    const val NAME_EDITED = "name_edited"
    const val BIRTH_DATE_EDITED = "birth_date_editted"
    const val PARTNER_NAME = "partner_name"
    const val LIVENESS_ATTEMPT = "liveness_attempt"

    const val ACTIVE = "active"
    const val NON_ACTIVE = "non-active"
    const val ID = "ID"
    const val VERIFIKASI_KTP = "Verifikasi KTP"
    const val VERIFIKASI_WAJAH = "Verifikasi Wajah"
}

```

**File: `core/analytic/src/main/kotlin/id/co/bri/brimons/core/analytic/constant/dailybanking/VirtualCardTrackerEventConstants.kt`**
```kotlin
package id.co.bri.brimons.core.analytic.constant.dailybanking

import id.co.bri.brimons.core.analytic.constant.global.GlobalTrackerEventConstants

object VirtualCardTrackerEventConstants {
    const val VIRTUAL_CARD_DETAIL_OPENED = "debitvirtual_detail_card"
    const val NO_LINKED_CARD_OPENED = "debitvirtual_no_linked_card_page"
    const val CREATE_VIRTUAL_CARD_CLICKED = "debitvirtual_button_click_create_virtual_debit"
    const val CREATE_PHYSICAL_CARD_CLICKED = "debitvirtual_button_click_create_physical_debit"
    const val CREATE_VIRTUAL_CARD_INFO_PRODUCT_CLICKED =
        "debitvirtual_button_click_create_on_info_virtual_debit_page"
    const val SELECT_VIRTUAL_CARD_TYPE_CLICKED = "debitvirtual_button_click_select_card_type_page"
    const val CONTINUE_CARD_LABEL_CLICKED = "debitvirtual_button_click_continue_card_label"
    const val OPEN_VIRTUAL_CARD_CONFIRMATION_CLICKED =
        "debitvirtual_button_click_open_on_confirmation_page"
    const val PIN_CONFIRMATION_VIRTUAL_CARD_CLICKED = "debitvirtual_pin_confirmation"
    const val VIRTUAL_CARD_STATUS = "debitvirtual_status"
    const val CREATE_VIRTUAL_CARD_REPORT_FAILED_CLICKED =
        "debitvirtual_button_click_create_report_on_failed_page"
    const val SELECT_VIRTUAL_CARD_BRANCH = "debitvirtual_physical_card_branch"

    const val CARD_TYPE = "card_type"
    const val CARD_TYPE_VISA = "Visa"

    fun buildStatusParams(type: String): Map<String, Object> {
        return mapOf(
            CARD_TYPE to CARD_TYPE_VISA as Object,
            GlobalTrackerEventConstants.STATUS to type as Object,
        )
    }
}

```

**File: `core/analytic/src/main/kotlin/id/co/bri/brimons/core/analytic/constant/global/GlobalTrackerEventConstants.kt`**
```kotlin
package id.co.bri.brimons.core.analytic.constant.global

object GlobalTrackerEventConstants {

    const val V1 = "v1.0"
    const val LEGACY_V1 = "v.1.0"

    const val TIMESTAMP_WIB = "timestamp_wib"
    const val EVENT_VERSION = "event_version"
    const val CUSTOMER_ID = "customer_id"
    const val USER_TYPE = "user_type"
    const val APP_VERSION = "app_version"
    const val ENTRY_POINT = "entry_point"
    const val STATUS = "status"
    const val SUCCESS_STATUS = "success_status"
    const val FAILED_STATUS = "failed_status"
    const val PROCESS_STATUS = "processed_status"
    const val ERROR_CODE = "error_code"
    const val ERROR_CATEGORY = "error_category"
    const val FAIL_REASON = "fail_reason"
    const val AMOUNT = "amount"
    const val TRANSACTION_ID = "transaction_id"
    const val ATTEMPT_COUNT = "attempt_count"
    const val ACTION_TYPE = "action_type"
    const val IS_EMPTY = "is_empty"
    const val IS_VALID = "is_valid"
    const val IS_FILLED = "is_filled"

    const val SUCCESS = "success"
    const val FAILED = "failed"
    const val SUCCESS_TITLE_CASE = "Success"
    const val FAILED_TITLE_CASE = "Failed"
    const val PROCESS = "process"
    const val TRUE = "true"
    const val FALSE = "false"

    const val DEVICE_TYPE = "device_type"
    const val MANUFACTURER = "manufacturer"
    const val MODEL = "model"
    const val ANDROID_VERSION = "android_version"
    const val SDK_LEVEL = "sdk_level"
    const val LOCALE = "locale"

    const val OTP_METHOD = "otp_method"
}

```

**File: `core/analytic/src/main/kotlin/id/co/bri/brimons/core/analytic/constant/insurance/InsuranceTrackerEventConstants.kt`**
```kotlin
package id.co.bri.brimons.core.analytic.constant.insurance

object InsuranceTrackerEventConstants {
    // Rekomendasi Asuransi
    const val ASURANSI_START_KUESIONER = "asuransi_start_kuesioner"
    const val ASURANSI_KUESIONER_LANJUTKAN = "asuransi_kuesioner_lanjutkan"
    const val ASURANSI_KUESIONER_PERTANYAANPERTAMA = "asuransi_kuesioner_pertanyaanpertama"
    const val ASURANSI_KUESIONER_PERTANYAANKEDUA = "asuransi_kuesioner_pertanyaankedua"
    const val ASURANSI_KUESIONER_PERTANYAAANKETIGA = "asuransi_kuesioner_pertanyaanketiga"
    const val ASURANSI_KUESIONER_PROSESPAGE = "asuransi_kuesioner_prosespage"
    const val ASURANSI_KUESIONER_HASILREKOMENDASI = "asuransi_kuesioner_hasilrekomendasi"
    const val ASURANSI_KUESIONER_LANJUTKANPROSES = "asuransi_kuesioner_lanjutkanproses"
    const val ASURANSI_KUESIONER_KLIKYAMICROSITE = "asuransi_kuesioner_klikyamicrosite"

    // Pengajuan Asuransi
    const val ASURANSI_START_PENGAJUAN = "asuransi_start_pengajuan"
    const val ASURANSI_KONFIRMASI_PENGAJUAN = "asuransi_konfirmasi_pengajuan"
    const val ASURANSI_PENGAJUAN_INPUTPINBAYAR = "asuransi_pengajuan_inputpinbayar"
    const val ASURANSI_PENGAJUAN_BAYARRECEIPTSUCCESS = "asuransi_pengajuan_bayarreceiptsuccess"
    const val ASURANSI_PENGAJUAN_BAYARERROR = "asuransi_pengajuan_bayarerror"

    // Bayar Asuransi
    const val ASURANSI_START_BAYARASURANSI = "asuransi_start_bayarasuransi"
    const val ASURANSI_START_PEMBAYARANBARU = "asuransi_start_pembayaranbaru"
    const val ASURANSI_OPEN_PILIHPENYEDIAASURANSI = "asuransi_open_pilihpenyediaasuransi"
    const val ASURANSI_OPEN_BAYARASURANSI = "asuransi_open_bayarasuransi"
    const val ASURANSI_OPEN_DETAILPEMBAYARAN = "asuransi_open_detailpembayaran"
    const val ASURANSI_OPEN_KONFIRMASIBAYAR = "asuransi_open_konfirmasibayar"
    const val ASURANSI_OPEN_INPUTPINBAYAR = "asuransi_open_inputpinbayar"
    const val ASURANSI_OPEN_BAYARRECEIPTSUCCESS = "asuransi_open_bayarreceiptsuccess"
    const val ASURANSI_OPEN_BAYARERROR = "asuransi_open_bayarerror"

    const val PRODUCT_NAME_BRILIFE_ACCICARE = "brilife_accicare"
    const val PRODUCT_NAME_BRILIFE_LIFECARE = "brilife_lifecare"
    const val PRODUCT_NAME_BRINS_OTO = "brins_oto"
    const val PRODUCT_NAME_BRINS_SEPEDA = "brins_sepeda"
    const val PRODUCT_NAME_BRINS_ASRI = "brins_asri"
    const val PRODUCT_NAME = "product_name"

    object InsuranceParamName {
        const val FIRST_PAYMENT_ASURANSI = "first_payment_asuransi"
        const val SWITCH_FAVORITE = "switch_favorite"
        const val CHOOSE_ASURANSI = "choose_asuranse"
        const val INTEREST_JENIS_ASURANSI_PAGE1 = "interest_jenis_asuransi_page1"
        const val INTEREST_JENIS_ASURANSI_PAGE2 = "interest_jenis_asuransi_page2"
        const val INTEREST_JENIS_ASURANSI_PAGE3 = "interest_jenis_asuransi_page3"
    }
}

```

**File: `core/analytic/src/main/kotlin/id/co/bri/brimons/core/analytic/constant/investment/DPLKTrackerEventConstants.kt`**
```kotlin
package id.co.bri.brimons.core.analytic.constant.investment

object DPLKTrackerEventConstants {
    const val BRIFINE_PROMOTION_OPENACCOUNT = "brifine_promotion_openaccount"
    const val BRIFINE_DETAILPRODUCT_OPENACCOUNT = "brifine_detailproduct_openaccount"
    const val BRIFINE_SNK_OPENACCOUNT = "brifine_snk_openaccount"
    const val BRIFINE_DATADIRI_OPENACCOUNT = "brifine_datadiri_openaccount"
    const val BRIFINE_DATADIRI_UBAH_OPENACCOUNT = "brifine_datadiri_ubah_openaccount"
    const val BRIFINE_PROFESIONAL_OPENACCOUNT = "brifine_profesional_openaccount"
    const val BRIFINE_DATAIURAN_OPENACCOUNT = "brifine_dataiuran_openaccount"
    const val BRIFINE_KONFIRMASI_OPENACCOUNT = "brifine_konfirmasi_openaccount"
    const val BRIFINE_PIN_OPENACCOUNT = "brifine_pin_openaccount"
    const val BRIFINE_START_TOPUP = "brifine_start_topup"
    const val BRIFINE_FORM_TOPUP = "brifine_form_topup"
    const val BRIFINE_KONFIRMASI_TOPUP = "brifine_konfirmasi_topup"
    const val BRIFINE_PIN_TOPUP = "brifine_pin_topup"
    const val BRIFINE_PORTOFOLIO_ADDBRIFINE = "brifine_portofolio_addbrifine"
    const val BRIFINE_DETAILPRODUCT_ADDBRIFINE = "brifine_detailproduct_addbrifine"
    const val BRIFINE_SNK_ADDBRIFINE = "brifine_snk_addbrifine"
    const val BRIFINE_DATADIRI_ADDBRIFINE = "brifine_datadiri_addbrifine"
    const val BRIFINE_DATADIRI_UBAH_ADDBRIFINE = "brifine_datadiri_ubah_addbrifine"
    const val BRIFINE_PROFESIONAL_ADDBRIFINE = "brifine_profesional_addbrifine"
    const val BRIFINE_DATAIURAN_ADDBRIFINE = "brifine_dataiuran_addbrifine"
    const val BRIFINE_KONFIRMASI_ADDBRIFINE = "brifine_konfirmasi_addbrifine"
    const val BRIFINE_PIN_ADDBRIFINE = "brifine_pin_addbrifine"
    const val BRIFINE_LIHATRECEIPT_ADDBRIFINE = "brifine_lihatreceipt_addbrifine"
    const val BRIFINE_SELESAI_OPENACCOUNT = "brifine_selesai_openaccount"
    const val BRIFINE_SELESAI_ADDBRIFINE = "brifine_selesai_addbrifine"
}

```

**File: `core/analytic/src/main/kotlin/id/co/bri/brimons/core/analytic/constant/investment/DepositTrackerEventConstants.kt`**
```kotlin
package id.co.bri.brimons.core.analytic.constant.investment

object DepositTrackerEventConstants {
    const val DEPOSITO_BENEFIT_OPENACCOUNT = "deposito_benefit_openaccount"
    const val DEPOSITO_TNC_OPENACCOUNT = "deposito_tnc_openaccount"
    const val DEPOSITO_SIMULATION = "deposito_simulation"
    const val DEPOSITO_SELECTRENEWAL = "deposito_selectrenewal"
    const val DEPOSITO_CONFIRM = "deposito_confirm"
    const val DEPOSITO_SUCCESS = "deposito_success"
    const val DEPOSITO_PORTFOLIO_ADDACCOUNT = "deposito_portfolio_addaccount"

    const val NEW_USER_DEPOSITO = "new_user_deposito"
}

```

**File: `core/analytic/src/main/kotlin/id/co/bri/brimons/core/analytic/constant/investment/GoldTrackerEventConstant.kt`**
```kotlin
package id.co.bri.brimons.core.analytic.constant.investment

object GoldTrackerEventConstant {
    const val EMAS_START_PROMOTION = "emas_start_promotion_openaccount"
    const val EMAS_KONFIRMASI_DATA_DIRI = "emas_konfirmasidatadiri_openaccount"
    const val EMAS_FORM_SETORAN = "emas_formsetoran_openaccount"
    const val EMAS_KONFIRMASI_PEMBAYARAN = "emas_konfirmasipembayaran_openaccount"
    const val EMAS_INPUT_PIN = "emas_input_pin_openaccount"
    const val EMAS_SUCCESS_PAGE = "emas_success_page_openaccount"

    const val BELI_EMAS_FORM = "emas_beli_form"
    const val BELI_EMAS_KONFIRMASI = "emas_beli_konfirmasi"
    const val BELI_EMAS_PIN = "emas_beli_pin"
    const val BELI_EMAS_SUCCESS_RECEIPT = "emas_beli_success_receipt"

    const val JUAL_EMAS_FORM = "emas_jual_form"
    const val JUAL_EMAS_KONFIRMASI = "emas_jual_konfirmasi"
    const val JUAL_EMAS_PIN = "emas_jual_pin"
    const val SUCCESS_RECEIPT = "emas_jual_success_receipt"

    const val START = "cicilemas_start"
    const val SNK = "cicilemas_snk"
    const val SELF_DATA = "cicilemas_data_diri"
    const val SELF_DATA_FAIL = "cicilemas_data_diri_fail"
    const val CHOOSE_GOLD = "cicilemas_pilih_emas"
    const val CALCULATE_TENOR = "cicilemas_hitung_cicilan"
    const val CHOOSE_TENOR = "cicilemas_ajukan_cicilan"
    const val CONFIRMATION = "cicilemas_konfirmasi"
    const val AKAD = "cicilemas_akad"
    const val PIN = "cicilemas_pin"
    const val START_PAYMENT = "bayar_cicilemas_start"
    const val PAYMENT_CONFIRMATION = "bayar_cicilemas_konfirmasi"

    const val PAWN_GOLD_ENTRY = "gte_entry"
    const val PAWN_GOLD_START = "gte_start"
    const val PAWN_GOLD_SNK = "gte_snk"
    const val PAWN_GOLD_SELF_DATA = "gte_data_diri"
    const val PAWN_GOLD_SELF_DATA_FAIL = "gte_data_diri_fail"
    const val PAWN_GOLD_COUNT_SCHEMA = "gte_hitung_pinjaman"
    const val PAWN_GOLD_APPLY_PAWN = "gte_ajukan_gadai"
    const val PAWN_GOLD_CONFIRMATION = "gte_konfirmasi"
    const val PAWN_GOLD_PIN = "gte_pin"
    const val PAWN_GOLD_PAYMENT_START = "bayar_gte_start"
    const val PAWN_GOLD_PAYMENT_CONFIRMATION = "bayar_gte_konfirmasi"

    const val TOP_UP_DAFTAR_TRANSAKSI = "emas_belilain_daftartransaksi"
    const val TOP_UP_FORM = "emas_belilain_form"
    const val TOP_UP_KONFIRMASI = "emas_belilain_konfirmasi"
    const val TOP_UP_PIN = "emas_belilain_pin"
    const val TOP_UP_SUCCESS_RECEIPT = "emas_belilain_success_receipt"

    object ParamName {
        const val BELI_EMAS_TRIGGER_SOURCE = "trigger_source_beli"
        const val JUAL_TRIGGER_SOURCE = "trigger_source_jual"
        const val TYPE_ACCESS = "type_access"
        const val TYPE_PENGAJUAN = "type_pengajuan"
    }

    object ParamNameValue {
        const val FTU = "FTU"
        const val NON_FTU = "non FTU"
        const val DASHBOARD = "dashboard"
        const val DAFTAR_CICILAN = "daftar_cicilan"
        const val DETAIL = "detail"
        const val DAFTAR_GADAI = "daftar_gadai"
        const val PENGAJUAN = "pengajuan"
        const val SIMULASI = "simulasi"
    }
}

```

**File: `core/analytic/src/main/kotlin/id/co/bri/brimons/core/analytic/constant/investment/RdnTrackerEventConstants.kt`**
```kotlin
package id.co.bri.brimons.core.analytic.constant.investment

object RdnTrackerEventConstants {
    const val RDN_DASHBOARD_OPENACCOUNT = "rdn_dashboard_openaccount"
    const val RDN_DASHBOARD_TYPEOFRDN = "rdn_dashboard_typeofrdn"
    const val RDN_DASHBOARD_TOP_UP = "rdn_dashboard_topup"
    const val RDN_CARD_TOP_UP = "rdn_card_topup"
    const val RDN_DETAIL_TOP_UP = "rdn_detail_topup"
    const val RDN_TRANSACTION_TOP_UP = "rdn_transaction_topup"
    const val RDN_CONFIRMATION_TOP_UP = "rdn_confirmation_topup"
    const val RDN_SUCCESS_TOP_UP = "rdn_success_topup"
    const val RDN_BENEFIT_OPENACCOUNT = "rdn_benefit_openaccount"
    const val RDN_TNC_OPENACCOUNT = "rdn_tnc_openaccount"
    const val RDN_DATACHECK_OPENACCOUNT = "rdn_datacheck_openaccount"
    const val RDN_DATACHECKCONFIRM_OPENACCOUNT = "rdn_datacheckconfirm_openaccount"
    const val RDN_DATACHECKUPDATE_OPENACCOUNT = "rdn_datacheckupdate_openaccount"
    const val RDN_WORKDATA_OPENACCOUNT = "rdn_workdata_openaccount"
    const val RDN_RISKPROFILE_OPENACCOUNT = "rdn_riskprofile_openaccount"
    const val RDN_OTHERINFO_OPENACCOUNT = "rdn_otherinfo_openaccount"
    const val RDN_CONFIRM_OPENACCOUNT = "rdn_confirm_openaccount"
    const val RDN_SUCCESS_OPENACCOUNT = "rdn_success_openaccount"

    object ParamName {
        const val TYPE_OF_RDN = "type_of_rdn"
        const val DATA_COMPLETE = "data_complete"
    }
}

```

**File: `core/analytic/src/main/kotlin/id/co/bri/brimons/core/analytic/constant/investment/SBNTrackerEventConstants.kt`**
```kotlin
package id.co.bri.brimons.core.analytic.constant.investment

object SBNTrackerEventConstants {
    const val SBN_BENEFIT_REGISTERSBN = "sbn_benefit_registersbn"
    const val SBN_PRODUCTDETAIL_REGISTERSBN = "sbn_productdetail_registersbn"
    const val SBN_SIMULATION_REGISTERSBN = "sbn_simulation_registersbn"
    const val SBN_TNC_REGISTERSBN = "sbn_tnc_registersbn"
    const val SBN_CUSTOMERDATA_REGISTERSBN = "sbn_customerdata_registersbn"
    const val SBN_PAYOUTACCOUNT_REGISTERSBN = "sbn_payoutaccount_registersbn"
    const val SBN_CONFIRMATION_REGISTERSBN = "sbn_confirmation_registersbn"
    const val SBN_SUBMITTED_REGISTERSBN = "sbn_submitted_registersbn"
    const val SBN_START_ADDSBN = "sbn_start_addsbn"
    const val SBN_PRODUCTLIST_ADDSBN = "sbn_productlist_addsbn"
    const val SBN_PRODUCTDETAIL_ADDSBN = "sbn_productdetail_addsbn"
    const val SBN_SIMULATION_ADDSBN = "sbn_simulation_addsbn"
    const val SBN_BUYSBN_ADDSBN = "sbn_buysbn_addsbn"
    const val SBN_TNC_ADDSBN = "sbn_tnc_addsbn"
    const val SBN_BUYFORM_ADDSBN = "sbn_buyform_addsbn"
    const val SBN_CONFIRMATION_ADDSBN = "sbn_confirmation_addsbn"
    const val SBN_SUBMITTED_ADDSBN = "sbn_submitted_addsbn"
    const val SBN_CONFIRMATIONUKER_ADDSBN = "sbn_confirmationuker_addsbn"
    const val SBN_SUBMITTEDUKER_ADDSBN = "sbn_submitteduker_addsbn"

    const val CLICK_PRODUCT = "click_product"
    const val SBNPRODUCT_NAME = "sbnproduct_name"
}

```

**File: `core/analytic/src/main/kotlin/id/co/bri/brimons/core/analytic/constant/lifestyle/DonationTrackerEventConstants.kt`**
```kotlin
package id.co.bri.brimons.core.analytic.constant.lifestyle

object DonationTrackerEventConstants {
    const val SELECT_DONATION_ORG = "select_donation_organization"
    const val DONATION_PAYMENT_SUCCESS = "donation_payment_success"
    const val DONATION_PAYMENT_FAILED = "donation_payment_failed"

    const val ORGANIZATION_NAME = "organization_name"
    const val DONATION_TYPE = "donation_type"
}

```

**File: `core/analytic/src/main/kotlin/id/co/bri/brimons/core/analytic/constant/lifestyle/LifestyleTrackerEventConstants.kt`**
```kotlin
package id.co.bri.brimons.core.analytic.constant.lifestyle

object LifestyleTrackerEventConstants {
    /** BRI Super League */
    const val LIGA1_VIEWED = "bri_liga1_homepage_viewed"
    const val LIGA1_PAYMENT_INITIATED = "bri_liga1_payment_initiated"
    const val LIGA1_PAYMENT_COMPLETED = "bri_liga1_payment_completed"
    const val LIGA1_PAYMENT_FAILED = "bri_liga1_payment_failed"

    /** Indihome */
    const val INDIHOME_REGISTRATION_START = "indihome_registration_start"

    /** UFood */
    const val UFOOD_START_WEBVIEW = "ufood_homepage_viewed"
    const val UFOOD_PAYMENT_INITIATED = "payment_initiated"
    const val UFOOD_PAYMENT_COMPLETED = "payment_completed"
    const val UFOOD_PAYMENT_FAILED = "payment_failed"

    /** Beli Qurban */
    const val QURBAN_LAZIO_START_WEBVIEW = "qurban_lazio_homepage_viewed"
    const val QURBAN_LAZIO_PAYMENT_INITIATED = "qurban_lazio_payment_initiated"
    const val QURBAN_LAZIO_PAYMENT_COMPLETED = "qurban_lazio_payment_completed"
    const val QURBAN_LAZIO_PAYMENT_FAILED = "qurban_lazio_payment_failed"

    /** Traveloka Pesawat */
    const val PESAWAT_START_WEBVIEW = "travelokaticket_purchase_start"
    const val PESAWAT_CREATE_ORDER = "travelokaticket_purchase_initiatepayment"
    const val PESAWAT_CREATE_ORDER_SUCCESS = "travelokaticket_purchase_success"
    const val PESAWAT_CREATE_ORDER_FAIL = "travelokaticket_purchase_failed"

    /** PELNI */
    const val PELNI_START_WEBVIEW = "pelniticket_purchase_start"
    const val PELNI_CREATE_ORDER = "pelniticket_purchase_createorder"
    const val PELNI_CONFIRM_ORDER = "pelniticket_purchase_pending_created"
    const val PELNI_EXIT_CONFIRMATION = "pelniticket_purchase_exit_before_payment"
    const val PELNI_TRANSACTION_EXPIRED = "pelniticket_purchase_pending_expired"
    const val PELNI_INITIATE_PAYMENT = "pelniticket_purchase_initiatepayment"
    const val PELNI_TRANSACTION_SUCCESS = "pelniticket_purchase_success"
    const val PELNI_PAYMENT_ONPROCESS = "pelniticket_purchase_suspend"
    const val PELNI_TRANSACTION_FAIL = "pelniticket_purchase_failed"
    const val PELNI_RECEIPT = "pelniticket_purchase_viewreceipt"

    /** KAI */
    const val KAI_START_WEBVIEW = "kai_homepage_viewed"
    const val KAI_PAYMENT_INITIATED = "kai_payment_initiated"
    const val KAI_PAYMENT_COMPLETED = "kai_payment_completed"
    const val KAI_PAYMENT_ONPROCESS = "kai_payment_onprocess"
    const val KAI_PAYMENT_FAILED = "kai_payment_failed"

    /** Voucher Streaming */
    const val VSTREAMING_START = "vstreaming_start"
    const val VSTREAMING_SELECT_VOUCHER = "vstreaming_select_voucher"
    const val VSTREAMING_SELECT_PRODUCT = "vstreaming_select_product"
    const val VSTREAMING_CONFIRM_PAYMENT = "vstreaming_confirm_payment"
    const val VSTREAMING_PAYMENT_SUCCESS = "vstreaming_payment_success"
    const val VSTREAMING_PAYMENT_FAILED = "vstreaming_payment_failed"

    // ---------- Params ----------

    const val ORDER_ID = "order_id"
    const val TOTAL_AMOUNT = "total_amount"
    const val PAYMENT_METHOD = "payment_method"
    const val ENTRY_SOURCE = "entry_source"
    const val AMOUNT_PAID = "amount_paid"
    const val ERROR_CODE = "error_code"
    const val ERROR_MESSAGE = "error_message"
    const val STREAMING_ID = "streaming_id"
    const val PRODUCT_CODE = "product_code"
}

```

**File: `core/analytic/src/main/kotlin/id/co/bri/brimons/core/analytic/constant/lifestyle/ZakatTrackerEventConstants.kt`**
```kotlin
package id.co.bri.brimons.core.analytic.constant.lifestyle

object ZakatTrackerEventConstants {
    const val SELECT_ZAKAT_ORG = "select_zakat_organization"
    const val ZAKAT_PAYMENT_SUCCESS = "zakat_payment_success"
    const val ZAKAT_PAYMENT_FAILED = "zakat_payment_failed"

    const val ORGANIZATION_NAME = "organization_name"
    const val ZAKAT_TYPE = "zakat_type"
}

```

**File: `core/analytic/src/main/kotlin/id/co/bri/brimons/core/analytic/constant/loan/LoanTrackerEventConstants.kt`**
```kotlin
package id.co.bri.brimons.core.analytic.constant.loan

object LoanTrackerEventConstants {
    const val LOAN_PAYMENT_FAVORITE = "loanpayment_favorite"
    const val LOAN_PAYMENT_HISTORY = "loanpayment_history"
    const val LOAN_PAYMENT_SEARCH = "loanpayment_search"
    const val LOAN_PAYMENT_SIMPAN_FAV = "loanpayment_simpanfavorite"
    const val LOAN_PAYMENT_ACTION_PIN = "loanpayment_action_pin"
    const val LOAN_PAYMENT_FAVORITE_TAB = "loanpayment_favorite_tab"
    const val LOAN_PAYMENT_ACTION_UNPIN = "loanpayment_action_unpin"
    const val LOAN_PAYMENT_HISTORY_TAB = "loanpayment_history_tab"
    const val LOAN_PAYMENT_UBAH_NAMA = "loanpayment_action_ubahnama"
    const val LOAN_PAYMENT_HAPUS_DAFTAR = "loanpayment_action_hapusdaftar"
    const val LOAN_PAYMENT_UBAH_NAMA_SAVE = "loanpayment_ubahnama_save"
    const val LOANPAYMENT_INQUIRY = "loanpayment_inquiry"
    const val LOANPAYMENT_DETAIL = "loanpayment_detail"
    const val LOANPAYMENT_KONFIRMASI = "loanpayment_konfirmasi"
    const val LOANPAYMENT_PIN_VERIFIKASI = "loanpayment_pin_verifikasi"
    const val LOANPAYMENT_TRANSACTION_SUCCESS = "loanpayment_transaction_success"
}

```

**File: `core/analytic/src/main/kotlin/id/co/bri/brimons/core/analytic/constant/onboarding/OnboardingNewSkinTrackerEventConstants.kt`**
```kotlin
package id.co.bri.brimons.core.analytic.constant.onboarding

object OnboardingNewSkinTrackerEventConstants {
    const val NDS_START = "NDSQita_start"
    const val NDS_TNC = "NDSQita_snk"
    const val NDS_CREATE_PASSWORD = "NDSQita_create_password"
    const val NDS_CREATE_PIN = "NDSQita_create_pin"
    const val NDS_CONFIRM_PIN = "NDSQita_confirm_pin"
    const val NDS_CODE_VALIDATION = "NDSQita_validasi_kode"
    const val NDS_ONBOARDING_SUCCESS = "NDSQita_onboarding_success"
    const val NDS_TO_HOMEPAGE = "NDSQita_to_homepage"
    const val FAST_MIGRATION_START = "fastmigration_start"
    const val FAST_MIGRATION_CODE_VALIDATION = "fastmigration_validasi_kode"
    const val FAST_MIGRATION_SNK = "fastmigration_snk"
    const val FAST_MIGRATION_PIN_VERIFICATION = "fastmigration_pin_verification"
    const val FAST_MIGRATION_CREATE_PASSWORD = "fastmigration_create_password"
    const val FAST_MIGRATION_SUCCESS = "fastmigration_onboarding_success"
    const val FAST_MIGRATION_TO_HOMEPAGE = "fastmigration_to_hompage"
    const val ONBOARDINGLN_OTPHP_SEND = "onboardingln_otphp_send"
    const val ONBOARDINGLN_OTP_HP_INPUT = "onboardingln_otphp_input"
    const val ONBOARDINGLN_OTP_HP_RESEND = "onboardingln_otp_hp_resend"
    const val ONBOARDINGLN_EMAIL_INPUT = "onboardingln_email_input"
    const val ONBOARDINGLN_OTP_EMAIL_INPUT = "onboardingln_otpemail_input"
    const val ONBOARDINGLN_OTP_EMAIL_RESEND = "onboardingln_otpemail_resend"
    const val ONBOARDINGLN_DATAUTAMA_START = "onboardingln_datautama_start"
    const val ONBOARDINGLN_DATAUTAMA_SUCCESS = "onboardingln_datautama_success"
    const val ONBOARDINGLN_ALAMAT_START = "onboardingln_alamat_start"
    const val ONBOARDINGLN_ALAMAT_ZIPCODE = "onboardingln_alamat_zipcode"
    const val ONBOARDINGLN_ALAMAT_SUCCESS = "onboardingln_alamat_success"

    const val ONBOARDINGLN_SNK = "onboardingln_snk"
    const val ONBOARDINGLN_SCANEKTP_TAKE = "onboardingln_scanektp_take"
    const val ONBOARDINGLN_PROFILING_START = "onboardingln_profiling_start"
    const val ONBOARDINGLN_PROFFILING_SUCCESS = "onboardingln_profilling_success"
    const val ONBOARDINGLN_CONFIRMATION_START = "onboardingln_confirmation_start"
    const val ONBOARDINGLN_CONFIRMATION_SUCCESS = "onboardingln_confirmation_success"
    const val ONBOARDINGLN_PASSWORD_START = "onboardingln_password_start"
    const val ONBOARDINGLN_PASSWORD_SUCCESS = "onboardingln_password_success"

    const val ONBOARDINGLN_VERIFY_DUKCAPIL = "onboardingln_verify_dukcapil"
    const val ONBOARDINGLN_PIN_START = "onboardingln_pin_start"
    const val ONBOARDINGLN_PIN_CONFIRM = "onboardingln_pin_confirm"
    const val ONBOARDINGLN_PIN_SUCCESS = "onboardingln_pin_success"
    const val ONBOARDINGLN_NTB_SUCCESS = "onboardingln_ntb_success"
    const val ONBOARDINGLN_ETB_SUCCESS = "onboardingln_etb_success"
    const val ONBOARDINGLN_BERBRIMO_SUCCESS = "onboardingln_berbrimo_success"
    const val ONBOARDINGLN_REINSTALL_SUCCESS = "onboardingln_reinstall_success"
    const val ONBOARDINGLN_BERQITTA_SUCCESS = "onboardingln_berqitta_success"
    const val ONBOARDINGLN_LOGIN = "onboardingln_login"
    const val ONBOARDINGLN_PILIHJENIS_VERIFIKASI = "onboardingln_pilihjenis_verifikasi"
    const val ONBOARDINGLN_SCAN_ID_PANDUAN = "onboardingln_scan_id_panduan"
    const val ONBOARDINGLN_SCAN_PASSPORT_PANDUAN = "onboarding_scan_paspor_panduan"
    const val ONBOARDINGLN_TAPEPASPOR_TAP = "onboardingln_tapepaspor_tap"
    const val ONBOARDINGLN_SCANPASPOR_RETAKE = "onboardingln_scanpaspor_retake"
    const val ONBOARDINGLN_PANDUAN_LIVENESS = "onboardingln_panduan_liveness"
    const val ONBOARDINGLN_LIVENESS_TAKE = "onboardingln_liveness_take"
    const val ONBOARDINGLN_LIVENESS_SUCCESS = "onboardingln_liveness_success"
    const val ONBOARDINGLN_SCAN_EKTP_OCR = "onboardingln_scan_ektp_ocr"
    const val ONBOARDINGLN_SCANEKTP_RETAKE = "onboardingln_scanektp_retake"
    const val ONBOARDINGLN_SCANEKTP_SUCCESS = "onboardingln_scanektp_success"
    const val ONBOARDINGLN_SCANPASPOR_TAKE = "onboardingln_scanpaspor_take"

    // nik matching onboarding LN
    const val ONBOARDINGLN_LIST_BRINET = "brinetln_list_number"
    const val ONBOARDINGLN_BRINET_PENGKINIAN_LIST = "brinetln_pengkiniandata_listnumber"
    const val ONBOARDINGLN_BRINET_CAPTCHA_START = "brinetln_captcha_start"
    const val ONBOARDINGLN_BRINET_CAPTCHA_INPUT = "brinetln_captcha_input"
    const val ONBOARDINGLN_BRINET_NIK_MATCHING_OLD_START = "brinetln_nikmatching_oldnumber_start"
    const val ONBOARDINGLN_BRINET_NIK_MATCHING_NEW_START = "brinetln_nikmatching_newnumber_start"
    const val ONBOARDINGLN_BRINET_PENGKINIAN_DATA_NIK_MATCHING =
        "brinetln_pengkiniandata_nikmatching"

    // nik matching onboarding DN
    const val ONBOARDING_LIST_BRINET = "brinet_list_number"
    const val ONBOARDING_BRINET_PENGKINIAN_LIST = "brinet_pengkiniandata_listnumber"
    const val ONBOARDING_BRINET_CAPTCHA_START = "brinet_captcha_start"
    const val ONBOARDING_BRINET_CAPTCHA_INPUT = "brinet_captcha_input"
    const val ONBOARDING_BRINET_NIK_MATCHING_OLD_START = "brinet_nikmatching_oldnumber_start"
    const val ONBOARDING_BRINET_NIK_MATCHING_NEW_START = "brinet_nikmatching_newnumber_start"
    const val ONBOARDING_BRINET_PENGKINIAN_DATA_NIK_MATCHING = "brinet_pengkiniandata_nikmatching"

    const val ONBOARDING_ID = "onboarding_id"
    const val FINGERPRINTING_RESULT = "fingerprinting_result"
    const val LOCATION_PERMISSION_ACTION = "location_permission_action"
    const val TNC_AGREEMENT = "tnc_agreement"
    const val NOTIF_CONSENT = "notif_consent"
    const val REPLY_AGREEMENT = "reply_agreement"
    const val TOTAL_TIME = "total_time"
    const val COUNTER_FAIL = "counter_fail"
    const val IS_HAS_COMBINATION = "is_has_combination"
    const val IS_HAS_LENGTH_MATCH = "is_has_length_match"
    const val IS_CONTAINS_SPACE = "is_contains_space"
    const val IS_PASSWORD_MATCH = "is_passwords_match"
    const val IS_PASSWORD_VALID = "is_password_valid"
    const val VERIFICATION_STATUS = "verification_status"
    const val IS_START_TRANSACTION = "start_transaction"
    const val IS_STRAT_TRANSACTION = "start_transaction"

    // -- Enhancement attributes --
    const val ONBOARDING_TYPE = "onboarding _type"
    const val IS_FIRST_TIME_REQUEST = "is_first_time_request"
    const val IS_FOREIGN = "is_foreign"
    const val STATUS_ONBOARDING = "status_onboarding"
    const val PREFIX_VALUE = "prefix_value"
    const val LOCATION_ORIGIN = "location_origin"
    const val METHOD_OTP = "method_otp"
    const val ONBOARDING_ACCESS = "onboarding _access"
    const val PHONE_ORIGIN = "phone_origin"
    const val STATUS_PAGE = "status_page"
    const val REMAINING_SEND_ATTEMPT = "remaining_send_attempt"
    const val REMAINING_VERIFY_ATTEMPT = "remaining_verify_attempt"
    const val REMAINING_DEVICE_SLOT = "remaining_device_slot"
    const val COUNTER_GAGAL = "counter gagal"
    const val TYPE_USER = "type_user"
    const val DOCUMENT_TYPE = "document_type"
    const val PARTNER_EKYC = "partner_ekyc"
    const val BIZ_ID = "bizId"
    const val STATUS_OPEN_SDK = "status_open_sdk"
    const val STATUS_SCAN = "status_scan"
    const val STATUS_OCR = "status_ocr"
    const val STATUS_VERIFY = "status_verify"
    const val COMPARE_RESULT = "comapre_result"
    const val STATUS_LIVENESS = "status_liveness"
    const val PRIVY_STATUS = "privy_status"
    const val PASPOR_COMPARE_STATUS = "Paspor_comapre_status"
    const val NIK_MATCH_RESULT = "nik_match_result"
    const val CAPTCHA = "captcha"
    const val STATUS_CAPTCHA = "status_captcha"
    const val SEARCH_SUCCESS = "search_success"

    // -- Profiling / Data Utama filled flags --
    const val IS_REFERRAL_FILLED = "is_referral_filled"
    const val IS_JENIS_KELAMIN_FILLED = "is_jenis_kelamin_filled"
    const val IS_AGAMA_FILLED = "is_agama_filled"
    const val IS_STATUS_PERKAWINAN_FILLED = "is_status_perkawinan_filled"
    const val IS_PENDIDIKAN_FILLED = "is_pendiidikan_filled"

    // -- Alamat filled flags --
    const val IS_POSTAL_CODE_FILLED = "is_postal_code_filled"
    const val IS_ADDRESS_DETAIL_FILLED = "is_address_detail_filled"
    const val IS_RT_FILLED = "is_rt_filled"
    const val IS_RW_FILLED = "is_rw_filled"
    const val IS_DOMICILE_DIFFERENT = "is_domicile_different"
    const val IS_POSTAL_CODE_DOMICILE_FILLED = "is_postal_code_domicile_filled"
    const val IS_ADDRESS_DETAIL_DOMICILE_FILLED = "is_address_detail_domicile_filled"
    const val IS_RT_DOMICILE_FILLED = "is_rt_domicile_filled"
    const val IS_BRANCH_OFFICE_FILLED = "is_branch_office_filled"

    // -- Profiling pekerjaan filled flags --
    const val IS_INCOME_FILLED = "is_income_filled"
    const val IS_MONTHLY_INCOME_FILLED = "is_monthly_income_filled"
    const val IS_DAILY_TRANSACTION_FILLED = "is_daily_transaction_filled"
    const val IS_ACCOUNT_PURPOSE_FILLED = "is_account_purpose_filled"
    const val IS_OCCUPATION_FILLED = "is_occupation_filled"
    const val IS_JOB_TITLE_FILLED = "is_job_title_filled"
    const val IS_COMPANY_NAME_FILLED = "is_company_name_filled"
    const val IS_COMPANY_ADDRESS_FILLED = "is_company_address_filled"

    // -- Konfirmasi edited flags --
    const val EDITED_PERSONAL_INFO = "edited_personal_info"
    const val EDITED_ADDRESS_INFO = "edited_address_info"
    const val EDITED_OCCUPATION_INFO = "edited_occupation_info"
    const val ONBOARDINGLN_ID = "onboardingln_id"

    // -- Password flags --
    const val IS_HAS_UPPERCASE = "is_has_uppercase"
    const val IS_HAS_NUMBER = "is_has_number"
    const val IS_AS_SYMBOL = "is_as_symbol"

    // -- PIN flags --
    const val IS_SEQUENTIAL = "is_sequential"
    const val IS_REPEATED_PATTERN = "is_repeated_pattern"
    const val PINS_MATCH = "pins_match"
    const val IS_PIN_VALID = "is_pin_valid"
    const val IS_PIN_MATCH = "is_pin_match"

    // -- onboarding nonce flags --
    const val ONBOARDING_NONCE_INFO = "onboarding_nonce_info"
    const val ONBOARDING_NONCE_REDIRECT = "onboarding_nonce_redirect"
}

```

**File: `core/analytic/src/main/kotlin/id/co/bri/brimons/core/analytic/constant/onboarding/OnboardingTrackerEventConstants.kt`**
```kotlin
package id.co.bri.brimons.core.analytic.constant.onboarding

object OnboardingTrackerEventConstants {
    const val ONBOARDING_START = "onboarding_start"
    const val ONBOARDING_HP_INPUT = "onboarding_hp_input"
    const val ONBOARDING_OTP_PILIH_METODE = "onboarding_otp_metode"
    const val ONBOARDING_OTP_HP_INPUT = "onboarding_otphp_send"
    const val ONBOARDING_OTP_HP_RESEND = "onboarding_otp_hp_resend"
    const val ONBOARDING_EMAIL_INPUT = "onboarding_email_input"
    const val ONBOARDING_OTP_EMAIL_INPUT = "onboarding_otpemail_input"
    const val ONBOARDING_OTP_EMAIL_RESEND = "onboarding_otpemail_resend"
    const val ONBOARDING_SNK = "onboarding_snk"
    const val ONBOARDING_SCANKTP_PANDUAN = "onboarding_scanktp_panduan"
    const val ONBOARDING_SCANEKTP_TAKE = "onboarding_scanektp_take"
    const val ONBOARDING_SCANEKTP_RETAKE = "onboarding_scanektp_retake"
    const val ONBOARDING_SCANEKTP_OCR = "onboarding_scanektp_ocr"
    const val ONBOARDING_LIVENESS_PANDUAN = "onboarding_panduan_liveness"
    const val ONBOARDING_LIVENESS_TAKE = "onboarding_liveness_take"
    const val ONBOARDING_LIVENESS_SUCCESS = "onboarding_liveness_success"
    const val ONBOARDING_DATAUTAMA_START = "onboarding_datautama_start"

    const val BRINET_OTPHP_START = "brinet_otphp_start"

    const val BRINET_PANDUAN_VIEW = "brinet_panduan_view"

    const val BRINET_OTPHP_SEND = "brinet_otphp_send"

    const val BRINET_OTPHP_INPUT = "brinet_otphp_input"

    const val BRINET_CHANGEPHONE_INPUT = "brinet_changephone_input"

    const val BRINET_CHANGEOTP_SEND = "brinet_changeotp_send"

    const val BRINET_CHANGEOTP_INPUT = "brinet_changeotp_input"

    const val BRINET_EMAIL_INPUT = "brinet_email_input"

    const val BRINET_EMAILOTP_INPUT = "brinet_emailotp_input"

    const val BRINET_EMAILOTP_RESEND = "brinet_emailotp_resend"

    const val ONBOARDING_DATAUTAMA_SUCCESS = "onboarding_datautama_success"
    const val ONBOARDING_ALAMAT_START = "onboarding_alamat_start"
    const val ONBOARDING_ALAMAT_ZIPCODE = "onboarding_alamat_zipcode"
    const val ONBOARDING_ALAMAT_SUCCESS = "onboarding_alamat_success"
    const val ONBOARDING_PROFILING_START = "onboarding_profiling_start"
    const val ONBOARDING_PROFILLING_SUCCESS = "onboarding_profilling_success"
    const val ONBOARDING_CONFIRMATION_DATA_START = "onboarding_confirmation_start"
    const val ONBOARDING_CONFIRMATION_DATA_SUCCESS = "onboarding_confirmation_success"
    const val ONBOARDING_PASSWORD_START = "onboarding_password_start"
    const val ONBOARDING_PASSWORD_SUCCESS = "onboarding_password_success"
    const val ONBOARDING_PIN_START = "onboarding_pin_start"
    const val ONBOARDING_PIN_CONFIRM = "onboarding_pin_confirm"
    const val ONBOARDING_PIN_SUCCESS = "onboarding_pin_success"
    const val ONBOARDING_NTB_SUCCESS = "onboarding_ntb_success"
    const val ONBOARDING_ETB_SUCCESS = "onboarding_etb_success"

    const val ONBOARDING_BERQITTA_SUCCESS = "onboarding_berqitta_success"
    const val ONBOARDING_BERBRIMO_SUCCESS = "onboarding_berbrimo_success"

    const val ONBOARDING_REINSTALL_SUCCESS = "onboarding_reinstall_success"
    const val ONBOARDING_LOGIN = "onboarding_login"

    const val INPUT_REFERRAL_FIELD = "input_referral_field"

    const val VALIDATE_REFERRAL_CODE = "validate_referral_code"

    const val ONBOARDING_ID = "onboarding_id"
    const val DPLK_ENTRY_ACCESS = "entry_access"
    const val STATUS_VERIFICATION = "status_verification"
    const val CHECKBOX_RIPLAY = "checkbox_riplay"
    const val CHECKBOX_MARKETING_INFO = "checkbox_marketing_info"
    const val CHECKBOX_PRIVACY_POLICY = "checkbox_privacy_policy"

    const val CODE_LENGTH = "code_length"
    const val IS_JENIS_KELAMIN_FILLED = "is_jenis_kelamin_filled"
    const val IS_AGAMA_FILLED = "is_agama_filled"
    const val IS_STATUS_PERKAWINAN_FILLED = "is_status_perkawinan_filled"
    const val IS_PENDIIDIKAN_FILLED = "is_pendiidikan_filled"
    const val DATA_SOURCE = "data_source"
    const val SEARCH_SUCCESS = "search_success"
    const val IS_POSTAL_CODE_FILLED = "is_postal_code_filled"
    const val IS_ADDRESS_DETAIL_FILLED = "is_address_detail_filled"
    const val IS_RT_FILLED = "is_rt_filled"
    const val IS_RW_FILLED = "is_rw_filled"
    const val IS_DOMICILE_DIFFERENT = "is_domicile_different"
    const val IS_POSTAL_CODE_DOMICILE_FILLED = "is_postal_code_domicile_filled"
    const val IS_ADDRESS_DETAIL_DOMICILE_FILLED = "is_address_detail_domicile_filled"
    const val IS_RT_DOMICILE_FILLED = "is_rt_domicile_filled"
    const val IS_RW_DOMICILE_FILLED = "is_rw_domicile_filled"
    const val IS_BRANCH_OFFICE_FILLED = "is_branch_office_filled"
    const val IS_INCOME_FILLED = "is_income_filled"
    const val IS_MONTHLY_INCOME_FILLED = "is_monthly_income_filled"
    const val IS_DAILY_TRANSACTION_FILLED = "is_daily_transaction_filled"
    const val IS_ACCOUNT_PURPOSE_FILLED = "is_account_purpose_filled"
    const val IS_OCCUPATION_FILLED = "is_occupation_filled"
    const val IS_JOB_TITLE_FILLED = "is_job_title_filled"
    const val IS_COMPANY_NAME_FILLED = "is_company_name_filled"
    const val IS_COMPANY_ADDRESS_FILLED = "is_company_address_filled"
    const val EDITED_PERSONAL_INFO = "edited_personal_info"
    const val EDITED_ADDRESS_INFO = "edited_address_info"
    const val EDITED_OCCUPATION_INFO = "edited_occupation_info"
    const val SECTIONS_EDITED_COUNT = "sections_edited_count"
    const val IS_ALL_SECTIONS_REVIEWED = "is_all_sections_reviewed"
    const val IS_HAS_UPPERCASE = "is_has_uppercase"
    const val IS_HAS_NUMBER = "is_has_number"
    const val IS_AS_SYMBOL = "is_as_symbol"
    const val IS_CONTAINS_SPACE = "is_contains_space"
    const val IS_PASSWORDS_MATCH = "is_passwords_match"
    const val IS_PASSWORD_VALID = "is_password_valid"
    const val PASSWORD_ATTEMPT_COUNT = "password_attempt_count"
    const val FIELD_EDITED = "field_edited"
    const val IS_SEQUENTIAL = "is_sequential"
    const val IS_REPEATED_PATTERN = "is_repeated_pattern"
    const val PINS_MATCH = "pins_match"
    const val IS_PIN_VALID = "is_pin_valid"
    const val PIN_ATTEMPT_COUNT = "pin_attempt_count"
    const val LIVENESS_PARTNER_NAME = "liveness_partner_name"
    const val ONBOARDINGLN_BRINET_PENGKINIAN_DATA_BLOCKED = "brinetln_pengkiniandata_blocked"
    const val ONBOARDING_BRINET_PENGKINIAN_DATA_BLOCKED = "brinet_pengkiniandata_blocked"
}

```

**File: `core/analytic/src/main/kotlin/id/co/bri/brimons/core/analytic/constant/other/FastMenuTrackerEventConstants.kt`**
```kotlin
package id.co.bri.brimons.core.analytic.constant.other

object FastMenuTrackerEventConstants {
    const val FASTMENU_CLICKED = "fastmenu_clicked"
    const val FASTMENU_PROMO_CLICKED = "fastmenu_promo_clicked"
}

```

**File: `core/analytic/src/main/kotlin/id/co/bri/brimons/core/analytic/constant/other/InsiderNotificationTrackerEventConstants.kt`**
```kotlin
package id.co.bri.brimons.core.analytic.constant.other

object InsiderNotificationTrackerEventConstants {
    const val EVENT_NAME_NOTIFICATION_PAGE = "triger_notification_bell"
    const val EVENT_NAME_READ_NOTIFICATION = "show_detail_notification"

    const val EVENT_PARAM_NOTIFICATION_ID = "notification_id"
}

```

**File: `core/analytic/src/main/kotlin/id/co/bri/brimons/core/analytic/constant/other/LogoutTrackerEventConstants.kt`**
```kotlin
package id.co.bri.brimons.core.analytic.constant.other

object LogoutHomeTrackerEventConstants {
    const val LOGOUT_HOME_CONFIRM = "homepage_logout_confirm"
    const val LOGOUT_HOME_CANCEL = "homepage_logout_cancel"
    const val LOGOUT_HOME_SUCCESS = "homepage_logout_success"
}

```

**File: `core/analytic/src/main/kotlin/id/co/bri/brimons/core/analytic/constant/payment/CashDepositTrackerEventConstants.kt`**
```kotlin
package id.co.bri.brimons.core.analytic.constant.payment

object CashDepositTrackerEventConstants {
    const val SETOR_TUNAI_PAGE = "setor_tunai_page"
    const val SETOR_TUNAI_CLICK_CONTINUE_BTN = "setor_tunai_click_continue_btn"
    const val SETOR_TUNAI_PIN_CONFIRMATION = "setor_tunai_pin_confirmation"
    const val SETOR_TUNAI_DEPOSIT_CODE_PAGE = "setor_tunai_deposit_code_page"
    const val SETOR_TUNAI_CLICK_CANCEL_DEPOSIT_BTN = "setor_tunai_click_cancel_deposit_btn"
    const val SETOR_TUNAI_CANCEL_DEPOSIT_CONFIRMATION = "setor_tunai_cancel_deposit_confirmation"
    const val SETOR_TUNAI_EXPIRED_CODE_PAGE = "setor_tunai_expired_code_page"
    const val SETOR_TUNAI_CLICK_REGENERATE_CODE_BTN = "setor_tunai_click_regenerate_code_btn"
    const val SETOR_TUNAI_SUCCESS = "setor_tunai_success"

    const val ERROR = "error"
    const val WITHDRAWAL_CHANNEL = "withdrawal_channel"
    const val REGENERATE_CODE_COUNT = "regenerate_code_count"
}

```

**File: `core/analytic/src/main/kotlin/id/co/bri/brimons/core/analytic/constant/payment/CashWithdrawalTrackerEventConstants.kt`**
```kotlin
package id.co.bri.brimons.core.analytic.constant.payment

object CashWithdrawalTrackerEventConstants {
    const val CASH_WITHDRAWAL_PAGE = "tarik_tunai_page"
    const val CASH_WITHDRAWAL_CLICK_CONTINUE_BTN = "tarik_tunai_click_continue_btn"
    const val CASH_WITHDRAWAL_SETUP_PAGE = "tarik_tunai_setup_page"
    const val CASH_WITHDRAWAL_SETUP_CLICK_CONTINUE_BTN = "tarik_tunai_setup_click_continue_btn"
    const val CASH_WITHDRAWAL_CLICK_CONFIRMATION_BTN = "tarik_tunai_click_confirmation_btn"
    const val CASH_WITHDRAWAL_PIN_CONFIRMATION = "tarik_tunai_pin_confirmation"
    const val CASH_WITHDRAWAL_WITHDRAWAL_CODE_PAGE = "tarik_tunai_withdrawal_code_page"
    const val CASH_WITHDRAWAL_CLICK_CANCEL_WITHDRAWAL_BTN =
        "tarik_tunai_click_cancel_withdrawal_btn"
    const val CASH_WITHDRAWAL_CANCEL_WITHDRAWAL_CONFIRMATION =
        "tarik_tunai_cancel_withdrawal_confirmation"
    const val CASH_WITHDRAWAL_EXPIRED_CODE_PAGE = "tarik_tunai_expired_code_page"
    const val CASH_WITHDRAWAL_CLICK_REGENERATE_CODE_BTN = "tarik_tunai_click_regenerate_code_btn"
    const val CASH_WITHDRAWAL_SUCCESS = "tarik_tunai_success"

    const val ERROR = "error"
    const val WITHDRAWAL_CHANNEL = "withdrawal_channel"
    const val REGENERATE_CODE_COUNT = "regenerate_code_count"

    fun buildParam(
        error: String? = null,
        withdrawalChannel: String? = null,
        regenerateCount: Int? = null,
    ): Map<String, Any> = buildMap {
        error?.takeIf { it.isNotBlank() }?.let { put(ERROR, it) }
        withdrawalChannel?.takeIf { it.isNotBlank() }?.let { put(WITHDRAWAL_CHANNEL, it) }
        regenerateCount?.let { put(REGENERATE_CODE_COUNT, it) }
    }
}

```

**File: `core/analytic/src/main/kotlin/id/co/bri/brimons/core/analytic/constant/payment/ChangeSourceOfFundTrackerEventConstants.kt`**
```kotlin
package id.co.bri.brimons.core.analytic.constant.payment

object ChangeSourceOfFundTrackerEventConstants {
    interface BaseEvent {
        val eventName: String
    }

    enum class ChangeSourceOfFundEvent(override val eventName: String) : BaseEvent {
        ENTRY_POINT("sumber_dana_utama_entry_point"),
        SELECT_BUTTON("sumber_dana_utama_click_btn_select"),
        SELECT_CONFIRMATION("sumber_dana_utama_click_btn_confirmation"),
    }
}

```

**File: `core/analytic/src/main/kotlin/id/co/bri/brimons/core/analytic/constant/payment/EWalletTrackerEventConstants.kt`**
```kotlin
package id.co.bri.brimons.core.analytic.constant.payment

object EWalletTrackerEventConstants {
    const val EWALLET_START = "ewallet_start"
    const val EWALLET_CHOOSE = "ewallet_choose"
    const val EWALLET_NO_INPUT = "ewallet_no_input"
    const val EWALLET_NOMINAL_INPUT = "ewallet_nominal_input"
    const val EWALLET_CONFIRM = "ewallet_confirm"
    const val EWALLET_SUCCESS = "ewallet_success"
    const val EWALLET_BINDING_START = "ewallet_binding_start"
    const val EWALLET_BINDING_CHOOSE = "ewallet_binding_choose"
    const val EWALLET_BINDING_SNK = "ewallet_binding_snk"
    const val EWALLET_BINDING_CONFIRM = "ewallet_binding_confirm"
    const val EWALLET_BINDING_SUCCESS = "ewallet_binding_success"
}

```

**File: `core/analytic/src/main/kotlin/id/co/bri/brimons/core/analytic/constant/payment/PulsaAndPaketDataTrackerEventConstants.kt`**
```kotlin
package id.co.bri.brimons.core.analytic.constant.payment

object PulsaAndPaketDataTrackerEventConstants {
    const val PULSA_START = "pulsa_start"
    const val PULSA_NO_INPUT = "pulsa_no_input"
    const val PULSA_NOMINAL_INPUT = "pulsa_nominal_input"
    const val PULSA_CONFIRM = "pulsa_confirm"
    const val PULSA_SUCCESS = "pulsa_success"
    const val PAKETDATA_NOMINAL_INPUT = "paketdata_nominal_input"
    const val PAKETDATA_CONFIRM = "paketdata_confirm"
    const val PAKETDATA_SUCCESS = "paketdata_success"
}

```

**File: `core/analytic/src/test/kotlin/id/co/bri/brimons/core/analytic/AnalyticsHubTest.kt`**
```kotlin
package id.co.bri.brimons.core.analytic

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch

class AnalyticsHubTest :
    FunSpec({
        context("nowGmt7") {
            test("returns a string matching dd.MM.yyyy HH:mm") {
                AnalyticsHub.nowGmt7() shouldMatch Regex("\\d{2}\\.\\d{2}\\.\\d{4} \\d{2}:\\d{2}")
            }
        }

        context("nowIso") {
            test("returns a string matching yyyy-MM-dd'T'HH:mm:ssZ") {
                AnalyticsHub.nowIso() shouldMatch
                    Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}[+-]\\d{4}")
            }
        }

        context("deviceInfo") {
            test("returns null before init() populates the device info map") {
                AnalyticsHub.deviceInfo("any_key").shouldBeNull()
            }
        }

        context("isInitialized") {
            test("returns false when init has not been called") {
                AnalyticsHub.isInitialized() shouldBe false
            }
        }
    })

```

**File: `core/database/build.gradle.kts`**
```kotlin
plugins {
    alias(libs.plugins.brimo.android.library)
    alias(libs.plugins.brimo.android.hilt)
    alias(libs.plugins.brimo.android.room)
}

android { namespace = "id.co.bri.brimons.core.database" }

dependencies {
    implementation(libs.gson)
    implementation(libs.sqlcipher)
    implementation(projects.core.security)
    implementation(projects.core.util)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}

```

**File: `core/database/src/main/kotlin/id/co/bri/brimons/core/database/DatabaseSecretConfig.kt`**
```kotlin
package id.co.bri.brimons.core.database

import android.util.Log
import id.co.bri.brimons.core.security.crypto.SecurityConstants
import id.co.bri.brimons.core.security.crypto.decryptAsBase64Compat
import id.co.bri.brimons.core.util.GeneralHelper

object DatabaseSecretConfig {

    private fun generateKey(source: String): CharArray {
        return try {
            val firstChar = decryptAsBase64Compat(source).first()
            CharArray(16) { firstChar }
        } catch (e: Exception) {
            if (!GeneralHelper.isProd()) {
                Log.e("DatabaseSecretConfig", "generateKey error", e)
            }
            CharArray(16)
        }
    }

    fun getRawSecretKeyRoom(): CharArray = generateKey(SecurityConstants.DEV_STRING_CHAR1)

    fun getRawSecretKeyRoomFastMenu(): CharArray = generateKey(SecurityConstants.DEV_STRING_CHAR_2)
}

```

**File: `core/database/src/main/kotlin/id/co/bri/brimons/core/database/di/DaoModule.kt`**
```kotlin
package id.co.bri.brimons.core.database.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import id.co.bri.brimons.core.database.impl.dao.FastMenuDao
import id.co.bri.brimons.core.database.impl.db.FastMenuDatabase

@Module
@InstallIn(SingletonComponent::class)
object DaoModule {
    @Provides fun provideFastMenuDao(db: FastMenuDatabase): FastMenuDao = db.fastMenuDao()
}

```

**File: `core/database/src/main/kotlin/id/co/bri/brimons/core/database/di/DatabaseModule.kt`**
```kotlin
package id.co.bri.brimons.core.database.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import id.co.bri.brimons.core.database.DatabaseSecretConfig
import id.co.bri.brimons.core.database.impl.dao.FastMenuDao
import id.co.bri.brimons.core.database.impl.db.FastMenuDatabase
import id.co.bri.brimons.core.database.impl.utils.DatabaseConfig
import javax.inject.Singleton
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideMenuDatabase(@ApplicationContext context: Context): FastMenuDatabase {

        val passphrase = SQLiteDatabase.getBytes(DatabaseSecretConfig.getRawSecretKeyRoomFastMenu())

        val factory = SupportFactory(passphrase)

        return Room.databaseBuilder(context, FastMenuDatabase::class.java, DatabaseConfig.Name.MENU)
            .openHelperFactory(factory)
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides fun provideFastMenuDao(db: FastMenuDatabase): FastMenuDao = db.fastMenuDao()
}

```

**File: `core/database/src/main/kotlin/id/co/bri/brimons/core/database/impl/dao/FastMenuDao.kt`**
```kotlin
package id.co.bri.brimons.core.database.impl.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import id.co.bri.brimons.core.database.impl.entity.FastMenuDefaultEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FastMenuDao {

    @Query("SELECT * FROM tbl_fast_menu_default") fun getAll(): Flow<List<FastMenuDefaultEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(data: List<FastMenuDefaultEntity>)

    @Query("DELETE FROM tbl_fast_menu_default") suspend fun deleteAll()
}

```

**File: `core/database/src/main/kotlin/id/co/bri/brimons/core/database/impl/db/FastMenuDatabase.kt`**
```kotlin
package id.co.bri.brimons.core.database.impl.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import id.co.bri.brimons.core.database.impl.dao.FastMenuDao
import id.co.bri.brimons.core.database.impl.entity.FastMenuDefaultEntity
import id.co.bri.brimons.core.database.impl.utils.converter.RoomDateConverter
import id.co.bri.brimons.core.database.impl.utils.converter.RoomLanguageConverter

@Database(entities = [FastMenuDefaultEntity::class], version = 15, exportSchema = false)
@TypeConverters(RoomDateConverter::class, RoomLanguageConverter::class)
abstract class FastMenuDatabase : RoomDatabase() {
    abstract fun fastMenuDao(): FastMenuDao
}

```

**File: `core/database/src/main/kotlin/id/co/bri/brimons/core/database/impl/entity/FastMenuDefaultEntity.kt`**
```kotlin
package id.co.bri.brimons.core.database.impl.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import id.co.bri.brimons.core.database.impl.utils.TableNames
import id.co.bri.brimons.core.database.impl.utils.converter.RoomLanguageConverter

@Entity(tableName = TableNames.FAST_MENU_DEFAULT)
data class FastMenuDefaultEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Int = 0,
    @ColumnInfo(name = "kode") val kode: String,
    @field:TypeConverters(RoomLanguageConverter::class)
    @ColumnInfo(name = "name")
    val menuName: LanguageModel,
    @ColumnInfo(name = "image") val gambarMenu: String,
    @ColumnInfo(name = "menu") val menu: String,
    @ColumnInfo(name = "tag") val tag: String,
    @ColumnInfo(name = "flag") val flagNew: Boolean,
    @ColumnInfo(name = "position") val position: Int,
    @ColumnInfo(name = "is_toggled") val isToggled: Boolean,
    @ColumnInfo(name = "is_available") val isAvailable: Boolean,
)

```

**File: `core/database/src/main/kotlin/id/co/bri/brimons/core/database/impl/entity/LanguageModel.kt`**
```kotlin
package id.co.bri.brimons.core.database.impl.entity

import id.co.bri.brimons.core.database.impl.utils.TableNames

data class LanguageModel(val id: String, val en: String) {
    fun getCurrentLanguage(language: String): String {
        return when (language) {
            TableNames.LANGUAGE_ENGLISH -> en
            else -> id
        }
    }
}

```

**File: `core/database/src/main/kotlin/id/co/bri/brimons/core/database/impl/utils/DatabaseConstants.kt`**
```kotlin
package id.co.bri.brimons.core.database.impl.utils

object DatabaseConfig {

    object Name {
        const val MENU = "menu_db"
    }

    object Version {
        const val PFM = 15
        const val MENU = 15
        const val FAST_MENU = 29
        const val RATE = 7
        const val LIFESTYLE = 4
        const val PENGELOLAAN_KARTU = 1
    }
}

object TableNames {
    const val FAST_MENU_DEFAULT = "tbl_fast_menu_default"
    const val DB_DATE_FORMAT = "yyyy-MM-dd"
    const val LANGUAGE_ENGLISH = "en"
}

```

**File: `core/database/src/main/kotlin/id/co/bri/brimons/core/database/impl/utils/converter/RoomDateConverter.kt`**
```kotlin
package id.co.bri.brimons.core.database.impl.utils.converter

import androidx.room.TypeConverter
import id.co.bri.brimons.core.database.impl.utils.TableNames
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RoomDateConverter {

    private val format = SimpleDateFormat(TableNames.DB_DATE_FORMAT, Locale.getDefault())

    @TypeConverter fun fromString(value: String?): Date? = value?.let { format.parse(it) }

    @TypeConverter fun fromDate(date: Date?): String? = date?.let { format.format(it) }
}

```

**File: `core/database/src/main/kotlin/id/co/bri/brimons/core/database/impl/utils/converter/RoomLanguageConverter.kt`**
```kotlin
package id.co.bri.brimons.core.database.impl.utils.converter

import androidx.room.TypeConverter
import com.google.gson.Gson
import id.co.bri.brimons.core.database.impl.entity.LanguageModel

class RoomLanguageConverter {

    @TypeConverter fun fromLanguageModel(value: LanguageModel): String = Gson().toJson(value)

    @TypeConverter
    fun toLanguageModel(value: String): LanguageModel =
        Gson().fromJson(value, LanguageModel::class.java)
}

```

**File: `core/database/src/test/kotlin/id/co/bri/brimons/core/database/FastMenuDaoTest.kt`**
```kotlin
package id.co.bri.brimons.core.database

import id.co.bri.brimons.core.database.impl.dao.FastMenuDao
import id.co.bri.brimons.core.database.impl.entity.FastMenuDefaultEntity
import id.co.bri.brimons.core.database.impl.entity.LanguageModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.core.Is.`is`
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.Config

@Config(sdk = [24])
class FastMenuDaoTest : FastMenuDatabaseTest() {

    private lateinit var fastMenuDao: FastMenuDao

    @Before
    fun init() {
        fastMenuDao = db.fastMenuDao()
    }

    @Test
    fun insertAndGetAll_shouldReturnInsertedData() = runBlocking {
        val mockData =
            listOf(
                FastMenuDefaultEntity(
                    kode = "BRIVA",
                    menuName = LanguageModel(id = "Briva", en = "Briva"),
                    gambarMenu = "briva.png",
                    menu = "BRIVA",
                    tag = "finance",
                    flagNew = false,
                    position = 1,
                    isToggled = true,
                    isAvailable = true,
                ),
                FastMenuDefaultEntity(
                    kode = "QRIS",
                    menuName = LanguageModel(id = "QRIS", en = "QRIS"),
                    gambarMenu = "qris.png",
                    menu = "QRIS",
                    tag = "finance",
                    flagNew = true,
                    position = 2,
                    isToggled = false,
                    isAvailable = true,
                ),
            )

        fastMenuDao.insert(mockData)

        val result = fastMenuDao.getAll().first()
        assertThat(result.size, `is`(2))
        assertThat(result[0].kode, `is`("BRIVA"))
        assertThat(result[0].menu, `is`("BRIVA"))
        assertThat(result[1].kode, `is`("QRIS"))
        assertThat(result[1].menu, `is`("QRIS"))
    }

    @Test
    fun deleteAll_shouldClearTable() = runBlocking {
        val mockData =
            listOf(
                FastMenuDefaultEntity(
                    kode = "BRIVA",
                    menuName = LanguageModel(id = "BRIVA", en = "BRIVA"),
                    gambarMenu = "briva.png",
                    menu = "BRIVA",
                    tag = "finance",
                    flagNew = false,
                    position = 1,
                    isToggled = true,
                    isAvailable = true,
                )
            )

        fastMenuDao.insert(mockData)
        fastMenuDao.deleteAll()
        val result = fastMenuDao.getAll().first()
        assertThat(result.isEmpty(), `is`(true))
    }
}

```

**File: `core/database/src/test/kotlin/id/co/bri/brimons/core/database/FastMenuDatabaseTest.kt`**
```kotlin
package id.co.bri.brimons.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider.getApplicationContext
import id.co.bri.brimons.core.database.impl.db.FastMenuDatabase
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24])
abstract class FastMenuDatabaseTest {
    lateinit var db: FastMenuDatabase

    @Before
    fun initDB() {
        db =
            Room.inMemoryDatabaseBuilder(getApplicationContext(), FastMenuDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun closeDB() {
        db.close()
    }
}

```

**File: `core/model/build.gradle.kts`**
```kotlin
plugins {
    alias(libs.plugins.brimo.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android { namespace = "id.co.bri.brimons.core.model" }

dependencies {
    // exposed in public signatures: JsonElement on
    // ApiErrorException/GeneralApiException/RestResponse,
    // SharedFlow on GlobalEventDispatcher
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)

    implementation(projects.core.util)
    implementation(libs.gson)
}

```

**File: `core/model/src/main/kotlin/id/co/bri/brimons/core/model/AmountModel.kt`**
```kotlin
package id.co.bri.brimons.core.model

import com.google.gson.annotations.SerializedName

data class AmountModel(
    @SerializedName("text") val text: String,
    @SerializedName("value") val value: Int,
)

```

**File: `core/model/src/main/kotlin/id/co/bri/brimons/core/model/BillingModel.kt`**
```kotlin
package id.co.bri.brimons.core.model

import com.google.gson.annotations.SerializedName

data class BillingModel(
    @SerializedName("icon_path") val iconPath: String,
    @SerializedName("title") val title: String,
    @SerializedName("subtitle") val subtitle: String,
    @SerializedName("description") val description: String,
) {
    companion object {
        val EMPTY = BillingModel(iconPath = "", title = "", subtitle = "", description = "")
    }
}

```

**File: `core/model/src/main/kotlin/id/co/bri/brimons/core/model/DataViewModel.kt`**
```kotlin
package id.co.bri.brimons.core.model

import com.google.gson.annotations.SerializedName

data class DataViewModel(
    @SerializedName("name") val name: String,
    @SerializedName("value") val value: String,
    @SerializedName("style") val style: String? = null,
    @SerializedName("title") val title: String = "",
)

```

**File: `core/model/src/main/kotlin/id/co/bri/brimons/core/model/PopupModel.kt`**
```kotlin
package id.co.bri.brimons.core.model

import com.google.gson.annotations.SerializedName

data class PopupModel(
    @SerializedName("title") val title: String,
    @SerializedName("description") val description: String,
)

```

**File: `core/model/src/main/kotlin/id/co/bri/brimons/core/model/exception/ApiErrorException.kt`**
```kotlin
package id.co.bri.brimons.core.model.exception

import kotlinx.serialization.json.JsonElement

open class ApiErrorException(
    val code: String?,
    override val message: String,
    val data: JsonElement? = null,
) : RuntimeException(message)

```

**File: `core/model/src/main/kotlin/id/co/bri/brimons/core/model/exception/BaseException.kt`**
```kotlin
package id.co.bri.brimons.core.model.exception

/** Base class for custom exceptions, providing structured error details. */
abstract class BaseException : Exception() {

    /** Unique error code for this exception. */
    abstract val code: String

    /** Title for UI (dialog, bottom sheet, full screen). */
    open val title: String? = null

    /** Description for UI (human-readable). */
    abstract val description: String

    /** Optional image for UI (banner, helper, empty state, etc.). */
    open val image: String? = null

    override val message: String? = null

    /** Generates a unique error code based on predefined error code. */
    fun generatedCode(code: String) = code
}

```

**File: `core/model/src/main/kotlin/id/co/bri/brimons/core/model/exception/ExceptionHandler.kt`**
```kotlin
package id.co.bri.brimons.core.model.exception

import id.co.bri.brimons.core.model.exception.ExceptionMapper.toMappedException

/**
 * Represents an exception handler with a predicate to determine if the handler should be applied
 * and a transform function to convert the exception.
 *
 * @property predicate A suspending function that checks if the handler should handle the exception.
 * @property transform A function that transforms the original exception into a custom one.
 */
data class ExceptionHandler(
    val predicate: suspend (Throwable) -> Boolean,
    val transform: (Throwable) -> BaseException,
)

/**
 * Runs [block] and wraps its outcome in a [Result], mapping any thrown exception to a
 * [BaseException] via [toMappedException]. Used by the Result-based use cases.
 */
suspend fun <T> processResult(
    mapper: List<ExceptionHandler>? = null,
    block: suspend () -> T,
): Result<T> =
    try {
        Result.success(block())
    } catch (e: Throwable) {
        Result.failure(e.toMappedException(mapper))
    }

```

**File: `core/model/src/main/kotlin/id/co/bri/brimons/core/model/exception/ExceptionMapper.kt`**
```kotlin
package id.co.bri.brimons.core.model.exception

import id.co.bri.brimons.core.model.exception.dynamic.DynamicErrorApiException
import id.co.bri.brimons.core.model.exception.dynamic.DynamicErrorType
import id.co.bri.brimons.core.model.exception.event.AppEvent
import id.co.bri.brimons.core.model.exception.event.GlobalEventDispatcher
import id.co.bri.brimons.core.model.response.ResponseCode

/**
 * Maps a throwable to a custom exception using the provided handlers.
 *
 * @param mapper A list of exception handlers.
 * @return A transformed exception if a handler matches; otherwise, a [GeneralApiException].
 */
object ExceptionMapper {

    suspend fun handleGlobalEvent(exception: BaseException) {
        if (exception !is GeneralApiException) return
        when (exception.code) {
            ResponseCode.SESSION_END.code ->
                GlobalEventDispatcher.emit(AppEvent.SessionEnd(exception.description))
            ResponseCode.ALERT_FINISH.code ->
                GlobalEventDispatcher.emit(
                    AppEvent.AlertFinish(exception.title, exception.description)
                )
            ResponseCode.CHANGE_DEVICE.code -> GlobalEventDispatcher.emit(AppEvent.ChangeDevice)
            ResponseCode.LIMIT_EXCEPTION.code ->
                GlobalEventDispatcher.emit(
                    AppEvent.LimitException(exception.title, exception.description)
                )
            ResponseCode.DELETE_USER.code ->
                GlobalEventDispatcher.emit(AppEvent.DeleteUser(exception))
            ResponseCode.MAINTENANCE.code ->
                GlobalEventDispatcher.emit(
                    AppEvent.Maintenance(exception.title, exception.description)
                )
            ResponseCode.LIMIT_HIT.code ->
                GlobalEventDispatcher.emit(
                    AppEvent.LimitHit(exception.title, exception.description, exception.image)
                )
            ResponseCode.NETWORK_IO.code ->
                GlobalEventDispatcher.emit(AppEvent.NetworkError(exception.retry))
            ResponseCode.GENERAL.code -> {
                GlobalEventDispatcher.emit(
                    AppEvent.GeneralError(
                        exception.title,
                        exception.description,
                        exception.image,
                        exception.retry,
                        exception.onDismiss,
                    )
                )
            }
        }
    }

    suspend fun handleDynamicError(
        exception: DynamicErrorApiException,
        retry: (() -> Unit)? = null,
        onDismiss: (() -> Unit)? = null,
        onAction: ((String) -> Unit)? = null,
    ) {
        when (exception.errorType) {
            DynamicErrorType.SNACKBAR -> {
                GlobalEventDispatcher.emit(AppEvent.DynamicError.Snackbar(exception.description))
            }
            DynamicErrorType.BOTTOM_SHEET -> {
                GlobalEventDispatcher.emit(
                    AppEvent.DynamicError.BottomSheet(
                        exception = exception,
                        retry = retry,
                        onDismiss = onDismiss,
                        onAction = onAction,
                    )
                )
            }
            else -> Unit
        }
    }

    suspend fun Throwable.toMappedException(mapper: List<ExceptionHandler>? = null): BaseException {
        if (this is BaseException) return this

        if (NetworkException.isNetworkThrowable(this)) {
            return GeneralApiException(code = ResponseCode.NETWORK_IO.code, description = "")
        }

        GeneralApiException.handler
            .takeIf { it.predicate(this) }
            ?.transform
            ?.invoke(this)
            ?.let {
                return it
            }

        DynamicErrorApiException.handler
            .takeIf { it.predicate(this) }
            ?.transform
            ?.invoke(this)
            ?.let {
                return it
            }

        mapper
            ?.firstOrNull { it.predicate(this) }
            ?.transform
            ?.invoke(this)
            ?.let {
                return it
            }

        return GeneralApiException(
            code = ResponseCode.GENERAL.code,
            description = message.orEmpty(),
        )
    }
}

```

**File: `core/model/src/main/kotlin/id/co/bri/brimons/core/model/exception/GeneralApiException.kt`**
```kotlin
package id.co.bri.brimons.core.model.exception

import id.co.bri.brimons.core.model.response.EmptyStateResponse
import id.co.bri.brimons.core.model.response.ResponseCode
import id.co.bri.brimons.core.model.result.EmptyStateResult
import id.co.bri.brimons.core.util.ext.json
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.decodeFromJsonElement

class GeneralApiException(
    override val code: String,
    override val description: String,
    override val title: String? = null,
    override val image: String? = null,
    val data: JsonElement? = null,
    val retry: (() -> Unit)? = null,
    val onDismiss: (() -> Unit)? = null,
) : BaseException() {

    companion object {
        private val HANDLED_CODES =
            setOf(
                ResponseCode.ALERT_FINISH.code,
                ResponseCode.SESSION_END.code,
                ResponseCode.LIMIT_EXCEPTION.code,
                ResponseCode.GENERAL.code,
                ResponseCode.NOT_FOUND.code,
                ResponseCode.LIMIT_HIT.code,
                ResponseCode.CHANGE_DEVICE.code,
                ResponseCode.DELETE_USER.code,
                ResponseCode.MAINTENANCE.code,
                ResponseCode.NETWORK_IO.code,
            )

        val handler =
            ExceptionHandler(
                predicate = { it is ApiErrorException && it.code in HANDLED_CODES },
                transform = { error ->
                    when (error) {
                        is ApiErrorException -> {
                            if (error.code == ResponseCode.NOT_FOUND.code) {
                                error.toNotFoundApiException()
                            } else {
                                error.toGeneralApiException()
                            }
                        }

                        else -> error as BaseException
                    }
                },
            )
    }
}

class NotFoundApiException(
    override val code: String,
    override val description: String,
    val emptyState: EmptyStateResult,
) : BaseException()

private fun ApiErrorException.toNotFoundApiException(): NotFoundApiException {
    val emptyState = data.decodeOrNull<EmptyStateResponse>()?.map() ?: EmptyStateResult()

    return NotFoundApiException(
        code = code.orEmpty(),
        description = message,
        emptyState = emptyState,
    )
}

private fun ApiErrorException.toGeneralApiException(): GeneralApiException {
    val payload = data.decodeOrNull<GeneralErrorPayload>() ?: GeneralErrorPayload()

    return GeneralApiException(
        code = code.orEmpty(),
        title = payload.title,
        description = payload.normalizedDescription.ifBlank { message },
        image = payload.image,
        data = data,
    )
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
private data class GeneralErrorPayload(
    val title: String? = null,
    val description: String? = null,
    val message: String? = null,
    @JsonNames("image_url", "imageUrl", "image_path", "imagePath") val image: String? = null,
) {
    val normalizedDescription: String
        get() = description.orEmpty().ifBlank { message.orEmpty() }
}

fun GeneralApiException.copyActions(
    retry: (() -> Unit)?,
    onDismiss: (() -> Unit)?,
): GeneralApiException {
    return GeneralApiException(
        code = code,
        description = description,
        title = title,
        image = image,
        data = data,
        retry = retry,
        onDismiss = onDismiss,
    )
}

fun GeneralApiException.copyRetry(retry: (() -> Unit)?): GeneralApiException {
    return copyActions(retry = retry, onDismiss = onDismiss)
}

private inline fun <reified T> JsonElement?.decodeOrNull(): T? {
    val element = this ?: return null
    return runCatching { json.decodeFromJsonElement<T>(element) }.getOrNull()
}

```

**File: `core/model/src/main/kotlin/id/co/bri/brimons/core/model/exception/NetworkException.kt`**
```kotlin
package id.co.bri.brimons.core.model.exception

import id.co.bri.brimons.core.model.response.ResponseCode
import java.net.ConnectException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Thrown when a call fails due to a network-level problem.
 *
 * Covers all types OkHttp can throw, including okio 1.x which does NOT extend java.io.IOException:
 * - [UnknownHostException] — DNS resolution failure
 * - [ConnectException] — could not reach the host
 * - [java.net.SocketTimeoutException] — connection or read timed out
 * - [java.net.SocketException] — socket closed/reset mid-request
 * - [SSLException] — TLS/SSL handshake or cert failure
 * - [java.io.IOException] — other JDK I/O failures
 *
 * UI layers should use [Throwable.isNetworkError] to check for this condition.
 */
class NetworkException(
    override val code: String = ResponseCode.NETWORK_IO.code,
    override val description: String = "",
    cause: Throwable? = null,
) : BaseException() {
    init {
        cause?.let { initCause(it) }
    }

    companion object {
        /**
         * Returns true for any throwable that originates from a network-level failure.
         *
         * Listed explicitly because okio 1.x's [okio.IOException] does NOT extend
         * [java.io.IOException], so a single type check would miss it.
         */
        fun isNetworkThrowable(t: Throwable): Boolean =
            t is UnknownHostException ||
                t is ConnectException ||
                t is java.net.SocketTimeoutException ||
                t is java.net.SocketException ||
                t is SSLException ||
                t is java.io.IOException
    }
}

/**
 * Returns true when this throwable represents a network-level failure.
 *
 * Handles both the new DSL path (already wrapped as [NetworkException]) and the legacy path (raw
 * exceptions not yet mapped).
 */
fun Throwable?.isNetworkError(): Boolean =
    this is NetworkException || (this != null && NetworkException.isNetworkThrowable(this))

```

**File: `core/model/src/main/kotlin/id/co/bri/brimons/core/model/exception/dynamic/DynamicErrorActionButton.kt`**
```kotlin
package id.co.bri.brimons.core.model.exception.dynamic

data class DynamicErrorActionButton(
    val title: String,
    val action: String,
    val type: String? = null,
) {
    companion object {
        const val BACK_TO_HOME = "BACK_TO_HOME"
        const val CLOSE = "CLOSE"
        const val REFRESH = "REFRESH"
        const val BACK_TO_LOGIN = "BACK_TO_LOGIN"
    }
}

```

**File: `core/model/src/main/kotlin/id/co/bri/brimons/core/model/exception/dynamic/DynamicErrorApiException.kt`**
```kotlin
package id.co.bri.brimons.core.model.exception.dynamic

import id.co.bri.brimons.core.model.exception.ApiErrorException
import id.co.bri.brimons.core.model.exception.BaseException
import id.co.bri.brimons.core.model.exception.ExceptionHandler
import id.co.bri.brimons.core.model.response.ResponseCode
import id.co.bri.brimons.core.util.ext.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

data class DynamicErrorApiException(
    override val code: String,
    val errorType: DynamicErrorType,
    override val title: String,
    override val description: String,
    override val image: String? = null,
    val imageName: String? = null,
    val errorCode: String? = null,
    val dismissable: Boolean = true,
    val buttons: List<DynamicErrorActionButton> = emptyList(),
) : BaseException() {

    companion object {
        internal val handler =
            ExceptionHandler(
                predicate = {
                    it is ApiErrorException && it.code == ResponseCode.GENERAL_ERROR.code
                },
                transform = {
                    val exception = it as ApiErrorException
                    exception.toDynamicErrorApiException()
                },
            )
    }
}

fun List<DynamicErrorActionButton>.primaryOrNull(): DynamicErrorActionButton? {
    return firstOrNull { it.type == "primary" } ?: firstOrNull()
}

fun List<DynamicErrorActionButton>.outlineOrNull(): DynamicErrorActionButton? {
    return firstOrNull { it.type == "outline" } ?: firstOrNull()
}

@Serializable
private data class DynamicErrorResponse(
    @SerialName("error_type") val errorType: String? = null,
    @SerialName("image_name") val imageName: String? = null,
    @SerialName("image_path") val imagePath: String? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("error_code") val errorCode: String? = null,
    @SerialName("is_dismissable") val isDismissable: Boolean = true,
    @SerialName("rc") val rc: String? = null,
    @SerialName("buttons") val buttons: List<ErrorActionButtonResponse> = emptyList(),
) {
    val parsedErrorType: DynamicErrorType
        get() = DynamicErrorType(errorType)

    val isValid: Boolean
        get() =
            parsedErrorType in DynamicErrorType.entries.filter { it != DynamicErrorType.UNKNOWN }
}

@Serializable
private data class ErrorActionButtonResponse(
    @SerialName("title") val title: String,
    @SerialName("action") val action: String,
    @SerialName("type") val type: String? = null,
)

fun ApiErrorException.toDynamicErrorApiException(): DynamicErrorApiException {
    val response = data.decodeOrNull<DynamicErrorResponse>()?.takeIf { it.isValid }
    return DynamicErrorApiException(
        code = response?.rc ?: code.orEmpty(),
        errorType = response?.parsedErrorType ?: DynamicErrorType.UNKNOWN,
        title = response?.title.orEmpty(),
        description = response?.description ?: message,
        image = response?.imagePath,
        imageName = response?.imageName,
        errorCode = response?.errorCode,
        dismissable = response?.isDismissable ?: true,
        buttons =
            response?.buttons?.map {
                DynamicErrorActionButton(title = it.title, action = it.action, type = it.type)
            } ?: emptyList(),
    )
}

private inline fun <reified T> JsonElement?.decodeOrNull(): T? {
    return runCatching { this?.let { json.decodeFromJsonElement<T>(it) } }.getOrNull()
}

```

**File: `core/model/src/main/kotlin/id/co/bri/brimons/core/model/exception/dynamic/DynamicErrorType.kt`**
```kotlin
package id.co.bri.brimons.core.model.exception.dynamic

enum class DynamicErrorType(val value: String) {
    BOTTOM_SHEET("BOTTOM_SHEET"),
    SNACKBAR("SNACKBAR"),
    TEXT("TEXT"),
    UNKNOWN("");

    companion object {
        operator fun invoke(value: String?): DynamicErrorType =
            entries.find { it.value == value } ?: UNKNOWN
    }
}

```

**File: `core/model/src/main/kotlin/id/co/bri/brimons/core/model/exception/event/AppEvent.kt`**
```kotlin
package id.co.bri.brimons.core.model.exception.event

import id.co.bri.brimons.core.model.exception.GeneralApiException
import id.co.bri.brimons.core.model.exception.dynamic.DynamicErrorApiException

sealed class AppEvent {
    data class SessionEnd(val message: String) : AppEvent()

    data class AlertFinish(val title: String? = null, val message: String? = null) : AppEvent()

    data class DeleteUser(val error: GeneralApiException) : AppEvent()

    data class Maintenance(val title: String? = null, val message: String? = null) : AppEvent()

    data object ChangeDevice : AppEvent()

    data class LimitException(val title: String? = null, val message: String? = null) : AppEvent()

    data class LimitHit(
        val title: String? = null,
        val message: String? = null,
        val image: String? = null,
    ) : AppEvent()

    data class GeneralError(
        val title: String? = null,
        val message: String? = null,
        val image: String? = null,
        val retry: (() -> Unit)? = null,
        val onDismiss: (() -> Unit)? = null,
    ) : AppEvent()

    data class NetworkError(val retry: (() -> Unit)? = null) : AppEvent()

    sealed class DynamicError : AppEvent() {

        data class BottomSheet(
            val exception: DynamicErrorApiException,
            val retry: (() -> Unit)? = null,
            val onDismiss: (() -> Unit)? = null,
            val onAction: ((String) -> Unit)? = null,
        ) : DynamicError()

        data class Snackbar(val message: String) : DynamicError()
    }
}

```

**File: `core/model/src/main/kotlin/id/co/bri/brimons/core/model/exception/event/GlobalEventDispatcher.kt`**
```kotlin
package id.co.bri.brimons.core.model.exception.event

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object GlobalEventDispatcher {

    private val _events = MutableSharedFlow<AppEvent>(replay = 1, extraBufferCapacity = 1)
    val events = _events.asSharedFlow()

    suspend fun emit(event: AppEvent) {
        _events.emit(event)
    }

    fun clearReplay() {
        _events.resetReplayCache()
    }
}

```

**File: `core/model/src/main/kotlin/id/co/bri/brimons/core/model/general/mapper/BillingItemMapper.kt`**
```kotlin
package id.co.bri.brimons.core.model.general.mapper

import id.co.bri.brimons.core.model.general.model.BillingItemModel
import id.co.bri.brimons.core.model.general.response.BillingItemResponse

fun BillingItemResponse?.asBillingItemModel(): BillingItemModel {
    return BillingItemModel(
        iconPath = this?.iconPath.orEmpty(),
        title = this?.title.orEmpty(),
        subtitle = this?.subtitle.orEmpty(),
        description = this?.description.orEmpty(),
    )
}

```

**File: `core/model/src/main/kotlin/id/co/bri/brimons/core/model/general/mapper/DataItemMapper.kt`**
```kotlin
package id.co.bri.brimons.core.model.general.mapper

import id.co.bri.brimons.core.model.general.model.DataItemModel
import id.co.bri.brimons.core.model.general.response.DataItemResponse

fun DataItemResponse?.asDataItemModel(): DataItemModel {
    return DataItemModel(
        name = this?.name.orEmpty(),
        value = this?.value.orEmpty(),
        style = this?.style.orEmpty(),
    )
}

```

**File: `core/model/src/main/kotlin/id/co/bri/brimons/core/model/general/model/BillingItemModel.kt`**
```kotlin
package id.co.bri.brimons.core.model.general.model

import kotlinx.serialization.Serializable

@Serializable
data class BillingItemModel(
    val iconPath: String,
    val title: String,
    val subtitle: String,
    val description: String,
)

```

**File: `core/model/src/main/kotlin/id/co/bri/brimons/core/model/general/model/DataItemModel.kt`**
```kotlin
package id.co.bri.brimons.core.model.general.model

import kotlinx.serialization.Serializable

@Serializable data class DataItemModel(val name: String, val value: String, val style: String)

```

**File: `core/model/src/main/kotlin/id/co/bri/brimons/core/model/general/model/FavoriteItemModel.kt`**
```kotlin
package id.co.bri.brimons.core.model.general.model

import kotlinx.serialization.Serializable

@Serializable
data class FavoriteItemModel(
    val icon: String,
    val title: String,
    val subtitle: String,
    val description: String,
    val savedId: String = "",
    val productId: String = "",
    val number: String = "",
    val badge: String = "",
    val favorite: Boolean = false,
    val bind: Boolean = false,
)

```

**File: `core/model/src/main/kotlin/id/co/bri/brimons/core/model/general/model/HistoryItemModel.kt`**
```kotlin
package id.co.bri.brimons.core.model.general.model

import kotlinx.serialization.Serializable

@Serializable
data class HistoryItemModel(
    val icon: String,
    val title: String,
    val subtitle: String,
    val description: String,
    val productId: String = "",
    val number: String = "",
    val value: String = "",
    val amount: String = "",
)

```

**File: `core/model/src/main/kotlin/id/co/bri/brimons/core/model/general/model/TransactionItemModel.kt`**
```kotlin
package id.co.bri.brimons.core.model.general.model

import kotlinx.serialization.Serializable

@Serializable
data class TransactionItemModel(
    val dataView: List<DataItemModel>,
    val title: String? = null,
    val hidden: Boolean = false,
)

```

**File: `core/model/src/main/kotlin/id/co/bri/brimons/core/model/general/response/BillingItemResponse.kt`**
```kotlin
package id.co.bri.brimons.core.model.general.response

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BillingItemResponse(
    @SerialName("icon_path") val iconPath: String?,
    @SerialName("title") val title: String?,
    @SerialName("subtitle") val subtitle: String?,
    @SerialName("description") val description: String?,
)

```

**File: `core/model/src/main/kotlin/id/co/bri/brimons/core/model/general/response/DataItemResponse.kt`**
```kotlin
package id.co.bri.brimons.core.model.general.response

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DataItemResponse(
    @SerialName("name") val name: String?,
    @SerialName("value") val value: String?,
    @SerialName("style") val style: String?,
)

```

**File: `core/model/src/main/kotlin/id/co/bri/brimons/core/model/mapper/AmountMapper.kt`**
```kotlin
package id.co.bri.brimons.core.model.mapper

import id.co.bri.brimons.core.model.AmountModel
import id.co.bri.brimons.core.model.response.AmountResponse

fun AmountResponse?.asAmountModel(): AmountModel {
    return AmountModel(text = this?.text.orEmpty(), value = this?.value ?: 0)
}

```

**File: `core/model/src/main/kotlin/id/co/bri/brimons/core/model/mapper/BillingMapper.kt`**
```kotlin
package id.co.bri.brimons.core.model.mapper

import id.co.bri.brimons.core.model.BillingModel
import id.co.bri.brimons.core.model.response.BillingResponse

fun BillingResponse?.asBillingModel(): BillingModel {
    return BillingModel(
        iconPath = this?.iconPath.orEmpty(),
        title = this?.title.orEmpty(),
        subtitle = this?.subtitle.orEmpty(),
        description = this?.description.orEmpty(),
    )
}

```

**File: `core/model/src/main/kotlin/id/co/bri/brimons/core/model/mapper/DataViewMapper.kt`**
```kotlin
package id.co.bri.brimons.core.model.mapper

import id.co.bri.brimons.core.model.DataViewModel
import id.co.bri.brimons.core.model.response.DataViewResponse

fun DataViewResponse?.asDataViewModel(): DataViewModel {
    return DataViewModel(
        name = this?.name.orEmpty(),
        value = this?.value.orEmpty(),
        style = this?.style.orEmpty(),
    )
}

```

**File: `core/model/src/main/kotlin/id/co/bri/brimons/core/model/mapper/PopupMapper.kt`**
```kotlin
package id.co.bri.brimons.core.model.mapper

import id.co.bri.brimons.core.model.PopupModel
import id.co.bri.brimons.core.model.response.PopupResponse

fun PopupResponse?.asPopupModel(): PopupModel {
    return PopupModel(title = this?.title.orEmpty(), description = this?.description.orEmpty())
}

```

**File: `core/model/src/main/kotlin/id/co/bri/brimons/core/model/receipt/AssetAllocationItem.kt`**
```kotlin
package id.co.bri.brimons.core.model.receipt

import kotlinx.serialization.Serializable

/** Represents asset allocation item for investment receipts. */
@Serializable
data class AssetAllocationItem(
    val assetName: String,
    val allocationPercentage: String,
    val amount: String = "",
    val iconUrl: String = "",
)

```

**File: `core/model/src/main/kotlin/id/co/bri/brimons/core/model/receipt/BulkPaymentItem.kt`**
```kotlin
package id.co.bri.brimons.core.model.receipt

import kotlinx.serialization.Serializable

/** Represents a single payment item in bulk payment sections. */
@Serializable
data class BulkPaymentItem(
    val title: String,
    val subtitle: String = "",
    val amount: String,
    val status: ReceiptStatus = ReceiptStatus.SUCCESS,
    val iconUrl: String = "",
    /** Additional details to show when expanded */
    val details: List<ReceiptDetailItem> = emptyList(),
)

```

**File: `core/model/src/main/kotlin/id/co/bri/brimons/core/model/receipt/ReceiptAccountModel.kt`**
```kotlin
package id.co.bri.brimons.core.model.receipt

import kotlinx.serialization.Serializable

/**
 * Represents account information displayed in transaction sections. Used for both source and
 * destination accounts.
 */
@Serializable
data class ReceiptAccountModel(
    val iconUrl: String = "",
    val title: String = "",
    val subtitle: String = "",
    val description: String = "",
) {
    companion object {
        val EMPTY = ReceiptAccountModel()
    }
}

```

**File: `core/model/src/main/kotlin/id/co/bri/brimons/core/model/receipt/ReceiptActionType.kt`**
```kotlin
package id.co.bri.brimons.core.model.receipt

import kotlinx.serialization.Serializable

/** Represents types of interactive actions that can be triggered from receipt sections. */
@Serializable
enum class ReceiptActionType {
    /** Gold Bundle action - triggers postGoldBundleReceipt API */
    GOLD_BUNDLE,
    /** Redeem voucher action */
    REDEEM_VOUCHER,
    /** Copy to clipboard action */
    COPY,
    /** Custom action with feature-specific handling */
    CUSTOM,
}

```

**File: `core/model/src/main/kotlin/id/co/bri/brimons/core/model/receipt/ReceiptAnalyticEvent.kt`**
```kotlin
package id.co.bri.brimons.core.model.receipt

/**
 * Analytic events emitted by the shared receipt screen.
 *
 * The shared module does not track analytics itself — it delegates to the feature by invoking
 * [onAnalytic] callback passed from the caller. Each feature maps these events to its own event
 * names and parameters.
 *
 * Usage in feature:
 * ```kotlin
 * receiptNavigation.navigateToReceipt(
 *     context = context,
 *     receipt = receiptUiModel,
 *     onAnalytic = { event ->
 *         when (event) {
 *             ReceiptAnalyticEvent.ScreenOpened ->
 *                 TransferTrackerEvent.TRANSFER_RECEIPT_OPEN.track()
 *             ReceiptAnalyticEvent.ShareTapped ->
 *                 TransferTrackerEvent.TRANSFER_RECEIPT_SHARE.track()
 *             ReceiptAnalyticEvent.DownloadTapped ->
 *                 TransferTrackerEvent.TRANSFER_RECEIPT_DOWNLOAD.track()
 *             ReceiptAnalyticEvent.FinishTapped ->
 *                 TransferTrackerEvent.TRANSFER_RECEIPT_CLOSE.track()
 *             ReceiptAnalyticEvent.HelpCenterTapped ->
 *                 TransferTrackerEvent.TRANSFER_RECEIPT_HELP_CENTER.track()
 *             is ReceiptAnalyticEvent.ActionTapped ->
 *                 if (event.actionType == ReceiptActionType.GOLD_BUNDLE)
 *                     TransferTrackerEvent.TRANSFER_RECEIPT_GOLD_BUNDLE.track()
 *         }
 *     }
 * )
 * ```
 */
sealed interface ReceiptAnalyticEvent {

    /** Receipt screen is visible to the user for the first time. */
    data object ScreenOpened : ReceiptAnalyticEvent

    /** Share button tapped. */
    data object ShareTapped : ReceiptAnalyticEvent

    /** Download button tapped. */
    data object DownloadTapped : ReceiptAnalyticEvent

    /** Primary finish button tapped (e.g., "Selesai"). */
    data object FinishTapped : ReceiptAnalyticEvent

    /** Help center button tapped. */
    data object HelpCenterTapped : ReceiptAnalyticEvent

    /** An action card was tapped (e.g., Gold Bundle). */
    data class ActionTapped(val actionType: ReceiptActionType, val actionData: String) :
        ReceiptAnalyticEvent
}

```

**File: `core/model/src/main/kotlin/id/co/bri/brimons/core/model/receipt/ReceiptConfig.kt`**
```kotlin
package id.co.bri.brimons.core.model.receipt

import kotlinx.serialization.Serializable

/**
 * Configuration options for receipt screen behavior and appearance.
 *
 * Usage:
 * ```kotlin
 * // Default - all buttons visible
 * config = ReceiptConfig.DEFAULT
 *
 * // Custom configuration
 * config = ReceiptConfig(
 *     showHelpCenter = false,
 *     primaryButtonLabel = "Kembali"
 * )
 *
 * // Or start from default and customize
 * config = ReceiptConfig.DEFAULT.copy(
 *     showShareButton = false,
 *     primaryButtonLabel = "Selesai"
 * )
 * ```
 */
@Serializable
data class ReceiptConfig(
    val showShareButton: Boolean = true,
    val showDownloadButton: Boolean = true,
    val primaryButtonLabel: String = "Selesai",
    val showHelpCenter: Boolean = false,
    /** Navigate to history after finish */
    val toHistory: Boolean = false,
    /** Whether triggered from fast menu */
    val isFastMenu: Boolean = false,
    /**
     * Controls finish navigation behavior.
     * - `true`: Navigate to root screen (Dashboard or FastMenu based on [isFastMenu])
     * - `false`: Just close the activity (returns to previous screen)
     */
    val backToRoot: Boolean = true,
) {
    companion object {
        val DEFAULT = ReceiptConfig()
    }
}

```

**File: `core/model/src/main/kotlin/id/co/bri/brimons/core/model/receipt/ReceiptDetailItem.kt`**
```kotlin
package id.co.bri.brimons.core.model.receipt

import kotlinx.serialization.Serializable

/** Represents a single key-value item in receipt detail sections. */
@Serializable
data class ReceiptDetailItem(
    val label: String,
    val value: String,
    val style: ReceiptTextStyle = ReceiptTextStyle.NORMAL,
    /** Optional title/header text above the item */
    val title: String = "",
    /** Whether this item can be copied to clipboard */
    val isCopyable: Boolean = false,
)

```

**File: `core/model/src/main/kotlin/id/co/bri/brimons/core/model/receipt/ReceiptFooterModel.kt`**
```kotlin
package id.co.bri.brimons.core.model.receipt

import kotlinx.serialization.Serializable

/**
 * Footer model for receipt screen.
 *
 * Structure:
 * - Footer texts: [customFooter], displayed via BRIContainerReceiptFooter
 * - Copyright: displayed below the card
 *
 * Usage:
 * ```kotlin
 * // Default footer - uses DS call center + legal only
 * val receipt = ReceiptUiModel(
 *     ...,
 *     footer = ReceiptFooterModel()
 * )
 *
 * // With custom footer info
 * // you should add call center + legal + [your content]
 * val receipt = ReceiptUiModel(
 *     ...,
 *     footer = ReceiptFooterModel(
 *         customFooter = listOf(
 *             "Disclaimer: This is a disclaimer text",
 *             "Regulated by OJK"
 *         )
 *     )
 * )
 *
 * // Custom copyright
 * val receipt = ReceiptUiModel(
 *     ...,
 *     footer = ReceiptFooterModel(
 *         copyright = "© 2024 Custom Company"
 *     )
 * )
 * ```
 */
@Serializable
data class ReceiptFooterModel(
    /**
     * Custom footer info. Each item is displayed as a separate line, centered. add also call
     * center + legal
     */
    val customFooter: List<String> = emptyList(),

    /**
     * Copyright text displayed below the card. When empty, falls back to the string resource in
     * presentation layer.
     */
    val copyright: String = "",
)

```

**File: `core/model/src/main/kotlin/id/co/bri/brimons/core/model/receipt/ReceiptHeaderModel.kt`**
```kotlin
package id.co.bri.brimons.core.model.receipt

import kotlinx.serialization.Serializable

/** Header model containing transaction summary displayed at the top of receipt. */
@Serializable
data class ReceiptHeaderModel(
    val title: String = "",
    val amount: String = "",
    /** Optional conversion text (e.g., "~ 0.5 gram emas") */
    val conversionText: String = "",
    val timestamp: String = "",
    val status: ReceiptStatus = ReceiptStatus.PENDING,
)

```

**File: `core/model/src/main/kotlin/id/co/bri/brimons/core/model/receipt/ReceiptSection.kt`**
```kotlin
package id.co.bri.brimons.core.model.receipt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Sealed interface representing different types of sections that can appear on a receipt. Each
 * section type handles its own rendering logic and data requirements.
 */
@Serializable
sealed interface ReceiptSection {

    /**
     * Transaction section showing source and destination accounts. Used for transfers, payments,
     * and other transaction types.
     */
    @Serializable
    @SerialName("transaction")
    data class TransactionSection(
        val title: String = "",
        val source: ReceiptAccountModel,
        val destination: ReceiptAccountModel,
    ) : ReceiptSection

    /**
     * Detail section showing key-value pairs. Supports expandable/collapsible behavior for long
     * lists.
     */
    @Serializable
    @SerialName("detail")
    data class DetailSection(val title: String = "", val items: List<ReceiptDetailItem>) :
        ReceiptSection

    /** Voucher section for displaying voucher codes with copy functionality. */
    @Serializable
    @SerialName("voucher")
    data class VoucherSection(
        val label: String,
        val code: String,
        val isCopyable: Boolean = true,
        val redeemInstructions: String? = null,
    ) : ReceiptSection

    /**
     * Interactive action card section that triggers callbacks. Used for Gold Bundle and similar
     * features.
     */
    @Serializable
    @SerialName("action_card")
    data class ActionCardSection(
        val iconUrl: String = "",
        val title: String,
        val subtitle: String = "",
        val actionType: ReceiptActionType,
        /** JSON-encoded action data passed to the callback handler */
        val actionData: String = "",
    ) : ReceiptSection

    /** Bulk payment section showing multiple payment items. */
    @Serializable
    @SerialName("bulk_payment")
    data class BulkPaymentSection(val title: String = "", val payments: List<BulkPaymentItem>) :
        ReceiptSection

    /** Asset allocation section for investment receipts. */
    @Serializable
    @SerialName("asset_allocation")
    data class AssetAllocationSection(
        val title: String = "",
        val items: List<AssetAllocationItem>,
    ) : ReceiptSection

    /**
     * Custom section for feature-specific content. Renders raw content without specific structure.
     */
    @Serializable
    @SerialName("custom")
    data class CustomSection(
        val title: String = "",
        val contentType: String,
        val contentData: String,
    ) : ReceiptSection
}

```

**File: `core/model/src/main/kotlin/id/co/bri/brimons/core/model/receipt/ReceiptStatus.kt`**
```kotlin
package id.co.bri.brimons.core.model.receipt

import kotlinx.serialization.Serializable

/** Represents the status of a receipt transaction. */
@Serializable
enum class ReceiptStatus {
    SUCCESS,
    PENDING,
    FAILED,
    PROCESSING,
}

```

**File: `core/model/src/main/kotlin/id/co/bri/brimons/core/model/receipt/ReceiptTextStyle.kt`**
```kotlin
package id.co.bri.brimons.core.model.receipt

import kotlinx.serialization.Serializable

/**
 * Represents text styling options for receipt detail items.
 *
 * Only styles supported by the Design System's BRICardDetailTransaction are included:
 * - NORMAL: Default text appearance
 * - BOLD: Semi-bold text with darker label
 * - GREEN: Green text color
 * - RED: Red text color
 */
@Serializable
enum class ReceiptTextStyle {
    NORMAL,
    BOLD,
    GREEN,
    RED;

    companion object {
        /**
         * Parses backend style string to enum. Supports common variations: "bold",
         * "success"/"green", "error"/"red"/"danger"
         */
        fun fromString(style: String?): ReceiptTextStyle {
            return when (style?.lowercase()) {
                "bold" -> BOLD
                "success",
                "green" -> GREEN
                "error",
                "red",
                "danger" -> RED
                else -> NORMAL
            }
        }
    }
}

```

**File: `core/model/src/main/kotlin/id/co/bri/brimons/core/model/receipt/ReceiptUiModel.kt`**
```kotlin
package id.co.bri.brimons.core.model.receipt

import kotlinx.serialization.Serializable

/**
 * Unified receipt UI model that any feature module can use.
 *
 * Features map their backend responses to this model and pass it via navigation. The shared receipt
 * screen renders this model dynamically based on the sections provided.
 *
 * ## Section Rendering
 * - [sections]: Always visible content (TransactionSection, DetailSection, etc.)
 * - [hiddenSections]: Expandable content shown when user taps "Lihat Detail"
 * - Non-detail sections (ActionCard, Voucher, etc.) from both lists render in footer area
 * - [BRIContainerReceiptContact] renders at the end of footer automatically
 *
 * ## Snapshot Behavior
 * For download/share, all sections from both [sections] and [hiddenSections] are rendered expanded
 * (no toggle).
 *
 * Usage:
 * ```kotlin
 * // In feature module - feature controls what's visible vs hidden
 * fun TransferPayResponse.toReceiptUiModel(): ReceiptUiModel {
 *     return ReceiptUiModel(
 *         header = ReceiptHeaderModel(
 *             title = title.orEmpty(),
 *             amount = totalDataView?.firstOrNull()?.value.orEmpty(),
 *             timestamp = dateTransaction.orEmpty(),
 *             status = if (onProcess == true) ReceiptStatus.PENDING else ReceiptStatus.SUCCESS
 *         ),
 *         // Always visible
 *         sections = buildList {
 *             add(ReceiptSection.TransactionSection(
 *                 source = sourceAccountDataView.toReceiptAccount(),
 *                 destination = billingDetail.toReceiptAccount()
 *             ))
 *             add(ReceiptSection.DetailSection(
 *                 title = "Detail Transaksi",
 *                 items = headerDataView.map { it.toReceiptDetailItem() }
 *             ))
 *         },
 *         // Expandable (shown on "Lihat Detail")
 *         hiddenSections = buildList {
 *             add(ReceiptSection.DetailSection(
 *                 title = "",
 *                 items = amountDataView.map { it.toReceiptDetailItem() }
 *             ))
 *             // ActionCard renders in footer when expanded
 *             add(ReceiptSection.ActionCardSection(...))
 *         },
 *         config = ReceiptConfig.DEFAULT
 *     )
 * }
 * ```
 */
@Serializable
data class ReceiptUiModel(
    /** Receipt header with title, amount, timestamp, status */
    val header: ReceiptHeaderModel = ReceiptHeaderModel(),
    /** Always visible sections */
    val sections: List<ReceiptSection> = emptyList(),
    /** Expandable sections shown when "Lihat Detail" is tapped */
    val hiddenSections: List<ReceiptSection> = emptyList(),
    /** Footer configuration (copyright, additional info) */
    val footer: ReceiptFooterModel = ReceiptFooterModel(),
    /** Receipt behavior configuration */
    val config: ReceiptConfig = ReceiptConfig.DEFAULT,
)

```

**File: `core/model/src/main/kotlin/id/co/bri/brimons/core/model/response/AmountResponse.kt`**
```kotlin
package id.co.bri.brimons.core.model.response

import com.google.gson.annotations.SerializedName

data class AmountResponse(
    @SerializedName("text") val text: String?,
    @SerializedName("value") val value: Int?,
)

```

**File: `core/model/src/main/kotlin/id/co/bri/brimons/core/model/response/BaseResponse.kt`**
```kotlin
package id.co.bri.brimons.core.model.response

import id.co.bri.brimons.core.model.result.Result

interface BaseResponse<out T : Response<R>, R : Result>

abstract class Response<T : Result> {
    abstract fun map(): T
}

@JvmName("mapResponse")
fun <R : Result, T : Response<R>, F> T.map(transform: (T) -> F): F {
    return transform(this)
}

@JvmName("mapListResponse")
fun <T : Response<R>, R : Result> List<T>.map(): List<R> {
    return this.map { response -> response.map() }
}

```

**File: `core/model/src/main/kotlin/id/co/bri/brimons/core/model/response/BillingResponse.kt`**
```kotlin
package id.co.bri.brimons.core.model.response

import com.google.gson.annotations.SerializedName

data class BillingResponse(
    @SerializedName("icon_path") val iconPath: String?,
    @SerializedName("title") val title: String?,
    @SerializedName("subtitle") val subtitle: String?,
    @SerializedName("description") val description: String?,
)

```

**File: `core/model/src/main/kotlin/id/co/bri/brimons/core/model/response/DataViewResponse.kt`**
```kotlin
package id.co.bri.brimons.core.model.response

import com.google.gson.annotations.SerializedName

data class DataViewResponse(
    @SerializedName("name") val name: String?,
    @SerializedName("value") val value: String?,
    @SerializedName("style") val style: String?,
)

```

**File: `core/model/src/main/kotlin/id/co/bri/brimons/core/model/response/EmptyStateResponse.kt`**
```kotlin
package id.co.bri.brimons.core.model.response

import id.co.bri.brimons.core.model.result.EmptyStateResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class EmptyStateResponse(
    @SerialName("image_name") val imageName: String? = null,
    @SerialName("image_path") val imagePath: String? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("sub_description") val subDescription: String? = null,
) : Response<EmptyStateResult>() {

    override fun map(): EmptyStateResult {
        return EmptyStateResult(
            imageName = imageName.orEmpty(),
            imagePath = imagePath.orEmpty(),
            title = title.orEmpty(),
            description = description.orEmpty(),
            subDescription = subDescription.orEmpty(),
        )
    }
}

```

**File: `core/model/src/main/kotlin/id/co/bri/brimons/core/model/response/FavoriteResponse.kt`**
```kotlin
package id.co.bri.brimons.core.model.response

import com.google.gson.annotations.SerializedName

data class FavoriteResponse(
    @SerializedName("icon_path") val iconPath: String?,
    @SerializedName("title") val title: String?,
    @SerializedName("subtitle") val subtitle: String?,
    @SerializedName("description") val description: String?,
    @SerializedName("value") val value: String?,
    @SerializedName("favorite") val favorite: Boolean?,
)

```

**File: `core/model/src/main/kotlin/id/co/bri/brimons/core/model/response/HistoryResponse.kt`**
```kotlin
package id.co.bri.brimons.core.model.response

import com.google.gson.annotations.SerializedName

data class HistoryResponse(
    @SerializedName("icon_path") val iconPath: String?,
    @SerializedName("title") val title: String?,
    @SerializedName("subtitle") val subtitle: String?,
    @SerializedName("description") val description: String?,
    @SerializedName("value") val value: String?,
    @SerializedName("value_amount") val valueAmount: String?,
)

```

**File: `core/model/src/main/kotlin/id/co/bri/brimons/core/model/response/PopupResponse.kt`**
```kotlin
package id.co.bri.brimons.core.model.response

import com.google.gson.annotations.SerializedName

data class PopupResponse(
    @SerializedName("title") val title: String?,
    @SerializedName("description") val description: String?,
)

```

**File: `core/model/src/main/kotlin/id/co/bri/brimons/core/model/response/ResponseCode.kt`**
```kotlin
package id.co.bri.brimons.core.model.response

/**
 * Defines various exception codes used throughout the application.
 *
 * @property code A string representation of the exception type.
 */
enum class ResponseCode(val code: String) {
    // Common
    SUCCESS("00"),

    // GENERAL
    ALERT_FINISH("93"),
    SESSION_END("05"),
    CHANGE_DEVICE("19"),
    LIMIT_EXCEPTION("61"),
    LIMIT_HIT("06"),
    MAINTENANCE("MT"),
    NOT_FOUND("NF"),
    GENERAL("12"),
    DELETE_USER("UD04"),
    NETWORK_IO("NET_IO"),
    GENERAL_ERROR("GE"),
    UNKNOWN("UNKNOWN"),
}

```

**File: `core/model/src/main/kotlin/id/co/bri/brimons/core/model/response/RestResponse.kt`**
```kotlin
package id.co.bri.brimons.core.model.response

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

@Serializable
data class RestResponse(
    @SerialName("code") val code: String,
    @SerialName("description") val desc: String,
    @SerialName("data") val data: JsonElement? = null,
) {
    inline fun <reified T> getData(): T =
        data?.let { Json.decodeFromJsonElement(it) } ?: error(desc)
}

```

**File: `core/model/src/main/kotlin/id/co/bri/brimons/core/model/result/EmptyStateResult.kt`**
```kotlin
package id.co.bri.brimons.core.model.result

data class EmptyStateResult(
    val imageName: String = "",
    val imagePath: String = "",
    val title: String = "",
    val description: String = "",
    val subDescription: String = "",
) : Result

```

**File: `core/model/src/main/kotlin/id/co/bri/brimons/core/model/result/Result.kt`**
```kotlin
package id.co.bri.brimons.core.model.result

interface Result

@JvmName("mapResult")
fun <T : Result, R> T.map(transform: (T) -> R): R {
    return transform(this)
}

```

**File: `core/model/src/test/kotlin/id/co/bri/brimons/core/model/exception/GeneralApiExceptionTest.kt`**
```kotlin
package id.co.bri.brimons.core.model.exception

import id.co.bri.brimons.core.model.response.ResponseCode
import id.co.bri.brimons.core.model.result.EmptyStateResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json

class GeneralApiExceptionTest :
    FunSpec({
        context("handler.predicate") {
            test("true for handled ApiErrorException codes") {
                GeneralApiException.handler
                    .predicate(ApiErrorException(code = "61", message = "message"))
                    .shouldBeTrue()
            }

            test("false for unknown ApiErrorException codes") {
                GeneralApiException.handler
                    .predicate(ApiErrorException(code = "00", message = "message"))
                    .shouldBeFalse()
            }

            test("false for non-ApiErrorException throwables") {
                GeneralApiException.handler
                    .predicate(IllegalStateException("message"))
                    .shouldBeFalse()
            }

            test("false when the ApiErrorException code is null") {
                GeneralApiException.handler
                    .predicate(ApiErrorException(code = null, message = "message"))
                    .shouldBeFalse()
            }
        }

        context("handler.transform - GeneralApiException path") {
            test("decodes title, description, and image from the data payload") {
                val data =
                    Json.parseToJsonElement(
                        """
                        {
                            "image_name": "image_name",
                            "image_path": "image_path",
                            "title": "title",
                            "description": "description",
                            "sub_description": "",
                            "type": "type"
                        }
                        """
                            .trimIndent()
                    )

                val result =
                    GeneralApiException.handler.transform(
                        ApiErrorException(code = "61", message = "message", data = data)
                    )

                result.shouldBeInstanceOf<GeneralApiException>()
                result.code shouldBe "61"
                result.title shouldBe "title"
                result.description shouldBe "description"
            }

            test("falls back to the ApiErrorException message when description is blank") {
                val data = Json.parseToJsonElement("""{"title":"title","description":""}""")

                val result =
                    GeneralApiException.handler.transform(
                        ApiErrorException(
                            code = ResponseCode.GENERAL.code,
                            message = "message",
                            data = data,
                        )
                    )

                result.shouldBeInstanceOf<GeneralApiException>()
                result.description shouldBe "message"
            }

            test("promotes `message` field to description when description is missing") {
                val data = Json.parseToJsonElement("""{"title":"title","message":"message"}""")

                val result =
                    GeneralApiException.handler.transform(
                        ApiErrorException(
                            code = ResponseCode.GENERAL.code,
                            message = "fallback",
                            data = data,
                        )
                    )

                result.shouldBeInstanceOf<GeneralApiException>()
                result.description shouldBe "message"
            }

            test("accepts image_url alias for the image field") {
                val data =
                    Json.parseToJsonElement("""{"description":"description","image_url":"image"}""")

                val result =
                    GeneralApiException.handler.transform(
                        ApiErrorException(
                            code = ResponseCode.GENERAL.code,
                            message = "message",
                            data = data,
                        )
                    )

                result.shouldBeInstanceOf<GeneralApiException>()
                result.image shouldBe "image"
            }

            test("tolerates a null data payload") {
                val result =
                    GeneralApiException.handler.transform(
                        ApiErrorException(
                            code = ResponseCode.GENERAL.code,
                            message = "message",
                            data = null,
                        )
                    )

                result.shouldBeInstanceOf<GeneralApiException>()
                result.code shouldBe ResponseCode.GENERAL.code
                result.description shouldBe "message"
                result.title.shouldBeNull()
                result.image.shouldBeNull()
            }

            test("recovers when the data payload is malformed") {
                val data = Json.parseToJsonElement("""["not","an","object"]""")

                val result =
                    GeneralApiException.handler.transform(
                        ApiErrorException(
                            code = ResponseCode.GENERAL.code,
                            message = "message",
                            data = data,
                        )
                    )

                result.shouldBeInstanceOf<GeneralApiException>()
                result.description shouldBe "message"
                result.title.shouldBeNull()
                result.image.shouldBeNull()
            }
        }

        context("handler.transform - NotFoundApiException path") {
            test("decodes empty_state payload") {
                val data =
                    Json.parseToJsonElement(
                        """
                        {
                            "image_name": "image_name",
                            "image_path": "image_path",
                            "title": "title",
                            "description": "description",
                            "sub_description": "sub_description"
                        }
                        """
                            .trimIndent()
                    )

                val result =
                    GeneralApiException.handler.transform(
                        ApiErrorException(
                            code = ResponseCode.NOT_FOUND.code,
                            message = "message",
                            data = data,
                        )
                    )

                result.shouldBeInstanceOf<NotFoundApiException>()
                result.code shouldBe ResponseCode.NOT_FOUND.code
                result.description shouldBe "message"
                result.emptyState.title shouldBe "title"
                result.emptyState.description shouldBe "description"
                result.emptyState.subDescription shouldBe "sub_description"
            }

            test("returns defaults when data is null") {
                val result =
                    GeneralApiException.handler.transform(
                        ApiErrorException(
                            code = ResponseCode.NOT_FOUND.code,
                            message = "message",
                            data = null,
                        )
                    )

                result.shouldBeInstanceOf<NotFoundApiException>()
                result.emptyState shouldBe EmptyStateResult()
            }
        }

        context("copyActions") {
            test("carries the injected retry/onDismiss lambdas") {
                val original =
                    GeneralApiException(
                        code = "code",
                        description = "description",
                        title = "title",
                        image = "image",
                    )
                var retried = false
                var dismissed = false

                val copy =
                    original.copyActions(
                        retry = { retried = true },
                        onDismiss = { dismissed = true },
                    )

                copy.code shouldBe original.code
                copy.description shouldBe original.description
                copy.title shouldBe original.title
                copy.image shouldBe original.image
                copy.retry?.invoke()
                copy.onDismiss?.invoke()
                retried.shouldBeTrue()
                dismissed.shouldBeTrue()
            }

            test("null actions produce a copy whose actions are null") {
                val copy =
                    GeneralApiException(code = "code", description = "description")
                        .copyActions(retry = null, onDismiss = null)

                copy.retry.shouldBeNull()
                copy.onDismiss.shouldBeNull()
            }
        }
    })

```

**File: `core/model/src/test/kotlin/id/co/bri/brimons/core/model/response/RestResponseTest.kt`**
```kotlin
package id.co.bri.brimons.core.model.response

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable private data class SamplePayload(val id: Int, val name: String)

class RestResponseTest :
    FunSpec({
        context("getData") {
            test("decodes the data JsonElement into the target type") {
                val response =
                    RestResponse(
                        code = "00",
                        desc = "desc",
                        data = Json.parseToJsonElement("""{"id":7,"name":"name"}"""),
                    )

                response.getData<SamplePayload>() shouldBe SamplePayload(id = 7, name = "name")
            }

            test("throws IllegalStateException carrying desc when data is null") {
                val response = RestResponse(code = "99", desc = "desc", data = null)

                val ex = shouldThrow<IllegalStateException> { response.getData<SamplePayload>() }
                ex.message shouldBe "desc"
            }

            test("throws when the data shape does not match the target type") {
                val response =
                    RestResponse(
                        code = "00",
                        desc = "desc",
                        data = Json.parseToJsonElement("""["not","an","object"]"""),
                    )

                shouldThrow<Exception> { response.getData<SamplePayload>() }
            }
        }
    })

```

**File: `core/model/src/test/kotlin/id/co/bri/brimons/core/model/result/ResultTest.kt`**
```kotlin
package id.co.bri.brimons.core.model.result

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private data class SampleResult(val value: Int) : Result

class ResultTest :
    FunSpec({
        context("Result.map") {
            test("applies the transform and returns the produced value") {
                SampleResult(value = 3).map { it.value * 2 } shouldBe 6
            }

            test("transform can widen types") {
                val stringified: String = SampleResult(value = 1).map { "value=${it.value}" }
                stringified shouldBe "value=1"
            }

            test("transform can produce another Result subtype") {
                val empty: EmptyStateResult =
                    SampleResult(value = 5).map { EmptyStateResult(title = "seed=${it.value}") }
                empty.title shouldBe "seed=5"
            }
        }
    })

```

**File: `core/mvi/build.gradle.kts`**
```kotlin
plugins { alias(libs.plugins.brimo.android.library) }

android { namespace = "id.co.bri.brimons.core.mvi" }

dependencies {
    implementation(projects.core.util)
    implementation(projects.core.model)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.bundles.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
}

```

**File: `core/mvi/src/main/kotlin/id/co/bri/brimons/core/mvi/BaseState.kt`**
```kotlin
package id.co.bri.brimons.core.mvi

import id.co.bri.brimons.core.util.LogPriority
import id.co.bri.brimons.core.util.logcat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

data class BaseState<T>(
    val data: T,
    val skeleton: Boolean = false,
    val loading: Boolean = false,
    val process: Boolean = false,
    val error: Throwable? = null,
    val retry: () -> Unit = {},
    val tag: String = "",
)

fun <T> BaseState<T>.init(
    skeleton: Boolean = false,
    loading: Boolean = false,
    process: Boolean = false,
    tag: String = "",
): BaseState<T> {
    return this.copy(
        skeleton = skeleton,
        loading = loading,
        process = process,
        error = null,
        retry = {},
        tag = tag,
    )
}

fun <T> MutableStateFlow<BaseState<T>>.updateData(transform: T.() -> T) {
    update { it.copy(data = it.data.transform()) }
}

suspend fun <T> MutableStateFlow<BaseState<T>>.execute(
    tag: String = "",
    retry: () -> Unit = {},
    action: suspend () -> Unit,
) {
    update { it.init(tag = tag) }
    try {
        action()
        logcat { this.value.data.toString() }
    } catch (error: Throwable) {
        update { it.copy(error = error, retry = retry) }
        logcat(priority = LogPriority.ERROR) { error.toString() }
    }
}

suspend fun <T> MutableStateFlow<BaseState<T>>.executeSkeleton(
    tag: String = "",
    retry: () -> Unit = {},
    action: suspend () -> Unit,
) {
    update { it.init(skeleton = true, tag = tag) }
    try {
        action()
        update { it.copy(skeleton = false) }
        logcat { this.value.data.toString() }
    } catch (error: Throwable) {
        update { it.copy(skeleton = false, error = error, retry = retry) }
        logcat(priority = LogPriority.ERROR) { error.toString() }
    }
}

suspend fun <T> MutableStateFlow<BaseState<T>>.executeLoading(
    tag: String = "",
    retry: () -> Unit = {},
    action: suspend () -> Unit,
) {
    update { it.init(loading = true, tag = tag) }
    try {
        action()
        update { it.copy(loading = false) }
        logcat { this.value.data.toString() }
    } catch (error: Throwable) {
        update { it.copy(loading = false, error = error, retry = retry) }
        logcat(priority = LogPriority.ERROR) { error.toString() }
    }
}

suspend fun <T> MutableStateFlow<BaseState<T>>.executeProcess(
    tag: String = "",
    retry: () -> Unit = {},
    onError: suspend (Throwable) -> Unit = {},
    action: suspend () -> Unit,
) {
    update { it.init(process = true, tag = tag) }
    try {
        action()
        update { it.copy(process = false) }
        logcat { this.value.data.toString() }
    } catch (error: Throwable) {
        update { it.copy(process = false, error = error, retry = retry) }
        logcat(priority = LogPriority.ERROR) { error.toString() }
        onError(error)
    }
}

```

**File: `core/mvi/src/main/kotlin/id/co/bri/brimons/core/mvi/ExecutionMode.kt`**
```kotlin
package id.co.bri.brimons.core.mvi

enum class ExecutionMode {
    Loading,
    Skeleton,
    Process,
}

```

**File: `core/mvi/src/main/kotlin/id/co/bri/brimons/core/mvi/MviViewModel.kt`**
```kotlin
package id.co.bri.brimons.core.mvi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.co.bri.brimons.core.model.exception.BaseException
import id.co.bri.brimons.core.model.exception.ExceptionMapper
import id.co.bri.brimons.core.model.exception.ExceptionMapper.toMappedException
import id.co.bri.brimons.core.model.exception.GeneralApiException
import id.co.bri.brimons.core.model.exception.copyActions
import id.co.bri.brimons.core.model.exception.dynamic.DynamicErrorApiException
import id.co.bri.brimons.core.mvi.interfaces.UiEffect
import id.co.bri.brimons.core.mvi.interfaces.UiEvent
import id.co.bri.brimons.core.mvi.interfaces.UiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

abstract class MviViewModel<E : UiEvent, S : UiState, F : UiEffect>(initialState: S) : ViewModel() {

    private val _state = MutableStateFlow(BaseState(initialState))
    val state: StateFlow<BaseState<S>> = _state.transformState()

    private val _effect = MutableSharedFlow<F>(replay = 0, extraBufferCapacity = 1)
    val effect = _effect.asSharedFlow()

    private val _stickyEffect = MutableSharedFlow<F>(replay = 1)
    val stickyEffect = _stickyEffect.asSharedFlow()

    protected open fun MutableStateFlow<BaseState<S>>.transformState(): StateFlow<BaseState<S>> =
        asStateFlow()

    fun onEvent(event: E) {
        handleEvent(event)
    }

    protected abstract fun handleEvent(event: E)

    protected fun setState(reducer: BaseState<S>.() -> BaseState<S>) {
        _state.update { state -> state.reducer() }
    }

    protected fun sendEffect(builder: () -> F) {
        _effect.tryEmit(builder())
    }

    protected fun sendStickyEffect(builder: () -> F) {
        _stickyEffect.tryEmit(builder())
    }

    protected fun clearStickyEffects() {
        _stickyEffect.resetReplayCache()
    }

    protected suspend fun handleException(
        throwable: Throwable,
        retry: (() -> Unit)? = null,
        onDismiss: (() -> Unit)? = null,
        onAction: ((String) -> Unit)? = null,
    ): BaseException {
        val base = throwable.asBaseException()

        setState { copy(error = base, retry = retry ?: {}) }

        if (base is DynamicErrorApiException) {
            ExceptionMapper.handleDynamicError(base, retry, onDismiss, onAction)
        }

        if (base is GeneralApiException) {
            ExceptionMapper.handleGlobalEvent(base.copyActions(retry, onDismiss))
        }

        return base
    }

    protected fun <T> execute(
        mode: ExecutionMode,
        tag: String = "",
        retry: (() -> Unit)? = null,
        onDismiss: (() -> Unit)? = null,
        onDynamicErrorAction: ((String) -> Unit)? = null,
        block: suspend () -> T,
        onSuccess: suspend (T) -> Unit,
        onFailure: (BaseException) -> Unit = {},
    ) {
        if (state.value.isRunning(mode)) return

        viewModelScope.launch {
            setState { setRunning(mode = mode, value = true, tag = tag) }

            try {
                val result = block()

                setState { setRunning(mode = mode, value = false) }

                onSuccess(result)
            } catch (e: CancellationException) {
                setState { setRunning(mode = mode, value = false) }
                throw e
            } catch (e: Throwable) {
                setState { setRunning(mode = mode, value = false) }

                val base =
                    handleException(
                        throwable = e,
                        retry = retry,
                        onDismiss = onDismiss,
                        onAction = onDynamicErrorAction,
                    )

                if (base !is GeneralApiException) {
                    onFailure(base)
                }
            }
        }
    }

    protected fun <T> executeCustom(
        call: suspend () -> T,
        job: Job? = null,
        onJob: (Job?) -> Unit = {},
        onLoading: (Boolean) -> Unit = {},
        onSuccess: (T) -> Unit = {},
        onError: (Throwable) -> Unit = {},
    ) {
        if (job?.isActive == true) return
        onLoading(true)
        onJob(
            viewModelScope.launch {
                try {
                    onSuccess(call())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    onError(e)
                } finally {
                    onLoading(false)
                }
            }
        )
    }

    private suspend fun Throwable.asBaseException(): BaseException {
        return this as? BaseException ?: toMappedException()
    }

    private fun BaseState<*>.isRunning(mode: ExecutionMode): Boolean {
        return when (mode) {
            ExecutionMode.Loading -> loading
            ExecutionMode.Skeleton -> skeleton
            ExecutionMode.Process -> process
        }
    }

    protected fun <S : UiState> BaseState<S>.setRunning(
        mode: ExecutionMode,
        value: Boolean,
        tag: String = this.tag,
    ): BaseState<S> {
        return when (mode) {
            ExecutionMode.Loading -> copy(loading = value, tag = tag, error = null)

            ExecutionMode.Skeleton -> copy(skeleton = value, tag = tag, error = null)

            ExecutionMode.Process -> copy(process = value, tag = tag, error = null)
        }
    }
}

```

**File: `core/mvi/src/main/kotlin/id/co/bri/brimons/core/mvi/interfaces/UiEffect.kt`**
```kotlin
package id.co.bri.brimons.core.mvi.interfaces

interface UiEffect

```

**File: `core/mvi/src/main/kotlin/id/co/bri/brimons/core/mvi/interfaces/UiEvent.kt`**
```kotlin
package id.co.bri.brimons.core.mvi.interfaces

interface UiEvent

```

**File: `core/mvi/src/main/kotlin/id/co/bri/brimons/core/mvi/interfaces/UiScreen.kt`**
```kotlin
package id.co.bri.brimons.core.mvi.interfaces

interface UiScreen

```

**File: `core/mvi/src/main/kotlin/id/co/bri/brimons/core/mvi/interfaces/UiState.kt`**
```kotlin
package id.co.bri.brimons.core.mvi.interfaces

interface UiState

```

**File: `core/mvi/src/test/kotlin/id/co/bri/brimons/core/mvi/BaseStateTest.kt`**
```kotlin
package id.co.bri.brimons.core.mvi

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BaseStateTest {
    @Test
    fun `init should reset flags and clear errors`() {
        val initialState =
            BaseState(data = "TestData", skeleton = true, error = RuntimeException("Old Error"))

        val inited = initialState.init(loading = true, tag = "InitTag")

        inited.data shouldBe "TestData"
        inited.skeleton.shouldBeFalse()
        inited.loading.shouldBeTrue()
        inited.process.shouldBeFalse()
        inited.error.shouldBeNull()
        inited.tag shouldBe "InitTag"
    }

    @Test
    fun `updateData should modify data via transform`() {
        val flow = MutableStateFlow(BaseState(data = 10))

        flow.updateData { this + 5 }

        flow.value.data shouldBe 15
    }

    @Test
    fun `execute should set tag and handle success`() = runTest {
        val flow = MutableStateFlow(BaseState(data = "Initial"))

        flow.execute(tag = "ExecuteTag") {
            // Action success
        }

        flow.value.tag shouldBe "ExecuteTag"
        flow.value.error.shouldBeNull()
    }

    @Test
    fun `execute should catch error and set retry action`() = runTest {
        val testException = RuntimeException("Test Error")
        val retryAction: () -> Unit = {}

        val flow = MutableStateFlow(BaseState(data = "Initial"))

        flow.execute(retry = retryAction) { throw testException }

        flow.value.error shouldBe testException
        flow.value.retry shouldBe retryAction
    }

    @Test
    fun `executeSkeleton should toggle skeleton flag on success`() = runTest {
        val flow = MutableStateFlow(BaseState(data = "Initial"))

        flow.executeSkeleton {
            // Assert that skeleton is true DURING the action
            flow.value.skeleton.shouldBeTrue()
        }

        // Assert that skeleton is false AFTER success
        flow.value.skeleton.shouldBeFalse()
        flow.value.error.shouldBeNull()
    }

    @Test
    fun `executeSkeleton should turn off skeleton and catch error`() = runTest {
        val testException = RuntimeException("Test Error")
        val flow = MutableStateFlow(BaseState(data = "Initial"))

        flow.executeSkeleton { throw testException }

        flow.value.skeleton.shouldBeFalse()
        flow.value.error shouldBe testException
    }

    @Test
    fun `executeLoading should toggle loading flag on success`() = runTest {
        val flow = MutableStateFlow(BaseState(data = "Initial"))

        flow.executeLoading {
            // Assert that loading is true DURING the action
            flow.value.loading.shouldBeTrue()
        }

        // Assert that loading is false AFTER success
        flow.value.loading.shouldBeFalse()
        flow.value.error.shouldBeNull()
    }

    @Test
    fun `executeLoading should turn off loading and catch error`() = runTest {
        val testException = RuntimeException("Test Error")
        val flow = MutableStateFlow(BaseState(data = "Initial"))

        flow.executeLoading { throw testException }

        flow.value.loading.shouldBeFalse()
        flow.value.error shouldBe testException
    }

    @Test
    fun `executeProcess should toggle process flag on success`() = runTest {
        val flow = MutableStateFlow(BaseState(data = "Initial"))

        flow.executeProcess {
            // Assert that process is true DURING the action
            flow.value.process.shouldBeTrue()
        }

        // Assert that process is false AFTER success
        flow.value.process.shouldBeFalse()
        flow.value.error.shouldBeNull()
    }

    @Test
    fun `executeProcess should turn off process and catch error`() = runTest {
        val testException = RuntimeException("Test Error")
        val flow = MutableStateFlow(BaseState(data = "Initial"))

        flow.executeProcess { throw testException }

        flow.value.process.shouldBeFalse()
        flow.value.error shouldBe testException
    }
}

```

**File: `core/mvi/src/test/kotlin/id/co/bri/brimons/core/mvi/MviViewModelTest.kt`**
```kotlin
package id.co.bri.brimons.core.mvi

import id.co.bri.brimons.core.mvi.interfaces.UiEffect
import id.co.bri.brimons.core.mvi.interfaces.UiEvent
import id.co.bri.brimons.core.mvi.interfaces.UiState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private data class TestState(val value: Int = 0) : UiState

private data class TestEvent(val id: Int) : UiEvent

private data class TestEffect(val id: Int) : UiEffect

private class FakeMviViewModel : MviViewModel<TestEvent, TestState, TestEffect>(TestState()) {
    val handledEvents = mutableListOf<TestEvent>()

    override fun handleEvent(event: TestEvent) {
        handledEvents.add(event)
    }

    fun emit(effect: TestEffect) = sendEffect { effect }

    fun emitSticky(effect: TestEffect) = sendStickyEffect { effect }

    fun clearSticky() = clearStickyEffects()

    fun setValue(value: Int) = setState { copy(data = data.copy(value = value)) }
}

class MviViewModelTest :
    FunSpec({
        test("onEvent delegates to handleEvent") {
            val sut = FakeMviViewModel()

            sut.onEvent(TestEvent(7))
            sut.onEvent(TestEvent(8))

            sut.handledEvents shouldBe listOf(TestEvent(7), TestEvent(8))
        }

        test("setState reduces the wrapped data") {
            val sut = FakeMviViewModel()

            sut.state.value.data.value shouldBe 0
            sut.setValue(42)
            sut.state.value.data.value shouldBe 42
        }

        test("an emitted effect is not retained for a collector that attaches later") {
            val sut = FakeMviViewModel()

            sut.emit(TestEffect(1))

            sut.effect.replayCache shouldBe emptyList()
        }

        test("multiple emitted effects are not retained for a later collector") {
            val sut = FakeMviViewModel()

            sut.emit(TestEffect(1))
            sut.emit(TestEffect(2))
            sut.emit(TestEffect(3))

            sut.effect.replayCache shouldBe emptyList()
        }

        test("a sticky effect is retained for a collector that attaches later") {
            val sut = FakeMviViewModel()

            sut.emitSticky(TestEffect(5))

            sut.stickyEffect.replayCache shouldBe listOf(TestEffect(5))
        }

        test("only the most recent sticky effect is retained") {
            val sut = FakeMviViewModel()

            sut.emitSticky(TestEffect(1))
            sut.emitSticky(TestEffect(2))

            sut.stickyEffect.replayCache shouldBe listOf(TestEffect(2))
        }

        test("clearStickyEffects drops the retained sticky effect") {
            val sut = FakeMviViewModel()

            sut.emitSticky(TestEffect(5))
            sut.clearSticky()

            sut.stickyEffect.replayCache shouldBe emptyList()
        }
    })

```

**File: `core/navigation/build.gradle.kts`**
```kotlin
plugins {
    alias(libs.plugins.brimo.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android { namespace = "id.co.bri.brimons.core.navigation" }

dependencies {
    implementation(projects.core.model)
    implementation(libs.android.material)
    implementation(libs.androidx.material.navigation)
    implementation(libs.kotlinx.serialization.json)
}

```

**File: `core/navigation/src/main/kotlin/id/co/bri/brimons/core/navigation/ComplaintNavigation.kt`**
```kotlin
package id.co.bri.brimons.core.navigation

import android.content.Context
import id.co.bri.brimons.core.navigation.route.ComplaintDestination

interface ComplaintNavigation {
    fun getComplaintActivity(
        context: Context,
        complaintDestination: ComplaintDestination,
        mutationDetail: String? = null,
        sourceAccount: String? = null,
        brizziCardNumber: String = "",
        brizziBalance: String = "",
        brizziStatus: String = "",
        brizziHistory: String = "",
    )
}

```

**File: `core/navigation/src/main/kotlin/id/co/bri/brimons/core/navigation/FaqNavigation.kt`**
```kotlin
package id.co.bri.brimons.core.navigation

import android.content.Context
import id.co.bri.brimons.core.navigation.route.FaqDestination

interface FaqNavigation {
    fun getFaqActivity(
        context: Context,
        faqDestination: FaqDestination,
        content: String? = null,
        isFromFastMenu: Boolean = false,
        isFromOnBoarding: Boolean = false,
    )
}

```

**File: `core/navigation/src/main/kotlin/id/co/bri/brimons/core/navigation/HelpCenterNavigation.kt`**
```kotlin
package id.co.bri.brimons.core.navigation

import android.content.Context

interface HelpCenterNavigation {
    fun getHelpCenterActivity(
        context: Context,
        sourceAccount: String? = null,
        isFromFastMenu: Boolean = false,
        isFromOnBoarding: Boolean = false,
    )
}

```

**File: `core/navigation/src/main/kotlin/id/co/bri/brimons/core/navigation/LifestyleNavigation.kt`**
```kotlin
package id.co.bri.brimons.core.navigation

import android.content.Context
import id.co.bri.brimons.route.LifestyleDestination

interface LifestyleNavigation {
    fun startLifestyle(context: Context, destination: LifestyleDestination)

    /**
     * Attempts to open the lifestyle receipt screen for the given transaction type.
     *
     * Returns true if the trxType is owned by the lifestyle module (whether or not the receipt was
     * serialized), false if the caller should handle it through the legacy receipt path.
     * [serializeReceipt] is only invoked when the trxType matches a known lifestyle type.
     */
    fun tryStartLifestyleReceipt(
        context: Context,
        trxType: String,
        serializeReceipt: () -> String?,
    ): Boolean
}

```

**File: `core/navigation/src/main/kotlin/id/co/bri/brimons/core/navigation/PinNavigation.kt`**
```kotlin
package id.co.bri.brimons.core.navigation

import android.content.Context
import id.co.bri.brimons.core.navigation.route.pin.PinStatus
import kotlinx.coroutines.flow.SharedFlow

/**
 * Cross-module contract for launching PIN entry and observing its result.
 *
 * Lives in `core:navigation` (no dependency on the pin feature) so any module can inject it and
 * drive a PIN check without depending on `shared:pin:presentation`. The concrete implementation
 * there, `PinSharedNavigationImpl`, backs [pinStatus] with a single `MutableSharedFlow` and starts
 * `id.co.bri.brimons.shared.pin.presentation.PinActivity` from [navigatePin] — see that class's
 * KDoc for how the activity reads [navigatePin]'s params and reports back through
 * [updatePinStatus]/[pinStatus].
 *
 * To implement: back [pinStatus] with a shared, replay-less `MutableSharedFlow<PinStatus>` (must
 * support multiple/late collectors) and forward every emission from [updatePinStatus] into it.
 *
 * To use (inject the interface, never the impl):
 * ```
 * @Inject lateinit var pinSharedNavigation: PinSharedNavigation
 *
 * pinSharedNavigation.navigatePin(context, isCheckPin = true, fastMenu = false)
 *
 * LaunchedEffect(Unit) {
 *     pinSharedNavigation.pinStatus.collect { status ->
 *         if (status is PinStatus.PinSuccess) { /* pin verified */ }
 *     }
 * }
 * ```
 */
interface PinSharedNavigation {

    /** Emits every [PinStatus] update; both PIN UI and its caller collect and emit here. */
    val pinStatus: SharedFlow<PinStatus>

    /** Publishes [status] to [pinStatus]. Called by both the PIN UI and its caller. */
    suspend fun updatePinStatus(status: PinStatus)

    /**
     * Opens PIN entry. [isCheckPin] = true verifies the entered pin against the backend before
     * reporting success; false just collects digits and reports them via `PinStatus.PinSubmitted`.
     * [fastMenu] is forwarded to the check-pin request when [isCheckPin] is true.
     */
    fun navigatePin(context: Context, isCheckPin: Boolean = true, fastMenu: Boolean = false)
}

```

**File: `core/navigation/src/main/kotlin/id/co/bri/brimons/core/navigation/ReceiptNavigation.kt`**
```kotlin
package id.co.bri.brimons.core.navigation

import android.content.Context
import id.co.bri.brimons.core.model.receipt.ReceiptAnalyticEvent
import id.co.bri.brimons.core.model.receipt.ReceiptUiModel

/**
 * Navigation interface for receipt screens.
 *
 * Use [navigateToReceipt] for the dynamic section-based receipt.
 */
interface ReceiptNavigation {

    /**
     * Navigate to the dynamic section-based receipt screen.
     *
     * @param context The context to start the activity from.
     * @param receipt The [ReceiptUiModel] containing all receipt data and configuration.
     * @param onAnalytic Optional analytics callback invoked at key user interactions.
     */
    fun navigateToReceipt(
        context: Context,
        receipt: ReceiptUiModel,
        onAnalytic: (ReceiptAnalyticEvent) -> Unit = {},
    )
}

```

**File: `core/navigation/src/main/kotlin/id/co/bri/brimons/core/navigation/route/ComplaintDestination.kt`**
```kotlin
package id.co.bri.brimons.core.navigation.route

enum class ComplaintDestination {
    LIST,
    FORM,
    MUTATION,
}

```

**File: `core/navigation/src/main/kotlin/id/co/bri/brimons/core/navigation/route/FaqDestination.kt`**
```kotlin
package id.co.bri.brimons.core.navigation.route

enum class FaqDestination {
    LANDING,
    SEARCH,
    TOPIC,
}

```

**File: `core/navigation/src/main/kotlin/id/co/bri/brimons/core/navigation/route/LifestyleDestination.kt`**
```kotlin
package id.co.bri.brimons.route

sealed class LifestyleDestination {
    data object VoucherStreaming : LifestyleDestination()

    data object Donation : LifestyleDestination()

    data object Zakat : LifestyleDestination()

    /** [receiptJson] is a serialized GeneralReceiptResponse by Gson. */
    data class Receipt(val receiptJson: String) : LifestyleDestination()
}

```

**File: `core/navigation/src/main/kotlin/id/co/bri/brimons/core/navigation/route/PaymentPageDestination.kt`**
```kotlin
package id.co.bri.brimons.core.navigation.route

object PaymentPageDestination {
    const val KEY = "destination"
    const val FAST_MENU = "fromfastmenu"
    const val BRIVA_NUMBER = "preBrivaNumber"
    const val BRIZZI_OUTSIDE = "brizzi_outside"
    const val BRIZZI_FORM = "brizzi_form"
    const val BRIVA_FORM = "briva_form"
    const val BULK_PAYMENT_FORM = "bayar_sekaligus"
    const val QRIS_SCAN = "qris_scan"
    const val TRANSFER_FORM = "transfer_form"
}

```

**File: `core/navigation/src/main/kotlin/id/co/bri/brimons/core/navigation/route/pin/PinStatus.kt`**
```kotlin
package id.co.bri.brimons.core.navigation.route.pin

sealed interface PinStatus {
    // PIN Emit, Caller Collect
    data class PinSubmitted(val pin: String) : PinStatus

    data class PinSuccess(val pin: String) : PinStatus

    data object ProcessFinished : PinStatus

    // Caller Emit, PIN Collect
    data object Loading : PinStatus

    data class Process(val loading: Boolean, val title: String, val description: String) : PinStatus

    data class Error(
        val error: Throwable,
        val retry: (() -> Unit)? = null,
        val onDismiss: (() -> Unit)? = null,
        val onDynamicErrorAction: ((String) -> Unit)? = null,
    ) : PinStatus

    data object Finish : PinStatus
}

```

**File: `core/network/build.gradle.kts`**
```kotlin
import java.util.Properties

plugins {
    alias(libs.plugins.brimo.android.library.flavors)
    alias(libs.plugins.brimo.android.hilt)
    alias(libs.plugins.brimo.android.network)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "id.co.bri.brimons.core.network"

    val endpointFile = rootProject.file("app/endpoint.properties")
    val endpointProperties = Properties().apply { load(endpointFile.inputStream()) }
    productFlavors {
        getByName("production") {
            buildConfigField(
                "String",
                "M_API_URL",
                endpointProperties["PRODUCTION_URL_API"] as String,
            )
            buildConfigField(
                "String",
                "M_ENDPOINT_MINIO",
                endpointProperties["PRODUCTION_ENDPOINT_MINIO"] as String,
            )
        }
        getByName("qittaErangel") {
            buildConfigField(
                "String",
                "M_API_URL",
                endpointProperties["QITTA_DEV_URL_API"] as String,
            )
            buildConfigField(
                "String",
                "M_ENDPOINT_MINIO",
                endpointProperties["DEVELOPMENT_ENDPOINT_MINIO"] as String,
            )
        }
    }
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.preference)
    implementation(projects.core.util)
    implementation(projects.core.security)
    implementation(libs.trustkit)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.gson)
    implementation(libs.retrofit.adapter.rxjava2)
    implementation(libs.rxjava)
    implementation(libs.rxandroid)
    implementation(libs.kotlinx.coroutines.rx2)
    testImplementation(libs.mockwebserver)
}

```

**File: `core/network/src/main/kotlin/id/co/bri/brimons/core/network/NetworkConfig.kt`**
```kotlin
package id.co.bri.brimons.core.network

import id.co.bri.brimons.core.security.crypto.decryptAesCbcBase64
import id.co.bri.brimons.core.util.logcat
import java.io.IOException

object NetworkConfig {
    const val USER_AGENT: String = "mXeycaG0U7KvwbFgMsl3mg=="
    const val TIMEOUT_CONNECT: Int = 30
    const val TIMEOUT_READ: Int = 30

    fun getBaseUrl(): String {
        try {
            return decryptAesCbcBase64(BuildConfig.M_API_URL)
        } catch (e: IOException) {
            logcat { "getBaseUrl: ${e.message}" }
            return ""
        }
    }

    fun getEndPointMinio(): String {
        try {
            return decryptAesCbcBase64(BuildConfig.M_ENDPOINT_MINIO)
        } catch (e: IOException) {
            logcat { "getEndPointMinio: ${e.message}" }
            return ""
        }
    }

    fun getUserAgent(): String {
        try {
            return decryptAesCbcBase64(USER_AGENT)
        } catch (e: IOException) {
            logcat { "getUserAgent: ${e.message}" }
            return ""
        }
    }
}

```

**File: `core/network/src/main/kotlin/id/co/bri/brimons/core/network/api/ApiClient.kt`**
```kotlin
package id.co.bri.brimons.core.network.api

import android.content.Context
import com.brimo.wormaceptor.api.BrimoCeptorInterceptor
import com.brimo.wormaceptor.api.DynamicBaseUrlInterceptor
import com.datatheorem.android.trustkit.pinning.OkHttp3Helper
import id.co.bri.brimons.core.network.BuildConfig
import id.co.bri.brimons.core.network.NetworkConfig
import id.co.bri.brimons.core.network.utils.BodyDecryptor
import java.util.concurrent.TimeUnit
import okhttp3.CookieJar
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor

object NewApiClient {
    fun getOkHttpClient(context: Context, cookieJar: CookieJar): OkHttpClient {
        val bodyDecryptor = BodyDecryptor()

        val logging =
            HttpLoggingInterceptor().apply {
                level =
                    if (BuildConfig.DEBUG) {
                        HttpLoggingInterceptor.Level.BODY
                    } else {
                        HttpLoggingInterceptor.Level.NONE
                    }
            }

        val brimoCeptor = BrimoCeptorInterceptor().addBodyDecoder(bodyDecryptor)

        val builder =
            if (BuildConfig.DEBUG) {
                OkHttpClient.Builder()
            } else {
                OkHttpClient.Builder()
                    .sslSocketFactory(
                        OkHttp3Helper.getSSLSocketFactory(),
                        OkHttp3Helper.getTrustManager(),
                    )
                    .addInterceptor(OkHttp3Helper.getPinningInterceptor())
            }

        return builder
            .addInterceptor(headerInterceptor())
            .addInterceptor(logging)
            .addInterceptor(
                DynamicBaseUrlInterceptor(
                    context = context,
                    fallbackBaseUrl = { NetworkConfig.getBaseUrl() },
                )
            )
            .addInterceptor(brimoCeptor)
            .connectTimeout(NetworkConfig.TIMEOUT_CONNECT.toLong(), TimeUnit.SECONDS)
            .readTimeout(NetworkConfig.TIMEOUT_READ.toLong(), TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(false)
            .followSslRedirects(false)
            .cookieJar(cookieJar)
            .build()
    }

    fun getMinioOkHttpClient(): OkHttpClient {
        val logging =
            HttpLoggingInterceptor().apply {
                level =
                    if (BuildConfig.DEBUG) {
                        HttpLoggingInterceptor.Level.BODY
                    } else {
                        HttpLoggingInterceptor.Level.NONE
                    }
            }

        return OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(NetworkConfig.TIMEOUT_CONNECT.toLong(), TimeUnit.SECONDS)
            .readTimeout(NetworkConfig.TIMEOUT_READ.toLong(), TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private fun headerInterceptor() = Interceptor { chain ->
        val original = chain.request()
        val request =
            original
                .newBuilder()
                .header(NetworkConstant.USER_AGENT_HEADER, NetworkConfig.getUserAgent())
                .header(NetworkConstant.X_ACCEPTED_LANGUAGE, "id")
                .build()
        chain.proceed(request)
    }

    object NetworkConstant {
        internal const val USER_AGENT_HEADER: String = "User-Agent"
        internal const val X_ACCEPTED_LANGUAGE: String = "X-Accepted-Language"
    }
}

```

**File: `core/network/src/main/kotlin/id/co/bri/brimons/core/network/api/ApiInterface.kt`**
```kotlin
package id.co.bri.brimons.core.network.api

import retrofit2.Response
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.HeaderMap
import retrofit2.http.POST
import retrofit2.http.Url

interface ApiInterface {
    companion object {
        const val HEADER_RANDOM_KEY: String = "X-RANDOM-KEY"
        const val HEADER_DEVICE_ID: String = "X-DEVICE-ID"
        const val HEADER_DEVICE: String = "X-DEVICE"
        const val HEADER_ID: String = "X-ID"
        const val HEADER_KEY_SEQUENCE: String = "X-KEY-SEQUENCE"
    }

    @FormUrlEncoded
    @POST
    suspend fun post(
        @Url url: String,
        @Field("request") requestData: String,
        @HeaderMap headers: Map<String, String>,
    ): Response<String>

    @GET
    suspend fun get(@Url url: String, @HeaderMap headers: Map<String, String>): Response<String>

    @FormUrlEncoded
    @POST
    suspend fun getErangelRequest(
        @Url url: String,
        @Field("request") requestData: String,
        @Header(HEADER_DEVICE_ID) deviceId: String,
        @Header(HEADER_DEVICE) device: String,
        @Header(HEADER_RANDOM_KEY) randomKey: String,
        @Header(HEADER_ID) id: String,
        @Header(HEADER_KEY_SEQUENCE) key: String,
    ): Response<String>
}

```

**File: `core/network/src/main/kotlin/id/co/bri/brimons/core/network/api/MinioInterface.kt`**
```kotlin
package id.co.bri.brimons.core.network.api

import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.PUT
import retrofit2.http.Url

interface MinioInterface {
    @PUT suspend fun putMinioData(@Url url: String, @Body file: RequestBody): Response<Unit>
}

```

**File: `core/network/src/main/kotlin/id/co/bri/brimons/core/network/api/NetworkSource.kt`**
```kotlin
package id.co.bri.brimons.core.network.api

import android.content.Context
import id.co.bri.brimons.core.network.di.qualifier.MigrateNewNetwork
import id.co.bri.brimons.core.network.erangel.createErangelRequest
import id.co.bri.brimons.core.network.utils.helper.ErangelHeaderFactory
import id.co.bri.brimons.core.preference.impl.session.SessionPreference
import id.co.bri.brimons.core.util.ext.toJsonString
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import retrofit2.Response

class NetworkSource
@Inject
constructor(
    val context: Context,
    val preference: SessionPreference,
    @MigrateNewNetwork val apiInterface: ApiInterface,
    val dispatcher: CoroutineDispatcher,
) {
    suspend fun getData(url: String, request: Any, seqNum: String): Response<String> =
        withContext(dispatcher) {
            val erangel =
                createErangelRequest(
                    context = context,
                    seqNumb = seqNum,
                    rawRequest = request.toJsonString(),
                    deviceId1 = preference.getDeviceId(),
                    deviceId2 = preference.getDeviceId2(),
                )

            apiInterface.post(
                url = url,
                requestData = erangel.request,
                headers = ErangelHeaderFactory.create(erangel),
            )
        }
}

```

**File: `core/network/src/main/kotlin/id/co/bri/brimons/core/network/connectivity/NetworkObserver.kt`**
```kotlin
package id.co.bri.brimons.core.network.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class NetworkObserverImpl @Inject constructor(@ApplicationContext private val context: Context) :
    INetworkObserver {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _state = MutableStateFlow<NetworkState>(NetworkState.Available)
    override val state: StateFlow<NetworkState> = _state.asStateFlow()

    private var isRegistered = false

    private val callback =
        object : ConnectivityManager.NetworkCallback() {

            override fun onAvailable(network: Network) {
                updateState(true)
            }

            override fun onLost(network: Network) {
                updateState(false)
            }
        }

    override fun start() {
        if (isRegistered) return

        checkCurrentConnection()

        val request =
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .build()

        connectivityManager.registerNetworkCallback(request, callback)
        isRegistered = true
    }

    override fun stop() {
        if (!isRegistered) return

        runCatching { connectivityManager.unregisterNetworkCallback(callback) }

        isRegistered = false
    }

    private fun checkCurrentConnection() {
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)

        val connected =
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

        updateState(connected)
    }

    private fun updateState(connected: Boolean) {
        _state.value =
            if (connected) {
                NetworkState.Available
            } else {
                NetworkState.Lost
            }
    }
}

```

**File: `core/network/src/main/kotlin/id/co/bri/brimons/core/network/connectivity/NetworkState.kt`**
```kotlin
package id.co.bri.brimons.core.network.connectivity

import kotlinx.coroutines.flow.StateFlow

sealed interface NetworkState {
    data object Available : NetworkState

    data object Lost : NetworkState
}

interface INetworkObserver {
    val state: StateFlow<NetworkState>

    fun start()

    fun stop()
}

```

**File: `core/network/src/main/kotlin/id/co/bri/brimons/core/network/constants/ApiConstant.kt`**
```kotlin
package id.co.bri.brimons.core.network.constants

object ApiConstant {

    // COMPLAINT ENDPOINTS
    const val V5_COMPLAINT_MUTATION = "2BrtWMr+Mo3sGfRhvey7jX8tpCIQMlfGIsnsY4sEbc8="
    const val V5_COMPLAINT_LIST = "/EWwT8y2sEVwW1Y7er2NcMDzOz1nWmJdAzhxi1C2F3c="
    const val V5_COMPLAINT_DETAILS = "g7ZbNR2Kjpjgy0nRdH+Pstv6SBOfi4AauwNFRrAI17I="
    const val V5_COMPLAINT_MUTATION_DETAILS = "ifaFBQnzKepD2dOb3ynet1NqcHXmwco0FQHBvQ2LI94="
    const val V5_COMPLAINT_SUBMIT = "4PVhrrINf88jLKkzqPgaEsacfgwsI65xV5dRDGlEyqI="

    const val V5_ACCOUNT_LIST = "6XcHuzXPT8Uh/Pggd+tfUbOgaMo7So3VHQjLBHPZC/g="

    // HELP CENTER
    const val V5_HELP_CENTER = "rx7u2yLpaBWp03J525Z/Og=="
    const val V5_FM_HELP_CENTER = "GCAX0VytMitJbwlk89jZs8JSLAuJnPYWYZ61UWbZFHY="
    const val V5_HELP_CENTER_ONBOARDING = "UyKof8IwclGgOmrqU2JkWzKo+/C+tgY3I0+bcuJLjIg="

    // FAQ / QNA
    const val V5_QNA_LANDING = "K2o3F1TL3/okP8xrOd2fXA=="
    const val V5_FM_QNA_LANDING = "GPOwVlMr3Wdm8QzZ1hkV+ZjS36jrrSNOsOBFKBMYAVQ="
    const val V5_QNA_SEARCH = "BSGfBap/ob+WFgllH+ub8Q=="
    const val V5_FM_QNA_SEARCH = "Erq+VECyt/XCRiMeAs0FJqu1EYtqe8Om7BdNZogewRU="
    const val V5_QNA_SEARCH_ONBOARDING = "fdW94Zu2KAHG4FoDCq6XeU/zjzkD5jeGOj0ztSkvHXQ="
    const val V5_QNA_BY_TOPIC = "UYYkCri6/aZSzGx4Fr7oIQ=="
    const val V5_FM_QNA_BY_TOPIC = "xfROC3M4cRrig1UC1R5wdNPA2z6dbWIwW9ONtD/bRqM="
    const val V5_QNA_BY_TOPIC_ONBOARDING = "BqRK7RfTBrpJZ5v7jnG3IsM7CbTiFu4U8IIKqS9iFuU="
    const val V5_QNA_SUBMIT_FEEDBACK = "OlwMfuerqGYrVmTs5vg/iLjfW9b+WEzWti1lBvZt8oU="
    const val V5_FM_QNA_SUBMIT_FEEDBACK = "CzP6KIwJXR0uS24/c+O5bONJT4F4UGqY9w+8lhcaL5s="
    const val V5_QNA_SUBMIT_FEEDBACK_ONBOARDING =
        "OlwMfuerqGYrVmTs5vg/iDO2YeXY7vZC/DgzcqQQu60HQ3+yCzA8P09EvWNBXMAF"

    // e-STATEMENT ENDPOINT
    const val V5_LIST_E_STATEMENT = "BNSjyMMgYpqOf4Tk31iTNmA5sUtDEpc8NsZrLRWdQ6A="
    const val V5_FORM_REQUEST_E_STATEMENT = "NLf1In41P404pAIMce3wBXUMsICNJkZ2TIa03NgUQQc="
    const val V5_SUBMIT_REQUEST_E_STATEMENT = "cAjR5YT3npkNdtYfJcnxLWkHD9Ig/xFKe54L9OM54uI="
    const val V5_DOWNLOAD_E_STATEMENT = "VLP68T364YHy1x+fQSas6/4bppkhTNNVxMptrM+fIQM="

    // FINANCIAL STATUS
    const val V5_SEND_OTP_FINANCIAL = "OUq2tHEB4dvb+GStjAlo4A=="
    const val V3_SEND_OTP_FINANCIAL = "7PahRjRWflnMEN6CR/CixCN44NGTu0MyTvEza52lFzA="
    const val V3_VALIDATE_OTP_FINANCIAL =
        "7PahRjRWflnMEN6CR/CixCqHd0hf/n/87i3mVWWSfDlT/ynVztFgEK4QUnmfeiXM"
    const val V3_FINANCIAL_STATUS_PRESIGN_OCR = "T3aXYA+jR2I6p++YgMGW2/A1YnYwcihEKzIkE9WV/bs="
    const val V3_FINANCIAL_STATUS_INITIALIZE_ZOLOZ =
        "Vyw53LGEfsY647EyHRJGRxiJ77DYP5yg71ed1b2m1/8sJinU7SkuGj888083wSAr"
    const val V3_FINANCIAL_STATUS_CHECK_KTP = "AAL+Hs6sA/ML6J1574LOpyhXgZROZGbgQtzv6V5nrzM="
    const val V3_FINANCIAL_STATUS_CHECK_FLOW =
        "7PahRjRWflnMEN6CR/CixLZF3muRTZwmsaT7/+yCXi3iCqzibg8zSK42aWdRS8mL"
    const val V3_FINANCIAL_VERIFY_KTP = "NuuFRvGDnQlRA0cvbqfbERFanvHlZTlU0yBpj3MKYys="
    const val V3_FINANCIAL_PRESIGN_LIVENESS =
        "T3aXYA+jR2I6p++YgMGW2+MUJIDjXdfe6DSRFk8yineW8n2T2Y+EKGflWSfAti46"
    const val V3_FINANCIAL_CHECK_STATUS_LIVENESS = "AAL+Hs6sA/ML6J1574LOpzw1ULeONPln0xjAu5X2I9I="
    const val V3_FINANCIAL_INITIALIZE_KYC =
        "Vyw53LGEfsY647EyHRJGR7OUs6iX15KuXKlPsIQTWLxygJ2NVge3HKNnilZrXEeg"
    const val V3_FINANCIAL_SEND_KYC = "1b7AVGZiqntAyeETxVbBLihxVuTrdbrDImojY1Di+Hg="
    const val V3_ACTIVATE_STATUS_FIN = "7PahRjRWflnMEN6CR/CixL8hzUIso30l6w/SDcyDd9o="
    const val V3_GET_STATUS_FIN = "N/7/hNnCRVf6zrewPiZb/5MQ7Y24tTPAs+IHPEdffWBfZKcDmLyKZh6fuoeQmJsg"
    const val V3_DEACTIVATE_STATUS_FIN = "XnT8sxjFr+IH+A9wZGHkxcPQyAERQodwC92WtW1OYCM="
    const val V3_RESET_PROGRESS = "n+tv78tQ3sv1QY/rDbvb5oxvSgy85Sewyvtz0cWFvesXlW3g4gCZHVxyYrfASDIV"

    // ACCOUNT INFORMATION
    const val V1_CHECK_STATUS_NOTIF = "ogHpaiED5sN2IxaLPTDz5SiWhY0ki/jcE/8sxGK9F84="
    const val V1_CHECK_CARD_STATUS = "H02rnfHPfh7GKdWXtaffVQ=="
    const val V1_SET_ACCOUNT_DEFAULT = "kw1BZ7x9U/hcFIyx4jB/zFFkn7I5XkzOAkkDn/FTrzs="
    const val V1_ACCOUNT_BI_FAST = "Vd6cZQ/VfSqxarmhyxXt0fjdBmVrtHvN6KTk0Dsw7II="
    const val V1_INACTIVE_FINANCIAL_STATUS = "dX37XLFPOxRtWAK050Pyt7AiR7XWlFcLkG+EJfSa/R4="

    // VIRTUAL CARD ENDPOINT
    const val V1_GET_OFFICE_LOCATION = "N/jnPqPljjQwOxfchnGQWAgVa2w0plGHb1V1550WAmE="
    const val V1_SEARCH_OFFICE_LOCATION = "VGZObshKCgIVvg5kWZYKZS0QNF24BStcS2Gl7vl8ofk="
    const val V1_CHECK_STATUS_VIRTUAL_CARD =
        "ogHpaiED5sN2IxaLPTDz5UseZ0EYii31fCmX8pVDk8VVfXnu85P+XmnjJY9WGxdW"
    const val V1_GET_LIST_VIRTUAL_CARD = "of/grUv5QqHrsrLJq6ufL7mREgAKLNKK+5LlWXp/gfg="
    const val V1_GENERATE_CVV_VIRTUAL_CARD = "8QKJw7IFSXYkphHGEn6B+hOlG8zmzWwu0UzbsLV/tzw="
    const val V1_ENABLED_DISABLE_VIRTUAL_CAD_SETTING =
        "HuvrZci6jzxnDBGKINHkGHNdKtwD6C+di6nI+FHwHBHjZ8OFILRV/62PAzQ7PibT"
    const val V1_DETAIL_VIRTUAL_CARD = "sDHQUUlgGiOhdwBLdOzKZDuSABCtnlwR8rpN8lBX7iA="
    const val V1_UPDATE_LABEL_VIRTUAL_CARD = "5nVPOSTBbBcyqQKLc5jGuLaaRP81R3C3RVx0uXanaZg="
    const val V5_INIT_CHANGE_PIN_VIRTUAL_CARD =
        "ZTqpqCMp/G7sGQxLnmGaWRpknoTJLGjIbHd3oSzpgzRizFTo/7NEapXn37wBt7A0"
    const val V1_CONFIRMATION_VIRTUAL_CARD =
        "8iAH4X7hNjWQIZhmTJ6G4xXV8A9Rgn55+R8RY9kpEgoB0ZFZ02pRMcLZ68WRf13i"
    const val V1_SELECT_PRODUCT_VIRTUAL_CARD = "j5Yyck4VZfIjAdj6dbBCraImrZSlhw+LeCno3EZQYNs="
    const val V1_GET_LIST_PRODUCT_VIRTUAL_CARD =
        "RVskb+OT5Kg0V5c3m3JlCLyolP+ipY+uceS5YT9smVZH19sE15P5w3zq1HBfYC6X"
    const val V1_CREATE_VIRTUAL_CARD = "043LyETVysZo+GwBJq7g+WSeueowMnhAvXbw+cPDYto="

    // PAWN GOLD ENDPOINT
    const val URL_SEARCH_BRANCH_PEGADAIAN = "kW8b7cuHLwb1FKFUtEjk/d51eCfFlK2JZuSP1ttCjg8="
    const val URL_DASHBOARD_PAWN_GOLD_SAVINGS_NEW_SKIN =
        "TQEysmuZNN+Lcy4hG5jwHpOiwDIYIVmWESg6pIvTkKk="
    const val URL_PORTFOLIO_PAWN_GOLD_SAVINGS_NEW_SKIN =
        "TQEysmuZNN+Lcy4hG5jwHrBAtmIf4ahDjpwLKrLLC0Q="
    const val URL_DETAIL_PORTFOLIO_PAWN_GOLD_SAVINGS_NEW_SKIN =
        "TQEysmuZNN+Lcy4hG5jwHlsbTSsjdOE4i6P4ZL9SeOuYEpCAkF/9eSDrquTEGFmi"
    const val URL_SELF_DATA_PAWN_GOLD =
        "TQEysmuZNN+Lcy4hG5jwHsOHfgPM1VJx+/mfw2UGBHDmBgJ7velVShHMcvuw8BiX"
    const val URL_CONVERSION_AMOUNT_PAWN_GOLD_SAVINGS =
        "TQEysmuZNN+Lcy4hG5jwHhK0sjdLG4C/2TiA6dZnExngsHjlRT4yGN+rTXIEv3G2"
    const val URL_INQUIRY_INITIAL_PAWN_GOLD_SAVINGS_NEW_SKIN =
        "TQEysmuZNN+Lcy4hG5jwHhejOWGoPgGTD3T879uCXjOQ132IKQtV4JJ3OKKv9nnW"
    const val URL_OPEN_COUNT_PAWN_GOLD_SAVINGS =
        "TQEysmuZNN+Lcy4hG5jwHhcUvNjRPsU5sUXXfyOJ5vBt5I8qrFOm9eNlJWDF84Ur"
    const val URL_CONFIRMATION_PAWN_GOLD_SAVINGS =
        "TQEysmuZNN+Lcy4hG5jwHs7fd/FVdhVc1ztPQx4zP7kNiSdIw5B7WUumZ3UkYWJ2"
    const val URL_PAYMENT_OPEN_PAWN_GOLD_SAVINGS_NEW_SKIN =
        "TQEysmuZNN+Lcy4hG5jwHilr7Bm9pXH3Xq3bnnxcfIE="
    const val URL_VALIDATE_COUNT_SCHEMA_OPEN_PAWN_GOLD_SAVINGS_NEW_SKIN =
        "TQEysmuZNN+Lcy4hG5jwHk7T7Gw9p1BCXI1IczRTIo3fegEjABNEj2RGygodvJ9R"
    const val URL_FORM_PAYMENT_PAWN_GOLD_SAVINGS_NEW_SKIN =
        "657V9BpKPgYGjfe2sL1bcmkL+tgXMsIds2yy3K5z9xo="
    const val URL_COUNT_SCHEMA_PAYMENT_PAWN_GOLD_SAVINGS_NEW_SKIN =
        "TQEysmuZNN+Lcy4hG5jwHnpUGlz/HdZZ0L/M91vLWPEvU5VsItRFMIOHRHMAbj6s"
    const val URL_INQUIRY_PAYMENT_PAWN_GOLD_SAVINGS_NEW_SKIN =
        "WiMADWkraAXmYm1zu9I1gOWUK2EA1cbt0ahsAyzhbnc="
    const val URL_PAYMENT_PAWN_GOLD_SAVINGS_NEW_SKIN =
        "ELJJdsUhQnyn+RTEJpsG88t9DVr9fkMX375cp1XJNA4="
    const val URL_ACCOUNT_LIST = "6XcHuzXPT8Uh/Pggd+tfUdYwWu7OSbxYs/WHKsVq6qA="
    const val URL_SALDO_REKENING = "3i1GKPhCqV0GDOVPQJ/bBg=="

    // GOLD INSTALMENT ENDPOINT
    const val URL_DASHBOARD_GOLD_INSTALLMENT = "l3nI+qRQmSfyGGNZ7eLB1JSTKLkyIsyuyin+M547kqc="
    const val URL_LIST_GOLD_INSTALLMENT = "l3nI+qRQmSfyGGNZ7eLB1Kh3YTyFVx2xq45wlxP10bA="
    const val URL_FORM_DATA_GOLD_INSTALLMENT =
        "l3nI+qRQmSfyGGNZ7eLB1Lv5ogkjWPLGhvgz4Zh0hMyr6CCHf5BGs4vChk4FM4Ks"
    const val URL_DETAIL_PORTO_GOLD_INSTALLMENT =
        "l3nI+qRQmSfyGGNZ7eLB1PPvD+SMFniOkYHoQ1LwXFz43mm64ero8vXG80Uj9WDw"
    const val URL_PAYMENT_INSTALMENT_GOLD_INSTALLMENT =
        "l3nI+qRQmSfyGGNZ7eLB1LQ2JFluARmhnZqTe+1bye5slFgRnWqu+jotT1qD7sy7"
    const val URL_CHOOSE_TENOR_GOLD_INSTALLMENT =
        "l3nI+qRQmSfyGGNZ7eLB1LQpSPzg2bK7+AyZPqNW2jGNIRU3M3WOzyi/3QB196yW"
    const val URL_COUNT_SCHEMA_GOLD_INSTALLMENT =
        "l3nI+qRQmSfyGGNZ7eLB1HPqreXkDQKxIm16nVH6wcgGYXp4twoZpt8rSRl/juNM"
    const val URL_OPEN_CONFIRMATION_GOLD_INSTALLMENT =
        "l3nI+qRQmSfyGGNZ7eLB1OUccBNUXySubhRi+YG9FoXnFhi2D9+2C4lAeZHI1PIO"
    const val URL_CHOOSE_VENDOR_GOLD_INSTALLMENT =
        "l3nI+qRQmSfyGGNZ7eLB1LQpSPzg2bK7+AyZPqNW2jGlT2H8w9w0IZk8tbreilFW"
    const val URL_PRICE_LIST_GOLD_INSTALLMENT = "l3nI+qRQmSfyGGNZ7eLB1O6AfTkClpNwZeJWvrlD8Ks="
    const val URL_PAYMENT_OPEN_GOLD_INSTALLMENT =
        "l3nI+qRQmSfyGGNZ7eLB1E0isNBHscmI4HsdJuS0G3OAvDAj0vidShjArcW0EC3D"
    const val URL_PAYMENT_GOLD_INSTALLMENT =
        "rpGw5nLaR929TUgCDS7cz5l2bS5rNtD7vcU0OEUBe/N9hCrQeDfwtG4k6puJZJuA"
    const val URL_ONBOARD_GOLD_INSTALLMENT = "l3nI+qRQmSfyGGNZ7eLB1NbOWkQ56mI0dJkrJNnS2O0="

    const val URL_V5_ACCOUNT_LIST = "6XcHuzXPT8Uh/Pggd+tfUdYwWu7OSbxYs/WHKsVq6qA="

    // DYNAMIC FAST MENU
    const val V1_NEW_DYNAMIC_SCREEN = "CYJ8LkEuN6oLkPX/NAva/fJVBqd4Ms5mxJhd1OumLss="

    // Notification List Read, Unread
    const val V1_NOTIFICATION_SET_NOTIFICATION_AS_READ =
        "x+GZqTvsDFDrkS8SqiQj6Sa9s1XYoFt5ziPxaqCdjDBuPh+PCYJpV2PuFEREo39I"
    const val V1_NOTIFICATION_GET_READ_NOTIFICATION =
        "x+GZqTvsDFDrkS8SqiQj6QBmn19nja1/kGj7MVDWJBJDXs3PX2dgU3zVkiTP7Oll"
    // URL Notification settings

    const val URL_V1_GET_DETAIL_NOTIF = "/8uc+rxnUF7Af3437pkzy1Jyk+7Ra516a2ZfIvbHO7c="
    const val URL_V1_REGISTRATION_NOTIF = "qqx3rN/L7XZl2TgaagivlyfwQt9lsXLjk1B/mvjaoU0="
    const val URL_V1_UPDATE_DATA_NOTIF = "bxtIx2w4npM4bTGBfXII943HStlXQTZE2LfSsDLJW5k="

    // --- RESPONSE CODE DECLARATIONS -----------------------------------------------------------
    // SUCCESS CODE
    const val RE_SUCCESS = "00"

    // ERROR CODE
    const val RE_FREEZE_DEVICE = "FDF"
    const val RE_FREEZE_ACCOUNT = "FIF"
    const val RE_LIMIT_ATTEMPT = "A9"
    const val RE_VALIDATION_ERROR = "VE"
    const val RE_ATTEMPT_EXPIRED = "OTE"
    const val RE_ACCOUNT_REGISTERED = "REG"
    const val RE_DATA_NOT_MATCH_K4 = "K4"
    const val RE_DATA_NOT_MATCH_K5 = "K5"
    const val RE_MAXIMUM_LIMIT = "ML"
    const val RE_STATUS_NOT_MATCH = "SM"
    const val RE_FRAUDSTER_DETECTED = "HC"
    const val RE_EXCEED_LIMIT = "EL"
    const val RE_LIMIT = "LI"
    const val RE_FACE_NOT_MATCH = "ZLR"
    const val RE_SDK_DOC_LIMIT = "SDL"
    const val RE_SDK_LIVENESS_LIMIT = "SLL"
    const val RE_DATA_NOT_MATCH_K1 = "K1"
    const val RE_DATA_NOT_MATCH_K3 = "K3"
    const val RE_DATA_NOT_MATCH_K2 = "K2"
    const val RE_FREEZE_KTP = "FM"
    const val RE_REJECT_VIDEO = "LVF"
    const val RE_LIMIT_OCR = "LOI"
    const val RE_WRONG_OCR = "OCV"
    const val USER_BLOCKED = "50"
    const val USER_FREEZE = "DIF"
    const val EXPIRED = "93"
    const val RE_MINIMUM_BALANCE = "MB"
    const val NO_EXCEPTION = "05"
    const val LIMIT_PRESIGN = "LI"
    const val LIMIT_LIVENESS = "HSLI"
    const val FACE_NOT_DETECTED = "HSZLR"
    const val CONFIRM_PIN_MISMATCH = "PMP"

    const val VALIDATION_ERROR = "VE"
    const val RE_PHONE_MATCHING_FAILED = "PFW"
    const val RE_PHONE_NEED_UPDATE = "PNU"
    const val FAILED_COMPARE_PASSPORT = "FPC"
    const val FAILED_COMPARE_PASSPORT_MAX_ATTEMPT = "FPCM"
    const val FAILED_SCAN_PASSPORT = "FPS"
    const val FAILED_SCAN_PASSPORT_MAX_ATTEMPT = "FPSM"
    const val EXPIRED_PASSPORT = "PEX"
    const val INVALID_CITIZENSHIP_PASSPORT = "IC"
    const val ERROR_TEXT = "ERROR_TEXT"
    const val BOTTOM_SHEET = "BOTTOM_SHEET"

    const val VALIDATION_PIN_FAILED = "URF"
    const val VE_URF = "URF"
    const val VE_HD = "HD"
    const val VE_CC = "CC"
    const val VE_MCP = "MCP"
    const val VE_SP = "SP"
    const val LIMIT_OTP = "PIF"

    const val NOT_PROCESSED = "NP"
    const val DATA_NOT_FOUND = "DNF"

    // URL FROM strings.xml
    const val URL_LOGOUT_V5 = "8O2bFeG0yftmL+gygivnSw=="
    const val URL_CHANGE_DEVICE_V2 = "9o9LVCwZcNYKZP6kU7h41E+4H/KdV2e2xB55bVWzcbM="
    const val URL_LOGIN_V3 = "IsQKEPYMvEbFHo3X5FldNw=="
    const val URL_LOGIN_V5 = "fsdNtG/v3pqPO2qdcmCX/Q=="
    const val URL_EDIT_USERNAME = "4ni2I83KRpckrdN27rP+7agcqAs1A2HLo6kk51PoaEI="
    const val URL_FORM_ASURANSI = "qulFuIXlqWF9Ym4bm/tqXUrX5+y1DcnwkqBETNLYe/U="
    const val URL_WEBVIEW_NEW = "c1EAHiM9Qsj03IGnzIonYFjUrD/pbpqdKhltctqWq2o="
    const val URL_REVOKE_SESSION = "FXsOpU9/xReVmP/QopsGW13Ys3jXctzTXtDsKaHDorI="
    const val URL_USER_PROFIL_BRIPOINT = "/738R+1Pn+9KNcnG5RC83w=="
    const val URL_BRIPOIN = "gw0CzO5f11k6sR5BaEpprg=="
    const val URL_V2_ACCOUNT_LIST = "Bi7wvYeUqCq3lEHklfDgAg=="
    const val URL_V5_ACCOUNT_LIST_ALL = "6XcHuzXPT8Uh/Pggd+tfUbOgaMo7So3VHQjLBHPZC/g="
    const val URL_V5_ACCOUNT_LIST_FM = "j1EEkMeQ0/HX28L5CI1tREbX2GgbvC2xNJ9KzPRllBs="
    const val URL_V3_MUTATION_LAST_FIVE = "mO2Xg/LTR38yUixbOWQoANQDKp6bgd8+f70oLjneW10="
    const val URL_V3_MUTATION_DATE_RANGE = "Gr4wxedH3bgzKPSUCAqK1tUO20VgeXnkoJ8sQ2lXR4s="
    const val URL_UPDATE_ALIAS = "kw1BZ7x9U/hcFIyx4jB/zMC2gzlWvDK8sgxYyCQuEPA="
    const val URL_DETAIL_PROMO = "MgKChOwVXitAmXv1gosM0hc3t7cIBFI7D/GPsztvm0o="
    const val URL_PROMO_NON_FEATURED = "FewUb1Ib6hnG4FTx0Wz8e/0tOApwzxB0Row76kgEpnQ="
    const val URL_DETAIL_NON_PROMO = "MgKChOwVXitAmXv1gosM0jgHystBOD4vEz+Vns2NgAc="
    const val URL_SET_DEFAULT_REKENING = "kw1BZ7x9U/hcFIyx4jB/zFFkn7I5XkzOAkkDn/FTrzs="
    const val URL_ACTIVITY_LIST_V5 = "sqODg69rnCatBsF9SHiqTFx6MzIN5vWau7Vn+I1MBUY="
    const val URL_NOTIFICATION_READ_FAST_MENU = "x+GZqTvsDFDrkS8SqiQj6ex7N/wpJV9iUdhAKR0ucTc="
    const val URL_FORM_LUPA_PIN_DASHBOARD = "qbPvVi7BbNvw4BzXJAn14oqHGhxTsLfeBZIVzEcnlAg="
    const val URL_LUPA_PIN_OTP_DASHBOARD = "s3IE01sL4vzPAfkmy7N2l+TKXp40AaPkl4tJ6ht+BIM="
    const val URL_UPDATE_PIN_DASHBOARD = "VRYThyFmIRXcCCR048+XTtrRw/lzafJzlpNNZAbjW+8="
    const val URL_RESEND_LUPA_PIN_DASHBOARD = "NY+KL08h+Ereb7Tc6PgnZnNueG+gtEI80QiMSMDQLDk="
    const val URL_FORM_LUPA_PIN_FASTMENU = "4roNXnB5MYRt7FpGr6rd8MRa8p4bokC0m6/xXoanhIU="
    const val URL_LUPA_PIN_OTP_FASTMENU = "DWSw/DUY3R/LNaV9S9M+g4vDQMkMrI5Cy1WT0Mvduss="
    const val URL_UPDATE_PIN_FASTMENU = "PF34uLQ/Pnrpc5poWwJxc1/RanUISlY8uACrYAelIgA="
    const val URL_RESEND_LUPA_PIN_FASTMENU =
        "NY+KL08h+Ereb7Tc6PgnZgOWMZseWr60gTUsb8ZqKuJBGgXqhxx5T9q8FPkd4P3M"
    const val URL_INQUIRY_LTMPT = "SbEjHVZ6KrnwM6yD3wzx/xO1B46ymbLW+FvWf93Ngmc="
    const val URL_CONFIRM_LTMPT = "9+KjHZWzhuWnvR73FmK/Vo1T7fGAYGhonky+2D26mec="
    const val URL_PAY_LTMPT = "9lYmjzrBbNFMrvgQAeqm1UROerH0WAa/FVs/BqErwH4="
    const val URL_VALIDATE_TOP_UP_BRIZZI_FM = "gnxq++gWK8ZbQnmnJjGYTSKGPHXkXOsnldsHfvX8Qvk="
    const val URL_FORM_TRANSFER_ALIAS = "pXl+y3EGpZjO3zizXXTeI6Dbmw1MNCQVm3yio2hxNhw="
    const val URL_INQ_TRANSFER = "pXl+y3EGpZjO3zizXXTeI68kRHtpmWWHV/KcmWEET7s="
    const val URL_CONFIRM_TRANSFER = "pXl+y3EGpZjO3zizXXTeI1x19lpmYRUEEyPCL14pmLY="
    const val URL_PAY_TRANSFER = "pXl+y3EGpZjO3zizXXTeI0bwqxxHhmJRQLCG3cZOLOc="
    const val URL_CHECK_STATUS = "pXl+y3EGpZjO3zizXXTeI2slwoZnkxLK0uTKQRyVtAo="
    const val URL_UPDATE_ALIAS_BIFAST = "Vd6cZQ/VfSqxarmhyxXt0RN64Njok1ANo4ltfTlb/Wo="
    const val URL_FORM_TRANSFER_ALIAS_FM = "NFt64Ji+x0A7sRE4LaHgDBAFk+EHkveHAkLVKT+5H8M="
    const val URL_INQ_TRANSFER_FM = "NFt64Ji+x0A7sRE4LaHgDAJoM6dWDwKfFXyJ0A4WsUs="
    const val URL_CONFIRM_TRANSFER_FM =
        "NFt64Ji+x0A7sRE4LaHgDKiVKsjKRPZskK80w7CWSNftxylUw+zudL2JlkuVROqz"
    const val URL_PAY_TRANSFER_FM = "NFt64Ji+x0A7sRE4LaHgDKk1GNfnZGBsVmr0yM1wEqE="
    const val URL_CHECK_STATUS_FM =
        "NFt64Ji+x0A7sRE4LaHgDK+JdNlMWhDDHiQxpKT20DDZ7c46KR3Jn6tN7eJqY6Av"
    const val URL_UNFAVORIT_TRANSFER =
        "86bysH9SZGwS2mBxgm3pjCJEHPtQKJENdRWXEE+PqxIC8T01zgUhF2aiP7JogOZr"
    const val URL_FAVORIT_TRANSFER = "2t3vzlMg7LgdF8hhyDzY3D9MhuA+BTTmKiMvDusX/Ms="
    const val URL_DELETE_TRANSFER = "yJXLTVlYVBCi+LEWiymARQKRKHRAUsjcwF+I8iXToQw="
    const val URL_PROFILE = "S6Ykw51m4SPlsBFOX3UJbA=="
    const val URL_QR_MPM_INQUIRY = "02JnKDc0LRWujQ7DUg9ukA=="
    const val URL_QR_MPM_CONFIRM =
        "cGQzaSsOhoO1KmmvkqZALitB6qZr/F3y+MngXoWc06uNLjkWWIbSl66IUIFmeyMj"
    const val URL_QR_MPM_PAYMENT = "ALySQRi7RzW+eZEVHwypnj1MananXSSt6l8wC6AfeGQ="
    const val URL_QR_MPM_INQUIRY_FM = "Gb70IffTvbgACgbmH8PIIg=="
    const val URL_QR_MPM_CONFIRM_FM =
        "gRBYhDaFFNEBjvO0dX/EQwP2FGEKULlDBG25jbCH+ZR10aCuBe9wa22oqfvtMmbM"
    const val URL_QR_MPM_PAYMENT_FM = "gRBYhDaFFNEBjvO0dX/EQ2GXARJlTFyn0l8EbgSJeRo="
    const val URL_CHECK_STATUS_KARTU = "H02rnfHPfh7GKdWXtaffVQ=="
    const val URL_BLOK_KARTU = "v3+qWmshxHkS8vkJTyBzZg=="
    const val URL_UNBLOK_KARTU = "HL2uwHF9kJuY1hNjEt8qbw=="
    const val URL_RATE_US_SEND = "1w0+e8MXnebw4C/ug7ovfg=="
    const val URL_RATE_US_DATA = "P7ocGY39hCXInqJX5G5YwQ=="
    const val URL_PINJAMAN_INQUIRY = "1YTBodMPFZak2Bnq8jI6m+tTQA/vJEVdGfk8Dx4uhSw="
    const val URL_PINJAMAN_CONFIRMATION = "c4Eb0uKN043V5Lr7iSPX5FWRnDKMRLfFfv1wJ3m4C7o="
    const val URL_PINJAMAN_PAYMENT = "3Jroil8aZyTYTkxWO0s4Gl1Q0OI0tu9z6DrdmjAvnnU="
    const val URL_CHAT_BANKING_STATUS = "HNXYc8b8K7HKdNunzivXXnqwaPTCGc+ccWvc1lCHCXI="
    const val URL_GET_LIST_LIMIT_CARD = "DkssMpnyZ3+YsKlaeIMDazXyBocl6QRYiwPXhXZDaRY="
    const val URL_GET_DETAIL_LIMIT_CARD = "DaGLadYg8EDtDRXMO5tWrmBWXRlrntgUzoHXEyc3sLA="
    const val URL_SUBMIT_CUSTOM_LIMIT_CARD = "qybWz1npMY1VYvBOTeUJ1bhcdRmDmrhJE3V5VoGBaa0="
    const val URL_CARD_ENABLE_DISABLE_TRANSACTION =
        "NdS8QvBRFYe7ncT3axfGCUbBIU103E/+3GNsXLTxPrlRhwK3uEbVWoXlJ+UGXt+G"
    const val URL_CARD_MANAGEMENT_LIST_V2 = "MnYJUbZVBkEO0QhAXVL6zt81oAqXCVqz76RswGctkmY="
    const val URL_CARD_MANAGEMENT_CARD_DETAIL_V2 = "MnYJUbZVBkEO0QhAXVL6zsbk6sIBwJaPD2uwAySZmxo="
    const val URL_CARD_MANAGEMENT_INIT_CHANGE_PIN_V5 =
        "ZTqpqCMp/G7sGQxLnmGaWRpknoTJLGjIbHd3oSzpgzRizFTo/7NEapXn37wBt7A0"
    const val URL_CARD_MANAGEMENT_CHANGE_PIN_V5 = "ZTqpqCMp/G7sGQxLnmGaWWe/NP+ox1Ac1lvKoNKumeM="
    const val URL_FORM_BUS = "ShTR1exCT4rcny42/bFyEA=="
    const val URL_TRAVEL_KAI_WEBVIEW = "5/vG/xP2BImfXeU/VogG+AifM/Y3XgLxpvMQ9IF9b7w="
    const val FLAGGING_URL_ACTIVATION = "activation-link-url"
    const val URL_SAFETY_MODE_FM = "pHhSBo+8VYWGx1BxIbVqYi/VeWirCP46HsyGKIwLZIU="
    const val URL_ONBOARDING_BRIMO_V5 = "cTqAHxciQ0fSx4fJPJ4bGiGhDOwIuyf0cjeXzAp3m4E="
    const val URL_GET_LIST_VOIP = "0EqkfnSDDD6qbERibFN36NIrjs6FMAzQ5ptRLztXW/8="
    const val URL_MNV_REQUEST = "XWbYgw74u4NgLkyTp9Y/Gg=="
    const val URL_MNV_CHECK = "GXCrEZ/aZSqM4t7o6Cnlrg=="
    const val URL_QRIC_CB_CONFIRMATION = "4QY8JB1TdjzUQiWg+gdlCLgN/fttdCOSlxK1sAyE+7I="
    const val URL_QRIC_CB_CONFIRMATION_FM = "4QY8JB1TdjzUQiWg+gdlCP32wT4/ep5RO0+LXpM9Tgg="
    const val URL_DASHBOARD_LIFESTYLE_MENU = "TkbvRSsCyGjF/W1Z7mZk1v77dAOsU6eu9xnZ2WZpvS0="
    const val URL_DASHBOARD_LIFESTYLE_PAYMENT = "glqd36s3dQYa/8Rb0mg61OiLmamTMsWocxfSrYb1sjo="
    const val URL_BLOCK_CARD = "+cFZjo/kew52DNwKW4Xa3VEehwmTQAtmIrXaV1BbSdw="
    const val URL_GENERATE_PAYLOAD_FM = "ziM9XOo3ZgCnFZy7basZtsr7tU8flcN3EeY5QThzPHc="
    const val URL_INFO_KUPON_BRIMO_FESTIVAL_V3 = "3zPBukiiClifV5MbIxDmsQOSynzmOtyUfLm9IicXH0k="
    const val URL_SET_AKTIVASI_VOICE_ASSISTANT = "6dAwPbnb73BdXXMhICb8tw1rpE9XK3Vq7aLMLQbRDjw="
    const val URL_GET_AKTIVASI_VOICE_ASSISTANT = "6dAwPbnb73BdXXMhICb8t/I6wh6XUrws+escOoADsTw="
    const val URL_TENTANG_BRIMO = "44eYzyDnZUEDJxrLp6arUA=="
    const val SMART_TRANSFER_USER_CONSENT_URL =
        "K/8Tw/YPBaqElbLIoIl/FDrxlg2u/pRTFsTZIHe0sPGmf2EbnXrI62ygxbptZJa9/O"
    const val SMART_TRANSFER_MANAGE_USER_CONSENT_URL =
        "K/8Tw/YPBaqElbLIoIl/FNNBWp3af5yt8ASKERcDeQ+gw72Gwd8gVmU9Be+ImZxO"
    const val URL_PREFRENCES_BILINGUAL = "ZWWsg5HcMb4nwZOUIF/ytHCbv6d09kz0Du29SRQKiBk="
    const val URL_PREFRENCES_BILINGUAL_FAST_MENU = "1dFsL2jdKyzQ3shx5mAjnHBDOPcH8chNR4Srf4TaucA="
    const val URL_ONBOARDING_BRIMO_SEND_OTP_PHONE_V4 =
        "wBMb8VOt8RjEmF7jqg4vkBsmOw3vOkSvuecfvI+njuLdrshM3JfSJ9xeXcS2km/7"
    const val URL_V5_BUNDLE_LIST = "A669lCT/s0HHBic3xWPczQ=="
    const val URL_V5_GET_PUBLIC_KEY = "bDnFDyYVsKldB/xBpr3kBblW6fvGZPRPpvNF+A0VHdo="
    const val URL_V5_GET_BUNDLE_BY_VERSION_IDS = "2t6UEyfo2790qg/IIpP2JaTLtXKmxjl5eE9whSPCmO8="
    const val URL_CETAK_TOKEN_PLN = "3X8y/y3yLuFpRLEsKH7OlFUhBOUlBCtDkSm6vfNQoTk="
    const val URL_ACTIVITY_DETAIL = "yIGD6fkdBalN3N8OXZi1yExM6vQvs3EvKKgL7BFkk4c="
    const val URL_PDN_CHECK = "09x+wupcXv3EhYAdGeSqRg=="
}

```

**File: `core/network/src/main/kotlin/id/co/bri/brimons/core/network/di/CookieModule.kt`**
```kotlin
package id.co.bri.brimons.core.network.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import id.co.bri.brimons.core.network.di.qualifier.SessionCookieJar
import id.co.bri.brimons.core.network.utils.cookie.ClearableCookieJar
import id.co.bri.brimons.core.network.utils.cookie.SharedPrefsCookieJar
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CookieModule {

    @Provides
    @Singleton
    @SessionCookieJar
    fun provideSessionCookieJar(cookieJar: SharedPrefsCookieJar): ClearableCookieJar = cookieJar
}

```

**File: `core/network/src/main/kotlin/id/co/bri/brimons/core/network/di/FileDownloaderModule.kt`**
```kotlin
package id.co.bri.brimons.core.network.di

import android.content.Context
import com.brimo.wormaceptor.api.BrimoCeptorInterceptor
import com.datatheorem.android.trustkit.pinning.OkHttp3Helper
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import id.co.bri.brimons.core.network.BuildConfig
import id.co.bri.brimons.core.network.NetworkConfig
import id.co.bri.brimons.core.network.utils.BodyDecryptor
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class FileDownloadClient

@Module
@InstallIn(SingletonComponent::class)
object FileDownloaderModule {

    @Provides
    @Singleton
    fun provideFilesDir(@ApplicationContext context: Context): File = context.filesDir

    @Provides
    @Singleton
    @FileDownloadClient
    fun provideFileDownloadClient(): OkHttpClient {
        val bodyDecryptor = BodyDecryptor()

        val logging =
            HttpLoggingInterceptor().apply {
                level =
                    if (BuildConfig.DEBUG) {
                        HttpLoggingInterceptor.Level.BODY
                    } else {
                        HttpLoggingInterceptor.Level.NONE
                    }
            }

        val brimoCeptor = BrimoCeptorInterceptor().addBodyDecoder(bodyDecryptor)

        val builder =
            if (BuildConfig.DEBUG) {
                OkHttpClient.Builder()
            } else {
                OkHttpClient.Builder()
                    .sslSocketFactory(
                        OkHttp3Helper.getSSLSocketFactory(),
                        OkHttp3Helper.getTrustManager(),
                    )
                    .addInterceptor(OkHttp3Helper.getPinningInterceptor())
            }

        return builder
            .addInterceptor(brimoCeptor)
            .addInterceptor(logging)
            .connectTimeout(NetworkConfig.TIMEOUT_CONNECT.toLong(), TimeUnit.SECONDS)
            .readTimeout(NetworkConfig.TIMEOUT_READ.toLong(), TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}

```

**File: `core/network/src/main/kotlin/id/co/bri/brimons/core/network/di/NetworkModule.kt`**
```kotlin
package id.co.bri.brimons.core.network.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import id.co.bri.brimons.core.network.NetworkConfig
import id.co.bri.brimons.core.network.api.ApiInterface
import id.co.bri.brimons.core.network.api.MinioInterface
import id.co.bri.brimons.core.network.api.NetworkSource
import id.co.bri.brimons.core.network.api.NewApiClient
import id.co.bri.brimons.core.network.di.qualifier.ApplicationScope
import id.co.bri.brimons.core.network.di.qualifier.MigrateNewNetwork
import id.co.bri.brimons.core.network.di.qualifier.MinioNetwork
import id.co.bri.brimons.core.network.di.qualifier.SessionCookieJar
import id.co.bri.brimons.core.network.utils.cookie.ClearableCookieJar
import id.co.bri.brimons.core.preference.impl.session.SessionPreference
import id.co.bri.brimons.core.util.dispatcher.IODispatcher
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    @Singleton
    @MigrateNewNetwork
    fun provideOkHttpClient(
        @ApplicationContext context: Context,
        @SessionCookieJar cookieJar: ClearableCookieJar,
    ): OkHttpClient = NewApiClient.getOkHttpClient(context, cookieJar = cookieJar)

    @Provides
    @Singleton
    @MigrateNewNetwork
    fun provideRetrofit(@MigrateNewNetwork okHttpClient: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(NetworkConfig.getBaseUrl())
            .addConverterFactory(ScalarsConverterFactory.create())
            .client(okHttpClient)
            .build()

    @Provides
    @Singleton
    @MigrateNewNetwork
    fun provideApiInterface(@MigrateNewNetwork retrofit: Retrofit): ApiInterface =
        retrofit.create(ApiInterface::class.java)

    @Provides
    @Singleton
    @MinioNetwork
    fun provideMinioOkHttpClient(): OkHttpClient = NewApiClient.getMinioOkHttpClient()

    @Provides
    @Singleton
    @MigrateNewNetwork
    fun provideNetworkSource(
        @ApplicationContext context: Context,
        preference: SessionPreference,
        @MigrateNewNetwork apiInterface: ApiInterface,
        @IODispatcher dispatcher: CoroutineDispatcher,
    ): NetworkSource =
        NetworkSource(
            context = context,
            preference = preference,
            apiInterface = apiInterface,
            dispatcher = dispatcher,
        )

    @Provides
    @Singleton
    @MinioNetwork
    fun provideMinioRetrofit(@MinioNetwork okHttpClient: OkHttpClient): Retrofit =
        Retrofit.Builder().baseUrl(NetworkConfig.getEndPointMinio()).client(okHttpClient).build()

    @Provides
    @Singleton
    @MinioNetwork
    fun provideMinioInterface(@MinioNetwork retrofit: Retrofit): MinioInterface =
        retrofit.create(MinioInterface::class.java)
}

```

**File: `core/network/src/main/kotlin/id/co/bri/brimons/core/network/di/NetworkObserverModule.kt`**
```kotlin
package id.co.bri.brimons.core.network.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import id.co.bri.brimons.core.network.connectivity.INetworkObserver
import id.co.bri.brimons.core.network.connectivity.NetworkObserverImpl
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkModuleBinds {
    @Binds @Singleton abstract fun bindNetworkObserver(impl: NetworkObserverImpl): INetworkObserver
}

```

**File: `core/network/src/main/kotlin/id/co/bri/brimons/core/network/di/qualifier/NetworkQualifier.kt`**
```kotlin
package id.co.bri.brimons.core.network.di.qualifier

import javax.inject.Qualifier

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class MigrateNewNetwork

@Qualifier @Retention(AnnotationRetention.RUNTIME) annotation class ApplicationScope

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class MinioNetwork

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class SessionCookieJar

```

**File: `core/network/src/main/kotlin/id/co/bri/brimons/core/network/erangel/ErangelConstant.kt`**
```kotlin
package id.co.bri.brimons.core.network.erangel

object ErangelConstant {
    const val TAG_SPACER = "{$$}"
    const val TAG_DEVICE = "2"
    const val SEQ_NUM_KEY = "YnJpbW9fc2VxdW5jZV9udW1i"
}

```

**File: `core/network/src/main/kotlin/id/co/bri/brimons/core/network/erangel/ErangelRequest.kt`**
```kotlin
package id.co.bri.brimons.core.network.erangel

import android.content.Context
import android.os.Build
import id.co.bri.brimons.core.network.erangel.ErangelConstant.SEQ_NUM_KEY
import id.co.bri.brimons.core.network.erangel.ErangelConstant.TAG_DEVICE
import id.co.bri.brimons.core.network.erangel.ErangelConstant.TAG_SPACER
import id.co.bri.brimons.core.network.utils.generateRandomXKey
import id.co.bri.brimons.core.network.utils.getLastAppVersion
import id.co.bri.brimons.core.security.crypto.encryptAesGcmBase64
import id.co.bri.brimons.core.security.crypto.getPaddedSeqnum
import id.co.bri.brimons.core.security.crypto.md5

data class ErangelRequest(
    val xRandomKey: String,
    val xDeviceId: String,
    val xDevice: String,
    val xId: String,
    val request: String,
    val xSequenceKey: String,
    val isDklC2: Boolean?,
)

fun createErangelRequest(
    context: Context,
    seqNumb: String,
    rawRequest: String,
    deviceId1: String,
    deviceId2: String,
    isDklC2: Boolean? = null,
): ErangelRequest {

    val paddedSeq = getPaddedSeqnum(seqNumb)

    val encryptedRequest = rawRequest.encryptWithSeq(paddedSeq)
    val checksum = encryptedRequest.generateChecksum()

    val finalRequestBody = checksum + encryptedRequest

    return ErangelRequest(
        xRandomKey = generateRandomXKey(seqNumb, encryptedRequest),
        xDeviceId = deviceId1,
        xDevice = buildDeviceInfo(context),
        xId = deviceId2,
        request = finalRequestBody,
        xSequenceKey = seqNumb.encryptSequenceKey(),
        isDklC2 = isDklC2,
    )
}

private fun String.encryptWithSeq(paddedSeq: String): String {
    return encryptAesGcmBase64(this, paddedSeq)
}

private fun String.generateChecksum(): String {
    return md5(this)
}

private fun buildDeviceInfo(context: Context): String {
    val appVersion = getLastAppVersion(context)

    return listOf(TAG_DEVICE, Build.MODEL.orEmpty(), Build.VERSION.RELEASE.orEmpty(), appVersion)
        .joinToString(TAG_SPACER)
}

private fun String.encryptSequenceKey(): String {
    return encryptAesGcmBase64(this, SEQ_NUM_KEY)
}

```

**File: `core/network/src/main/kotlin/id/co/bri/brimons/core/network/erangel/ErangelResponseParser.kt`**
```kotlin
package id.co.bri.brimons.core.network.erangel

import id.co.bri.brimons.core.model.response.RestResponse
import id.co.bri.brimons.core.network.erangel.ErangelConstant.SEQ_NUM_KEY
import id.co.bri.brimons.core.security.crypto.decryptAesGcm
import id.co.bri.brimons.core.security.crypto.getZeroPaddedData
import id.co.bri.brimons.core.security.crypto.md5
import id.co.bri.brimons.core.util.ext.fromJsonString
import id.co.bri.brimons.core.util.logcat

fun String.toRestResponse(seqNumber: String): RestResponse? {
    return runCatching {
            val cleaned = this.replace("\"", "").replace("\\", "")

            val responseId = cleaned.take(32)
            if (responseId.isEmpty()) return null

            val seqqqNumber = decryptAesGcm(seqNumber, SEQ_NUM_KEY)

            val key = responseId.take(8) + getZeroPaddedData(seqqqNumber)

            val cipher = cleaned.substring(32).replace(" ", "+")

            val plain = decryptAesGcm(cipher, key)
            if (plain.isEmpty()) return null

            if (responseId != md5(plain)) return null
            logcat {
                "Success parsing RestResponse: ${
                plain.fromJsonString<RestResponse>()
            }"
            }

            plain.fromJsonString<RestResponse>()
        }
        .getOrElse {
            logcat { "Failed parsing RestResponse: ${it.message}" }
            null
        }
}

```

**File: `core/network/src/main/kotlin/id/co/bri/brimons/core/network/legacy/BaseResponse.kt`**
```kotlin
package id.co.bri.brimons.core.network.legacy

import com.google.gson.annotations.SerializedName

@Deprecated(
    "Legacy Gson response envelope, parsed by processApi(). EncryptedCallExecutor parses the " +
        "envelope internally, so callers no longer need this wrapper type."
)
data class BaseResponse<T>(
    @SerializedName("code") val code: String?,
    @SerializedName("description") val description: String?,
    @SerializedName("data") val data: T?,
)

@Deprecated(
    "Legacy Gson response envelope, parsed by processApi(). EncryptedCallExecutor parses the " +
        "envelope internally, so callers no longer need this wrapper type."
)
data class ErrorResponse(
    @SerializedName("title") val title: String?,
    @SerializedName("description", alternate = ["message"]) val description: String?,
)

@Deprecated(
    "Thrown by the legacy processApi(). EncryptedCallExecutor reports failures as " +
        "GeneralApiException/ApiErrorException via ExceptionMapper instead."
)
class MessageException(val code: String, val description: String) : Exception()

@Deprecated(
    "Thrown by the legacy processApi(). EncryptedCallExecutor reports failures as " +
        "GeneralApiException/ApiErrorException via ExceptionMapper instead."
)
class PinException(val code: String, val description: String) : Exception()

```

**File: `core/network/src/main/kotlin/id/co/bri/brimons/core/network/legacy/NetworkConstant.kt`**
```kotlin
package id.co.bri.brimons.core.network.legacy

private const val DEPRECATION_MESSAGE =
    "Legacy response code used by the deprecated processApi(). Use core:model's " +
        "ResponseCode enum instead."

// Success
@Deprecated(DEPRECATION_MESSAGE) const val CODE_SUCCESS = "00"

// PIN
@Deprecated(DEPRECATION_MESSAGE) const val CODE_PIN_ERROR = "PIN"
@Deprecated(DEPRECATION_MESSAGE) const val CODE_PIN_INVALID = "URF"
@Deprecated(DEPRECATION_MESSAGE) const val CODE_PIN_BLOCKED = "50"

// Session
@Deprecated(DEPRECATION_MESSAGE) const val CODE_ANOTHER_DEVICE = "19"
@Deprecated(DEPRECATION_MESSAGE) const val CODE_SESSION_EXPIRED = "05"

@Deprecated(DEPRECATION_MESSAGE) const val CODE_DELETE_USER = "UD04"

// Limit
@Deprecated(DEPRECATION_MESSAGE) const val CODE_LIMIT_HIT = "06"
@Deprecated(DEPRECATION_MESSAGE) const val CODE_LIMIT_TRANSACTION = "61"

// Transaction
@Deprecated(DEPRECATION_MESSAGE) const val CODE_TRANSACTION_FAILED = "01"
@Deprecated(DEPRECATION_MESSAGE) const val CODE_TRANSACTION_INSUFFICIENT = "51"
@Deprecated(DEPRECATION_MESSAGE) const val CODE_TRANSACTION_EXPIRED = "93"

// Maintenance
@Deprecated(DEPRECATION_MESSAGE) const val CODE_MAINTENANCE = "MT"
// Account
@Deprecated(DEPRECATION_MESSAGE) const val ACCOUNT_NOT_AVAILABLE = "41"

// DataException codes that produce a full error bottom sheet
@Suppress("DEPRECATION")
@Deprecated(DEPRECATION_MESSAGE)
val CODE_DATA_EXCEPTION_LIST =
    listOf(
        CODE_ANOTHER_DEVICE,
        CODE_SESSION_EXPIRED,
        CODE_LIMIT_HIT,
        CODE_LIMIT_TRANSACTION,
        CODE_TRANSACTION_FAILED,
        CODE_TRANSACTION_INSUFFICIENT,
        CODE_TRANSACTION_EXPIRED,
        CODE_MAINTENANCE,
        ACCOUNT_NOT_AVAILABLE,
        CODE_DELETE_USER,
    )

```

**File: `core/network/src/main/kotlin/id/co/bri/brimons/core/network/legacy/helper/ProcessApi.kt`**
```kotlin
package id.co.bri.brimons.core.network.legacy.helper

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import id.co.bri.brimons.core.model.exception.GeneralApiException
import id.co.bri.brimons.core.network.legacy.BaseResponse
import id.co.bri.brimons.core.network.legacy.CODE_DATA_EXCEPTION_LIST
import id.co.bri.brimons.core.network.legacy.CODE_PIN_BLOCKED
import id.co.bri.brimons.core.network.legacy.CODE_PIN_ERROR
import id.co.bri.brimons.core.network.legacy.CODE_PIN_INVALID
import id.co.bri.brimons.core.network.legacy.CODE_SUCCESS
import id.co.bri.brimons.core.network.legacy.ErrorResponse
import id.co.bri.brimons.core.network.legacy.MessageException
import id.co.bri.brimons.core.network.legacy.PinException
import id.co.bri.brimons.core.network.legacy.response.CheckPinResponse

@Deprecated(
    "Legacy Gson response parsing. Inject core:network's EncryptedCallExecutor and call " +
        "executor.post/get instead."
)
inline fun <reified T> processApi(
    customSuccess: List<String> = listOf(CODE_SUCCESS),
    action: () -> String?,
): T {
    val response = action()
    val type = object : TypeToken<BaseResponse<T>>() {}.type
    val model = Gson().fromJson<BaseResponse<T>>(response, type)
    return if (model.code == CODE_SUCCESS || model.code in customSuccess) {
        if (T::class == Unit::class) {
            Unit as T
        } else {
            model.data
                ?: throw MessageException(
                    code = model.code.orEmpty(),
                    description = model.description.orEmpty(),
                )
        }
    } else if (model.code == CODE_PIN_INVALID || model.code == CODE_PIN_BLOCKED) {
        val pinType = object : TypeToken<BaseResponse<CheckPinResponse>>() {}.type
        val pinModel = Gson().fromJson<BaseResponse<CheckPinResponse>>(response, pinType)
        if (model.code == CODE_PIN_INVALID) {
            throw PinException(
                code = model.code,
                description = pinModel.data?.description.orEmpty(),
            )
        } else {
            throw GeneralApiException(
                code = CODE_PIN_ERROR,
                image = "",
                title = pinModel.data?.title.orEmpty(),
                description = pinModel.data?.description.orEmpty(),
            )
        }
    } else {
        val errorType = object : TypeToken<BaseResponse<ErrorResponse>>() {}.type
        val errorModel = Gson().fromJson<BaseResponse<ErrorResponse>>(response, errorType)
        if (errorModel.data != null || model.code in CODE_DATA_EXCEPTION_LIST) {
            throw GeneralApiException(
                code = model.code.orEmpty(),
                image = "",
                title = errorModel.data?.title.orEmpty(),
                description =
                    errorModel.data?.description.orEmpty().ifEmpty { model.description.orEmpty() },
            )
        } else {
            throw MessageException(
                code = model.code.orEmpty(),
                description = model.description.orEmpty(),
            )
        }
    }
}

```

**File: `core/network/src/main/kotlin/id/co/bri/brimons/core/network/legacy/response/CheckPinResponse.kt`**
```kotlin
package id.co.bri.brimons.core.network.legacy.response

import com.google.gson.annotations.SerializedName

@Deprecated(
    "Legacy Gson DTO for the deprecated processApi()/BaseResponse pin-check flow. No existing " +
        "modern equivalent - define a kotlinx.serialization response type when migrating this " +
        "call to EncryptedCallExecutor."
)
data class CheckPinResponse(
    @SerializedName("title") val title: String?,
    @SerializedName("description") val description: String?,
    @SerializedName("error_type") val errorType: String?,
)

```

**File: `core/network/src/main/kotlin/id/co/bri/brimons/core/network/utils/ApiUtils.kt`**
```kotlin
package id.co.bri.brimons.core.network.utils

import android.content.Context
import android.content.pm.PackageManager
import id.co.bri.brimons.core.security.crypto.SecurityConstants
import id.co.bri.brimons.core.security.crypto.encryptAesGcmBase64
import id.co.bri.brimons.core.security.crypto.md5
import id.co.bri.brimons.core.util.logcat

fun generateRandomXKey(seqNum: String?, request: String?): String {
    val requestHash = md5(request.orEmpty())
    val requestIv = requestHash.substring(SecurityConstants.BEGIN_IV, SecurityConstants.END_IV)

    return encryptAesGcmBase64(seqNum.orEmpty(), requestIv)
}

fun getLastAppVersion(context: Context): String {
    return runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        }
        .onFailure { error ->
            if (error is PackageManager.NameNotFoundException) {
                logcat { "Failed to get app version: ${error.message}" }
            }
        }
        .getOrDefault("")
}

```

**File: `core/network/src/main/kotlin/id/co/bri/brimons/core/network/utils/BodyDecryptor.kt`**
```kotlin
package id.co.bri.brimons.core.network.utils

import com.brimo.wormaceptor.api.BodyDecoder
import id.co.bri.brimons.core.network.api.ApiInterface.Companion.HEADER_KEY_SEQUENCE
import id.co.bri.brimons.core.network.erangel.ErangelConstant.SEQ_NUM_KEY
import id.co.bri.brimons.core.security.crypto.decryptAesGcm
import id.co.bri.brimons.core.security.crypto.getPaddedSeqnum
import id.co.bri.brimons.core.security.crypto.getZeroPaddedData
import id.co.bri.brimons.core.util.logcat
import java.net.URLDecoder
import okhttp3.Request
import okhttp3.Response

class BodyDecryptor : BodyDecoder {

    override fun decodeRequest(requestBody: Request, requestString: String): String {
        val key = requestBody.header(HEADER_KEY_SEQUENCE) ?: return requestString
        val decryptedKey = getDecryptedKey(key)
        val strRequest = requestString.replace("\"", "").replace("\\", "").substringAfter("=")
        val reqId = strRequest.take(32)
        if (reqId.isNotEmpty()) {
            val strCipher = strRequest.substring(32)
            val strDecode = URLDecoder.decode(strCipher, "UTF-8")
            val strPlain = decryptAesGcm(strDecode, getPaddedSeqnum(decryptedKey))
            return strPlain.ifEmpty { requestString }
        }
        return requestString
    }

    override fun decodeResponse(responseBody: Response, responseString: String): String {
        val strResponse = responseString.replace("\"", "").replace("\\", "")
        try {
            val key = responseBody.request.header(HEADER_KEY_SEQUENCE) ?: return strResponse
            val seqNum = getDecryptedKey(key)
            val responseId = strResponse.take(32)
            if (responseId.isNotEmpty()) {
                val keyString = responseId.take(8) + getZeroPaddedData(seqNum)
                val strCipher = strResponse.substring(32)
                val strPlain = decryptAesGcm(strCipher, keyString)
                if (strPlain.isNotEmpty()) return strPlain
            }
        } catch (e: Exception) {
            logcat { "checkError ${e.message}" }
            // do-nothing
        }
        return strResponse
    }

    private fun getDecryptedKey(key: String): String = decryptAesGcm(key, SEQ_NUM_KEY)

    companion object {
        const val CONTENT_TYPE_JSON = "application/json; charset=UTF-8"
    }
}

```

**File: `core/network/src/main/kotlin/id/co/bri/brimons/core/network/utils/FileDownloader.kt`**
```kotlin
package id.co.bri.brimons.core.network.utils

import id.co.bri.brimons.core.network.di.FileDownloadClient
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class FileDownloader
@Inject
constructor(
    private val filesDir: File,
    @FileDownloadClient private val okHttpClient: OkHttpClient,
) {
    companion object {
        private const val DOWNLOADS_DIR = "downloads"
    }

    /**
     * Downloads a file from [url] and saves it to app internal storage.
     *
     * The downloaded file is stored in the app's internal `filesDir/downloads` directory with a
     * unique file name.
     *
     * @param url URL of the file to download.
     * @param fileName Name of the downloaded file.
     * @return [Result] containing the downloaded [File] when successful, or the encountered
     *   exception when the download fails.
     */
    suspend fun downloadFromUrl(url: URL, fileName: String): Result<File> =
        withContext(Dispatchers.IO) {
            var outputFile: File? = null

            try {
                outputFile = createDownloadFile(fileName)

                val request = Request.Builder().url(url).get().build()

                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("Download failed: HTTP ${response.code}")
                    }

                    val body =
                        response.body ?: throw IOException("Download failed: empty response body")

                    body.byteStream().use { input ->
                        FileOutputStream(outputFile).use { output -> input.copyTo(output) }
                    }
                }

                Result.success(outputFile)
            } catch (exception: Exception) {
                outputFile?.delete()
                Result.failure(exception)
            }
        }

    private fun createDownloadFile(fileName: String): File {
        val directory = File(filesDir, DOWNLOADS_DIR)

        if (!directory.exists()) {
            val created = directory.mkdirs()

            if (!created) {
                throw IOException("Unable to create directory: ${directory.absolutePath}")
            }
        }

        return File(directory, "${UUID.randomUUID()}_$fileName")
    }
}

```

**File: `core/network/src/main/kotlin/id/co/bri/brimons/core/network/utils/cookie/ClearableCookieJar.kt`**
```kotlin
package id.co.bri.brimons.core.network.utils.cookie

import okhttp3.CookieJar

interface ClearableCookieJar : CookieJar {
    fun clear()
}

```

**File: `core/network/src/main/kotlin/id/co/bri/brimons/core/network/utils/cookie/CookieMapper.kt`**
```kotlin
package id.co.bri.brimons.core.network.utils.cookie

import okhttp3.Cookie

internal val Cookie.storageKey: String
    get() = "$name|$domain|$path"

internal fun Cookie.toSnapshot(): CookieSnapshot {
    return CookieSnapshot(
        name = name,
        value = value,
        expiresAt = expiresAt,
        domain = domain,
        path = path,
        secure = secure,
        httpOnly = httpOnly,
        hostOnly = hostOnly,
        persistent = persistent,
    )
}

internal fun CookieSnapshot.toCookie(): Cookie? {
    return runCatching {
            Cookie.Builder()
                .name(name)
                .value(value)
                .expiresAt(expiresAt)
                .path(path)
                .apply {
                    if (hostOnly) {
                        hostOnlyDomain(domain)
                    } else {
                        domain(domain)
                    }

                    if (secure) {
                        secure()
                    }

                    if (httpOnly) {
                        httpOnly()
                    }
                }
                .build()
        }
        .getOrNull()
}

```

**File: `core/network/src/main/kotlin/id/co/bri/brimons/core/network/utils/cookie/CookieSnapshot.kt`**
```kotlin
package id.co.bri.brimons.core.network.utils.cookie

internal data class CookieSnapshot(
    val name: String,
    val value: String,
    val expiresAt: Long,
    val domain: String,
    val path: String,
    val secure: Boolean,
    val httpOnly: Boolean,
    val hostOnly: Boolean,
    val persistent: Boolean,
)

```

**File: `core/network/src/main/kotlin/id/co/bri/brimons/core/network/utils/cookie/CookieSnapshotJsonAdapter.kt`**
```kotlin
package id.co.bri.brimons.core.network.utils.cookie

import org.json.JSONArray
import org.json.JSONObject

internal object CookieSnapshotJsonAdapter {

    fun encode(snapshots: List<CookieSnapshot>): String {
        val array = JSONArray()

        snapshots.forEach { snapshot -> array.put(snapshot.toJsonObject()) }

        return array.toString()
    }

    fun decode(raw: String): List<CookieSnapshot> {
        val array = JSONArray(raw)

        return buildList(array.length()) {
            repeat(array.length()) { index -> add(array.getJSONObject(index).toCookieSnapshot()) }
        }
    }

    private fun CookieSnapshot.toJsonObject(): JSONObject {
        return JSONObject()
            .put(FIELD_NAME, name)
            .put(FIELD_VALUE, value)
            .put(FIELD_EXPIRES_AT, expiresAt)
            .put(FIELD_DOMAIN, domain)
            .put(FIELD_PATH, path)
            .put(FIELD_SECURE, secure)
            .put(FIELD_HTTP_ONLY, httpOnly)
            .put(FIELD_HOST_ONLY, hostOnly)
            .put(FIELD_PERSISTENT, persistent)
    }

    private fun JSONObject.toCookieSnapshot(): CookieSnapshot {
        return CookieSnapshot(
            name = getString(FIELD_NAME),
            value = getString(FIELD_VALUE),
            expiresAt = getLong(FIELD_EXPIRES_AT),
            domain = getString(FIELD_DOMAIN),
            path = getString(FIELD_PATH),
            secure = getBoolean(FIELD_SECURE),
            httpOnly = getBoolean(FIELD_HTTP_ONLY),
            hostOnly = getBoolean(FIELD_HOST_ONLY),
            persistent = getBoolean(FIELD_PERSISTENT),
        )
    }

    private const val FIELD_NAME = "name"
    private const val FIELD_VALUE = "value"
    private const val FIELD_EXPIRES_AT = "expires_at"
    private const val FIELD_DOMAIN = "domain"
    private const val FIELD_PATH = "path"
    private const val FIELD_SECURE = "secure"
    private const val FIELD_HTTP_ONLY = "http_only"
    private const val FIELD_HOST_ONLY = "host_only"
    private const val FIELD_PERSISTENT = "persistent"
}

```

**File: `core/network/src/main/kotlin/id/co/bri/brimons/core/network/utils/cookie/SharedPrefsCookieJar.kt`**
```kotlin
package id.co.bri.brimons.core.network.utils.cookie

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import id.co.bri.brimons.core.security.crypto.decryptAesCbcBase64
import id.co.bri.brimons.core.security.crypto.encryptAesCbcBase64
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Cookie
import okhttp3.HttpUrl

@Singleton
class SharedPrefsCookieJar @Inject constructor(@ApplicationContext context: Context) :
    ClearableCookieJar {

    private val preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private val cache = LinkedHashMap<String, Cookie>().apply { putAll(readPersistedCookies()) }

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()

        cookies.forEach { cookie ->
            if (cookie.isExpired(now)) {
                cache.remove(cookie.storageKey)
            } else {
                cache[cookie.storageKey] = cookie
            }
        }

        persistCookies()
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val hasExpiredCookie = removeExpiredCookies(now)

        if (hasExpiredCookie) {
            persistCookies()
        }

        return cache.values.filter { cookie -> cookie.matches(url) }
    }

    @Synchronized
    override fun clear() {
        cache.clear()
        preferences.edit { remove(KEY_COOKIES) }
    }

    private fun persistCookies() {
        val snapshots =
            cache.values
                .asSequence()
                .filter { cookie -> cookie.persistent }
                .map { cookie -> cookie.toSnapshot() }
                .toList()

        val encodedCookies = CookieSnapshotJsonAdapter.encode(snapshots)
        val encryptedCookies = encryptCookies(encodedCookies)

        preferences.edit { putString(KEY_COOKIES, encryptedCookies) }
    }

    private fun readPersistedCookies(): Map<String, Cookie> {
        val encryptedCookies =
            preferences.getString(KEY_COOKIES, null)?.takeIf(String::isNotBlank)
                ?: return emptyMap()

        val now = System.currentTimeMillis()

        return runCatching {
                val rawCookies = decryptCookies(encryptedCookies)

                CookieSnapshotJsonAdapter.decode(rawCookies)
                    .asSequence()
                    .mapNotNull(CookieSnapshot::toCookie)
                    .filterNot { cookie -> cookie.isExpired(now) }
                    .associateByTo(LinkedHashMap(), Cookie::storageKey)
            }
            .getOrElse {
                preferences.edit { remove(KEY_COOKIES) }
                emptyMap()
            }
    }

    private fun removeExpiredCookies(now: Long): Boolean {
        return cache.entries.removeAll { (_, cookie) -> cookie.isExpired(now) }
    }

    private fun encryptCookies(rawCookies: String): String {
        return encryptAesCbcBase64(rawCookies)
    }

    private fun decryptCookies(encryptedCookies: String): String {
        return decryptAesCbcBase64(encryptedCookies)
    }

    private fun Cookie.isExpired(now: Long): Boolean {
        return expiresAt <= now
    }

    private companion object {
        const val PREF_NAME = "qita_cookies"
        const val KEY_COOKIES = "cookies"
    }
}

```

**File: `core/network/src/main/kotlin/id/co/bri/brimons/core/network/utils/helper/EncryptedCallDsl.kt`**
```kotlin
package id.co.bri.brimons.core.network.utils.helper

import id.co.bri.brimons.core.model.exception.ExceptionHandler
import id.co.bri.brimons.core.model.exception.ExceptionMapper.toMappedException
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlinx.coroutines.CancellationException

@DslMarker annotation class EncryptedCallDsl

@EncryptedCallDsl
class EncryptedCallScope
@PublishedApi
internal constructor(@PublishedApi internal val executor: EncryptedCallExecutor) {

    private val errorHandlers = mutableListOf<ExceptionHandler>()

    @PublishedApi internal var requestEngine: NetworkJsonEngine = NetworkJsonEngine.KOTLINX

    @PublishedApi internal var responseEngine: NetworkJsonEngine = NetworkJsonEngine.KOTLINX

    @PublishedApi internal val acceptedCodes: MutableSet<String> = mutableSetOf()

    /**
     * Treats these response codes as success instead of throwing
     * [id.co.bri.brimons.core.common.util.exception.ApiErrorException].
     */
    fun acceptCodes(vararg codes: String) {
        acceptedCodes += codes
    }

    fun mapError(handler: ExceptionHandler) {
        errorHandlers += handler
    }

    fun mapErrors(vararg handlers: ExceptionHandler) {
        errorHandlers += handlers
    }

    fun mapErrors(handlers: Iterable<ExceptionHandler>) {
        errorHandlers += handlers
    }

    fun kotlinx() {
        requestEngine = NetworkJsonEngine.KOTLINX
        responseEngine = NetworkJsonEngine.KOTLINX
    }

    fun gson() {
        requestEngine = NetworkJsonEngine.GSON
        responseEngine = NetworkJsonEngine.GSON
    }

    fun kotlinxRequest() {
        requestEngine = NetworkJsonEngine.KOTLINX
    }

    fun gsonRequest() {
        requestEngine = NetworkJsonEngine.GSON
    }

    fun kotlinxResponse() {
        responseEngine = NetworkJsonEngine.KOTLINX
    }

    fun gsonResponse() {
        responseEngine = NetworkJsonEngine.GSON
    }

    fun json(
        request: NetworkJsonEngine = requestEngine,
        response: NetworkJsonEngine = responseEngine,
    ) {
        requestEngine = request
        responseEngine = response
    }

    suspend inline fun <reified Req : Any, reified Res> post(
        url: String,
        request: Req,
        fastMenu: Boolean = false,
    ): Res {
        return executor.execute(
            method = HttpMethod.POST,
            url = url,
            request =
                EncryptedHttpRequest.WithBody(
                    jsonBody =
                        NetworkJsonCodec.encodeToString(value = request, engine = requestEngine),
                    isFastMenu = fastMenu,
                ),
            responseEngine = responseEngine,
            acceptedCodes = acceptedCodes,
        )
    }

    suspend inline fun <reified Res> get(url: String): Res {
        return executor.execute(
            method = HttpMethod.GET,
            url = url,
            request = EncryptedHttpRequest.WithoutBody,
            responseEngine = responseEngine,
            acceptedCodes = acceptedCodes,
        )
    }

    @PublishedApi
    internal suspend fun mapThrowable(throwable: Throwable): Throwable {
        if (errorHandlers.isEmpty()) return throwable

        return throwable.toMappedException(mapper = errorHandlers)
    }
}

@OptIn(ExperimentalContracts::class)
suspend inline fun <T> EncryptedCallExecutor.call(
    crossinline configure: EncryptedCallScope.() -> Unit = {},
    crossinline block: suspend EncryptedCallScope.() -> T,
): T {
    contract { callsInPlace(configure, InvocationKind.EXACTLY_ONCE) }

    val scope = EncryptedCallScope(this)

    return try {
        scope.configure()
        scope.block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        throw scope.mapThrowable(error)
    }
}

```

**File: `core/network/src/main/kotlin/id/co/bri/brimons/core/network/utils/helper/EncryptedCallExecutor.kt`**
```kotlin
package id.co.bri.brimons.core.network.utils.helper

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import id.co.bri.brimons.core.model.exception.ApiErrorException
import id.co.bri.brimons.core.network.api.ApiInterface
import id.co.bri.brimons.core.network.api.MinioInterface
import id.co.bri.brimons.core.network.di.qualifier.MigrateNewNetwork
import id.co.bri.brimons.core.network.di.qualifier.MinioNetwork
import id.co.bri.brimons.core.preference.impl.session.SessionPreference
import id.co.bri.brimons.core.security.crypto.decryptUrlPath
import id.co.bri.brimons.core.util.dispatcher.IODispatcher
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.RequestBody
import retrofit2.Response

class EncryptedCallExecutor
@Inject
constructor(
    @param:ApplicationContext context: Context,
    pref: SessionPreference,
    @param:MigrateNewNetwork private val api: ApiInterface,
    @param:MinioNetwork private val minioApi: MinioInterface,
    @param:IODispatcher val dispatcher: CoroutineDispatcher,
) {

    private val requestFactory = EncryptedRequestFactory(context = context, pref = pref)

    suspend inline fun <reified Req : Any, reified Res> post(
        url: String,
        request: Req,
        fastMenu: Boolean = false,
    ): Res {
        return call { post<Req, Res>(url = url, request = request, fastMenu = fastMenu) }
    }

    suspend inline fun <reified Res> get(url: String): Res {
        return call { get<Res>(url = url) }
    }

    suspend fun postMinio(url: String, file: RequestBody) {
        withContext(dispatcher) {
            val response = minioApi.putMinioData(url = url, file = file)

            if (!response.isSuccessful) {
                throw ApiErrorException(
                    code = response.code().toString(),
                    message = response.message(),
                )
            }
        }
    }

    @PublishedApi
    internal suspend inline fun <reified Res> execute(
        method: HttpMethod,
        url: String,
        request: EncryptedHttpRequest,
        responseEngine: NetworkJsonEngine = NetworkJsonEngine.KOTLINX,
        acceptedCodes: Set<String> = emptySet(),
    ): Res {
        return withContext(dispatcher) {
            val callResult =
                executeEncryptedRequest(
                    method = method,
                    url = decryptUrlPath(url),
                    request = request,
                )

            EncryptedResponseParser.parse(
                response = callResult.response,
                sequenceKey = callResult.sequenceKey,
                responseEngine = responseEngine,
                acceptedCodes = acceptedCodes,
            )
        }
    }

    @PublishedApi
    internal suspend fun executeEncryptedRequest(
        method: HttpMethod,
        url: String,
        request: EncryptedHttpRequest,
    ): EncryptedCallResult {
        val erangel = requestFactory.create(request)
        val headers = ErangelHeaderFactory.create(erangel)

        val response =
            when (method) {
                HttpMethod.POST -> {
                    api.post(url = url, requestData = erangel.request, headers = headers)
                }

                HttpMethod.GET -> {
                    api.get(url = url, headers = headers)
                }
            }

        return EncryptedCallResult(response = response, sequenceKey = erangel.xSequenceKey)
    }
}

data class EncryptedCallResult(val response: Response<String>, val sequenceKey: String)

```

**File: `core/network/src/main/kotlin/id/co/bri/brimons/core/network/utils/helper/EncryptedHttpRequest.kt`**
```kotlin
package id.co.bri.brimons.core.network.utils.helper

sealed interface EncryptedHttpRequest {

    data class WithBody(val jsonBody: String, val isFastMenu: Boolean = false) :
        EncryptedHttpRequest

    data object WithoutBody : EncryptedHttpRequest
}

```

**File: `core/network/src/main/kotlin/id/co/bri/brimons/core/network/utils/helper/EncryptedRequestFactory.kt`**
```kotlin
package id.co.bri.brimons.core.network.utils.helper

import android.content.Context
import id.co.bri.brimons.core.network.erangel.ErangelRequest
import id.co.bri.brimons.core.network.erangel.createErangelRequest
import id.co.bri.brimons.core.preference.impl.session.SessionPreference
import id.co.bri.brimons.core.util.ext.json
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

@OptIn(ExperimentalSerializationApi::class)
internal class EncryptedRequestFactory(
    private val context: Context,
    private val pref: SessionPreference,
) {

    fun create(request: EncryptedHttpRequest): ErangelRequest {
        return when (request) {
            is EncryptedHttpRequest.WithBody -> {
                createErangelRequest(
                    context = context,
                    seqNumb = pref.getSeqNumber(),
                    rawRequest = request.resolveJsonBody(),
                    deviceId1 = pref.getDeviceId(),
                    deviceId2 = pref.getDeviceId2(),
                )
            }

            EncryptedHttpRequest.WithoutBody -> {
                createErangelRequest(
                    context = context,
                    seqNumb = pref.getSeqNumber(),
                    rawRequest = "",
                    deviceId1 = pref.getDeviceId(),
                    deviceId2 = pref.getDeviceId2(),
                )
            }
        }
    }

    private fun EncryptedHttpRequest.WithBody.resolveJsonBody(): String {
        return if (isFastMenu) {
            injectSessionCredentials(jsonBody)
        } else {
            jsonBody
        }
    }

    private fun injectSessionCredentials(jsonBody: String): String {
        val jsonObject =
            json.parseToJsonElement(jsonBody.ifBlank { "{}" }).jsonObject.toMutableMap()

        jsonObject[USERNAME] = JsonPrimitive(pref.getUsername())
        jsonObject[TOKEN_KEY] = JsonPrimitive(pref.getTokenKey())

        return JsonObject(jsonObject).toString()
    }

    private companion object {
        const val USERNAME = "username"
        const val TOKEN_KEY = "token_key"
    }
}

```

**File: `core/network/src/main/kotlin/id/co/bri/brimons/core/network/utils/helper/EncryptedResponseParser.kt`**
```kotlin
package id.co.bri.brimons.core.network.utils.helper

import id.co.bri.brimons.core.model.exception.ApiErrorException
import id.co.bri.brimons.core.model.response.ResponseCode
import id.co.bri.brimons.core.model.response.RestResponse
import id.co.bri.brimons.core.network.erangel.toRestResponse
import id.co.bri.brimons.core.util.logcat
import retrofit2.Response

@PublishedApi
internal object EncryptedResponseParser {

    @PublishedApi
    internal inline fun <reified T> parse(
        response: Response<String>,
        sequenceKey: String,
        responseEngine: NetworkJsonEngine,
        acceptedCodes: Set<String> = emptySet(),
    ): T {
        if (!response.isSuccessful) {
            throw ApiErrorException(code = response.code().toString(), message = response.message())
        }

        val rest =
            response.body()?.toRestResponse(sequenceKey)
                ?: throw ApiErrorException(code = ResponseCode.GENERAL.code, message = "")

        if (rest.code != ResponseCode.SUCCESS.code && rest.code !in acceptedCodes) {
            logcat { "rest.code = ${rest.code}" }

            throw ApiErrorException(code = rest.code, message = rest.desc, data = rest.data)
        }

        return rest.decodeData(responseEngine = responseEngine)
    }

    @PublishedApi
    internal inline fun <reified T> RestResponse.decodeData(responseEngine: NetworkJsonEngine): T {
        return when (T::class) {
            Unit::class -> Unit as T

            RestResponse::class -> RestResponse(code = code, desc = desc, data = data) as T

            else ->
                data?.let { element ->
                    NetworkJsonCodec.decodeFromJsonElement<T>(
                        element = element,
                        engine = responseEngine,
                    )
                } ?: throw ApiErrorException(code = code, message = desc, data = data)
        }
    }
}

```

**File: `core/network/src/main/kotlin/id/co/bri/brimons/core/network/utils/helper/ErangelHeaderFactory.kt`**
```kotlin
package id.co.bri.brimons.core.network.utils.helper

import id.co.bri.brimons.core.network.api.ApiInterface
import id.co.bri.brimons.core.network.erangel.ErangelRequest

object ErangelHeaderFactory {

    fun create(request: ErangelRequest): Map<String, String> {
        return buildMap {
            put(ApiInterface.HEADER_DEVICE_ID, request.xDeviceId)
            put(ApiInterface.HEADER_DEVICE, request.xDevice)
            put(ApiInterface.HEADER_RANDOM_KEY, request.xRandomKey)
            put(ApiInterface.HEADER_ID, request.xId)
            put(ApiInterface.HEADER_KEY_SEQUENCE, request.xSequenceKey)
        }
    }
}

```

**File: `core/network/src/main/kotlin/id/co/bri/brimons/core/network/utils/helper/HttpMethod.kt`**
```kotlin
package id.co.bri.brimons.core.network.utils.helper

enum class HttpMethod {
    GET,
    POST,
}

```

**File: `core/network/src/main/kotlin/id/co/bri/brimons/core/network/utils/helper/MinioHelper.kt`**
```kotlin
package id.co.bri.brimons.core.network.utils.helper

import id.co.bri.brimons.core.network.api.MinioInterface
import javax.inject.Inject
import okhttp3.RequestBody
import retrofit2.Response

class MinioHelper @Inject constructor(private val minioInterface: MinioInterface) : MinioSource {

    override suspend fun putMinioData(url: String, file: RequestBody): Response<Unit> {
        return minioInterface.putMinioData(url, file)
    }
}

```

**File: `core/network/src/main/kotlin/id/co/bri/brimons/core/network/utils/helper/MinioSource.kt`**
```kotlin
package id.co.bri.brimons.core.network.utils.helper

import okhttp3.RequestBody
import retrofit2.Response

interface MinioSource {
    suspend fun putMinioData(url: String, file: RequestBody): Response<Unit>
}

```

**File: `core/network/src/main/kotlin/id/co/bri/brimons/core/network/utils/helper/NetworkJsonCodec.kt`**
```kotlin
package id.co.bri.brimons.core.network.utils.helper

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import id.co.bri.brimons.core.util.ext.json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

@PublishedApi
internal object NetworkJsonCodec {

    @PublishedApi internal val gson: Gson = Gson()

    @PublishedApi
    internal inline fun <reified T : Any> encodeToString(
        value: T,
        engine: NetworkJsonEngine,
    ): String {
        return when (engine) {
            NetworkJsonEngine.KOTLINX -> json.encodeToString(value)
            NetworkJsonEngine.GSON -> gson.toJson(value)
        }
    }

    @PublishedApi
    internal inline fun <reified T> decodeFromJsonElement(
        element: JsonElement,
        engine: NetworkJsonEngine,
    ): T {
        return when (engine) {
            NetworkJsonEngine.KOTLINX -> json.decodeFromJsonElement<T>(element)

            NetworkJsonEngine.GSON -> {
                val type = object : TypeToken<T>() {}.type
                gson.fromJson<T>(element.toString(), type)
            }
        }
    }
}

```

**File: `core/network/src/main/kotlin/id/co/bri/brimons/core/network/utils/helper/NetworkJsonEngine.kt`**
```kotlin
package id.co.bri.brimons.core.network.utils.helper

enum class NetworkJsonEngine {
    KOTLINX,
    GSON,
}

```

**File: `core/network/src/main/kotlin/id/co/bri/brimons/core/network/utils/helper/RxEncryptedCallExecutor.kt`**
```kotlin
package id.co.bri.brimons.core.network.utils.helper

import io.reactivex.Observable
import javax.inject.Inject
import kotlinx.coroutines.rx2.rxSingle

class RxEncryptedCallHelper
@Inject
constructor(@PublishedApi internal val executor: EncryptedCallExecutor) {

    internal inline fun <reified Req : Any, reified Res : Any> post(
        url: String,
        request: Req,
        fastMenu: Boolean = false,
    ): Observable<Res> {
        return rxSingle {
                executor.post<Req, Res>(url = url, request = request, fastMenu = fastMenu)
            }
            .toObservable()
    }

    internal inline fun <reified Res : Any> get(url: String): Observable<Res> {
        return rxSingle { executor.get<Res>(url = url) }.toObservable()
    }
}

```

**File: `core/network/src/test/kotlin/id/co/bri/brimons/core/network/EncryptedCallExecutorTest.kt`**
```kotlin
package id.co.bri.brimons.core.network

import android.content.Context
import id.co.bri.brimons.core.model.exception.ApiErrorException
import id.co.bri.brimons.core.model.response.RestResponse
import id.co.bri.brimons.core.network.erangel.ErangelRequest
import id.co.bri.brimons.core.network.erangel.createErangelRequest
import id.co.bri.brimons.core.network.erangel.toRestResponse
import id.co.bri.brimons.core.network.utils.helper.EncryptedCallExecutor
import id.co.bri.brimons.core.network.utils.helper.EncryptedHttpRequest
import id.co.bri.brimons.core.network.utils.helper.EncryptedResponseParser
import id.co.bri.brimons.core.network.utils.helper.HttpMethod
import id.co.bri.brimons.core.network.utils.helper.NetworkJsonEngine
import id.co.bri.brimons.core.preference.impl.session.SessionPreference
import id.co.bri.brimons.core.testing.network.FakeApiInterface
import id.co.bri.brimons.core.testing.network.FakeMinioInterface
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response

class EncryptedCallExecutorTest :
    FunSpec({
        lateinit var api: FakeApiInterface

        val pref = mockk<SessionPreference>()
        val context = mockk<Context>(relaxed = true)

        val erangelRequest =
            ErangelRequest(
                xRandomKey = "6CbPYCfBEwt+Rdyzhm0nJz2IwqE=",
                xDeviceId = "a86b2d204dc5df39d8fce533e0fde90d23d52731604bf1d5e669d52a95af58d1",
                xDevice = "2{\$\$}sdk_gphone64_arm64{\$\$}15{\$\$}3.4.0-erangelQita",
                xId = "cc11f3a75eb50250",
                request =
                    "2e4065e4a96ff4f6c785a79415e5a0dejI9qxe6LVYpeJOXM%2BtUi3HHoc7QChGXQ%2BfL29GGXS2sO%2FE2aiXkOQsB%2BYhnP",
                xSequenceKey = "/hotkbcsDnyHqeUwXcrumCebUCk=",
                isDklC2 = null,
            )

        fun newExecutor(): EncryptedCallExecutor {
            return EncryptedCallExecutor(
                context = context,
                pref = pref,
                api = api,
                minioApi = FakeMinioInterface(),
                dispatcher = Dispatchers.Unconfined,
            )
        }

        beforeTest {
            api = FakeApiInterface()

            every { pref.getSeqNumber() } returns "123456"
            every { pref.getDeviceId() } returns "device-id"
            every { pref.getDeviceId2() } returns "device-id-2"
            every { pref.getUsername() } returns "nizar"
            every { pref.getTokenKey() } returns "token-123"

            mockkStatic("id.co.bri.brimons.core.network.erangel.ErangelRequestKt")

            every {
                createErangelRequest(
                    context = any(),
                    seqNumb = any(),
                    rawRequest = any(),
                    deviceId1 = any(),
                    deviceId2 = any(),
                    isDklC2 = any(),
                )
            } returns erangelRequest
        }

        afterTest { unmockkStatic("id.co.bri.brimons.core.network.erangel.ErangelRequestKt") }

        test("should call POST api") {
            val executor = newExecutor()

            val result =
                executor.executeEncryptedRequest(
                    method = HttpMethod.POST,
                    url = "/erangel/v5-fm-transfer-form",
                    request = EncryptedHttpRequest.WithBody("""{"foo":"bar"}"""),
                )

            api.lastMethod shouldBe "POST"
            api.lastUrl shouldBe "/erangel/v5-fm-transfer-form"
            api.lastRequestBody shouldBe erangelRequest.request
            result.sequenceKey shouldBe erangelRequest.xSequenceKey
        }

        test("should call GET api") {
            val executor = newExecutor()

            executor.executeEncryptedRequest(
                method = HttpMethod.GET,
                url = "/erangel/v5-fm-transfer-form",
                request = EncryptedHttpRequest.WithoutBody,
            )

            api.lastMethod shouldBe "GET"
            api.lastUrl shouldBe "/erangel/v5-fm-transfer-form"
        }

        test("should inject username and token when fastMenu is true") {
            val executor = newExecutor()
            val rawRequestSlot = slot<String>()

            every {
                createErangelRequest(
                    context = any(),
                    seqNumb = any(),
                    rawRequest = capture(rawRequestSlot),
                    deviceId1 = any(),
                    deviceId2 = any(),
                    isDklC2 = any(),
                )
            } returns erangelRequest

            executor.executeEncryptedRequest(
                method = HttpMethod.POST,
                url = "/erangel/v5-fm-transfer-form",
                request =
                    EncryptedHttpRequest.WithBody(jsonBody = """{"foo":"bar"}""", isFastMenu = true),
            )

            rawRequestSlot.captured shouldBe
                """{"foo":"bar","username":"nizar","token_key":"token-123"}"""
        }

        test("should throw ApiErrorException when response is unsuccessful") {
            val response =
                Response.error<String>(
                    500,
                    "Mohon maaf, kami belum bisa memproses transaksimu".toResponseBody(null),
                )

            val error =
                shouldThrow<ApiErrorException> {
                    EncryptedResponseParser.parse<Any>(
                        response = response,
                        sequenceKey = "seq-key",
                        responseEngine = NetworkJsonEngine.KOTLINX,
                    )
                }

            error.code shouldBe "500"
        }
        test("should decode body when code is not 00 but is in acceptedCodes") {
            mockkStatic("id.co.bri.brimons.core.network.erangel.ErangelResponseParserKt")
            every { any<String>().toRestResponse(any()) } returns
                RestResponse(
                    code = "68",
                    desc = "Transaction Suspend",
                    data = buildJsonObject { put("title", JsonPrimitive("suspended")) },
                )

            val result =
                EncryptedResponseParser.parse<Map<String, String>>(
                    response = Response.success("encrypted-body"),
                    sequenceKey = "seq-key",
                    responseEngine = NetworkJsonEngine.KOTLINX,
                    acceptedCodes = setOf("68"),
                )

            result shouldBe mapOf("title" to "suspended")

            unmockkStatic("id.co.bri.brimons.core.network.erangel.ErangelResponseParserKt")
        }
    })

```

**File: `core/network/src/test/kotlin/id/co/bri/brimons/core/network/NetworkRetrofitTest.kt`**
```kotlin
package id.co.bri.brimons.core.network

import id.co.bri.brimons.core.network.api.ApiInterface
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class NetworkRetrofitTest :
    FunSpec({
        lateinit var server: MockWebServer
        lateinit var api: ApiInterface

        beforeTest {
            server = MockWebServer()
            server.start()

            val retrofit =
                Retrofit.Builder()
                    .baseUrl(server.url("/"))
                    .client(OkHttpClient())
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()

            api = retrofit.create(ApiInterface::class.java)
        }

        afterTest { server.shutdown() }

        test("post should send form body and headers correctly") {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("\"ok\"")
                    .setHeader("Content-Type", "application/json")
            )

            val response =
                api.post(
                    url = "/v1/test",
                    requestData = "encrypted-body",
                    headers =
                        mapOf(
                            ApiInterface.HEADER_DEVICE_ID to "device-id",
                            ApiInterface.HEADER_KEY_SEQUENCE to "seq-key",
                        ),
                )

            response.isSuccessful shouldBe true
            response.body() shouldBe "ok"

            val request = server.takeRequest()

            request.method shouldBe "POST"
            request.path shouldBe "/v1/test"

            request.getHeader(ApiInterface.HEADER_DEVICE_ID) shouldBe "device-id"
            request.getHeader(ApiInterface.HEADER_KEY_SEQUENCE) shouldBe "seq-key"

            request.body.readUtf8() shouldBe "request=encrypted-body"
        }

        test("get should send headers correctly") {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("\"ok\"")
            )

            val response =
                api.get(
                    url = "/v1/test",
                    headers = mapOf(ApiInterface.HEADER_DEVICE_ID to "device-id"),
                )

            response.isSuccessful shouldBe true

            val request = server.takeRequest()

            request.method shouldBe "GET"
            request.path shouldBe "/v1/test"
            request.getHeader(ApiInterface.HEADER_DEVICE_ID) shouldBe "device-id"
        }
    })

```

**File: `core/network/src/test/kotlin/id/co/bri/brimons/core/network/utils/FileDownloaderTest.kt`**
```kotlin
package id.co.bri.brimons.core.network.utils

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.file.shouldExist
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.IOException
import java.net.URL
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

private const val DOWNLOADS_DIR = "downloads"
private const val FILE_NAME = "test.pdf"
private const val FILE_CONTENT = "dummy content"

class FileDownloaderTest :
    FunSpec({
        lateinit var filesDir: File
        lateinit var okHttpClient: OkHttpClient
        lateinit var call: Call
        lateinit var fileDownloader: FileDownloader

        beforeTest {
            filesDir = createTempDir()
            okHttpClient = mockk()
            call = mockk()

            fileDownloader = FileDownloader(filesDir = filesDir, okHttpClient = okHttpClient)
        }

        afterTest { filesDir.deleteRecursively() }

        context("successful download") {
            test("downloads and saves the file") {
                val response =
                    Response.Builder()
                        .request(Request.Builder().url("https://example.com/$FILE_NAME").build())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(FILE_CONTENT.toResponseBody())
                        .build()

                every { okHttpClient.newCall(any()) } returns call
                every { call.execute() } returns response

                val result =
                    fileDownloader.downloadFromUrl(
                        url = URL("https://example.com/$FILE_NAME"),
                        fileName = FILE_NAME,
                    )

                result.isSuccess shouldBe true

                val downloadedFile = result.getOrNull()

                downloadedFile shouldBe downloadedFile
                downloadedFile?.shouldExist()
                downloadedFile?.readText() shouldBe FILE_CONTENT
                downloadedFile?.parentFile?.name shouldBe DOWNLOADS_DIR
            }
        }

        context("HTTP failure") {
            test("returns failure when response is not successful") {
                val response =
                    Response.Builder()
                        .request(Request.Builder().url("https://example.com/$FILE_NAME").build())
                        .protocol(Protocol.HTTP_1_1)
                        .code(404)
                        .message("Not Found")
                        .body("Not Found".toResponseBody())
                        .build()

                every { okHttpClient.newCall(any()) } returns call
                every { call.execute() } returns response

                val result =
                    fileDownloader.downloadFromUrl(
                        url = URL("https://example.com/$FILE_NAME"),
                        fileName = FILE_NAME,
                    )

                result.isFailure shouldBe true
                result.exceptionOrNull()?.message shouldBe "Download failed: HTTP 404"
            }
        }

        context("empty response body") {
            test("returns failure when response body is null") {
                val response =
                    Response.Builder()
                        .request(Request.Builder().url("https://example.com/$FILE_NAME").build())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .build()

                every { okHttpClient.newCall(any()) } returns call
                every { call.execute() } returns response

                val result =
                    fileDownloader.downloadFromUrl(
                        url = URL("https://example.com/$FILE_NAME"),
                        fileName = FILE_NAME,
                    )

                result.isFailure shouldBe true
                result.exceptionOrNull()?.message shouldBe "Download failed: empty response body"
            }
        }

        context("network failure") {
            test("returns failure when download throws an exception") {
                every { okHttpClient.newCall(any()) } returns call
                every { call.execute() } throws IOException("Network error")

                val result =
                    fileDownloader.downloadFromUrl(
                        url = URL("https://example.com/$FILE_NAME"),
                        fileName = FILE_NAME,
                    )

                result.isFailure shouldBe true
                result.exceptionOrNull()?.message shouldBe "Network error"
            }
        }
    })

```

**File: `core/network/src/test/kotlin/id/co/bri/brimons/core/network/utils/cookie/CookieMapperTest.kt`**
```kotlin
package id.co.bri.brimons.core.network.utils.cookie

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import okhttp3.Cookie

class CookieMapperTest :
    FunSpec({
        context("Cookie.storageKey") {
            test("combines name, domain, and path with pipe separator") {
                val cookie =
                    Cookie.Builder()
                        .name("name")
                        .value("value")
                        .domain("example.com")
                        .path("/path")
                        .build()

                cookie.storageKey shouldBe "name|example.com|/path"
            }

            test("two cookies with different paths get distinct keys") {
                val a =
                    Cookie.Builder()
                        .name("name")
                        .value("value")
                        .domain("example.com")
                        .path("/a")
                        .build()
                val b =
                    Cookie.Builder()
                        .name("name")
                        .value("value")
                        .domain("example.com")
                        .path("/b")
                        .build()

                a.storageKey shouldBe "name|example.com|/a"
                b.storageKey shouldBe "name|example.com|/b"
                (a.storageKey == b.storageKey).shouldBeFalse()
            }
        }

        context("Cookie.toSnapshot") {
            test("copies all persistent fields") {
                val cookie =
                    Cookie.Builder()
                        .name("name")
                        .value("value")
                        .expiresAt(1_000_000L)
                        .domain("example.com")
                        .path("/path")
                        .secure()
                        .httpOnly()
                        .build()

                val snapshot = cookie.toSnapshot()

                snapshot.name shouldBe "name"
                snapshot.value shouldBe "value"
                snapshot.expiresAt shouldBe 1_000_000L
                snapshot.domain shouldBe "example.com"
                snapshot.path shouldBe "/path"
                snapshot.secure.shouldBeTrue()
                snapshot.httpOnly.shouldBeTrue()
                snapshot.persistent.shouldBeTrue()
            }

            test("captures hostOnly true when the domain is set via hostOnlyDomain") {
                val cookie =
                    Cookie.Builder()
                        .name("name")
                        .value("value")
                        .hostOnlyDomain("example.com")
                        .build()

                cookie.toSnapshot().hostOnly.shouldBeTrue()
            }
        }

        context("CookieSnapshot.toCookie") {
            test("rebuilds a cookie whose storageKey matches the original") {
                val original =
                    Cookie.Builder()
                        .name("name")
                        .value("value")
                        .expiresAt(1_000_000L)
                        .domain("example.com")
                        .path("/path")
                        .secure()
                        .httpOnly()
                        .build()

                val restored = original.toSnapshot().toCookie()

                restored.shouldNotBeNull()
                restored.storageKey shouldBe original.storageKey
                restored.value shouldBe "value"
                restored.secure.shouldBeTrue()
                restored.httpOnly.shouldBeTrue()
            }

            test("rebuilds a host-only cookie with hostOnly preserved") {
                val original =
                    Cookie.Builder()
                        .name("name")
                        .value("value")
                        .hostOnlyDomain("example.com")
                        .build()

                val restored = original.toSnapshot().toCookie()

                restored.shouldNotBeNull()
                restored.hostOnly.shouldBeTrue()
                restored.domain shouldBe "example.com"
            }

            test("returns null when the snapshot has an invalid domain") {
                val snapshot =
                    CookieSnapshot(
                        name = "name",
                        value = "value",
                        expiresAt = 1_000_000L,
                        domain = "",
                        path = "/",
                        secure = false,
                        httpOnly = false,
                        hostOnly = false,
                        persistent = false,
                    )

                snapshot.toCookie().shouldBeNull()
            }
        }
    })

```

**File: `core/network/src/test/kotlin/id/co/bri/brimons/core/network/utils/helper/EncryptedResponseParserTest.kt`**
```kotlin
package id.co.bri.brimons.core.network.utils.helper

import android.util.Log
import id.co.bri.brimons.core.model.exception.ApiErrorException
import id.co.bri.brimons.core.model.response.RestResponse
import id.co.bri.brimons.core.network.erangel.toRestResponse
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.unmockkStatic
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response

@Serializable private data class Payload(val title: String)

private const val ERANGEL_PARSER = "id.co.bri.brimons.core.network.erangel.ErangelResponseParserKt"

class EncryptedResponseParserTest :
    FunSpec({
        beforeSpec {
            mockkStatic(Log::class)
            every { Log.println(any(), any(), any()) } returns 0
        }
        afterSpec { unmockkAll() }

        beforeTest { mockkStatic(ERANGEL_PARSER) }
        afterTest { unmockkStatic(ERANGEL_PARSER) }

        context("HTTP failure") {
            test("throws ApiErrorException carrying the HTTP status code") {
                val response = Response.error<String>(500, "server error".toResponseBody(null))

                val error =
                    shouldThrow<ApiErrorException> {
                        EncryptedResponseParser.parse<Payload>(
                            response = response,
                            sequenceKey = "sequenceKey",
                            responseEngine = NetworkJsonEngine.KOTLINX,
                        )
                    }

                error.code shouldBe "500"
            }
        }

        context("toRestResponse returns null") {
            test("throws ApiErrorException with GENERAL code") {
                every { any<String>().toRestResponse(any()) } returns null

                val error =
                    shouldThrow<ApiErrorException> {
                        EncryptedResponseParser.parse<Payload>(
                            response = Response.success("body"),
                            sequenceKey = "sequenceKey",
                            responseEngine = NetworkJsonEngine.KOTLINX,
                        )
                    }

                error.code shouldBe "12"
            }
        }

        context("non-success rest.code") {
            test("throws when code is not 00 and not in acceptedCodes") {
                every { any<String>().toRestResponse(any()) } returns
                    RestResponse(code = "68", desc = "desc", data = null)

                val error =
                    shouldThrow<ApiErrorException> {
                        EncryptedResponseParser.parse<Payload>(
                            response = Response.success("body"),
                            sequenceKey = "sequenceKey",
                            responseEngine = NetworkJsonEngine.KOTLINX,
                        )
                    }

                error.code shouldBe "68"
                error.message shouldBe "desc"
            }

            test("returns decoded body when the code is accepted") {
                every { any<String>().toRestResponse(any()) } returns
                    RestResponse(
                        code = "68",
                        desc = "desc",
                        data = buildJsonObject { put("title", JsonPrimitive("title")) },
                    )

                val result =
                    EncryptedResponseParser.parse<Payload>(
                        response = Response.success("body"),
                        sequenceKey = "sequenceKey",
                        responseEngine = NetworkJsonEngine.KOTLINX,
                        acceptedCodes = setOf("68"),
                    )

                result shouldBe Payload(title = "title")
            }
        }

        context("Unit return type") {
            test("returns Unit without touching data") {
                every { any<String>().toRestResponse(any()) } returns
                    RestResponse(code = "00", desc = "desc", data = null)

                EncryptedResponseParser.parse<Unit>(
                    response = Response.success("body"),
                    sequenceKey = "sequenceKey",
                    responseEngine = NetworkJsonEngine.KOTLINX,
                ) shouldBe Unit
            }
        }

        context("RestResponse return type") {
            test("returns the RestResponse envelope itself") {
                val rest =
                    RestResponse(
                        code = "00",
                        desc = "desc",
                        data = buildJsonObject { put("title", JsonPrimitive("title")) },
                    )
                every { any<String>().toRestResponse(any()) } returns rest

                val result =
                    EncryptedResponseParser.parse<RestResponse>(
                        response = Response.success("body"),
                        sequenceKey = "sequenceKey",
                        responseEngine = NetworkJsonEngine.KOTLINX,
                    )

                result.code shouldBe "00"
                result.desc shouldBe "desc"
                result.data shouldBe rest.data
            }
        }

        context("null data on success") {
            test("throws ApiErrorException when a typed body is expected but data is null") {
                every { any<String>().toRestResponse(any()) } returns
                    RestResponse(code = "00", desc = "desc", data = null)

                val error =
                    shouldThrow<ApiErrorException> {
                        EncryptedResponseParser.parse<Payload>(
                            response = Response.success("body"),
                            sequenceKey = "sequenceKey",
                            responseEngine = NetworkJsonEngine.KOTLINX,
                        )
                    }

                error.code shouldBe "00"
                error.message shouldBe "desc"
            }
        }
    })

```

**File: `core/network/src/test/kotlin/id/co/bri/brimons/core/network/utils/helper/NetworkJsonCodecTest.kt`**
```kotlin
package id.co.bri.brimons.core.network.utils.helper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable private data class Sample(val id: Int, val name: String)

class NetworkJsonCodecTest :
    FunSpec({
        context("encodeToString") {
            test("KOTLINX produces keys per shared json config") {
                val sample = Sample(id = 7, name = "name")

                val encoded = NetworkJsonCodec.encodeToString(sample, NetworkJsonEngine.KOTLINX)

                encoded shouldContain "\"id\":7"
                encoded shouldContain "\"name\":\"name\""
            }

            test("GSON produces keys per default Gson config") {
                val sample = Sample(id = 7, name = "name")

                val encoded = NetworkJsonCodec.encodeToString(sample, NetworkJsonEngine.GSON)

                encoded shouldContain "\"id\":7"
                encoded shouldContain "\"name\":\"name\""
            }
        }

        context("decodeFromJsonElement") {
            test("KOTLINX decodes JSON into the target type") {
                val element = Json.parseToJsonElement("""{"id":7,"name":"name"}""")

                val decoded =
                    NetworkJsonCodec.decodeFromJsonElement<Sample>(
                        element,
                        NetworkJsonEngine.KOTLINX,
                    )

                decoded shouldBe Sample(id = 7, name = "name")
            }

            test("GSON decodes JSON into the target type") {
                val element = Json.parseToJsonElement("""{"id":7,"name":"name"}""")

                val decoded =
                    NetworkJsonCodec.decodeFromJsonElement<Sample>(element, NetworkJsonEngine.GSON)

                decoded shouldBe Sample(id = 7, name = "name")
            }
        }
    })

```

**File: `core/preference/build.gradle.kts`**
```kotlin
plugins {
    alias(libs.plugins.brimo.android.library)
    alias(libs.plugins.brimo.android.hilt)
}

android { namespace = "id.co.bri.brimons.core.preference" }

dependencies {
    implementation(libs.gson)
    implementation(projects.core.util)
    implementation(projects.core.security)

    testImplementation(libs.bundles.robolectric)
}

```

**File: `core/preference/src/main/kotlin/id/co/bri/brimons/core/preference/PrefConstant.kt`**
```kotlin
package id.co.bri.brimons.core.preference

object PrefConstant {

    const val ONBOARDING_STATUS_NOT_MATCH_KEY = "onboarding_status_not_match_key"
    const val PREF_KEY_FORM_JOB_RDN = "pref_key_form_job_rdn"
    const val PREF_KEY_FORM_RISK_PROFILE_RDN = "pref_key_form_risk_profile_rdn"
    const val PREF_KEY_FORM_OTHER_INFORMATION_RDN = "pref_key_form_other_information_rdn"
    const val PREF_KEY_CHECKPOINT = "pref_key_checkpoint"
    const val PREF_KEY_DATETIMEWIB1 = "pref_key_datetimewib1"
    const val PREF_KEY_DATETIMEWIB2 = "pref_key_datetimewib2"
    const val PREF_KEY_DATETIMEWIB3 = "pref_key_datetimewib3"
    const val OTA_PARAMETERS_KEY = "pref_ota_parameters_key"
    const val ONBOARDING_BLOCKED_STATUS = "onboarding_blocked_status"
    const val PREF_KEY_ALERT_SKIP_PENGKINIAN = "pref_key_skip_pengkinian"
    const val DYNAMIC_SCREEN_LOGIN_KEY = "pref_dynamic_screen_login"
    const val DYNAMIC_SCREEN_HOMEPAGE_KEY = "pref_dynamic_screen_homepage"
    const val DYNAMIC_SCREEN_FETCHED = "pref_dynamic_screen_fetched"
    const val DYNAMIC_SCREEN_TIME_INTERVAL = "pref_dynamic_screen_interval"
    const val DYNAMIC_SCREEN_LAST_FETCH_TIME = "pref_dynamic_screen_last_fetch_time"

    const val PREF_ONBOARDING_LOCATION_KEY = "pref_onboarding_location_key"
    const val IS_FOREIGN_PREF = "is_foreign_pref"
    const val SIMULATION_GOLD_INSTALLMENT = "simulation_gold_installment"
    const val PREF_CHOOSE_TENOR_DATA = "choose_tenor_data"
}

```

**File: `core/preference/src/main/kotlin/id/co/bri/brimons/core/preference/di/StorageModule.kt`**
```kotlin
package id.co.bri.brimons.core.preference.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import id.co.bri.brimons.core.preference.impl.account.AccountPreference
import id.co.bri.brimons.core.preference.impl.account.AccountPreferenceImpl
import id.co.bri.brimons.core.preference.impl.appconfig.AppConfigPreference
import id.co.bri.brimons.core.preference.impl.appconfig.AppConfigPreferenceImpl
import id.co.bri.brimons.core.preference.impl.biometric.BiometricPreference
import id.co.bri.brimons.core.preference.impl.biometric.BiometricPreferenceImpl
import id.co.bri.brimons.core.preference.impl.fcm.FcmPreference
import id.co.bri.brimons.core.preference.impl.fcm.FcmPreferenceImpl
import id.co.bri.brimons.core.preference.impl.general.GeneralPreference
import id.co.bri.brimons.core.preference.impl.general.GeneralPreferenceImpl
import id.co.bri.brimons.core.preference.impl.investment.InvestmentPreference
import id.co.bri.brimons.core.preference.impl.investment.InvestmentPreferenceImpl
import id.co.bri.brimons.core.preference.impl.onboarding.OnboardingPreference
import id.co.bri.brimons.core.preference.impl.onboarding.OnboardingPreferenceImpl
import id.co.bri.brimons.core.preference.impl.payment.PaymentPreference
import id.co.bri.brimons.core.preference.impl.payment.PaymentPreferenceImpl
import id.co.bri.brimons.core.preference.impl.session.SessionPreference
import id.co.bri.brimons.core.preference.impl.session.SessionPreferenceImpl
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class StorageModule {

    @Binds
    @Singleton
    abstract fun bindAccountPreference(impl: AccountPreferenceImpl): AccountPreference

    @Binds
    @Singleton
    abstract fun bindAppConfigPreference(impl: AppConfigPreferenceImpl): AppConfigPreference

    @Binds
    @Singleton
    abstract fun bindBiometricPreference(impl: BiometricPreferenceImpl): BiometricPreference

    @Binds @Singleton abstract fun bindFcmPreference(impl: FcmPreferenceImpl): FcmPreference

    @Binds
    @Singleton
    abstract fun bindGeneralPreference(impl: GeneralPreferenceImpl): GeneralPreference

    @Binds
    @Singleton
    abstract fun bindInvestmentPreference(impl: InvestmentPreferenceImpl): InvestmentPreference

    @Binds
    @Singleton
    abstract fun bindOnboardingPreference(impl: OnboardingPreferenceImpl): OnboardingPreference

    @Binds
    @Singleton
    abstract fun bindPaymentPreference(impl: PaymentPreferenceImpl): PaymentPreference

    @Binds
    @Singleton
    abstract fun bindSessionPreference(impl: SessionPreferenceImpl): SessionPreference
}

```

**File: `core/preference/src/main/kotlin/id/co/bri/brimons/core/preference/impl/SharedPrefHelper.kt`**
```kotlin
package id.co.bri.brimons.core.preference.impl

import android.content.Context
import androidx.core.content.edit
import id.co.bri.brimons.core.security.crypto.decryptAesCbcBase64
import id.co.bri.brimons.core.security.crypto.encryptAesCbcBase64
import id.co.bri.brimons.core.util.ext.fromJsonString
import id.co.bri.brimons.core.util.ext.toJsonString

object SharedPrefHelper {
    const val FILE_NAME: String = "BRIMO_PREFERENCES"

    fun put(context: Context, key: String, value: Any?) {
        val sp = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

        sp.edit {
            when (value) {
                is String -> putString(key, value)
                is Int -> putInt(key, value)
                is Boolean -> putBoolean(key, value)
                is Float -> putFloat(key, value)
                is Long -> putLong(key, value)
                else -> putString(key, (value ?: "").toString())
            }
        }
    }

    fun putEncrypted(context: Context, key: String, value: String?) {
        val encrypted = encryptAesCbcBase64(value ?: "")
        put(context, key, encrypted)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> get(context: Context, key: String?, defaultObject: T): T {
        val sp = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

        return when (defaultObject) {
            is String -> sp.getString(key, defaultObject) as T
            is Int -> sp.getInt(key, defaultObject) as T
            is Boolean -> sp.getBoolean(key, defaultObject) as T
            is Float -> sp.getFloat(key, defaultObject) as T
            is Long -> sp.getLong(key, defaultObject) as T
            else -> defaultObject
        }
    }

    fun getDecrypted(context: Context, key: String, defaultObject: String): String =
        try {
            decryptAesCbcBase64(get(context, key, ""))
        } catch (_: Exception) {
            defaultObject
        }

    fun remove(context: Context, key: String?) {
        val sp = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        sp.edit { remove(key) }
    }

    fun clear(context: Context) {
        val sp = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        sp.edit { clear() }
    }

    fun contains(context: Context, key: String): Boolean {
        val sp = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        return sp.contains(key)
    }

    fun getAll(context: Context): Map<String, *> {
        val sp = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        return sp.all
    }

    fun <T> setList(context: Context, key: String, list: List<T>) {
        val json = list.toJsonString()
        put(context, key, json)
    }

    fun <T : Any> getList(context: Context, key: String): List<T>? {
        val json = get(context, key, "")
        return if (json.isEmpty()) listOf() else json.fromJsonString()
    }
}

```

**File: `core/preference/src/main/kotlin/id/co/bri/brimons/core/preference/impl/account/AccountPreference.kt`**
```kotlin
package id.co.bri.brimons.core.preference.impl.account

interface AccountPreference {
    fun getSaldoRekeningUtama(): String

    fun getSaldoRekeningUtamaString(): String

    fun getAccountDefault(): String

    fun getMidMerchant(): String

    fun getAccNumbMerch(): String

    fun getSavedMenu(): String

    fun getDateMenuUpdate(): String

    fun getBlastId(): Int

    fun getTimeStamp(): Int

    fun getExpiredTime(): Int

    fun getCheckPoint(): Int

    fun getCheckPointDS(): Int

    fun getVerifikasiId(): String

    fun getVerifikasiIdDS(): String

    fun getBackConditionRDNSBN(): Int

    fun getSaldoHold(): Boolean

    fun isSaldoHide(): Boolean

    fun isPfmHide(): Boolean

    fun saveSaldoRekeningUtama(saldo: String?)

    fun saveSaldoRekeningUtamaString(saldo: String?)

    fun saveCurrency(currency: String?)

    fun saveNameRekeningUtama(name: String?)

    fun saveAccountDefault(account: String?)

    fun saveMidMerchant(mIdMerchant: String?)

    fun saveAccNumberMerchant(accNumb: String?)

    fun saveDateMenuUpdate(updateDate: String?)

    fun saveBlastId(blastId: Int)

    fun saveTimeStamp(timeStamp: Int)

    fun saveExpiredTime(timeExp: Int)

    fun saveCheckPoint(chekpoint: Int)

    fun saveCheckPointDS(checkPointDS: Int)

    fun saveVerifikasiId(verifId: String?)

    fun saveVerifikasiIdDS(verifIdDs: String?)

    fun saveBackGeneral(backGeneral: Int)

    fun saveSaldoHold(isSaldoHold: Boolean)

    fun updateSaldoHide(isHide: Boolean)

    fun updatePfmHide(isHide: Boolean)

    fun saveCheckPoint(key: String, value: String?)

    fun getCheckPoint(key: String): String?

    fun clearCheckPoint(key: String)

    fun deteleSavedMenu()

    fun deleteCheckPoint()

    fun deleteCheckPointDS()

    fun deleteBlastId()
}

```

**File: `core/preference/src/main/kotlin/id/co/bri/brimons/core/preference/impl/account/AccountPreferenceConstants.kt`**
```kotlin
package id.co.bri.brimons.core.preference.impl.account

object AccountPreferenceConstants {
    const val SALDO_REK_UTAMA = "saldo_rek_utama"
    const val SALDO_REK_UTAMA_STRING = "saldo_rek_utama_string"
    const val CURRENCY_REK_UTAMA = "currency_rek_utama"
    const val NAME_REK_UTAMA = "name_rek_utama"
    const val AKUN_DEFAULT = "akun_default"
    const val SALDO_HOLD = "saldo_hold"
    const val SALDO_SHOW = "saldo_show"
    const val PFM_SHOW = "pfm_show"
    const val MID_MERCHANT = "mid_merchant"
    const val ACC_NUMB = "acc_numb"
    const val MENU_MODEL = "menumodel"
    const val MENU_MODEL2 = "menumodel2"
    const val MENU_MODEL3 = "menumodel3"
    const val DATE_MENU_UPDATE = "date_menu_update"
    const val BLAST_ID = "blast_id"
    const val TIMEOTP = "timeotp"
    const val EXPTIME = "expiredtime"
    const val BACK_GENERAL = "back_general"
    const val CHECK_POINT = "checkpoint"
    const val CHECK_POINT_DS = "check_point_ds"
    const val VERIF_ID = "verifikasiid"
    const val VERIF_ID_DS = "verifikasi_id_ds"
}

```

**File: `core/preference/src/main/kotlin/id/co/bri/brimons/core/preference/impl/account/AccountPreferenceImpl.kt`**
```kotlin
package id.co.bri.brimons.core.preference.impl.account

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import id.co.bri.brimons.core.preference.impl.SharedPrefHelper
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccountPreferenceImpl @Inject constructor(@ApplicationContext private val context: Context) :
    AccountPreference {

    override fun getSaldoRekeningUtama(): String =
        SharedPrefHelper.getDecrypted(context, AccountPreferenceConstants.SALDO_REK_UTAMA, "")

    override fun getSaldoRekeningUtamaString(): String =
        SharedPrefHelper.getDecrypted(
            context,
            AccountPreferenceConstants.SALDO_REK_UTAMA_STRING,
            "",
        )

    override fun getAccountDefault(): String =
        SharedPrefHelper.getDecrypted(context, AccountPreferenceConstants.AKUN_DEFAULT, "")

    override fun getMidMerchant(): String =
        SharedPrefHelper.getDecrypted(context, AccountPreferenceConstants.MID_MERCHANT, "")

    override fun getAccNumbMerch(): String =
        SharedPrefHelper.getDecrypted(context, AccountPreferenceConstants.ACC_NUMB, "")

    override fun getSavedMenu(): String =
        SharedPrefHelper.get(context, AccountPreferenceConstants.MENU_MODEL, "")

    override fun getDateMenuUpdate(): String =
        SharedPrefHelper.getDecrypted(context, AccountPreferenceConstants.DATE_MENU_UPDATE, "")

    override fun getBlastId(): Int =
        SharedPrefHelper.get(context, AccountPreferenceConstants.BLAST_ID, 0)

    override fun getTimeStamp(): Int =
        SharedPrefHelper.get(context, AccountPreferenceConstants.TIMEOTP, 0)

    override fun getExpiredTime(): Int =
        SharedPrefHelper.get(context, AccountPreferenceConstants.EXPTIME, 0)

    override fun getCheckPoint(): Int =
        SharedPrefHelper.get(context, AccountPreferenceConstants.CHECK_POINT, 0)

    override fun getCheckPointDS(): Int =
        SharedPrefHelper.get(context, AccountPreferenceConstants.CHECK_POINT_DS, 0)

    override fun getVerifikasiId(): String =
        SharedPrefHelper.getDecrypted(context, AccountPreferenceConstants.VERIF_ID, "")

    override fun getVerifikasiIdDS(): String =
        SharedPrefHelper.getDecrypted(context, AccountPreferenceConstants.VERIF_ID_DS, "")

    override fun getBackConditionRDNSBN(): Int =
        SharedPrefHelper.get(context, AccountPreferenceConstants.BACK_GENERAL, 0)

    override fun getSaldoHold(): Boolean =
        SharedPrefHelper.get(context, AccountPreferenceConstants.SALDO_HOLD, false)

    override fun isSaldoHide(): Boolean =
        SharedPrefHelper.get(context, AccountPreferenceConstants.SALDO_SHOW, false)

    override fun isPfmHide(): Boolean =
        SharedPrefHelper.get(context, AccountPreferenceConstants.PFM_SHOW, false)

    override fun saveSaldoRekeningUtama(saldo: String?) {
        SharedPrefHelper.putEncrypted(context, AccountPreferenceConstants.SALDO_REK_UTAMA, saldo)
    }

    override fun saveSaldoRekeningUtamaString(saldo: String?) {
        SharedPrefHelper.putEncrypted(
            context,
            AccountPreferenceConstants.SALDO_REK_UTAMA_STRING,
            saldo,
        )
    }

    override fun saveCurrency(currency: String?) {
        SharedPrefHelper.put(context, AccountPreferenceConstants.CURRENCY_REK_UTAMA, currency)
    }

    override fun saveNameRekeningUtama(name: String?) {
        SharedPrefHelper.putEncrypted(context, AccountPreferenceConstants.NAME_REK_UTAMA, name)
    }

    override fun saveAccountDefault(account: String?) {
        SharedPrefHelper.putEncrypted(context, AccountPreferenceConstants.AKUN_DEFAULT, account)
    }

    override fun saveMidMerchant(mIdMerchant: String?) {
        SharedPrefHelper.putEncrypted(context, AccountPreferenceConstants.MID_MERCHANT, mIdMerchant)
    }

    override fun saveAccNumberMerchant(accNumb: String?) {
        SharedPrefHelper.putEncrypted(context, AccountPreferenceConstants.ACC_NUMB, accNumb)
    }

    override fun saveDateMenuUpdate(updateDate: String?) {
        SharedPrefHelper.putEncrypted(
            context,
            AccountPreferenceConstants.DATE_MENU_UPDATE,
            updateDate,
        )
    }

    override fun saveBlastId(blastId: Int) {
        SharedPrefHelper.put(context, AccountPreferenceConstants.BLAST_ID, blastId)
    }

    override fun saveTimeStamp(timeStamp: Int) {
        SharedPrefHelper.put(context, AccountPreferenceConstants.TIMEOTP, timeStamp)
    }

    override fun saveExpiredTime(timeExp: Int) {
        SharedPrefHelper.put(context, AccountPreferenceConstants.EXPTIME, timeExp)
    }

    override fun saveCheckPoint(chekpoint: Int) {
        SharedPrefHelper.put(context, AccountPreferenceConstants.CHECK_POINT, chekpoint)
    }

    override fun saveCheckPointDS(checkPointDS: Int) {
        SharedPrefHelper.put(context, AccountPreferenceConstants.CHECK_POINT_DS, checkPointDS)
    }

    override fun saveVerifikasiId(verifId: String?) {
        SharedPrefHelper.putEncrypted(context, AccountPreferenceConstants.VERIF_ID, verifId)
    }

    override fun saveVerifikasiIdDS(verifIdDs: String?) {
        SharedPrefHelper.putEncrypted(context, AccountPreferenceConstants.VERIF_ID_DS, verifIdDs)
    }

    override fun saveBackGeneral(backGeneral: Int) {
        SharedPrefHelper.put(context, AccountPreferenceConstants.BACK_GENERAL, backGeneral)
    }

    override fun saveSaldoHold(isSaldoHold: Boolean) {
        SharedPrefHelper.put(context, AccountPreferenceConstants.SALDO_HOLD, isSaldoHold)
    }

    override fun updateSaldoHide(isHide: Boolean) {
        SharedPrefHelper.put(context, AccountPreferenceConstants.SALDO_SHOW, isHide)
    }

    override fun updatePfmHide(isHide: Boolean) {
        SharedPrefHelper.put(context, AccountPreferenceConstants.PFM_SHOW, isHide)
    }

    override fun saveCheckPoint(key: String, value: String?) {
        SharedPrefHelper.putEncrypted(context, key, value)
    }

    override fun getCheckPoint(key: String): String? =
        try {
            val encrypted = SharedPrefHelper.get(context, key, "")
            if (encrypted.isEmpty()) "" else SharedPrefHelper.getDecrypted(context, key, "")
        } catch (e: Exception) {
            null
        }

    override fun clearCheckPoint(key: String) {
        SharedPrefHelper.remove(context, key)
    }

    override fun deteleSavedMenu() {
        SharedPrefHelper.remove(context, AccountPreferenceConstants.MENU_MODEL)
        SharedPrefHelper.remove(context, AccountPreferenceConstants.MENU_MODEL2)
        SharedPrefHelper.remove(context, AccountPreferenceConstants.MENU_MODEL3)
    }

    override fun deleteCheckPoint() {
        SharedPrefHelper.remove(context, AccountPreferenceConstants.CHECK_POINT)
    }

    override fun deleteCheckPointDS() {
        SharedPrefHelper.remove(context, AccountPreferenceConstants.CHECK_POINT_DS)
    }

    override fun deleteBlastId() {
        SharedPrefHelper.remove(context, AccountPreferenceConstants.BLAST_ID)
    }
}

```

**File: `core/preference/src/main/kotlin/id/co/bri/brimons/core/preference/impl/appconfig/AppConfigPreference.kt`**
```kotlin
package id.co.bri.brimons.core.preference.impl.appconfig

interface AppConfigPreference {
    fun getBundleVersion(bundleName: String): String

    fun getBundleVersionId(bundleName: String): String

    fun getBundleSourceChecksum(bundleName: String): String

    fun getApplyVccDataForm(): String

    fun getApplyVccRequest(): String

    fun getRiplayUrl(): String

    fun isDklC2(): Boolean

    fun isInitC2TokenSuccess(): Boolean

    fun saveBundleVersion(bundleName: String, bundleVersion: String?)

    fun saveBundleVersionId(bundleName: String, bundleVersionId: String?)

    fun saveBundleSourceChecksum(bundleName: String, bundleSourceChecksum: String?)

    fun setApplyVccDataForm(applyCcDataFormModelJson: String)

    fun setApplyVccRequest(applyCcDataFormModelJson: String)

    fun setRiplayUrl(riplayUrl: String?)

    fun saveDklC2(isDklC2: Boolean)

    fun saveInitC2TokenSuccess(successInit: Boolean)
}

```

**File: `core/preference/src/main/kotlin/id/co/bri/brimons/core/preference/impl/appconfig/AppConfigPreferenceConstants.kt`**
```kotlin
package id.co.bri.brimons.core.preference.impl.appconfig

object AppConfigPreferenceConstants {
    const val BUNDLE_VERSION_PREFIX = "bundle_version_"
    const val BUNDLE_VERSION_ID_PREFIX = "bundle_version_id_"
    const val BUNDLE_SOURCE_CHECKSUM_PREFIX = "bundle_source_checksum_"
    const val APPLY_VCC_DATA_FORM = "apply_vcc_data_form"
    const val APPLY_VCC_REQUEST = "apply_vcc_request"
    const val RIPLAY_URL = "url_riplay"
    const val DKL_C2 = "dkl_c2"
    const val INIT_C2_TOKEN_SUCCESS = "init_c2_token_success"
    const val TOKEN_INIT_DKL_C2 = "init_c2_token"
}

```

**File: `core/preference/src/main/kotlin/id/co/bri/brimons/core/preference/impl/appconfig/AppConfigPreferenceImpl.kt`**
```kotlin
package id.co.bri.brimons.core.preference.impl.appconfig

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import id.co.bri.brimons.core.preference.impl.SharedPrefHelper
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppConfigPreferenceImpl
@Inject
constructor(@ApplicationContext private val context: Context) : AppConfigPreference {

    override fun getBundleVersion(bundleName: String): String =
        SharedPrefHelper.get(
            context,
            "${AppConfigPreferenceConstants.BUNDLE_VERSION_PREFIX}$bundleName",
            "",
        )

    override fun getBundleVersionId(bundleName: String): String =
        SharedPrefHelper.get(
            context,
            "${AppConfigPreferenceConstants.BUNDLE_VERSION_ID_PREFIX}$bundleName",
            "0",
        )

    override fun getBundleSourceChecksum(bundleName: String): String =
        SharedPrefHelper.get(
            context,
            "${AppConfigPreferenceConstants.BUNDLE_SOURCE_CHECKSUM_PREFIX}$bundleName",
            "",
        )

    override fun getApplyVccDataForm(): String =
        SharedPrefHelper.getDecrypted(context, AppConfigPreferenceConstants.APPLY_VCC_DATA_FORM, "")

    override fun getApplyVccRequest(): String =
        SharedPrefHelper.getDecrypted(context, AppConfigPreferenceConstants.APPLY_VCC_REQUEST, "")

    override fun getRiplayUrl(): String =
        SharedPrefHelper.getDecrypted(context, AppConfigPreferenceConstants.RIPLAY_URL, "")

    override fun isDklC2(): Boolean =
        SharedPrefHelper.get(context, AppConfigPreferenceConstants.DKL_C2, false)

    override fun isInitC2TokenSuccess(): Boolean =
        SharedPrefHelper.get(context, AppConfigPreferenceConstants.INIT_C2_TOKEN_SUCCESS, false)

    override fun saveBundleVersion(bundleName: String, bundleVersion: String?) {
        SharedPrefHelper.put(
            context,
            "${AppConfigPreferenceConstants.BUNDLE_VERSION_PREFIX}$bundleName",
            bundleVersion,
        )
    }

    override fun saveBundleVersionId(bundleName: String, bundleVersionId: String?) {
        SharedPrefHelper.put(
            context,
            "${AppConfigPreferenceConstants.BUNDLE_VERSION_ID_PREFIX}$bundleName",
            bundleVersionId,
        )
    }

    override fun saveBundleSourceChecksum(bundleName: String, bundleSourceChecksum: String?) {
        SharedPrefHelper.put(
            context,
            "${AppConfigPreferenceConstants.BUNDLE_SOURCE_CHECKSUM_PREFIX}$bundleName",
            bundleSourceChecksum,
        )
    }

    override fun setApplyVccDataForm(applyCcDataFormModelJson: String) {
        SharedPrefHelper.putEncrypted(
            context,
            AppConfigPreferenceConstants.APPLY_VCC_DATA_FORM,
            applyCcDataFormModelJson,
        )
    }

    override fun setApplyVccRequest(applyCcDataFormModelJson: String) {
        SharedPrefHelper.putEncrypted(
            context,
            AppConfigPreferenceConstants.APPLY_VCC_REQUEST,
            applyCcDataFormModelJson,
        )
    }

    override fun setRiplayUrl(riplayUrl: String?) {
        SharedPrefHelper.putEncrypted(context, AppConfigPreferenceConstants.RIPLAY_URL, riplayUrl)
    }

    override fun saveDklC2(isDklC2: Boolean) {
        SharedPrefHelper.put(context, AppConfigPreferenceConstants.DKL_C2, isDklC2)
    }

    override fun saveInitC2TokenSuccess(successInit: Boolean) {
        SharedPrefHelper.put(
            context,
            AppConfigPreferenceConstants.INIT_C2_TOKEN_SUCCESS,
            successInit,
        )
    }
}

```

**File: `core/preference/src/main/kotlin/id/co/bri/brimons/core/preference/impl/biometric/BiometricPreference.kt`**
```kotlin
package id.co.bri.brimons.core.preference.impl.biometric

interface BiometricPreference {
    fun getStatusAktivasi(): Boolean

    fun getBiometricType(): String

    fun getValueKeyBiometric(): String

    fun getStatusBioChange(): Boolean

    fun getStatusUpdateBio(): Boolean

    fun getBottomBiometric(): Boolean

    fun getAktivasiVoiceAssistant(): Boolean

    fun isBioChangedDialogShown(): Boolean

    fun isBiometricLockoutPermanently(): Boolean

    fun saveStatusAktivasi(statusAktivasi: Boolean)

    fun saveFaceAvailable(faceAvailable: Boolean)

    fun saveFingerAvailable(fingerAvailable: Boolean)

    fun saveBiometricType(biometricType: String?)

    fun saveValueKeyBiometric(valueBiometric: String?)

    fun saveStatusBioChange(statusBioChange: Boolean)

    fun saveStatusUpdateBio(statusUpdate: Boolean)

    fun saveBottomBiometric(bottomBiometric: Boolean)

    fun saveAktivasiVoiceAssistant(newStatusAktivasiVoiceAssistant: Boolean)

    fun setBioChangedDialogShown(isBioChangedDialogShown: Boolean)

    fun setBiometricLockoutPermanently(isBiometricLockoutPermanently: Boolean)

    fun deleteStatusAktivasi()

    fun deleteValueKeyBiometric()
}

```

**File: `core/preference/src/main/kotlin/id/co/bri/brimons/core/preference/impl/biometric/BiometricPreferenceConstants.kt`**
```kotlin
package id.co.bri.brimons.core.preference.impl.biometric

object BiometricPreferenceConstants {
    const val STATUS_BIOMETRIC = "status_biometric"
    const val FACE_ID_AVAILABLE = "face_available"
    const val FINGERPRINT_AVAILABLE = "fingerprint_available"
    const val BIOMETRIC_TYPE = "biometric_type"
    const val BIOMETRIC_CHANGED = "is_bio_changed"
    const val STATUS_UPDATE_BIOMETRIC = "is_update_bio"
    const val VALUE_KEY_BIOMETRIC = "value_biometric"
    const val BOTTOM_BIOMETRIC = "is_bottom_biometric"
    const val IS_BIO_CHANGED_DIALOG_SHOWN = "IsBioChangedDialogShown"
    const val IS_BIOMETRIC_LOCKOUT_PERMANENTLY = "IsBiometricLockoutPermanently"
    const val VOICE_ASSISTANT_STATUS = "VoiceAssistant"
}

```

**File: `core/preference/src/main/kotlin/id/co/bri/brimons/core/preference/impl/biometric/BiometricPreferenceImpl.kt`**
```kotlin
package id.co.bri.brimons.core.preference.impl.biometric

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import id.co.bri.brimons.core.preference.impl.SharedPrefHelper
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BiometricPreferenceImpl
@Inject
constructor(@ApplicationContext private val context: Context) : BiometricPreference {

    override fun getStatusAktivasi(): Boolean =
        SharedPrefHelper.get(context, BiometricPreferenceConstants.STATUS_BIOMETRIC, false)

    override fun getBiometricType(): String =
        SharedPrefHelper.getDecrypted(context, BiometricPreferenceConstants.BIOMETRIC_TYPE, "")

    override fun getValueKeyBiometric(): String =
        SharedPrefHelper.getDecrypted(context, BiometricPreferenceConstants.VALUE_KEY_BIOMETRIC, "")

    override fun getStatusBioChange(): Boolean =
        SharedPrefHelper.get(context, BiometricPreferenceConstants.BIOMETRIC_CHANGED, false)

    override fun getStatusUpdateBio(): Boolean =
        SharedPrefHelper.get(context, BiometricPreferenceConstants.STATUS_UPDATE_BIOMETRIC, false)

    override fun getBottomBiometric(): Boolean =
        SharedPrefHelper.get(context, BiometricPreferenceConstants.BOTTOM_BIOMETRIC, false)

    override fun getAktivasiVoiceAssistant(): Boolean =
        SharedPrefHelper.get(context, BiometricPreferenceConstants.VOICE_ASSISTANT_STATUS, false)

    override fun isBioChangedDialogShown(): Boolean =
        SharedPrefHelper.get(
            context,
            BiometricPreferenceConstants.IS_BIO_CHANGED_DIALOG_SHOWN,
            false,
        )

    override fun isBiometricLockoutPermanently(): Boolean =
        SharedPrefHelper.get(
            context,
            BiometricPreferenceConstants.IS_BIOMETRIC_LOCKOUT_PERMANENTLY,
            false,
        )

    override fun saveStatusAktivasi(statusAktivasi: Boolean) {
        SharedPrefHelper.put(context, BiometricPreferenceConstants.STATUS_BIOMETRIC, statusAktivasi)
    }

    override fun saveFaceAvailable(faceAvailable: Boolean) {
        SharedPrefHelper.put(context, BiometricPreferenceConstants.FACE_ID_AVAILABLE, faceAvailable)
    }

    override fun saveFingerAvailable(fingerAvailable: Boolean) {
        SharedPrefHelper.put(
            context,
            BiometricPreferenceConstants.FINGERPRINT_AVAILABLE,
            fingerAvailable,
        )
    }

    override fun saveBiometricType(biometricType: String?) {
        SharedPrefHelper.putEncrypted(
            context,
            BiometricPreferenceConstants.BIOMETRIC_TYPE,
            biometricType,
        )
    }

    override fun saveValueKeyBiometric(valueBiometric: String?) {
        SharedPrefHelper.putEncrypted(
            context,
            BiometricPreferenceConstants.VALUE_KEY_BIOMETRIC,
            valueBiometric,
        )
    }

    override fun saveStatusBioChange(statusBioChange: Boolean) {
        SharedPrefHelper.put(
            context,
            BiometricPreferenceConstants.BIOMETRIC_CHANGED,
            statusBioChange,
        )
    }

    override fun saveStatusUpdateBio(statusUpdate: Boolean) {
        SharedPrefHelper.put(
            context,
            BiometricPreferenceConstants.STATUS_UPDATE_BIOMETRIC,
            statusUpdate,
        )
    }

    override fun saveBottomBiometric(bottomBiometric: Boolean) {
        SharedPrefHelper.put(
            context,
            BiometricPreferenceConstants.BOTTOM_BIOMETRIC,
            bottomBiometric,
        )
    }

    override fun saveAktivasiVoiceAssistant(newStatusAktivasiVoiceAssistant: Boolean) {
        SharedPrefHelper.put(
            context,
            BiometricPreferenceConstants.VOICE_ASSISTANT_STATUS,
            newStatusAktivasiVoiceAssistant,
        )
    }

    override fun setBioChangedDialogShown(isBioChangedDialogShown: Boolean) {
        SharedPrefHelper.put(
            context,
            BiometricPreferenceConstants.IS_BIO_CHANGED_DIALOG_SHOWN,
            isBioChangedDialogShown,
        )
    }

    override fun setBiometricLockoutPermanently(isBiometricLockoutPermanently: Boolean) {
        SharedPrefHelper.put(
            context,
            BiometricPreferenceConstants.IS_BIOMETRIC_LOCKOUT_PERMANENTLY,
            isBiometricLockoutPermanently,
        )
    }

    override fun deleteStatusAktivasi() {
        SharedPrefHelper.remove(context, BiometricPreferenceConstants.STATUS_BIOMETRIC)
    }

    override fun deleteValueKeyBiometric() {
        SharedPrefHelper.remove(context, BiometricPreferenceConstants.VALUE_KEY_BIOMETRIC)
    }
}

```

**File: `core/preference/src/main/kotlin/id/co/bri/brimons/core/preference/impl/fcm/FcmPreference.kt`**
```kotlin
package id.co.bri.brimons.core.preference.impl.fcm

interface FcmPreference {
    /** Raw JSON, or empty when nothing is pending. */
    fun getPendingNotification(): String

    fun hasPendingNotification(): Boolean

    fun savePendingNotification(json: String)

    fun clearPendingNotification()
}

```

**File: `core/preference/src/main/kotlin/id/co/bri/brimons/core/preference/impl/fcm/FcmPreferenceConstants.kt`**
```kotlin
package id.co.bri.brimons.core.preference.impl.fcm

object FcmPreferenceConstants {
    const val PENDING_NOTIFICATION = "pending_notification"
    const val HAS_PENDING_NOTIFICATION = "has_notification"
    const val FLAG_TRUE = "true"
}

```

**File: `core/preference/src/main/kotlin/id/co/bri/brimons/core/preference/impl/fcm/FcmPreferenceImpl.kt`**
```kotlin
package id.co.bri.brimons.core.preference.impl.fcm

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import id.co.bri.brimons.core.preference.impl.SharedPrefHelper
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FcmPreferenceImpl @Inject constructor(@ApplicationContext private val context: Context) :
    FcmPreference {

    override fun getPendingNotification(): String =
        SharedPrefHelper.get(context, FcmPreferenceConstants.PENDING_NOTIFICATION, "")

    /**
     * Read as a string, not a boolean: installed apps already hold `"true"` under this key, so
     * `getBoolean` would throw ClassCastException.
     */
    override fun hasPendingNotification(): Boolean =
        SharedPrefHelper.get(context, FcmPreferenceConstants.HAS_PENDING_NOTIFICATION, "")
            .equals(FcmPreferenceConstants.FLAG_TRUE, ignoreCase = true)

    override fun savePendingNotification(json: String) {
        SharedPrefHelper.put(context, FcmPreferenceConstants.PENDING_NOTIFICATION, json)
        SharedPrefHelper.put(
            context,
            FcmPreferenceConstants.HAS_PENDING_NOTIFICATION,
            FcmPreferenceConstants.FLAG_TRUE,
        )
    }

    override fun clearPendingNotification() {
        SharedPrefHelper.remove(context, FcmPreferenceConstants.PENDING_NOTIFICATION)
        SharedPrefHelper.remove(context, FcmPreferenceConstants.HAS_PENDING_NOTIFICATION)
    }
}

```

**File: `core/preference/src/main/kotlin/id/co/bri/brimons/core/preference/impl/general/GeneralPreference.kt`**
```kotlin
package id.co.bri.brimons.core.preference.impl.general

interface GeneralPreference {
    fun getPfmBubble(): Boolean

    fun getVallasBubble(): Boolean

    fun getSiklusFirstOpen(): Boolean

    fun getFastMenuFirst(): Boolean

    fun getDisablePopupNotif(): Boolean

    fun getAsuransiBubble(): Boolean

    fun getRencanaBubble(): Boolean

    fun getRencanaDetailBubble(): Boolean

    fun getInfoRencanaBottom(): Boolean

    fun getEmasBubble(): Boolean

    fun getPengkinianBubble(): Boolean

    fun getChatBankingBubble(): Boolean

    fun getBlockCardBubble(): Boolean

    fun getProfileRevampBubble(): Boolean

    fun getBubbleAlertSaldo(): Boolean

    fun getUpdateTokenFirebase(): Boolean

    fun getBubbleNewOnboarding(): Boolean

    fun getBannerImageUrl(): String

    fun getTitleBanner(): String

    fun getListFastMenu(): String

    fun getListFastMenuDefault(): String

    fun getListFastMenuDefaultToggle(): String

    fun getSavedMenu(): String

    fun isSavedDbRevamp(): Boolean

    fun getAppIconKey(): String

    fun getOpenCampaignEvent(): String

    fun isIndihomeFirstClick(): Boolean

    fun getIsFirstTimeShowAgf(page: String): Boolean

    fun getIsFirstTimeShowAft(): Boolean

    fun getPayloadMiniApps(): String

    fun savePfmBubble(pfmFirst: Boolean)

    fun saveVallasBubble(vallasFirst: Boolean)

    fun saveSiklusFirst(pfmFirst: Boolean)

    fun saveFirstFastMenu(fastMenuFirst: Boolean)

    fun disablePopupNotif(allowPopup: Boolean)

    fun saveAsuransiBubble(asuransiFirst: Boolean)

    fun saveRencanaBubble(rencanaFirst: Boolean)

    fun saveRencanaDetailBubble(rencanaDetailFirst: Boolean)

    fun saveInfoRencanaBottom(firstRencanaRevamp: Boolean?)

    fun saveEmasBubble(isBubble: Boolean)

    fun savePengkinianBubble(pengkinianFirst: Boolean)

    fun saveChatBankingBubble(isBubble: Boolean)

    fun saveBlockCardBubble(isBubble: Boolean)

    fun saveProfileRevampBubble(isBubble: Boolean)

    fun savebubbleAlertSaldo(alertDialog: Boolean)

    fun saveBannerImageUrl(imageUrl: String?)

    fun saveTitleBanner(title: String?)

    fun saveListFastMenu(fastMenu: String?)

    fun saveListFastMenuDefault(fastMenuDefault: String?)

    fun saveListFastMenuDefaultToggle(fastMenuDefault: String?)

    fun saveDBMenuRevamp(isSaved: Boolean)

    fun saveAppIconKey(iconKey: String?)

    fun saveOpenCampaign(openUntil: String?)

    fun saveIndihomeFirstClick(isFirst: Boolean)

    fun setIsFirstTimeShowAgf(page: String, isFirstTime: Boolean)

    fun setIsFirstTimeShowAft(isFirstTimeShowAft: Boolean)

    fun saveMiniAppPayload(payload: String?)

    fun deleteSavedMenu()
}

```

**File: `core/preference/src/main/kotlin/id/co/bri/brimons/core/preference/impl/general/GeneralPreferenceConstants.kt`**
```kotlin
package id.co.bri.brimons.core.preference.impl.general

object GeneralPreferenceConstants {
    const val FAST_MENU = "fast_menu"
    const val FAST_MENU_DEFAULT = "fast_menu_default"
    const val FAST_MENU_DEFAULT_TOGGLE = "fast_menu_default_toggle"
    const val FAST_MENU_FIRST_OPENED = "FastMenuFirstOpened"
    const val PFM_FIRST_OPENED = "PFMFirstOpened"
    const val VALLAS_FIRST_OPENED = "VallasFirstOpened"
    const val SIKLUS_FIRST_OPENED = "SiklusFirstOpened"
    const val ASURANSI_FIRST_OPENED = "AsuransiFirstOpened"
    const val RENCANA_FIRST_OPENED = "RencanaFirstOpened"
    const val DETAIL_RENCANA_FIRST_OPENED = "DetailRencanaFirstUsed"
    const val INFO_RENCANA_FIRST_OPENED = "InfoRencanaFirstOpened"
    const val EMAS_FIRST_OPENED = "EmasFirstOpened"
    const val PENGKINIAN_FIRST_OPENED = "pengkinianFirstOpened"
    const val CHAT_BANKING_FIRST_OPENED = "ChatBankingFirstOpened"
    const val BLOCK_CARD_FIRST_OPENED = "BlockCardFirstOpened"
    const val PROFILE_REVAMP_FIRST_OPENED = "ProfileRevampFirstOpened"
    const val ALLOW_NOTIF = "allow_popup_notif"
    const val TAG_ALERT_SALDO = "alert_saldo"
    const val IMAGE_BANNER = "bannerImage"
    const val TITLE_BANNER = "bannerTitle"
    const val SAVED_DB_REVAMP = "saved_db_revamp"
    const val DYNAMIC_APP_ICON = "dynamic_app_icon"
    const val BRIMO_FSTVL_OPEN_UNTIL = "brimo_fstvl_open_until"
    const val INDIHOME_REGISTER = "first_indihome_register"
    const val FIRST_TIME_SHOW_AFT = "IsFirstTimeShowAft"
    const val PAYLOAD = "payload"
}

```

**File: `core/preference/src/main/kotlin/id/co/bri/brimons/core/preference/impl/general/GeneralPreferenceImpl.kt`**
```kotlin
package id.co.bri.brimons.core.preference.impl.general

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import id.co.bri.brimons.core.preference.impl.SharedPrefHelper
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GeneralPreferenceImpl @Inject constructor(@ApplicationContext private val context: Context) :
    GeneralPreference {

    override fun getPfmBubble(): Boolean =
        SharedPrefHelper.get(context, GeneralPreferenceConstants.PFM_FIRST_OPENED, false)

    override fun getVallasBubble(): Boolean =
        SharedPrefHelper.get(context, GeneralPreferenceConstants.VALLAS_FIRST_OPENED, false)

    override fun getSiklusFirstOpen(): Boolean =
        SharedPrefHelper.get(context, GeneralPreferenceConstants.SIKLUS_FIRST_OPENED, false)

    override fun getFastMenuFirst(): Boolean =
        SharedPrefHelper.get(context, GeneralPreferenceConstants.FAST_MENU_FIRST_OPENED, false)

    override fun getDisablePopupNotif(): Boolean =
        SharedPrefHelper.get(context, GeneralPreferenceConstants.ALLOW_NOTIF, false)

    override fun getAsuransiBubble(): Boolean =
        SharedPrefHelper.get(context, GeneralPreferenceConstants.ASURANSI_FIRST_OPENED, false)

    override fun getRencanaBubble(): Boolean =
        SharedPrefHelper.get(context, GeneralPreferenceConstants.RENCANA_FIRST_OPENED, false)

    override fun getRencanaDetailBubble(): Boolean =
        SharedPrefHelper.get(context, GeneralPreferenceConstants.DETAIL_RENCANA_FIRST_OPENED, false)

    override fun getInfoRencanaBottom(): Boolean =
        SharedPrefHelper.get(context, GeneralPreferenceConstants.INFO_RENCANA_FIRST_OPENED, false)

    override fun getEmasBubble(): Boolean =
        SharedPrefHelper.get(context, GeneralPreferenceConstants.EMAS_FIRST_OPENED, false)

    override fun getPengkinianBubble(): Boolean =
        SharedPrefHelper.get(context, GeneralPreferenceConstants.PENGKINIAN_FIRST_OPENED, false)

    override fun getChatBankingBubble(): Boolean =
        SharedPrefHelper.get(context, GeneralPreferenceConstants.CHAT_BANKING_FIRST_OPENED, false)

    override fun getBlockCardBubble(): Boolean =
        SharedPrefHelper.get(context, GeneralPreferenceConstants.BLOCK_CARD_FIRST_OPENED, false)

    override fun getProfileRevampBubble(): Boolean =
        SharedPrefHelper.get(context, GeneralPreferenceConstants.PROFILE_REVAMP_FIRST_OPENED, false)

    override fun getBubbleAlertSaldo(): Boolean =
        SharedPrefHelper.get(context, GeneralPreferenceConstants.TAG_ALERT_SALDO, false)

    override fun getUpdateTokenFirebase(): Boolean =
        SharedPrefHelper.get(context, GeneralPreferenceConstants.BRIMO_FSTVL_OPEN_UNTIL, false)

    override fun getBubbleNewOnboarding(): Boolean =
        SharedPrefHelper.get(context, GeneralPreferenceConstants.BRIMO_FSTVL_OPEN_UNTIL, false)

    override fun getBannerImageUrl(): String =
        SharedPrefHelper.getDecrypted(context, GeneralPreferenceConstants.IMAGE_BANNER, "")

    override fun getTitleBanner(): String =
        SharedPrefHelper.get(context, GeneralPreferenceConstants.TITLE_BANNER, "")

    override fun getListFastMenu(): String =
        SharedPrefHelper.getDecrypted(context, GeneralPreferenceConstants.FAST_MENU, "")

    override fun getListFastMenuDefault(): String =
        SharedPrefHelper.getDecrypted(context, GeneralPreferenceConstants.FAST_MENU_DEFAULT, "")

    override fun getListFastMenuDefaultToggle(): String =
        SharedPrefHelper.getDecrypted(
            context,
            GeneralPreferenceConstants.FAST_MENU_DEFAULT_TOGGLE,
            "",
        )

    override fun getSavedMenu(): String =
        SharedPrefHelper.get(context, GeneralPreferenceConstants.FAST_MENU, "")

    override fun isSavedDbRevamp(): Boolean =
        SharedPrefHelper.get(context, GeneralPreferenceConstants.SAVED_DB_REVAMP, false)

    override fun getAppIconKey(): String =
        SharedPrefHelper.get(context, GeneralPreferenceConstants.DYNAMIC_APP_ICON, "")

    override fun getOpenCampaignEvent(): String =
        SharedPrefHelper.getDecrypted(
            context,
            GeneralPreferenceConstants.BRIMO_FSTVL_OPEN_UNTIL,
            "",
        )

    override fun isIndihomeFirstClick(): Boolean =
        SharedPrefHelper.get(context, GeneralPreferenceConstants.INDIHOME_REGISTER, false)

    override fun getIsFirstTimeShowAgf(page: String): Boolean {
        var isFirstTime = false
        if (SharedPrefHelper.contains(context, page)) {
            isFirstTime = SharedPrefHelper.get(context, page, true)
        }
        return isFirstTime
    }

    override fun getIsFirstTimeShowAft(): Boolean =
        SharedPrefHelper.get(context, GeneralPreferenceConstants.FIRST_TIME_SHOW_AFT, true)

    override fun getPayloadMiniApps(): String =
        SharedPrefHelper.get(context, GeneralPreferenceConstants.PAYLOAD, "")

    override fun savePfmBubble(pfmFirst: Boolean) {
        SharedPrefHelper.put(context, GeneralPreferenceConstants.PFM_FIRST_OPENED, pfmFirst)
    }

    override fun saveVallasBubble(vallasFirst: Boolean) {
        SharedPrefHelper.put(context, GeneralPreferenceConstants.VALLAS_FIRST_OPENED, vallasFirst)
    }

    override fun saveSiklusFirst(pfmFirst: Boolean) {
        SharedPrefHelper.put(context, GeneralPreferenceConstants.SIKLUS_FIRST_OPENED, pfmFirst)
    }

    override fun saveFirstFastMenu(fastMenuFirst: Boolean) {
        SharedPrefHelper.put(
            context,
            GeneralPreferenceConstants.FAST_MENU_FIRST_OPENED,
            fastMenuFirst,
        )
    }

    override fun disablePopupNotif(allowPopup: Boolean) {
        SharedPrefHelper.put(context, GeneralPreferenceConstants.ALLOW_NOTIF, allowPopup)
    }

    override fun saveAsuransiBubble(asuransiFirst: Boolean) {
        SharedPrefHelper.put(
            context,
            GeneralPreferenceConstants.ASURANSI_FIRST_OPENED,
            asuransiFirst,
        )
    }

    override fun saveRencanaBubble(rencanaFirst: Boolean) {
        SharedPrefHelper.put(context, GeneralPreferenceConstants.RENCANA_FIRST_OPENED, rencanaFirst)
    }

    override fun saveRencanaDetailBubble(rencanaDetailFirst: Boolean) {
        SharedPrefHelper.put(
            context,
            GeneralPreferenceConstants.DETAIL_RENCANA_FIRST_OPENED,
            rencanaDetailFirst,
        )
    }

    override fun saveInfoRencanaBottom(firstRencanaRevamp: Boolean?) {
        SharedPrefHelper.put(
            context,
            GeneralPreferenceConstants.INFO_RENCANA_FIRST_OPENED,
            firstRencanaRevamp,
        )
    }

    override fun saveEmasBubble(isBubble: Boolean) {
        SharedPrefHelper.put(context, GeneralPreferenceConstants.EMAS_FIRST_OPENED, isBubble)
    }

    override fun savePengkinianBubble(pengkinianFirst: Boolean) {
        SharedPrefHelper.put(
            context,
            GeneralPreferenceConstants.PENGKINIAN_FIRST_OPENED,
            pengkinianFirst,
        )
    }

    override fun saveChatBankingBubble(isBubble: Boolean) {
        SharedPrefHelper.put(
            context,
            GeneralPreferenceConstants.CHAT_BANKING_FIRST_OPENED,
            isBubble,
        )
    }

    override fun saveBlockCardBubble(isBubble: Boolean) {
        SharedPrefHelper.put(context, GeneralPreferenceConstants.BLOCK_CARD_FIRST_OPENED, isBubble)
    }

    override fun saveProfileRevampBubble(isBubble: Boolean) {
        SharedPrefHelper.put(
            context,
            GeneralPreferenceConstants.PROFILE_REVAMP_FIRST_OPENED,
            isBubble,
        )
    }

    override fun savebubbleAlertSaldo(alertDialog: Boolean) {
        SharedPrefHelper.put(context, GeneralPreferenceConstants.TAG_ALERT_SALDO, alertDialog)
    }

    override fun saveBannerImageUrl(imageUrl: String?) {
        SharedPrefHelper.putEncrypted(context, GeneralPreferenceConstants.IMAGE_BANNER, imageUrl)
    }

    override fun saveTitleBanner(title: String?) {
        SharedPrefHelper.putEncrypted(context, GeneralPreferenceConstants.TITLE_BANNER, title)
    }

    override fun saveListFastMenu(fastMenu: String?) {
        SharedPrefHelper.putEncrypted(context, GeneralPreferenceConstants.FAST_MENU, fastMenu)
    }

    override fun saveListFastMenuDefault(fastMenuDefault: String?) {
        SharedPrefHelper.putEncrypted(
            context,
            GeneralPreferenceConstants.FAST_MENU_DEFAULT,
            fastMenuDefault,
        )
    }

    override fun saveListFastMenuDefaultToggle(fastMenuDefault: String?) {
        SharedPrefHelper.putEncrypted(
            context,
            GeneralPreferenceConstants.FAST_MENU_DEFAULT_TOGGLE,
            fastMenuDefault,
        )
    }

    override fun saveDBMenuRevamp(isSaved: Boolean) {
        SharedPrefHelper.put(context, GeneralPreferenceConstants.SAVED_DB_REVAMP, isSaved)
    }

    override fun saveAppIconKey(iconKey: String?) {
        SharedPrefHelper.put(context, GeneralPreferenceConstants.DYNAMIC_APP_ICON, iconKey)
    }

    override fun saveOpenCampaign(openUntil: String?) {
        SharedPrefHelper.putEncrypted(
            context,
            GeneralPreferenceConstants.BRIMO_FSTVL_OPEN_UNTIL,
            openUntil,
        )
    }

    override fun saveIndihomeFirstClick(isFirst: Boolean) {
        SharedPrefHelper.put(context, GeneralPreferenceConstants.INDIHOME_REGISTER, isFirst)
    }

    override fun setIsFirstTimeShowAgf(page: String, isFirstTime: Boolean) {
        SharedPrefHelper.put(context, page, isFirstTime)
    }

    override fun setIsFirstTimeShowAft(isFirstTimeShowAft: Boolean) {
        SharedPrefHelper.put(
            context,
            GeneralPreferenceConstants.FIRST_TIME_SHOW_AFT,
            isFirstTimeShowAft,
        )
    }

    override fun saveMiniAppPayload(payload: String?) {
        SharedPrefHelper.put(context, GeneralPreferenceConstants.PAYLOAD, payload)
    }

    override fun deleteSavedMenu() {
        SharedPrefHelper.remove(context, GeneralPreferenceConstants.FAST_MENU)
        SharedPrefHelper.remove(context, GeneralPreferenceConstants.FAST_MENU_DEFAULT)
        SharedPrefHelper.remove(context, GeneralPreferenceConstants.FAST_MENU_DEFAULT_TOGGLE)
    }
}

```

**File: `core/preference/src/main/kotlin/id/co/bri/brimons/core/preference/impl/investment/InvestmentPreference.kt`**
```kotlin
package id.co.bri.brimons.core.preference.impl.investment

interface InvestmentPreference {
    fun getFirstRdn(): Boolean

    fun getCheckPointRdn(): String

    fun getBubbleDashboardRdnRevamp(): Boolean

    fun getKseiDetail(): Boolean

    fun getInvestasi(): Boolean

    fun getInvestasiNoCurrency(): Boolean

    fun getInvestasiNoAset(): Boolean

    fun getDepositoRevampBuble(): Boolean

    fun getFirstDashboardDplkRevamp(): Boolean

    fun getDataGraphicDplk(): String

    fun getListPFMFirstOpen(): Boolean

    fun getReportPFMFirstOpen(): Boolean

    fun isFirstTimeAmbilFisik(): Boolean

    fun loadDepositCreationTime(): Long

    fun loadDepositCreationData(): String?

    fun saveFirstRdn(rdnFrist: Boolean)

    fun saveCheckPointRdn(checkpoint: String?)

    fun saveBubbleDashboardRdnRevamp(isShowBubble: Boolean)

    fun saveKseiDetail(ksei: Boolean)

    fun saveFirstInvestasi(investasiFirs: Boolean)

    fun saveFirstInvestasiNoCurency(investasiFirs: Boolean)

    fun saveFirstInvestasiNoAset(investasiFirs: Boolean)

    fun saveDepositoRevampBuble(firstDepositoRevamp: Boolean?)

    fun saveFirstDashboardDplkRevamp(isFirst: Boolean)

    fun saveDataGrapichDplk(response: String?)

    fun saveListPFMFirstOpen(isFirst: Boolean)

    fun saveReportPFMFirstOpen(isFirst: Boolean)

    fun saveIsFirstAmbilFisik(isFirst: Boolean)

    fun saveDepositCreationTime(millis: Long)

    fun saveDepositCreationData(value: String?)

    fun deleteDataGrapichDplk()

    fun clearDepositCreationTime()

    fun clearDepositCreationData()

    fun saveFormRDNNewSkin(key: String, form: String?)

    fun getFormRDNNewSkin(key: String): String?

    fun clearFormRDNNewSkin(key: String)
}

```

**File: `core/preference/src/main/kotlin/id/co/bri/brimons/core/preference/impl/investment/InvestmentPreferenceConstants.kt`**
```kotlin
package id.co.bri.brimons.core.preference.impl.investment

object InvestmentPreferenceConstants {
    const val RDN_FIRST = "rdn_first"
    const val CHECKPOINT_RDN = "checkpoint_rdn"
    const val BUBBLE_DASHBOARD_RDN_REVAMP = "bubble_dashboard_rdn_revamp"
    const val KSEI_FIRST_OPENED = "KseiFirstOpened"
    const val INVESTASI_FRIST = "investasi_first"
    const val INVESTASI_FRIST_NO_CURRENCY = "investasi_first_currency"
    const val INVESTASI_FRIST_NO_ASET = "investasi_first_aset"
    const val DEPOSITO_REVAMP_FIRST_OPEN = "depositoFirstOpened"
    const val DEPOSITO_NS_CREATION_TIME = "deposito_ns_creation_time"
    const val DEPOSITO_NS_CREATION_DATA = "deposito_ns_creation_data"
    const val DATA_GRAPH_DPLK = "data_graph_dplk"
    const val DASHBOARD_DPLK_REVAMP_FIRST = "dashboard_dplk_revamp_first"
    const val LIST_PFM_FIRST_OPENED = "list_pfm_first_opened"
    const val REPORT_PFM_FIRST_OPENED = "report_pfm_first_opened"
    const val CETAK_EMAS_FIRST_OPENED = "cetak_emas_first_opened"
}

```

**File: `core/preference/src/main/kotlin/id/co/bri/brimons/core/preference/impl/investment/InvestmentPreferenceImpl.kt`**
```kotlin
package id.co.bri.brimons.core.preference.impl.investment

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import id.co.bri.brimons.core.preference.impl.SharedPrefHelper
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InvestmentPreferenceImpl
@Inject
constructor(@ApplicationContext private val context: Context) : InvestmentPreference {

    override fun getFirstRdn(): Boolean =
        SharedPrefHelper.get(context, InvestmentPreferenceConstants.RDN_FIRST, false)

    override fun getCheckPointRdn(): String =
        SharedPrefHelper.get(context, InvestmentPreferenceConstants.CHECKPOINT_RDN, "")

    override fun getBubbleDashboardRdnRevamp(): Boolean =
        SharedPrefHelper.get(
            context,
            InvestmentPreferenceConstants.BUBBLE_DASHBOARD_RDN_REVAMP,
            true,
        )

    override fun getKseiDetail(): Boolean =
        SharedPrefHelper.get(context, InvestmentPreferenceConstants.KSEI_FIRST_OPENED, false)

    override fun getInvestasi(): Boolean =
        SharedPrefHelper.get(context, InvestmentPreferenceConstants.INVESTASI_FRIST, false)

    override fun getInvestasiNoCurrency(): Boolean =
        SharedPrefHelper.get(
            context,
            InvestmentPreferenceConstants.INVESTASI_FRIST_NO_CURRENCY,
            false,
        )

    override fun getInvestasiNoAset(): Boolean =
        SharedPrefHelper.get(context, InvestmentPreferenceConstants.INVESTASI_FRIST_NO_ASET, false)

    override fun getDepositoRevampBuble(): Boolean =
        SharedPrefHelper.get(
            context,
            InvestmentPreferenceConstants.DEPOSITO_REVAMP_FIRST_OPEN,
            false,
        )

    override fun getFirstDashboardDplkRevamp(): Boolean =
        SharedPrefHelper.get(
            context,
            InvestmentPreferenceConstants.DASHBOARD_DPLK_REVAMP_FIRST,
            true,
        )

    override fun getDataGraphicDplk(): String =
        SharedPrefHelper.getDecrypted(context, InvestmentPreferenceConstants.DATA_GRAPH_DPLK, "")

    override fun getListPFMFirstOpen(): Boolean =
        SharedPrefHelper.get(context, InvestmentPreferenceConstants.LIST_PFM_FIRST_OPENED, false)

    override fun getReportPFMFirstOpen(): Boolean =
        SharedPrefHelper.get(context, InvestmentPreferenceConstants.REPORT_PFM_FIRST_OPENED, false)

    override fun isFirstTimeAmbilFisik(): Boolean =
        SharedPrefHelper.get(context, InvestmentPreferenceConstants.CETAK_EMAS_FIRST_OPENED, true)

    override fun loadDepositCreationTime(): Long =
        SharedPrefHelper.get(context, InvestmentPreferenceConstants.DEPOSITO_NS_CREATION_TIME, 0L)

    override fun loadDepositCreationData(): String? =
        try {
            val encrypted =
                SharedPrefHelper.get(
                    context,
                    InvestmentPreferenceConstants.DEPOSITO_NS_CREATION_DATA,
                    "",
                )
            if (encrypted.isEmpty()) ""
            else
                SharedPrefHelper.getDecrypted(
                    context,
                    InvestmentPreferenceConstants.DEPOSITO_NS_CREATION_DATA,
                    "",
                )
        } catch (e: Exception) {
            null
        }

    override fun saveFirstRdn(rdnFrist: Boolean) {
        SharedPrefHelper.put(context, InvestmentPreferenceConstants.RDN_FIRST, rdnFrist)
    }

    override fun saveCheckPointRdn(checkpoint: String?) {
        SharedPrefHelper.put(context, InvestmentPreferenceConstants.CHECKPOINT_RDN, checkpoint)
    }

    override fun saveBubbleDashboardRdnRevamp(isShowBubble: Boolean) {
        SharedPrefHelper.put(
            context,
            InvestmentPreferenceConstants.BUBBLE_DASHBOARD_RDN_REVAMP,
            isShowBubble,
        )
    }

    override fun saveKseiDetail(ksei: Boolean) {
        SharedPrefHelper.put(context, InvestmentPreferenceConstants.KSEI_FIRST_OPENED, ksei)
    }

    override fun saveFirstInvestasi(investasiFirs: Boolean) {
        SharedPrefHelper.put(context, InvestmentPreferenceConstants.INVESTASI_FRIST, investasiFirs)
    }

    override fun saveFirstInvestasiNoCurency(investasiFirs: Boolean) {
        SharedPrefHelper.put(
            context,
            InvestmentPreferenceConstants.INVESTASI_FRIST_NO_CURRENCY,
            investasiFirs,
        )
    }

    override fun saveFirstInvestasiNoAset(investasiFirs: Boolean) {
        SharedPrefHelper.put(
            context,
            InvestmentPreferenceConstants.INVESTASI_FRIST_NO_ASET,
            investasiFirs,
        )
    }

    override fun saveDepositoRevampBuble(firstDepositoRevamp: Boolean?) {
        SharedPrefHelper.put(
            context,
            InvestmentPreferenceConstants.DEPOSITO_REVAMP_FIRST_OPEN,
            firstDepositoRevamp,
        )
    }

    override fun saveFirstDashboardDplkRevamp(isFirst: Boolean) {
        SharedPrefHelper.put(
            context,
            InvestmentPreferenceConstants.DASHBOARD_DPLK_REVAMP_FIRST,
            isFirst,
        )
    }

    override fun saveDataGrapichDplk(response: String?) {
        SharedPrefHelper.putEncrypted(
            context,
            InvestmentPreferenceConstants.DATA_GRAPH_DPLK,
            response,
        )
    }

    override fun saveListPFMFirstOpen(isFirst: Boolean) {
        SharedPrefHelper.put(context, InvestmentPreferenceConstants.LIST_PFM_FIRST_OPENED, isFirst)
    }

    override fun saveReportPFMFirstOpen(isFirst: Boolean) {
        SharedPrefHelper.put(
            context,
            InvestmentPreferenceConstants.REPORT_PFM_FIRST_OPENED,
            isFirst,
        )
    }

    override fun saveIsFirstAmbilFisik(isFirst: Boolean) {
        SharedPrefHelper.put(
            context,
            InvestmentPreferenceConstants.CETAK_EMAS_FIRST_OPENED,
            isFirst,
        )
    }

    override fun saveDepositCreationTime(millis: Long) {
        SharedPrefHelper.put(
            context,
            InvestmentPreferenceConstants.DEPOSITO_NS_CREATION_TIME,
            millis,
        )
    }

    override fun saveDepositCreationData(value: String?) {
        SharedPrefHelper.putEncrypted(
            context,
            InvestmentPreferenceConstants.DEPOSITO_NS_CREATION_DATA,
            value,
        )
    }

    override fun deleteDataGrapichDplk() {
        SharedPrefHelper.remove(context, InvestmentPreferenceConstants.DATA_GRAPH_DPLK)
    }

    override fun clearDepositCreationTime() {
        SharedPrefHelper.remove(context, InvestmentPreferenceConstants.DEPOSITO_NS_CREATION_TIME)
    }

    override fun clearDepositCreationData() {
        SharedPrefHelper.remove(context, InvestmentPreferenceConstants.DEPOSITO_NS_CREATION_DATA)
    }

    override fun saveFormRDNNewSkin(key: String, form: String?) {
        SharedPrefHelper.putEncrypted(context, key, form)
    }

    override fun getFormRDNNewSkin(key: String): String? =
        try {
            val encrypted = SharedPrefHelper.get(context, key, "")
            if (encrypted.isEmpty()) "" else SharedPrefHelper.getDecrypted(context, key, "")
        } catch (e: Exception) {
            null
        }

    override fun clearFormRDNNewSkin(key: String) {
        SharedPrefHelper.remove(context, key)
    }
}

```

**File: `core/preference/src/main/kotlin/id/co/bri/brimons/core/preference/impl/onboarding/OnboardingPreference.kt`**
```kotlin
package id.co.bri.brimons.core.preference.impl.onboarding

interface OnboardingPreference {
    fun getBubbleNewOnboarding(): Boolean

    fun getTermCondition(): String

    fun getFirstKcic(): Boolean

    fun getDialogBrifine(): Boolean

    fun getAlertMaintenanceTokenId(): String

    fun isFirstTimeTap(): Boolean

    fun getOnboardingStatusNotMatchFlag(): Boolean

    fun getOnboardingBlockedStatus(): Boolean

    //    fun getOtaParametersData(): OtaParametersByKeyData?

    fun saveBubbleNewOnboarding(newOnboarding: Boolean)

    fun saveTermCondition(termCondition: String?)

    fun saveFirstKcic(kcicFirst: Boolean)

    fun saveDialogBrifine(isDialog: Boolean)

    fun saveAlertMaintenanceTokenId(id: String?)

    fun setFirstTimeTap()

    fun setOnboardingStatusNotMatchFlag(isNotMatch: Boolean)

    fun setOnboardingBlockedStatus(isBlocked: Boolean)
    //    fun setOtaParametersData(data: OtaParametersByKeyData)
}

```

**File: `core/preference/src/main/kotlin/id/co/bri/brimons/core/preference/impl/onboarding/OnboardingPreferenceConstants.kt`**
```kotlin
package id.co.bri.brimons.core.preference.impl.onboarding

object OnboardingPreferenceConstants {
    const val ONBOARDING_BRIMO = "OnboardingBrimo"
    const val ONBOARDING_STATUS_NOT_MATCH_KEY = "onboarding_status_not_match"
    const val ONBOARDING_BLOCKED_STATUS = "onboarding_blocked_status"
    const val OTA_PARAMETERS_KEY = "ota_parameters_key"
    const val FIRST_KCIC = "first_kcic"
    const val TERM_CONDITION = "term_condition"
    const val DIALOG_FLAG = "dialog_brifine"
    const val IS_FIRST_TIME = "isFirstTime"
    const val FIRST_TIME_TAP = "first_time_tap"
}

```

**File: `core/preference/src/main/kotlin/id/co/bri/brimons/core/preference/impl/onboarding/OnboardingPreferenceImpl.kt`**
```kotlin
package id.co.bri.brimons.core.preference.impl.onboarding

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import id.co.bri.brimons.core.preference.impl.SharedPrefHelper
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OnboardingPreferenceImpl
@Inject
constructor(@ApplicationContext private val context: Context) : OnboardingPreference {

    override fun getBubbleNewOnboarding(): Boolean =
        SharedPrefHelper.get(context, OnboardingPreferenceConstants.ONBOARDING_BRIMO, false)

    override fun getTermCondition(): String =
        SharedPrefHelper.getDecrypted(context, OnboardingPreferenceConstants.TERM_CONDITION, "")

    override fun getFirstKcic(): Boolean =
        SharedPrefHelper.get(context, OnboardingPreferenceConstants.FIRST_KCIC, false)

    override fun getDialogBrifine(): Boolean =
        SharedPrefHelper.get(context, OnboardingPreferenceConstants.DIALOG_FLAG, false)

    override fun getAlertMaintenanceTokenId(): String =
        SharedPrefHelper.get(context, OnboardingPreferenceConstants.IS_FIRST_TIME, "")

    override fun isFirstTimeTap(): Boolean =
        SharedPrefHelper.get(context, OnboardingPreferenceConstants.FIRST_TIME_TAP, true)

    override fun getOnboardingStatusNotMatchFlag(): Boolean =
        try {
            SharedPrefHelper.get(
                context,
                OnboardingPreferenceConstants.ONBOARDING_STATUS_NOT_MATCH_KEY,
                false,
            )
        } catch (e: Exception) {
            false
        }

    override fun getOnboardingBlockedStatus(): Boolean =
        SharedPrefHelper.get(
            context,
            OnboardingPreferenceConstants.ONBOARDING_BLOCKED_STATUS,
            false,
        )

    //    override fun getOtaParametersData(): OtaParametersByKeyData? = try {
    //        val json = SharedPrefHelper.get(context,
    // OnboardingPreferenceConstants.OTA_PARAMETERS_KEY, "")
    //        if (json.isEmpty()) null else Gson().fromJson(json,
    // OtaParametersByKeyData::class.java)
    //    } catch (e: Exception) {
    //        null
    //    }

    override fun saveBubbleNewOnboarding(newOnboarding: Boolean) {
        SharedPrefHelper.put(context, OnboardingPreferenceConstants.ONBOARDING_BRIMO, newOnboarding)
    }

    override fun saveTermCondition(termCondition: String?) {
        SharedPrefHelper.putEncrypted(
            context,
            OnboardingPreferenceConstants.TERM_CONDITION,
            termCondition,
        )
    }

    override fun saveFirstKcic(kcicFirst: Boolean) {
        SharedPrefHelper.put(context, OnboardingPreferenceConstants.FIRST_KCIC, kcicFirst)
    }

    override fun saveDialogBrifine(isDialog: Boolean) {
        SharedPrefHelper.put(context, OnboardingPreferenceConstants.DIALOG_FLAG, isDialog)
    }

    override fun saveAlertMaintenanceTokenId(id: String?) {
        SharedPrefHelper.put(context, OnboardingPreferenceConstants.IS_FIRST_TIME, id)
    }

    override fun setFirstTimeTap() {
        SharedPrefHelper.put(context, OnboardingPreferenceConstants.FIRST_TIME_TAP, false)
    }

    override fun setOnboardingStatusNotMatchFlag(isNotMatch: Boolean) {
        SharedPrefHelper.put(
            context,
            OnboardingPreferenceConstants.ONBOARDING_STATUS_NOT_MATCH_KEY,
            isNotMatch,
        )
    }

    override fun setOnboardingBlockedStatus(isBlocked: Boolean) {
        SharedPrefHelper.put(
            context,
            OnboardingPreferenceConstants.ONBOARDING_BLOCKED_STATUS,
            isBlocked,
        )
    }

    //    override fun setOtaParametersData(data: OtaParametersByKeyData) {
    //        SharedPrefHelper.put(context, OnboardingPreferenceConstants.OTA_PARAMETERS_KEY,
    // Gson().toJson(data))
    //    }
}

```

**File: `core/preference/src/main/kotlin/id/co/bri/brimons/core/preference/impl/payment/PaymentPreference.kt`**
```kotlin
package id.co.bri.brimons.core.preference.impl.payment

interface PaymentPreference {
    fun getListCcAlreadySelectOnQris(): String

    fun getCountCcQrisNotSof(): Int

    fun isBillCreated(): Boolean

    fun saveListCcAlreadySelectOnQris(json: String?)

    fun saveCountCcQrisNotSof(count: Int)

    fun saveIsBillCreated(isBillCreated: Boolean)

    fun isFirstTimeVisitTransfer(): Boolean

    fun getTFirstTimeTransferInternasional(): Boolean

    //    fun getValidateRequest(): ArrayList<Any>?
    fun saveIsFirstTimeVisitTransfer(isFirst: Boolean)

    fun saveFirstTimeTransferInternasional(transferInternationalFirst: Boolean)

    //    fun saveBrizziValidate(validate: Any)
    fun saveBrizziValidateList(validate: Any)

    fun deleteBrizziValidate()
}

```

**File: `core/preference/src/main/kotlin/id/co/bri/brimons/core/preference/impl/payment/PaymentPreferenceConstants.kt`**
```kotlin
package id.co.bri.brimons.core.preference.impl.payment

object PaymentPreferenceConstants {
    const val LIST_CC_ALREADY_SELECT_ON_QRIS = "listCcAlreadySelectOnQris"
    const val COUNT_CC_QRIS_NOT_SOF = "countCcQrisNotSof"
    const val BILL_CREATED = "bill_created"
    const val TRANSFER_FIRST_OPENED = "transfer_first_opened"
    const val TRANSFER_INTERNASIONAL_FIRST_OPENED = "TransferInternasionalFirstOpened"
    const val BRIZZI_VALIDATE = "validate"
}

```

**File: `core/preference/src/main/kotlin/id/co/bri/brimons/core/preference/impl/payment/PaymentPreferenceImpl.kt`**
```kotlin
package id.co.bri.brimons.core.preference.impl.payment

import android.content.Context
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import id.co.bri.brimons.core.preference.impl.SharedPrefHelper
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PaymentPreferenceImpl @Inject constructor(@ApplicationContext private val context: Context) :
    PaymentPreference {

    override fun getListCcAlreadySelectOnQris(): String =
        SharedPrefHelper.getDecrypted(
            context,
            PaymentPreferenceConstants.LIST_CC_ALREADY_SELECT_ON_QRIS,
            "",
        )

    override fun getCountCcQrisNotSof(): Int =
        SharedPrefHelper.get(context, PaymentPreferenceConstants.COUNT_CC_QRIS_NOT_SOF, 0)

    override fun isBillCreated(): Boolean =
        SharedPrefHelper.get(context, PaymentPreferenceConstants.BILL_CREATED, false)

    override fun saveListCcAlreadySelectOnQris(json: String?) {
        SharedPrefHelper.putEncrypted(
            context,
            PaymentPreferenceConstants.LIST_CC_ALREADY_SELECT_ON_QRIS,
            json,
        )
    }

    override fun saveCountCcQrisNotSof(count: Int) {
        SharedPrefHelper.put(context, PaymentPreferenceConstants.COUNT_CC_QRIS_NOT_SOF, count)
    }

    override fun saveIsBillCreated(isBillCreated: Boolean) {
        SharedPrefHelper.put(context, PaymentPreferenceConstants.BILL_CREATED, isBillCreated)
    }

    private val gson = Gson()

    override fun isFirstTimeVisitTransfer(): Boolean =
        SharedPrefHelper.get(context, PaymentPreferenceConstants.TRANSFER_FIRST_OPENED, true)

    override fun getTFirstTimeTransferInternasional(): Boolean =
        SharedPrefHelper.get(
            context,
            PaymentPreferenceConstants.TRANSFER_INTERNASIONAL_FIRST_OPENED,
            true,
        )

    //    override fun getValidateRequest(): ArrayList<Any>? {
    //        val raw = SharedPrefHelper.get(context, PaymentPreferenceConstants.BRIZZI_VALIDATE,
    // "")
    //            .takeIf { it.isNotEmpty() } ?: return null
    //        return try {
    //            val decrypted = SharedPrefHelper.getDecrypted(context,
    // PaymentPreferenceConstants.BRIZZI_VALIDATE, "")
    //            val list = gson.fromJson(decrypted, ListValidateTersimpan::class.java)
    //            list?.validateRequest?.takeIf { it.isNotEmpty() } as? ArrayList<Any>
    //        } catch (e: Exception) {
    //            null
    //        }
    //    }

    override fun saveIsFirstTimeVisitTransfer(isFirst: Boolean) {
        SharedPrefHelper.put(context, PaymentPreferenceConstants.TRANSFER_FIRST_OPENED, isFirst)
    }

    override fun saveFirstTimeTransferInternasional(transferInternationalFirst: Boolean) {
        SharedPrefHelper.put(
            context,
            PaymentPreferenceConstants.TRANSFER_INTERNASIONAL_FIRST_OPENED,
            transferInternationalFirst,
        )
    }

    //    override fun saveBrizziValidate(validate: Any) {
    //        try {
    //            val raw = SharedPrefHelper.get(context,
    // PaymentPreferenceConstants.BRIZZI_VALIDATE, "")
    //            val existing: ArrayList<ValidateTersimpanRequest> = if (raw.isNotEmpty()) {
    //                val decrypted = SharedPrefHelper.getDecrypted(context,
    // PaymentPreferenceConstants.BRIZZI_VALIDATE, "")
    //                gson.fromJson(decrypted, ListValidateTersimpan::class.java)?.validateRequest
    // ?: arrayListOf()
    //            } else arrayListOf()
    //            existing.add(validate as ValidateTersimpanRequest)
    //            val wrapper = ListValidateTersimpan().apply { validateRequest = existing }
    //            SharedPrefHelper.putEncrypted(context, PaymentPreferenceConstants.BRIZZI_VALIDATE,
    // gson.toJson(wrapper))
    //        } catch (e: Exception) {
    //            e.printStackTrace()
    //        }
    //    }

    override fun saveBrizziValidateList(validate: Any) {
        SharedPrefHelper.putEncrypted(
            context,
            PaymentPreferenceConstants.BRIZZI_VALIDATE,
            gson.toJson(validate),
        )
    }

    override fun deleteBrizziValidate() {
        SharedPrefHelper.remove(context, PaymentPreferenceConstants.BRIZZI_VALIDATE)
    }
}

```

**File: `core/preference/src/main/kotlin/id/co/bri/brimons/core/preference/impl/session/SessionPreference.kt`**
```kotlin
package id.co.bri.brimons.core.preference.impl.session

interface SessionPreference {
    fun getTokenKey(): String

    fun getDeviceId(): String

    fun getDeviceId2(): String

    fun getPhoneNumber(): String

    fun getFirebaseToken(): String

    /** Token last successfully associated with an account by the push-token sync. */
    fun getLastSyncedFirebaseToken(): String

    /** Username the token was last associated with. */
    fun getLastSyncedFirebaseUsername(): String

    fun getRandom(): String

    fun getSeqNumber(): String

    fun getCurrentSeqNumber(): String

    fun getVersionApp(): String

    fun getVersionCodeApp(): Int

    fun getInstallId(): String

    fun getLoginFlag(): Boolean

    fun getChangeDeviceFlag(): Boolean

    fun getFreshInstallFlag(): Boolean

    fun getUpdateTokenFirebase(): Boolean

    fun getPauseTime(): Long

    fun getTimeIdle(): Long

    fun getLanguage(): String

    fun saveTokenKey(tokenKey: String?)

    fun saveDeviceId(deviceID: String?)

    fun saveDeviceId2(deviceID2: String?)

    fun savePhoneNumber(phoneNumber: String?)

    fun saveFirebaseToken(newToken: String?)

    fun saveLastSyncedFirebaseToken(newToken: String?)

    fun saveLastSyncedFirebaseUsername(username: String?)

    fun saveSeqNumber(seqNumber: Int)

    fun saveVersionApp(version: String?)

    fun saveVersionCodeApp(versionCode: Int)

    fun saveInstallId(installId: String)

    fun saveLoginFlag(isLogin: Boolean)

    fun saveChangeDeviceFlag(isChangeDevice: Boolean)

    fun saveFreshInstallFlag(isFreshInstall: Boolean)

    fun saveUpdateTokenFirebase(isChecked: Boolean)

    fun savePauseTime(time: Long)

    fun saveTimeIdle(timeIdle: Long)

    fun saveLanguage(language: String?)

    fun deleteSeqNumber()

    fun isContains(value: String): Boolean

    fun isLogged(): Boolean

    fun getUser(): String

    fun getUsername(): String

    fun getUserAlias(): String

    fun getName(): String

    fun getFirstName(): String

    fun getFullName(): String

    fun getLastName(): String

    fun getCustomerId(): String

    fun getPhone(): String

    fun getEmail(): String

    fun getNickname(): String

    fun getUserType(): String

    fun saveUsername(userName: String?)

    fun saveUserAlias(userAlias: String?)

    fun saveFirstName(firstName: String?)

    fun saveFullName(fullName: String?)

    fun saveLastName(lastName: String?)

    fun saveCustomerId(customerId: String?)

    fun savePhone(phone: String?)

    fun saveEmail(email: String?)

    fun saveNickname(nickname: String?)

    fun saveUserType(userType: String?)

    fun saveUserExist(exist: Boolean)

    fun deleteUsername()

    fun deleteUserAlias()

    fun clearAllData()
}

```

**File: `core/preference/src/main/kotlin/id/co/bri/brimons/core/preference/impl/session/SessionPreferenceConstants.kt`**
```kotlin
package id.co.bri.brimons.core.preference.impl.session

object SessionPreferenceConstants {
    const val TOKEN_KEY = "token_key"
    const val RANDOM = "random"
    const val SEQ_NUM = "seqnumber"
    const val PHONE_NUMBER = "phonenumber"
    const val DEVICE_ID = "deviceid"
    const val DEVICE_ID2 = "deviceid2"
    const val FIREBASE_TOKEN = "firebase_token"
    const val UPDATE_TOKEN = "update_token"
    // Key strings must stay identical to BRImoPrefRepository's — the legacy repository reads and
    // writes the same two entries.
    const val LAST_SYNCED_FIREBASE_TOKEN = "last_synced_firebase_token"
    const val LAST_SYNCED_FIREBASE_USERNAME = "last_synced_firebase_username"
    const val LOGIN_FLAG = "login_flag"
    const val CHANGE_DEVICE_FLAG = "change_device_flag"
    const val FRESH_INSTALL_FLAG = "fresh_install_flag"
    const val VERSION_APP = "version_app"
    const val LAST_APP_VERSION = "last_app_version"
    const val INSTALL_ID = "install_id"
    const val PAUSE_TIME = "pause_time"
    const val IDLE_TIME = "idletime"
    const val LANGUAGE = "language"
    const val USER = "user"
    const val USERNAME = "username"
    const val USER_ALIAS = "user_alias"
    const val NAME = "name"
    const val NICKNAME = "nickname"
    const val USER_TYPE = "usertype"
    const val USER_EXIST = "userExist"
    const val FIRST_NAME = "first_name"
    const val FULL_NAME = "full_name"
    const val LAST_NAME = "last_name"
    const val CUSTOMERID = "customerid"
    const val PHONE = "phone"
    const val EMAIL = "email"
    const val USER_REMARK = "user_remark"
    const val USER_BER_BRIMO = "user_ber_brimo"
    const val USER_BER_QITA = "user_ber_qita"
}

```

**File: `core/preference/src/main/kotlin/id/co/bri/brimons/core/preference/impl/session/SessionPreferenceImpl.kt`**
```kotlin
package id.co.bri.brimons.core.preference.impl.session

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import id.co.bri.brimons.core.preference.impl.SharedPrefHelper
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionPreferenceImpl @Inject constructor(@ApplicationContext private val context: Context) :
    SessionPreference {

    override fun getTokenKey(): String =
        SharedPrefHelper.getDecrypted(context, SessionPreferenceConstants.TOKEN_KEY, "")

    override fun getDeviceId(): String =
        SharedPrefHelper.getDecrypted(context, SessionPreferenceConstants.DEVICE_ID, "")

    override fun getDeviceId2(): String =
        SharedPrefHelper.getDecrypted(context, SessionPreferenceConstants.DEVICE_ID2, "")

    override fun getPhoneNumber(): String =
        SharedPrefHelper.getDecrypted(context, SessionPreferenceConstants.PHONE_NUMBER, "")

    override fun getFirebaseToken(): String =
        SharedPrefHelper.getDecrypted(context, SessionPreferenceConstants.FIREBASE_TOKEN, "")

    override fun getLastSyncedFirebaseToken(): String =
        SharedPrefHelper.getDecrypted(
            context,
            SessionPreferenceConstants.LAST_SYNCED_FIREBASE_TOKEN,
            "",
        )

    override fun getLastSyncedFirebaseUsername(): String =
        SharedPrefHelper.getDecrypted(
            context,
            SessionPreferenceConstants.LAST_SYNCED_FIREBASE_USERNAME,
            "",
        )

    override fun getRandom(): String =
        SharedPrefHelper.get(context, SessionPreferenceConstants.RANDOM, "")
            .takeIf { it.isNotEmpty() }
            ?.let {
                val next = it.toInt() + 1
                SharedPrefHelper.put(context, SessionPreferenceConstants.RANDOM, next.toString())
                next.toString()
            } ?: "1".also { SharedPrefHelper.put(context, SessionPreferenceConstants.RANDOM, it) }

    override fun getSeqNumber(): String =
        SharedPrefHelper.get(context, SessionPreferenceConstants.SEQ_NUM, "")
            .takeIf { it.isNotEmpty() }
            ?.let {
                val next = it.toInt() + 1
                SharedPrefHelper.put(context, SessionPreferenceConstants.SEQ_NUM, next.toString())
                next.toString()
            } ?: "1".also { SharedPrefHelper.put(context, SessionPreferenceConstants.SEQ_NUM, it) }

    override fun getCurrentSeqNumber(): String =
        SharedPrefHelper.get(context, SessionPreferenceConstants.SEQ_NUM, "1").takeIf {
            it.isNotEmpty()
        } ?: "1".also { SharedPrefHelper.put(context, SessionPreferenceConstants.SEQ_NUM, it) }

    override fun getVersionApp(): String =
        SharedPrefHelper.getDecrypted(context, SessionPreferenceConstants.VERSION_APP, "")

    override fun getVersionCodeApp(): Int =
        SharedPrefHelper.get(context, SessionPreferenceConstants.LAST_APP_VERSION, -1)

    override fun getInstallId(): String =
        SharedPrefHelper.get(context, SessionPreferenceConstants.INSTALL_ID, "")

    override fun getLoginFlag(): Boolean =
        SharedPrefHelper.get(context, SessionPreferenceConstants.LOGIN_FLAG, false)

    override fun getChangeDeviceFlag(): Boolean =
        SharedPrefHelper.get(context, SessionPreferenceConstants.CHANGE_DEVICE_FLAG, false)

    override fun getFreshInstallFlag(): Boolean =
        SharedPrefHelper.get(context, SessionPreferenceConstants.FRESH_INSTALL_FLAG, false)

    override fun getUpdateTokenFirebase(): Boolean =
        SharedPrefHelper.get(context, SessionPreferenceConstants.UPDATE_TOKEN, false)

    override fun getPauseTime(): Long =
        SharedPrefHelper.get(context, SessionPreferenceConstants.PAUSE_TIME, 0L)

    override fun getTimeIdle(): Long =
        SharedPrefHelper.get(context, SessionPreferenceConstants.IDLE_TIME, 0L)

    override fun getLanguage(): String =
        SharedPrefHelper.get(context, SessionPreferenceConstants.LANGUAGE, "language_id")

    override fun saveTokenKey(tokenKey: String?) {
        SharedPrefHelper.putEncrypted(context, SessionPreferenceConstants.TOKEN_KEY, tokenKey)
    }

    override fun saveDeviceId(deviceID: String?) {
        SharedPrefHelper.putEncrypted(context, SessionPreferenceConstants.DEVICE_ID, deviceID)
    }

    override fun saveDeviceId2(deviceID2: String?) {
        SharedPrefHelper.putEncrypted(context, SessionPreferenceConstants.DEVICE_ID2, deviceID2)
    }

    override fun savePhoneNumber(phoneNumber: String?) {
        SharedPrefHelper.putEncrypted(context, SessionPreferenceConstants.PHONE_NUMBER, phoneNumber)
    }

    override fun saveFirebaseToken(newToken: String?) {
        SharedPrefHelper.putEncrypted(context, SessionPreferenceConstants.FIREBASE_TOKEN, newToken)
    }

    override fun saveLastSyncedFirebaseToken(newToken: String?) {
        SharedPrefHelper.putEncrypted(
            context,
            SessionPreferenceConstants.LAST_SYNCED_FIREBASE_TOKEN,
            newToken,
        )
    }

    override fun saveLastSyncedFirebaseUsername(username: String?) {
        SharedPrefHelper.putEncrypted(
            context,
            SessionPreferenceConstants.LAST_SYNCED_FIREBASE_USERNAME,
            username,
        )
    }

    override fun saveSeqNumber(seqNumber: Int) {
        SharedPrefHelper.put(context, SessionPreferenceConstants.SEQ_NUM, seqNumber)
    }

    override fun saveVersionApp(version: String?) {
        SharedPrefHelper.putEncrypted(context, SessionPreferenceConstants.VERSION_APP, version)
    }

    override fun saveVersionCodeApp(versionCode: Int) {
        SharedPrefHelper.put(context, SessionPreferenceConstants.LAST_APP_VERSION, versionCode)
    }

    override fun saveInstallId(installId: String) {
        SharedPrefHelper.put(context, SessionPreferenceConstants.INSTALL_ID, installId)
    }

    override fun saveLoginFlag(isLogin: Boolean) {
        SharedPrefHelper.put(context, SessionPreferenceConstants.LOGIN_FLAG, isLogin)
    }

    override fun saveChangeDeviceFlag(isChangeDevice: Boolean) {
        SharedPrefHelper.put(context, SessionPreferenceConstants.CHANGE_DEVICE_FLAG, isChangeDevice)
    }

    override fun saveFreshInstallFlag(isFreshInstall: Boolean) {
        SharedPrefHelper.put(context, SessionPreferenceConstants.FRESH_INSTALL_FLAG, isFreshInstall)
    }

    override fun saveUpdateTokenFirebase(isChecked: Boolean) {
        SharedPrefHelper.put(context, SessionPreferenceConstants.UPDATE_TOKEN, isChecked)
    }

    override fun savePauseTime(time: Long) {
        SharedPrefHelper.put(context, SessionPreferenceConstants.PAUSE_TIME, time)
    }

    override fun saveTimeIdle(timeIdle: Long) {
        SharedPrefHelper.put(context, SessionPreferenceConstants.IDLE_TIME, timeIdle)
    }

    override fun saveLanguage(language: String?) {
        SharedPrefHelper.put(context, SessionPreferenceConstants.LANGUAGE, language)
    }

    override fun deleteSeqNumber() {
        SharedPrefHelper.remove(context, SessionPreferenceConstants.SEQ_NUM)
    }

    override fun isContains(value: String): Boolean = SharedPrefHelper.contains(context, value)

    override fun isLogged(): Boolean = getUser().isNotEmpty()

    override fun getUser(): String =
        SharedPrefHelper.getDecrypted(context, SessionPreferenceConstants.USER, "")

    override fun getUsername(): String =
        SharedPrefHelper.getDecrypted(context, SessionPreferenceConstants.USERNAME, "")

    override fun getUserAlias(): String =
        SharedPrefHelper.getDecrypted(context, SessionPreferenceConstants.USER_ALIAS, "")

    override fun getName(): String =
        SharedPrefHelper.getDecrypted(context, SessionPreferenceConstants.NAME, "")

    override fun getFirstName(): String =
        SharedPrefHelper.getDecrypted(context, SessionPreferenceConstants.FIRST_NAME, "")

    override fun getFullName(): String =
        SharedPrefHelper.getDecrypted(context, SessionPreferenceConstants.FULL_NAME, "")

    override fun getLastName(): String =
        SharedPrefHelper.getDecrypted(context, SessionPreferenceConstants.LAST_NAME, "")

    override fun getCustomerId(): String =
        SharedPrefHelper.getDecrypted(context, SessionPreferenceConstants.CUSTOMERID, "")

    override fun getPhone(): String =
        SharedPrefHelper.getDecrypted(context, SessionPreferenceConstants.PHONE, "")

    override fun getEmail(): String =
        SharedPrefHelper.getDecrypted(context, SessionPreferenceConstants.EMAIL, "")

    override fun getNickname(): String =
        SharedPrefHelper.getDecrypted(context, SessionPreferenceConstants.NICKNAME, "")

    override fun getUserType(): String =
        SharedPrefHelper.get(context, SessionPreferenceConstants.USER_TYPE, "")

    override fun saveUsername(userName: String?) {
        SharedPrefHelper.putEncrypted(context, SessionPreferenceConstants.USERNAME, userName)
    }

    override fun saveUserAlias(userAlias: String?) {
        SharedPrefHelper.putEncrypted(context, SessionPreferenceConstants.USER_ALIAS, userAlias)
    }

    override fun saveFirstName(firstName: String?) {
        SharedPrefHelper.putEncrypted(context, SessionPreferenceConstants.FIRST_NAME, firstName)
    }

    override fun saveFullName(fullName: String?) {
        SharedPrefHelper.putEncrypted(context, SessionPreferenceConstants.FULL_NAME, fullName)
    }

    override fun saveLastName(lastName: String?) {
        SharedPrefHelper.putEncrypted(context, SessionPreferenceConstants.LAST_NAME, lastName)
    }

    override fun saveCustomerId(customerId: String?) {
        SharedPrefHelper.putEncrypted(context, SessionPreferenceConstants.CUSTOMERID, customerId)
    }

    override fun savePhone(phone: String?) {
        SharedPrefHelper.putEncrypted(context, SessionPreferenceConstants.PHONE, phone)
    }

    override fun saveEmail(email: String?) {
        SharedPrefHelper.putEncrypted(context, SessionPreferenceConstants.EMAIL, email)
    }

    override fun saveNickname(nickname: String?) {
        SharedPrefHelper.putEncrypted(context, SessionPreferenceConstants.NICKNAME, nickname)
    }

    override fun saveUserType(userType: String?) {
        SharedPrefHelper.put(context, SessionPreferenceConstants.USER_TYPE, userType)
    }

    override fun saveUserExist(exist: Boolean) {
        SharedPrefHelper.put(context, SessionPreferenceConstants.USER_EXIST, true)
    }

    override fun deleteUsername() {
        SharedPrefHelper.remove(context, SessionPreferenceConstants.USERNAME)
    }

    override fun deleteUserAlias() {
        SharedPrefHelper.remove(context, SessionPreferenceConstants.USER_ALIAS)
    }

    override fun clearAllData() {
        SharedPrefHelper.clear(context)
    }
}

```

**File: `core/preference/src/test/kotlin/id/co/bri/brimons/core/preference/GeneralPreferenceImplTest.kt`**
```kotlin
package id.co.bri.brimons.core.preference

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import id.co.bri.brimons.core.preference.impl.SharedPrefHelper
import id.co.bri.brimons.core.preference.impl.general.GeneralPreferenceConstants
import id.co.bri.brimons.core.preference.impl.general.GeneralPreferenceImpl
import io.kotest.matchers.shouldBe
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GeneralPreferenceImplTest {
    private lateinit var appContext: Context
    private lateinit var pref: GeneralPreferenceImpl

    @Before
    fun setup() {
        appContext = ApplicationProvider.getApplicationContext()
        SharedPrefHelper.clear(appContext)
        pref = GeneralPreferenceImpl(appContext)
    }

    @After
    fun teardown() {
        SharedPrefHelper.clear(appContext)
    }

    @Test
    fun `feature flags`() {
        pref.savePfmBubble(true)
        pref.getPfmBubble() shouldBe true

        pref.saveVallasBubble(true)
        pref.getVallasBubble() shouldBe true

        pref.saveBlockCardBubble(true)
        pref.getBlockCardBubble() shouldBe true
    }

    @Test
    fun `string fields`() {
        pref.saveAppIconKey("icon")
        pref.getAppIconKey() shouldBe "icon"

        pref.saveOpenCampaign("campaign")
        pref.getOpenCampaignEvent() shouldBe "campaign"
    }

    @Test
    fun `toggle logic`() {
        pref.saveDBMenuRevamp(true)
        pref.isSavedDbRevamp() shouldBe true
    }

    @Test
    fun `delete menu`() {
        pref.saveListFastMenu("menu")
        pref.deleteSavedMenu() // Fixed the "detele" typo here!

        SharedPrefHelper.contains(appContext, GeneralPreferenceConstants.FAST_MENU) shouldBe false
    }
}

```

**File: `core/preference/src/test/kotlin/id/co/bri/brimons/core/preference/InvestmentPreferenceImplTest.kt`**
```kotlin
package id.co.bri.brimons.core.preference

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import id.co.bri.brimons.core.preference.impl.SharedPrefHelper
import id.co.bri.brimons.core.preference.impl.investment.InvestmentPreferenceConstants
import id.co.bri.brimons.core.preference.impl.investment.InvestmentPreferenceImpl
import io.kotest.matchers.shouldBe
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class InvestmentPreferenceImplTest {
    private lateinit var appContext: Context
    private lateinit var pref: InvestmentPreferenceImpl

    @Before
    fun setup() {
        appContext = ApplicationProvider.getApplicationContext()
        SharedPrefHelper.clear(appContext)
        pref = InvestmentPreferenceImpl(appContext)
    }

    @After
    fun teardown() {
        SharedPrefHelper.clear(appContext)
    }

    @Test
    fun `boolean flags`() {
        pref.saveFirstRdn(true)
        pref.getFirstRdn() shouldBe true

        pref.saveKseiDetail(true)
        pref.getKseiDetail() shouldBe true

        pref.saveFirstInvestasi(true)
        pref.getInvestasi() shouldBe true
    }

    @Test
    fun `string + long fields`() {
        pref.saveCheckPointRdn("cp")
        pref.getCheckPointRdn() shouldBe "cp"

        pref.saveDepositCreationTime(123L)
        pref.loadDepositCreationTime() shouldBe 123L
    }

    @Test
    fun `encrypted data safe`() {
        pref.saveDataGrapichDplk("graph-json")
        pref.getDataGraphicDplk() shouldBe "graph-json"
    }

    @Test
    fun `delete flows`() {
        pref.saveDataGrapichDplk("x")
        pref.deleteDataGrapichDplk()

        SharedPrefHelper.contains(
            appContext,
            InvestmentPreferenceConstants.DATA_GRAPH_DPLK,
        ) shouldBe false
    }
}

```

**File: `core/preference/src/test/kotlin/id/co/bri/brimons/core/preference/OnboardingPreferenceImplTest.kt`**
```kotlin
package id.co.bri.brimons.core.preference

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import id.co.bri.brimons.core.preference.impl.SharedPrefHelper
import id.co.bri.brimons.core.preference.impl.onboarding.OnboardingPreferenceImpl
import io.kotest.matchers.shouldBe
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OnboardingPreferenceImplTest {
    private lateinit var appContext: Context
    private lateinit var pref: OnboardingPreferenceImpl

    @Before
    fun setup() {
        appContext = ApplicationProvider.getApplicationContext()
        SharedPrefHelper.clear(appContext)
        pref = OnboardingPreferenceImpl(appContext)
    }

    @After
    fun teardown() {
        SharedPrefHelper.clear(appContext)
    }

    @Test
    fun `boolean onboarding flags`() {
        pref.saveBubbleNewOnboarding(true)
        pref.getBubbleNewOnboarding() shouldBe true

        pref.saveFirstKcic(true)
        pref.getFirstKcic() shouldBe true

        pref.saveDialogBrifine(true)
        pref.getDialogBrifine() shouldBe true
    }

    @Test
    fun `string fields`() {
        pref.saveTermCondition("tc")
        pref.getTermCondition() shouldBe "tc"

        pref.saveAlertMaintenanceTokenId("id-1")
        pref.getAlertMaintenanceTokenId() shouldBe "id-1"
    }

    @Test
    fun `toggle state`() {
        pref.setFirstTimeTap()
        pref.isFirstTimeTap() shouldBe false
    }
}

```

**File: `core/preference/src/test/kotlin/id/co/bri/brimons/core/preference/PaymentPreferenceImplTest.kt`**
```kotlin
package id.co.bri.brimons.core.preference

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import id.co.bri.brimons.core.preference.impl.SharedPrefHelper
import id.co.bri.brimons.core.preference.impl.payment.PaymentPreferenceConstants
import id.co.bri.brimons.core.preference.impl.payment.PaymentPreferenceImpl
import io.kotest.matchers.shouldBe
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PaymentPreferenceImplTest {
    private lateinit var appContext: Context
    private lateinit var pref: PaymentPreferenceImpl

    @Before
    fun setup() {
        appContext = ApplicationProvider.getApplicationContext()
        SharedPrefHelper.clear(appContext)
        pref = PaymentPreferenceImpl(appContext)
    }

    @After
    fun teardown() {
        SharedPrefHelper.clear(appContext)
    }

    @Test
    fun `should save and get list cc already selected on qris`() {
        pref.saveListCcAlreadySelectOnQris("[1,2,3]")
        pref.getListCcAlreadySelectOnQris() shouldBe "[1,2,3]"
    }

    @Test
    fun `should save and get count cc qris not sof`() {
        pref.saveCountCcQrisNotSof(5)
        pref.getCountCcQrisNotSof() shouldBe 5
    }

    @Test
    fun `should save and get bill created`() {
        pref.saveIsBillCreated(true)
        pref.isBillCreated() shouldBe true
    }

    @Test
    fun `should save and get first time visit transfer`() {
        pref.saveIsFirstTimeVisitTransfer(false)
        pref.isFirstTimeVisitTransfer() shouldBe false
    }

    @Test
    fun `should save and get first time transfer internasional`() {
        pref.saveFirstTimeTransferInternasional(false)
        pref.getTFirstTimeTransferInternasional() shouldBe false
    }

    @Test
    fun `should delete brizzi validate`() {
        pref.saveBrizziValidateList("{\"dummy\":true}")
        pref.deleteBrizziValidate()

        SharedPrefHelper.contains(appContext, PaymentPreferenceConstants.BRIZZI_VALIDATE) shouldBe
            false
    }

    @Test
    fun `should save brizzi validate list json`() {
        val json = """{"validate":true}"""

        pref.saveBrizziValidateList(json)

        pref.getListCcAlreadySelectOnQris() shouldBe ""
    }
}

```

**File: `core/preference/src/test/kotlin/id/co/bri/brimons/core/preference/SessionPreferenceImplTest.kt`**
```kotlin
package id.co.bri.brimons.core.preference

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import id.co.bri.brimons.core.preference.impl.SharedPrefHelper
import id.co.bri.brimons.core.preference.impl.session.SessionPreferenceConstants
import id.co.bri.brimons.core.preference.impl.session.SessionPreferenceImpl
import io.kotest.matchers.shouldBe
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SessionPreferenceImplTest {
    private lateinit var appContext: Context
    private lateinit var preference: SessionPreferenceImpl

    @Before
    fun setup() {
        appContext = ApplicationProvider.getApplicationContext()
        SharedPrefHelper.clear(appContext)
        preference = SessionPreferenceImpl(appContext)
    }

    @After
    fun teardown() {
        SharedPrefHelper.clear(appContext)
    }

    @Test
    fun `should save and get token`() {
        preference.saveTokenKey("token")
        preference.getTokenKey() shouldBe "token"
    }

    @Test
    fun `should save and get device`() {
        preference.saveDeviceId("device")
        preference.getDeviceId() shouldBe "device"
    }

    @Test
    fun `should save and get device2`() {
        preference.saveDeviceId2("device2")
        preference.getDeviceId2() shouldBe "device2"
    }

    @Test
    fun `should save and get username`() {
        preference.saveUsername("nizar")
        preference.getUsername() shouldBe "nizar"
    }

    @Test
    fun `should save and get email`() {
        preference.saveEmail("nizar@mail.com")
        preference.getEmail() shouldBe "nizar@mail.com"
    }

    @Test
    fun `should save and get version code`() {
        preference.saveVersionCodeApp(123)
        preference.getVersionCodeApp() shouldBe 123
    }

    @Test
    fun `should save and get login flag`() {
        preference.saveLoginFlag(true)
        preference.getLoginFlag() shouldBe true
    }

    @Test
    fun `should save and get pause time`() {
        preference.savePauseTime(999L)
        preference.getPauseTime() shouldBe 999L
    }

    @Test
    fun `getSeqNumber should increment`() {
        preference.getSeqNumber() shouldBe "1"
        preference.getSeqNumber() shouldBe "2"
        preference.getSeqNumber() shouldBe "3"
    }

    @Test
    fun `getRandom should increment`() {
        preference.getRandom() shouldBe "1"
        preference.getRandom() shouldBe "2"
    }

    @Test
    fun `delete username should remove value`() {
        preference.saveUsername("nizar")
        preference.deleteUsername()

        preference.getUsername() shouldBe ""
    }

    @Test
    fun `clearAllData should remove everything`() {
        preference.saveUsername("nizar")
        preference.saveDeviceId("abc")

        preference.clearAllData()

        preference.getUsername() shouldBe ""
        preference.getDeviceId() shouldBe ""
    }

    @Test
    fun `isLogged should reflect user state`() {
        preference.isLogged() shouldBe false

        SharedPrefHelper.putEncrypted(appContext, SessionPreferenceConstants.USER, "nizar")

        preference.isLogged() shouldBe true
    }
}

```

**File: `core/security/build.gradle.kts`**
```kotlin
import java.util.Properties

plugins { alias(libs.plugins.brimo.android.library.flavors) }

android {
    namespace = "id.co.bri.brimons.core.security"

    val endpointFile = rootProject.file("app/endpoint.properties")
    val endpointProperties = Properties().apply { load(endpointFile.inputStream()) }
    productFlavors {
        getByName("production") {
            buildConfigField(
                "String",
                "M_API_KEY",
                endpointProperties["PRODUCTION_API_KEY"] as String,
            )
        }
        getByName("qittaErangel") {
            buildConfigField(
                "String",
                "M_API_KEY",
                endpointProperties["QITTA_DEV_API_KEY"] as String,
            )
        }
    }
}

dependencies {
    implementation(projects.core.util)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.bundles.robolectric)
}

```

**File: `core/security/src/main/kotlin/id/co/bri/brimons/core/security/AppConfig.kt`**
```kotlin
package id.co.bri.brimons.core.security

import id.co.bri.brimons.core.security.crypto.decryptAesCbcBase64
import id.co.bri.brimons.core.util.logcat

object AppConfig {

    fun getSecret(): String {
        try {
            return decryptAesCbcBase64(BuildConfig.M_API_KEY)
        } catch (e: Exception) {
            logcat { "getSecret: ${e.message}" }
            return ""
        }
    }
}

```

**File: `core/security/src/main/kotlin/id/co/bri/brimons/core/security/crypto/Crypto.kt`**
```kotlin
package id.co.bri.brimons.core.security.crypto

import android.os.Build
import android.util.Base64 as AndroidBase64
import id.co.bri.brimons.core.security.AppConfig
import id.co.bri.brimons.core.security.crypto.SecurityConstants.AES_CBC_ALGO
import id.co.bri.brimons.core.security.crypto.SecurityConstants.AES_GCM_ALGO
import id.co.bri.brimons.core.security.crypto.SecurityConstants.AES_LENGTH
import id.co.bri.brimons.core.security.crypto.SecurityConstants.CI_ALGORITHM
import id.co.bri.brimons.core.security.crypto.SecurityConstants.MESSAGE_DIGEST_ALGORITHM
import id.co.bri.brimons.core.security.crypto.SecurityConstants.STRING_PRASE
import id.co.bri.brimons.core.util.logcat
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.Base64 as JavaBase64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

val rawSecretKey: ByteArray = ByteArray(16)

// region SHA-256
fun String.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(toByteArray(StandardCharsets.UTF_8)).joinToString(
        ""
    ) {
        "%02x".format(it)
    }

// endregion

// region MD5

/**
 * Generates an MD5 hash of the given input string.
 *
 * @param input The string to hash.
 * @return The MD5 hash as a hexadecimal string, or an empty string if the MD5 algorithm is not
 *   available.
 */
fun md5(input: String): String =
    try {
        val md = MessageDigest.getInstance(MESSAGE_DIGEST_ALGORITHM)
        val messageDigest = md.digest(input.toByteArray(StandardCharsets.UTF_8))
        messageDigest.joinToString("") { "%02x".format(it) }
    } catch (e: NoSuchAlgorithmException) {
        logcat { "MD5 fail: ${e.message}" }
        ""
    }

// endregion

// region AES/GCM/NoPadding

/**
 * Decrypt given [chip] using [iv] as base of nonce and BuildConfig's key as base of cipher key and
 * AES/GCM/NoPadding algorithm
 */
fun decryptAesGcm(chip: String, iv: String): String {
    val cipher: Cipher =
        try {
            Cipher.getInstance(AES_GCM_ALGO)
        } catch (e: Exception) {
            logcat { "AES/GCM not found - ${e.printStackTrace()}" }
            return ""
        }
    val plainText: ByteArray
    try {
        val secretKey = generateGcmSecret()
        val gcmParameterSpec = GCMParameterSpec(AES_LENGTH, iv.toByteArray(StandardCharsets.UTF_8))
        cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmParameterSpec)
        val chipByte: ByteArray = decodeBase64(chip)
        plainText = cipher.doFinal(chipByte)
    } catch (e: Exception) {
        logcat { "AES/GCM failure - ${e.printStackTrace()}" }
        return ""
    }
    return String(plainText, StandardCharsets.UTF_8)
}

/**
 * Encrypt given [plainText] using [iv] as base of nonce and BuildConfig's key as base of cipher key
 * and AES/GCM/NoPadding algorithm
 */
fun encryptAesGcmBase64(plainText: String, iv: String): String {
    try {
        val cipher = Cipher.getInstance(AES_GCM_ALGO)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            generateGcmSecret(),
            GCMParameterSpec(AES_LENGTH, iv.toByteArray(StandardCharsets.UTF_8)),
        )
        val encryptedText = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
        return encodeBase64(encryptedText)
    } catch (e: java.lang.Exception) {
        logcat { "encryptGcm Error: ${e.message}" }
        return ""
    }
}

fun decryptUrlPath(encryptedUrl: String?): String {
    if (encryptedUrl.isNullOrBlank()) return ""
    return decryptAesCbcBase64(encryptedUrl)
}

/**
 * Encrypt given [plainText] using [refNum] as base of nonce and [key] as base of cipher key and
 * AES/GCM/NoPadding algorithm
 */
fun encryptAesGcmRefNumNonce(plainText: String, refNum: String, key: String): String {
    val nonce = generateNonce(refNum)
    val byteKey = key.toByteArray(StandardCharsets.UTF_8)
    val secretKey = SecretKeySpec(byteKey, CI_ALGORITHM)
    val cipher = Cipher.getInstance(AES_GCM_ALGO)
    val gcmParameterSpec = GCMParameterSpec(AES_LENGTH, nonce)
    cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmParameterSpec)

    val cipherText = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))

    return cipherText.joinToString("") { "%02X".format(it) }
}

private fun generateNonce(refNum: String): ByteArray =
    refNum.padEnd(16, 'F').toByteArray(StandardCharsets.UTF_8)

private fun generateGcmSecret(): SecretKey? {
    val key = AppConfig.getSecret().toByteArray(StandardCharsets.UTF_8)
    return try {
        SecretKeySpec(key, 0, key.size, CI_ALGORITHM)
    } catch (_: Exception) {
        null
    }
}

// endregion

// region AES/CBC/PKCS5Padding

/**
 * decode [encrypted] using Base64 and then decrypt the result using default app's secret key as
 * cipher key and AES/CBC/PKCS5Padding algorithm
 */
@Synchronized
fun decryptAesCbcBase64(encrypted: String?): String {
    if (encrypted.isNullOrEmpty()) return ""
    var clearData: String?
    val aesKey: ByteArray? = getDigest()
    val secretKey = SecretKeySpec(aesKey, CI_ALGORITHM)
    val decodedData = decodeBase64(encrypted)
    clearData = decryptAesCbc(decodedData, secretKey)
    return clearData
}

/**
 * encrypt [plainText] with AES/CBC/PKCS5padding using app's default secret key, and then encode the
 * result in base64
 */
fun encryptAesCbcBase64(plainText: String): String {
    val aesKey: ByteArray? = getDigest()
    val secretKey = SecretKeySpec(aesKey, CI_ALGORITHM)
    val clearData: ByteArray = plainText.toByteArray()
    val encryptedData: ByteArray = encryptAesCbc(clearData, secretKey)
    return encodeBase64(encryptedData)
}

private fun getDigest(): ByteArray? {
    try {
        val digest = MessageDigest.getInstance(MESSAGE_DIGEST_ALGORITHM)
        return digest.digest(STRING_PRASE.toByteArray())
    } catch (e: NoSuchAlgorithmException) {
        logcat { "No such algorithm $MESSAGE_DIGEST_ALGORITHM - ${e.printStackTrace()}" }
    }
    return null
}

private fun decryptAesCbc(encryptedData: ByteArray, secretKey: SecretKeySpec): String {
    try {
        val ivParamSpec = IvParameterSpec(rawSecretKey)
        val aesCipher = Cipher.getInstance(AES_CBC_ALGO)
        aesCipher.init(Cipher.DECRYPT_MODE, secretKey, ivParamSpec)
        return String(aesCipher.doFinal(encryptedData))
    } catch (e: Exception) {
        logcat { "AES/CBC decrypt Exception - ${e.printStackTrace()}" }
        return ""
    }
}

private fun encryptAesCbc(clearData: ByteArray, secretKey: SecretKeySpec): ByteArray {
    try {
        val aesData = Cipher.getInstance(AES_CBC_ALGO)
        val ivParamSpec = IvParameterSpec(rawSecretKey)
        aesData.init(Cipher.ENCRYPT_MODE, secretKey, ivParamSpec)
        return aesData.doFinal(clearData)
    } catch (e: Exception) {
        logcat { "AES/CBC encrypt Exception - ${e.printStackTrace()}" }
        return "".toByteArray()
    }
}

fun decryptAesGcmBase64(encryptedBase64: String, keyBytes: ByteArray): String {
    val allBytes = decodeBase64(encryptedBase64)

    val iv = allBytes.copyOfRange(0, 12)
    val cipherText = allBytes.copyOfRange(12, allBytes.size)

    val cipher = Cipher.getInstance(AES_GCM_ALGO)
    val key = SecretKeySpec(keyBytes, CI_ALGORITHM)
    val spec = GCMParameterSpec(128, iv)

    cipher.init(Cipher.DECRYPT_MODE, key, spec)

    val plain = cipher.doFinal(cipherText)
    return String(plain, StandardCharsets.UTF_8)
}

// endregion

// region Base64

fun encodeBase64(data: ByteArray): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        JavaBase64.getEncoder().encodeToString(data)
    } else {
        AndroidBase64.encodeToString(data, AndroidBase64.DEFAULT)
    }

fun decodeBase64(data: String): ByteArray =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        JavaBase64.getDecoder().decode(data)
    } else {
        AndroidBase64.decode(data, AndroidBase64.DEFAULT)
    }

// endregion

// region Extra Helper
fun getZeroPaddedData(data: String): String {
    val paddedData = data.padStart(12, '0')
    return paddedData.substring(paddedData.length - 8)
}

fun getPaddedSeqnum(sequenceNumber: String?): String {
    if (sequenceNumber.isNullOrEmpty()) return ""
    return sequenceNumber.padStart(12, '0').padEnd(16, 'F')
}

fun decryptAsBase64Compat(encrypted: String?): String {
    if (encrypted.isNullOrEmpty()) return ""

    return try {
        val keyBytes = getDigest()
        val secretKey = SecretKeySpec(keyBytes, CI_ALGORITHM)
        val ivSpec = IvParameterSpec(rawSecretKey)
        val cipher = Cipher.getInstance(AES_CBC_ALGO)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
        val decoded = decodeBase64(encrypted)
        val result = cipher.doFinal(decoded)
        String(result, Charsets.UTF_8)
    } catch (e: Exception) {
        logcat { "decryptAsBase64Compat error: ${e.message}" }
        ""
    }
}

// endregion

object SecurityConstants {
    const val BEGIN_IV = 16
    const val END_IV = 32
    const val STRING_PRASE = "fahrdrgr"
    const val CI_ALGORITHM = "AES"
    const val MESSAGE_DIGEST_ALGORITHM = "MD5"
    const val AES_CBC_ALGO = "AES/CBC/PKCS5Padding"
    const val AES_GCM_ALGO = "AES/GCM/NoPadding"
    const val AES_LENGTH = 128
    const val DEV_STRING_CHAR1 = "EnEGXh3jrGEYHwERiyYkIg=="
    const val DEV_STRING_CHAR_2 = "An62lxtr9Py56Awf6xNkhQ=="
}

```

**File: `core/security/src/main/kotlin/id/co/bri/brimons/core/security/device/AccessibilityChecker.kt`**
```kotlin
package id.co.bri.brimons.core.security.device

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityManager
import id.co.bri.brimons.core.security.crypto.sha256

class AccessibilityChecker(private val context: Context) {

    private val allowedServices =
        setOf(
            "0365d8ad4cc5326e2c9e93ed514e449e013caf8125637bbb79812bfaef9ef772",
            "c6301cbcb13851b8f6bcb9dfd51c51b6ec893fc4f806d69fed417cc674193b55",
            "9c0e3c8e7c4935ed19081940b0e2e4a728f2bbd2eb2b60c34b32e58a54e8a979",
            "48f96ab723a440c715fa22ccebbbde72f8363abde37074beff64c14c221c5fa4",
            "c7e1539f6bf78d3e9541830723b4e72ad29e45e014c7e934b79f50f88b00bce1",
        )

    fun hasSuspiciousAccessibilityServices(): Boolean {
        val manager =
            context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager

        val packageManager = context.packageManager

        return manager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { service -> service.isSuspicious(packageManager) }
    }

    private fun AccessibilityServiceInfo.isSuspicious(packageManager: PackageManager): Boolean =
        runCatching {
                val packageName = resolveInfo.serviceInfo.packageName

                val installer = packageManager.getInstallerPackageName(packageName)

                val isSideloaded =
                    installer == null || installer !in AccessibilityConstants.ALLOWED_INSTALLER

                if (!isSideloaded) return@runCatching false

                val hash = packageName.sha256()

                !(installer == null && hash in allowedServices)
            }
            .getOrDefault(false)

    object AccessibilityConstants {
        val ALLOWED_INSTALLER =
            arrayOf(
                "com.android.vending",
                "com.sec.android.app.samsungapps",
                "com.miui.supermarket",
                "com.oppo.market",
                "com.vivo.appstore",
                "com.huawei.appmarket",
                "com.miui.packageinstaller",
                "com.xiaomi.market",
                "com.xiaomi.mipicks",
                "com.google.android.feedback",
                "com.bbk.appstore",
                "com.heytap.market",
            )
    }
}

```

**File: `core/security/src/main/kotlin/id/co/bri/brimons/core/security/device/DeveloperChecker.kt`**
```kotlin
package id.co.bri.brimons.core.security.device

import android.app.Activity

class DeveloperChecker(private val activity: Activity) {
    fun isDeveloperModeEnabled(): Boolean {
        return android.provider.Settings.Secure.getInt(
            activity.contentResolver,
            android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
            0,
        ) != 0
    }
}

```

**File: `core/security/src/main/kotlin/id/co/bri/brimons/core/security/device/DeviceIdHelper.kt`**
```kotlin
package id.co.bri.brimons.core.security.device

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import id.co.bri.brimons.core.security.crypto.sha256
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.util.Locale

object DeviceIdHelper {
    @Throws(NoSuchAlgorithmException::class)
    fun setDeviceID(): String {
        val timeStamp = (System.currentTimeMillis() / 1000).toString()
        val randomDigits =
            String.format(Locale.getDefault(), "%06d", SecureRandom().nextInt(999999))
        val deviceImei = ""
        val deviceName = ""
        val deviceId = "$deviceImei$deviceName$randomDigits$timeStamp"
        return deviceId.sha256()
    }

    @SuppressLint("HardwareIds")
    fun getPersistentId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
}

```

**File: `core/security/src/main/kotlin/id/co/bri/brimons/core/security/device/EmulatorChecker.kt`**
```kotlin
package id.co.bri.brimons.core.security.device

import android.os.Build

class EmulatorChecker {

    fun isEmulator(): Boolean {
        return Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.startsWith("unknown") ||
            Build.MODEL.contains("google_sdk") ||
            Build.MODEL.lowercase().contains("droid4x") ||
            Build.MODEL.contains("Emulator") ||
            Build.MODEL.contains("Android SDK built for x86") ||
            Build.MANUFACTURER.contains("Genymotion") ||
            Build.HARDWARE.contains("goldfish") ||
            Build.HARDWARE.contains("ranchu") ||
            Build.HARDWARE.contains("vbox86") ||
            Build.PRODUCT.contains("sdk") ||
            Build.PRODUCT.contains("google_sdk") ||
            Build.PRODUCT.contains("sdk_google") ||
            Build.PRODUCT.contains("sdk_x86") ||
            Build.PRODUCT.contains("vbox86p") ||
            Build.PRODUCT.contains("emulator") ||
            Build.PRODUCT.contains("simulator") ||
            Build.BOARD.lowercase().contains("nox") ||
            Build.BOOTLOADER.lowercase().contains("nox") ||
            Build.HARDWARE.lowercase().contains("nox") ||
            Build.PRODUCT.lowercase().contains("nox") ||
            (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
    }
}

```

**File: `core/security/src/main/kotlin/id/co/bri/brimons/core/security/device/KeyboardChecker.kt`**
```kotlin
package id.co.bri.brimons.core.security.device

import android.content.Context
import android.provider.Settings
import android.view.inputmethod.InputMethodManager

class KeyboardChecker(
    private val context: Context,
    private val allowedKeyboards: List<String> = defaultAllowedKeyboards(),
) {

    fun isCustomKeyboardEnabled(): Boolean {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

        val defaultIme =
            Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?: return false

        val defaultKeyboard = imm.enabledInputMethodList.firstOrNull { it.id == defaultIme }

        val packageName = defaultKeyboard?.packageName ?: return false

        return allowedKeyboards.none { packageName.startsWith(it) }
    }

    companion object {
        private fun defaultAllowedKeyboards() =
            listOf(
                "com.android.inputmethod", // AOSP Latin IME
                "com.google.android.inputmethod", // Gboard
                "com.samsung.android.honeyboard", // Samsung Keyboard (new)
                "com.sec.android.inputmethod", // Samsung Keyboard (old)
                "com.miui.inputmethod", // Xiaomi
                "com.xiaomi.inputmethod", // Xiaomi
                "com.preff.kb.xm", // Poco
                "com.coloros.ime", // Oppo / Realme
                "com.nearme.ime", // Oppo (legacy)
                "com.bbk.inputmethod", // Vivo
                "com.huawei.ime", // Huawei (legacy)
                "com.baidu.input_huawei", // Huawei (Baidu IME)
                "com.ape.ime", // Infinix / Tecno (some models)
                "com.oneplus.ime", // OnePlus (legacy OxygenOS)
                "com.touchtype.swiftkey", // Swiftkey Keyboard (Microsoft)
                "com.facemoji.lite.transsion", // Infinix
                "com.facemoji.lite.vivo", // Vivo
                "com.google.android.inputmethod.latin", // Google / Pixel | Gboard
                "com.android.inputmethod.latin", // Android AOSP | AOSP Keyboard
                "com.samsung.android.smartkeyboardmanager", // Samsung | Smart Keyboard Manager
                "com.sec.android.inputmethod.iwnnn", // Samsung | Neural Keyboard
                "com.baidu.input_mi", // Xiaomi (China) | Baidu Input for MIUI
                "com.sohu.inputmethod.sogou.xiaomi", // Xiaomi (China) | Sogou Keyboard
                "com.iflytek.inputmethod.miui", // Xiaomi (China) | iFlytek Input
                "com.mi.android.globalminput", // Xiaomi (Global) | Mint Keyboard
                "com.huawei.ohos.inputmethod", // Huawei | Celia Keyboard
                "com.huawei.inputmethod.kika", // Huawei (Lama) | Huawei Swype
                "com.nuance.swype.emui", // Huawei (Lama) | Kika / Swype (EMUI)
                "com.hihonor.inputmethod", // Honor (China) | Honor Keyboard
                "com.baidu.input_oppo", // OPPO (China) | Baidu IME
                "com.sohu.inputmethod.sogou.oppo", // OPPO (China) | Sogou Keyboard
                "com.baidu.input_vivo", // Vivo (China) | Baidu IME
                "com.sohu.inputmethod.sogou.vivo", // Vivo (China) | Sogou Keyboard
                "com.vivo.inputmethod", // Vivo | Jovi Input Method
                "com.lge.ime", // LG (Legacy) | LG Keyboard
                "com.htc.sense.ime", // HTC (Legacy) | HTC Sense Input
            ) // list of default keyboards in various OEMs. Might want to move this to BE/Firebase
        // instead
    }
}

```

**File: `core/security/src/main/kotlin/id/co/bri/brimons/core/security/device/OverlayWatcher.kt`**
```kotlin
package id.co.bri.brimons.core.security.device

import android.app.Activity
import android.view.MotionEvent
import android.view.Window

class OverlayWatcher(private val activity: Activity) {

    private var wrappedCallback: Window.Callback? = null

    fun start(onOverlayDetected: () -> Unit) {
        val window = activity.window
        if (wrappedCallback != null) return
        window.decorView.filterTouchesWhenObscured = true
        val original = window.callback
        val wrapper =
            object : Window.Callback by original {
                override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                    val isObscured = event.flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED != 0
                    if (isObscured && !activity.isFinishing) {
                        onOverlayDetected()
                        return true
                    }
                    return original.dispatchTouchEvent(event)
                }
            }
        wrappedCallback = original
        window.callback = wrapper
    }

    fun stop() {
        val window = activity.window
        wrappedCallback?.let { original -> window.callback = original }
        wrappedCallback = null
    }
}

```

**File: `core/security/src/test/kotlin/id/co/bri/brimons/core/security/crypto/CryptoHelperTest.kt`**
```kotlin
package id.co.bri.brimons.core.security.crypto

import id.co.bri.brimons.core.security.AppConfig
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CryptoHelperTest {
    @Before
    fun setup() {
        mockkObject(AppConfig)
        every { AppConfig.getSecret() } returns "1234567890123456"
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    @Test
    fun `sha256 should return a 64-character hex hash`() {
        val hash = "password123".sha256()
        hash.length shouldBe 64
        hash shouldNotBe "password123"
    }

    @Test
    fun `md5 should return a 32-character hex hash`() {
        val hash = md5("password123")
        hash.length shouldBe 32
        hash shouldNotBe "password123"
    }

    @Test
    fun `getZeroPaddedData should zero-pad string and take the last 8 characters`() {
        getZeroPaddedData("123") shouldBe "00000123"
        getZeroPaddedData("123456789") shouldBe "23456789"
    }

    @Test
    fun `getPaddedSeqnum should pad zeroes to the left up to 12 and F to the right up to 16`() {
        getPaddedSeqnum("123") shouldBe "000000000123FFFF"
        getPaddedSeqnum("123456789012") shouldBe "123456789012FFFF"
        getPaddedSeqnum("") shouldBe ""
        getPaddedSeqnum(null) shouldBe ""
    }

    @Test
    fun `encryptAesCbcBase64 and decryptAesCbcBase64 should perform a valid round trip`() {
        val plainText = "SecretMessage"

        val encrypted = encryptAesCbcBase64(plainText)

        encrypted shouldNotBe plainText

        val decrypted = decryptAesCbcBase64(encrypted)

        decrypted shouldBe plainText
    }

    @Test
    fun `decryptUrlPath should gracefully handle null or empty strings`() {
        decryptUrlPath(null) shouldBe ""
        decryptUrlPath("") shouldBe ""
        decryptUrlPath("   ") shouldBe ""
    }

    @Test
    fun `encryptAesGcmBase64 and decryptAesGcm should perform a valid round trip`() {
        val plainText = "ConfidentialData"
        val iv = "123456789012"

        val encrypted = encryptAesGcmBase64(plainText, iv)

        encrypted shouldNotBe plainText

        val decrypted = decryptAesGcm(encrypted, iv)

        decrypted shouldBe plainText
    }

    @Test
    fun `encryptAesGcmRefNumNonce should produce a hexadecimal ciphertext`() {
        val plainText = "SensitiveInfo"
        val refNum = "REF123"
        val key = "1234567890123456"

        val encryptedHex = encryptAesGcmRefNumNonce(plainText, refNum, key)

        encryptedHex shouldNotBe ""
        encryptedHex shouldNotBe plainText
    }

    @Test
    fun `encodeBase64 and decodeBase64 should perform a valid round trip`() {
        val payload = "PayloadBytes".toByteArray()

        val encoded = encodeBase64(payload)
        val decoded = decodeBase64(encoded)

        String(decoded) shouldBe "PayloadBytes"
    }

    @Test
    fun `decryptAsBase64Compat should return empty string for null or empty input`() {
        decryptAsBase64Compat(null) shouldBe ""
        decryptAsBase64Compat("") shouldBe ""
    }

    @Test
    fun `decryptAsBase64Compat should decrypt a value encrypted with encryptAesCbcBase64`() {
        val plainText = "CompatibilityCheck"
        val encrypted = encryptAesCbcBase64(plainText)

        decryptAsBase64Compat(encrypted) shouldBe plainText
    }

    @Test
    fun `decryptAsBase64Compat should return empty string when the ciphertext is malformed`() {
        decryptAsBase64Compat("not-real-base64-!!!") shouldBe ""
    }

    @Test
    fun `sha256 is deterministic for the same input`() {
        "same".sha256() shouldBe "same".sha256()
    }

    @Test
    fun `sha256 produces different hashes for different inputs`() {
        "one".sha256() shouldNotBe "two".sha256()
    }

    @Test
    fun `md5 returns empty string on unknown-algorithm failure surface`() {
        // Sanity check
        md5("").length shouldBe 32
    }

    @Test
    fun `getPaddedSeqnum truncates long input to 16 characters via the padEnd`() {
        getPaddedSeqnum("1234567890123456").length shouldBe 16
    }
}

```

**File: `core/security/src/test/kotlin/id/co/bri/brimons/core/security/device/AccessibilityCheckerTest.kt`**
```kotlin
package id.co.bri.brimons.core.security.device

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.view.accessibility.AccessibilityManager
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AccessibilityCheckerTest {

    @Test
    fun `returns false when no accessibility services are enabled`() {
        val checker = AccessibilityChecker(newContext(services = emptyList()))

        checker.hasSuspiciousAccessibilityServices().shouldBeFalse()
    }

    @Test
    fun `returns false when the service was installed via an allowed store`() {
        val service = service(packageName = "com.example.legit", installer = "com.android.vending")
        val checker =
            AccessibilityChecker(
                newContext(services = listOf(service.first), pmSetup = service.second)
            )

        checker.hasSuspiciousAccessibilityServices().shouldBeFalse()
    }

    @Test
    fun `returns true when a sideloaded service is not in the allowlist`() {
        val service = service(packageName = "com.evil.app", installer = "com.evil.installer")
        val checker =
            AccessibilityChecker(
                newContext(services = listOf(service.first), pmSetup = service.second)
            )

        checker.hasSuspiciousAccessibilityServices().shouldBeTrue()
    }

    @Test
    fun `returns true when installer is null and the package hash is not in the allowlist`() {
        val service = service(packageName = "com.random.app", installer = null)
        val checker =
            AccessibilityChecker(
                newContext(services = listOf(service.first), pmSetup = service.second)
            )

        checker.hasSuspiciousAccessibilityServices().shouldBeTrue()
    }

    @Test
    fun `returns false when installer is null but the package hash IS in the allowlist`() {
        val service = service(packageName = "com.another.random", installer = null)
        val checker =
            AccessibilityChecker(
                newContext(services = listOf(service.first), pmSetup = service.second)
            )

        checker.hasSuspiciousAccessibilityServices().shouldBeTrue()
    }

    /**
     * Builds a mocked AccessibilityServiceInfo + a lambda to configure PackageManager with the
     * appropriate installer response for that package.
     */
    private fun service(
        packageName: String,
        installer: String?,
    ): Pair<AccessibilityServiceInfo, (PackageManager) -> Unit> {
        val serviceInfo = mockk<ServiceInfo>().apply { this.packageName = packageName }
        val resolveInfo = mockk<ResolveInfo>().apply { this.serviceInfo = serviceInfo }
        val info = mockk<AccessibilityServiceInfo>()
        every { info.resolveInfo } returns resolveInfo
        val pmSetup: (PackageManager) -> Unit = { pm ->
            every { pm.getInstallerPackageName(packageName) } returns installer
        }
        return info to pmSetup
    }

    private fun newContext(
        services: List<AccessibilityServiceInfo>,
        pmSetup: (PackageManager) -> Unit = {},
    ): Context {
        val manager = mockk<AccessibilityManager>()
        every { manager.getEnabledAccessibilityServiceList(any()) } returns services

        val packageManager = mockk<PackageManager>()
        pmSetup(packageManager)

        return mockk<Context>().apply {
            every { getSystemService(Context.ACCESSIBILITY_SERVICE) } returns manager
            every { this@apply.packageManager } returns packageManager
        }
    }
}

```

**File: `core/security/src/test/kotlin/id/co/bri/brimons/core/security/device/DeveloperCheckerTest.kt`**
```kotlin
package id.co.bri.brimons.core.security.device

import android.app.Activity
import android.provider.Settings
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeveloperCheckerTest {

    @Test
    fun `returns true when DEVELOPMENT_SETTINGS_ENABLED is set to 1`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        Settings.Secure.putInt(
            activity.contentResolver,
            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
            1,
        )

        DeveloperChecker(activity).isDeveloperModeEnabled().shouldBeTrue()
    }

    @Test
    fun `returns false when DEVELOPMENT_SETTINGS_ENABLED is set to 0`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        Settings.Secure.putInt(
            activity.contentResolver,
            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
            0,
        )

        DeveloperChecker(activity).isDeveloperModeEnabled().shouldBeFalse()
    }

    @Test
    fun `returns false when the setting is not present (default 0)`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        DeveloperChecker(activity).isDeveloperModeEnabled().shouldBeFalse()
    }
}

```

**File: `core/security/src/test/kotlin/id/co/bri/brimons/core/security/device/DeviceIdHelperTest.kt`**
```kotlin
package id.co.bri.brimons.core.security.device

import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeviceIdHelperTest {

    @Test
    fun `setDeviceID returns a 64-character lowercase hex string`() {
        val id = DeviceIdHelper.setDeviceID()

        id.length shouldBe 64
        id shouldMatch Regex("^[a-f0-9]+$")
    }

    @Test
    fun `setDeviceID produces distinct ids on successive calls`() {
        val ids = List(50) { DeviceIdHelper.setDeviceID() }

        ids.toSet().size shouldBe 50
    }

    @Test
    fun `getPersistentId returns the Settings ANDROID_ID`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ANDROID_ID, "android_id")

        DeviceIdHelper.getPersistentId(context) shouldBe "android_id"
    }

    @Test
    fun `getPersistentId returns different values for different set ANDROID_IDs`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ANDROID_ID, "id_one")
        val first = DeviceIdHelper.getPersistentId(context)

        Settings.Secure.putString(context.contentResolver, Settings.Secure.ANDROID_ID, "id_two")
        val second = DeviceIdHelper.getPersistentId(context)

        first shouldBe "id_one"
        second shouldBe "id_two"
        first shouldNotBe second
    }
}

```

**File: `core/security/src/test/kotlin/id/co/bri/brimons/core/security/device/EmulatorCheckerTest.kt`**
```kotlin
package id.co.bri.brimons.core.security.device

import android.os.Build
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.util.ReflectionHelpers.setStaticField

@RunWith(RobolectricTestRunner::class)
class EmulatorCheckerTest {

    private val fields =
        listOf(
            "FINGERPRINT",
            "MODEL",
            "MANUFACTURER",
            "HARDWARE",
            "PRODUCT",
            "BOARD",
            "BOOTLOADER",
            "BRAND",
            "DEVICE",
        )
    private lateinit var originals: Map<String, String>

    @Before
    fun setup() {
        originals = fields.associateWith { Build::class.java.getField(it).get(null) as String }
        setBuild(
            fingerprint = "google/coral/coral:14/UP1A/12345:user/release-keys",
            model = "Pixel 4",
            manufacturer = "Google",
            hardware = "coral",
            product = "coral",
            board = "coral",
            bootloader = "c2f2-1.10-11111111",
            brand = "google",
            device = "coral",
        )
    }

    @After
    fun teardown() {
        originals.forEach { (name, value) -> setStaticField(Build::class.java, name, value) }
    }

    @Test
    fun `real device profile is not detected as emulator`() {
        EmulatorChecker().isEmulator().shouldBeFalse()
    }

    @Test
    fun `FINGERPRINT starting with generic is detected`() {
        setStaticField(Build::class.java, "FINGERPRINT", "generic/x/y")
        EmulatorChecker().isEmulator().shouldBeTrue()
    }

    @Test
    fun `FINGERPRINT starting with unknown is detected`() {
        setStaticField(Build::class.java, "FINGERPRINT", "unknown/x/y")
        EmulatorChecker().isEmulator().shouldBeTrue()
    }

    @Test
    fun `MODEL containing google_sdk is detected`() {
        setStaticField(Build::class.java, "MODEL", "google_sdk phone")
        EmulatorChecker().isEmulator().shouldBeTrue()
    }

    @Test
    fun `MODEL droid4x lowercase match is detected`() {
        setStaticField(Build::class.java, "MODEL", "Droid4X device")
        EmulatorChecker().isEmulator().shouldBeTrue()
    }

    @Test
    fun `MODEL containing Emulator is detected`() {
        setStaticField(Build::class.java, "MODEL", "Some Emulator")
        EmulatorChecker().isEmulator().shouldBeTrue()
    }

    @Test
    fun `MANUFACTURER containing Genymotion is detected`() {
        setStaticField(Build::class.java, "MANUFACTURER", "Genymotion")
        EmulatorChecker().isEmulator().shouldBeTrue()
    }

    @Test
    fun `HARDWARE containing goldfish is detected`() {
        setStaticField(Build::class.java, "HARDWARE", "goldfish")
        EmulatorChecker().isEmulator().shouldBeTrue()
    }

    @Test
    fun `HARDWARE containing ranchu is detected`() {
        setStaticField(Build::class.java, "HARDWARE", "ranchu")
        EmulatorChecker().isEmulator().shouldBeTrue()
    }

    @Test
    fun `HARDWARE containing vbox86 is detected`() {
        setStaticField(Build::class.java, "HARDWARE", "vbox86")
        EmulatorChecker().isEmulator().shouldBeTrue()
    }

    @Test
    fun `PRODUCT containing sdk is detected`() {
        setStaticField(Build::class.java, "PRODUCT", "sdk_x86")
        EmulatorChecker().isEmulator().shouldBeTrue()
    }

    @Test
    fun `PRODUCT containing emulator is detected`() {
        setStaticField(Build::class.java, "PRODUCT", "emulator")
        EmulatorChecker().isEmulator().shouldBeTrue()
    }

    @Test
    fun `BOARD containing NOX case-insensitive is detected`() {
        setStaticField(Build::class.java, "BOARD", "NOXBoard")
        EmulatorChecker().isEmulator().shouldBeTrue()
    }

    @Test
    fun `BRAND generic and DEVICE generic together are detected`() {
        setStaticField(Build::class.java, "BRAND", "generic")
        setStaticField(Build::class.java, "DEVICE", "generic_x86")
        EmulatorChecker().isEmulator().shouldBeTrue()
    }

    @Test
    fun `BRAND generic alone without DEVICE generic is not detected`() {
        setStaticField(Build::class.java, "BRAND", "generic")
        EmulatorChecker().isEmulator().shouldBeFalse()
    }

    private fun setBuild(
        fingerprint: String,
        model: String,
        manufacturer: String,
        hardware: String,
        product: String,
        board: String,
        bootloader: String,
        brand: String,
        device: String,
    ) {
        setStaticField(Build::class.java, "FINGERPRINT", fingerprint)
        setStaticField(Build::class.java, "MODEL", model)
        setStaticField(Build::class.java, "MANUFACTURER", manufacturer)
        setStaticField(Build::class.java, "HARDWARE", hardware)
        setStaticField(Build::class.java, "PRODUCT", product)
        setStaticField(Build::class.java, "BOARD", board)
        setStaticField(Build::class.java, "BOOTLOADER", bootloader)
        setStaticField(Build::class.java, "BRAND", brand)
        setStaticField(Build::class.java, "DEVICE", device)
    }
}

```

**File: `core/service/build.gradle.kts`**
```kotlin
plugins { alias(libs.plugins.brimo.android.library) }

android {
    namespace = "id.co.bri.brimons.core.service"

    buildFeatures { buildConfig = true }
}

dependencies {
    implementation(projects.core.util)
    implementation(libs.play.services.auth.api.phone)
}

```

**File: `core/service/src/main/kotlin/id/co/bri/brimons/core/service/LocationProvider.kt`**
```kotlin
package id.co.bri.brimons.core.service

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import id.co.bri.brimons.core.util.permission.AppPermission
import id.co.bri.brimons.core.util.permission.isPermissionGranted

/**
 * Provides the last known device location.
 *
 * @return the last known location, or `null` if location is unavailable.
 */
class LocationProvider(context: Context) {
    private val appContext = context.applicationContext

    private val locationManager: LocationManager by lazy {
        appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    @SuppressLint("MissingPermission")
    fun getLocation(): Location? {
        if (!appContext.isPermissionGranted(AppPermission.LOCATION)) {
            return null
        }

        val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)

        val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

        if (!isGpsEnabled && !isNetworkEnabled) {
            return null
        }

        val networkLocation =
            if (isNetworkEnabled) {
                locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            } else {
                null
            }

        return networkLocation
            ?: if (isGpsEnabled) {
                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            } else {
                null
            }
    }
}

```

**File: `core/service/src/main/kotlin/id/co/bri/brimons/core/service/google/SmsRetrieverHelper.kt`**
```kotlin
package id.co.bri.brimons.core.service.google

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context.RECEIVER_EXPORTED
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.auth.api.phone.SmsRetriever
import id.co.bri.brimons.core.service.BuildConfig

/**
 * A helper object for using the Google Play Services [SmsRetrieverClient]. This class simplifies
 * starting the SMS retriever, handling the OTP, and managing the BroadcastReceiver lifecycle.
 */
object SmsRetrieverHelper {

    const val TAG = "SmsRetriever"

    private val otpResetHandler = Handler(Looper.getMainLooper())
    private val otpResetRunnable = Runnable { latestOtp = "" }

    /**
     * The last OTP received and saved. It is automatically cleared after 60 seconds. This property
     * is publicly readable but can only be set privately within this helper.
     */
    var latestOtp = ""
        private set

    /**
     * Starts the SMS Retriever API to listen for an SMS message containing the app's hash. The
     * listener will automatically time out after 5 minutes if no matching SMS is received.
     *
     * @param activity The current activity context needed to get the client.
     */
    fun start(activity: Activity) {
        val client = SmsRetriever.getClient(activity).startSmsRetriever()
        client.addOnSuccessListener {
            if (BuildConfig.DEBUG) Log.d(TAG, "Successfully running sms retriever")
        }

        client.addOnFailureListener {
            if (BuildConfig.DEBUG) Log.d(TAG, "Failed running sms retriever")
        }
    }

    /**
     * Saves the extracted OTP and sets a 60-second timer to clear it. If this function is called
     * again before the timer expires, the previous timer is canceled and a new one is started.
     *
     * @param otp The One-Time Password string to be saved.
     */
    fun saveLatestOtp(otp: String) {
        latestOtp = otp
        otpResetHandler.removeCallbacks(otpResetRunnable)
        otpResetHandler.postDelayed(otpResetRunnable, 60000)
    }

    /**
     * Registers the [BroadcastReceiver] to listen for the SMS retrieved by the API. This handles
     * API level differences for registering a broadcast receiver.
     *
     * @param activity The activity context used to register the receiver.
     * @param smsRetrieverReceiver The receiver instance to be registered.
     */
    fun registerReceiver(activity: Activity, smsRetrieverReceiver: BroadcastReceiver) {
        val intentFilter = IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(smsRetrieverReceiver, intentFilter, RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            activity.registerReceiver(smsRetrieverReceiver, intentFilter)
        }
    }

    /**
     * Unregisters a previously registered [BroadcastReceiver]. This should be called in the
     * appropriate lifecycle method (e.g., `onDestroy` or `onPause`) to prevent memory leaks.
     *
     * @param activity The activity context used to unregister the receiver.
     * @param smsRetrieverReceiver The receiver instance to be unregistered.
     */
    fun unregisterReceiver(activity: Activity, smsRetrieverReceiver: BroadcastReceiver) {
        activity.unregisterReceiver(smsRetrieverReceiver)
    }
}

```

**File: `core/testing/build.gradle.kts`**
```kotlin
plugins { alias(libs.plugins.brimo.android.library) }

android { namespace = "id.co.bri.brimons.core.testing" }

dependencies {
    api(libs.bundles.kotest)
    api(libs.mockk)
    api(libs.kotlinx.coroutines.test)
    api(libs.mockwebserver)
    api(libs.retrofit)
    api(libs.androidx.lifecycle.viewmodel.ktx)
    api(libs.androidx.test.core)

    implementation(libs.androidx.test.runner)

    api(projects.core.mvi)
    api(projects.core.network)
    api(projects.core.preference)
}

```

**File: `core/testing/src/main/kotlin/id/co/bri/brimons/core/testing/coroutine/MainDispatcherExtension.kt`**
```kotlin
package id.co.bri.brimons.core.testing.coroutine

import io.kotest.core.listeners.AfterEachListener
import io.kotest.core.listeners.BeforeEachListener
import io.kotest.core.test.TestCase
import io.kotest.core.test.TestResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

class MainDispatcherExtension(private val dispatcher: TestDispatcher = StandardTestDispatcher()) :
    BeforeEachListener, AfterEachListener {

    override suspend fun beforeEach(testCase: TestCase) {

        Dispatchers.setMain(dispatcher)
    }

    override suspend fun afterEach(testCase: TestCase, result: TestResult) {
        Dispatchers.resetMain()
    }
}

```

**File: `core/testing/src/main/kotlin/id/co/bri/brimons/core/testing/matcher/BaseStateMatchers.kt`**
```kotlin
package id.co.bri.brimons.core.testing.matcher

import id.co.bri.brimons.core.mvi.BaseState
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

fun <T> BaseState<T>.shouldBeLoading(): BaseState<T> {
    loading shouldBe true
    return this
}

fun <T> BaseState<T>.shouldBeSkeleton(): BaseState<T> {
    skeleton shouldBe true
    return this
}

fun <T> BaseState<T>.shouldBeProcessing(): BaseState<T> {
    process shouldBe true
    return this
}

fun <T : Any> BaseState<T>.shouldBeSuccess(assertions: T.() -> Unit = {}): T {
    loading shouldBe false
    error shouldBe null
    val data = data
    data shouldNotBe null
    data.assertions()
    return data
}

fun <T> BaseState<T>.shouldBeError(assertions: (Throwable) -> Unit = {}): Throwable {
    loading shouldBe false
    val err = error
    err shouldNotBe null
    assertions(err!!)
    return err
}

```

**File: `core/testing/src/main/kotlin/id/co/bri/brimons/core/testing/mvi/MviTestRobot.kt`**
```kotlin
package id.co.bri.brimons.core.testing.mvi

import androidx.lifecycle.ViewModel
import id.co.bri.brimons.core.mvi.BaseState
import id.co.bri.brimons.core.mvi.MviViewModel
import id.co.bri.brimons.core.mvi.interfaces.UiEffect
import id.co.bri.brimons.core.mvi.interfaces.UiEvent
import id.co.bri.brimons.core.mvi.interfaces.UiState
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Drives an [MviViewModel] in tests: send events, assert state, collect effects.
 *
 * ```kotlin
 * val robot = MviTestRobot(viewModel, testScope)
 * robot.sendAndAdvance(MyEvent.Load)
 * robot.state { title shouldBe "Hello" }
 * val effect = robot.sendAndAwaitEffect(MyEvent.Submit)
 * ```
 */
class MviTestRobot<E : UiEvent, S : UiState, F : UiEffect>(
    private val vm: MviViewModel<E, S, F>,
    private val testScope: TestScope,
) {

    fun send(event: E) {
        vm.onEvent(event)
    }

    fun advanceUntilIdle() {
        testScope.advanceUntilIdle()
    }

    fun sendAndAdvance(event: E) {
        vm.onEvent(event)
        testScope.advanceUntilIdle()
    }

    fun state(assertions: S.() -> Unit) {
        vm.state.value.data.assertions()
    }

    fun baseState(assertions: BaseState<S>.() -> Unit) {
        vm.state.value.assertions()
    }

    suspend fun awaitEffect(timeoutMs: Long = DEFAULT_TIMEOUT_MS): F? =
        withTimeoutOrNull(timeoutMs.milliseconds) { vm.effect.first() }

    /**
     * Subscribes to the effect channel before the event is sent, avoiding a race condition. Use
     * when you need to assert the effect yourself after calling [send].
     */
    fun prepareEffect(): Deferred<F> =
        testScope.backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { vm.effect.first() }

    /**
     * Throws [kotlinx.coroutines.TimeoutCancellationException] if no effect is emitted within
     * [timeoutMs].
     */
    suspend fun sendAndAwaitEffect(event: E, timeoutMs: Long = DEFAULT_TIMEOUT_MS): F {
        val deferredEffect = prepareEffect()
        vm.onEvent(event)
        testScope.advanceUntilIdle()
        return withTimeout(timeoutMs.milliseconds) { deferredEffect.await() }
    }

    /** Fails the test if any effect is emitted within [timeoutMs]. */
    suspend fun sendAndExpectNoEffect(event: E, timeoutMs: Long = NO_EFFECT_TIMEOUT_MS) {
        val deferredEffect =
            testScope.backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(timeoutMs.milliseconds) { vm.effect.first() }
            }
        vm.onEvent(event)
        testScope.advanceUntilIdle()
        val result = runCatching { deferredEffect.await() }
        check(result.isFailure) {
            "Expected no effect, but effect was emitted: ${result.getOrNull()}"
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 1_000L
        const val NO_EFFECT_TIMEOUT_MS = 300L
    }
}

fun ViewModel.clearForTest() {
    runCatching {
        ViewModel::class.java.getDeclaredMethod("clear").apply { isAccessible = true }.invoke(this)
    }
}

```

**File: `core/testing/src/main/kotlin/id/co/bri/brimons/core/testing/network/FakeApiInterface.kt`**
```kotlin
package id.co.bri.brimons.core.testing.network

import id.co.bri.brimons.core.network.api.ApiInterface
import retrofit2.Response

class FakeApiInterface : ApiInterface {

    var lastMethod: String? = null
    var lastUrl: String? = null
    var lastHeaders: Map<String, String>? = null
    var lastRequestBody: String? = null
    var nextResponse: Response<String> = Response.success("success")

    override suspend fun post(
        url: String,
        requestData: String,
        headers: Map<String, String>,
    ): Response<String> {
        lastMethod = "POST"
        lastUrl = url
        lastHeaders = headers
        lastRequestBody = requestData
        return nextResponse
    }

    override suspend fun get(url: String, headers: Map<String, String>): Response<String> {
        lastMethod = "GET"
        lastUrl = url
        lastHeaders = headers
        return nextResponse
    }

    override suspend fun getErangelRequest(
        url: String,
        requestData: String,
        deviceId: String,
        device: String,
        randomKey: String,
        id: String,
        key: String,
    ): Response<String> {
        lastMethod = "GET_ERANGEL"
        lastUrl = url
        lastRequestBody = requestData
        return nextResponse
    }
}

```

**File: `core/testing/src/main/kotlin/id/co/bri/brimons/core/testing/network/FakeMinioInterface.kt`**
```kotlin
package id.co.bri.brimons.core.testing.network

import id.co.bri.brimons.core.network.api.MinioInterface
import okhttp3.RequestBody
import retrofit2.Response

class FakeMinioInterface : MinioInterface {

    var lastUrl: String? = null
    var lastFile: RequestBody? = null
    var nextResponse: Response<Unit> = Response.success(Unit)

    override suspend fun putMinioData(url: String, file: RequestBody): Response<Unit> {
        lastUrl = url
        lastFile = file
        return nextResponse
    }
}

```

**File: `core/testing/src/main/kotlin/id/co/bri/brimons/core/testing/network/MockWebServerExtension.kt`**
```kotlin
package id.co.bri.brimons.core.testing.network

import io.kotest.core.listeners.AfterEachListener
import io.kotest.core.listeners.BeforeEachListener
import io.kotest.core.test.TestCase
import io.kotest.core.test.TestResult
import okhttp3.mockwebserver.MockWebServer

class MockWebServerExtension : BeforeEachListener, AfterEachListener {

    lateinit var server: MockWebServer
        private set

    override suspend fun beforeEach(testCase: TestCase) {
        server = MockWebServer()
        server.start()
    }

    override suspend fun afterEach(testCase: TestCase, result: TestResult) {
        server.shutdown()
    }
}

```

**File: `core/testing/src/main/kotlin/id/co/bri/brimons/core/testing/preference/PreferencesMock.kt`**
```kotlin
package id.co.bri.brimons.core.testing.preference

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Base64
import androidx.annotation.RequiresApi
import id.co.bri.brimons.core.preference.impl.SharedPrefHelper
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.util.Base64 as JavaBase64

fun newPreferenceContext(): Context {
    val sharedPreferences = InMemorySharedPreferences()

    return mockk<Context>(relaxed = true) {
        every { getSharedPreferences(SharedPrefHelper.FILE_NAME, Context.MODE_PRIVATE) } returns
            sharedPreferences

        every { getSharedPreferences(any(), Context.MODE_PRIVATE) } returns sharedPreferences
    }
}

private class InMemorySharedPreferences : SharedPreferences {

    private val data = linkedMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = data.toMutableMap()

    override fun getString(key: String?, defValue: String?): String? =
        data[key] as? String ?: defValue

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
        @Suppress("UNCHECKED_CAST")
        return data[key] as? MutableSet<String> ?: defValues
    }

    override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        data[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = data.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    private inner class Editor : SharedPreferences.Editor {

        private val pending = linkedMapOf<String, Any?>()
        private val removedKeys = mutableSetOf<String>()
        private var shouldClear = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }

        override fun putStringSet(
            key: String?,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor {
            if (key != null) pending[key] = values
            return this
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }

        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) removedKeys += key
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            shouldClear = true
            return this
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (shouldClear) data.clear()
            removedKeys.forEach { data.remove(it) }
            pending.forEach { (key, value) ->
                if (value == null) data.remove(key) else data[key] = value
            }
            pending.clear()
            removedKeys.clear()
            shouldClear = false
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
fun mockAndroidBase64() {
    mockkStatic(Base64::class)

    every { Base64.encodeToString(any<ByteArray>(), any()) } answers
        {
            JavaBase64.getEncoder().encodeToString(firstArg<ByteArray>())
        }
    every { Base64.decode(any<String>(), any()) } answers
        {
            JavaBase64.getDecoder().decode(firstArg<String>())
        }
    every { Base64.encode(any<ByteArray>(), any()) } answers
        {
            JavaBase64.getEncoder().encode(firstArg<ByteArray>())
        }
    every { Base64.decode(any<ByteArray>(), any()) } answers
        {
            JavaBase64.getDecoder().decode(firstArg<ByteArray>())
        }
}

fun unmockAndroidBase64() {
    unmockkStatic(Base64::class)
}

```

**File: `core/ui/build.gradle.kts`**
```kotlin
plugins {
    alias(libs.plugins.brimo.android.library.compose)
    alias(libs.plugins.brimo.android.hilt)
}

android { namespace = "id.co.bri.brimons.core.ui" }

dependencies {
    implementation(projects.uikit)
    implementation(projects.core.util)
    implementation(projects.core.model)
    implementation(projects.core.network)
    implementation(projects.core.security)
    implementation(libs.okio)
    // ComposeBaseActivity drives uikit's bottom sheets, which expose Material types.
    implementation(libs.android.material)

    testImplementation(libs.bundles.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
}

```

**File: `core/ui/src/main/kotlin/id/co/bri/brimons/core/ui/component/DownloadHelper.kt`**
```kotlin
package id.co.bri.brimons.core.ui.component

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import id.co.bri.brimons.core.ui.permission.rememberPermissionState
import id.co.bri.brimons.core.util.permission.AppPermission
import id.co.bri.brimons.core.util.permission.PermissionStatus
import id.co.bri.brimons.uikit.R
import id.co.bri.brimons.uikit.compose.internal.composableToBitmap
import id.co.bri.brimons.uikit.compose.internal.screenWidth
import id.co.bri.brimons.uikit.compose.templates.BaseBottomSheet
import id.co.bri.brimons.uikit.compose.theme.QitaTheme
import id.co.bri.brimons.uikit.util.saveToDisk
import id.co.bri.brimons.uikit.util.shareBitmap
import kotlinx.coroutines.launch

/**
 * Renders [content] off-screen, saves it as an image, and optionally shares it — asking for storage
 * access first on the API levels that still require it.
 */
@Composable
fun DownloadHelper(
    content: @Composable () -> Unit = {},
    onDownload: (() -> Unit) -> Unit = {},
    onShare: (() -> Unit) -> Unit = {},
    onSuccess: () -> Unit = {},
    onError: () -> Unit = {},
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    // Global
    val screenWidth = screenWidth()

    // Storage Bottom Sheet
    var storageBottomSheet by rememberSaveable { mutableStateOf(false) }
    var storageAction by remember { mutableStateOf({}) }

    BaseBottomSheet(
        showBottomSheet = storageBottomSheet,
        onShowBottomSheet = { storageBottomSheet = it },
        title = stringResource(R.string.storage_bottomsheet_title),
        description = stringResource(R.string.storage_bottomsheet_description),
        primaryText = stringResource(R.string.storage_bottomsheet_button),
        onPrimary = { storageAction() },
    )

    // Permission — resolves to Granted without a dialog on Q and above.
    val storagePermission = rememberPermissionState(AppPermission.WRITE_STORAGE)

    fun shareBitmapFromComposable(isShare: Boolean) {
        when (storagePermission.status) {
            PermissionStatus.Granted -> {
                if (storageBottomSheet) storageBottomSheet = false

                coroutineScope.launch {
                    try {
                        val bitmap =
                            composableToBitmap(
                                mainScope = coroutineScope,
                                activity = context as Activity,
                                width = screenWidth,
                                density = density,
                            ) {
                                QitaTheme { content() }
                            }
                        val uri = bitmap.saveToDisk(context)
                        if (isShare) {
                            shareBitmap(context, uri)
                        } else {
                            onSuccess()
                        }
                    } catch (_: Throwable) {
                        if (!isShare) {
                            onError()
                        }
                    }
                }
            }

            PermissionStatus.Denied -> {
                storageAction = { storagePermission.launch() }
                storageBottomSheet = true
            }

            PermissionStatus.PermanentlyDenied -> {
                storageAction = { storagePermission.openAppSettings() }
                storageBottomSheet = true
            }
        }
    }

    onDownload { shareBitmapFromComposable(isShare = false) }

    onShare { shareBitmapFromComposable(isShare = true) }
}

```

**File: `core/ui/src/main/kotlin/id/co/bri/brimons/core/ui/core/base/BaseActivityOverlays.kt`**
```kotlin
package id.co.bri.brimons.core.ui.core.base

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import id.co.bri.brimons.core.ui.core.security.SecurityThreat
import id.co.bri.brimons.core.ui.core.security.Type
import id.co.bri.brimons.core.ui.feature.base.ui.DynamicErrorBottomSheet
import id.co.bri.brimons.uikit.R
import id.co.bri.brimons.uikit.compose.molecules.SnackbarType
import id.co.bri.brimons.uikit.compose.molecules.TopSnackbarCustom
import id.co.bri.brimons.uikit.compose.templates.BaseBottomSheet

@Composable
internal fun BaseActivityContent(
    state: BaseOverlayState,
    onSnackbarDismiss: () -> Unit,
    onInternetLostDismiss: () -> Unit,
    onSecurityDismiss: () -> Unit,
    onSecurityPrimary: (Type) -> Unit,
    onGlobalErrorPrimary: () -> Unit,
    onGlobalErrorDismiss: () -> Unit,
    onDynamicErrorDismiss: () -> Unit,
    onDynamicErrorAction: (String) -> Unit,
    content: @Composable () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        content()

        BaseActivityOverlays(
            state = state,
            onSnackbarDismiss = onSnackbarDismiss,
            onInternetLostDismiss = onInternetLostDismiss,
            onSecurityDismiss = onSecurityDismiss,
            onSecurityPrimary = onSecurityPrimary,
            onGlobalErrorPrimary = onGlobalErrorPrimary,
            onGlobalErrorDismiss = onGlobalErrorDismiss,
            onDynamicErrorDismiss = onDynamicErrorDismiss,
            onDynamicErrorAction = onDynamicErrorAction,
        )
    }
}

@Composable
private fun BaseActivityOverlays(
    state: BaseOverlayState,
    onSnackbarDismiss: () -> Unit,
    onInternetLostDismiss: () -> Unit,
    onSecurityDismiss: () -> Unit,
    onSecurityPrimary: (Type) -> Unit,
    onGlobalErrorPrimary: () -> Unit,
    onGlobalErrorDismiss: () -> Unit,
    onDynamicErrorDismiss: () -> Unit,
    onDynamicErrorAction: (String) -> Unit,
) {
    state.snackbarMessage?.let { message ->
        TopSnackbarCustom(
            snackbarMessage = message,
            snackbarType = SnackbarType.ERROR,
            onDismiss = onSnackbarDismiss,
        )
    }

    InternetLostBottomSheet(show = state.isInternetLost, onDismiss = onInternetLostDismiss)

    SecurityThreatBottomSheet(
        state = state.securitySheet,
        onDismiss = onSecurityDismiss,
        onPrimary = onSecurityPrimary,
    )

    GlobalErrorBottomSheet(
        state = state.errorSheet,
        onPrimary = onGlobalErrorPrimary,
        onDismiss = onGlobalErrorDismiss,
    )

    state.dynamicErrorSheet?.let { sheet ->
        DynamicErrorBottomSheet(
            error = sheet.event.exception,
            onDismiss = onDynamicErrorDismiss,
            onAction = onDynamicErrorAction,
        )
    }
}

@Composable
private fun InternetLostBottomSheet(show: Boolean, onDismiss: () -> Unit) {
    BaseBottomSheet(
        showBottomSheet = show,
        onShowBottomSheet = { visible -> if (!visible) onDismiss() },
        image = R.drawable.icon_illustrations_failed_sad_3d,
        title = stringResource(R.string.error_bottomsheet_no_internet_title),
        description = stringResource(R.string.error_bottomsheet_no_internet_description),
        primaryText = stringResource(R.string.error_bottomsheet_no_internet_button),
        isDismissable = false,
        onPrimary = onDismiss,
    )
}

@Composable
private fun SecurityThreatBottomSheet(
    state: SecuritySheetState?,
    onDismiss: () -> Unit,
    onPrimary: (Type) -> Unit,
) {
    val threat = state?.threat ?: return

    BaseBottomSheet(
        showBottomSheet = true,
        onShowBottomSheet = { visible -> if (!visible) onDismiss() },
        image = R.drawable.icon_illustrations_denied_3d,
        title = threat.titleRes?.let { stringResource(it) }.orEmpty(),
        description = threat.securityDescription(state.rootMethods),
        primaryText =
            if (threat.type == Type.GO_TO_ACCESSIBILITY || threat.type == Type.GO_TO_APP_SETTINGS) {
                stringResource(R.string.txt_error_open_app_options)
            } else {
                stringResource(R.string.mengerti)
            },
        isDismissable = false,
        onPrimary = { onPrimary(threat.type) },
    )
}

@Composable
private fun GlobalErrorBottomSheet(
    state: GlobalErrorSheetState?,
    onPrimary: () -> Unit,
    onDismiss: () -> Unit,
) {
    val uiModel = state?.uiModel

    BaseBottomSheet(
        showBottomSheet = state != null && uiModel != null,
        image = uiModel?.imageRes ?: R.drawable.icon_illustrations_failed_sad_3d,
        url = uiModel?.imageUrl.orEmpty(),
        title = uiModel?.title.orEmpty(),
        description = uiModel?.description.orEmpty(),
        primaryText = uiModel?.buttonText.orEmpty(),
        isDismissable = uiModel?.dismissable == true,
        onPrimary = onPrimary,
        onDismiss = onDismiss,
    )
}

@Composable
private fun SecurityThreat.securityDescription(rootMethods: List<String>): String {
    val baseDescription = stringResource(descriptionRes)

    return if (rootMethods.isNotEmpty()) {
        buildString {
            append(baseDescription)
            append('\n')
            append(rootMethods.joinToString())
        }
    } else {
        baseDescription
    }
}

```

**File: `core/ui/src/main/kotlin/id/co/bri/brimons/core/ui/core/base/BaseComposeActivity.kt`**
```kotlin
package id.co.bri.brimons.core.ui.core.base

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import id.co.bri.brimons.core.model.exception.dynamic.DynamicErrorActionButton
import id.co.bri.brimons.core.model.exception.event.AppEvent
import id.co.bri.brimons.core.model.exception.event.GlobalEventDispatcher
import id.co.bri.brimons.core.network.connectivity.INetworkObserver
import id.co.bri.brimons.core.network.connectivity.NetworkState
import id.co.bri.brimons.core.ui.core.security.SecurityManager
import id.co.bri.brimons.core.ui.core.security.SecurityThreat
import id.co.bri.brimons.core.ui.dependency.ComposeApi
import id.co.bri.brimons.core.ui.feature.base.mapper.toErrorBottomSheetUiModel
import id.co.bri.brimons.core.util.logcat
import id.co.bri.brimons.uikit.BuildConfig
import id.co.bri.brimons.uikit.extension.withFixedFontScale
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@AndroidEntryPoint
abstract class BaseComposeActivity : ComponentActivity() {

    @Inject lateinit var networkObserver: INetworkObserver

    @Inject lateinit var composeApi: ComposeApi

    private val securityManager by
        lazy(LazyThreadSafetyMode.NONE) { SecurityManager(activity = this@BaseComposeActivity) }

    private var overlayState by mutableStateOf(BaseOverlayState())

    private val shouldRunRuntimeGuard: Boolean
        get() = !BuildConfig.DEBUG

    @Composable protected abstract fun BaseScreen()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableBaseEdgeToEdge()
        observeGlobalEvents()
        observeNetworkState()

        setContent {
            BaseActivityContent(
                state = overlayState,
                onSnackbarDismiss = ::dismissSnackbar,
                onInternetLostDismiss = ::dismissInternetLost,
                onSecurityDismiss = ::dismissSecuritySheet,
                onSecurityPrimary = { type -> handleSecurityAction(type = type) },
                onGlobalErrorPrimary = ::handleCurrentGlobalErrorAction,
                onGlobalErrorDismiss = ::dismissGlobalErrorIfAllowed,
                onDynamicErrorDismiss = ::dismissDynamicErrorIfAllowed,
                onDynamicErrorAction = ::handleDynamicErrorAction,
            ) {
                BaseScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()

        if (shouldRunRuntimeGuard) {
            configureSecureWindow()
            runInitialSecurityChecks()
        }
    }

    override fun onStart() {
        super.onStart()
        networkObserver.start()
        if (shouldRunRuntimeGuard) {
            securityManager.startMonitoring(::emitThreat)
        }
    }

    override fun onStop() {
        if (shouldRunRuntimeGuard) {
            securityManager.stopMonitoring()
        }
        networkObserver.stop()

        super.onStop()
    }

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(newBase?.withFixedFontScale())
    }

    private fun observeNetworkState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                networkObserver.state
                    .map { state -> state == NetworkState.Lost }
                    .collect(::setInternetLost)
            }
        }
    }

    private fun observeGlobalEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                GlobalEventDispatcher.events.collect { event ->
                    try {
                        handleGlobalEvent(event)
                    } finally {
                        GlobalEventDispatcher.clearReplay()
                    }
                }
            }
        }
    }

    private fun handleGlobalEvent(event: AppEvent) {
        when (event) {
            is AppEvent.ChangeDevice -> {
                composeApi.onAnotherDeviceDetected(this)
            }
            is AppEvent.NetworkError -> {
                if (!overlayState.isInternetLost) showGlobalErrorSheet(event)
            }
            is AppEvent.DeleteUser -> {
                composeApi.onDeleteUser(this, event.error)
            }
            is AppEvent.DynamicError.Snackbar -> {
                updateOverlayState { copy(snackbarMessage = event.message) }
            }
            is AppEvent.DynamicError.BottomSheet -> {
                updateOverlayState { copy(dynamicErrorSheet = DynamicErrorSheetState(event)) }
            }
            else -> showGlobalErrorSheet(event)
        }
    }

    private fun showGlobalErrorSheet(event: AppEvent) {
        updateOverlayState {
            copy(
                errorSheet =
                    GlobalErrorSheetState(
                        uiModel = event.toErrorBottomSheetUiModel(this@BaseComposeActivity),
                        event = event,
                    )
            )
        }
    }

    private fun handleCurrentGlobalErrorAction() {
        val event = overlayState.errorSheet?.event ?: return

        logcat { "click global event: $event" }

        when (event) {
            is AppEvent.LimitHit,
            is AppEvent.DynamicError -> Unit

            is AppEvent.AlertFinish,
            is AppEvent.LimitException,
            is AppEvent.Maintenance -> {
                finish()
            }

            is AppEvent.SessionEnd -> {
                composeApi.onSession(this)
            }

            is AppEvent.GeneralError -> {
                event.retry?.invoke()
            }

            is AppEvent.NetworkError -> {
                event.retry?.invoke()
            }

            is AppEvent.DeleteUser -> {
                composeApi.onDeleteUser(this, event.error)
            }

            is AppEvent.ChangeDevice -> {
                composeApi.onAnotherDeviceDetected(this)
            }
        }

        dismissGlobalError()
    }

    private fun handleDynamicErrorAction(action: String) {
        val event = overlayState.dynamicErrorSheet?.event ?: return
        when (action) {
            DynamicErrorActionButton.BACK_TO_HOME -> finish()
            DynamicErrorActionButton.BACK_TO_LOGIN -> composeApi.onSession(this)
            DynamicErrorActionButton.CLOSE -> dismissDynamicErrorIfAllowed()
            DynamicErrorActionButton.REFRESH -> event.retry?.invoke()
            else -> event.onAction?.invoke(action)
        }

        dismissDynamicError()
    }

    private fun runInitialSecurityChecks() {
        lifecycleScope.launch {
            securityManager.runStaticChecks()?.let { threat ->
                emitThreat(threat)
                return@launch
            }

            securityManager.checkCallState(onThreat = ::emitThreat)
        }
    }

    private fun emitThreat(threat: SecurityThreat, rootMethods: List<String> = emptyList()) {
        if (overlayState.securitySheet != null) return

        updateOverlayState {
            copy(securitySheet = SecuritySheetState(threat = threat, rootMethods = rootMethods))
        }
    }

    private fun setInternetLost(isLost: Boolean) {
        if (overlayState.isInternetLost == isLost) return

        updateOverlayState {
            copy(
                isInternetLost = isLost,
                errorSheet =
                    if (isLost && errorSheet?.event is AppEvent.NetworkError) null else errorSheet,
            )
        }
    }

    private fun dismissInternetLost() {
        updateOverlayState { copy(isInternetLost = false) }
        lifecycleScope.launch {
            delay(DEFAULT_DELAY_MS.milliseconds)
            if (networkObserver.state.value == NetworkState.Lost) {
                updateOverlayState {
                    copy(
                        isInternetLost = true,
                        errorSheet =
                            if (errorSheet?.event is AppEvent.NetworkError) null else errorSheet,
                    )
                }
            }
        }
    }

    private fun dismissSnackbar() {
        updateOverlayState { copy(snackbarMessage = null) }
    }

    private fun dismissSecuritySheet() {
        updateOverlayState { copy(securitySheet = null) }
    }

    private fun dismissGlobalErrorIfAllowed() {
        val errorSheet = overlayState.errorSheet ?: return
        val uiModel = errorSheet.uiModel ?: return
        if (!uiModel.dismissable) return

        (errorSheet.event as? AppEvent.GeneralError)?.onDismiss?.invoke()
        dismissGlobalError()
    }

    private fun dismissDynamicErrorIfAllowed() {
        val dynamicErrorSheet = overlayState.dynamicErrorSheet ?: return
        if (!dynamicErrorSheet.event.exception.dismissable) return

        dynamicErrorSheet.event.onDismiss?.invoke()
        dismissDynamicError()
    }

    private fun dismissGlobalError() {
        updateOverlayState { copy(errorSheet = null) }
    }

    private fun dismissDynamicError() {
        updateOverlayState { copy(dynamicErrorSheet = null) }
    }

    private inline fun updateOverlayState(reducer: BaseOverlayState.() -> BaseOverlayState) {
        overlayState = overlayState.reducer()
    }

    companion object {
        const val DEFAULT_DELAY_MS = 400L
    }
}

```

**File: `core/ui/src/main/kotlin/id/co/bri/brimons/core/ui/core/base/BaseComposeActivitySecurityActions.kt`**
```kotlin
package id.co.bri.brimons.core.ui.core.base

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import id.co.bri.brimons.core.ui.core.security.Type

fun ComponentActivity.handleSecurityAction(type: Type) {
    when (type) {
        Type.BLOCKING_EXIT -> {
            finishAffinity()
        }

        Type.GO_TO_DEVELOPER_SETTINGS -> {
            openDeveloperSettings()
            finishAffinity()
        }

        Type.OPEN_KEYBOARD_PICKER -> {
            showKeyboardPicker()
        }

        Type.GO_TO_APP_SETTINGS -> {
            openAppSettings()
            finishAffinity()
        }

        Type.GO_TO_ACCESSIBILITY -> {
            goToAccessibilitySettings()
        }
    }
}

private fun ComponentActivity.openDeveloperSettings() {
    val action =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
        } else {
            Settings.ACTION_DEVICE_INFO_SETTINGS
        }

    runCatching { startActivity(Intent(action).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) }
}

fun ComponentActivity.goToAccessibilitySettings() {
    runCatching {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
    }
    finishAffinity()
}

fun ComponentActivity.openAppSettings() {
    runCatching {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        )
    }
}

fun ComponentActivity.showKeyboardPicker() {
    runCatching {
        Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(this)
        }
    }
    finishAffinity()
}

```

**File: `core/ui/src/main/kotlin/id/co/bri/brimons/core/ui/core/base/BaseComposeActivityWindow.kt`**
```kotlin
package id.co.bri.brimons.core.ui.core.base

import android.graphics.Color
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.WindowCompat

internal fun ComponentActivity.enableBaseEdgeToEdge() {
    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)

    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        navigationBarStyle = SystemBarStyle.light(Color.WHITE, Color.WHITE),
    )
}

internal fun ComponentActivity.configureSecureWindow() {
    WindowCompat.setDecorFitsSystemWindows(window, false)

    window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

    window.decorView.filterTouchesWhenObscured = true
}

```

**File: `core/ui/src/main/kotlin/id/co/bri/brimons/core/ui/core/base/BaseOverlayState.kt`**
```kotlin
package id.co.bri.brimons.core.ui.core.base

import id.co.bri.brimons.core.model.exception.event.AppEvent
import id.co.bri.brimons.core.ui.core.security.SecurityThreat
import id.co.bri.brimons.uikit.compose.templates.ErrorBottomSheetUiModel

internal data class BaseOverlayState(
    val isInternetLost: Boolean = false,
    val snackbarMessage: String? = null,
    val securitySheet: SecuritySheetState? = null,
    val errorSheet: GlobalErrorSheetState? = null,
    val dynamicErrorSheet: DynamicErrorSheetState? = null,
)

internal data class GlobalErrorSheetState(
    val uiModel: ErrorBottomSheetUiModel?,
    val event: AppEvent,
)

internal data class DynamicErrorSheetState(val event: AppEvent.DynamicError.BottomSheet)

internal data class SecuritySheetState(
    val threat: SecurityThreat,
    val rootMethods: List<String> = emptyList(),
)

```

**File: `core/ui/src/main/kotlin/id/co/bri/brimons/core/ui/core/network/mapper/MapperErrorResponse.kt`**
```kotlin
package id.co.bri.brimons.core.ui.core.network.mapper

import android.content.Context
import id.co.bri.brimons.core.model.exception.GeneralApiException
import id.co.bri.brimons.core.model.response.ResponseCode
import id.co.bri.brimons.core.network.legacy.ACCOUNT_NOT_AVAILABLE
import id.co.bri.brimons.core.network.legacy.CODE_LIMIT_HIT
import id.co.bri.brimons.core.network.legacy.CODE_LIMIT_TRANSACTION
import id.co.bri.brimons.core.network.legacy.CODE_MAINTENANCE
import id.co.bri.brimons.core.network.legacy.CODE_PIN_ERROR
import id.co.bri.brimons.core.network.legacy.CODE_SESSION_EXPIRED
import id.co.bri.brimons.core.network.legacy.CODE_TRANSACTION_EXPIRED
import id.co.bri.brimons.core.network.legacy.CODE_TRANSACTION_FAILED
import id.co.bri.brimons.core.network.legacy.CODE_TRANSACTION_INSUFFICIENT
import id.co.bri.brimons.core.network.legacy.MessageException
import id.co.bri.brimons.uikit.R
import id.co.bri.brimons.uikit.compose.templates.ErrorBottomSheetUiModel
import okio.IOException

internal fun Throwable.toErrorBottomSheetUiModel(
    context: Context,
    isPay: Boolean,
): ErrorBottomSheetUiModel {
    return when (this) {
        is GeneralApiException -> mapDataException(context, isPay)
        is MessageException -> mapMessageException(context, isPay)
        is IOException ->
            ErrorBottomSheetUiModel(
                title = context.getString(R.string.error_bottomsheet_no_internet_title),
                description = context.getString(R.string.error_bottomsheet_no_internet_description),
                buttonText = context.getString(R.string.error_bottomsheet_no_internet_button),
                imageRes = R.drawable.icon_illustrations_failed_sad_3d,
                dismissable = false,
            )

        else -> genericError(context)
    }
}

private fun GeneralApiException.mapDataException(
    context: Context,
    isPay: Boolean,
): ErrorBottomSheetUiModel {
    return when (code) {
        CODE_SESSION_EXPIRED ->
            ErrorBottomSheetUiModel(
                title.takeIf { !it.isNullOrBlank() }
                    ?: context.getString(R.string.error_bottomsheet_session_expired_title),
                description =
                    description.ifEmpty {
                        context.getString(R.string.error_bottomsheet_session_expired_description)
                    },
                buttonText = context.getString(R.string.error_bottomsheet_session_expired_button),
                imageRes = R.drawable.icon_illustrations_pending_3d,
                dismissable = false,
            )

        CODE_LIMIT_TRANSACTION ->
            ErrorBottomSheetUiModel(
                title =
                    title.takeIf { !it.isNullOrBlank() }
                        ?: context.getString(R.string.error_bottomsheet_daily_limit_title),
                description =
                    description.ifEmpty {
                        context.getString(R.string.error_bottomsheet_daily_limit_description)
                    },
                buttonText = context.getString(R.string.error_bottomsheet_daily_limit_button),
                imageRes = R.drawable.icon_illustrations_warning_3d,
                dismissable = false,
            )

        CODE_LIMIT_HIT ->
            ErrorBottomSheetUiModel(
                title =
                    title.takeIf { !it.isNullOrBlank() }
                        ?: context.getString(R.string.error_bottomsheet_generic_title),
                description =
                    description.ifEmpty {
                        context.getString(R.string.error_bottomsheet_generic_description)
                    },
                buttonText = context.getString(R.string.error_bottomsheet_understand_button),
                imageRes = R.drawable.icon_illustrations_failed_sad_3d,
                imageUrl = image.orEmpty(),
            )

        CODE_TRANSACTION_FAILED,
        CODE_TRANSACTION_INSUFFICIENT -> transactionFailed(context, image.orEmpty())

        CODE_TRANSACTION_EXPIRED ->
            ErrorBottomSheetUiModel(
                title =
                    title.takeIf { !it.isNullOrBlank() }
                        ?: context.getString(R.string.error_bottomsheet_transaction_expired_title),
                description =
                    description.ifEmpty {
                        context.getString(
                            R.string.error_bottomsheet_transaction_expired_description
                        )
                    },
                buttonText =
                    context.getString(R.string.error_bottomsheet_transaction_expired_button),
                imageRes = R.drawable.icon_illustrations_failed_sad_3d,
                dismissable = false,
            )

        CODE_MAINTENANCE ->
            ErrorBottomSheetUiModel(
                title =
                    title.takeIf { !it.isNullOrBlank() }
                        ?: context.getString(R.string.error_bottomsheet_maintenance_title),
                description =
                    description.ifEmpty {
                        context.getString(R.string.error_bottomsheet_maintenance_description)
                    },
                buttonText = context.getString(R.string.error_bottomsheet_understand_button),
                imageRes = R.drawable.icon_illustrations_pending_3d,
                dismissable = false,
            )

        CODE_PIN_ERROR ->
            ErrorBottomSheetUiModel(
                title =
                    title.takeIf { !it.isNullOrBlank() }
                        ?: context.getString(R.string.error_bottomsheet_pin_locked_title),
                description =
                    description.ifEmpty {
                        context.getString(R.string.error_bottomsheet_pin_locked_description)
                    },
                buttonText = context.getString(R.string.error_bottomsheet_pin_locked_button),
                imageRes = R.drawable.icon_illustrations_denied,
                dismissable = false,
            )

        ACCOUNT_NOT_AVAILABLE ->
            ErrorBottomSheetUiModel(
                title =
                    context.getString(R.string.error_bottomsheet_cant_process_transaction_title),
                description =
                    context.getString(R.string.error_bottomsheet_account_not_available_description),
                buttonText = context.getString(R.string.error_bottomsheet_understand_button),
                imageRes = R.drawable.icon_illustrations_failed_sad_3d,
                dismissable = true,
            )

        ResponseCode.NETWORK_IO.code ->
            ErrorBottomSheetUiModel(
                title = context.getString(R.string.error_bottomsheet_no_internet_title),
                description = context.getString(R.string.error_bottomsheet_no_internet_description),
                buttonText = context.getString(R.string.error_bottomsheet_no_internet_button),
                imageRes = R.drawable.icon_illustrations_failed_sad_3d,
                dismissable = true,
            )

        else -> {
            if (isPay) {
                transactionFailed(context, image.orEmpty())
            } else {
                genericError(context, image.orEmpty())
            }
        }
    }
}

private fun MessageException.mapMessageException(
    context: Context,
    isPay: Boolean,
): ErrorBottomSheetUiModel {
    return if (isPay) {
        transactionFailed(context)
    } else {
        ErrorBottomSheetUiModel(
            title = context.getString(R.string.error_bottomsheet_generic_title),
            description =
                description.ifEmpty {
                    context.getString(R.string.error_bottomsheet_generic_description)
                },
            buttonText = context.getString(R.string.error_bottomsheet_generic_button),
            imageRes = R.drawable.icon_illustrations_failed_sad_3d,
        )
    }
}

private fun transactionFailed(context: Context, imageUrl: String = ""): ErrorBottomSheetUiModel {
    return ErrorBottomSheetUiModel(
        title = context.getString(R.string.error_bottomsheet_transaction_failed_title),
        description = context.getString(R.string.error_bottomsheet_transaction_failed_description),
        buttonText = context.getString(R.string.error_bottomsheet_transaction_failed_button),
        imageRes = R.drawable.icon_illustrations_failed_sad_3d,
        dismissable = false,
        imageUrl = imageUrl,
    )
}

private fun genericError(context: Context, imageUrl: String = ""): ErrorBottomSheetUiModel {
    return ErrorBottomSheetUiModel(
        title = context.getString(R.string.error_bottomsheet_generic_title),
        description = context.getString(R.string.error_bottomsheet_generic_description),
        buttonText = context.getString(R.string.error_bottomsheet_generic_button),
        imageRes = R.drawable.icon_illustrations_failed_sad_3d,
        imageUrl = imageUrl,
    )
}

```

**File: `core/ui/src/main/kotlin/id/co/bri/brimons/core/ui/core/security/SecurityManager.kt`**
```kotlin
package id.co.bri.brimons.core.ui.core.security

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context.AUDIO_SERVICE
import android.content.Context.TELECOM_SERVICE
import android.content.Context.TELEPHONY_SERVICE
import android.media.AudioManager
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import id.co.bri.brimons.core.security.device.AccessibilityChecker
import id.co.bri.brimons.core.security.device.DeveloperChecker
import id.co.bri.brimons.core.security.device.EmulatorChecker
import id.co.bri.brimons.core.security.device.KeyboardChecker
import id.co.bri.brimons.core.security.device.OverlayWatcher
import id.co.bri.brimons.core.ui.permission.requestPermission
import id.co.bri.brimons.core.util.permission.AppPermission
import id.co.bri.brimons.core.util.permission.PermissionStatus
import id.co.bri.brimons.core.util.permission.isPermissionGranted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SecurityManager(private val activity: ComponentActivity) {

    private val emulatorChecker = EmulatorChecker()
    private val overlayWatcher = OverlayWatcher(activity)
    private val keyboardChecker = KeyboardChecker(activity)
    private val developerChecker = DeveloperChecker(activity)

    private val accessibilityChecker = AccessibilityChecker(activity)
    private var isCallPermissionFlowHandled = false
    private var callPermissionResult: ((Boolean) -> Unit)? = null

    suspend fun runStaticChecks(): SecurityThreat? {
        return withContext(Dispatchers.Default) {
            when {
                emulatorChecker.isEmulator() -> SecurityThreat.EMULATOR

                developerChecker.isDeveloperModeEnabled() -> SecurityThreat.DEVELOPER_OPTION

                keyboardChecker.isCustomKeyboardEnabled() -> SecurityThreat.CUSTOM_KEYBOARD

                accessibilityChecker.hasSuspiciousAccessibilityServices() ->
                    SecurityThreat.ACCESSIBILITY

                else -> null
            }
        }
    }

    fun checkCallState(onThreat: (SecurityThreat) -> Unit, onSafe: () -> Unit = {}) {
        if (activity.isPermissionGranted(AppPermission.PHONE_STATE)) {
            checkActiveCall(onThreat, onSafe)
            return
        }

        if (isCallPermissionFlowHandled) return

        isCallPermissionFlowHandled = true
        callPermissionResult = { granted ->
            if (granted) {
                checkActiveCall(onThreat, onSafe)
            } else {
                onThreat(SecurityThreat.CALL_PERMISSION)
            }
        }
        activity.requestPermission(AppPermission.PHONE_STATE) { status ->
            callPermissionResult?.invoke(status == PermissionStatus.Granted)
        }
    }

    /**
     * Re-prompts for the call permission while the OS still allows a rationale, and only falls back
     * to the app settings screen once the user has permanently denied it.
     */
    fun showCallBlockSetting(onOpenAppSettings: () -> Unit) {
        if (
            ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.READ_PHONE_STATE,
            )
        ) {
            activity.requestPermission(AppPermission.PHONE_STATE) { status ->
                callPermissionResult?.invoke(status == PermissionStatus.Granted)
            }
        } else {
            onOpenAppSettings()
        }
    }

    @SuppressLint("MissingPermission")
    private fun checkActiveCall(onThreat: (SecurityThreat) -> Unit, onSafe: () -> Unit) {
        if (isUserCalling()) {
            onThreat(SecurityThreat.CALL)
        } else {
            onSafe()
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun isUserCalling(): Boolean {
        val telephonyManager = activity.getSystemService(TELEPHONY_SERVICE) as? TelephonyManager
        val telecomManager = activity.getSystemService(TELECOM_SERVICE) as? TelecomManager
        val audioManager = activity.getSystemService(AUDIO_SERVICE) as? AudioManager

        return when {
            telecomManager?.isInCall == true -> true
            telephonyManager?.callState == TelephonyManager.CALL_STATE_OFFHOOK -> true
            audioManager?.mode == AudioManager.MODE_IN_CALL -> true
            audioManager?.mode == AudioManager.MODE_IN_COMMUNICATION -> true
            else -> false
        }
    }

    fun startMonitoring(onThreat: (SecurityThreat) -> Unit) {
        overlayWatcher.start { onThreat(SecurityThreat.OVERLAY) }
    }

    fun stopMonitoring() {
        overlayWatcher.stop()
    }
}

```

**File: `core/ui/src/main/kotlin/id/co/bri/brimons/core/ui/core/security/SecurityThreat.kt`**
```kotlin
package id.co.bri.brimons.core.ui.core.security

import androidx.annotation.StringRes
import id.co.bri.brimons.core.ui.R

enum class SecurityThreat(
    @StringRes val titleRes: Int? = null,
    @StringRes val descriptionRes: Int,
    val type: Type,
) {

    ACCESSIBILITY(
        R.string.str_a11y_service_title,
        R.string.str_a11y_service_description,
        Type.GO_TO_ACCESSIBILITY,
    ),
    EMULATOR(
        R.string.txt_error_emulator_title,
        R.string.txt_error_emulator_subtitle,
        Type.BLOCKING_EXIT,
    ),
    DEVELOPER_OPTION(
        R.string.txt_error_developer_option_title,
        R.string.txt_error_developer_option_subtitle,
        Type.GO_TO_DEVELOPER_SETTINGS,
    ),
    CUSTOM_KEYBOARD(
        R.string.txt_error_change_keyboard_title,
        R.string.txt_error_change_keyboard_subtitle,
        Type.OPEN_KEYBOARD_PICKER,
    ),
    OVERLAY(
        R.string.txt_error_overlay_title,
        R.string.txt_error_overlay_subtitle,
        Type.BLOCKING_EXIT,
    ),
    ROOTED(R.string.txt_error_rooted_title, R.string.txt_error_rooted_subtitle, Type.BLOCKING_EXIT),
    CALL_PERMISSION(
        R.string.txt_error_call_permission_title,
        R.string.txt_error_call_permission_subtitle,
        Type.GO_TO_APP_SETTINGS,
    ),
    CALL(
        titleRes = R.string.txt_error_call_title,
        descriptionRes = R.string.txt_error_call_subtitle,
        type = Type.BLOCKING_EXIT,
    ),
}

enum class Type {
    BLOCKING_EXIT,
    GO_TO_DEVELOPER_SETTINGS,
    OPEN_KEYBOARD_PICKER,
    GO_TO_APP_SETTINGS,
    GO_TO_ACCESSIBILITY,
}

```

**File: `core/ui/src/main/kotlin/id/co/bri/brimons/core/ui/core/util/Flow.kt`**
```kotlin
package id.co.bri.brimons.core.ui.core.util

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

suspend inline fun <T> Flow<T>.launchAndCollectIn(
    owner: LifecycleOwner,
    minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
    crossinline action: suspend CoroutineScope.(T) -> Unit,
) =
    owner.repeatOnLifecycle(minActiveState) {
        withContext(Dispatchers.Main.immediate) { collect { action(it) } }
    }

```

**File: `core/ui/src/main/kotlin/id/co/bri/brimons/core/ui/dependency/ComposeApi.kt`**
```kotlin
package id.co.bri.brimons.core.ui.dependency

import android.app.Activity
import id.co.bri.brimons.core.model.exception.GeneralApiException

interface ComposeApi {

    @Deprecated(
        "Routes network calls through :app's own implementation instead of :core:network. " +
            "Inject EncryptedCallExecutor from :core:network directly in your repository instead."
    )
    suspend fun hitApi(url: String, request: Any, fastMenu: Boolean): String {
        return ""
    }

    fun onPin(activity: Activity, blocked: Boolean = false) = Unit

    fun onSession(activity: Activity) = Unit

    fun onAnotherDeviceDetected(activity: Activity) = Unit

    fun onDeleteUser(activity: Activity, error: GeneralApiException) = Unit

    fun onNavigateRoot(activity: Activity, isFromFastMenu: Boolean = false) = Unit
}

```

**File: `core/ui/src/main/kotlin/id/co/bri/brimons/core/ui/dependency/ComposeApiLocal.kt`**
```kotlin
package id.co.bri.brimons.core.ui.dependency

import androidx.compose.runtime.staticCompositionLocalOf

val LocalComposeApi = staticCompositionLocalOf<ComposeApi> { object : ComposeApi {} }

```

**File: `core/ui/src/main/kotlin/id/co/bri/brimons/core/ui/feature/base/mapper/AppEventMapper.kt`**
```kotlin
package id.co.bri.brimons.core.ui.feature.base.mapper

import android.content.Context
import id.co.bri.brimons.core.model.exception.event.AppEvent
import id.co.bri.brimons.uikit.R
import id.co.bri.brimons.uikit.compose.templates.ErrorBottomSheetUiModel

internal fun AppEvent.toErrorBottomSheetUiModel(context: Context): ErrorBottomSheetUiModel? {
    return when (this) {
        is AppEvent.SessionEnd ->
            ErrorBottomSheetUiModel(
                title = context.getString(R.string.error_bottomsheet_session_expired_title),
                description =
                    message.orDefault(
                        context = context,
                        resId = R.string.error_bottomsheet_session_expired_description,
                    ),
                buttonText = context.getString(R.string.error_bottomsheet_session_expired_button),
                imageRes = R.drawable.icon_illustrations_pending_3d,
                dismissable = false,
            )

        is AppEvent.LimitException ->
            ErrorBottomSheetUiModel(
                title =
                    title.orDefault(
                        context = context,
                        resId = R.string.error_bottomsheet_daily_limit_title,
                    ),
                description =
                    message.orDefault(
                        context = context,
                        resId = R.string.error_bottomsheet_daily_limit_description,
                    ),
                buttonText = context.getString(R.string.error_bottomsheet_daily_limit_button),
                imageRes = R.drawable.icon_illustrations_warning_3d,
                dismissable = false,
            )

        is AppEvent.AlertFinish ->
            ErrorBottomSheetUiModel(
                title =
                    title.orDefault(
                        context = context,
                        resId = R.string.error_bottomsheet_transaction_expired_title,
                    ),
                description =
                    message.orDefault(
                        context = context,
                        resId = R.string.error_bottomsheet_transaction_expired_description,
                    ),
                buttonText =
                    context.getString(R.string.error_bottomsheet_transaction_expired_button),
                imageRes = R.drawable.icon_illustrations_failed_sad_3d,
                dismissable = false,
            )

        is AppEvent.DeleteUser ->
            ErrorBottomSheetUiModel(
                title =
                    error.title.orDefault(
                        context = context,
                        resId = R.string.error_bottomsheet_generic_title,
                    ),
                description =
                    error.message.orDefault(
                        context = context,
                        resId = R.string.error_bottomsheet_generic_description,
                    ),
                buttonText = context.getString(R.string.error_bottomsheet_understand_button),
                imageRes = R.drawable.icon_illustrations_warning_3d,
                dismissable = false,
            )

        is AppEvent.GeneralError ->
            ErrorBottomSheetUiModel(
                title =
                    title.orDefault(
                        context = context,
                        resId = R.string.error_bottomsheet_generic_title,
                    ),
                description =
                    message.orDefault(
                        context = context,
                        resId = R.string.error_bottomsheet_generic_description,
                    ),
                buttonText = context.getString(R.string.error_bottomsheet_generic_button),
                imageRes = R.drawable.icon_illustrations_failed_sad_3d,
                imageUrl = image.orEmpty(),
                dismissable = true,
            )

        is AppEvent.LimitHit ->
            ErrorBottomSheetUiModel(
                title =
                    title.orDefault(
                        context = context,
                        resId = R.string.error_bottomsheet_generic_title,
                    ),
                description =
                    message.orDefault(
                        context = context,
                        resId = R.string.error_bottomsheet_generic_description,
                    ),
                buttonText = context.getString(R.string.error_bottomsheet_understand_button),
                imageRes = R.drawable.icon_illustrations_failed_sad_3d,
                imageUrl = image.orEmpty(),
                dismissable = true,
            )

        is AppEvent.ChangeDevice ->
            ErrorBottomSheetUiModel(
                title = context.getString(R.string.error_bottomsheet_generic_title),
                description = context.getString(R.string.error_bottomsheet_generic_description),
                buttonText = context.getString(R.string.error_bottomsheet_understand_button),
                imageRes = R.drawable.icon_illustrations_denied,
                dismissable = false,
            )

        is AppEvent.Maintenance ->
            ErrorBottomSheetUiModel(
                title =
                    title.orDefault(
                        context = context,
                        resId = R.string.error_bottomsheet_maintenance_title,
                    ),
                description =
                    message.orDefault(
                        context = context,
                        resId = R.string.error_bottomsheet_maintenance_description,
                    ),
                buttonText = context.getString(R.string.error_bottomsheet_understand_button),
                imageRes = R.drawable.icon_illustrations_pending_3d,
                dismissable = false,
            )

        is AppEvent.NetworkError ->
            ErrorBottomSheetUiModel(
                title = context.getString(R.string.error_bottomsheet_no_internet_title),
                description = context.getString(R.string.error_bottomsheet_no_internet_description),
                buttonText = context.getString(R.string.error_bottomsheet_no_internet_button),
                imageRes = R.drawable.icon_illustrations_failed_sad_3d,
                dismissable = true,
            )

        is AppEvent.DynamicError -> null
    }
}

private fun String?.orDefault(context: Context, resId: Int): String {
    return takeUnless { it.isNullOrBlank() } ?: context.getString(resId)
}

```

**File: `core/ui/src/main/kotlin/id/co/bri/brimons/core/ui/feature/base/ui/DynamicErrorBottomSheet.kt`**
```kotlin
package id.co.bri.brimons.core.ui.feature.base.ui

import androidx.compose.runtime.Composable
import id.co.bri.brimons.core.model.exception.dynamic.DynamicErrorApiException
import id.co.bri.brimons.core.model.exception.dynamic.DynamicErrorType
import id.co.bri.brimons.uikit.compose.templates.BaseButtonListBottomSheet
import id.co.bri.brimons.uikit.compose.templates.BottomSheetButton
import id.co.bri.brimons.uikit.util.BottomSheetImageResource

@Composable
fun DynamicErrorBottomSheet(error: Throwable?, onDismiss: () -> Unit, onAction: (String) -> Unit) {
    if (error is DynamicErrorApiException && error.errorType == DynamicErrorType.BOTTOM_SHEET) {
        BaseButtonListBottomSheet(
            showBottomSheet = true,
            onShowBottomSheet = {},
            title = error.title,
            description = error.description,
            subtitle = error.errorCode.orEmpty(),
            isDismissable = error.dismissable,
            onDismiss = onDismiss,
            image = BottomSheetImageResource(error.imageName.orEmpty()).resource,
            url = error.image.orEmpty(),
            buttons =
                error.buttons.map { button ->
                    BottomSheetButton(
                        text = button.title,
                        isPrimary = button.type == "primary",
                        onClick = { onAction(button.action) },
                    )
                },
        )
    }
}

```

**File: `core/ui/src/main/kotlin/id/co/bri/brimons/core/ui/feature/base/ui/FeatureErrorBottomSheet.kt`**
```kotlin
package id.co.bri.brimons.core.ui.feature.base.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import id.co.bri.brimons.core.model.exception.GeneralApiException
import id.co.bri.brimons.core.model.exception.dynamic.DynamicErrorApiException
import id.co.bri.brimons.core.network.legacy.ACCOUNT_NOT_AVAILABLE
import id.co.bri.brimons.core.network.legacy.CODE_ANOTHER_DEVICE
import id.co.bri.brimons.core.network.legacy.CODE_DELETE_USER
import id.co.bri.brimons.core.network.legacy.CODE_LIMIT_HIT
import id.co.bri.brimons.core.network.legacy.CODE_LIMIT_TRANSACTION
import id.co.bri.brimons.core.network.legacy.CODE_MAINTENANCE
import id.co.bri.brimons.core.network.legacy.CODE_PIN_ERROR
import id.co.bri.brimons.core.network.legacy.CODE_SESSION_EXPIRED
import id.co.bri.brimons.core.network.legacy.CODE_TRANSACTION_EXPIRED
import id.co.bri.brimons.core.network.legacy.CODE_TRANSACTION_FAILED
import id.co.bri.brimons.core.network.legacy.CODE_TRANSACTION_INSUFFICIENT
import id.co.bri.brimons.core.ui.core.network.mapper.toErrorBottomSheetUiModel
import id.co.bri.brimons.core.ui.dependency.LocalComposeApi
import id.co.bri.brimons.core.util.ext.findActivity
import id.co.bri.brimons.uikit.compose.internal.NavigationEvent
import id.co.bri.brimons.uikit.compose.templates.ErrorBottomSheet

@Composable
fun FeatureErrorBottomSheet(
    error: Throwable,
    showBottomSheet: Boolean,
    onShowBottomSheet: (Boolean) -> Unit,
    isForm: Boolean = false,
    isPay: Boolean = false,
    onRetry: () -> Unit = {},
    onDismiss: () -> Unit = {},
) {
    if (error is DynamicErrorApiException) return

    val context = LocalContext.current
    val activity = context.findActivity()
    val composeApi = LocalComposeApi.current

    if (error is GeneralApiException) {
        when (error.code) {
            CODE_ANOTHER_DEVICE -> activity?.let { composeApi.onAnotherDeviceDetected(it) }
            CODE_DELETE_USER -> activity?.let { composeApi.onDeleteUser(it, error) }
        }
    }

    val model = error.toErrorBottomSheetUiModel(context = context, isPay = isPay)

    ErrorBottomSheet(
        model = model,
        showBottomSheet = showBottomSheet,
        onShowBottomSheet = onShowBottomSheet,
        onPrimaryClick = {
            when {
                error is GeneralApiException && error.code == CODE_SESSION_EXPIRED -> {
                    activity?.let(composeApi::onSession)
                }

                error is GeneralApiException && error.code == CODE_PIN_ERROR -> {
                    activity?.let(composeApi::onPin)
                }

                error is GeneralApiException &&
                    error.code in
                        setOf(
                            CODE_LIMIT_TRANSACTION,
                            CODE_TRANSACTION_FAILED,
                            CODE_TRANSACTION_INSUFFICIENT,
                            CODE_TRANSACTION_EXPIRED,
                            CODE_MAINTENANCE,
                            CODE_LIMIT_HIT,
                        ) -> {
                    NavigationEvent.finish()
                }

                error is GeneralApiException && error.code == ACCOUNT_NOT_AVAILABLE -> {
                    // do-nothing
                }

                isPay -> {
                    NavigationEvent.finish()
                }

                else -> {
                    onRetry()
                }
            }
        },
        onDismiss = {
            onDismiss()
            if (isForm) {
                NavigationEvent.finish()
            }
        },
    )
}

```

**File: `core/ui/src/main/kotlin/id/co/bri/brimons/core/ui/permission/PermissionState.kt`**
```kotlin
package id.co.bri.brimons.core.ui.permission

import android.app.Activity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import id.co.bri.brimons.core.util.ext.openAppSettings
import id.co.bri.brimons.core.util.permission.AppPermission
import id.co.bri.brimons.core.util.permission.PermissionStatus

/**
 * Current state of one or more [id.co.bri.brimons.core.util.permission.AppPermission]s, plus the
 * two actions that can change it.
 */
@Stable
class PermissionState
internal constructor(
    private val activity: Activity?,
    private val manifestPermissions: List<String>,
) {

    /**
     * Assigned by [rememberPermissionState] on first composition. Not snapshot state — reassigning
     * the same remembered launcher on recomposition is a no-op.
     */
    internal var launcher: ActivityResultLauncher<Array<String>>? = null

    var status: PermissionStatus by
        mutableStateOf(
            activity?.let { resolveStatus(it, manifestPermissions) } ?: PermissionStatus.Granted
        )
        private set

    /** Shows the system permission dialog. */
    fun launch() {
        val host = activity ?: return
        if (manifestPermissions.isEmpty()) return
        AskedPermissionStore(host).markAsked(manifestPermissions)
        launcher?.launch(manifestPermissions.toTypedArray())
    }

    /** Opens this app's entry in system Settings. */
    fun openAppSettings() {
        activity?.openAppSettings()
    }

    internal fun refresh() {
        val host = activity ?: return
        status = resolveStatus(host, manifestPermissions)
    }
}

/**
 * Remembers the state of [permissions] for the current activity.
 *
 * ```
 * val camera = rememberPermissionState(AppPermission.CAMERA)
 *
 * when (camera.status) {
 *     PermissionStatus.Granted -> CameraContent()
 *     PermissionStatus.Denied -> RationaleSheet(onConfirm = camera::launch)
 *     PermissionStatus.PermanentlyDenied -> RationaleSheet(onConfirm = camera::openAppSettings)
 * }
 * ```
 */
@Composable
fun rememberPermissionState(vararg permissions: AppPermission): PermissionState {
    val isPreview = LocalInspectionMode.current
    val activity = LocalActivity.current
    check(isPreview || activity != null) {
        "rememberPermissionState() needs an Activity host; none found in the composition."
    }

    val requested = permissions.toList()
    val manifestPermissions =
        remember(requested) { requested.flatMap { it.manifestPermissions }.distinct() }
    val state =
        remember(activity, manifestPermissions) { PermissionState(activity, manifestPermissions) }

    if (!isPreview) {
        state.launcher =
            rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) {
                // The result map only reports what was asked; re-read the OS for the whole bundle.
                state.refresh()
            }

        // Returning from app Settings changes grants without any result callback firing.
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { state.refresh() }
    }

    return state
}

```

**File: `core/ui/src/main/kotlin/id/co/bri/brimons/core/ui/permission/Permissions.kt`**
```kotlin
package id.co.bri.brimons.core.ui.permission

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import id.co.bri.brimons.core.util.permission.AppPermission
import id.co.bri.brimons.core.util.permission.PermissionStatus
import java.util.UUID

/** For XML activities and fragments, Safe to call at any point in the lifecycle */
fun ComponentActivity.requestPermission(
    vararg permissions: AppPermission,
    onResult: (PermissionStatus) -> Unit,
) {
    val manifestPermissions = permissions.flatMap { it.manifestPermissions }
    if (manifestPermissions.isEmpty()) {
        onResult(PermissionStatus.Granted)
        return
    }

    var launcher: ActivityResultLauncher<Array<String>>? = null
    launcher =
        activityResultRegistry.register(
            "permission:${UUID.randomUUID()}",
            ActivityResultContracts.RequestMultiplePermissions(),
        ) {
            launcher?.unregister()
            onResult(resolveStatus(this, manifestPermissions))
        }

    AskedPermissionStore(this).markAsked(manifestPermissions)
    launcher.launch(manifestPermissions.toTypedArray())
}

/** Resolves the current status of the given permissions, without requesting them. */
internal fun resolveStatus(activity: Activity, permissions: List<String>): PermissionStatus {
    val missing = permissions.filterNot {
        ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
    }
    if (missing.isEmpty()) return PermissionStatus.Granted

    val store = AskedPermissionStore(activity)
    val isPermanentlyDenied = missing.any {
        !ActivityCompat.shouldShowRequestPermissionRationale(activity, it) && store.wasAsked(it)
    }

    return if (isPermanentlyDenied) PermissionStatus.PermanentlyDenied else PermissionStatus.Denied
}

/** Remembers which permissions have ever been requested. */
internal class AskedPermissionStore(context: Context) {

    private val preferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun wasAsked(permission: String): Boolean = asked().contains(permission)

    fun markAsked(permissions: List<String>) {
        preferences.edit { putStringSet(KEY_ASKED, asked() + permissions) }
    }

    private fun asked(): Set<String> = preferences.getStringSet(KEY_ASKED, emptySet()).orEmpty()

    private companion object {
        const val FILE_NAME = "core_ui_permission"
        const val KEY_ASKED = "asked_permissions"
    }
}

```

**File: `core/ui/src/main/res/values/strings.xml`**
```kotlin
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="txt_error_developer_option_title">Opsi Pengembang Tidak Didukung</string>
    <string name="txt_error_developer_option_subtitle">Qita tidak dapat diakses saat opsi pengembang aktif. Demi keamanan, nonaktifkan opsi pengembang untuk dapat mengakses Qita.</string>
    <string name="txt_error_change_keyboard_title">Mode Keyboard Tidak Didukung</string>
    <string name="txt_error_change_keyboard_subtitle">Qita tidak dapat diakses dengan keyboard kustom. Demi keamanan, gunakan keyboard standar perangkat untuk dapat mengakses Qita.</string>
    <string name="txt_error_emulator_title">Akses Kamu Ditolak</string>
    <string name="txt_error_emulator_subtitle">Qita tidak dapat dijalankan dengan emulator. Demi keamanan, gunakan sistem standar perangkat untuk dapat mengakses Qita.</string>
    <string name="txt_error_call_title">Selesaikan Panggilan Telepon Dulu</string>
    <string name="txt_error_call_subtitle">Demi menjaga keamanaan akun, akses ke Qita dibatasi sementara. Silakan masuk kembali setelah panggilan telepon selesai.</string>
    <string name="txt_error_call_permission_title">Izinkan Akses Telepon di Pengaturan</string>
    <string name="txt_error_call_permission_subtitle">Demi menjaga keamanan akun, izinkan Qita untuk mengetahui status dan mengelola panggilan telepon di perangkat kamu, ya.</string>
    <string name="txt_error_rooted_title">Sistem Perangkat Tidak Didukung</string>
    <string name="txt_error_rooted_subtitle">Qita tidak dapat diakses dengan sistem yang dimodifikasi. Demi keamanan, gunakan sistem standar perangkat untuk dapat mengakses Qita.</string>
    <string name="txt_error_overlay_title">Aplikasi Lain Terdeteksi Aktif di Layarmu</string>
    <string name="txt_error_overlay_subtitle">Demi keamanan, mohon tutup aplikasi lain yang berjalan dalam tampilan mengambang (pop-up) di perangkatmu untuk dapat mengakses Qita.</string>
    <string name="str_a11y_service_title">Nonaktifkan Fitur Aksesibilitas Perangkat</string>
    <string name="str_a11y_service_description">Untuk mengakses Qita, mohon nonaktifkan fitur Aksesibilitas di aplikasi yang terinstal. Caranya, buka Pengaturan > Aksesibilitas > Pilih Aplikasi Terinstal yang Aktif > Nonaktifkan\n(Fitur aksesibilitas di setiap handphone mungkin berbeda).\n\nInfo lebih lanjut, hubungi 1500017.</string>
</resources>

```

**File: `core/ui/src/test/kotlin/id/co/bri/brimons/core/ui/core/security/SecurityManagerTest.kt`**
```kotlin
package id.co.bri.brimons.core.ui.core.security

import android.Manifest
import android.app.Activity
import android.media.AudioManager
import androidx.activity.ComponentActivity
import id.co.bri.brimons.core.security.device.AccessibilityChecker
import id.co.bri.brimons.core.security.device.DeveloperChecker
import id.co.bri.brimons.core.security.device.EmulatorChecker
import id.co.bri.brimons.core.security.device.KeyboardChecker
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

@RunWith(RobolectricTestRunner::class)
class SecurityManagerTest {

    private lateinit var controller: ActivityController<ComponentActivity>
    private lateinit var activity: ComponentActivity

    @Before
    fun setup() {
        mockkConstructor(EmulatorChecker::class)
        mockkConstructor(DeveloperChecker::class)
        mockkConstructor(KeyboardChecker::class)
        mockkConstructor(AccessibilityChecker::class)

        // Default: a clean device.
        every { anyConstructed<EmulatorChecker>().isEmulator() } returns false
        every { anyConstructed<DeveloperChecker>().isDeveloperModeEnabled() } returns false
        every { anyConstructed<KeyboardChecker>().isCustomKeyboardEnabled() } returns false
        every {
            anyConstructed<AccessibilityChecker>().hasSuspiciousAccessibilityServices()
        } returns false

        // SecurityManager registers an activity-result launcher in its constructor, so the host
        // must still be CREATED when it is built — the same constraint the call sites honour.
        controller = Robolectric.buildActivity(ComponentActivity::class.java).create()
        activity = controller.get()
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    private fun securityManager() = SecurityManager(activity)

    private fun grantCallPermission() {
        shadowOf(activity.application).grantPermissions(Manifest.permission.READ_PHONE_STATE)
    }

    // ---- runStaticChecks: detection ------------------------------------------------------

    @Test
    fun `returns null when the device is clean`() = runTest {
        securityManager().runStaticChecks() shouldBe null
    }

    @Test
    fun `reports EMULATOR when running on an emulator`() = runTest {
        every { anyConstructed<EmulatorChecker>().isEmulator() } returns true

        securityManager().runStaticChecks() shouldBe SecurityThreat.EMULATOR
    }

    @Test
    fun `reports DEVELOPER_OPTION when developer mode is on`() = runTest {
        every { anyConstructed<DeveloperChecker>().isDeveloperModeEnabled() } returns true

        securityManager().runStaticChecks() shouldBe SecurityThreat.DEVELOPER_OPTION
    }

    @Test
    fun `reports CUSTOM_KEYBOARD when a keyboard outside the allowlist is default`() = runTest {
        every { anyConstructed<KeyboardChecker>().isCustomKeyboardEnabled() } returns true

        securityManager().runStaticChecks() shouldBe SecurityThreat.CUSTOM_KEYBOARD
    }

    @Test
    fun `reports ACCESSIBILITY when a suspicious service is enabled`() = runTest {
        every {
            anyConstructed<AccessibilityChecker>().hasSuspiciousAccessibilityServices()
        } returns true

        securityManager().runStaticChecks() shouldBe SecurityThreat.ACCESSIBILITY
    }

    // ---- runStaticChecks: precedence -----------------------------------------------------
    // Locks the order inherited from the legacy SecurityHelper: emulator -> developer ->
    // keyboard -> accessibility. A silent reorder changes which sheet the user sees.

    @Test
    fun `emulator wins over keyboard`() = runTest {
        every { anyConstructed<EmulatorChecker>().isEmulator() } returns true
        every { anyConstructed<KeyboardChecker>().isCustomKeyboardEnabled() } returns true

        securityManager().runStaticChecks() shouldBe SecurityThreat.EMULATOR
    }

    @Test
    fun `developer mode wins over keyboard`() = runTest {
        every { anyConstructed<DeveloperChecker>().isDeveloperModeEnabled() } returns true
        every { anyConstructed<KeyboardChecker>().isCustomKeyboardEnabled() } returns true

        securityManager().runStaticChecks() shouldBe SecurityThreat.DEVELOPER_OPTION
    }

    @Test
    fun `keyboard wins over accessibility`() = runTest {
        every { anyConstructed<KeyboardChecker>().isCustomKeyboardEnabled() } returns true
        every {
            anyConstructed<AccessibilityChecker>().hasSuspiciousAccessibilityServices()
        } returns true

        securityManager().runStaticChecks() shouldBe SecurityThreat.CUSTOM_KEYBOARD
    }

    // ---- checkCallState -------------------------------------------------------------------

    @Test
    fun `calls onSafe when the permission is granted and no call is active`() {
        grantCallPermission()
        var safe = false
        var threat: SecurityThreat? = null

        securityManager().checkCallState(onThreat = { threat = it }, onSafe = { safe = true })

        safe shouldBe true
        threat shouldBe null
    }

    @Test
    fun `reports CALL when the audio manager is in a call`() {
        grantCallPermission()
        val audioManager = activity.getSystemService(Activity.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_CALL
        var safe = false
        var threat: SecurityThreat? = null

        securityManager().checkCallState(onThreat = { threat = it }, onSafe = { safe = true })

        threat shouldBe SecurityThreat.CALL
        safe shouldBe false
    }

    @Test
    fun `defers to the permission launcher when the permission is missing`() {
        var safe = false
        var threat: SecurityThreat? = null

        securityManager().checkCallState(onThreat = { threat = it }, onSafe = { safe = true })

        // Neither outcome is known yet — the result arrives via the launcher callback.
        threat shouldBe null
        safe shouldBe false
    }

    // ---- showCallBlockSetting -------------------------------------------------------------
    // Ported from SecurityHelper. Without it a denied permission jumps straight to app settings
    // and kills the app, instead of re-prompting while the OS still allows a rationale.

    @Test
    fun `showCallBlockSetting opens app settings when no rationale should be shown`() {
        var openedSettings = false

        securityManager().showCallBlockSetting { openedSettings = true }

        openedSettings shouldBe true
    }

    // ---- SecurityThreat -------------------------------------------------------------------

    @Test
    fun `ROOTED blocks and exits`() {
        SecurityThreat.ROOTED.type shouldBe Type.BLOCKING_EXIT
    }
}

```

**File: `core/ui/src/test/kotlin/id/co/bri/brimons/core/ui/permission/PermissionResolveTest.kt`**
```kotlin
package id.co.bri.brimons.core.ui.permission

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import id.co.bri.brimons.core.util.permission.PermissionStatus
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * The whole point of the helper: every permission outcome is reachable from a JVM test, so nobody
 * has to install a build and tap through system dialogs to check a branch.
 *
 * Grant state comes from Robolectric shadows. `shouldShowRequestPermissionRationale` has no shadow
 * setter, so it is stubbed statically — see the MockK section of `docs/testing.md`.
 */
@RunWith(RobolectricTestRunner::class)
class PermissionResolveTest {

    private lateinit var controller: ActivityController<ComponentActivity>
    private lateinit var activity: ComponentActivity

    @Before
    fun setup() {
        controller = Robolectric.buildActivity(ComponentActivity::class.java).create()
        activity = controller.get()

        mockkStatic(ActivityCompat::class)
        // Default: the OS is done showing rationales for anything we ask about.
        every { ActivityCompat.shouldShowRequestPermissionRationale(any(), any()) } returns false
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    private fun grant(vararg permissions: String) =
        shadowOf(activity.application).grantPermissions(*permissions)

    private fun deny(vararg permissions: String) =
        shadowOf(activity.application).denyPermissions(*permissions)

    private fun rationaleFor(permission: String, value: Boolean) {
        every { ActivityCompat.shouldShowRequestPermissionRationale(any(), permission) } returns
            value
    }

    private fun markAsked(vararg permissions: String) =
        AskedPermissionStore(activity).markAsked(permissions.toList())

    // ---- Granted ----------------------------------------------------------------------------

    @Test
    fun `reports Granted when every permission is held`() {
        grant(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

        val sut =
            resolveStatus(
                activity,
                listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
            )

        sut shouldBe PermissionStatus.Granted
    }

    @Test
    fun `reports Granted for an empty permission list`() {
        // How AppPermission.NOTIFICATION below API 33 and WRITE_STORAGE on Q+ resolve: no dialog.
        resolveStatus(activity, emptyList()) shouldBe PermissionStatus.Granted
    }

    @Test
    fun `reports Denied when only part of the bundle is held`() {
        grant(Manifest.permission.CAMERA)
        deny(Manifest.permission.RECORD_AUDIO)

        val sut =
            resolveStatus(
                activity,
                listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
            )

        sut shouldBe PermissionStatus.Denied
    }

    // ---- Denied vs PermanentlyDenied ---------------------------------------------------------

    @Test
    fun `reports Denied when the OS still wants a rationale shown`() {
        deny(Manifest.permission.CAMERA)
        rationaleFor(Manifest.permission.CAMERA, true)
        markAsked(Manifest.permission.CAMERA)

        resolveStatus(activity, listOf(Manifest.permission.CAMERA)) shouldBe PermissionStatus.Denied
    }

    @Test
    fun `reports Denied when the permission has never been requested`() {
        // The case accompanist gets wrong: no rationale AND never asked is a first run, not a
        // permanent denial. Treating it as permanent would send a fresh user to Settings.
        deny(Manifest.permission.CAMERA)
        rationaleFor(Manifest.permission.CAMERA, false)

        resolveStatus(activity, listOf(Manifest.permission.CAMERA)) shouldBe PermissionStatus.Denied
    }

    @Test
    fun `reports PermanentlyDenied once asked and the rationale is suppressed`() {
        deny(Manifest.permission.CAMERA)
        rationaleFor(Manifest.permission.CAMERA, false)
        markAsked(Manifest.permission.CAMERA)

        resolveStatus(activity, listOf(Manifest.permission.CAMERA)) shouldBe
            PermissionStatus.PermanentlyDenied
    }

    @Test
    fun `reports PermanentlyDenied when any one of a bundle is permanently denied`() {
        // Locks `any` over `all`. Launching would prompt for RECORD_AUDIO and silently skip
        // CAMERA, so the user could never satisfy the screen.
        deny(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        rationaleFor(Manifest.permission.RECORD_AUDIO, true)
        rationaleFor(Manifest.permission.CAMERA, false)
        markAsked(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

        val sut =
            resolveStatus(
                activity,
                listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
            )

        sut shouldBe PermissionStatus.PermanentlyDenied
    }

    @Test
    fun `a granted permission never makes the bundle permanently denied`() {
        grant(Manifest.permission.CAMERA)
        deny(Manifest.permission.RECORD_AUDIO)
        rationaleFor(Manifest.permission.RECORD_AUDIO, true)
        markAsked(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

        val sut =
            resolveStatus(
                activity,
                listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
            )

        sut shouldBe PermissionStatus.Denied
    }
}

```

**File: `core/ui/src/test/kotlin/id/co/bri/brimons/core/ui/permission/PermissionsTest.kt`**
```kotlin
package id.co.bri.brimons.core.ui.permission

import android.Manifest
import android.os.Build
import androidx.activity.ComponentActivity
import id.co.bri.brimons.core.util.permission.AppPermission
import id.co.bri.brimons.core.util.permission.isPermissionGranted
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/** The imperative half of the API — what the XML activities and Java fragments call. */
@RunWith(RobolectricTestRunner::class)
class PermissionsTest {

    private lateinit var controller: ActivityController<ComponentActivity>
    private lateinit var activity: ComponentActivity

    @Before
    fun setup() {
        controller = Robolectric.buildActivity(ComponentActivity::class.java).create()
        activity = controller.get()
    }

    // ---- isPermissionGranted -----------------------------------------------------------------

    @Test
    fun `isPermissionGranted is true only when the whole group is held`() {
        shadowOf(activity.application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(activity.application).denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)

        activity.isPermissionGranted(AppPermission.LOCATION).shouldBeFalse()

        shadowOf(activity.application).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)

        activity.isPermissionGranted(AppPermission.LOCATION).shouldBeTrue()
    }

    @Test
    fun `isPermissionGranted spans every permission passed to it`() {
        shadowOf(activity.application).grantPermissions(Manifest.permission.CAMERA)
        shadowOf(activity.application).denyPermissions(Manifest.permission.READ_CONTACTS)

        activity.isPermissionGranted(AppPermission.CAMERA).shouldBeTrue()
        activity.isPermissionGranted(AppPermission.CAMERA, AppPermission.CONTACTS).shouldBeFalse()
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.Q])
    fun `isPermissionGranted is true when the group needs nothing on this API level`() {
        // WRITE_STORAGE resolves to an empty list on Q and above; `all` on empty is true.
        // Pinned: Robolectric defaults to minSdk here, since a library manifest carries no
        // targetSdk.
        activity.isPermissionGranted(AppPermission.WRITE_STORAGE).shouldBeTrue()
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.P])
    fun `isPermissionGranted still checks the write grant below API 29`() {
        shadowOf(activity.application).denyPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)

        activity.isPermissionGranted(AppPermission.WRITE_STORAGE).shouldBeFalse()

        shadowOf(activity.application).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)

        activity.isPermissionGranted(AppPermission.WRITE_STORAGE).shouldBeTrue()
    }

    // ---- AskedPermissionStore ----------------------------------------------------------------

    @Test
    fun `store reports nothing asked before the first request`() {
        AskedPermissionStore(activity).wasAsked(Manifest.permission.CAMERA).shouldBeFalse()
    }

    @Test
    fun `store remembers what was asked and leaves the rest alone`() {
        val sut = AskedPermissionStore(activity)

        sut.markAsked(listOf(Manifest.permission.CAMERA))

        sut.wasAsked(Manifest.permission.CAMERA).shouldBeTrue()
        sut.wasAsked(Manifest.permission.RECORD_AUDIO).shouldBeFalse()
    }

    @Test
    fun `store accumulates across requests instead of replacing`() {
        AskedPermissionStore(activity).markAsked(listOf(Manifest.permission.CAMERA))
        AskedPermissionStore(activity).markAsked(listOf(Manifest.permission.RECORD_AUDIO))

        // A fresh instance reads the same SharedPreferences file — this is what survives a restart.
        val sut = AskedPermissionStore(activity)

        sut.wasAsked(Manifest.permission.CAMERA).shouldBeTrue()
        sut.wasAsked(Manifest.permission.RECORD_AUDIO).shouldBeTrue()
    }
}

```

**File: `core/util/build.gradle.kts`**
```kotlin
plugins {
    alias(libs.plugins.brimo.android.library.flavors)
    alias(libs.plugins.brimo.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android { namespace = "id.co.bri.brimons.core.util" }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.bundles.robolectric)
}

```

**File: `core/util/src/main/kotlin/id/co/bri/brimons/core/util/Base64Utils.kt`**
```kotlin
package id.co.bri.brimons.core.util

import android.util.Base64
import android.util.Base64InputStream
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Decodes a Base64 encoded string into an [InputStream].
 *
 * The returned stream decodes the Base64 content as it is read, allowing the caller to consume the
 * decoded data without explicitly creating a decoded [ByteArray] first.
 *
 * @param base64String Base64 encoded content.
 * @return [InputStream] that provides the decoded binary content.
 */
fun decodeBase64ToInputStream(base64String: String): InputStream {
    val inputStream = ByteArrayInputStream(base64String.toByteArray())

    return Base64InputStream(inputStream, Base64.DEFAULT)
}

```

**File: `core/util/src/main/kotlin/id/co/bri/brimons/core/util/DateUtil.kt`**
```kotlin
package id.co.bri.brimons.core.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object DateUtil {
    const val FORMAT_DD_MMM_YYYY = "dd MMM yyyy"
    const val FORMAT_DD_MM_YYYY = "dd-MM-yyyy"
    const val FORMAT_YYYY_MM_DD = "yyyy-MM-dd"
    const val FORMAT_YYYY_MM_DD_HH_MM_SS = "yyyy-MM-dd HH:mm:ss"
    const val FORMAT_DD_MMM_YYYY_HH_MM = "dd MMM yyyy HH:mm"
    const val FORMAT_DD_MMMM_YYYY = "dd MMMM yyyy"
    const val FORMAT_E_DD_MMM_YYYY = "E, dd MMM yyyy"
    const val FORMAT_HH_MM = "HH:mm"
    const val FORMAT_HH_MM_SS = "HH:mm:ss"
    const val FORMAT_ISO_8601 = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
    const val FORMAT_DAY_MONTH_YEAR_TIME = "dd MMMM yyyy, HH:mm"
    const val FORMAT_DAY_FULL_MONTH_YEAR = "EEEE, dd MMMM yyyy"
    const val FORMAT_MONTH_YEAR = "MMMM yyyy"
    const val FORMAT_DAY = "EEEE"
    const val FORMAT_DATE_INSIDER = "yyyy-MM-dd'T'HH:mm:ss'Z'"
    const val UTC_ZONE_ID = "UTC"
    const val WIB_ZONE_ID = "Asia/Jakarta"
    const val LOCALE_ID_LANGUAGE = "id"
    const val LOCALE_ID_COUNTRY = "ID"

    /**
     * Converts a date string from one format to another.
     *
     * @param dateString The date string to convert.
     * @param inputFormat The format of the input date string.
     * @param outputFormat The desired format for the output date string.
     * @return The formatted date string, or null if parsing fails.
     */
    fun convertDateFormat(dateString: String?, inputFormat: String, outputFormat: String): String? {
        if (dateString.isNullOrEmpty()) {
            return null
        }

        val inputSdf =
            SimpleDateFormat(inputFormat, Locale.getDefault()).apply {
                // Use UTC for 'Z' (Zulu time)
                if (inputFormat.contains("'Z'")) {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
            }
        val outputSdf = SimpleDateFormat(outputFormat, Locale.getDefault())
        return convertDateFormat(dateString, inputSdf, outputSdf)
    }

    /**
     * Converts a date string from one format to another using provided SimpleDateFormat instances.
     * This is a simplified version for cases where you manage SimpleDateFormat instances yourself.
     *
     * @param dateString The date string to convert, can be null or empty.
     * @param inputFormat The SimpleDateFormat instance for parsing the input string.
     * @param outputFormat The SimpleDateFormat instance for formatting the output string.
     * @return The formatted date string, or null if the input is invalid or parsing fails.
     */
    fun convertDateFormat(
        dateString: String?,
        inputFormat: SimpleDateFormat,
        outputFormat: SimpleDateFormat,
    ): String? {
        if (dateString.isNullOrEmpty()) return null
        return runCatching { inputFormat.parse(dateString)?.let(outputFormat::format) }.getOrNull()
    }

    /**
     * Converts a Date object to a formatted string.
     *
     * @param date The Date object to convert.
     * @param outputFormat The desired format for the output date string.
     * @return The formatted date string, or null if the date is null.
     */
    fun convertDateToString(date: Date?, outputFormat: String): String? {
        if (date == null) {
            return null
        }
        return try {
            val outputSdf = SimpleDateFormat(outputFormat, Locale.getDefault())
            outputSdf.format(date)
        } catch (e: Exception) {
            // Catch potential exceptions from SimpleDateFormat, although less likely with a Date
            // object
            e.printStackTrace()
            null
        }
    }

    /**
     * Converts a date string to a Date object.
     *
     * @param dateString The date string to convert.
     * @param inputFormat The format of the input date string.
     * @return The Date object, or null if parsing fails.
     */
    fun convertStringToDate(dateString: String?, inputFormat: String): Date? {
        if (dateString.isNullOrEmpty()) return null
        val sdf =
            SimpleDateFormat(inputFormat, Locale.getDefault()).apply {
                if (inputFormat.contains("'Z'")) {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
            }
        return runCatching { sdf.parse(dateString) }.getOrNull()
    }

    /**
     * Gets the current date and time formatted as a string.
     *
     * @param format The desired date format.
     * @return The formatted current date string.
     */
    fun getCurrentDate(format: String = FORMAT_DD_MM_YYYY): String {
        return SimpleDateFormat(format, Locale.getDefault()).format(Date())
    }

    /**
     * Checks if a given date string in ISO 8601 format is in the future. The comparison is done
     * against a reference date, which defaults to the current system time.
     *
     * @param date The date string to check, expected in "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'" format
     *   (UTC).
     * @param from The date to compare against. Defaults to the current moment.
     * @return `true` if the date is after the current moment, `false` otherwise (including if
     *   parsing fails or the date is in the past/present).
     * @sample val futureDateString = "2099-12-31T23:59:59.000Z" val isFuture =
     *   DateUtil.isDataInFuture(futureDateString) // returns true
     *
     * val pastDateString = "2000-01-01T00:00:00.000Z" val isFuturePast =
     * DateUtil.isDataInFuture(pastDateString) // returns false
     */
    fun isDataInFuture(date: String, from: Date = Date()): Boolean {
        val sdf = SimpleDateFormat(FORMAT_ISO_8601, Locale.getDefault())
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return try {
            val dataDate = sdf.parse(date)
            dataDate?.after(from) ?: false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Checks if a given date string in ISO 8601 format is in the past. The comparison is done
     * against a reference date, which defaults to the current system time.
     *
     * @param date The date string to check, expected in "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'" format
     *   (UTC).
     * @param from The date to compare against. Defaults to the current moment.
     * @return `true` if the date is before the current moment, `false` otherwise (including if
     *   parsing fails or the date is in the future/present).
     * @sample val pastDateString = "2000-01-01T00:00:00.000Z" val isPast =
     *   DateUtil.isDateInPast(pastDateString) // returns true
     *
     * val futureDateString = "2099-12-31T23:59:59.000Z" val isPastFuture =
     * DateUtil.isDateInPast(futureDateString) // returns false
     */
    fun isDateInPast(date: String, from: Date = Date()): Boolean {
        val sdf = SimpleDateFormat(FORMAT_ISO_8601, Locale.getDefault())
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return try {
            val dataDate = sdf.parse(date)
            dataDate?.before(from) ?: false
        } catch (_: Exception) {
            false
        }
    }
}

```

**File: `core/util/src/main/kotlin/id/co/bri/brimons/core/util/FileStorage.kt`**
```kotlin
package id.co.bri.brimons.core.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import id.co.bri.brimons.core.util.constants.MimeTypes
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "FileStorage"
private const val FILE_PROVIDER_SUFFIX = ".fileprovider"
private const val CACHE_DIR = "file_storage"

/** Returns the dedicated cache directory used for temporary file storage. */
private fun Context.getFileStorageCacheDir(): File = File(cacheDir, CACHE_DIR).apply { mkdirs() }

/** Deletes all files from the dedicated FileStorage cache directory. */
private fun Context.clearFileStorageCache() {
    getFileStorageCacheDir().listFiles()?.forEach { file -> file.delete() }
}

/**
 * Saves binary data to the user's public Downloads directory.
 *
 * @param data Binary content to save.
 * @param fileName Name of the resulting file.
 * @param mimeType MIME type of the file.
 * @return [Result] containing the saved [Uri] when successful, or the encountered exception when
 *   the operation fails.
 */
suspend fun Context.saveToDownloads(
    data: ByteArray,
    fileName: String,
    mimeType: String,
): Result<Uri> =
    withContext(Dispatchers.IO) {
        ByteArrayInputStream(data).use { inputStream ->
            saveInputStreamToDownloads(
                inputStream = inputStream,
                fileName = fileName,
                mimeType = mimeType,
            )
        }
    }

/**
 * Saves content from an [InputStream] to the user's public Downloads directory.
 *
 * This function handles the storage implementation based on the Android version:
 * - Android 10 (API 29) and above: uses [MediaStore] and scoped storage.
 * - Android versions below 10: uses the legacy public Downloads directory.
 *
 * @param inputStream Input stream containing the file content.
 * @param fileName Name of the resulting file.
 * @param mimeType MIME type of the file.
 * @return [Result] containing the saved [Uri] when successful, or the encountered exception when
 *   the operation fails.
 */
private fun Context.saveInputStreamToDownloads(
    inputStream: InputStream,
    fileName: String,
    mimeType: String,
): Result<Uri> {
    return runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToDownloadsApi29(
                inputStream = inputStream,
                fileName = fileName,
                mimeType = mimeType,
            )
        } else {
            saveToDownloadsLegacy(inputStream = inputStream, fileName = fileName)
        }
    }
}

/**
 * Saves content to the public Downloads directory using [MediaStore].
 *
 * The file is initially created with [MediaStore.MediaColumns.IS_PENDING] set to `1` so that it is
 * not exposed as a completed file until the write operation succeeds. If writing the file fails,
 * the partially created MediaStore entry is deleted.
 *
 * @param inputStream Input stream containing the file content.
 * @param fileName Name of the resulting file.
 * @param mimeType MIME type of the file.
 * @return [Uri] of the saved file.
 * @throws IllegalStateException if the file cannot be created or its output stream cannot be
 *   opened.
 * @throws Exception if writing or updating the file fails.
 */
@RequiresApi(Build.VERSION_CODES.Q)
private fun Context.saveToDownloadsApi29(
    inputStream: InputStream,
    fileName: String,
    mimeType: String,
): Uri {
    val resolver = contentResolver

    val contentValues =
        ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

    val uri =
        resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            ?: error("Failed to create file in Downloads")

    try {
        resolver.openOutputStream(uri)?.use { outputStream -> inputStream.copyTo(outputStream) }
            ?: error("Failed to open output stream")

        contentValues.clear()
        contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)

        resolver.update(uri, contentValues, null, null)

        return uri
    } catch (exception: Exception) {
        resolver.delete(uri, null, null)
        throw exception
    }
}

/**
 * Saves content to the public Downloads directory using the legacy external storage API for Android
 * versions below API 29.
 *
 * This implementation is required for devices where [MediaStore] with
 * [MediaStore.MediaColumns.RELATIVE_PATH] is not available.
 *
 * @param inputStream Input stream containing the file content.
 * @param fileName Name of the resulting file.
 * @return [Uri] pointing to the saved file.
 * @throws Exception if the Downloads directory cannot be created or the file cannot be written.
 */
private fun saveToDownloadsLegacy(inputStream: InputStream, fileName: String): Uri {
    val downloadsDirectory =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    if (!downloadsDirectory.exists()) {
        downloadsDirectory.mkdirs()
    }

    val file = File(downloadsDirectory, fileName)

    FileOutputStream(file).use { outputStream -> inputStream.copyTo(outputStream) }

    return Uri.fromFile(file)
}

/**
 * Saves Base64 encoded content to the app's cache directory.
 *
 * @param base64String Base64 encoded content.
 * @param fileName Name of the resulting file.
 * @return [Result] containing the saved [File] when successful.
 */
suspend fun Context.saveBase64ToCache(base64String: String, fileName: String): Result<File> =
    withContext(Dispatchers.IO) {
        decodeBase64ToInputStream(base64String).use { inputStream ->
            saveInputStreamToCache(inputStream = inputStream, fileName = fileName)
        }
    }

/**
 * Saves Base64 encoded content to the user's public Downloads directory.
 *
 * The Base64 content is decoded into an [InputStream] and then saved using
 * [saveInputStreamToDownloads], which handles the storage implementation based on the Android
 * version.
 *
 * @param base64String Base64 encoded content.
 * @param fileName Name of the resulting file.
 * @param mimeType MIME type of the file.
 * @return [Result] containing the saved [Uri] when successful, or the encountered exception when
 *   the operation fails.
 */
suspend fun Context.saveBase64ToDownloads(
    base64String: String,
    fileName: String,
    mimeType: String = MimeTypes.PDF,
): Result<Uri> =
    withContext(Dispatchers.IO) {
        decodeBase64ToInputStream(base64String).use { inputStream ->
            saveInputStreamToDownloads(
                inputStream = inputStream,
                fileName = fileName,
                mimeType = mimeType,
            )
        }
    }

/**
 * Saves content from an [InputStream] to the app's cache directory.
 *
 * @param inputStream Input stream containing the file content.
 * @param fileName Name of the resulting file.
 * @return [Result] containing the saved [File] when successful.
 */
private fun Context.saveInputStreamToCache(
    inputStream: InputStream,
    fileName: String,
): Result<File> {
    return runCatching {
        clearFileStorageCache()

        val file = File(getFileStorageCacheDir(), fileName)

        FileOutputStream(file).use { output -> inputStream.copyTo(output) }

        file
    }
}

/**
 * Opens a file with an external application.
 *
 * Use this for simple file opening without an Activity Result callback. Use
 * [createFileViewerIntent] when the caller needs to handle the Activity Result.
 *
 * @param file The file to open.
 * @param mimeType The MIME type of the file.
 * @return true if file opening was successful, false otherwise.
 */
fun Context.openFile(file: File, mimeType: String = MimeTypes.PDF): Boolean {
    val intent =
        createFileViewerIntent(file = file, mimeType = mimeType)
            ?: run {
                logcat(tag = TAG, priority = LogPriority.ERROR) {
                    "No app found to open file. mimeType=$mimeType"
                }
                return false
            }

    return try {
        startActivity(intent)
        true
    } catch (exception: Exception) {
        logcat(tag = TAG, priority = LogPriority.ERROR) {
            "Failed to open file: ${exception.message}"
        }
        false
    }
}

/**
 * Creates an [Intent] to open a file with an external application.
 *
 * Use this when the caller needs to launch the file viewer through the Activity Result API. Use
 * [openFile] for simple file opening without an Activity Result callback.
 *
 * @param file The file to open.
 * @param mimeType The MIME type of the file.
 * @return The [Intent], or null if no suitable application is available.
 */
fun Context.createFileViewerIntent(file: File, mimeType: String = MimeTypes.PDF): Intent? {
    val authority = "$packageName$FILE_PROVIDER_SUFFIX"

    return try {
        val uri = FileProvider.getUriForFile(this, authority, file)

        Intent(Intent.ACTION_VIEW)
            .apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
            }
            .takeIf { intent -> intent.resolveActivity(packageManager) != null }
    } catch (exception: Exception) {
        logcat(tag = TAG, priority = LogPriority.ERROR) {
            "Failed to create file viewer intent: ${exception.message}"
        }
        null
    }
}

```

**File: `core/util/src/main/kotlin/id/co/bri/brimons/core/util/GeneralHelper.kt`**
```kotlin
package id.co.bri.brimons.core.util

object GeneralHelper {
    fun isProd(): Boolean = BuildConfig.FLAVOR.equals("production", ignoreCase = true)

    fun isDebug(): Boolean = BuildConfig.DEBUG

    fun isProdRelease(): Boolean = isProd() && isDebug().not()
}

```

**File: `core/util/src/main/kotlin/id/co/bri/brimons/core/util/LogHelper.kt`**
```kotlin
package id.co.bri.brimons.core.util

import android.util.Log

@PublishedApi internal const val MAX_LOG_LENGTH = 4000

@PublishedApi internal const val MAX_TAG_LENGTH = 22

inline fun logcat(
    tag: String = "Qita Logs",
    priority: LogPriority = LogPriority.DEBUG,
    messageBuilder: () -> String?,
) {
    if (GeneralHelper.isProdRelease().not()) {
        val message = messageBuilder.invoke() ?: "<no logs>"
        if (message.length > MAX_LOG_LENGTH) {
            message.chunked(MAX_LOG_LENGTH).forEachIndexed { index, chunk ->
                Log.println(
                    priority.priorityInt,
                    tag.take(MAX_TAG_LENGTH).ifBlank { "Lihat" },
                    "$index: $chunk",
                )
            }
        } else {
            Log.println(priority.priorityInt, tag.take(MAX_TAG_LENGTH).ifBlank { "Lihat" }, message)
        }
    }
}

@Suppress("unused")
enum class LogPriority(val priorityInt: Int) {
    VERBOSE(2),
    DEBUG(3),
    INFO(4),
    WARN(5),
    ERROR(6),
    ASSERT(7),
}

```

**File: `core/util/src/main/kotlin/id/co/bri/brimons/core/util/constants/ErrorRouteType.kt`**
```kotlin
package id.co.bri.brimons.core.util.constants

enum class ErrorRouteType(val type: String) {
    TRY_AGAIN("try_again"),
    BACK_HOME("back_home"),
    UNDERSTAND("understand"),
    MAINTENANCE("maintenance"),
    WORKING_HOUR("working_hour"),
}

```

**File: `core/util/src/main/kotlin/id/co/bri/brimons/core/util/constants/GeneralConstant.kt`**
```kotlin
package id.co.bri.brimons.core.util.constants

object GeneralConstant {

    const val UPPERCASE = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    const val ALPHABET = "abcdefghijklmnopqrstuvwxyz"
    const val NUMERIC = "0123456789"
    const val ALPHABET_CAPITAL = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    const val ALPHA_NUMERIC = "$ALPHABET$NUMERIC"
    const val SPACE = " "
    const val ALPHA_NUMERIC_CAPITAL = "$ALPHABET_CAPITAL$ALPHA_NUMERIC"

    const val ALL_CHARS = ALPHABET + UPPERCASE + NUMERIC

    const val ALLOWED_EMAIL_CHARS = "$ALPHA_NUMERIC@._+-"
    const val ALLOWED_GENERAL_FORM_CHARS = "$ALPHA_NUMERIC .,'/:()-"

    const val IDN_PHONE_CODE = "+62"
    const val IDN_PHONE_CODE_CLEAN = "62"
    const val IDN_PHONE_VALID_PREFIX = "8"
    const val MIME_TYPE_TEXT = "text/plain"
    const val SPECIAL_CHARS = " -.,'/"
    const val ALPHABET_WITH_SPECIAL = ALPHABET + SPECIAL_CHARS
    const val DEFAULT_LATITUDE = "0.0"
    const val DEFAULT_LONGITUDE = "0.0"

    // Used for input with separator grouping
    const val DEFAULT_GROUP_SIZE = 4

    const val ANIMATE_DURATION: Long = 300

    const val BRIVA = "BRIVA"

    const val REGEX_FEEDBACK_FAQ = "^[a-zA-Z0-9\\s.,'/:\\(\\)\\-]*$"

    const val UNKNOWN_ERROR = "Unknown error"

    const val RESULT_CODE = "result_code"

    const val RESULT_MESSAGE = "result_message"
    const val ACCOUNT_TYPE = "account_type"
    const val ACCOUNT_NAME = "account_name"
    const val PRODUCT_TYPE = "product_type"
    const val ERROR = "error"
    const val INVALID_KATA_KUNCI_REGEX = "[<>]"
    const val MAX_LENGTH_ADDRESS = 200
}

```

**File: `core/util/src/main/kotlin/id/co/bri/brimons/core/util/constants/LifestyleTrxType.kt`**
```kotlin
package id.co.bri.brimons.core.util.constants

enum class LifestyleTrxType(val value: String) {
    VOUCHER_STREAMING("PurchaseStreaming-V3"),
    VOUCHER_STREAMING_NS("PurchaseStreamingNS");

    companion object {
        private val byValue = entries.associateBy { it.value }

        fun contains(trxType: String): Boolean = trxType in byValue
    }
}

```

**File: `core/util/src/main/kotlin/id/co/bri/brimons/core/util/constants/MimeTypes.kt`**
```kotlin
package id.co.bri.brimons.core.util.constants

object MimeTypes {
    const val PDF = "application/pdf"
    const val PNG = "image/png"
    const val JPEG = "image/jpeg"
    const val JSON = "application/json"
}

```

**File: `core/util/src/main/kotlin/id/co/bri/brimons/core/util/constants/NavConstant.kt`**
```kotlin
package id.co.bri.brimons.core.util.constants

object NavConstant {

    const val BUTTON_DISABLED_EXTRA = "button_disabled_extra"
    const val IS_FROM_LANDING = "is_from_landing"
}

```

**File: `core/util/src/main/kotlin/id/co/bri/brimons/core/util/dispatcher/DispatcherModule.kt`**
```kotlin
package id.co.bri.brimons.core.util.dispatcher

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

@Module
@InstallIn(SingletonComponent::class)
object DispatcherModule {

    @Provides
    @Singleton
    @IODispatcher
    fun provideIODispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default
}

```

**File: `core/util/src/main/kotlin/id/co/bri/brimons/core/util/dispatcher/DispatcherQualifiers.kt`**
```kotlin
package id.co.bri.brimons.core.util.dispatcher

import javax.inject.Qualifier

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class IODispatcher

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class DefaultDispatcher

```

**File: `core/util/src/main/kotlin/id/co/bri/brimons/core/util/ext/Context.kt`**
```kotlin
package id.co.bri.brimons.core.util.ext

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

```

**File: `core/util/src/main/kotlin/id/co/bri/brimons/core/util/ext/CopyToClipboard.kt`**
```kotlin
package id.co.bri.brimons.core.util.ext

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.core.content.getSystemService

fun Context.copyToClipboard(
    text: String,
    label: String = "Copied Text",
    isSensitive: Boolean = false,
) {
    val clipboard = getSystemService<ClipboardManager>() ?: return
    val clip =
        ClipData.newPlainText(label, text).apply {
            if (isSensitive) {
                description.extras =
                    PersistableBundle().apply {
                        putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                    }
            }
        }
    clipboard.setPrimaryClip(clip)
}

```

**File: `core/util/src/main/kotlin/id/co/bri/brimons/core/util/ext/Currency.kt`**
```kotlin
package id.co.bri.brimons.core.util.ext

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Locale

fun String.toDoubleCurrency(): Double {
    return this.replace(".", "").replace(",", ".").toDoubleOrNull() ?: 0.0
}

fun Double.toCurrencyFormat(): String {
    val rounded = BigDecimal(this.toString()).setScale(2, RoundingMode.HALF_UP)

    val integerPart = rounded.toLong()
    val decimalPart = rounded.remainder(BigDecimal.ONE).movePointRight(2).toInt()

    val formattedInteger = integerPart.toString().reversed().chunked(3).joinToString(".").reversed()

    return if (decimalPart > 0) {
        "$formattedInteger,${decimalPart.toString().padStart(2, '0')}"
    } else {
        formattedInteger
    }
}

fun Double.toLocalizedCurrencyString(): String {
    val symbols =
        DecimalFormatSymbols().apply {
            groupingSeparator = '.'
            decimalSeparator = ','
        }

    val hasDecimal = this % 1.0 != 0.0
    val pattern = if (hasDecimal) "#,##0.##" else "#,##0"

    return DecimalFormat(pattern, symbols).format(this)
}

fun Double.toCurrencyString(): String =
    BigDecimal(this).setScale(2, RoundingMode.HALF_UP).toPlainString()

fun Number.toIdr(): String {
    val formatter =
        NumberFormat.getNumberInstance(Locale("in", "ID")).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = 2
        }
    return "Rp${formatter.format(this)}"
}

fun Long.toRupiah(): String {
    if (this <= 0L) return "Rp0"
    return "Rp${toString().thousandSeparator()}"
}

```

**File: `core/util/src/main/kotlin/id/co/bri/brimons/core/util/ext/Gson.kt`**
```kotlin
package id.co.bri.brimons.core.util.ext

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

inline fun <reified T> String.deserialize(): T? {
    return runCatching {
            val gson = Gson()
            val type = object : TypeToken<T>() {}.type
            gson.fromJson<T>(this, type)
        }
        .getOrNull()
}

fun <T> T.serialize(): String? {
    return runCatching {
            val gson = Gson()
            gson.toJson(this)
        }
        .getOrNull()
}

```

**File: `core/util/src/main/kotlin/id/co/bri/brimons/core/util/ext/InputValidator.kt`**
```kotlin
package id.co.bri.brimons.core.util.ext

fun String.isAlphaNumeric() = matches(Regex("^[A-Za-z0-9 ]*$"))

```

**File: `core/util/src/main/kotlin/id/co/bri/brimons/core/util/ext/JsonExt.kt`**
```kotlin
package id.co.bri.brimons.core.util.ext

import id.co.bri.brimons.core.util.LogPriority
import id.co.bri.brimons.core.util.logcat
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

@OptIn(ExperimentalSerializationApi::class)
val json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
    explicitNulls = false
    namingStrategy = JsonNamingStrategy.SnakeCase
}

inline fun <reified T> T.toJsonString(): String =
    try {
        json.encodeToString(this)
    } catch (e: Exception) {
        logcat(priority = LogPriority.ERROR) { "Failed to encode JSON: ${e.message}" }
        ""
    }

inline fun <reified T> String.fromJsonString(): T? =
    try {
        json.decodeFromString(this)
    } catch (e: Exception) {
        logcat(priority = LogPriority.ERROR) { "Failed to decode from JSON: ${e.message}" }
        null
    }

```

**File: `core/util/src/main/kotlin/id/co/bri/brimons/core/util/ext/Number.kt`**
```kotlin
package id.co.bri.brimons.core.util.ext

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat

fun String.thousandSeparator(): String {
    return try {
        val numberFormat = NumberFormat.getInstance() as DecimalFormat
        val decimalFormatSymbols =
            DecimalFormatSymbols().apply {
                groupingSeparator = '.'
                decimalSeparator = ','
            }
        numberFormat.decimalFormatSymbols = decimalFormatSymbols
        numberFormat.maximumFractionDigits = 2
        val number = this.toDoubleOrNull() ?: 0.0
        numberFormat.format(number)
    } catch (_: Throwable) {
        this
    }
}

```

**File: `core/util/src/main/kotlin/id/co/bri/brimons/core/util/ext/OpenExternalApp.kt`**
```kotlin
package id.co.bri.brimons.core.util.ext

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.net.toUri

fun Context.openPlayStore(packageName: String = this.packageName) {
    try {
        val intent =
            Intent(Intent.ACTION_VIEW, "market://details?id=$packageName".toUri()).apply {
                setPackage("com.android.vending")
            }

        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        startActivity(
            Intent(
                Intent.ACTION_VIEW,
                "https://play.google.com/store/apps/details?id=$packageName".toUri(),
            )
        )
    }
}

fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

```

**File: `core/util/src/main/kotlin/id/co/bri/brimons/core/util/ext/StringExtension.kt`**
```kotlin
package id.co.bri.brimons.core.util.ext

const val BRI_BANK_CODE = "002"
const val MAX_LENGTH_TEXT_FIELD = 16

fun String.isBriBankCode(): Boolean {
    return this == BRI_BANK_CODE
}

```

**File: `core/util/src/main/kotlin/id/co/bri/brimons/core/util/helper/DateTime.kt`**
```kotlin
package id.co.bri.brimons.core.util.helper

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

fun getDateTimeSecond(second: Int): String {
    return runCatching {
            val now = Date()
            val calendar = Calendar.getInstance()
            calendar.time = now
            calendar.add(Calendar.SECOND, second)
            val formatter = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.ENGLISH)
            val formattedDate = formatter.format(calendar.time)
            formattedDate
        }
        .getOrElse { "" }
}

fun getCountDownTime(second: Int): String {
    return String.format(Locale.getDefault(), "%02d:%02d", second / 60, second % 60)
}

fun isDateExpired(date: String, formatDate: String): Boolean {
    return runCatching {
            val format = SimpleDateFormat(formatDate, Locale.getDefault())
            val inputDate = format.parse(date)
            val currentDate = Date()
            inputDate?.before(currentDate) ?: false
        }
        .getOrElse { false }
}

fun convertDate(date: String, input: String, output: String): String {
    return runCatching {
            val inputFormat = SimpleDateFormat(input, Locale.ENGLISH)
            val outputFormat = SimpleDateFormat(output, Locale.ENGLISH)
            val inputDate = inputFormat.parse(date)
            if (inputDate != null) {
                val outputDate = outputFormat.format(inputDate)
                outputDate
            } else {
                date
            }
        }
        .getOrElse { date }
}

```

**File: `core/util/src/main/kotlin/id/co/bri/brimons/core/util/helper/Random.kt`**
```kotlin
package id.co.bri.brimons.core.util.helper

import java.security.MessageDigest
import java.security.SecureRandom

fun generateRandomString(): String {
    val secureRandom = SecureRandom()
    val timestamp = System.currentTimeMillis()
    try {
        val randomInt = secureRandom.nextInt(Int.MAX_VALUE)
        val randomLong = secureRandom.nextLong()
        val input = timestamp.toString() + "_" + randomInt + "_" + randomLong

        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray())

        val hexString = StringBuilder()
        for (b in hash) {
            val hex = Integer.toHexString(0xff and b.toInt())
            if (hex.length == 1) {
                hexString.append('0')
            }
            hexString.append(hex)
        }

        return hexString.toString()
    } catch (_: Throwable) {
        return md5(timestamp.toString() + "_" + secureRandom.nextInt())
    }
}

private fun md5(s: String): String {
    return try {
        val digest = MessageDigest.getInstance("MD5")
        digest.update(s.toByteArray())
        val messageDigest = digest.digest()

        val hexString = StringBuilder()
        for (b in messageDigest) {
            var hex = Integer.toHexString(0xff and b.toInt())
            while (hex.length < 2) {
                hex = "0$hex"
            }
            hexString.append(hex)
        }

        return hexString.toString()
    } catch (_: Throwable) {
        ""
    }
}

```

**File: `core/util/src/main/kotlin/id/co/bri/brimons/core/util/permission/AppPermission.kt`**
```kotlin
package id.co.bri.brimons.core.util.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** True when every manifest permission behind [permissions] is already granted. */
fun Context.isPermissionGranted(vararg permissions: AppPermission): Boolean =
    permissions
        .flatMap { it.manifestPermissions }
        .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

enum class AppPermission(private val resolve: () -> List<String>) {
    CAMERA({ listOf(Manifest.permission.CAMERA) }),
    LOCATION({
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }),
    FINE_LOCATION({ listOf(Manifest.permission.ACCESS_FINE_LOCATION) }),
    CONTACTS({ listOf(Manifest.permission.READ_CONTACTS) }),
    MICROPHONE({ listOf(Manifest.permission.RECORD_AUDIO) }),
    PHONE_STATE({ listOf(Manifest.permission.READ_PHONE_STATE) }),
    PHONE_CALL({ listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.CALL_PHONE) }),
    CALL({ listOf(Manifest.permission.CALL_PHONE) }),
    NOTIFICATION({ sdkFrom(Build.VERSION_CODES.TIRAMISU, Manifest.permission.POST_NOTIFICATIONS) }),
    BLUETOOTH({
        sdkFrom(
            Build.VERSION_CODES.S,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
    }),
    READ_MEDIA({
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                listOf(Manifest.permission.READ_EXTERNAL_STORAGE)

            else ->
                listOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                )
        }
    }),
    WRITE_STORAGE({ sdkBelow(Build.VERSION_CODES.Q, Manifest.permission.WRITE_EXTERNAL_STORAGE) }),
    READ_STORAGE({
        sdkBelow(Build.VERSION_CODES.TIRAMISU, Manifest.permission.READ_EXTERNAL_STORAGE)
    });

    val manifestPermissions: List<String>
        get() = resolve()
}

private fun sdkFrom(minSdk: Int, vararg permissions: String): List<String> =
    if (Build.VERSION.SDK_INT >= minSdk) permissions.toList() else emptyList()

private fun sdkBelow(maxSdkExclusive: Int, vararg permissions: String): List<String> =
    if (Build.VERSION.SDK_INT < maxSdkExclusive) permissions.toList() else emptyList()

/**
 * The outcome of asking for a permission.
 *
 * Handle all three — the compiler enforces it when you `when` on this without an `else`:
 * ```
 * when (state.status) {
 *     PermissionStatus.Granted -> doTheThing()
 *     PermissionStatus.Denied -> state.launch()            // OS will still show the dialog
 *     PermissionStatus.PermanentlyDenied -> state.openAppSettings()  // dialog is suppressed
 * }
 * ```
 */
sealed interface PermissionStatus {
    data object Granted : PermissionStatus

    data object Denied : PermissionStatus

    data object PermanentlyDenied : PermissionStatus
}

```

**File: `core/util/src/main/res/values/strings.xml`**
```kotlin
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="cannot_be_empty">Tidak boleh kosong</string>
    <string name="must_not_contain_a_series_of_spaces">Tidak boleh mengandung deretan spasi</string>
    <string name="must_not_contain_spaces_at_start_or_end">Tidak boleh mengandung spasi di awal atau akhir</string>
    <string name="except_text">" selain "</string>
    <string name="cannot_contain_special_characters">Tidak boleh mengandung spesial karakter%1$s</string>
    <string name="cannot_contain_only_special_characters">Tidak boleh mengandung hanya isian spesial karakter</string>
    <string name="cannot_contain_only_values">Tidak boleh hanya mengandung nilai %1$s</string>
    <string name="cannot_contain_non_digit">Tidak boleh terisi selain angka</string>
    <string name="must_filled_3_digit">Harus terisi 3 digit</string>
    <string name="must_contain_length_characters">Harus terisi %1$d karakter</string>
    <string name="cannot_start_with_special_characters">Tidak boleh dimulai dengan karakter spesial</string>
    <string name="min_length_digit">Minimal harus %1$d digit angka</string>
    <string name="invalid_format">Format tidak sesuai. Silakan coba lagi.</string>
    <string name="must_contain_self_name">Hanya boleh mengandung nama pribadi.</string>
</resources>

```

**File: `core/util/src/test/kotlin/id/co/bri/brimons/core/ui/permission/AppPermissionTest.kt`**
```kotlin
package id.co.bri.brimons.core.ui.permission

import android.Manifest
import android.os.Build
import id.co.bri.brimons.core.util.permission.AppPermission
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Locks the SDK branching in [id.co.bri.brimons.core.util.permission.AppPermission]. Every entry
 * that varies by API level gets a case on both sides of its boundary — this is the regression net
 * for adding a new permission.
 */
@RunWith(RobolectricTestRunner::class)
class AppPermissionTest {

    // ---- constant entries -----------------------------------------------------------------

    @Test
    fun `CAMERA is the camera permission on every API level`() {
        AppPermission.CAMERA.manifestPermissions shouldContainExactly
            listOf(Manifest.permission.CAMERA)
    }

    @Test
    fun `LOCATION asks for fine and coarse together`() {
        AppPermission.LOCATION.manifestPermissions shouldContainExactly
            listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
    }

    @Test
    fun `PHONE_CALL needs the call state as well as the call itself`() {
        AppPermission.PHONE_CALL.manifestPermissions shouldContainExactly
            listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.CALL_PHONE)
    }

    @Test
    fun `CONTACTS and MICROPHONE are single permissions`() {
        AppPermission.CONTACTS.manifestPermissions shouldContainExactly
            listOf(Manifest.permission.READ_CONTACTS)
        AppPermission.MICROPHONE.manifestPermissions shouldContainExactly
            listOf(Manifest.permission.RECORD_AUDIO)
    }

    // ---- NOTIFICATION: introduced in 33 ----------------------------------------------------

    @Test
    @Config(sdk = [Build.VERSION_CODES.S_V2])
    fun `NOTIFICATION is implicit below API 33`() {
        AppPermission.NOTIFICATION.manifestPermissions.shouldBeEmpty()
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    fun `NOTIFICATION asks for POST_NOTIFICATIONS from API 33`() {
        AppPermission.NOTIFICATION.manifestPermissions shouldContainExactly
            listOf(Manifest.permission.POST_NOTIFICATIONS)
    }

    // ---- BLUETOOTH: runtime permissions introduced in 31 ------------------------------------

    @Test
    @Config(sdk = [Build.VERSION_CODES.R])
    fun `BLUETOOTH needs no runtime grant below API 31`() {
        AppPermission.BLUETOOTH.manifestPermissions.shouldBeEmpty()
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.S])
    fun `BLUETOOTH asks for scan and connect from API 31`() {
        AppPermission.BLUETOOTH.manifestPermissions shouldContainExactly
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    }

    // ---- WRITE_STORAGE: scoped storage removes the need at 29 --------------------------------

    @Test
    @Config(sdk = [Build.VERSION_CODES.P])
    fun `WRITE_STORAGE asks for the write grant below API 29`() {
        AppPermission.WRITE_STORAGE.manifestPermissions shouldContainExactly
            listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.Q])
    fun `WRITE_STORAGE is implicit from API 29 under scoped storage`() {
        AppPermission.WRITE_STORAGE.manifestPermissions.shouldBeEmpty()
    }

    // ---- READ_MEDIA: three-way split ---------------------------------------------------------

    @Test
    @Config(sdk = [Build.VERSION_CODES.P])
    fun `READ_MEDIA asks for read and write below API 29`() {
        AppPermission.READ_MEDIA.manifestPermissions shouldContainExactly
            listOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            )
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.Q])
    fun `READ_MEDIA asks for read only between API 29 and 32`() {
        AppPermission.READ_MEDIA.manifestPermissions shouldContainExactly
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    fun `READ_MEDIA asks for the granular media grants from API 33`() {
        AppPermission.READ_MEDIA.manifestPermissions shouldContainExactly
            listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    }
}

```

**File: `core/util/src/test/kotlin/id/co/bri/brimons/core/util/DateUtilTest.kt`**
```kotlin
package id.co.bri.brimons.core.util

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.mockk.unmockkAll
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class DateUtilTest :
    FunSpec({
        afterSpec { unmockkAll() }

        context("convertDateFormat with String formats") {
            test("should format correctly when input is valid without Z") {
                val result =
                    DateUtil.convertDateFormat(
                        "01-01-2023",
                        DateUtil.FORMAT_DD_MM_YYYY,
                        DateUtil.FORMAT_YYYY_MM_DD,
                    )
                result shouldBe "2023-01-01"
            }

            test(
                "should format without throwing exceptions when input is valid with Z (UTC formatting)"
            ) {
                val result =
                    DateUtil.convertDateFormat(
                        "2023-01-01T10:00:00.000Z",
                        DateUtil.FORMAT_ISO_8601,
                        DateUtil.FORMAT_YYYY_MM_DD,
                    )
                result.shouldNotBeNull()
            }

            test("should return null when input is null or empty") {
                DateUtil.convertDateFormat(
                        null,
                        DateUtil.FORMAT_DD_MM_YYYY,
                        DateUtil.FORMAT_YYYY_MM_DD,
                    )
                    .shouldBeNull()
                DateUtil.convertDateFormat(
                        "",
                        DateUtil.FORMAT_DD_MM_YYYY,
                        DateUtil.FORMAT_YYYY_MM_DD,
                    )
                    .shouldBeNull()
            }

            test("should return null due to parse exception when input string has invalid format") {
                val result =
                    DateUtil.convertDateFormat(
                        "invalid-date-string",
                        DateUtil.FORMAT_DD_MM_YYYY,
                        DateUtil.FORMAT_YYYY_MM_DD,
                    )
                result.shouldBeNull()
            }
        }

        context("convertDateFormat with SimpleDateFormat instances") {
            val inputSdf = SimpleDateFormat(DateUtil.FORMAT_DD_MM_YYYY, Locale.getDefault())
            val outputSdf = SimpleDateFormat(DateUtil.FORMAT_YYYY_MM_DD, Locale.getDefault())

            test("should format correctly when input is valid") {
                val result = DateUtil.convertDateFormat("15-05-2023", inputSdf, outputSdf)
                result shouldBe "2023-05-15"
            }

            test("should return null when input is null or empty") {
                DateUtil.convertDateFormat(null, inputSdf, outputSdf).shouldBeNull()
                DateUtil.convertDateFormat("", inputSdf, outputSdf).shouldBeNull()
            }

            test("should return null when input is unparseable") {
                val result = DateUtil.convertDateFormat("invalid", inputSdf, outputSdf)
                result.shouldBeNull()
            }
        }

        context("convertDateToString") {
            test("should convert Date to formatted string when date is valid") {
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val date = sdf.parse("2023-10-10")

                val result = DateUtil.convertDateToString(date, DateUtil.FORMAT_DD_MM_YYYY)
                result shouldBe "10-10-2023"
            }

            test("should return null when date is null") {
                DateUtil.convertDateToString(null, DateUtil.FORMAT_DD_MM_YYYY).shouldBeNull()
            }
        }

        context("convertStringToDate") {
            test("should return a valid Date object when string is valid") {
                val result = DateUtil.convertStringToDate("10-10-2023", DateUtil.FORMAT_DD_MM_YYYY)
                result.shouldNotBeNull()
                val formatted =
                    SimpleDateFormat(DateUtil.FORMAT_DD_MM_YYYY, Locale.getDefault()).format(result)
                formatted shouldBe "10-10-2023"
            }

            test(
                "should apply UTC TimeZone and return Date object when string contains Z (UTC indicator)"
            ) {
                val result =
                    DateUtil.convertStringToDate(
                        "2023-01-01T10:00:00.000Z",
                        DateUtil.FORMAT_ISO_8601,
                    )
                result.shouldNotBeNull()
            }

            test("should return null when string is null or empty") {
                DateUtil.convertStringToDate(null, DateUtil.FORMAT_DD_MM_YYYY).shouldBeNull()
                DateUtil.convertStringToDate("", DateUtil.FORMAT_DD_MM_YYYY).shouldBeNull()
            }

            test("should return null when string format is invalid") {
                val result = DateUtil.convertStringToDate("invalid", DateUtil.FORMAT_DD_MM_YYYY)
                result.shouldBeNull()
            }
        }

        context("getCurrentDate") {
            test(
                "should return a string matching the dd-MM-yyyy format when called with default format"
            ) {
                val result = DateUtil.getCurrentDate()
                result shouldMatch Regex("\\d{2}-\\d{2}-\\d{4}")
            }

            test(
                "should return a string matching yyyy-MM-dd format when called with custom format"
            ) {
                val result = DateUtil.getCurrentDate(DateUtil.FORMAT_YYYY_MM_DD)
                result shouldMatch Regex("\\d{4}-\\d{2}-\\d{2}")
            }
        }

        context("isDataInFuture") {
            val sdf =
                SimpleDateFormat(DateUtil.FORMAT_ISO_8601, Locale.getDefault()).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
            val referenceDate = sdf.parse("2023-05-15T10:00:00.000Z")!!

            test(
                "should return true when date string is in the future relative to the reference date"
            ) {
                val futureDateStr = "2023-05-16T10:00:00.000Z"
                val result = DateUtil.isDataInFuture(futureDateStr, referenceDate)
                result.shouldBeTrue()
            }

            test(
                "should return false when date string is in the past relative to the reference date"
            ) {
                val pastDateStr = "2023-05-14T10:00:00.000Z"
                val result = DateUtil.isDataInFuture(pastDateStr, referenceDate)
                result.shouldBeFalse()
            }

            test("should return false due to parse exception when date string is invalid") {
                val result = DateUtil.isDataInFuture("invalid-date", referenceDate)
                result.shouldBeFalse()
            }
        }

        context("isDateInPast") {
            val sdf =
                SimpleDateFormat(DateUtil.FORMAT_ISO_8601, Locale.getDefault()).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
            val referenceDate = sdf.parse("2023-05-15T10:00:00.000Z")!!

            test(
                "should return true when date string is in the past relative to the reference date"
            ) {
                val pastDateStr = "2023-05-14T10:00:00.000Z"
                val result = DateUtil.isDateInPast(pastDateStr, referenceDate)
                result.shouldBeTrue()
            }

            test(
                "should return false when date string is in the future relative to the reference date"
            ) {
                val futureDateStr = "2023-05-16T10:00:00.000Z"
                val result = DateUtil.isDateInPast(futureDateStr, referenceDate)
                result.shouldBeFalse()
            }

            test("should return false due to parse exception when date string is invalid") {
                val result = DateUtil.isDateInPast("invalid-date", referenceDate)
                result.shouldBeFalse()
            }
        }
    })

```

**File: `core/util/src/test/kotlin/id/co/bri/brimons/core/util/ext/CurrencyTest.kt`**
```kotlin
package id.co.bri.brimons.core.util.ext

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class CurrencyTest :
    FunSpec({
        context("String.toDoubleCurrency") {
            test("should parse string with dot as thousand separator") {
                "1.000".toDoubleCurrency() shouldBe 1000.0
                "1.234.567".toDoubleCurrency() shouldBe 1234567.0
            }

            test("should parse string with comma as decimal separator") {
                "1000,50".toDoubleCurrency() shouldBe 1000.50
            }

            test("should parse string with both dot and comma") {
                "1.234.567,89".toDoubleCurrency() shouldBe 1234567.89
            }

            test("should return 0.0 for unparseable strings") {
                "abc".toDoubleCurrency() shouldBe 0.0
                "".toDoubleCurrency() shouldBe 0.0
            }
        }

        context("Double.toCurrencyFormat") {
            test("should format integer parts with dots") {
                1000.0.toCurrencyFormat() shouldBe "1.000"
                1000000.0.toCurrencyFormat() shouldBe "1.000.000"
            }

            test("should format decimal parts with comma, padding to 2 digits") {
                1000.5.toCurrencyFormat() shouldBe "1.000,50"
                1000.99.toCurrencyFormat() shouldBe "1.000,99"
            }

            test("should round half up to 2 decimal places") {
                1000.555.toCurrencyFormat() shouldBe "1.000,56"
                1000.554.toCurrencyFormat() shouldBe "1.000,55"
            }

            test("should format zero correctly") { 0.0.toCurrencyFormat() shouldBe "0" }
        }

        context("Double.toLocalizedCurrencyString") {
            test("should format whole numbers with grouping separator") {
                1000.0.toLocalizedCurrencyString() shouldBe "1.000"
                1234567.0.toLocalizedCurrencyString() shouldBe "1.234.567"
            }

            test("should format decimals with comma without trailing zeroes due to pattern") {
                1000.5.toLocalizedCurrencyString() shouldBe "1.000,5"
                1000.55.toLocalizedCurrencyString() shouldBe "1.000,55"
            }

            test("should round off more than 2 decimal places") {
                1000.556.toLocalizedCurrencyString() shouldBe "1.000,56"
            }
        }

        context("Double.toCurrencyString") {
            test("should return plain string with exactly 2 decimal places") {
                1000.0.toCurrencyString() shouldBe "1000.00"
                1000.5.toCurrencyString() shouldBe "1000.50"
                0.0.toCurrencyString() shouldBe "0.00"
            }

            test("should round half up") {
                1000.556.toCurrencyString() shouldBe "1000.56"
                1000.554.toCurrencyString() shouldBe "1000.55"
            }
        }

        context("Number.toIdr") {
            test("should prepend Rp and format whole numbers") { 1000.toIdr() shouldBe "Rp1.000" }

            test("should format decimal numbers properly with Indonesian locale") {
                1000.5.toIdr() shouldBe "Rp1.000,5"
                1000.55.toIdr() shouldBe "Rp1.000,55"
            }
        }

        context("Long.toRupiah") {
            test("should return Rp0 for zero or negative numbers") {
                0L.toRupiah() shouldBe "Rp0"
                (-10L).toRupiah() shouldBe "Rp0"
                (-1000L).toRupiah() shouldBe "Rp0"
            }

            test("should prepend Rp and apply thousand separator for positive numbers") {
                1000L.toRupiah() shouldBe "Rp1.000"
                1234567L.toRupiah() shouldBe "Rp1.234.567"
            }
        }
    })

```

**File: `core/util/src/test/kotlin/id/co/bri/brimons/core/util/ext/GsonExtTest.kt`**
```kotlin
package id.co.bri.brimons.core.util.ext

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private data class GsonSample(val id: Int, val label: String, val flags: List<String>)

class GsonExtTest :
    FunSpec({
        context("Any.serialize") {
            test("emits JSON for a data class") {
                val obj = GsonSample(id = 3, label = "label", flags = listOf("a", "b"))

                val json = obj.serialize()

                json.shouldNotBeNull()
                json shouldContain "\"id\":3"
                json shouldContain "\"label\":\"label\""
                json shouldContain "\"flags\":[\"a\",\"b\"]"
            }

            test("handles primitive receivers") {
                123.serialize() shouldBe "123"
                "hello".serialize() shouldBe "\"hello\""
            }

            test("emits the string \"null\" for a null receiver") {
                val nothing: Any? = null
                nothing.serialize() shouldBe "null"
            }
        }

        context("String.deserialize") {
            test("round-trips through serialize/deserialize") {
                val original = GsonSample(id = 5, label = "label", flags = listOf("x"))

                original.serialize()?.deserialize<GsonSample>() shouldBe original
            }

            test("returns null for garbage input") {
                "definitely not json".deserialize<GsonSample>().shouldBeNull()
            }

            test("returns null when JSON shape does not match the target type") {
                "[1,2,3]".deserialize<GsonSample>().shouldBeNull()
            }

            test("decodes empty JSON object into a partially-filled instance") {
                "{}".deserialize<GsonSample>().shouldNotBeNull()
            }
        }
    })

```

**File: `core/util/src/test/kotlin/id/co/bri/brimons/core/util/ext/InputValidatorTest.kt`**
```kotlin
package id.co.bri.brimons.core.util.ext

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue

class InputValidatorTest :
    FunSpec({
        context("String.isAlphaNumeric") {
            test("should return true for purely alphabetic string") {
                "HelloWorld".isAlphaNumeric().shouldBeTrue()
            }

            test("should return true for purely numeric string") {
                "1234567890".isAlphaNumeric().shouldBeTrue()
            }

            test("should return true for alphanumeric string without spaces") {
                "Hello123".isAlphaNumeric().shouldBeTrue()
            }

            test("should return true for alphanumeric string with spaces") {
                "Hello World 123".isAlphaNumeric().shouldBeTrue()
            }

            test("should return true for string with only spaces") {
                "   ".isAlphaNumeric().shouldBeTrue()
            }

            test("should return true for empty string") { "".isAlphaNumeric().shouldBeTrue() }

            test("should return false for string with punctuation") {
                "Hello, World!".isAlphaNumeric().shouldBeFalse()
            }

            test("should return false for string with special characters") {
                "Hello@123#".isAlphaNumeric().shouldBeFalse()
            }
        }
    })

```

**File: `core/util/src/test/kotlin/id/co/bri/brimons/core/util/ext/JsonExtTest.kt`**
```kotlin
package id.co.bri.brimons.core.util.ext

import android.util.Log
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.serialization.Serializable

@Serializable
private data class SamplePayload(
    val userId: Int,
    val displayName: String? = null,
    val isActive: Boolean,
)

class JsonExtTest :
    FunSpec({
        beforeSpec {
            mockkStatic(Log::class)
            every { Log.e(any(), any<String>()) } returns 0
        }
        afterSpec { unmockkAll() }

        context("Any.toJsonString") {
            test("encodes with snake_case keys") {
                val payload =
                    SamplePayload(userId = 42, displayName = "displayName", isActive = true)

                val json = payload.toJsonString()

                json shouldContain "\"user_id\":42"
                json shouldContain "\"display_name\":\"displayName\""
                json shouldContain "\"is_active\":true"
            }

            test("omits null fields") {
                val payload = SamplePayload(userId = 1, displayName = null, isActive = false)

                val json = payload.toJsonString()

                json shouldContain "\"user_id\":1"
                json.contains("display_name") shouldBe false
            }

            test("returns empty string when encoding fails") { Any().toJsonString() shouldBe "" }
        }

        context("String.fromJsonString") {
            test("decodes valid JSON") {
                val raw = """{"user_id":7,"display_name":"displayName","is_active":true}"""

                raw.fromJsonString<SamplePayload>() shouldBe
                    SamplePayload(userId = 7, displayName = "displayName", isActive = true)
            }

            test("returns null for invalid JSON") {
                "not-json-at-all".fromJsonString<SamplePayload>().shouldBeNull()
            }

            test("returns null when required fields are missing") {
                "{}".fromJsonString<SamplePayload>().shouldBeNull()
            }

            test("ignores unknown keys") {
                val raw =
                    """{"user_id":9,"display_name":"displayName","is_active":false,"extra":"x"}"""

                raw.fromJsonString<SamplePayload>() shouldBe
                    SamplePayload(userId = 9, displayName = "displayName", isActive = false)
            }
        }
    })

```

**File: `core/util/src/test/kotlin/id/co/bri/brimons/core/util/ext/NumberTest.kt`**
```kotlin
package id.co.bri.brimons.core.util.ext

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class NumberTest :
    FunSpec({
        context("String.thousandSeparator") {
            test("should add dots as thousand separators for integer strings") {
                "1000".thousandSeparator() shouldBe "1.000"
                "1234567".thousandSeparator() shouldBe "1.234.567"
            }

            test("should format decimal parts with a comma") {
                "1000.5".thousandSeparator() shouldBe "1.000,5"
                "1000.55".thousandSeparator() shouldBe "1.000,55"
            }

            test("should round to a maximum of 2 decimal places") {
                "1000.556".thousandSeparator() shouldBe "1.000,56"
            }

            test("should return '0' for non-numeric or empty strings due to fallback logic") {
                "invalid".thousandSeparator() shouldBe "0"
                "".thousandSeparator() shouldBe "0"
            }
        }
    })

```

**File: `core/util/src/test/kotlin/id/co/bri/brimons/core/util/ext/StringExtensionTest.kt`**
```kotlin
package id.co.bri.brimons.core.util.ext

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue

class StringExtensionTest :
    FunSpec({
        context("String.isBriBankCode") {
            test("returns true for the BRI bank code") { "002".isBriBankCode().shouldBeTrue() }

            test("returns false for other bank codes") {
                "008".isBriBankCode().shouldBeFalse()
                "014".isBriBankCode().shouldBeFalse()
                "009".isBriBankCode().shouldBeFalse()
            }

            test("returns false for empty and whitespace strings") {
                "".isBriBankCode().shouldBeFalse()
                " ".isBriBankCode().shouldBeFalse()
                " 002 ".isBriBankCode().shouldBeFalse()
            }

            test("comparison is by exact string") {
                "2".isBriBankCode().shouldBeFalse()
                "0002".isBriBankCode().shouldBeFalse()
            }
        }
    })

```

**File: `core/util/src/test/kotlin/id/co/bri/brimons/core/util/helper/DateTimeTest.kt`**
```kotlin
package id.co.bri.brimons.core.util.helper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch

class DateTimeTest :
    FunSpec({
        context("getDateTimeSecond") {
            test(
                "should return formatted date string representing current time plus given seconds"
            ) {
                val result = getDateTimeSecond(3600)
                result shouldMatch Regex("\\d{2} [a-zA-Z]{3} \\d{4}, \\d{2}:\\d{2}")
            }
        }

        context("getCountDownTime") {
            test("should format seconds into MM:SS correctly") {
                getCountDownTime(0) shouldBe "00:00"
                getCountDownTime(9) shouldBe "00:09"
                getCountDownTime(60) shouldBe "01:00"
                getCountDownTime(125) shouldBe "02:05"
                getCountDownTime(3600) shouldBe "60:00"
            }
        }

        context("isDateExpired") {
            test("should return true if the date is in the past") {
                val pastDate = "01-01-2000"
                isDateExpired(pastDate, "dd-MM-yyyy").shouldBeTrue()
            }

            test("should return false if the date is in the future") {
                val futureDate = "01-01-2100"
                isDateExpired(futureDate, "dd-MM-yyyy").shouldBeFalse()
            }

            test("should return false if the date string is invalid and fails to parse") {
                isDateExpired("invalid-date", "dd-MM-yyyy").shouldBeFalse()
            }
        }

        context("convertDate") {
            test("should format the date from input format to output format") {
                val result =
                    convertDate(date = "2023-10-15", input = "yyyy-MM-dd", output = "dd/MM/yyyy")
                result shouldBe "15/10/2023"
            }

            test("should return original string if parsing fails due to bad date") {
                val result =
                    convertDate(date = "invalid-date", input = "yyyy-MM-dd", output = "dd/MM/yyyy")
                result shouldBe "invalid-date"
            }

            test("should return original string if format is mismatched") {
                val result =
                    convertDate(date = "15-10-2023", input = "yyyy/MM/dd", output = "dd/MM/yyyy")
                result shouldBe "15-10-2023"
            }
        }
    })

```

**File: `core/util/src/test/kotlin/id/co/bri/brimons/core/util/helper/RandomTest.kt`**
```kotlin
package id.co.bri.brimons.core.util.helper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeExactly
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch

class RandomTest :
    FunSpec({
        context("generateRandomString") {
            test("should return a non-empty string") {
                val result = generateRandomString()
                result shouldNotBe ""
            }

            test("should generate a 64-character hexadecimal string (SHA-256 length)") {
                val result = generateRandomString()

                result.length shouldBeExactly 64

                result shouldMatch Regex("^[a-f0-9]+$")
            }

            test("should generate unique strings on subsequent calls") {
                val result1 = generateRandomString()
                val result2 = generateRandomString()

                result1 shouldNotBe result2
            }

            test("should generate completely unique strings over multiple iterations") {
                val results = List(100) { generateRandomString() }

                val uniqueResults = results.toSet()

                uniqueResults.size shouldBeExactly 100
            }
        }
    })

```

**Expected Output:**
- A robust `core` module providing necessary infrastructure without business logic, strictly adhering to the exact implementation above. Do not invent new structure.
