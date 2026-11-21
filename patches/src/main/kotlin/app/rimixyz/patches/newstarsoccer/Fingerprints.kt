package app.rimixyz.patches.newstarsoccer

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string

/**
 * Fingerprints para New Star Soccer 4.34 / versionCode 142 (com.newstargames.newstarsoccer).
 *
 * Todas as âncoras foram confirmadas nos quatro DEX do base.apk analisado (SHA-256 do bundle
 * bc76b635a3ac82967e6973273485abb27796b67975653ad7827691211bb796c3). Nenhuma fingerprint usa
 * ordinal puro ou offset: todas combinam classe, assinatura e uma String ou chamada de SDK vizinha.
 */

private const val GOOGLE_IAP_COMPONENT =
    "Lcom/newstargames/newstarsoccer/c_GoogleIAPComponent;"
private const val GOOGLE_PLAY_BB = "Lcom/newstargames/newstarsoccer/GooglePlayBB;"
private const val ADMOB = "Lcom/newstargames/newstarsoccer/AdMob;"
private const val FYBER = "Lcom/newstargames/newstarsoccer/Fyber;"
private const val FAKE_INTERSTITIAL = "Lcom/newstargames/newstarsoccer/c_TScreen_FakeInterstitial;"
private const val BB_FLURRY = "Lcom/newstargames/newstarsoccer/BBFlurry;"
private const val FLURRY_PROVIDER = "Lcom/flurry/android/agent/FlurryContentProvider;"

/** Gate local de propriedade: c_GoogleIAPComponent -> GooglePlayBB.HasPurchased. */
internal object GoogleIAPHasPurchasedFingerprint : Fingerprint(
    definingClass = GOOGLE_IAP_COMPONENT,
    name = "p_HasPurchasedProduct",
    returnType = "Z",
    parameters = listOf("Ljava/lang/String;"),
    filters = listOf(
        methodCall(definingClass = GOOGLE_PLAY_BB, name = "HasPurchased"),
    ),
)

/** Carregamento do intersticial do AdMob (AdMob$4.run -> InterstitialAd.load). */
internal object AdMobInterstitialLoadFingerprint : Fingerprint(
    definingClass = "Lcom/newstargames/newstarsoccer/AdMob\$4;",
    name = "run",
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/google/android/gms/ads/interstitial/InterstitialAd;",
            name = "load",
        ),
    ),
)

/** Exibição do intersticial do AdMob (AdMob$6.run -> InterstitialAd.show). */
internal object AdMobInterstitialShowFingerprint : Fingerprint(
    definingClass = "Lcom/newstargames/newstarsoccer/AdMob\$6;",
    name = "run",
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/google/android/gms/ads/interstitial/InterstitialAd;",
            name = "show",
        ),
    ),
)

/**
 * Preload do intersticial da Fyber. A assinatura com String é deliberada: o caminho recompensado
 * usa PreLoadRewardedAdInternal(String) e deve continuar intacto.
 */
internal object FyberInterstitialPreloadFingerprint : Fingerprint(
    definingClass = FYBER,
    name = "PreLoadAdInternal",
    returnType = "V",
    parameters = listOf("Ljava/lang/String;"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/fyber/inneractive/sdk/external/InneractiveAdSpot;",
            name = "requestAd",
        ),
    ),
)

/** Exibição do intersticial da Fyber (separado de DisplayRewardedAdInternal). */
internal object FyberInterstitialShowFingerprint : Fingerprint(
    definingClass = FYBER,
    name = "DisplayAdInternal",
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/fyber/inneractive/sdk/external/InneractiveFullscreenUnitController;",
            name = "show",
        ),
    ),
)

/** Tela promocional interna. Strings próprias ancoram o método (I)Z. */
internal object FakeInterstitialSetupFingerprint : Fingerprint(
    definingClass = FAKE_INTERSTITIAL,
    name = "m_SetUpScreen",
    returnType = "Z",
    parameters = listOf("I"),
    filters = listOf(
        string("TScreen_FakeInterstitial.SetUpScreen"),
        string("fake-interstitial"),
    ),
)

/** Início da sessão de Analytics do Flurry feito pelo wrapper Monkey. */
internal object FlurrySessionStartFingerprint : Fingerprint(
    definingClass = BB_FLURRY,
    name = "onStartSession",
    returnType = "V",
    parameters = listOf("Ljava/lang/String;"),
    filters = listOf(
        string("Monkey|Flurry"),
        string("Flurry StartSession"),
        methodCall(
            definingClass = "Lcom/flurry/android/FlurryAgent\$Builder;",
            name = "build",
        ),
    ),
)

/** Auto-init do provider do Flurry que mede tempo de startup e memória. */
internal object FlurryProviderOnCreateFingerprint : Fingerprint(
    definingClass = FLURRY_PROVIDER,
    name = "onCreate",
    returnType = "Z",
    parameters = emptyList(),
    filters = listOf(
        string("performance"),
        string("FlurryContentProvider"),
    ),
)
