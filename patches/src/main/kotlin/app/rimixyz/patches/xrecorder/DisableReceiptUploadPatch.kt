package app.rimixyz.patches.xrecorder

import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.returnEarly
import app.rimixyz.patches.shared.Constants.COMPATIBILITY_XRECORDER_EXPERIMENTAL

/**
 * EXPERIMENTAL — nao testado em aparelho real.
 *
 * Redireciona o envio de token de assinatura do XRecorder 2.5.6.1 para loopback.
 * O endpoint original, lido em `s13.j()`, e:
 *   https://cast.inshot.cc/v2/subscription/token/
 *
 * So troca a URL; nao mexe no fluxo do Google Play Billing nem no estado premium
 * local (quem decide o consumo diario e `w45.b("kmgJSgyY", false)`).
 *
 * ATENCAO: so usar se a bancada provar que o servidor remoto revoga o beneficio.
 * Sem isso, este patch apenas mata telemetria de compra e pode gerar erros de rede.
 */
@Suppress("unused")
val disableReceiptUploadInXRecorderPatch = patch(
    name = "Disable receipt upload",
    description = "Blocks subscription token uploads. Server-side entitlements stay unchanged.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_XRECORDER_EXPERIMENTAL)

    execute {
        verifyXRecorderTarget()

        ReceiptEndpointFingerprint.method.returnEarly("http://127.0.0.1:9/subscription/token/")
    }
}
