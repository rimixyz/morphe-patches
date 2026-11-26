package app.rimixyz.patches.pocolauncher

import app.morphe.patcher.patch.packageMetadata

/**
 * Barreira de segurança para o patch do POCO Launcher.
 *
 * As fingerprints e reescritas foram ancoradas na release
 * `com.mi.android.globallauncher_RELEASE-7.50.06.2599-10091027`
 * (SHA-256 `6b7404669c2f5005d3a3e9e12a42fe9be5c92566730f7d7f94469d0fa87b5ace`).
 * A compatibilidade restringe pacote, versão, versionCode, minSdk e certificado;
 * a verificação é repetida no início do `execute` para evitar gerar um APK
 * inconsistente caso o patch seja forçado sobre outro alvo.
 */
internal fun verifyPocoLauncherTarget() {
    val metadata = packageMetadata
    check(
        metadata.packageName == "com.mi.android.globallauncher" &&
            metadata.versionName == "RELEASE-7.50.06.2599-10091027" &&
            metadata.versionCode == "750062599"
    ) {
        "POCO Launcher: alvo inesperado " +
            "(${metadata.packageName} ${metadata.versionName} versionCode=${metadata.versionCode}); " +
            "esperado com.mi.android.globallauncher RELEASE-7.50.06.2599-10091027 versionCode=750062599"
    }
}
