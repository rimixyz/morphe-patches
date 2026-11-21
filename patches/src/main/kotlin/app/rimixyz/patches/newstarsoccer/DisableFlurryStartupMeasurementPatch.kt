package app.rimixyz.patches.newstarsoccer

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.patch
import app.rimixyz.patches.shared.Constants.COMPATIBILITY_NEWSTARSOCER

/**
 * Neutraliza a medição de inicialização feita pelo FlurryContentProvider.
 *
 * O provider declara a URI "performance" e registra callbacks de ciclo de vida com tempo de
 * arranque, memória usada e total. Em vez de remover a declaração do manifesto — o que deixaria
 * consumidores da URI sem provider — o onCreate retorna true sem registrar nada. Assim o
 * componente permanece disponível e o arranque do jogo não é afetado.
 *
 * Risco: se algum consumidor consultar a URI, receberá um cursor vazio; nenhum consumidor direto
 * foi encontrado no DEX e o caminho do Flurry depende da sessão (bloqueada pelo patch separado).
 */
@Suppress("unused")
val disableFlurryStartupMeasurementPatch = patch(
    name = "Disable Flurry startup measurement",
    description = "Impede que o provider do Flurry capture tempo de inicialização e uso de memória, mantendo o componente declarado.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_NEWSTARSOCER)

    execute {
        verifyNewStarSoccerTarget()

        // onCreate()Z devolve v3 = 1 no caminho original; devolve o mesmo sem registrar nada.
        FlurryProviderOnCreateFingerprint.method.addInstructions(
            0,
            """
                const/4 v3, 0x1
                return v3
            """.trimIndent(),
        )
    }
}
