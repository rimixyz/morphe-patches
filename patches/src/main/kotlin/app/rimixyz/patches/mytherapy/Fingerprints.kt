package app.rimixyz.patches.mytherapy

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.string

/**
 * Fingerprints para MyTherapy 4.16.2 / versionCode 4016002 (eu.smartpatient.mytherapy).
 *
 * Todas as âncoras foram confirmadas nos DEX extraídos do base.apk analisado
 * (SHA-256 do bundle 953265d0c5b12b3cc40ac81765acd3c0123b13369b820940352cc865e8a855a2).
 * Nenhuma usa ordinal puro ou offset: combinam classe, assinatura e chamadas ou campos estáveis.
 */

private const val NATIVE_AD_MANAGER =
    "Leu/smartpatient/mytherapy/multiplatform/feature/appmonetization/infrastructure/nativead/NativeAdManager;"
private const val LOAD_BANNER_AD =
    "Leu/smartpatient/mytherapy/multiplatform/feature/appmonetization/presentation/usecase/AndroidLoadBannerAdUseCase;"
private const val AD_PLACEMENT =
    "Leu/smartpatient/mytherapy/multiplatform/feature/appmonetization/api/model/AdPlacement;"
private const val MOBILE_ADS_COMPANION =
    "Lcom/google/android/libraries/ads/mobile/sdk/MobileAds\$Companion;"
private const val NATIVE_AD_LOADER_COMPANION =
    "Lcom/google/android/libraries/ads/mobile/sdk/nativead/NativeAdLoader\$Companion;"
private const val AD_VIEW =
    "Lcom/google/android/libraries/ads/mobile/sdk/banner/AdView;"
private const val HAS_ANALYTICS_CONSENT =
    "Leu/smartpatient/mytherapy/multiplatform/feature/legalconsentsanddocuments/domain/usecase/HasAnalyticsConsentUseCaseImpl;"
private const val IS_CONSENT_STORED =
    "Leu/smartpatient/mytherapy/multiplatform/feature/legalconsentsanddocuments/api/usecase/IsConsentStoredUseCase;"
private const val IS_CONSENT_ACCEPTED =
    "Leu/smartpatient/mytherapy/multiplatform/feature/legalconsentsanddocuments/api/usecase/IsConsentAcceptedUseCase;"

/**
 * Consulta de consentimento de analytics. A string da chave legal e as duas interfaces do
 * repositório ancoram o método de forma única em classes3.dex.
 */
internal object HasAnalyticsConsentFingerprint : Fingerprint(
    definingClass = HAS_ANALYTICS_CONSENT,
    name = "invoke",
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Lkotlin/coroutines/Continuation;"),
    filters = listOf(
        string("LEGAL_CONSENT_HEALTH_DATA_ANALYTICS_MYTHERAPY"),
        methodCall(definingClass = IS_CONSENT_STORED, name = "invoke"),
        methodCall(definingClass = IS_CONSENT_ACCEPTED, name = "invoke"),
    ),
)

/**
 * Carga de anúncio nativo. O corpo chama `MobileAds.isInitialized()`, consulta o cache interno e
 * termina em `NativeAdLoader.load`. O `clear()` (destruição dos objetos) fica fora deste alvo.
 */
internal object NativeAdRequestFingerprint : Fingerprint(
    definingClass = NATIVE_AD_MANAGER,
    name = "request",
    returnType = "Ljava/lang/Object;",
    parameters = listOf(AD_PLACEMENT, "Lkotlin/coroutines/Continuation;"),
    filters = listOf(
        methodCall(definingClass = MOBILE_ADS_COMPANION, name = "isInitialized"),
        fieldAccess(definingClass = NATIVE_AD_MANAGER, name = "f", type = "Ljava/util/Map;"),
        methodCall(definingClass = NATIVE_AD_LOADER_COMPANION, name = "load"),
    ),
)

/**
 * Carga de banner programático. A assinatura com `AdView` e `AdLoadCallback` distingue este
 * caminho do caso de uso de intersticial e de recompensado.
 */
internal object BannerLoadFingerprint : Fingerprint(
    definingClass = LOAD_BANNER_AD,
    name = "invoke",
    returnType = "Ljava/lang/Object;",
    parameters = listOf(
        AD_VIEW,
        AD_PLACEMENT,
        "Lcom/google/android/libraries/ads/mobile/sdk/banner/AdSize;",
        "Lcom/google/android/libraries/ads/mobile/sdk/common/AdLoadCallback;",
        "Lkotlin/coroutines/Continuation;",
    ),
    filters = listOf(
        methodCall(definingClass = AD_VIEW, name = "loadAd"),
    ),
)
