package app.rimixyz.patches.newstarsoccer

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.patch
import app.rimixyz.patches.shared.Constants.COMPATIBILITY_NEWSTARSOCER

/**
 * Faz o jogo consultar o estado local de compra como "já possuído".
 *
 * A cadeia confirmada no DEX é:
 *   c_GoogleIAPComponent.p_HasPurchasedProduct(String)Z
 *     -> GooglePlayBB.HasPurchased(String)Z
 *       -> GooglePlayInvent.HasPending(String)Z  (inventário em memória da Play)
 *
 * O hook é o mais próximo da decisão e o menor possível: um único retorno booleano no wrapper
 * da ponte Android. p_CanConsumeProduct continua usando o inventário real, para não falsificar
 * consumo de itens. Nenhuma resposta da Play, recibo ou token é fabricado.
 *
 * Limite documentado: o repasse final do benefício para o estado salvo da partida depende da
 * camada Monkey (c_ProductOwned / c_Product.p_Owned), não confirmada em execução nesta amostra.
 */
@Suppress("unused")
val unlockProPatch = patch(
    name = "Unlock Pro features",
    description = "Reporta localmente que os produtos pagos já foram adquiridos, liberando Licença Pro, remoção de anúncios e demais itens sem tocar em recibos ou no servidor.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_NEWSTARSOCER)

    execute {
        verifyNewStarSoccerTarget()

        // p_HasPurchasedProduct tem 2 registros: v0 temporário, v1 = String, v2 = retorno.
        GoogleIAPHasPurchasedFingerprint.method.addInstructions(
            0,
            """
                const/4 v2, 0x1
                return v2
            """.trimIndent(),
        )
    }
}
