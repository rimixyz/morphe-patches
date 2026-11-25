package app.rimixyz.patches.xrecorder

/**
 * Aviso comum a todos os patches do XRecorder, exibido na descrição de cada um.
 *
 * Baseado em teste real no A15: a aba Editar fecha quando o app carrega o motor
 * nativo de vídeo (FFmpeg/player). O fluxo primordial de gravação funciona com
 * o estado Pro liberado. Isso não é feature server-side nem erro dos patches.
 */
internal const val XRECORDER_KNOWN_ISSUE =
    "Known issue: the Edit tab closes in tests while loading the native video engine; " +
        "core screen recording works with Pro unlocked."
