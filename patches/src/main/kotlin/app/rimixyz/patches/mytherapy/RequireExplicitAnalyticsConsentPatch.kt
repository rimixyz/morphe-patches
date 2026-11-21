package app.rimixyz.patches.mytherapy

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.patch
import app.rimixyz.patches.shared.Constants.COMPATIBILITY_MYTHERAPY

/**
 * Faz o consentimento de analytics exigir uma escolha explícita.
 *
 * No bytecode original, `HasAnalyticsConsentUseCaseImpl.invoke` consulta `IsConsentStoredUseCase`;
 * quando não há registro, desvia para `Boxing.boxBoolean(v5)` com `v5 == 1` e devolve `true`.
 * `TrackEventUseCaseImpl$b.invokeSuspend` interpreta `false` como "não enviar evento".
 *
 * O patch insere `const/4 v5, 0x0` imediatamente antes do `boxBoolean`, de modo que apenas o
 * caminho "sem registro" passe a devolver `false`. Aceitação e recusa já registradas continuam
 * usando `IsConsentAcceptedUseCase` e não são alteradas; a máquina de estados da coroutine
 * também permanece intacta porque `v5` é redefinido depois de ter servido como marcador de estado.
 *
 * Risco registrado: a ordem entre onboarding, auto-init e primeiros eventos só pode ser medida
 * em aparelho (T4/T5); o patch vem habilitado por padrão.
 */
@Suppress("unused")
val requireExplicitAnalyticsConsentInMyTherapyPatch = patch(
    name = "Require explicit analytics consent in MyTherapy",
    description = "Treats an unregistered analytics consent as opt-out, while preserving recorded acceptance and refusal.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MYTHERAPY)

    execute {
        verifyMyTherapyTarget()

        HasAnalyticsConsentFingerprint.method.addInstructions(49, "const/4 v5, 0x0")
    }
}
