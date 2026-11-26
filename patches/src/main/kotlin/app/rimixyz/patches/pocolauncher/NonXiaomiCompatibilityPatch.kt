package app.rimixyz.patches.pocolauncher

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.classDefForEach
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.resourceFile
import app.morphe.patcher.patch.resourceXml
import app.morphe.patcher.util.addInstructionsAtControlFlowLabel
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import app.rimixyz.patches.shared.Constants.COMPATIBILITY_POCOLAUNCHER
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference

private const val EXTENSION_PREFIX = "Lapp/rimixyz/extension/pocolauncher/"
private const val COMPAT_HELPER = "${EXTENSION_PREFIX}PocoCompatHelper;"
private const val SETTINGS_COMPAT = "${EXTENSION_PREFIX}PocoSettingsCompat;"

private const val OWN_WRITE_SETTINGS_PERMISSION =
    "com.mi.android.globallauncher.permission.WRITE_SETTINGS"

private val CONFLICTING_PERMISSIONS = setOf(
    "com.android.launcher.permission.INSTALL_SHORTCUT",
    "com.android.launcher.permission.READ_SETTINGS",
    "com.android.launcher.permission.WRITE_SETTINGS",
    "com.miui.miwallpaper.permission.BROADCAST",
)

private val SET_FRAME_BASE_CLASSES = setOf(
    "Lcom/miui/launcher/views/LauncherViewGroup;",
    "Lcom/miui/home/customview/LauncherFrameLayout;",
    "Lcom/miui/launcher/views/LauncherRelativeLayout;",
    "Lcom/miui/launcher/views/LauncherWidgetHostView;",
)

private val SET_FRAME_SUBCLASSES = setOf(
    "Lcom/miui/home/launcher/hotseats/HotSeatsScreenViewContent;",
    "Lcom/miui/home/launcher/gadget/Gadget;",
    "Lcom/miui/home/ItemIcon;",
    "Lcom/miui/home/launcher/maml/MaMlWidgetView;",
    "Lcom/miui/home/launcher/servicedeliver/ServiceDeliveryWidgetView;",
)

private val SET_FRAME_SUPER_CALLERS = setOf(
    "Lcom/miui/home/launcher/UninstallDialog;",
    "Lcom/miui/home/launcher/hotseats/HotSeatsScreenViewContent;",
    "Lcom/miui/home/launcher/maml/MaMlWidgetView;",
)

private val BLOCKED_VIEW_SET_FRAME_OWNERS = setOf(
    "Landroid/view/View;",
    "Landroid/view/ViewGroup;",
    "Landroid/widget/FrameLayout;",
    "Landroid/widget/RelativeLayout;",
    "Landroid/appwidget/AppWidgetHostView;",
)

private val SETTINGS_NAMESPACE_PREFIX = mapOf(
    "Landroid/provider/Settings\$System;" to "system",
    "Landroid/provider/Settings\$Global;" to "global",
    "Landroid/provider/Settings\$Secure;" to "secure",
)

private val REWRITTEN_SETTINGS_METHODS = setOf(
    "putInt",
    "putLong",
    "putFloat",
    "putString",
    "getInt",
    "getLong",
    "getFloat",
    "getString",
    "getIntForUser",
)

/**
 * Permite instalar e executar o POCO Launcher (HyperOS/MIUI release 7.50.06.2599)
 * sem root em aparelhos Android 14+ fora do ecossistema Xiaomi (Samsung One UI,
 * Pixel, Motorola, etc.).
 *
 * O patch atua em quatro frentes integradas:
 *  1. **Manifesto instalável e abrível:** remove `android:sharedUserId`,
 *     `android:process=""`, permissões duplicadas de sistema/OEM (`READ_SETTINGS`,
 *     `WRITE_SETTINGS`, `INSTALL_SHORTCUT`, `SYSTEM_TOOLS`, `miwallpaper.BROADCAST`),
 *     revincula `android:readPermission` dos providers internos à permissão própria
 *     do pacote e adiciona `CATEGORY_LAUNCHER` na `SplashActivity`.
 *  2. **Shims do framework MIUI/HyperOS e `libpag`:** injeta a extensão dedicada
 *     `extensions/poco-launcher.mpe` com ~62 classes proprietárias ausentes fora de
 *     ROMs Xiaomi (`miui.*`, `android.provider.MiuiSettings`, `org.libpag.*`, etc.),
 *     sem tocar em nenhuma classe AOSP do `BootClassLoader`.
 *  3. **Substituição de hidden APIs bloqueadas no Android 14/15/16 (`targetSdk=35`):**
 *     refatora `View.setFrame` (`max-target-p`) nas 4 classes-base de layout para
 *     `miuiSetFrame` + `View.setLeftTopRightBottom` + `OnLayoutChangeListener`
 *     (preservando `AutoLayoutAnimation`, `UninstallDialog` e `ScreenView`), neutraliza
 *     `InteractionJankMonitor` (`blocked`), `ThreadedRenderer.createHardwareBitmap`,
 *     `Intent.getSender`, `Window.setExtraFlags`, `Display.getDisplayInfo`,
 *     `AppOpsManager.noteOpNoThrow`, `acquireUnstableProvider`, `View.getThreadedRenderer`,
 *     `WindowManagerGlobal.trimMemory`, `com.android.internal.R$styleable.View` e
 *     redireciona todas as chamadas a `Settings.System/Global/Secure` para
 *     `PocoSettingsCompat` com persistência local em `SharedPreferences`.
 *  4. **Integração nativa em aparelhos não-Xiaomi:** ativa o diálogo padrão
 *     `ACTION_APPWIDGET_BIND` para widgets Android (incluindo configuráveis),
 *     desinstalação via `ACTION_DELETE`, seletor de papel de parede do sistema via
 *     `ACTION_SET_WALLPAPER` e adaptação de cor do papel de parede via
 *     `WallpaperManager.getWallpaperColors` + `ACTION_WALLPAPER_CHANGED`.
 */
@Suppress("unused")
val nonXiaomiCompatibilityPatch = patch(
    name = "Enable non-Xiaomi device compatibility",
    description = "Enables rootless POCO Launcher on non-Xiaomi Android 14+ devices with native " +
        "widgets, badges, hidden apps, gestures, icon/label customization, double-tap to lock, " +
        "and battery optimization.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_POCOLAUNCHER)

    extendWith("extensions/poco-launcher.mpe")

    execute {
        verifyPocoLauncherTarget()

        patchManifest()
        hookApplicationInit()
        patchBundledMiuiStubs()
        refactorBlockedViewSetFrame()
        rewriteAllAndroidSettingsCalls()
        rewriteContentResolverCalls()
        rewriteAndroid14PrivilegeCalls()
        patchHiddenApisAndBootCollisions()
        patchRuntimeAndPrivilegeCrashes()
        patchCpuBatteryAndBackgroundOverhead()
        patchNativeNonXiaomiUx()
    }
}

// ==================== 1. Manifest Fixes ====================

private val DISABLED_BACKGROUND_COMPONENTS = setOf(
    // Telemetry, ad pre-install, Firebase & DataTransport
    "com.google.firebase.sessions.SessionLifecycleService",
    "com.google.firebase.components.ComponentDiscoveryService",
    "com.google.android.datatransport.runtime.backends.TransportBackendDiscovery",
    "com.google.android.datatransport.runtime.scheduling.jobscheduling.JobInfoSchedulerService",
    "com.google.android.datatransport.runtime.scheduling.jobscheduling.AlarmManagerSchedulerBroadcastReceiver",
    "com.miui.home.launcher.AnalyticalDataCollectorJobService",
    "com.miui.home.launcher.IronSource.IronSourcePreInstallReceiver",
    "com.miui.bugreport.logprovider.DumpLogProvider",
    // Xiaomi Handoff / Synergy / MiClaw / Cloud Backup / MAML / Recents (dead outside Xiaomi ROM)
    "com.xiaomi.dist.handoff.system.ServiceStartReceiver",
    "com.xiaomi.dist.handoff.system.PackageReceiver",
    "com.miui.maml.elements.MusicListenerService",
    "com.miui.home.recents.TouchInteractionService",
    "com.miui.home.recents.AppHandleGestureProvider",
    "com.miui.home.recents.WindowVisibilityProvider",
    "com.miui.home.launcher.snapshot.HomeSnapshotHelperService",
    "com.miui.home.isolate.provision.SetupWizardCompletedService",
    "com.miui.home.miclaw.HomeSettingsToolService",
    "com.miui.home.launcher.cloudbackup.BackupRestoreHomeService",
    "com.miui.home.actionclient.AIActionProvider",
    "com.miui.home.launcher.offlinewidget.OfflineWidgetReceiver",
    "com.miui.home.launcher.offlinewidget.OfflineMamlReceiver",
    "com.miui.home.launcher.WallpaperEffectFinishReceiver",
    "com.miui.home.launcher.oldman.ElderlyManModeChangedReceiver",
)

private fun patchManifest() {
    resourceXml("AndroidManifest.xml").use { manifest ->
        manifest.root.removeAttribute("android:sharedUserId")

        val application = manifest.elements("application").firstOrNull()
            ?: throw PatchException("POCO Launcher: <application> não encontrado no AndroidManifest.xml")
        application.removeAttribute("android:process")

        manifest.elements("permission-group").toList()
            .filter { it.attribute("android:name") == "android.permission-group.SYSTEM_TOOLS" }
            .forEach { it.remove() }

        manifest.elements("permission").toList().forEach { perm ->
            val permName = perm.attribute("android:name")
            if (permName in CONFLICTING_PERMISSIONS) {
                perm.remove()
            } else if (perm.attribute("android:permissionGroup") == "android.permission-group.SYSTEM_TOOLS") {
                perm.removeAttribute("android:permissionGroup")
            }
        }

        // Declare USE_BIOMETRIC so modern Android BiometricPrompt works seamlessly for Hide Apps
        // on non-Xiaomi Android 14+ devices (original APK only declared deprecated USE_FINGERPRINT).
        // Also declare REQUEST_DELETE_PACKAGES so Android 9+ PackageInstaller shows the native
        // uninstall confirmation dialog for ACTION_DELETE (original APK only declared privileged
        // DELETE_PACKAGES, causing Android 14/15 to silently abort uninstall requests).
        for (permName in listOf(
            "android.permission.USE_BIOMETRIC",
            "android.permission.REQUEST_DELETE_PACKAGES",
        )) {
            val hasPerm = manifest.elements("uses-permission")
                .any { it.attribute("android:name") == permName }
            if (!hasPerm) {
                manifest.root.appendChild("uses-permission") {
                    setAttribute("android:name", permName)
                }
            }
        }

        manifest.elements("provider").forEach { provider ->
            if (provider.attribute("android:readPermission") == "com.android.launcher.permission.READ_SETTINGS") {
                provider.setAttribute("android:readPermission", OWN_WRITE_SETTINGS_PERMISSION)
            }
            if (provider.attribute("android:name") in DISABLED_BACKGROUND_COMPONENTS) {
                provider.setAttribute("android:enabled", "false")
            }
        }

        // Disable dead telemetry, ad pre-install, and MIUI-only background services/receivers
        // to eliminate idle wakeups, JobScheduler churn, and battery drain.
        manifest.elements("service").forEach { service ->
            if (service.attribute("android:name") in DISABLED_BACKGROUND_COMPONENTS) {
                service.setAttribute("android:enabled", "false")
            }
        }
        manifest.elements("receiver").forEach { receiver ->
            if (receiver.attribute("android:name") in DISABLED_BACKGROUND_COMPONENTS) {
                receiver.setAttribute("android:enabled", "false")
            }
        }

        val splashActivity = manifest.elements("activity")
            .firstOrNull { it.attribute("android:name") == "com.miui.home.launcher.SplashActivity" }
            ?: throw PatchException("POCO Launcher: SplashActivity não encontrada no AndroidManifest.xml")
        val intentFilter = splashActivity.children("intent-filter").firstOrNull()
            ?: splashActivity.appendChild("intent-filter")
        val hasLauncherCategory = intentFilter.children("category")
            .any { it.attribute("android:name") == "android.intent.category.LAUNCHER" }
        if (!hasLauncherCategory) {
            intentFilter.appendChild("category") {
                setAttribute("android:name", "android.intent.category.LAUNCHER")
            }
        }

        // Enable NotificationListener so Android lists POCO Launcher under Notification Access
        // and binds it for live unread icon badges outside MIUI (MIUI used hidden STATUS_BAR_SERVICE).
        val notifService = manifest.elements("service")
            .firstOrNull {
                it.attribute("android:name") == "com.miui.home.launcher.notification.NotificationListener"
            } ?: throw PatchException(
            "POCO Launcher: NotificationListener não encontrado no AndroidManifest.xml",
        )
        notifService.setAttribute("android:enabled", "true")

        // Register minimal zero-capability AccessibilityService for rootless double-tap-to-lock
        // (GLOBAL_ACTION_LOCK_SCREEN, preserving fingerprint/biometric unlock) and swipe-down
        // notification shade fallback (GLOBAL_ACTION_NOTIFICATIONS).
        val lockServiceClass = "app.rimixyz.extension.pocolauncher.PocoLockScreenService"
        val hasLockService = manifest.elements("service")
            .any { it.attribute("android:name") == lockServiceClass }
        if (!hasLockService) {
            application.appendChild("service") {
                setAttribute("android:name", lockServiceClass)
                setAttribute("android:label", "@string/gesture_tap_lock")
                setAttribute("android:permission", "android.permission.BIND_ACCESSIBILITY_SERVICE")
                setAttribute("android:exported", "true")
                appendChild("intent-filter") {
                    appendChild("action") {
                        setAttribute("android:name", "android.accessibilityservice.AccessibilityService")
                    }
                }
                appendChild("meta-data") {
                    setAttribute("android:name", "android.accessibilityservice")
                    setAttribute("android:resource", "@xml/poco_lock_accessibility")
                }
            }
        }
    }

    val lockAccessibilityXml = resourceFile("res/xml/poco_lock_accessibility.xml")
    lockAccessibilityXml.parentFile?.mkdirs()
    lockAccessibilityXml.writeText(
        """
        <?xml version="1.0" encoding="utf-8"?>
        <accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
            android:accessibilityFeedbackType="feedbackGeneric"
            android:canRetrieveWindowContent="false"
            android:description="@string/gesture_tap_lock"
            android:notificationTimeout="100"
            android:settingsActivity="com.miui.home.settings.MiuiHomeSettingActivity"
            android:summary="@string/gesture_tap_lock" />
        """.trimIndent() + "\n",
    )
}

// ==================== 2. Application Init ====================

private fun hookApplicationInit() {
    val attachBase = MainApplicationAttachBaseContextFingerprint.method
    attachBase.addInstructions(
        0,
        "invoke-static { p1 }, $COMPAT_HELPER->init(Landroid/content/Context;)V",
    )
}

// ==================== 2b. APK-Bundled Partial MIUI Stubs ====================

/**
 * Completa classes `miui.*` que o próprio APK empacota como stubs parciais (na HyperOS o
 * `BootClassLoader` sombreia essas classes com o framework da ROM, mas fora da Xiaomi o ART
 * carrega os stubs incompletos do `classes2.dex`).
 */
private fun patchBundledMiuiStubs() {
    // 1. miui.process.ProcessManager: faltam 5 métodos estáticos chamados por AppObserver,
    //    ProcessManagerWrapper e PowerClearButton (causava NoSuchMethodError no boot).
    val processManager = mutableClassDefBy("Lmiui/process/ProcessManager;")
    val publicStatic = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value

    processManager.addStaticStubMethod(
        name = "registerForegroundInfoListener",
        paramTypes = listOf("Lmiui/process/IForegroundInfoListener;"),
        returnType = "V",
        locals = 0,
        smali = "return-void",
        accessFlags = publicStatic,
    )
    processManager.addStaticStubMethod(
        name = "updateApplicationLockedState",
        paramTypes = listOf("Ljava/lang/String;", "I", "Z"),
        returnType = "V",
        locals = 0,
        smali = "return-void",
        accessFlags = publicStatic,
    )
    processManager.addStaticStubMethod(
        name = "kill",
        paramTypes = listOf("Lmiui/process/ProcessConfig;"),
        returnType = "Z",
        locals = 1,
        smali = """
            const/4 v0, 0x0
            return v0
        """.trimIndent(),
        accessFlags = publicStatic,
    )
    processManager.addStaticStubMethod(
        name = "isLockedApplication",
        paramTypes = listOf("Ljava/lang/String;", "I"),
        returnType = "Z",
        locals = 1,
        smali = """
            const/4 v0, 0x0
            return v0
        """.trimIndent(),
        accessFlags = publicStatic,
    )
    processManager.addStaticStubMethod(
        name = "getLockedApplication",
        paramTypes = listOf("I"),
        returnType = "Ljava/util/List;",
        locals = 1,
        smali = """
            invoke-static { }, Ljava/util/Collections;->emptyList()Ljava/util/List;
            move-result-object v0
            return-object v0
        """.trimIndent(),
        accessFlags = publicStatic,
    )

    // 2. miui.app.MiuiFreeFormManager: quando getService() == null fora da Xiaomi, vários métodos
    //    estáticos lançam UnsupportedOperationException("非MIUI13小窗版本..."). Neutraliza todos
    //    para retornos seguros (lista vazia / false / 0 / null / void).
    val freeForm = mutableClassDefBy("Lmiui/app/MiuiFreeFormManager;")
    val keepFreeFormMethods = setOf(
        "<clinit>",
        "<init>",
        "actionToString",
        "supportFreeform",
        "getActivityOptions",
        "getMiuiFreeformVersion",
    )
    freeForm.methods
        .filter { AccessFlags.STATIC.isSet(it.accessFlags) && it.name !in keepFreeFormMethods }
        .toList()
        .forEach { method ->
            when (method.returnType) {
                "V" -> method.replaceBody(locals = 0, smali = "return-void")
                "Z" -> method.replaceBody(locals = 1, smali = "const/4 v0, 0x0\nreturn v0")
                "I" -> {
                    val value = if (method.name == "getMiuiFreeformStackShowState") "-0x1" else "0x0"
                    method.replaceBody(locals = 1, smali = "const/4 v0, $value\nreturn v0")
                }

                "Ljava/util/List;" -> method.replaceBody(
                    locals = 1,
                    smali = """
                        invoke-static { }, Ljava/util/Collections;->emptyList()Ljava/util/List;
                        move-result-object v0
                        return-object v0
                    """.trimIndent(),
                )

                else -> method.replaceBody(locals = 1, smali = "const/4 v0, 0x0\nreturn-object v0")
            }
        }

    // 3. miui.app.IFreeformCallback$Stub: asBinder() e onTransact() lançavam RuntimeException("Stub!").
    val freeformStub = mutableClassDefBy("Lmiui/app/IFreeformCallback\$Stub;")
    freeformStub.methods.firstOrNull { it.name == "asBinder" && it.parameterTypes.isEmpty() }
        ?.replaceBody(locals = 0, smali = "return-object p0")
    freeformStub.methods.firstOrNull { it.name == "onTransact" && it.parameterTypes.size == 4 }
        ?.replaceBody(locals = 1, smali = "const/4 v0, 0x0\nreturn v0")
}

private fun MutableClass.addStaticStubMethod(
    name: String,
    paramTypes: List<String>,
    returnType: String,
    locals: Int,
    smali: String,
    accessFlags: Int,
) {
    val alreadyExists = methods.any {
        it.name == name &&
            it.returnType == returnType &&
            it.parameterTypes.map(CharSequence::toString) == paramTypes
    }
    if (alreadyExists) return

    var paramRegs = 0
    for (t in paramTypes) {
        paramRegs += if (t == "J" || t == "D") 2 else 1
    }
    val params = paramTypes.map { ImmutableMethodParameter(it, emptySet(), null) }
    val created = ImmutableMethod(
        type,
        name,
        params,
        returnType,
        accessFlags,
        emptySet(),
        emptySet(),
        ImmutableMethodImplementation(
            paramRegs + locals,
            listOf(ImmutableInstruction10x(Opcode.NOP)),
            emptyList(),
            emptyList(),
        ),
    ).toMutable()
    created.addInstructions(0, smali)
    created.removeInstruction(created.instructions.lastIndex)
    methods.add(created)
}

// ==================== 3. View.setFrame Refactor ====================

private fun refactorBlockedViewSetFrame() {
    val applyViewFrameRef = ImmutableMethodReference(
        COMPAT_HELPER,
        "applyViewFrame",
        listOf("Landroid/view/View;", "I", "I", "I", "I"),
        "Z",
    )

    // 3a. 4 base layout classes: rename setFrame -> miuiSetFrame, rewrite super.setFrame -> applyViewFrame,
    //     rewrite doSetFrame -> miuiSetFrame, and attach public OnLayoutChangeListener bridge in super-calling <init>.
    for (baseType in SET_FRAME_BASE_CLASSES) {
        val clazz = mutableClassDefBy(baseType)
        renameSetFrameMethod(clazz)

        val superSetFrame = clazz.findMethodOrThrow("superSetFrame", listOf("I", "I", "I", "I"), "Z")
        rewriteMethodCalls(superSetFrame) { ins, ref ->
            if (ins.opcode.isSuperInvoke() && ref.name == "setFrame" && ref.definingClass in BLOCKED_VIEW_SET_FRAME_OWNERS) {
                Opcode.INVOKE_STATIC to applyViewFrameRef
            } else {
                null
            }
        }

        val miuiSetFrame = clazz.findMethodOrThrow("miuiSetFrame", listOf("I", "I", "I", "I"), "Z")
        rewriteMethodCalls(miuiSetFrame) { ins, ref ->
            if (ins.opcode.isSuperInvoke() && ref.name == "setFrame" && ref.definingClass in BLOCKED_VIEW_SET_FRAME_OWNERS) {
                Opcode.INVOKE_STATIC to applyViewFrameRef
            } else {
                null
            }
        }

        val doSetFrame = clazz.findMethodOrThrow("doSetFrame", listOf("I", "I", "I", "I"), "Z")
        rewriteMethodCalls(doSetFrame) { ins, ref ->
            if (ins.opcode.isVirtualInvoke() && ref.name == "setFrame" && ref.definingClass == baseType) {
                null to ImmutableMethodReference(baseType, "miuiSetFrame", ref.parameterTypes, ref.returnType)
            } else {
                null
            }
        }

        // Attach OnLayoutChangeListener once per instance, only in constructors calling super.<init>.
        clazz.methods.filter { it.name == "<init>" }.forEach { ctor ->
            val callsSuperInit = ctor.instructions.any { ins ->
                ins.opcode == Opcode.INVOKE_DIRECT &&
                    (ins as? ReferenceInstruction)?.reference
                        .let { it as? MethodReference }
                        ?.let { it.name == "<init>" && it.definingClass != baseType } == true
            }
            if (callsSuperInit) {
                val returnIndices = ctor.instructions
                    .withIndex()
                    .filter { it.value.opcode == Opcode.RETURN_VOID }
                    .map { it.index }
                    .asReversed()
                check(returnIndices.isNotEmpty()) {
                    "POCO Launcher: return-void não encontrado em $baseType-><init>"
                }
                for (retIdx in returnIndices) {
                    ctor.addInstructions(
                        retIdx,
                        "invoke-static { p0 }, $COMPAT_HELPER->attachLayoutBridge(Landroid/view/View;)V",
                    )
                }
            }
        }
    }

    // 3b. 5 subclasses overriding setFrame: rename setFrame -> miuiSetFrame.
    for (subType in SET_FRAME_SUBCLASSES) {
        val clazz = mutableClassDefBy(subType)
        renameSetFrameMethod(clazz)
    }

    // 3c. 3 subclasses invoking super.setFrame on one of the 4 base classes: redirect to super.miuiSetFrame.
    for (callerType in SET_FRAME_SUPER_CALLERS) {
        val clazz = mutableClassDefBy(callerType)
        for (method in clazz.methods) {
            if (method.implementation == null) continue
            rewriteMethodCalls(method) { ins, ref ->
                if (ins.opcode.isSuperInvoke() && ref.name == "setFrame" && ref.definingClass in SET_FRAME_BASE_CLASSES) {
                    null to ImmutableMethodReference(ref.definingClass, "miuiSetFrame", ref.parameterTypes, ref.returnType)
                } else {
                    null
                }
            }
        }
    }
}

private fun renameSetFrameMethod(clazz: MutableClass) {
    val method = clazz.findMethodOrThrow("setFrame", listOf("I", "I", "I", "I"), "Z")
    check(clazz.methods.remove(method)) {
        "POCO Launcher: falha ao remover setFrame de ${clazz.type}"
    }
    method.setName("miuiSetFrame")
    clazz.methods.add(method)
}

// ==================== 4. Global Android Settings.* Rewrite ====================

private fun rewriteAllAndroidSettingsCalls() {
    var rewrittenCount = 0
    classDefForEach { classDef ->
        if (classDef.type.startsWith(EXTENSION_PREFIX)) return@classDefForEach

        val matchingMethods = mutableListOf<Method>()
        for (method in classDef.methods) {
            val impl = method.implementation ?: continue
            val hasSettingsCall = impl.instructions.any { ins ->
                if (!ins.opcode.isStaticInvoke()) return@any false
                val ref = (ins as? ReferenceInstruction)?.reference as? MethodReference ?: return@any false
                ref.definingClass in SETTINGS_NAMESPACE_PREFIX && ref.name in REWRITTEN_SETTINGS_METHODS
            }
            if (hasSettingsCall) {
                matchingMethods.add(method)
            }
        }

        if (matchingMethods.isEmpty()) return@classDefForEach
        val mutableClass = mutableClassDefBy(classDef)
        for (orig in matchingMethods) {
            val mutableMethod = mutableClass.methods.first {
                it.name == orig.name &&
                    it.returnType == orig.returnType &&
                    it.parameterTypes == orig.parameterTypes
            }
            rewrittenCount += rewriteMethodCalls(mutableMethod) { ins, ref ->
                if (!ins.opcode.isStaticInvoke()) return@rewriteMethodCalls null
                val prefix = SETTINGS_NAMESPACE_PREFIX[ref.definingClass] ?: return@rewriteMethodCalls null
                if (ref.name !in REWRITTEN_SETTINGS_METHODS) return@rewriteMethodCalls null
                val mappedName = prefix + ref.name.replaceFirstChar { it.uppercaseChar() }
                null to ImmutableMethodReference(SETTINGS_COMPAT, mappedName, ref.parameterTypes, ref.returnType)
            }
        }
    }
    check(rewrittenCount > 0) {
        "POCO Launcher: nenhuma chamada a Settings.System/Global/Secure encontrada para reescrever"
    }
}

// ==================== 4b. Global ContentResolver Observer & Call Guards ====================

private fun rewriteContentResolverCalls() {
    val safeRegisterRef = ImmutableMethodReference(
        COMPAT_HELPER,
        "safeRegisterContentObserver",
        listOf(
            "Landroid/content/ContentResolver;",
            "Landroid/net/Uri;",
            "Z",
            "Landroid/database/ContentObserver;",
        ),
        "V",
    )
    val safeUnregisterRef = ImmutableMethodReference(
        COMPAT_HELPER,
        "safeUnregisterContentObserver",
        listOf(
            "Landroid/content/ContentResolver;",
            "Landroid/database/ContentObserver;",
        ),
        "V",
    )
    val safeCallUriRef = ImmutableMethodReference(
        COMPAT_HELPER,
        "safeContentResolverCall",
        listOf(
            "Landroid/content/ContentResolver;",
            "Landroid/net/Uri;",
            "Ljava/lang/String;",
            "Ljava/lang/String;",
            "Landroid/os/Bundle;",
        ),
        "Landroid/os/Bundle;",
    )
    val safeCallAuthorityRef = ImmutableMethodReference(
        COMPAT_HELPER,
        "safeContentResolverCall",
        listOf(
            "Landroid/content/ContentResolver;",
            "Ljava/lang/String;",
            "Ljava/lang/String;",
            "Ljava/lang/String;",
            "Landroid/os/Bundle;",
        ),
        "Landroid/os/Bundle;",
    )
    val safeNotify2Ref = ImmutableMethodReference(
        COMPAT_HELPER,
        "safeNotifyChange",
        listOf(
            "Landroid/content/ContentResolver;",
            "Landroid/net/Uri;",
            "Landroid/database/ContentObserver;",
        ),
        "V",
    )
    val safeNotifyBoolRef = ImmutableMethodReference(
        COMPAT_HELPER,
        "safeNotifyChange",
        listOf(
            "Landroid/content/ContentResolver;",
            "Landroid/net/Uri;",
            "Landroid/database/ContentObserver;",
            "Z",
        ),
        "V",
    )
    val safeNotifyIntRef = ImmutableMethodReference(
        COMPAT_HELPER,
        "safeNotifyChange",
        listOf(
            "Landroid/content/ContentResolver;",
            "Landroid/net/Uri;",
            "Landroid/database/ContentObserver;",
            "I",
        ),
        "V",
    )

    fun mapTarget(ref: MethodReference): MethodReference? {
        if (ref.definingClass != "Landroid/content/ContentResolver;") return null
        val params = ref.parameterTypes.map(CharSequence::toString)
        return when {
            ref.name == "registerContentObserver" &&
                ref.returnType == "V" &&
                params == listOf("Landroid/net/Uri;", "Z", "Landroid/database/ContentObserver;") -> safeRegisterRef

            ref.name == "unregisterContentObserver" &&
                ref.returnType == "V" &&
                params == listOf("Landroid/database/ContentObserver;") -> safeUnregisterRef

            ref.name == "call" &&
                ref.returnType == "Landroid/os/Bundle;" &&
                params == listOf("Landroid/net/Uri;", "Ljava/lang/String;", "Ljava/lang/String;", "Landroid/os/Bundle;") -> safeCallUriRef

            ref.name == "call" &&
                ref.returnType == "Landroid/os/Bundle;" &&
                params == listOf("Ljava/lang/String;", "Ljava/lang/String;", "Ljava/lang/String;", "Landroid/os/Bundle;") -> safeCallAuthorityRef

            ref.name == "notifyChange" &&
                ref.returnType == "V" &&
                params == listOf("Landroid/net/Uri;", "Landroid/database/ContentObserver;") -> safeNotify2Ref

            ref.name == "notifyChange" &&
                ref.returnType == "V" &&
                params == listOf("Landroid/net/Uri;", "Landroid/database/ContentObserver;", "Z") -> safeNotifyBoolRef

            ref.name == "notifyChange" &&
                ref.returnType == "V" &&
                params == listOf("Landroid/net/Uri;", "Landroid/database/ContentObserver;", "I") -> safeNotifyIntRef

            else -> null
        }
    }

    var rewrittenCount = 0
    classDefForEach { classDef ->
        if (classDef.type.startsWith(EXTENSION_PREFIX)) return@classDefForEach

        val matchingMethods = mutableListOf<Method>()
        for (method in classDef.methods) {
            val impl = method.implementation ?: continue
            val hasTarget = impl.instructions.any { ins ->
                if (!ins.opcode.isVirtualInvoke()) return@any false
                val ref = (ins as? ReferenceInstruction)?.reference as? MethodReference ?: return@any false
                mapTarget(ref) != null
            }
            if (hasTarget) {
                matchingMethods.add(method)
            }
        }

        if (matchingMethods.isEmpty()) return@classDefForEach
        val mutableClass = mutableClassDefBy(classDef)
        for (orig in matchingMethods) {
            val mutableMethod = mutableClass.methods.first {
                it.name == orig.name &&
                    it.returnType == orig.returnType &&
                    it.parameterTypes == orig.parameterTypes
            }
            rewrittenCount += rewriteMethodCalls(mutableMethod) { ins, ref ->
                if (!ins.opcode.isVirtualInvoke()) return@rewriteMethodCalls null
                val target = mapTarget(ref) ?: return@rewriteMethodCalls null
                Opcode.INVOKE_STATIC to target
            }
        }
    }
    check(rewrittenCount > 0) {
        "POCO Launcher: nenhuma chamada a ContentResolver.registerContentObserver/call encontrada para reescrever"
    }
}

// ==================== 4c. Global Android 14+ Privilege & Broadcast/Alarm Guards ====================

private fun rewriteAndroid14PrivilegeCalls() {
    val safeSetExactAlarmRef = ImmutableMethodReference(
        COMPAT_HELPER,
        "safeSetExactAlarm",
        listOf("Landroid/app/AlarmManager;", "I", "J", "Landroid/app/PendingIntent;"),
        "V",
    )
    val safeRegisterReceiverRef = ImmutableMethodReference(
        COMPAT_HELPER,
        "safeRegisterReceiver",
        listOf("Landroid/content/Context;", "Landroid/content/BroadcastReceiver;", "Landroid/content/IntentFilter;"),
        "Landroid/content/Intent;",
    )
    val safeSendBroadcast1Ref = ImmutableMethodReference(
        COMPAT_HELPER,
        "safeSendBroadcast",
        listOf("Landroid/content/Context;", "Landroid/content/Intent;"),
        "V",
    )
    val safeSendBroadcast2Ref = ImmutableMethodReference(
        COMPAT_HELPER,
        "safeSendBroadcast",
        listOf("Landroid/content/Context;", "Landroid/content/Intent;", "Ljava/lang/String;"),
        "V",
    )
    val safeSendBroadcastAsUserRef = ImmutableMethodReference(
        COMPAT_HELPER,
        "safeSendBroadcastAsUser",
        listOf("Landroid/content/Context;", "Landroid/content/Intent;", "Landroid/os/UserHandle;"),
        "V",
    )
    val safeRegisterPredUpdatesRef = ImmutableMethodReference(
        COMPAT_HELPER,
        "safeRegisterPredictionUpdates",
        listOf("Ljava/lang/Object;", "Ljava/util/concurrent/Executor;", "Ljava/lang/Object;"),
        "V",
    )
    val safeUnregisterPredUpdatesRef = ImmutableMethodReference(
        COMPAT_HELPER,
        "safeUnregisterPredictionUpdates",
        listOf("Ljava/lang/Object;", "Ljava/lang/Object;"),
        "V",
    )
    val safeRequestPredUpdateRef = ImmutableMethodReference(
        COMPAT_HELPER,
        "safeRequestPredictionUpdate",
        listOf("Ljava/lang/Object;"),
        "V",
    )
    val safeNotifyAppTargetEventRef = ImmutableMethodReference(
        COMPAT_HELPER,
        "safeNotifyAppTargetEvent",
        listOf("Ljava/lang/Object;", "Ljava/lang/Object;"),
        "V",
    )

    fun mapTarget(ref: MethodReference): MethodReference? {
        val params = ref.parameterTypes.map(CharSequence::toString)
        return when (ref.definingClass) {
            "Landroid/app/AlarmManager;" -> when {
                ref.name == "setExact" &&
                    ref.returnType == "V" &&
                    params == listOf("I", "J", "Landroid/app/PendingIntent;") -> safeSetExactAlarmRef

                else -> null
            }

            "Landroid/content/Context;" -> when {
                ref.name == "registerReceiver" &&
                    ref.returnType == "Landroid/content/Intent;" &&
                    params == listOf("Landroid/content/BroadcastReceiver;", "Landroid/content/IntentFilter;") ->
                    safeRegisterReceiverRef

                ref.name == "sendBroadcast" &&
                    ref.returnType == "V" &&
                    params == listOf("Landroid/content/Intent;") -> safeSendBroadcast1Ref

                ref.name == "sendBroadcast" &&
                    ref.returnType == "V" &&
                    params == listOf("Landroid/content/Intent;", "Ljava/lang/String;") -> safeSendBroadcast2Ref

                ref.name == "sendBroadcastAsUser" &&
                    ref.returnType == "V" &&
                    params == listOf("Landroid/content/Intent;", "Landroid/os/UserHandle;") ->
                    safeSendBroadcastAsUserRef

                else -> null
            }

            "Landroid/app/prediction/AppPredictor;" -> when {
                ref.name == "registerPredictionUpdates" &&
                    ref.returnType == "V" &&
                    params == listOf("Ljava/util/concurrent/Executor;", "Landroid/app/prediction/AppPredictor\$Callback;") ->
                    safeRegisterPredUpdatesRef

                ref.name == "unregisterPredictionUpdates" &&
                    ref.returnType == "V" &&
                    params == listOf("Landroid/app/prediction/AppPredictor\$Callback;") ->
                    safeUnregisterPredUpdatesRef

                ref.name == "requestPredictionUpdate" &&
                    ref.returnType == "V" &&
                    params.isEmpty() -> safeRequestPredUpdateRef

                ref.name == "notifyAppTargetEvent" &&
                    ref.returnType == "V" &&
                    params == listOf("Landroid/app/prediction/AppTargetEvent;") -> safeNotifyAppTargetEventRef

                else -> null
            }

            else -> null
        }
    }

    var rewrittenCount = 0
    classDefForEach { classDef ->
        if (classDef.type.startsWith(EXTENSION_PREFIX) || classDef.type.startsWith("Landroidx/")) {
            return@classDefForEach
        }

        val matchingMethods = mutableListOf<Method>()
        for (method in classDef.methods) {
            val impl = method.implementation ?: continue
            val hasTarget = impl.instructions.any { ins ->
                if (!ins.opcode.isVirtualInvoke()) return@any false
                val ref = (ins as? ReferenceInstruction)?.reference as? MethodReference ?: return@any false
                mapTarget(ref) != null
            }
            if (hasTarget) {
                matchingMethods.add(method)
            }
        }

        if (matchingMethods.isEmpty()) return@classDefForEach
        val mutableClass = mutableClassDefBy(classDef)
        for (orig in matchingMethods) {
            val mutableMethod = mutableClass.methods.first {
                it.name == orig.name &&
                    it.returnType == orig.returnType &&
                    it.parameterTypes == orig.parameterTypes
            }
            rewrittenCount += rewriteMethodCalls(mutableMethod) { ins, ref ->
                if (!ins.opcode.isVirtualInvoke()) return@rewriteMethodCalls null
                val target = mapTarget(ref) ?: return@rewriteMethodCalls null
                Opcode.INVOKE_STATIC to target
            }
        }
    }
    check(rewrittenCount > 0) {
        "POCO Launcher: nenhuma chamada privilegiada Android 14+ encontrada para reescrever"
    }
}

// ==================== 5. Blocked Hidden-API & BootClassLoader Fixes ====================

private fun patchHiddenApisAndBootCollisions() {
    // 5a. InteractionJankMonitor (blocked on Android 15, BootClassLoader collision).
    val jankClass = mutableClassDefBy("Lcom/android/systemui/shared/system/InteractionJankMonitorWrapper;")
    jankClass.methods
        .filter {
            it.name in setOf("begin", "end", "cancel", "removeCallbacksAndMessagesOfWorkerHandler") &&
                it.returnType == "V"
        }
        .toList()
        .forEach { method ->
            method.replaceBody(locals = 0, smali = "return-void")
        }

    // 5b. BitmapRenderer.createHardwareBitmapWithAcceleratedCanvas -> delegate to Picture-based createHardwareBitmap.
    BitmapRendererCreateAccelFingerprint.method.replaceBody(
        locals = 1,
        smali = """
            invoke-static { p0, p1, p3 }, Lcom/miui/launcher/utils/BitmapRenderer;->createHardwareBitmap(IILcom/miui/launcher/utils/BitmapRenderer${'$'}Renderer;)Landroid/graphics/Bitmap;
            move-result-object v0
            return-object v0
        """.trimIndent(),
    )

    // 5c. LauncherUtils.getSender(Intent) -> null (Intent.getSender is MIUI-only).
    LauncherUtilsGetSenderFingerprint.method.replaceBody(
        locals = 1,
        smali = """
            const/4 v0, 0x0
            return-object v0
        """.trimIndent(),
    )

    // 5d. ActivityManagerWrapper.trimMemory (WindowManagerGlobal.trimMemory is max-target-p).
    //     Route BaseLauncher.onPause()'s 60s idle trimMemory(20) to PocoCompatHelper.onAppTrimMemory.
    ActivityManagerWrapperTrimMemoryFingerprint.method.replaceBody(
        locals = 0,
        smali = """
            invoke-static { p1 }, $COMPAT_HELPER->onAppTrimMemory(I)V
            return-void
        """.trimIndent(),
    )

    // 5e. ActivityManagerWrapper.getComponentName(ActivityInfo) (ActivityInfo.getComponentName is hidden).
    ActivityManagerWrapperGetComponentNameFingerprint.method.replaceBody(
        locals = 1,
        smali = """
            invoke-static { p1 }, $COMPAT_HELPER->getComponentName(Landroid/content/pm/ActivityInfo;)Landroid/content/ComponentName;
            move-result-object v0
            return-object v0
        """.trimIndent(),
    )

    // 5f. AppOpsManagerUtils.noteOpNoThrow -> MODE_ALLOWED (0) (3-arg int overload is max-target-q).
    AppOpsManagerNoteOpFingerprint.method.replaceBody(
        locals = 1,
        smali = """
            const/4 v0, 0x0
            return v0
        """.trimIndent(),
    )

    // 5g. ContentProviderUtils.updateData / isProviderExists (acquireUnstableProvider is max-target-o).
    ContentProviderUpdateDataFingerprint.method.replaceBody(
        locals = 0,
        smali = """
            invoke-static { p0, p1, p2 }, $COMPAT_HELPER->safeUpdateProviderData(Landroid/content/Context;Landroid/net/Uri;Landroid/content/ContentValues;)V
            return-void
        """.trimIndent(),
    )
    ContentProviderIsProviderExistsFingerprint.method.replaceBody(
        locals = 1,
        smali = """
            invoke-static { p0, p1 }, $COMPAT_HELPER->safeIsProviderExists(Landroid/content/Context;Landroid/net/Uri;)Z
            move-result v0
            return v0
        """.trimIndent(),
    )

    // 5h. ActivityUtilsCompat.makeCustomAnimation -> public 3-arg ActivityOptions.makeCustomAnimation.
    ActivityUtilsCompatMakeCustomAnimFingerprint.method.replaceBody(
        locals = 1,
        smali = """
            invoke-static { p0, p1, p2 }, Landroid/app/ActivityOptions;->makeCustomAnimation(Landroid/content/Context;II)Landroid/app/ActivityOptions;
            move-result-object v0
            return-object v0
        """.trimIndent(),
    )

    // 5i. DisplayManagerGlobalUtils.getRotation & LauncherUtils.getNaturalWidth (Display.getDisplayInfo is max-target-p).
    DisplayManagerGlobalGetRotationFingerprint.method.replaceBody(
        locals = 1,
        smali = """
            invoke-static { }, $COMPAT_HELPER->getRotation()I
            move-result v0
            return v0
        """.trimIndent(),
    )
    LauncherUtilsGetNaturalWidthFingerprint.method.replaceBody(
        locals = 1,
        smali = """
            invoke-static { p0 }, $COMPAT_HELPER->getNaturalWidth(Landroid/content/Context;)I
            move-result v0
            return v0
        """.trimIndent(),
    )

    // 5j. MiuiWindowManagerUtils (Window.setExtraFlags & LayoutParams.extraFlags are MIUI-only).
    MiuiWindowManagerSetExtraFlagFingerprint.method.replaceBody(
        locals = 0,
        smali = """
            invoke-static { p0, p1, p2 }, $COMPAT_HELPER->setExtraFlag(Landroid/view/Window;II)V
            return-void
        """.trimIndent(),
    )
    MiuiWindowManagerAddExtraFlagFingerprint.method.replaceBody(
        locals = 0,
        smali = "return-void",
    )
    MiuiWindowManagerGetInitialDisplaySizeFingerprint.method.replaceBody(
        locals = 1,
        smali = """
            invoke-static { }, $COMPAT_HELPER->getInitialDisplaySize()Landroid/graphics/Point;
            move-result-object v0
            return-object v0
        """.trimIndent(),
    )

    // 5k. InternalResource.<clinit> (com.android.internal.R$styleable.View is max-target-q).
    InternalResourceClinitFingerprint.method.replaceBody(
        locals = 1,
        smali = """
            const/4 v0, 0x0
            new-array v0, v0, [I
            sput-object v0, Lcom/miui/launcher/common/InternalResource;->VIEW_ATTR:[I
            return-void
        """.trimIndent(),
    )

    // 5l. ServiceDelivery IntentUtils.canBeResolved (resolveActivityAsUser is max-target-o).
    ServiceDeliveryIntentUtilsCanBeResolvedFingerprint.method.replaceBody(
        locals = 1,
        smali = """
            invoke-static { p0, p1 }, $COMPAT_HELPER->canBeResolved(Landroid/content/Context;Landroid/content/Intent;)Z
            move-result v0
            return v0
        """.trimIndent(),
    )

    // 5m. BoostHelper.getRenderThreadId (View.getThreadedRenderer is max-target-r).
    BoostHelperGetRenderThreadIdFingerprint.method.replaceBody(
        locals = 1,
        smali = """
            const/4 v0, 0x0
            return v0
        """.trimIndent(),
    )

    // 5n. BlurUtils.setBlur (SurfaceControl.Transaction.setBlur* are MIUI-only; still fire progress callback).
    BlurUtilsSetBlurFingerprint.method.replaceBody(
        locals = 1,
        smali = """
            if-eqz p3, :done
            invoke-static { p1 }, Ljava/lang/Float;->valueOf(F)Ljava/lang/Float;
            move-result-object v0
            invoke-interface { p3, v0 }, Ljava/util/function/Consumer;->accept(Ljava/lang/Object;)V
            :done
            return-void
        """.trimIndent(),
    )

    // 5o. Remaining Android 14/15/16 blocked/removed SystemUI Recents, SurfaceControl, and Insets APIs.
    patchBlockedRecentsAndSurfaceApis()

    // 5p. BootClassLoader-shadowed blocked AIDL Stub constructors (IRemoteTransition$Stub,
    //     IWindowContainerTransactionCallback$Stub, IDisplayFoldListener$Stub,
    //     ISystemGestureExclusionListener$Stub) & rootless app/widget launch path.
    patchBlockedShellTransitionsAndStubs()
}

private fun patchBlockedRecentsAndSurfaceApis() {
    val emptyListSmali = """
        invoke-static { }, Ljava/util/Collections;->emptyList()Ljava/util/List;
        move-result-object v0
        return-object v0
    """.trimIndent()
    val emptySetSmali = """
        invoke-static { }, Ljava/util/Collections;->emptySet()Ljava/util/Set;
        move-result-object v0
        return-object v0
    """.trimIndent()
    val returnZeroSmali = """
        const/4 v0, 0x0
        return v0
    """.trimIndent()

    // ActivityManagerWrapper: IActivityManager.getTasks(I) (max-target-o), getFilteredTasks(III)
    // (removed on Android 12+), ParceledListSlice.getList() (max-target-r), closeSystemDialogs,
    // TaskInfo.userId (max-target-r), TaskInfo.stackId (removed).
    val amw = mutableClassDefBy("Lcom/android/systemui/shared/recents/system/ActivityManagerWrapper;")
    amw.findMethodOrThrow("getRunningTasks", listOf("I"), "Ljava/util/List;")
        .replaceBody(locals = 1, smali = emptyListSmali)
    amw.findMethodOrThrow("getRunningTask", listOf("I", "I"), "Ljava/util/List;")
        .replaceBody(locals = 1, smali = emptyListSmali)
    amw.findMethodOrThrow("getRecentTasks", emptyList(), "Ljava/util/List;")
        .replaceBody(locals = 1, smali = emptyListSmali)
    amw.findMethodOrThrow(
        "getRecentTasksForceIncludingTaskIdIfValid",
        listOf("I", "I", "I", "Ljava/util/Set;", "Z"),
        "Ljava/util/List;",
    ).replaceBody(locals = 1, smali = emptyListSmali)
    amw.methods.firstOrNull {
        it.name == "lambda\$closeSystemWindows\$0" && it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;")
    }?.replaceBody(locals = 0, smali = "return-void")
    amw.findMethodOrThrow("getUserId", listOf("Landroid/app/ActivityManager\$RecentTaskInfo;"), "I")
        .replaceBody(locals = 1, smali = returnZeroSmali)
    amw.findMethodOrThrow("getRunningTaskInfoUserId", listOf("Landroid/app/ActivityManager\$RunningTaskInfo;"), "I")
        .replaceBody(locals = 1, smali = returnZeroSmali)
    amw.findMethodOrThrow("getStackId", listOf("Landroid/app/ActivityManager\$RecentTaskInfo;"), "I")
        .replaceBody(
            locals = 1,
            smali = """
                const/4 v0, -0x1
                return v0
            """.trimIndent(),
        )

    // RecentTasksList: UserManager.getProfiles (MANAGE_USERS) & UserInfo.isQuietModeEnabled (max-target-o).
    val recentTasksList = mutableClassDefBy("Lcom/android/systemui/shared/recents/model/manager/RecentTasksList;")
    recentTasksList.findMethodOrThrow("getQuietProfiles", listOf("I"), "Ljava/util/Set;")
        .replaceBody(locals = 1, smali = emptySetSmali)
    recentTasksList.findMethodOrThrow("loadRecentTasks", listOf("I"), "Ljava/util/List;")
        .replaceBody(locals = 1, smali = emptyListSmali)

    // GroupedRecentTaskInfoCompat: TaskInfo.userId (max-target-r).
    val groupedInfo = mutableClassDefBy("Lcom/android/systemui/shared/recents/model/GroupedRecentTaskInfoCompat;")
    groupedInfo.findMethodOrThrow("getMainUserId", emptyList(), "I")
        .replaceBody(locals = 1, smali = returnZeroSmali)
    groupedInfo.findMethodOrThrow("getPrimaryUserId", emptyList(), "I")
        .replaceBody(locals = 1, smali = returnZeroSmali)
    groupedInfo.findMethodOrThrow("getSecondUserId", emptyList(), "I")
        .replaceBody(locals = 1, smali = returnZeroSmali)

    // PackageManagerWrapper.getHomeActivities -> public SDK PackageManager implementation.
    val pmw = mutableClassDefBy("Lcom/android/systemui/shared/recents/system/PackageManagerWrapper;")
    pmw.findMethodOrThrow("getHomeActivities", listOf("Ljava/util/List;"), "Landroid/content/ComponentName;")
        .replaceBody(
            locals = 1,
            smali = """
                invoke-static { p1 }, $COMPAT_HELPER->getHomeActivities(Ljava/util/List;)Landroid/content/ComponentName;
                move-result-object v0
                return-object v0
            """.trimIndent(),
        )

    // SurfaceControlUtils: SurfaceControl$Builder(SurfaceSession) (max-target-o) & setColorLayer (blocked).
    val scUtils = mutableClassDefBy("Lcom/android/systemui/shared/recents/system/SurfaceControlUtils;")
    scUtils.findMethodOrThrow(
        "getColorLayer",
        listOf("Ljava/lang/String;", "Lcom/android/systemui/shared/recents/system/SurfaceControlCompat;"),
        "Lcom/android/systemui/shared/recents/system/SurfaceControlCompat;",
    ).replaceBody(
        locals = 1,
        smali = """
            const/4 v0, 0x0
            return-object v0
        """.trimIndent(),
    )
    scUtils.findMethodOrThrow(
        "getBufferLayer",
        listOf("Ljava/lang/String;", "I", "I", "Lcom/android/systemui/shared/recents/system/SurfaceControlCompat;"),
        "Lcom/android/systemui/shared/recents/system/SurfaceControlCompat;",
    ).replaceBody(
        locals = 2,
        smali = """
            const/4 v0, 0x0
            if-eqz p3, :no_parent
            iget-object v0, p3, Lcom/android/systemui/shared/recents/system/SurfaceControlCompat;->mSurfaceControl:Landroid/view/SurfaceControl;
            :no_parent
            invoke-static { p0, p1, p2, v0 }, $COMPAT_HELPER->createBufferLayerSurface(Ljava/lang/String;IILandroid/view/SurfaceControl;)Landroid/view/SurfaceControl;
            move-result-object v0
            if-nez v0, :wrap
            const/4 v1, 0x0
            return-object v1
            :wrap
            new-instance v1, Lcom/android/systemui/shared/recents/system/SurfaceControlCompat;
            invoke-direct { v1, v0 }, Lcom/android/systemui/shared/recents/system/SurfaceControlCompat;-><init>(Landroid/view/SurfaceControl;)V
            return-object v1
        """.trimIndent(),
    )

    // TransactionCompat: apply(Z) (max-target-o), setCornerRadius (max-target-r),
    // setRelativeLayer (max-target-o), deferTransactionUntilSurface / setEarlyWakeup (removed).
    val txCompat = mutableClassDefBy("Lcom/android/systemui/shared/recents/system/TransactionCompat;")
    val txType = "Lcom/android/systemui/shared/recents/system/TransactionCompat;"
    val scCompatType = "Lcom/android/systemui/shared/recents/system/SurfaceControlCompat;"
    txCompat.findMethodOrThrow("apply", listOf("Z"), "V")
        .replaceBody(
            locals = 1,
            smali = """
                iget-object v0, p0, $txType->mTransaction:Landroid/view/SurfaceControl${'$'}Transaction;
                if-eqz v0, :done
                invoke-virtual { v0 }, Landroid/view/SurfaceControl${'$'}Transaction;->apply()V
                :done
                return-void
            """.trimIndent(),
        )
    txCompat.findMethodOrThrow("setCornerRadius", listOf(scCompatType, "F"), txType)
        .replaceBody(locals = 0, smali = "return-object p0")
    txCompat.findMethodOrThrow("setRelativeLayer", listOf(scCompatType, scCompatType, "I"), txType)
        .replaceBody(locals = 0, smali = "return-object p0")
    txCompat.findMethodOrThrow("deferTransactionUntilSurface", listOf(scCompatType, "Landroid/view/Surface;", "J"), txType)
        .replaceBody(locals = 0, smali = "return-object p0")
    txCompat.findMethodOrThrow("setEarlyWakeup", emptyList(), txType)
        .replaceBody(locals = 0, smali = "return-object p0")

    // SurfaceControlCompat.remove -> public SurfaceControl.release() only (remove() removed in AOSP 15).
    val scCompat = mutableClassDefBy(scCompatType)
    scCompat.findMethodOrThrow("remove", emptyList(), "V")
        .replaceBody(
            locals = 1,
            smali = """
                iget-object v0, p0, $scCompatType->mSurfaceControl:Landroid/view/SurfaceControl;
                if-eqz v0, :done
                invoke-virtual { v0 }, Landroid/view/SurfaceControl;->release()V
                :done
                return-void
            """.trimIndent(),
        )

    // SyncRtSurfaceTransactionApplierCompat$SurfaceParams.applyTo: rewrite blocked setCornerRadius / setRelativeLayer.
    val surfaceParams = mutableClassDefBy(
        "Lcom/android/systemui/shared/recents/system/SyncRtSurfaceTransactionApplierCompat\$SurfaceParams;",
    )
    val applyTo = surfaceParams.findMethodOrThrow(
        "applyTo",
        listOf("Landroid/view/SurfaceControl\$Transaction;"),
        "V",
    )
    val noopCornerRadiusRef = ImmutableMethodReference(
        COMPAT_HELPER,
        "noopSurfaceTransaction",
        listOf("Landroid/view/SurfaceControl\$Transaction;", "Landroid/view/SurfaceControl;", "F"),
        "Landroid/view/SurfaceControl\$Transaction;",
    )
    val noopRelativeLayerRef = ImmutableMethodReference(
        COMPAT_HELPER,
        "noopSurfaceTransaction",
        listOf("Landroid/view/SurfaceControl\$Transaction;", "Landroid/view/SurfaceControl;", "Landroid/view/SurfaceControl;", "I"),
        "Landroid/view/SurfaceControl\$Transaction;",
    )
    rewriteMethodCalls(applyTo) { ins, ref ->
        if (!ins.opcode.isVirtualInvoke() || ref.definingClass != "Landroid/view/SurfaceControl\$Transaction;") {
            return@rewriteMethodCalls null
        }
        when (ref.name) {
            "setCornerRadius" -> Opcode.INVOKE_STATIC to noopCornerRadiusRef
            "setRelativeLayer" -> Opcode.INVOKE_STATIC to noopRelativeLayerRef
            else -> null
        }
    }

    // InternalInsetsInfoCompat: InternalInsetsInfo.touchableRegion (max-target-r).
    val insetsCompat = mutableClassDefBy("Lcom/android/systemui/shared/recents/touchableRegion/InternalInsetsInfoCompat;")
    insetsCompat.findMethodOrThrow("setTouchableRegion", listOf("Landroid/graphics/Region;"), "V")
        .replaceBody(locals = 0, smali = "return-void")
    insetsCompat.findMethodOrThrow("setTouchableRegionPath", listOf("Landroid/graphics/Path;", "Landroid/graphics/Region;"), "V")
        .replaceBody(locals = 0, smali = "return-void")

    // ActivityCompat.encodeViewHierarchy: ViewHierarchyEncoder / View.encode (max-target-o).
    val actCompat = mutableClassDefBy("Lcom/android/systemui/shared/recents/system/ActivityCompat;")
    actCompat.findMethodOrThrow("encodeViewHierarchy", listOf("Ljava/io/ByteArrayOutputStream;"), "Z")
        .replaceBody(locals = 1, smali = returnZeroSmali)
}

private fun patchBlockedShellTransitionsAndStubs() {
    val returnNullSmali = """
        const/4 v0, 0x0
        return-object v0
    """.trimIndent()
    val returnZeroSmali = """
        const/4 v0, 0x0
        return v0
    """.trimIndent()

    // 1. LauncherAppTransitionManager: return concrete base class (uses public SDK makeClipRevealAnimation)
    //    instead of QuickstepAppTransitionManagerImpl, whose RemoteTransitionBase subclasses
    //    android.window.IRemoteTransition$Stub (<init>()V is blocked by Android 14+ BootClassLoader).
    val appTransMgr = mutableClassDefBy("Lcom/miui/home/recents/LauncherAppTransitionManager;")
    appTransMgr.findMethodOrThrow(
        "newInstance",
        listOf("Landroid/content/Context;"),
        "Lcom/miui/home/recents/LauncherAppTransitionManager;",
    ).replaceBody(
        locals = 1,
        smali = """
            new-instance v0, Lcom/miui/home/recents/LauncherAppTransitionManager;
            invoke-direct { v0 }, Lcom/miui/home/recents/LauncherAppTransitionManager;-><init>()V
            return-object v0
        """.trimIndent(),
    )
    appTransMgr.findMethodOrThrow("onFsGestureStart", emptyList(), "V")
        .replaceBody(locals = 0, smali = "return-void")
    appTransMgr.findMethodOrThrow("registerRemoteTransitions", emptyList(), "V")
        .replaceBody(locals = 0, smali = "return-void")
    appTransMgr.findMethodOrThrow("unregisterRemoteTransitions", emptyList(), "V")
        .replaceBody(locals = 0, smali = "return-void")

    // 2. StateManager.onLauncherStartActivity & WindowElement.getActivityOptions:
    //    Launch apps & widgets directly via BaseLauncher with public ActivityOptions.makeClipRevealAnimation
    //    instead of constructing WindowElement / WindowTransitionCompat / RemoteAnimationAdapterCompat
    //    (all of which extend blocked IRemoteTransition$Stub / IWindowContainerTransactionCallback$Stub).
    val stateManager = mutableClassDefBy("Lcom/miui/home/recents/anim/StateManager;")
    stateManager.findMethodOrThrow(
        "onLauncherStartActivity",
        listOf(
            "Landroid/content/Intent;",
            "Ljava/lang/Object;",
            "Landroid/view/View;",
            "Lcom/miui/home/recents/event/WidgetClickEventInfo;",
        ),
        "V",
    ).replaceBody(
        locals = 0,
        smali = """
            invoke-static { p1, p2, p3, p4 }, $COMPAT_HELPER->launchActivityFromStateManager(Landroid/content/Intent;Ljava/lang/Object;Landroid/view/View;Ljava/lang/Object;)V
            return-void
        """.trimIndent(),
    )

    val windowElement = mutableClassDefBy("Lcom/miui/home/recents/anim/WindowElement;")
    windowElement.findMethodOrThrow(
        "getActivityOptions",
        listOf(
            "Landroid/view/View;",
            "Landroid/window/RemoteTransition;",
            "Lcom/miui/home/recents/FastLaunchData;",
        ),
        "Landroid/app/ActivityOptions;",
    ).replaceBody(
        locals = 1,
        smali = """
            invoke-static { p1 }, $COMPAT_HELPER->makeClipRevealOptions(Landroid/view/View;)Landroid/app/ActivityOptions;
            move-result-object v0
            return-object v0
        """.trimIndent(),
    )

    // 3. RemoteAnimationFinishCallbackManager & WindowTransitionCallbackHelper:
    //    Called on every BaseLauncher.onNewIntent (Home press). Null out blocked
    //    IWindowContainerTransactionCallback$Stub anonymous subclass instantiations and reuse
    //    BackgroundThread instead of spawning a permanently-resident "HomeShellAnim" HandlerThread.
    val finishCbMgr = mutableClassDefBy(
        "Lcom/android/systemui/shared/recents/utilities/RemoteAnimationFinishCallbackManager;",
    )
    val finishCtor = finishCbMgr.findMethodOrThrow("<init>", emptyList(), "V")
    nullOutBlockedInstanceInit(
        finishCtor,
        "Lcom/android/systemui/shared/recents/utilities/RemoteAnimationFinishCallbackManager\$1;",
    )
    val finishInsns = finishCtor.instructions.toList()
    val htNewIdx = finishInsns.indexOfFirst { ins ->
        ins.opcode == Opcode.NEW_INSTANCE &&
            ((ins as? ReferenceInstruction)?.reference as? TypeReference)?.type == "Landroid/os/HandlerThread;"
    }
    check(htNewIdx >= 0) {
        "POCO Launcher: new-instance HandlerThread não encontrado em RemoteAnimationFinishCallbackManager.<init>"
    }
    val putHandlerIdx = (htNewIdx + 1 until minOf(htNewIdx + 16, finishInsns.size)).firstOrNull { idx ->
        finishInsns[idx].opcode == Opcode.IPUT_OBJECT &&
            ((finishInsns[idx] as? ReferenceInstruction)?.reference as? FieldReference)?.name == "mWorkHandler"
    } ?: throw PatchException(
        "POCO Launcher: iput-object mWorkHandler não encontrado em RemoteAnimationFinishCallbackManager.<init>",
    )
    val htScratchReg = (finishInsns[htNewIdx] as OneRegisterInstruction).registerA
    for (idx in putHandlerIdx downTo htNewIdx + 1) {
        finishCtor.removeInstruction(idx)
    }
    finishCtor.addInstructions(
        htNewIdx + 1,
        """
            iput-object v$htScratchReg, p0, Lcom/android/systemui/shared/recents/utilities/RemoteAnimationFinishCallbackManager;->mWorkThread:Landroid/os/HandlerThread;
            invoke-static { }, Lcom/miui/home/common/multithread/BackgroundThread;->getHandler()Landroid/os/Handler;
            move-result-object v$htScratchReg
            iput-object v$htScratchReg, p0, Lcom/android/systemui/shared/recents/utilities/RemoteAnimationFinishCallbackManager;->mWorkHandler:Landroid/os/Handler;
        """.trimIndent(),
    )
    val zeroHtIns = if (htScratchReg < 16) "const/4 v$htScratchReg, 0x0" else "const/16 v$htScratchReg, 0x0"
    finishCtor.replaceInstruction(htNewIdx, zeroHtIns)

    val winTransHelper = mutableClassDefBy(
        "Lcom/android/systemui/shared/recents/utilities/WindowTransitionCallbackHelper;",
    )
    nullOutBlockedInstanceInit(
        winTransHelper.findMethodOrThrow(
            "<init>",
            listOf("Lcom/android/systemui/shared/recents/utilities/ShellTransitionCallback;"),
            "V",
        ),
        "Lcom/android/systemui/shared/recents/utilities/WindowTransitionCallbackHelper\$1;",
    )
    winTransHelper.findMethodOrThrow(
        "executeFinishCallback",
        listOf(
            "Z",
            "Z",
            "I",
            "Landroid/window/WindowContainerTransaction;",
            "Lcom/android/systemui/shared/recents/utilities/ShellTransitionCallback;",
            "Z",
            "Z",
        ),
        "V",
    ).replaceBody(
        locals = 1,
        smali = """
            if-eqz p5, :cb2
            invoke-interface { p5 }, Lcom/android/systemui/shared/recents/utilities/ShellTransitionCallback;->onFinish()V
            :cb2
            iget-object v0, p0, Lcom/android/systemui/shared/recents/utilities/WindowTransitionCallbackHelper;->windowTransitionCompatCallback:Lcom/android/systemui/shared/recents/utilities/ShellTransitionCallback;
            if-eqz v0, :done
            invoke-interface { v0 }, Lcom/android/systemui/shared/recents/utilities/ShellTransitionCallback;->onFinish()V
            :done
            return-void
        """.trimIndent(),
    )

    // 4. Defense-in-depth for remaining BootClassLoader-shadowed blocked AIDL Stub instantiations:
    //    - FoldDisplayChangeManager (IDisplayFoldListener$Stub.<init>, called on foldable phones)
    //    - SystemGestureExclusionListener (ISystemGestureExclusionListener$Stub.<init>)
    //    - WindowTransitionCompat ($2/$3 extending IRemoteTransition$Stub.<init>)
    //    - RemoteTransitionCompat ($1 extending IRemoteTransition$Stub.<init> & RemoteTransition.CREATOR)
    //    - RemoteAnimationAdapterCompat ($2 extending IRemoteTransition$Stub.<init>)
    //    - RemoteTransitionExpose & ActivityOptionsCompat.makeRemoteAnimation
    val foldMgr = mutableClassDefBy("Lcom/miui/launcher/common/FoldDisplayChangeManager;")
    foldMgr.findMethodOrThrow("<init>", emptyList(), "V").replaceBody(
        locals = 0,
        smali = """
            invoke-direct { p0 }, Ljava/lang/Object;-><init>()V
            return-void
        """.trimIndent(),
    )
    foldMgr.findMethodOrThrow(
        "addAndStartMonitor",
        listOf("Lcom/miui/launcher/common/FoldDisplayChangeListener;"),
        "V",
    ).replaceBody(locals = 0, smali = "return-void")
    foldMgr.findMethodOrThrow("stopMonitor", emptyList(), "V")
        .replaceBody(locals = 0, smali = "return-void")

    val sysGestureExcl = mutableClassDefBy("Lcom/miui/home/recents/SystemGestureExclusionListener;")
    sysGestureExcl.findMethodOrThrow("<init>", emptyList(), "V").replaceBody(
        locals = 1,
        smali = """
            invoke-direct { p0 }, Ljava/lang/Object;-><init>()V
            new-instance v0, Landroid/graphics/Region;
            invoke-direct { v0 }, Landroid/graphics/Region;-><init>()V
            iput-object v0, p0, Lcom/miui/home/recents/SystemGestureExclusionListener;->mBackGestureExcludedRegion:Landroid/graphics/Region;
            return-void
        """.trimIndent(),
    )
    sysGestureExcl.findMethodOrThrow("unregisterGestureExclusionListener", emptyList(), "V")
        .replaceBody(locals = 0, smali = "return-void")

    val winTransCompat = mutableClassDefBy("Lcom/android/systemui/shared/recents/system/WindowTransitionCompat;")
    val winTransCtor = winTransCompat.findMethodOrThrow(
        "<init>",
        listOf("Lcom/android/systemui/shared/recents/system/WindowTransitionCompat\$WindowTransitionCompatListener;", "Z"),
        "V",
    )
    nullOutBlockedInstanceInit(
        winTransCtor,
        "Lcom/android/systemui/shared/recents/system/WindowTransitionCompat\$2;",
    )
    nullOutBlockedInstanceInit(
        winTransCtor,
        "Lcom/android/systemui/shared/recents/system/WindowTransitionCompat\$3;",
    )

    val remoteTransCompat = mutableClassDefBy("Lcom/android/systemui/shared/system/RemoteTransitionCompat;")
    remoteTransCompat.findMethodOrThrow(
        "newRemoteTransitionForRunner",
        listOf(
            "Lcom/android/systemui/shared/recents/system/RemoteTransitionRunner;",
            "Ljava/util/concurrent/Executor;",
            "Landroid/content/ContextWrapper;",
        ),
        "Landroid/window/RemoteTransition;",
    ).replaceBody(locals = 1, smali = returnNullSmali)
    remoteTransCompat.findMethodOrThrow("setFilter", listOf("Landroid/window/TransitionFilter;"), "V")
        .replaceBody(locals = 0, smali = "return-void")

    val remoteAnimAdapter = mutableClassDefBy("Lcom/android/systemui/shared/recents/system/RemoteAnimationAdapterCompat;")
    remoteAnimAdapter.methods
        .filter { it.name == "buildRemoteTransition" }
        .toList()
        .forEach { it.replaceBody(locals = 1, smali = returnNullSmali) }

    val remoteTransExpose = mutableClassDefBy("Lcom/android/hideapi/RemoteTransitionExpose;")
    remoteTransExpose.methods
        .filter { it.name == "newInstance" }
        .toList()
        .forEach { it.replaceBody(locals = 1, smali = returnNullSmali) }

    val actOptsCompat = mutableClassDefBy("Lcom/android/systemui/shared/recents/system/ActivityOptionsCompat;")
    actOptsCompat.methods
        .filter { it.name == "makeRemoteAnimation" && it.returnType == "Landroid/app/ActivityOptions;" }
        .toList()
        .forEach {
            it.replaceBody(
                locals = 1,
                smali = """
                    invoke-static { }, Landroid/app/ActivityOptions;->makeBasic()Landroid/app/ActivityOptions;
                    move-result-object v0
                    return-object v0
                """.trimIndent(),
            )
        }

    // 5. Market SDK Utils.isFirstBoot(): IPackageManager.isFirstBoot() is max-target-o blocked.
    val marketUtils = mutableClassDefBy("Lcom/market/sdk/utils/Utils;")
    marketUtils.findMethodOrThrow("isFirstBoot", emptyList(), "Z")
        .replaceBody(locals = 1, smali = returnZeroSmali)
}

private fun nullOutBlockedInstanceInit(method: MutableMethod, blockedType: String) {
    val insns = method.instructions.toList()
    val newIdx = insns.indexOfFirst { ins ->
        ins.opcode == Opcode.NEW_INSTANCE &&
            (ins as? ReferenceInstruction)?.reference
                .let { it as? TypeReference }
                ?.type == blockedType
    }
    check(newIdx >= 0) {
        "POCO Launcher: new-instance $blockedType não encontrado em ${method.definingClass}->${method.name}"
    }
    val reg = (insns[newIdx] as OneRegisterInstruction).registerA
    val initIdx = (newIdx + 1 until minOf(newIdx + 6, insns.size)).firstOrNull { idx ->
        val ins = insns[idx]
        ins.opcode == Opcode.INVOKE_DIRECT &&
            (ins as? ReferenceInstruction)?.reference
                .let { it as? MethodReference }
                ?.let { it.definingClass == blockedType && it.name == "<init>" } == true
    } ?: throw PatchException(
        "POCO Launcher: invoke-direct $blockedType-><init> não encontrado em ${method.definingClass}->${method.name}",
    )
    method.removeInstruction(initIdx)
    val zeroIns = if (reg < 16) "const/4 v$reg, 0x0" else "const/16 v$reg, 0x0"
    method.replaceInstruction(newIdx, zeroIns)
}

// ==================== 6. Runtime / Privilege Crash Fixes ====================

private fun patchRuntimeAndPrivilegeCrashes() {
    // 6a. FallbackHomeCompat.needKillSelf -> false (never kill POCO when Samsung One UI is default home).
    FallbackHomeNeedKillSelfFingerprint.method.replaceBody(
        locals = 1,
        smali = """
            const/4 v0, 0x0
            return v0
        """.trimIndent(),
    )

    // 6b. StatusBarController$H.handleMessage -> skip privileged StatusBarManager.disable, keep Runnable callbacks.
    StatusBarHandleMessageFingerprint.method.replaceBody(
        locals = 0,
        smali = """
            invoke-static { p1 }, $COMPAT_HELPER->handleStatusBarMessage(Landroid/os/Message;)V
            return-void
        """.trimIndent(),
    )

    // 6c. Background/listener reflection crashes on non-MIUI / Android 14.
    KeyguardOccludedRegisterFingerprint.method.replaceBody(locals = 0, smali = "return-void")
    HighContrastRegisterFingerprint.method.replaceBody(locals = 0, smali = "return-void")
    HighContrastUnregisterFingerprint.method.replaceBody(locals = 0, smali = "return-void")

    // 6d. PowerKeeper permission prompt & privileged gesture overlay windows.
    BaseLauncherRequestPowerKeeperFingerprint.method.replaceBody(locals = 0, smali = "return-void")
    BaseRecentsCreateNavStubFingerprint.method.replaceBody(locals = 0, smali = "return-void")
    BaseRecentsAddBackStubFingerprint.method.replaceBody(locals = 0, smali = "return-void")

    // 6e. DeviceConfig.loadThemeResource -> avoid ClassCastException(Resources -> MiuiResources).
    DeviceConfigLoadThemeResourceFingerprint.method.replaceBody(
        locals = 1,
        smali = """
            new-instance v0, Lmiui/content/res/ThemeValues;
            invoke-direct { v0 }, Lmiui/content/res/ThemeValues;-><init>()V
            sput-object v0, Lcom/miui/home/launcher/DeviceConfig;->sThemeValues:Lmiui/content/res/ThemeValues;
            return-void
        """.trimIndent(),
    )

    // 6f. Application.hasBroughtToForeground -> guard empty getRunningTasks(1) list.
    ApplicationHasBroughtToForegroundFingerprint.method.replaceBody(
        locals = 1,
        smali = """
            iget-object v0, p0, Lcom/miui/home/launcher/Application;->mLauncher:Lcom/miui/home/launcher/BaseLauncher;
            invoke-static { v0 }, $COMPAT_HELPER->safeHasBroughtToForeground(Landroid/app/Activity;)Z
            move-result v0
            return v0
        """.trimIndent(),
    )

    // 6g. LauncherUtils privileged / hidden methods.
    LauncherUtilsStartActivityAsUserFingerprint.method.replaceBody(
        locals = 0,
        smali = """
            invoke-static { p0, p1, p2, p3 }, $COMPAT_HELPER->startActivityAsUser(Landroid/content/Context;Landroid/content/Intent;Landroid/os/Bundle;Landroid/os/UserHandle;)V
            return-void
        """.trimIndent(),
    )
    LauncherUtilsRegisterReceiverAsUser6Fingerprint.method.replaceBody(
        locals = 0,
        smali = """
            invoke-static/range { p0 .. p5 }, $COMPAT_HELPER->registerReceiverAsUser(Landroid/content/Context;Landroid/content/BroadcastReceiver;Landroid/os/UserHandle;Landroid/content/IntentFilter;Ljava/lang/String;Landroid/os/Handler;)V
            return-void
        """.trimIndent(),
    )
    LauncherUtilsRegisterReceiverAsUser7Fingerprint.method.replaceBody(
        locals = 0,
        smali = """
            invoke-static/range { p0 .. p6 }, $COMPAT_HELPER->registerReceiverAsUser(Landroid/content/Context;Landroid/content/BroadcastReceiver;Landroid/os/UserHandle;Landroid/content/IntentFilter;Ljava/lang/String;Landroid/os/Handler;I)V
            return-void
        """.trimIndent(),
    )
    LauncherUtilsGetSharedPrefsFileFingerprint.method.replaceBody(
        locals = 1,
        smali = """
            invoke-static { p0, p1 }, $COMPAT_HELPER->getSharedPrefsFile(Landroid/content/Context;Ljava/lang/String;)Ljava/io/File;
            move-result-object v0
            return-object v0
        """.trimIndent(),
    )
    LauncherUtilsIsPackageDisableAsUserFingerprint.method.replaceBody(
        locals = 1,
        smali = """
            invoke-static { p0, p1, p2 }, $COMPAT_HELPER->isPackageDisableAsUser(Landroid/content/Context;Ljava/lang/String;I)Z
            move-result v0
            return v0
        """.trimIndent(),
    )
    LauncherUtilsHasRelativeXSpaceAppFingerprint.method.replaceBody(
        locals = 1,
        smali = """
            const/4 v0, 0x0
            return v0
        """.trimIndent(),
    )
    LauncherUtilsSetProcessForegroundFingerprint.method.replaceBody(locals = 0, smali = "return-void")
    LauncherUtilsSetAsyncTaskExecutorFingerprint.method.replaceBody(locals = 0, smali = "return-void")
    LauncherUtilsGetAllUserHandleFingerprint.method.replaceBody(
        locals = 1,
        smali = """
            invoke-static { }, Landroid/os/Process;->myUserHandle()Landroid/os/UserHandle;
            move-result-object v0
            return-object v0
        """.trimIndent(),
    )
    LauncherUtilsGetCurrentUserHandleFingerprint.method.replaceBody(
        locals = 1,
        smali = """
            invoke-static { }, Landroid/os/Process;->myUserHandle()Landroid/os/UserHandle;
            move-result-object v0
            return-object v0
        """.trimIndent(),
    )
    LauncherUtilsGetAppLaunchCountFingerprint.method.replaceBody(
        locals = 1,
        smali = """
            invoke-static { p0 }, $COMPAT_HELPER->safeGetAppLaunchCount(Ljava/lang/Object;)I
            move-result v0
            return v0
        """.trimIndent(),
    )

    // 6h. PortableUtils hidden / privileged methods.
    PortableUtilsQueryIntentActivitiesAsUserFingerprint.method.replaceBody(
        locals = 1,
        smali = """
            invoke-virtual { p0 }, Landroid/content/Context;->getPackageManager()Landroid/content/pm/PackageManager;
            move-result-object v0
            invoke-virtual { v0, p1, p2 }, Landroid/content/pm/PackageManager;->queryIntentActivities(Landroid/content/Intent;I)Ljava/util/List;
            move-result-object v0
            return-object v0
        """.trimIndent(),
    )
    PortableUtilsGetCurrentWallpaperInfoFingerprint.method.replaceBody(
        locals = 1,
        smali = """
            const/4 v0, 0x0
            return-object v0
        """.trimIndent(),
    )
    PortableUtilsUpdateCurrentWallpaperInfoFingerprint.method.replaceBody(locals = 0, smali = "return-void")
    PortableUtilsMoveFilesFingerprint.method.replaceBody(
        locals = 1,
        smali = """
            invoke-static { p0, p1, p2 }, $COMPAT_HELPER->moveFiles(Ljava/io/File;Ljava/io/File;Ljava/lang/String;)I
            move-result v0
            return v0
        """.trimIndent(),
    )

    // 6i. WindowManagerWrapper & TaskStackChangeListeners privileged SystemUI/QuickStep calls.
    val wmWrapperClass = mutableClassDefBy("Lcom/android/systemui/shared/recents/system/WindowManagerWrapper;")
    val wmNoOpMethods = setOf(
        "getStableInsets",
        "overridePendingAppTransitionMultiThumbFuture",
        "overridePendingAppTransitionRemote",
        "setNavBarVirtualKeyHapticFeedbackEnabled",
        "setShelfHeight",
        "setRecentsVisibility",
    )
    wmWrapperClass.methods
        .filter { it.name in wmNoOpMethods && it.returnType == "V" }
        .toList()
        .forEach { method ->
            method.replaceBody(locals = 0, smali = "return-void")
        }
    TaskStackRegisterListenerFingerprint.method.replaceBody(locals = 0, smali = "return-void")

    // 6j. com.miui.launcher.utils.SystemProperties -> null-safe public reflection wrappers, set() no-op.
    SystemPropertiesGet1Fingerprint.method.replaceBody(
        locals = 1,
        smali = """
            const-string v0, ""
            invoke-static { p0, v0 }, $COMPAT_HELPER->sysPropGet(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
            move-result-object v0
            return-object v0
        """.trimIndent(),
    )
    SystemPropertiesGet2Fingerprint.method.replaceBody(
        locals = 1,
        smali = """
            invoke-static { p0, p1 }, $COMPAT_HELPER->sysPropGet(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
            move-result-object v0
            return-object v0
        """.trimIndent(),
    )
    SystemPropertiesGetIntFingerprint.method.replaceBody(
        locals = 1,
        smali = """
            invoke-static { p0, p1 }, $COMPAT_HELPER->sysPropGetInt(Ljava/lang/String;I)I
            move-result v0
            return v0
        """.trimIndent(),
    )
    SystemPropertiesGetBooleanFingerprint.method.replaceBody(
        locals = 1,
        smali = """
            invoke-static { p0, p1 }, $COMPAT_HELPER->sysPropGetBoolean(Ljava/lang/String;Z)Z
            move-result v0
            return v0
        """.trimIndent(),
    )
    SystemPropertiesGetBooleanNoCheckFingerprint.method.replaceBody(
        locals = 1,
        smali = """
            invoke-static { p0, p1 }, $COMPAT_HELPER->sysPropGetBoolean(Ljava/lang/String;Z)Z
            move-result v0
            return v0
        """.trimIndent(),
    )
    SystemPropertiesSetFingerprint.method.replaceBody(locals = 0, smali = "return-void")

    // 6k. Market SDK background custom receivers (2-arg registerReceiver throws SecurityException on API 34+).
    EnableStateManagerRegisterReceiverFingerprint.method.replaceBody(locals = 0, smali = "return-void")
    UpdateResultReceiverRegisterFingerprint.method.replaceBody(locals = 0, smali = "return-void")

    // 6l. SafeModeManager: never enter Xiaomi SafeLauncher or silently swallow crashes with killProcess.
    val safeModeManager = mutableClassDefBy("Lcom/miui/home/safemode/SafeModeManager;")
    safeModeManager.findMethodOrThrow("needEnterSafeMode", listOf("Z", "Z"), "Z").replaceBody(
        locals = 1,
        smali = """
            const/4 v0, 0x0
            return v0
        """.trimIndent(),
    )

    // 6m. GoogleRecommendApp.createAppPredictionSession: requires PACKAGE_USAGE_STATS on Android 14+.
    //     Guard via PocoCompatHelper so rootless devices without Usage Access fall back cleanly to
    //     local MiuiRecommendApp instead of crashing ~2.4s after boot.
    val googleRecommendApp = mutableClassDefBy("Lcom/miui/launcher/appprediction/GoogleRecommendApp;")
    googleRecommendApp.findMethodOrThrow("createAppPredictionSession", listOf("I"), "V").replaceBody(
        locals = 2,
        smali = """
            iget-object v0, p0, Lcom/miui/launcher/appprediction/GoogleRecommendApp;->mAppPredictionManager:Landroid/app/prediction/AppPredictionManager;
            if-eqz v0, :done
            iget-object v1, p0, Lcom/miui/launcher/appprediction/GoogleRecommendApp;->mContext:Landroid/content/Context;
            invoke-static { v0, v1, p1 }, $COMPAT_HELPER->safeCreateAppPredictionSession(Ljava/lang/Object;Landroid/content/Context;I)Ljava/lang/Object;
            move-result-object v0
            check-cast v0, Landroid/app/prediction/AppPredictor;
            iput-object v0, p0, Lcom/miui/launcher/appprediction/GoogleRecommendApp;->mPredictor:Landroid/app/prediction/AppPredictor;
            :done
            return-void
        """.trimIndent(),
    )

    // 6n. UserManagerCompatVP.requestQuietModeEnabled: throws SecurityException when not default Home app.
    val userMgrVP = mutableClassDefBy("Lcom/miui/home/library/compat/UserManagerCompatVP;")
    userMgrVP.findMethodOrThrow(
        "requestQuietModeEnabled",
        listOf("Landroid/content/Context;", "Z", "Landroid/os/UserHandle;"),
        "Z",
    ).replaceBody(
        locals = 1,
        smali = """
            invoke-static { p1, p2, p3 }, $COMPAT_HELPER->safeRequestQuietModeEnabled(Landroid/content/Context;ZLandroid/os/UserHandle;)Z
            move-result v0
            return v0
        """.trimIndent(),
    )

    // 6o. PinShortcutRequestUtils.getFullResIcon: LauncherApps.getShortcutIconDrawable throws
    //     SecurityException when POCO Launcher is not yet the default Home app.
    val pinShortcutUtils = mutableClassDefBy("Lcom/miui/launcher/common/PinShortcutRequestUtils;")
    pinShortcutUtils.findMethodOrThrow(
        "getFullResIcon",
        listOf("Landroid/content/Context;", "Ljava/lang/Object;"),
        "Landroid/graphics/drawable/Drawable;",
    ).replaceBody(
        locals = 1,
        smali = """
            invoke-static { p0, p1 }, $COMPAT_HELPER->safeGetShortcutIconDrawable(Landroid/content/Context;Ljava/lang/Object;)Landroid/graphics/drawable/Drawable;
            move-result-object v0
            return-object v0
        """.trimIndent(),
    )

    // 6p. miuix.core.util.SystemProperties.set: writing system properties fails non-root.
    val miuixSysProps = mutableClassDefBy("Lmiuix/core/util/SystemProperties;")
    miuixSysProps.findMethodOrThrow("set", listOf("Ljava/lang/String;", "Ljava/lang/String;"), "V")
        .replaceBody(locals = 0, smali = "return-void")

    // 6q. BaseLauncher.makeUnableToStartActivityToast & BaseLauncher.onResume: clear
    //     mWaitingForResult so a failed or aborted startActivityForResult (ActivityNotFoundException /
    //     SecurityException / canceled system dialog) never leaves BaseLauncher.isActivityLocked()
    //     stuck true and blocking all touches.
    val baseLauncher = mutableClassDefBy("Lcom/miui/home/launcher/BaseLauncher;")
    val clearWaitingForResultSmali = """
        const/4 v0, 0x0
        iput-boolean v0, p0, Lcom/miui/home/launcher/BaseLauncher;->mWaitingForResult:Z
    """.trimIndent()
    baseLauncher.findMethodOrThrow("makeUnableToStartActivityToast", emptyList(), "V")
        .addInstructions(0, clearWaitingForResultSmali)
    baseLauncher.findMethodOrThrow("onResume", emptyList(), "V")
        .addInstructions(0, clearWaitingForResultSmali)
}

// ==================== 6b. CPU, Battery & Background Overhead Reduction ====================

private fun patchCpuBatteryAndBackgroundOverhead() {
    val returnFalseSmali = """
        const/4 v0, 0x0
        return v0
    """.trimIndent()
    val returnTrueSmali = """
        const/4 v0, 0x1
        return v0
    """.trimIndent()

    // 1. Thread-safe negative-caching ReflectUtils backend (417 call sites across 110 files!).
    //    Stock ReflectUtils uses unsynchronized HashMaps and NEVER negative-caches missing MIUI
    //    classes/methods/fields (and callObjectMethod/callObjectMethod2/getObjectField aren't
    //    cached at all), throwing + logging full stack traces on every UI/blur/animation frame.
    val reflectUtils = mutableClassDefBy("Lcom/miui/launcher/utils/ReflectUtils;")
    reflectUtils.findMethodOrThrow("getClass", listOf("Ljava/lang/String;"), "Ljava/lang/Class;").replaceBody(
        locals = 1,
        smali = """
            invoke-static { p0 }, $COMPAT_HELPER->reflectGetClass(Ljava/lang/String;)Ljava/lang/Class;
            move-result-object v0
            return-object v0
        """.trimIndent(),
    )
    reflectUtils.findMethodOrThrow(
        "hasMethod",
        listOf("Ljava/lang/Class;", "Ljava/lang/String;", "[Ljava/lang/Class;"),
        "Z",
    ).replaceBody(
        locals = 1,
        smali = """
            invoke-static { p0, p1, p2 }, $COMPAT_HELPER->reflectHasMethod(Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/Class;)Z
            move-result v0
            return v0
        """.trimIndent(),
    )
    reflectUtils.findMethodOrThrow(
        "getMethod",
        listOf("Ljava/lang/Class;", "Ljava/lang/String;", "Ljava/lang/String;", "[Ljava/lang/Class;"),
        "Ljava/lang/reflect/Method;",
    ).replaceBody(
        locals = 1,
        smali = """
            invoke-static { p0, p1, p2, p3 }, $COMPAT_HELPER->reflectGetMethod(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;
            move-result-object v0
            return-object v0
        """.trimIndent(),
    )
    reflectUtils.findMethodOrThrow(
        "getDeclaredMethod",
        listOf("Ljava/lang/Class;", "Ljava/lang/String;", "Ljava/lang/String;", "[Ljava/lang/Class;"),
        "Ljava/lang/reflect/Method;",
    ).replaceBody(
        locals = 1,
        smali = """
            invoke-static { p0, p1, p2, p3 }, $COMPAT_HELPER->reflectGetDeclaredMethod(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;
            move-result-object v0
            return-object v0
        """.trimIndent(),
    )
    reflectUtils.findMethodOrThrow(
        "getField",
        listOf("Ljava/lang/Class;", "Ljava/lang/String;", "Ljava/lang/String;"),
        "Ljava/lang/reflect/Field;",
    ).replaceBody(
        locals = 1,
        smali = """
            invoke-static { p0, p1, p2 }, $COMPAT_HELPER->reflectGetField(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/reflect/Field;
            move-result-object v0
            return-object v0
        """.trimIndent(),
    )
    reflectUtils.findMethodOrThrow(
        "getObjectField",
        listOf("Ljava/lang/Object;", "Ljava/lang/String;"),
        "Ljava/lang/Object;",
    ).replaceBody(
        locals = 1,
        smali = """
            invoke-static { p0, p1 }, $COMPAT_HELPER->reflectGetObjectField(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;
            move-result-object v0
            return-object v0
        """.trimIndent(),
    )
    reflectUtils.findMethodOrThrow(
        "callObjectMethod",
        listOf("Ljava/lang/Object;", "Ljava/lang/Class;", "Ljava/lang/String;", "[Ljava/lang/Class;", "[Ljava/lang/Object;"),
        "Ljava/lang/Object;",
    ).replaceBody(
        locals = 1,
        smali = """
            invoke-static { p0, p1, p2, p3, p4 }, $COMPAT_HELPER->reflectCallObjectMethod(Ljava/lang/Object;Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/Class;[Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object v0
            return-object v0
        """.trimIndent(),
    )
    reflectUtils.findMethodOrThrow(
        "callObjectMethod2",
        listOf("Ljava/lang/Object;", "Ljava/lang/Class;", "Ljava/lang/String;", "[Ljava/lang/Class;", "[Ljava/lang/Object;"),
        "Ljava/lang/Object;",
    ).replaceBody(
        locals = 1,
        smali = """
            invoke-static { p0, p1, p2, p3, p4 }, $COMPAT_HELPER->reflectCallObjectMethod2(Ljava/lang/Object;Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/Class;[Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object v0
            return-object v0
        """.trimIndent(),
    )
    reflectUtils.findMethodOrThrow(
        "callStaticMethod",
        listOf("Ljava/lang/String;", "Ljava/lang/Class;", "Ljava/lang/String;", "[Ljava/lang/Class;", "[Ljava/lang/Object;"),
        "Ljava/lang/Object;",
    ).replaceBody(
        locals = 1,
        smali = """
            invoke-static { p0, p1, p2, p3, p4 }, $COMPAT_HELPER->reflectCallStaticMethod(Ljava/lang/String;Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/Class;[Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object v0
            return-object v0
        """.trimIndent(),
    )
    reflectUtils.findMethodOrThrow(
        "createNewInstance",
        listOf("Ljava/lang/Class;", "[Ljava/lang/Class;", "[Ljava/lang/Object;"),
        "Ljava/lang/Object;",
    ).replaceBody(
        locals = 1,
        smali = """
            invoke-static { p0, p1, p2 }, $COMPAT_HELPER->reflectCreateNewInstance(Ljava/lang/Class;[Ljava/lang/Class;[Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object v0
            return-object v0
        """.trimIndent(),
    )
    reflectUtils.findMethodOrThrow(
        "invoke",
        listOf("Ljava/lang/Class;", "Ljava/lang/Object;", "Ljava/lang/String;", "Ljava/lang/Class;", "[Ljava/lang/Class;", "[Ljava/lang/Object;"),
        "V",
    ).replaceBody(
        locals = 0,
        smali = """
            invoke-static/range { p0 .. p5 }, $COMPAT_HELPER->reflectInvoke(Ljava/lang/Class;Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Class;[Ljava/lang/Class;[Ljava/lang/Object;)V
            return-void
        """.trimIndent(),
    )
    reflectUtils.findMethodOrThrow(
        "invokeObject",
        listOf("Ljava/lang/Class;", "Ljava/lang/Object;", "Ljava/lang/String;", "Ljava/lang/Class;", "[Ljava/lang/Class;", "[Ljava/lang/Object;"),
        "Ljava/lang/Object;",
    ).replaceBody(
        locals = 1,
        smali = """
            invoke-static/range { p0 .. p5 }, $COMPAT_HELPER->reflectInvokeObject(Ljava/lang/Class;Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Class;[Ljava/lang/Class;[Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object v0
            return-object v0
        """.trimIndent(),
    )
    reflectUtils.findMethodOrThrow(
        "invokeObject",
        listOf("Ljava/lang/Class;", "Ljava/lang/Object;", "Ljava/lang/String;", "Ljava/lang/Class;", "Ljava/lang/Object;", "[Ljava/lang/Class;", "[Ljava/lang/Object;"),
        "Ljava/lang/Object;",
    ).replaceBody(
        locals = 1,
        smali = """
            invoke-static/range { p0 .. p6 }, $COMPAT_HELPER->reflectInvokeObjectDefault(Ljava/lang/Class;Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Class;Ljava/lang/Object;[Ljava/lang/Class;[Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object v0
            return-object v0
        """.trimIndent(),
    )

    // 2. ApplicationsMessage.initSuperscriptCountObserver: eliminates MIUI-only
    //    ScriptCountObserver -> write-back -> observer notification feedback storm.
    val appMsg = mutableClassDefBy("Lcom/miui/home/launcher/ApplicationsMessage;")
    appMsg.findMethodOrThrow("initSuperscriptCountObserver", emptyList(), "V")
        .replaceBody(locals = 0, smali = "return-void")

    // 3. Disable Firebase Analytics/RemoteConfig, AppFinder remote sync, OneTrack, MSA ad SDK,
    //    MIUI bugreport disk logging (284 log), and PreviewDisappear icon-load log spam.
    val firebaseMgr = mutableClassDefBy("Lcom/mi/appfinder/launcher/firebase/FirebaseManager;")
    firebaseMgr.findMethodOrThrow("initFirebase", emptyList(), "V")
        .replaceBody(locals = 0, smali = "return-void")

    val finderCtrl = mutableClassDefBy("Lcom/mi/appfinder/launcher/FinderController;")
    finderCtrl.findMethodOrThrow("syncFinderRemoteConfig", listOf("Z"), "Z")
        .replaceBody(locals = 1, smali = returnTrueSmali)
    finderCtrl.findMethodOrThrow("onLauncherAsyncStart", listOf("Z"), "V")
        .replaceBody(locals = 0, smali = "return-void")

    val oneTrack = mutableClassDefBy("Lcom/miui/home/common/utils/OneTrackInterfaceUtils;")
    oneTrack.findMethodOrThrow("init", listOf("Landroid/content/Context;"), "V")
        .replaceBody(locals = 0, smali = "return-void")

    val msaSdk = mutableClassDefBy("Lcom/miui/msa/MSASdk;")
    msaSdk.findMethodOrThrow("init", listOf("Landroid/content/Context;"), "V")
        .replaceBody(locals = 0, smali = "return-void")

    val log284 = mutableClassDefBy("Lcom/miui/home/library/MiuiHomeLog284Helper;")
    log284.findMethodOrThrow(
        "asyncFileLoggerLevelDebug",
        listOf("Landroid/content/Context;", "Ljava/lang/String;", "Ljava/lang/String;"),
        "V",
    ).replaceBody(locals = 0, smali = "return-void")
    log284.findMethodOrThrow(
        "fileLoggerLevelFatal",
        listOf("Landroid/content/Context;", "Ljava/lang/String;", "Ljava/lang/String;", "Ljava/lang/String;", "Ljava/lang/Exception;"),
        "V",
    ).replaceBody(locals = 0, smali = "return-void")

    val tempLog = mutableClassDefBy("Lcom/miui/home/common/utils/TempLog;")
    tempLog.findMethodOrThrow("w", listOf("Ljava/lang/String;", "Ljava/lang/String;"), "V")
        .replaceBody(locals = 0, smali = "return-void")

    // 4. Neutralize MIUI-only CPU/thread-priority boosters & MAML background thread pause hooks
    //    (all fail non-root or spam failed reflection/binder calls on every swipe and touch).
    val folme = mutableClassDefBy("Lmiuix/animation/Folme;")
    folme.findMethodOrThrow("setThreadPriority", listOf("I"), "V")
        .replaceBody(locals = 0, smali = "return-void")

    val cpuBooster = mutableClassDefBy("Lcom/miui/home/library/utils/CPUBooster;")
    cpuBooster.findMethodOrThrow("boostCpuToMax", listOf("I"), "V")
        .replaceBody(locals = 0, smali = "return-void")
    cpuBooster.findMethodOrThrow("cancelCpuBoost", emptyList(), "V")
        .replaceBody(locals = 0, smali = "return-void")
    cpuBooster.findMethodOrThrow("boostTouch", listOf("Landroid/view/MotionEvent;", "I"), "V")
        .replaceBody(locals = 0, smali = "return-void")
    cpuBooster.findMethodOrThrow(
        "boostThreadLevel",
        listOf("[I", "I", "Lcom/miui/home/library/utils/CPUBooster\$ThreadLevel;"),
        "V",
    ).replaceBody(locals = 0, smali = "return-void")

    val boostHelper = mutableClassDefBy("Lcom/miui/launcher/utils/BoostHelper;")
    boostHelper.findMethodOrThrow(
        "ensureTurboSchedManagerInitialized",
        listOf("Landroid/content/Context;"),
        "V",
    ).replaceBody(locals = 0, smali = "return-void")
    boostHelper.findMethodOrThrow("boost", listOf("Landroid/view/View;", "Z"), "V")
        .replaceBody(locals = 0, smali = "return-void")
    boostHelper.findMethodOrThrow("bindCore", listOf("Landroid/view/View;", "J"), "V")
        .replaceBody(locals = 0, smali = "return-void")

    val deviceConfig = mutableClassDefBy("Lcom/miui/home/launcher/DeviceConfig;")
    deviceConfig.findMethodOrThrow("setContextPriorityHigh", emptyList(), "V")
        .replaceBody(locals = 0, smali = "return-void")
    deviceConfig.findMethodOrThrow("checkSystemUIFsgVersion", emptyList(), "V")
        .replaceBody(locals = 0, smali = "return-void")

    val mamlUtils = mutableClassDefBy("Lcom/miui/launcher/utils/MamlUtils;")
    mamlUtils.findMethodOrThrow("setGlobalThreadPause", listOf("Z"), "V")
        .replaceBody(locals = 0, smali = "return-void")

    // 5. Eliminate dead MIUI Quickstep / Recents / Synergy threads & Binder listeners
    //    (saves 4-6 permanently-resident threads: FsGestureSecondaryThread, FsGesturePriorityThread,
    //    Recents-TaskResourceLoader, TaskSnapshotDeamon, BadgeDecoupling, Synergy CachedThreadPool).
    val touchInteraction = mutableClassDefBy("Lcom/miui/home/recents/TouchInteractionService;")
    touchInteraction.findMethodOrThrow("getGestureSecondaryLooper", emptyList(), "Landroid/os/Looper;").replaceBody(
        locals = 1,
        smali = """
            invoke-static { }, Lcom/miui/home/common/multithread/BackgroundThread;->getHandler()Landroid/os/Handler;
            move-result-object v0
            invoke-virtual { v0 }, Landroid/os/Handler;->getLooper()Landroid/os/Looper;
            move-result-object v0
            return-object v0
        """.trimIndent(),
    )
    val recentGestUtils = mutableClassDefBy("Lcom/miui/home/common/utils/CommonRecentAndGestUtils;")
    recentGestUtils.findMethodOrThrow("isUseGesturePriorityThread", emptyList(), "Z")
        .replaceBody(locals = 1, smali = returnFalseSmali)

    val bgTaskLoader = mutableClassDefBy("Lcom/android/systemui/shared/recents/model/BackgroundTaskLoader;")
    bgTaskLoader.findMethodOrThrow(
        "<init>",
        listOf(
            "Lcom/android/systemui/shared/recents/model/TaskResourceLoadQueue;",
            "Lcom/android/systemui/shared/recents/model/IconLoader;",
            "Lcom/android/systemui/shared/recents/model/TaskKeyLruCache;",
            "Lcom/android/systemui/shared/recents/model/TaskKeyLruCache;",
            "Landroid/graphics/Bitmap;",
            "I",
        ),
        "V",
    ).replaceBody(
        locals = 2,
        smali = """
            invoke-direct { p0 }, Ljava/lang/Object;-><init>()V
            new-instance v0, Landroid/os/Handler;
            invoke-static { }, Landroid/os/Looper;->getMainLooper()Landroid/os/Looper;
            move-result-object v1
            invoke-direct { v0, v1 }, Landroid/os/Handler;-><init>(Landroid/os/Looper;)V
            iput-object v0, p0, Lcom/android/systemui/shared/recents/model/BackgroundTaskLoader;->mMainThreadHandler:Landroid/os/Handler;
            iput-object v0, p0, Lcom/android/systemui/shared/recents/model/BackgroundTaskLoader;->mLoadThreadHandler:Landroid/os/Handler;
            iput-object p1, p0, Lcom/android/systemui/shared/recents/model/BackgroundTaskLoader;->mLoadQueue:Lcom/android/systemui/shared/recents/model/TaskResourceLoadQueue;
            iput-object p2, p0, Lcom/android/systemui/shared/recents/model/BackgroundTaskLoader;->mIconLoader:Lcom/android/systemui/shared/recents/model/IconLoader;
            iput-object p3, p0, Lcom/android/systemui/shared/recents/model/BackgroundTaskLoader;->mThumbnailCache:Lcom/android/systemui/shared/recents/model/TaskKeyLruCache;
            iput-object p4, p0, Lcom/android/systemui/shared/recents/model/BackgroundTaskLoader;->mWaitingBlurThumbnailDatas:Lcom/android/systemui/shared/recents/model/TaskKeyLruCache;
            iput-object p5, p0, Lcom/android/systemui/shared/recents/model/BackgroundTaskLoader;->mDefaultThumbnail:Landroid/graphics/Bitmap;
            iput p6, p0, Lcom/android/systemui/shared/recents/model/BackgroundTaskLoader;->mSvelteLevel:I
            new-instance v0, Landroid/os/HandlerThread;
            const-string v1, "Recents-TaskResourceLoader"
            invoke-direct { v0, v1 }, Landroid/os/HandlerThread;-><init>(Ljava/lang/String;)V
            iput-object v0, p0, Lcom/android/systemui/shared/recents/model/BackgroundTaskLoader;->mLoadThread:Landroid/os/HandlerThread;
            return-void
        """.trimIndent(),
    )
    bgTaskLoader.findMethodOrThrow("start", listOf("Landroid/content/Context;"), "V")
        .replaceBody(locals = 0, smali = "return-void")
    bgTaskLoader.findMethodOrThrow("stop", emptyList(), "V")
        .replaceBody(locals = 0, smali = "return-void")
    bgTaskLoader.findMethodOrThrow("run", emptyList(), "V")
        .replaceBody(locals = 0, smali = "return-void")

    val taskStackListeners = mutableClassDefBy("Lcom/android/systemui/shared/recents/system/TaskStackChangeListeners;")
    taskStackListeners.findMethodOrThrow("<init>", listOf("Landroid/os/Looper;"), "V").replaceBody(
        locals = 1,
        smali = """
            invoke-direct { p0 }, Landroid/app/TaskStackListener;-><init>()V
            new-instance v0, Ljava/util/ArrayList;
            invoke-direct { v0 }, Ljava/util/ArrayList;-><init>()V
            iput-object v0, p0, Lcom/android/systemui/shared/recents/system/TaskStackChangeListeners;->mTaskStackListeners:Ljava/util/List;
            new-instance v0, Ljava/util/ArrayList;
            invoke-direct { v0 }, Ljava/util/ArrayList;-><init>()V
            iput-object v0, p0, Lcom/android/systemui/shared/recents/system/TaskStackChangeListeners;->mTmpListenersList:Ljava/util/List;
            new-instance v0, Landroid/os/Handler;
            invoke-direct { v0, p1 }, Landroid/os/Handler;-><init>(Landroid/os/Looper;)V
            iput-object v0, p0, Lcom/android/systemui/shared/recents/system/TaskStackChangeListeners;->backgroundHandler:Landroid/os/Handler;
            new-instance v0, Lcom/android/systemui/shared/recents/system/TaskStackChangeListeners${'$'}H;
            invoke-direct { v0, p0, p1 }, Lcom/android/systemui/shared/recents/system/TaskStackChangeListeners${'$'}H;-><init>(Lcom/android/systemui/shared/recents/system/TaskStackChangeListeners;Landroid/os/Looper;)V
            iput-object v0, p0, Lcom/android/systemui/shared/recents/system/TaskStackChangeListeners;->mHandler:Landroid/os/Handler;
            return-void
        """.trimIndent(),
    )
    taskStackListeners.findMethodOrThrow(
        "registerTaskStackListener",
        listOf("Lcom/android/systemui/shared/recents/system/TaskStackChangeListener;"),
        "V",
    ).replaceBody(locals = 0, smali = "return-void")
    taskStackListeners.findMethodOrThrow(
        "unregisterTaskStackListener",
        listOf("Lcom/android/systemui/shared/recents/system/TaskStackChangeListener;"),
        "V",
    ).replaceBody(locals = 0, smali = "return-void")

    val synergySdk = mutableClassDefBy("Lcom/xiaomi/mirror/synergy/MiuiSynergySdk;")
    synergySdk.findMethodOrThrow(
        "addRemoteDeviceListener",
        listOf("Landroid/content/Context;", "Lcom/xiaomi/mirror/synergy/MiuiSynergySdk\$IRemoteDeviceListener;"),
        "V",
    ).replaceBody(locals = 0, smali = "return-void")

    val thumbBlurMgr = mutableClassDefBy("Lcom/miui/home/recents/ThumbnailBlurManager;")
    thumbBlurMgr.findMethodOrThrow(
        "<init>",
        listOf("Landroid/content/Context;", "Landroid/os/Handler;"),
        "V",
    ).replaceBody(
        locals = 0,
        smali = """
            invoke-direct { p0 }, Ljava/lang/Object;-><init>()V
            iput-object p1, p0, Lcom/miui/home/recents/ThumbnailBlurManager;->mContext:Landroid/content/Context;
            iput-object p2, p0, Lcom/miui/home/recents/ThumbnailBlurManager;->mHandler:Landroid/os/Handler;
            return-void
        """.trimIndent(),
    )

    val taskSnapshotVS = mutableClassDefBy("Lcom/android/systemui/shared/recents/system/TaskSnapshotCompatVS;")
    taskSnapshotVS.findMethodOrThrow("startCache", emptyList(), "V")
        .replaceBody(locals = 0, smali = "return-void")
    taskSnapshotVS.findMethodOrThrow("stopCache", emptyList(), "V")
        .replaceBody(locals = 0, smali = "return-void")

    val appObserver = mutableClassDefBy("Lcom/android/systemui/shared/recents/AppObserver;")
    appObserver.findMethodOrThrow(
        "addForegroundInfoListener",
        listOf("Lcom/android/systemui/shared/recents/AppObserver\$ForegroundListener;"),
        "V",
    ).replaceBody(locals = 0, smali = "return-void")

    val baseRecents = mutableClassDefBy("Lcom/miui/home/recents/BaseRecentsImpl;")
    baseRecents.findMethodOrThrow("addFsgGestureWindow", emptyList(), "V")
        .replaceBody(locals = 0, smali = "return-void")
    baseRecents.findMethodOrThrow("updateFsgWindowState", emptyList(), "V")
        .replaceBody(locals = 0, smali = "return-void")
    baseRecents.findMethodOrThrow(
        "updateFsgWindowVisibilityState",
        listOf("Z", "Ljava/lang/String;"),
        "V",
    ).replaceBody(locals = 0, smali = "return-void")
    baseRecents.findMethodOrThrow("readCloudDataForFsg", emptyList(), "V")
        .replaceBody(locals = 0, smali = "return-void")
    baseRecents.findMethodOrThrow("registerCloudDataObserver", emptyList(), "V")
        .replaceBody(locals = 0, smali = "return-void")
    baseRecents.findMethodOrThrow("registerScreeningModeObserver", emptyList(), "V")
        .replaceBody(locals = 0, smali = "return-void")
    baseRecents.findMethodOrThrow("registerThreeGestureObserver", emptyList(), "V")
        .replaceBody(locals = 0, smali = "return-void")
    baseRecents.findMethodOrThrow("registerVTCameraReceiver", emptyList(), "V")
        .replaceBody(locals = 0, smali = "return-void")
    baseRecents.findMethodOrThrow("registerSuperSavePowerObserver", emptyList(), "V")
        .replaceBody(locals = 0, smali = "return-void")
    baseRecents.findMethodOrThrow("registerRecentsLayoutStyleObserver", emptyList(), "V")
        .replaceBody(locals = 0, smali = "return-void")
    baseRecents.findMethodOrThrow("initSFDeviceGestureHelper", emptyList(), "V")
        .replaceBody(locals = 0, smali = "return-void")

    val actObserver = mutableClassDefBy("Lcom/miui/home/recents/ActivityObserverLauncherImpl;")
    actObserver.findMethodOrThrow("run", emptyList(), "V")
        .replaceBody(locals = 0, smali = "return-void")

    val keyguardListener = mutableClassDefBy("Lcom/miui/home/launcher/KeyguardOccludedChangedListener;")
    keyguardListener.findMethodOrThrow("initAndRegisterInBackground", emptyList(), "V")
        .replaceBody(locals = 0, smali = "return-void")

    val analyticalJob = mutableClassDefBy("Lcom/miui/home/launcher/AnalyticalDataCollectorJobService;")
    analyticalJob.findMethodOrThrow("onStartJob", listOf("Landroid/app/job/JobParameters;"), "Z")
        .replaceBody(locals = 1, smali = returnFalseSmali)

    // 6. Eliminate per-resume / per-swipe / per-stop Xiaomi cloud, GetApps, ServiceDelivery,
    //    SecurityCenter, PowerKeeper, and empty-list UsageStats churn.
    val baseLauncher = mutableClassDefBy("Lcom/miui/home/launcher/BaseLauncher;")
    baseLauncher.findMethodOrThrow("notifyBackGestureStatus", emptyList(), "V")
        .replaceBody(locals = 0, smali = "return-void")
    baseLauncher.findMethodOrThrow("requestPowerKeeperPermissionIfNeed", emptyList(), "V")
        .replaceBody(locals = 0, smali = "return-void")

    val checkNewInstalled = baseLauncher.findMethodOrThrow("checkNewInstalledAppsBeStarted", emptyList(), "V")
    val firstCheckNewInsn = checkNewInstalled.instructions.first()
    checkNewInstalled.addInstructionsWithLabels(
        0,
        """
            iget-object v0, p0, Lcom/miui/home/launcher/BaseLauncher;->mNewInstalledApps:Ljava/util/ArrayList;
            if-eqz v0, :skip_new_installed
            invoke-virtual { v0 }, Ljava/util/ArrayList;->isEmpty()Z
            move-result v0
            if-eqz v0, :continue_new_installed
            :skip_new_installed
            return-void
        """.trimIndent(),
        ExternalLabel("continue_new_installed", firstCheckNewInsn),
    )

    val marketMonitor = mutableClassDefBy("Lcom/miui/home/launcher/operationicon/MarketCustomizeIconVisibleMonitor;")
    marketMonitor.findMethodOrThrow("onLauncherResume", listOf("Lcom/miui/home/launcher/BaseLauncher;"), "V")
        .replaceBody(locals = 0, smali = "return-void")
    marketMonitor.findMethodOrThrow("onWorkspaceScreenChanged", listOf("Lcom/miui/home/launcher/BaseLauncher;"), "V")
        .replaceBody(locals = 0, smali = "return-void")
    marketMonitor.findMethodOrThrow("onOverlayChanged", listOf("Lcom/miui/home/launcher/BaseLauncher;"), "V")
        .replaceBody(locals = 0, smali = "return-void")

    val marketMgr = mutableClassDefBy("Lcom/miui/home/launcher/operationicon/MarketCustomizeIconManager;")
    marketMgr.findMethodOrThrow("onLauncherResume", emptyList(), "V")
        .replaceBody(locals = 0, smali = "return-void")

    val badgeDecoupling = mutableClassDefBy("Lcom/miui/home/launcher/badge/BadgeDecouplingConfig;")
    badgeDecoupling.findMethodOrThrow("initCloudControl", listOf("Landroid/content/Context;"), "V")
        .replaceBody(locals = 0, smali = "return-void")
    badgeDecoupling.findMethodOrThrow("ensureLoadedForProvider", listOf("Landroid/content/Context;"), "V")
        .replaceBody(locals = 0, smali = "return-void")

    val uninstallReminder = mutableClassDefBy("Lcom/miui/home/launcher/uninstall/UninstallReminderCloudControlHelper;")
    uninstallReminder.findMethodOrThrow("onLauncherActivityCreate", emptyList(), "V")
        .replaceBody(locals = 0, smali = "return-void")

    val sdReplace = mutableClassDefBy("Lcom/miui/home/launcher/servicedeliver/ServiceDeliveryReplaceHelper;")
    sdReplace.findMethodOrThrow("sendWeatherCallbacks", listOf("Landroid/os/Handler;", "I"), "V")
        .replaceBody(locals = 0, smali = "return-void")
    sdReplace.findMethodOrThrow("tryReplaceToServiceDelivery", emptyList(), "V")
        .replaceBody(locals = 0, smali = "return-void")

    val sdDelegate = mutableClassDefBy("Lcom/miui/home/launcher/LauncherServiceDeliveryWidgetDelegate;")
    sdDelegate.findMethodOrThrow("initWidgetHost", emptyList(), "V")
        .replaceBody(locals = 0, smali = "return-void")

    val intlContentCenter = mutableClassDefBy("Lcom/miui/home/launcher/InternationalContentCenter;")
    intlContentCenter.findMethodOrThrow("init", emptyList(), "V")
        .replaceBody(locals = 0, smali = "return-void")

    val securityHide = mutableClassDefBy("Lcom/miui/home/isolate/security/SecurityHide;")
    securityHide.findMethodOrThrow("getSecurityHideItemsAsync", listOf("Landroid/content/Context;"), "V")
        .replaceBody(locals = 0, smali = "return-void")
    securityHide.findMethodOrThrow("getSecurityHideItems", listOf("Landroid/content/Context;"), "V")
        .replaceBody(locals = 0, smali = "return-void")

    val appPredict = mutableClassDefBy("Lcom/miui/home/folder/apppredict/AppPredictHelper;")
    appPredict.findMethodOrThrow("getAppPredictFromService", listOf("Z"), "V")
        .replaceBody(locals = 0, smali = "return-void")

    // 7. Skip dead Minus-1 & International Search overlay LauncherClient connections when
    //    Xiaomi App Vault / AppFinder are absent (stops bindService loops on every lifecycle event).
    val assistantGoogle = mutableClassDefBy("Lcom/miui/home/launcher/LauncherAssistantCompatGoogle;")
    val createGoogleClient = assistantGoogle.findMethodOrThrow(
        "createLauncherClient",
        listOf("Lcom/miui/home/launcher/allapps/LauncherMode;"),
        "V",
    )
    val firstGoogleClientInsn = createGoogleClient.instructions.first()
    createGoogleClient.addInstructionsWithLabels(
        0,
        """
            iget-object v0, p0, Lcom/miui/home/launcher/LauncherAssistantCompat;->mLauncher:Lcom/miui/home/launcher/BaseLauncher;
            iget-object v1, p0, Lcom/miui/home/launcher/LauncherAssistantCompat;->mPackageName:Ljava/lang/String;
            invoke-static { v0, v1 }, $COMPAT_HELPER->shouldCreateMinusScreenClient(Landroid/content/Context;Ljava/lang/String;)Z
            move-result v0
            if-nez v0, :continue_minus_client
            return-void
        """.trimIndent(),
        ExternalLabel("continue_minus_client", firstGoogleClientInsn),
    )

    val intlSearchCb = mutableClassDefBy(
        "Lcom/miui/home/launcher/overlay/internationalsearch/InternationalSearchLauncherCallbacks;",
    )
    val createFinderClient = intlSearchCb.findMethodOrThrow(
        "createLauncherClient",
        listOf("Landroid/os/Bundle;", "Lcom/miui/home/launcher/allapps/LauncherMode;"),
        "Lcom/miui/launcher/overlay/client/LauncherClient;",
    )
    val firstFinderClientInsn = createFinderClient.instructions.first()
    createFinderClient.addInstructionsWithLabels(
        0,
        """
            iget-object v0, p0, Lcom/miui/home/launcher/overlay/OverlayLauncherCallbacks;->mLauncher:Lcom/miui/home/launcher/BaseLauncher;
            invoke-static { v0 }, $COMPAT_HELPER->shouldCreateAppFinderClient(Landroid/content/Context;)Z
            move-result v0
            if-nez v0, :continue_finder_client
            const/4 v0, 0x0
            return-object v0
        """.trimIndent(),
        ExternalLabel("continue_finder_client", firstFinderClientInsn),
    )

    // 8. Negative-cache miuix.internal.util.ReflectUtil to eliminate uncached reflection & Log.e
    //    stack-trace spam from MIUIX helpers (MiMotionHelper, NumberPicker, CornerRadiusUtils, etc.).
    val miuixReflect = mutableClassDefBy("Lmiuix/internal/util/ReflectUtil;")
    miuixReflect.findMethodOrThrow("getClass", listOf("Ljava/lang/String;"), "Ljava/lang/Class;")
        .replaceBody(
            locals = 1,
            smali = """
                invoke-static { p0 }, $COMPAT_HELPER->reflectGetClass(Ljava/lang/String;)Ljava/lang/Class;
                move-result-object v0
                return-object v0
            """.trimIndent(),
        )
    miuixReflect.findMethodOrThrow(
        "callObjectMethod",
        listOf("Ljava/lang/Object;", "Ljava/lang/String;", "[Ljava/lang/Class;", "[Ljava/lang/Object;"),
        "Ljava/lang/Object;",
    ).replaceBody(
        locals = 1,
        smali = """
            invoke-static { p0, p1, p2, p3 }, $COMPAT_HELPER->miuixReflectCallObjectMethod(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Class;[Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object v0
            return-object v0
        """.trimIndent(),
    )
    miuixReflect.findMethodOrThrow(
        "callStaticObjectMethod",
        listOf("Ljava/lang/Class;", "Ljava/lang/Class;", "Ljava/lang/String;", "[Ljava/lang/Class;", "[Ljava/lang/Object;"),
        "Ljava/lang/Object;",
    ).replaceBody(
        locals = 1,
        smali = """
            invoke-static { p0, p1, p2, p3, p4 }, $COMPAT_HELPER->miuixReflectCallStaticObjectMethod(Ljava/lang/Class;Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/Class;[Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object v0
            return-object v0
        """.trimIndent(),
    )

    // 9. Eliminate permanently-resident delayReportHandlerThread, disk cache I/O, and 30s report timers.
    val delayReport = mutableClassDefBy("Lcom/miui/home/launcher/util/TrackerDataDelayReportHelper;")
    delayReport.findMethodOrThrow("init", emptyList(), "V")
        .replaceBody(locals = 0, smali = "return-void")
    delayReport.findMethodOrThrow("handleNewTrackData", listOf("Ljava/util/Map;"), "V")
        .replaceBody(locals = 0, smali = "return-void")
    delayReport.findMethodOrThrow("trackReport", emptyList(), "V")
        .replaceBody(locals = 0, smali = "return-void")

    // 10. Eliminate 8,500+ boot-pinned uninstall spark objects & 2,116 native Skia Paint handles
    //     (BoomByFolmeAnimator.initBoomSpark() already lazily allocates them on first uninstall).
    val appClass = mutableClassDefBy("Lcom/miui/home/launcher/Application;")
    appClass.findMethodOrThrow("initBoomObject", emptyList(), "V")
        .replaceBody(locals = 0, smali = "return-void")

    // 11. Avoid spawning & waking ProgressManager's SingleThreadExecutor on every Home press when
    //     no MIUI GetApps/GameCenter install progress servers are active.
    val progressProc = mutableClassDefBy("Lcom/miui/home/launcher/progress/ApplicationProgressProcessor;")
    val progressStart = progressProc.findMethodOrThrow("start", emptyList(), "V")
    val firstProgressStartInsn = progressStart.instructions.first()
    progressStart.addInstructionsWithLabels(
        0,
        """
            const/4 v0, 0x0
            iput-boolean v0, p0, Lcom/miui/home/launcher/progress/ApplicationProgressProcessor;->mIsStop:Z
            iget-object v0, p0, Lcom/miui/home/launcher/progress/ApplicationProgressProcessor;->mAllServersProgressMap:Ljava/util/HashMap;
            if-eqz v0, :skip_progress_start
            invoke-virtual { v0 }, Ljava/util/HashMap;->isEmpty()Z
            move-result v0
            if-eqz v0, :continue_progress_start
            :skip_progress_start
            return-void
        """.trimIndent(),
        ExternalLabel("continue_progress_start", firstProgressStartInsn),
    )

    // 12. Allow core LauncherTask #1..4 worker threads to time out after 30s when idle.
    val asyncHelper = mutableClassDefBy("Lcom/miui/home/library/utils/AsyncTaskExecutorHelper;")
    val initDefExec = asyncHelper.findMethodOrThrow("initDefaultExecutor", emptyList(), "V")
    val initDefReturns = initDefExec.instructions.withIndex()
        .filter { it.value.opcode == Opcode.RETURN_VOID }
        .map { it.index }
        .asReversed()
    check(initDefReturns.isNotEmpty()) {
        "POCO Launcher: return-void não encontrado em AsyncTaskExecutorHelper.initDefaultExecutor"
    }
    for (retIdx in initDefReturns) {
        initDefExec.addInstructionsAtControlFlowLabel(
            retIdx,
            """
                sget-object v0, Lcom/miui/home/library/utils/AsyncTaskExecutorHelper;->LAUNCHER_THREAD_POOL_EXECUTOR:Ljava/util/concurrent/ThreadPoolExecutor;
                invoke-static { v0 }, $COMPAT_HELPER->configureIdleThreadPool(Ljava/util/concurrent/ThreadPoolExecutor;)V
            """.trimIndent(),
        )
    }

    // 13. Prevent GlobalExecutors.THREAD_POOL_IO boot spin-up & dead per-onStart BackgroundThread tasks.
    val initHomeFinder = finderCtrl.findMethodOrThrow("initHomeFinder", listOf("Z"), "V")
    val firstInitFinderInsn = initHomeFinder.instructions.first()
    initHomeFinder.addInstructionsWithLabels(
        0,
        """
            iget-object v0, p0, Lcom/mi/appfinder/launcher/FinderController;->mApplication:Landroid/app/Application;
            invoke-static { v0 }, $COMPAT_HELPER->shouldCreateAppFinderClient(Landroid/content/Context;)Z
            move-result v0
            if-nez v0, :continue_init_finder
            return-void
        """.trimIndent(),
        ExternalLabel("continue_init_finder", firstInitFinderInsn),
    )
    baseLauncher.findMethodOrThrow("onStartAsync", emptyList(), "V")
        .replaceBody(locals = 0, smali = "return-void")

    // 14. Eliminate per-onPause 5s UI-thread timer + SharedPrefs churn, per-onResume dead MIUI
    //     APPLICATION_MESSAGE_QUERY broadcast, per-scroll-pixel UI-thread DateTimeFormatter work,
    //     Alexa QS-tile NPE/spam, dead MIUI thermal broadcasts, 5 dead Minus-1 observers, and
    //     dead Recents package-change wakeups.
    sdReplace.findMethodOrThrow("sendCallbacks", listOf("Landroid/os/Handler;", "I"), "V")
        .replaceBody(locals = 0, smali = "return-void")
    sdReplace.findMethodOrThrow("removeCallbacks", listOf("Landroid/os/Handler;"), "V")
        .replaceBody(locals = 0, smali = "return-void")

    val appMsgQuery = mutableClassDefBy("Lcom/miui/home/launcher/ApplicationsMessage\$2;")
    appMsgQuery.findMethodOrThrow("run", emptyList(), "V")
        .replaceBody(locals = 0, smali = "return-void")

    val homeLog = mutableClassDefBy("Lcom/miui/home/common/MiuiHomeLog;")
    homeLog.findMethodOrThrow("saveRecorded", listOf("Ljava/lang/String;", "Ljava/lang/String;"), "V")
        .replaceBody(locals = 0, smali = "return-void")
    homeLog.findMethodOrThrow("setHead", listOf("Ljava/lang/String;", "Ljava/lang/String;"), "V")
        .replaceBody(locals = 0, smali = "return-void")

    val installApkReceiver = mutableClassDefBy("Lcom/miui/home/launcher/install/InstallApkReceiver;")
    installApkReceiver.findMethodOrThrow(
        "onReceive",
        listOf("Landroid/content/Context;", "Landroid/content/Intent;"),
        "V",
    ).replaceBody(
        locals = 0,
        smali = """
            invoke-static { p1, p2 }, $COMPAT_HELPER->handleInstallApkBroadcast(Landroid/content/Context;Landroid/content/Intent;)V
            return-void
        """.trimIndent(),
    )

    val appUsageMonitor = mutableClassDefBy("Lcom/miui/home/launcher/AppUsageMonitor;")
    appUsageMonitor.findMethodOrThrow("onAppOpened", emptyList(), "V")
        .replaceBody(locals = 0, smali = "return-void")

    val assistantWrap = mutableClassDefBy("Lcom/miui/home/launcher/AssistantLauncherCallbacksWrap;")
    val regAssistantObs = assistantWrap.findMethodOrThrow("registerObserver", emptyList(), "V")
    val firstRegObsInsn = regAssistantObs.instructions.first()
    regAssistantObs.addInstructionsWithLabels(
        0,
        """
            iget-object v0, p0, Lcom/miui/home/launcher/overlay/assistant/AssistantLauncherCallbacks;->mLauncher:Lcom/miui/home/launcher/BaseLauncher;
            const/4 v1, 0x0
            invoke-static { v0, v1 }, $COMPAT_HELPER->shouldCreateMinusScreenClient(Landroid/content/Context;Ljava/lang/String;)Z
            move-result v0
            if-nez v0, :continue_assistant_observers
            return-void
        """.trimIndent(),
        ExternalLabel("continue_assistant_observers", firstRegObsInsn),
    )

    val recentsPkgCb = mutableClassDefBy("Lcom/android/systemui/shared/recents/model/RecentsTaskLoader\$2;")
    recentsPkgCb.findMethodOrThrow(
        "onPackageChanged",
        listOf("Ljava/lang/String;", "Landroid/os/UserHandle;"),
        "V",
    ).replaceBody(locals = 0, smali = "return-void")
}

// ==================== 7. Native Non-Xiaomi UX (Widgets, Uninstall, Wallpaper) ====================

private fun patchNativeNonXiaomiUx() {
    // 7a. Force built-in Android widget picker instead of missing Xiaomi App Vault / MIUI Widget store.
    MiuiWidgetUtilIsSupportFingerprint.method.replaceBody(
        locals = 1,
        smali = """
            const/4 v0, 0x0
            return v0
        """.trimIndent(),
    )

    // 7b. Native Android ACTION_APPWIDGET_BIND consent flow in BaseLauncher.addAppWidgetWithoutFindPlaceAgain
    //     and BaseLauncher.onActivityResult.
    patchAppWidgetBinding()

    // 7c. Standard Android ACTION_DELETE app uninstall (all LauncherUtils.deletePackage* paths
    //     plus long-press trash, multi-select DeleteDialog, and drag-to-UninstallDialog).
    LauncherUtilsDeletePackageFingerprint.method.replaceBody(
        locals = 0,
        smali = """
            invoke-static { p0, p1 }, $COMPAT_HELPER->launchUninstallIntent(Landroid/content/Context;Ljava/lang/String;)Z
            return-void
        """.trimIndent(),
    )
    LauncherUtilsDeletePackageAsUserFingerprint.method.replaceBody(
        locals = 0,
        smali = """
            invoke-static { p0, p1, p3 }, $COMPAT_HELPER->launchUninstallIntent(Landroid/content/Context;Ljava/lang/String;I)Z
            return-void
        """.trimIndent(),
    )
    patchUninstallFlow()

    // 7d. Wallpaper color adaptation & real Android wallpaper picker fallback.
    patchWallpaperIntegration()

    // 7e. Populate non-Xiaomi default home screen & dock (POCO XML only lists MIUI packages).
    patchDefaultWorkspacePopulation()

    // 7f. Standard Android NotificationListenerService badges (replace MIUI SystemUI/registerAsSystemService).
    patchNotificationBadges()

    // 7g. Full rootless Hide Apps support (drawer toggle, native BiometricPrompt unlock,
    //     device-credential pattern reset, seamless legacy migration).
    patchHideAppsFeature()

    // 7h. Non-Xiaomi settings polish (Notification badges, Icons, Hide Apps, Default Home,
    //     Swipe-down gestures, No-label mode, Animation speed, Double-Tap to Lock, Global Search).
    patchNonXiaomiSettingsAndFallbacks()

    // 7i. Transparent status & navigation bars without legacy FLAG_TRANSLUCENT_STATUS dark scrim.
    patchTransparentSystemBars()

    // 7j. Expanded Home screen grid options (4/5/6 columns, up to 10 screen-adapted layouts).
    patchExpandedHomeScreenGrids()
}

private fun patchUninstallFlow() {
    val uninstallCtrl = mutableClassDefBy("Lcom/miui/home/launcher/uninstall/UninstallController;")

    // 1. Single-app uninstall via long-press trash icon or edit-mode button: open Android's native
    //    ACTION_DELETE dialog directly so the app is never deleted prematurely from the App Drawer.
    val showDialog = uninstallCtrl.findMethodOrThrow(
        "showDialog",
        listOf(
            "Lcom/miui/home/launcher/BaseLauncher;",
            "Ljava/util/List;",
            "Ljava/lang/String;",
        ),
        "V",
    )
    val firstShowInsn = showDialog.instructions.first()
    showDialog.addInstructionsWithLabels(
        0,
        """
            invoke-static { p0, p1 }, $COMPAT_HELPER->tryDirectSystemUninstall(Ljava/lang/Object;Ljava/util/List;)Z
            move-result v0
            if-eqz v0, :continue_uninstall_dialog
            return-void
        """.trimIndent(),
        ExternalLabel("continue_uninstall_dialog", firstShowInsn),
    )

    // 2. Prevent DeleteObserver from enqueuing PackageRemoveTask before Android confirms uninstall
    //    (which previously erased apps from the App Drawer without actually uninstalling them).
    val deleteObserver = mutableClassDefBy("Lcom/miui/home/launcher/uninstall/UninstallController\$DeleteObserver;")
    deleteObserver.findMethodOrThrow(
        "onPackageStartDelete",
        listOf("Lcom/miui/home/launcher/ShortcutInfo;"),
        "V",
    ).replaceBody(locals = 0, smali = "return-void")

    // 3. Prevent poisoning PendingUninstallTracker (ACTION_DELETE never fires DeleteObserver callbacks).
    uninstallCtrl.findMethodOrThrow(
        "markPendingUninstall",
        listOf("Ljava/util/List;"),
        "V",
    ).replaceBody(locals = 0, smali = "return-void")

    // 4. Multi-select DeleteDialog confirm: invoke uninstallApps directly without premature Boom hide.
    val uninstallConfirm = mutableClassDefBy("Lcom/miui/home/launcher/uninstall/UninstallController\$5;")
    uninstallConfirm.findMethodOrThrow(
        "accept",
        listOf("Ljava/util/List;"),
        "V",
    ).replaceBody(
        locals = 1,
        smali = """
            iget-object v0, p0, Lcom/miui/home/launcher/uninstall/UninstallController${'$'}5;->val${'$'}launcher:Lcom/miui/home/launcher/BaseLauncher;
            invoke-static { v0, p1 }, $COMPAT_HELPER->handleUninstallDialogConfirm(Ljava/lang/Object;Ljava/util/List;)V
            return-void
        """.trimIndent(),
    )

    // 5. Drag-to-Uninstall drop target: open ACTION_DELETE while keeping the dragged icon safe if
    //    the user cancels Android's system dialog (confirmed uninstall removes it via PACKAGE_REMOVED).
    val uninstallDialog = mutableClassDefBy("Lcom/miui/home/launcher/UninstallDialog;")
    uninstallDialog.findMethodOrThrow(
        "removeItem",
        listOf("Landroid/view/View;"),
        "Z",
    ).replaceBody(
        locals = 1,
        smali = """
            invoke-static { p0, p1 }, $COMPAT_HELPER->handleUninstallDialogRemoveItem(Ljava/lang/Object;Landroid/view/View;)Z
            move-result v0
            return v0
        """.trimIndent(),
    )
}

private fun patchTransparentSystemBars() {
    // 1. BaseLauncher.onCreateBeforeSetCreatedFlag: replace window.addFlags(FLAG_TRANSLUCENT_STATUS)
    //    (0x04000000), which causes non-Xiaomi Android 10–15 SystemUI to draw a dark status bar scrim,
    //    with clean transparent system bar configuration and disabled contrast enforcement.
    val baseLauncher = mutableClassDefBy("Lcom/miui/home/launcher/BaseLauncher;")
    val onCreateBefore = baseLauncher.findMethodOrThrow("onCreateBeforeSetCreatedFlag", emptyList(), "Z")
    val insns = onCreateBefore.instructions.toList()
    val translucentConstIdx = insns.indexOfFirst { ins ->
        ins is NarrowLiteralInstruction &&
            ins is OneRegisterInstruction &&
            ins.narrowLiteral == 0x04000000
    }
    check(translucentConstIdx >= 0) {
        "POCO Launcher: constante FLAG_TRANSLUCENT_STATUS (0x04000000) não encontrada em BaseLauncher.onCreateBeforeSetCreatedFlag"
    }
    val flagReg = (insns[translucentConstIdx] as OneRegisterInstruction).registerA
    val addFlagsIdx = (translucentConstIdx + 1 until minOf(translucentConstIdx + 4, insns.size)).firstOrNull { idx ->
        val ins = insns[idx]
        ins.opcode == Opcode.INVOKE_VIRTUAL &&
            ins is FiveRegisterInstruction &&
            ins.registerD == flagReg &&
            ((ins as? ReferenceInstruction)?.reference as? MethodReference)?.let {
                it.definingClass == "Landroid/view/Window;" && it.name == "addFlags"
            } == true
    } ?: throw PatchException(
        "POCO Launcher: Window.addFlags(FLAG_TRANSLUCENT_STATUS) não encontrado em BaseLauncher.onCreateBeforeSetCreatedFlag",
    )
    val windowReg = (insns[addFlagsIdx] as FiveRegisterInstruction).registerC
    onCreateBefore.replaceInstruction(
        addFlagsIdx,
        "invoke-static { v$windowReg }, $COMPAT_HELPER->configureTransparentSystemBars(Landroid/view/Window;)V",
    )

    // 2. SystemUiController.updateUiState(int, String): re-assert transparent system bars on every
    //    status bar visibility/mode update so OEM SystemUI never reintroduces a contrast scrim.
    val sysUiCtrl = mutableClassDefBy("Lcom/miui/home/launcher/util/SystemUiController;")
    val updateUiState = sysUiCtrl.findMethodOrThrow(
        "updateUiState",
        listOf("I", "Ljava/lang/String;"),
        "V",
    )
    updateUiState.addInstructions(
        0,
        """
            iget-object v0, p0, Lcom/miui/home/launcher/util/SystemUiController;->mWindow:Landroid/view/Window;
            invoke-static { v0 }, $COMPAT_HELPER->configureTransparentSystemBars(Landroid/view/Window;)V
        """.trimIndent(),
    )
}

private fun patchExpandedHomeScreenGrids() {
    // 1. Raise maximum Home & App Drawer column count from 5 to 6.
    mutableClassDefBy("Lcom/miui/home/launcher/compat/LauncherCellCountDefaultMin;")
        .findMethodOrThrow("getCellCountXMax", emptyList(), "I")
        .replaceBody(
            locals = 1,
            smali = """
                const/4 v0, 0x6
                return v0
            """.trimIndent(),
        )
    mutableClassDefBy("Lcom/miui/home/launcher/compat/LauncherCellCountDefaultMax;")
        .findMethodOrThrow("getCellCountXMax", emptyList(), "I")
        .replaceBody(
            locals = 1,
            smali = """
                const/4 v0, 0x6
                return v0
            """.trimIndent(),
        )

    // 2. Treat 5 and 6 columns as dense layouts and allow up to 6 dock icons in 6-column mode.
    mutableClassDefBy("Lcom/miui/home/common/device/DeviceConfigs;")
        .findMethodOrThrow("isDenseLayout", emptyList(), "Z")
        .replaceBody(
            locals = 2,
            smali = """
                invoke-static {}, Lcom/miui/home/common/device/DeviceConfigs;->getCellCountX()I
                move-result v0
                const/4 v1, 0x5
                if-lt v0, v1, :not_dense
                const/4 v0, 0x1
                return v0
                :not_dense
                const/4 v0, 0x0
                return v0
            """.trimIndent(),
        )
    mutableClassDefBy("Lcom/miui/home/launcher/DeviceConfig;")
        .findMethodOrThrow("calHotseatMaxCount", emptyList(), "I")
        .replaceBody(
            locals = 2,
            smali = """
                invoke-static {}, Lcom/miui/home/launcher/allapps/LauncherModeController;->isElderlyManMode()Z
                move-result v0
                if-eqz v0, :not_elderly
                const/4 v0, 0x4
                return v0
                :not_elderly
                invoke-static {}, Lcom/miui/home/common/device/DeviceConfigs;->getCellCountX()I
                move-result v0
                const/4 v1, 0x6
                if-le v0, v1, :check_min
                const/4 v0, 0x6
                return v0
                :check_min
                const/4 v1, 0x5
                if-ge v0, v1, :done_hotseat
                const/4 v0, 0x5
                :done_hotseat
                return v0
            """.trimIndent(),
        )

    // 3. Icon scale bounds, default icon scale, and label text size for 6-column grids.
    val gridConfigUtil = mutableClassDefBy("Lcom/miui/home/common/utils/GridConfigUtil;")
    val getScaleScope = gridConfigUtil.findMethodOrThrow("getScaleScopeByCellCount", listOf("I"), "Landroid/util/Pair;")
    val firstScaleScopeInsn = getScaleScope.instructions.first()
    getScaleScope.addInstructionsWithLabels(
        0,
        """
            invoke-static { p0 }, $COMPAT_HELPER->getGridIconScaleScope(I)Landroid/util/Pair;
            move-result-object v0
            if-eqz v0, :stock_scale_scope
            return-object v0
        """.trimIndent(),
        ExternalLabel("stock_scale_scope", firstScaleScopeInsn),
    )

    val getDefaultScale = gridConfigUtil.findMethodOrThrow("getDefaultScaleByCellCount", listOf("I"), "F")
    val firstDefaultScaleInsn = getDefaultScale.instructions.first()
    getDefaultScale.addInstructionsWithLabels(
        0,
        """
            invoke-static { p0 }, $COMPAT_HELPER->getGridDefaultIconScale(I)F
            move-result v0
            const/4 v1, 0x0
            cmpl-float v1, v0, v1
            if-ltz v1, :stock_default_scale
            return v0
        """.trimIndent(),
        ExternalLabel("stock_default_scale", firstDefaultScaleInsn),
    )

    val textSizeConfig = mutableClassDefBy("Lcom/miui/home/common/gridconfig/TextSizeConfig;")
    val calTextSize = textSizeConfig.findMethodOrThrow("calTextSize", listOf("F", "F"), "F")
    val firstTextSizeInsn = calTextSize.instructions.first()
    calTextSize.addInstructionsWithLabels(
        0,
        """
            iget v0, p0, Lcom/miui/home/common/gridconfig/TextSizeConfig;->currentColumn:I
            const/4 v1, 0x6
            if-lt v0, v1, :stock_text_size
            const/high16 v0, 0x41200000
            mul-float/2addr v0, p2
            mul-float/2addr v0, p1
            return v0
        """.trimIndent(),
        ExternalLabel("stock_text_size", firstTextSizeInsn),
    )

    // 4. Support extended layout type encoding (type = cols * 100 + rows for custom grids)
    //    while remaining 100% backward compatible with stock types 0, 1, and 2.
    val layoutType = mutableClassDefBy("Lcom/miui/home/common/LauncherLayoutType;")
    layoutType.findMethodOrThrow("getCellXFromLayoutType", listOf("I"), "I").replaceBody(
        locals = 2,
        smali = """
            invoke-static { p1 }, $COMPAT_HELPER->getCellXFromLayoutType(I)I
            move-result v0
            if-ltz v0, :check_stock_map
            return v0
            :check_stock_map
            sget-object v0, Lcom/miui/home/common/LauncherLayoutType;->LAYOUT_TYPE_TO_CELL_X_MAP:Ljava/util/HashMap;
            invoke-static { p1 }, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;
            move-result-object v1
            invoke-virtual { v0, v1 }, Ljava/util/HashMap;->get(Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object v0
            check-cast v0, Ljava/lang/Integer;
            if-eqz v0, :fallback_cell_x
            invoke-virtual { v0 }, Ljava/lang/Integer;->intValue()I
            move-result v0
            return v0
            :fallback_cell_x
            invoke-static {}, Lcom/miui/home/common/device/DeviceConfigs;->getCellCountX()I
            move-result v0
            return v0
        """.trimIndent(),
    )

    layoutType.findMethodOrThrow("getCurrentTypeName", emptyList(), "Ljava/lang/String;").replaceBody(
        locals = 2,
        smali = """
            iget v0, p0, Lcom/miui/home/common/LauncherLayoutType;->mCurrentType:I
            invoke-static { v0 }, $COMPAT_HELPER->getCustomLayoutTypeName(I)Ljava/lang/String;
            move-result-object v1
            if-eqz v1, :stock_type_name
            return-object v1
            :stock_type_name
            sget-object v1, Lcom/miui/home/common/LauncherLayoutType;->sTrackLayoutTypeName:Ljava/util/HashMap;
            invoke-static { v0 }, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;
            move-result-object v0
            invoke-virtual { v1, v0 }, Ljava/util/HashMap;->get(Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object v0
            check-cast v0, Ljava/lang/String;
            return-object v0
        """.trimIndent(),
    )

    val getDefaultType = layoutType.findMethodOrThrow("getDefaultTypeWithSearchBarStatus", listOf("I"), "I")
    val firstDefaultTypeInsn = getDefaultType.instructions.first()
    getDefaultType.addInstructionsWithLabels(
        0,
        """
            iget v0, p0, Lcom/miui/home/common/LauncherLayoutType;->mCurrentType:I
            const/16 v1, 0x64
            if-lt v0, v1, :stock_default_layout
            invoke-virtual { p0, p1, v0 }, Lcom/miui/home/common/LauncherLayoutType;->isLayoutAndSearchTypeSupported(II)Z
            move-result v1
            if-eqz v1, :stock_default_layout
            return v0
        """.trimIndent(),
        ExternalLabel("stock_default_layout", firstDefaultTypeInsn),
    )

    // 5. Row count computation & balanced vertical row height distribution for custom grids.
    val phoneRules = mutableClassDefBy("Lcom/miui/home/common/gridconfig/PhoneDeviceRules;")
    phoneRules.findMethodOrThrow(
        "calCellCountYWithGridHeightAndLayoutType",
        listOf("I", "I"),
        "I",
    ).replaceBody(
        locals = 3,
        smali = """
            iget v0, p0, Lcom/miui/home/common/gridconfig/GridSizeCalRules;->mCellWidth:I
            if-nez v0, :calc_grid_y
            iget v0, p0, Lcom/miui/home/common/gridconfig/GridSizeCalRules;->mCellCountY:I
            return v0
            :calc_grid_y
            invoke-static {}, Lcom/miui/home/common/LauncherLayoutType;->getInstance()Lcom/miui/home/common/LauncherLayoutType;
            move-result-object v0
            invoke-virtual { v0, p2 }, Lcom/miui/home/common/LauncherLayoutType;->getCellXFromLayoutType(I)I
            move-result v0
            invoke-direct { p0, v0 }, Lcom/miui/home/common/gridconfig/PhoneDeviceRules;->getCellWidth(I)I
            move-result v1
            iget v2, p0, Lcom/miui/home/common/gridconfig/GridSizeCalRules;->mCellCountY:I
            invoke-static { p1, v1, p2, v2 }, $COMPAT_HELPER->computeGridCellCountY(IIII)I
            move-result v0
            return v0
        """.trimIndent(),
    )

    val gridRules = mutableClassDefBy("Lcom/miui/home/common/gridconfig/GridSizeCalRules;")
    val calVarHeight = gridRules.findMethodOrThrow("calVariableHeight", listOf("I", "I"), "Z")
    val firstVarHeightInsn = calVarHeight.instructions.first()
    calVarHeight.addInstructionsWithLabels(
        0,
        """
            invoke-static { p0, p1, p2 }, $COMPAT_HELPER->applyCustomGridVariableHeight(Ljava/lang/Object;II)I
            move-result v0
            if-ltz v0, :stock_var_height
            return v0
        """.trimIndent(),
        ExternalLabel("stock_var_height", firstVarHeightInsn),
    )

    // 6. Enable horizontal carousel scrolling in LayoutPreviewView (mode 7) and populate up to 10
    //    screen-adapted Home screen grid pills across 4, 5, and 6 columns.
    val layoutPreview = mutableClassDefBy("Lcom/miui/home/launcher/LayoutPreviewView;")
    layoutPreview.findMethodOrThrow("initWidgetThumbnailView", emptyList(), "V").replaceBody(
        locals = 2,
        smali = """
            invoke-super { p0 }, Lcom/miui/home/launcher/EditingEntryThumbnailView;->initWidgetThumbnailView()V
            const/4 v0, 0x7
            const/4 v1, 0x1
            invoke-virtual { p0, v0, v1 }, Lcom/miui/home/launcher/ScreenView;->setScreenLayoutMode(IZ)V
            invoke-static {}, Lcom/miui/home/common/device/DeviceConfigs;->getLayoutPreviewBottomButtonGap()I
            move-result v0
            invoke-virtual { p0, v0 }, Lcom/miui/home/launcher/ScreenView;->setFixedGap(I)V
            return-void
        """.trimIndent(),
    )
    layoutPreview.findMethodOrThrow("prepareCellCountButton", emptyList(), "V").replaceBody(
        locals = 0,
        smali = """
            invoke-static { p0 }, $COMPAT_HELPER->populateLayoutPreviewButtons(Ljava/lang/Object;)V
            return-void
        """.trimIndent(),
    )
    layoutPreview.findMethodOrThrow("prepareToShow", emptyList(), "V").replaceBody(
        locals = 1,
        smali = """
            invoke-direct { p0 }, Lcom/miui/home/launcher/LayoutPreviewView;->prepareCellCountXYInUse()V
            invoke-direct { p0 }, Lcom/miui/home/launcher/LayoutPreviewView;->prepareCellCountButton()V
            invoke-virtual { p0 }, Lcom/miui/home/launcher/LayoutPreviewView;->reLoadThumbnails()V
            const/4 v0, 0x0
            invoke-virtual { p0, v0 }, Lcom/miui/home/launcher/LayoutPreviewView;->updateBorderLine(Z)V
            invoke-static { p0 }, $COMPAT_HELPER->scrollLayoutPreviewToActive(Ljava/lang/Object;)V
            return-void
        """.trimIndent(),
    )

    mutableClassDefBy("Lcom/miui/home/launcher/EditingEntryThumbnailView;")
        .findMethodOrThrow("isScrollable", emptyList(), "Z")
        .replaceBody(
            locals = 1,
            smali = """
                instance-of v0, p0, Lcom/miui/home/launcher/LayoutPreviewView;
                return v0
            """.trimIndent(),
        )
}

private fun patchDefaultWorkspacePopulation() {
    val dbHelper = mutableClassDefBy("Lcom/miui/home/model/core/LauncherProvider\$DatabaseHelper;")
    val loadDefault = dbHelper.findMethodOrThrow(
        "loadDefaultWorkspace",
        listOf(
            "Landroid/database/sqlite/SQLiteDatabase;",
            "Lcom/miui/home/common/launchermode/ILauncherMode;",
            "Ljava/util/Map;",
        ),
        "V",
    )
    val insns = loadDefault.instructions.toList()
    val loadFavIdx = insns.indexOfFirst { ins ->
        ins.opcode == Opcode.INVOKE_DIRECT &&
            (ins as? ReferenceInstruction)?.reference
                .let { it as? MethodReference }
                ?.let {
                    it.definingClass == "Lcom/miui/home/model/core/LauncherProvider\$DatabaseHelper;" &&
                        it.name == "loadFavorites"
                } == true
    }
    check(loadFavIdx >= 0) {
        "POCO Launcher: chamada loadFavorites não encontrada em LauncherProvider\$DatabaseHelper.loadDefaultWorkspace"
    }
    val invokeIns = insns[loadFavIdx] as FiveRegisterInstruction
    val thisReg = invokeIns.registerC
    val dbReg = invokeIns.registerD
    val modeReg = invokeIns.registerE
    val appsReg = invokeIns.registerF
    val scratchReg = if (appsReg == 1) 2 else 1

    loadDefault.addInstructions(
        loadFavIdx + 1,
        """
            iget-object v$scratchReg, v$thisReg, Lcom/miui/home/model/core/LauncherProvider${'$'}DatabaseHelper;->mContext:Landroid/content/Context;
            invoke-static { v$scratchReg, v$dbReg, v$modeReg, v$appsReg }, $COMPAT_HELPER->populateNonXiaomiDefaultWorkspace(Landroid/content/Context;Landroid/database/sqlite/SQLiteDatabase;Ljava/lang/Object;Ljava/util/Collection;)V
        """.trimIndent(),
    )
}

private fun patchAppWidgetBinding() {
    val addWidget = BaseLauncherAddAppWidgetFingerprint.method
    val insns = addWidget.instructions.toList()

    val bindCallIndex = insns.indexOfFirst { ins ->
        ins.opcode == Opcode.INVOKE_STATIC &&
            (ins as? ReferenceInstruction)?.reference
                .let { it as? MethodReference }
                ?.let {
                    it.definingClass == "Lcom/miui/home/common/utils/WidgetUtils;" &&
                        it.name == "bindAppWidgetIdIfAllowed"
                } == true
    }
    check(bindCallIndex >= 0) {
        "POCO Launcher: chamada WidgetUtils.bindAppWidgetIdIfAllowed não encontrada"
    }

    val bindIns = insns[bindCallIndex] as FiveRegisterInstruction
    val launcherReg = bindIns.registerC // v8 (this)
    val widgetIdReg = bindIns.registerD // v1
    val optionsReg = bindIns.registerG // v4 (Bundle before move-result overwrites it)

    val regs = addWidget.implementation!!.registerCount
    val widgetInfoReg = regs - 2 // v9
    val cellScreenReg = regs - 1 // v10
    val savedOptionsReg = 7 // v7 is unused until after the bind-allowed branch

    // Find the `if-eqz` right after `move-result` that branches to the bind-denied path.
    val ifEqzIndex = (bindCallIndex + 1 until minOf(bindCallIndex + 5, insns.size)).firstOrNull { idx ->
        insns[idx].opcode == Opcode.IF_EQZ
    } ?: throw PatchException("POCO Launcher: if-eqz após bindAppWidgetIdIfAllowed não encontrado")

    // Find the `move-object v4, v5` target immediately after the `goto` of the bind-allowed branch.
    val gotoAfterAllowed = (ifEqzIndex + 1 until insns.size).firstOrNull { idx ->
        insns[idx].opcode == Opcode.GOTO || insns[idx].opcode == Opcode.GOTO_16
    } ?: throw PatchException("POCO Launcher: goto do ramo bind-allowed não encontrado")
    val deniedTargetIndex = gotoAfterAllowed + 1
    check(insns[deniedTargetIndex].opcode == Opcode.MOVE_OBJECT) {
        "POCO Launcher: instrução esperada move-object no ramo bind-denied não encontrada (encontrado ${insns[deniedTargetIndex].opcode})"
    }

    // Insert the bind-denied consent handler at deniedTargetIndex (preserving the branch label!) first,
    // then save `options` into v7 right before `bindAppWidgetIdIfAllowed`.
    addWidget.addInstructionsAtControlFlowLabel(
        deniedTargetIndex,
        """
            invoke-static { v$launcherReg, v$widgetIdReg, v$widgetInfoReg, v$cellScreenReg, v$savedOptionsReg }, $COMPAT_HELPER->requestBindAppWidget(Landroid/app/Activity;ILjava/lang/Object;Ljava/lang/Object;Landroid/os/Bundle;)V
            const/4 v$savedOptionsReg, -0x1
            return v$savedOptionsReg
        """.trimIndent(),
    )
    addWidget.addInstructions(
        bindCallIndex,
        "move-object v$savedOptionsReg, v$optionsReg",
    )

    // Dispatch requestCode == 11 (ACTION_APPWIDGET_BIND) & pending requestCode == 5 (configure)
    // at the start of BaseLauncher.onActivityResult.
    val onResult = BaseLauncherOnActivityResultFingerprint.method
    val firstOriginalInstruction = onResult.instructions.first()
    onResult.addInstructionsWithLabels(
        0,
        """
            invoke-static { p0, p1, p2, p3 }, $COMPAT_HELPER->handleAppWidgetBindResult(Landroid/app/Activity;IILandroid/content/Intent;)Z
            move-result v0
            if-eqz v0, :continue_original
            return-void
        """.trimIndent(),
        ExternalLabel("continue_original", firstOriginalInstruction),
    )

    // Utilities.getAppVersionCode(Context, String, UserHandle): original reflected hidden
    // PackageManager.getPackageInfoAsUser, caught InvocationTargetException(SecurityException)
    // and returned 0 -> caused BaseLauncher.completeAddAppWidget to mark EVERY widget as a
    // dead restore placeholder (status=0) instead of creating its AppWidgetHostView!
    val launcherUtilities = mutableClassDefBy("Lcom/miui/home/launcher/common/Utilities;")
    launcherUtilities.findMethodOrThrow(
        "getAppVersionCode",
        listOf("Landroid/content/Context;", "Ljava/lang/String;", "Landroid/os/UserHandle;"),
        "I",
    ).replaceBody(
        locals = 1,
        smali = """
            invoke-static { p0, p1, p2 }, $COMPAT_HELPER->getAppVersionCode(Landroid/content/Context;Ljava/lang/String;Landroid/os/UserHandle;)I
            move-result v0
            return v0
        """.trimIndent(),
    )

    // LauncherAppWidgetHost.startConfigActivity: pass Android 14+ BAL-allowed ActivityOptions
    // and preserve mLastAddInfo across BaseLauncher.onResume()->clearLastAddInfo().
    val widgetHost = mutableClassDefBy("Lcom/miui/home/launcher/LauncherAppWidgetHost;")
    widgetHost.findMethodOrThrow(
        "startConfigActivity",
        listOf("Lcom/miui/home/common/BaseActivity;", "I", "I"),
        "V",
    ).replaceBody(
        locals = 0,
        smali = """
            invoke-static { p0, p1, p2, p3 }, $COMPAT_HELPER->startWidgetConfigActivity(Landroid/appwidget/AppWidgetHost;Landroid/app/Activity;II)V
            return-void
        """.trimIndent(),
    )

    // BaseLauncher.updateQsbDefaultLayout: null-guard ActivityInfo.metaData when adding Google Search widget.
    val baseLauncher = mutableClassDefBy("Lcom/miui/home/launcher/BaseLauncher;")
    baseLauncher.findMethodOrThrow(
        "updateQsbDefaultLayout",
        listOf("Landroid/content/Context;", "Landroid/appwidget/AppWidgetProviderInfo;", "Landroid/appwidget/AppWidgetHostView;"),
        "V",
    ).replaceBody(
        locals = 0,
        smali = """
            invoke-static { p1, p2, p3 }, $COMPAT_HELPER->safeUpdateQsbDefaultLayout(Landroid/content/Context;Landroid/appwidget/AppWidgetProviderInfo;Landroid/appwidget/AppWidgetHostView;)V
            return-void
        """.trimIndent(),
    )

    // InstallWidgetReceiver$2.run: when pinning a widget from inside another app (AddItemActivity),
    // launch ACTION_APPWIDGET_BIND instead of silently aborting on bindAppWidgetIdIfAllowed == false.
    val pinRunnable = mutableClassDefBy("Lcom/miui/home/launcher/InstallWidgetReceiver\$2;")
    val pinRun = pinRunnable.findMethodOrThrow("run", emptyList(), "V")
    val pinInsns = pinRun.instructions.toList()
    val pinBindIdx = pinInsns.indexOfFirst { ins ->
        ins.opcode == Opcode.INVOKE_STATIC &&
            (ins as? ReferenceInstruction)?.reference
                .let { it as? MethodReference }
                ?.let {
                    it.definingClass == "Lcom/miui/home/common/utils/WidgetUtils;" &&
                        it.name == "bindAppWidgetIdIfAllowed"
                } == true
    }
    check(pinBindIdx >= 0) {
        "POCO Launcher: chamada bindAppWidgetIdIfAllowed não encontrada em InstallWidgetReceiver\$2.run"
    }
    val fromProviderIdx = pinInsns.indexOfFirst { ins ->
        ins.opcode == Opcode.INVOKE_STATIC &&
            (ins as? ReferenceInstruction)?.reference
                .let { it as? MethodReference }
                ?.let {
                    it.definingClass == "Lcom/miui/home/launcher/LauncherAppWidgetProviderInfo;" &&
                        it.name == "fromProviderInfo"
                } == true
    }
    check(fromProviderIdx >= 0 && pinInsns[fromProviderIdx + 1].opcode == Opcode.MOVE_RESULT_OBJECT) {
        "POCO Launcher: LauncherAppWidgetProviderInfo.fromProviderInfo não encontrada em InstallWidgetReceiver\$2.run"
    }
    val pinIfNezIdx = (pinBindIdx + 1 until minOf(pinBindIdx + 5, pinInsns.size)).firstOrNull { idx ->
        pinInsns[idx].opcode == Opcode.IF_NEZ
    } ?: throw PatchException("POCO Launcher: if-nez após bindAppWidgetIdIfAllowed não encontrado em InstallWidgetReceiver\$2.run")

    val pinBindIns = pinInsns[pinBindIdx] as FiveRegisterInstruction
    val pinThisReg = pinRun.implementation!!.registerCount - 1
    val pinLauncherReg = pinBindIns.registerC
    val pinWidgetIdReg = pinBindIns.registerD
    val pinOptionsReg = pinBindIns.registerG
    val pinProviderInfoReg = (pinInsns[fromProviderIdx + 1] as OneRegisterInstruction).registerA

    pinRun.addInstructions(
        pinIfNezIdx + 1,
        """
            invoke-static { v$pinThisReg, v$pinLauncherReg, v$pinWidgetIdReg, v$pinProviderInfoReg, v$pinOptionsReg }, $COMPAT_HELPER->requestPinBindAppWidget(Ljava/lang/Object;Landroid/app/Activity;ILjava/lang/Object;Landroid/os/Bundle;)V
            return-void
        """.trimIndent(),
    )

    // BaseWidgetsVerticalAdapter.buildGadgetItems: hide broken MIUI MAML theme clock gadgets (6, 8)
    // that require absent /data/system/theme/clock_2x4 and show "Couldn't load widget" errors.
    val widgetAdapter = mutableClassDefBy("Lcom/miui/home/launcher/widget/BaseWidgetsVerticalAdapter;")
    widgetAdapter.findMethodOrThrow("buildGadgetItems", listOf("Ljava/util/ArrayList;"), "V")
        .replaceBody(locals = 0, smali = "return-void")
}

private fun patchNotificationBadges() {
    val notifHelper = mutableClassDefBy("Lcom/miui/home/launcher/NotificationHelper;")

    // 1. NotificationHelper.useSystemUI(): non-MIUI com.android.systemui lacks MIUI metaData,
    //    which set mUseSystemUI = true and prevented subscribing to NotificationListener!
    notifHelper.findMethodOrThrow("useSystemUI", emptyList(), "V").replaceBody(
        locals = 1,
        smali = """
            const/4 v0, 0x0
            iput-boolean v0, p0, Lcom/miui/home/launcher/NotificationHelper;->mUseSystemUI:Z
            return-void
        """.trimIndent(),
    )

    // 2. NotificationHelper.registerListener(): never instantiate a dummy NotificationListener or
    //    call hidden registerAsSystemService (which also overwrote sNotificationListenerInstance!).
    //    Instead ensure Android's real bound NotificationListenerService is connected.
    notifHelper.findMethodOrThrow("registerListener", emptyList(), "V").replaceBody(
        locals = 1,
        smali = """
            const/4 v0, 0x0
            iput-boolean v0, p0, Lcom/miui/home/launcher/NotificationHelper;->mUseSystemUI:Z
            iget-object v0, p0, Lcom/miui/home/launcher/NotificationHelper;->mLauncher:Lcom/miui/home/launcher/BaseLauncher;
            invoke-static { v0 }, $COMPAT_HELPER->ensureNotificationListenerBound(Landroid/content/Context;)V
            return-void
        """.trimIndent(),
    )

    // 3. NotificationHelper.unRegisterListener(): avoid hidden unRegisterAsSystemService.
    notifHelper.findMethodOrThrow("unRegisterListener", emptyList(), "V")
        .replaceBody(locals = 0, smali = "return-void")

    // 4. NotificationHelper.registerNotificationChangeListener(): request rebind if user just
    //    enabled Notification Access and returned to Launcher.
    val regChange = notifHelper.findMethodOrThrow("registerNotificationChangeListener", emptyList(), "V")
    regChange.addInstructions(
        0,
        """
            iget-object v0, p0, Lcom/miui/home/launcher/NotificationHelper;->mLauncher:Lcom/miui/home/launcher/BaseLauncher;
            invoke-static { v0 }, $COMPAT_HELPER->ensureNotificationListenerBound(Landroid/content/Context;)V
        """.trimIndent(),
    )

    // 5. NotificationListener.shouldBeFilteredOut(StatusBarNotification): guard against null
    //    Notification / null extras so unexpected third-party notifications never crash the listener.
    val notifListener = mutableClassDefBy("Lcom/miui/home/launcher/notification/NotificationListener;")
    val shouldFilter = notifListener.findMethodOrThrow(
        "shouldBeFilteredOut",
        listOf("Landroid/service/notification/StatusBarNotification;"),
        "Z",
    )
    val firstFilterInsn = shouldFilter.instructions.first()
    shouldFilter.addInstructionsWithLabels(
        0,
        """
            invoke-static { p1 }, $COMPAT_HELPER->hasInvalidNotificationExtras(Landroid/service/notification/StatusBarNotification;)Z
            move-result v0
            if-eqz v0, :continue_filter
            const/4 v0, 0x1
            return v0
        """.trimIndent(),
        ExternalLabel("continue_filter", firstFilterInsn),
    )
}

private fun patchNonXiaomiSettingsAndFallbacks() {
    // 1. MiuiHomeSettings: add live "Emblemas de notificação" / "Notification badges" and
    //    "Ícones" / "Icons" (size + monochrome) entries, wire rootless Double-Tap to Lock and
    //    System Navigation redirect, hide MIUI-only Recents items, and replace privileged
    //    injectKeyEvent(3) so "Home screen layout" returns to Launcher and opens grid preview.
    val homeSettings = mutableClassDefBy("Lcom/miui/home/settings/MiuiHomeSettings;")

    val onCreatePrefs = homeSettings.findMethodOrThrow(
        "onCreatePreferences",
        listOf("Landroid/os/Bundle;", "Ljava/lang/String;"),
        "V",
    )
    val createReturns = onCreatePrefs.instructions.withIndex()
        .filter { it.value.opcode == Opcode.RETURN_VOID }
        .map { it.index }
        .asReversed()
    check(createReturns.isNotEmpty()) {
        "POCO Launcher: return-void não encontrado em MiuiHomeSettings.onCreatePreferences"
    }
    for (retIdx in createReturns) {
        onCreatePrefs.addInstructionsAtControlFlowLabel(
            retIdx,
            "invoke-static { p0 }, $COMPAT_HELPER->setupNonXiaomiHomeSettings(Ljava/lang/Object;)V",
        )
    }

    val onResumeSettings = homeSettings.findMethodOrThrow("onResume", emptyList(), "V")
    // R8 clobbers p0 (v4) at the end of onResume() with `iget-object v4, v4, mDoubleTapLockPref`
    // right before `return-void`, which previously passed a CheckBoxPreference instead of
    // MiuiHomeSettings to refreshNonXiaomiHomeSettings(p0). Redirect that load/call to a dead
    // scratch register so p0 stays `this` across every return site.
    val resumeInsns = onResumeSettings.instructions.toList()
    val resumeThisReg = (onResumeSettings.implementation?.registerCount ?: 1) - 1
    val clobberIdx = resumeInsns.indexOfFirst { ins ->
        ins.opcode == Opcode.IGET_OBJECT &&
            ins is TwoRegisterInstruction &&
            ins.registerA == resumeThisReg &&
            ins.registerB == resumeThisReg &&
            ((ins as? ReferenceInstruction)?.reference as? FieldReference)?.name == "mDoubleTapLockPref"
    }
    if (clobberIdx >= 0) {
        val setCheckedIdx = (clobberIdx + 1 until minOf(clobberIdx + 4, resumeInsns.size)).firstOrNull { idx ->
            val ins = resumeInsns[idx]
            ins.opcode == Opcode.INVOKE_VIRTUAL &&
                ins is FiveRegisterInstruction &&
                ins.registerC == resumeThisReg &&
                ((ins as? ReferenceInstruction)?.reference as? MethodReference)?.name == "setChecked"
        } ?: throw PatchException(
            "POCO Launcher: TwoStatePreference.setChecked não encontrado após mDoubleTapLockPref em MiuiHomeSettings.onResume",
        )
        val setCheckedIns = resumeInsns[setCheckedIdx] as FiveRegisterInstruction
        val boolReg = setCheckedIns.registerD
        val scratchReg = if (boolReg == 1) 2 else 1
        onResumeSettings.replaceInstruction(
            clobberIdx,
            "iget-object v$scratchReg, v$resumeThisReg, Lcom/miui/home/settings/MiuiHomeSettings;->mDoubleTapLockPref:Lcom/miui/home/settings/preference/CheckBoxPreference;",
        )
        onResumeSettings.replaceInstruction(
            setCheckedIdx,
            "invoke-virtual { v$scratchReg, v$boolReg }, Landroidx/preference/TwoStatePreference;->setChecked(Z)V",
        )
    }
    val resumeReturns = onResumeSettings.instructions.withIndex()
        .filter { it.value.opcode == Opcode.RETURN_VOID }
        .map { it.index }
        .asReversed()
    check(resumeReturns.isNotEmpty()) {
        "POCO Launcher: return-void não encontrado em MiuiHomeSettings.onResume"
    }
    for (retIdx in resumeReturns) {
        onResumeSettings.addInstructionsAtControlFlowLabel(
            retIdx,
            "invoke-static { p0 }, $COMPAT_HELPER->refreshNonXiaomiHomeSettings(Ljava/lang/Object;)V",
        )
    }

    // Hook MiuiHomeSettings.onPreferenceChange right after saving lock_screen_mode.
    // Note: R8 reuses p0 (v5) for `const-string "lock_screen_mode"`, so we preserve p0 in v1
    // immediately before that const-string and pass { v1, p2 } after putBooleanToCurrentUser.
    val onPrefChange = homeSettings.findMethodOrThrow(
        "onPreferenceChange",
        listOf("Landroidx/preference/Preference;", "Ljava/lang/Object;"),
        "Z",
    )
    val prefChangeInsns = onPrefChange.instructions.toList()
    val lockKeyIdx = prefChangeInsns.indexOfFirst { ins ->
        (ins.opcode == Opcode.CONST_STRING || ins.opcode == Opcode.CONST_STRING_JUMBO) &&
            ((ins as? ReferenceInstruction)?.reference as? StringReference)?.string == "lock_screen_mode"
    }
    check(lockKeyIdx >= 0) {
        "POCO Launcher: const-string \"lock_screen_mode\" não encontrado em MiuiHomeSettings.onPreferenceChange"
    }
    val putLockIdx = (lockKeyIdx + 1 until minOf(lockKeyIdx + 8, prefChangeInsns.size)).firstOrNull { idx ->
        val ins = prefChangeInsns[idx]
        ins.opcode.isStaticInvoke() &&
            ((ins as? ReferenceInstruction)?.reference as? MethodReference)?.let {
                it.definingClass == "Lcom/miui/launcher/utils/MiuiSettingsUtils;" &&
                    it.name == "putBooleanToCurrentUser"
            } == true
    } ?: throw PatchException(
        "POCO Launcher: MiuiSettingsUtils.putBooleanToCurrentUser não encontrado após lock_screen_mode",
    )
    onPrefChange.addInstructions(
        putLockIdx + 1,
        "invoke-static { v1, p2 }, $COMPAT_HELPER->onDoubleTapLockPreferenceChanged(Ljava/lang/Object;Ljava/lang/Object;)V",
    )
    onPrefChange.addInstructions(
        lockKeyIdx,
        "move-object v1, p0",
    )

    homeSettings.findMethodOrThrow("injectKeyEvent", listOf("I"), "V").replaceBody(
        locals = 0,
        smali = """
            invoke-static { p0, p1 }, $COMPAT_HELPER->navigateToHomeForSettings(Ljava/lang/Object;I)V
            return-void
        """.trimIndent(),
    )

    // Keep POCO Launcher's own working settings visible even before user switches default Home app.
    homeSettings.findMethodOrThrow("checkDefaultLauncher", listOf("Ljava/lang/String;"), "V")
        .addInstructions(0, """const-string p1, "com.mi.android.globallauncher"""")
    val pocoSettings = mutableClassDefBy("Lcom/miui/home/settings/PocoHomeSettings;")
    pocoSettings.findMethodOrThrow("checkDefaultLauncher", listOf("Ljava/lang/String;"), "V")
        .addInstructions(0, """const-string p1, "com.mi.android.globallauncher"""")

    // 2. SuperDraglayer$DoubleTapListener.onDoubleTapEvent: replace MIUI ExtraStatusBarManager
    //    toggle broadcast with rootless AccessibilityService GLOBAL_ACTION_LOCK_SCREEN.
    val doubleTapListener = mutableClassDefBy("Lcom/miui/home/launcher/SuperDraglayer\$DoubleTapListener;")
    val onDoubleTapEvent = doubleTapListener.findMethodOrThrow(
        "onDoubleTapEvent",
        listOf("Landroid/view/MotionEvent;"),
        "Z",
    )
    val dtInsns = onDoubleTapEvent.instructions.toList()
    val sendBroadcastIdx = dtInsns.indexOfFirst { ins ->
        val ref = (ins as? ReferenceInstruction)?.reference as? MethodReference ?: return@indexOfFirst false
        (ins.opcode.isStaticInvoke() && ref.definingClass == COMPAT_HELPER && ref.name == "safeSendBroadcast") ||
            (ins.opcode.isVirtualInvoke() && ref.definingClass == "Landroid/content/Context;" && ref.name == "sendBroadcast")
    }
    check(sendBroadcastIdx >= 0) {
        "POCO Launcher: chamada sendBroadcast/safeSendBroadcast não encontrada em SuperDraglayer\$DoubleTapListener.onDoubleTapEvent"
    }
    val ctxReg = when (val ins = dtInsns[sendBroadcastIdx]) {
        is FiveRegisterInstruction -> ins.registerC
        is RegisterRangeInstruction -> ins.startRegister
        else -> throw PatchException("POCO Launcher: instrução invoke inesperada em onDoubleTapEvent: ${ins.opcode}")
    }
    onDoubleTapEvent.replaceInstruction(
        sendBroadcastIdx,
        "invoke-static { v$ctxReg }, $COMPAT_HELPER->performDoubleTapLockScreen(Landroid/content/Context;)V",
    )

    // 3. LauncherUtils.expandStatusBar: prefer public GLOBAL_ACTION_NOTIFICATIONS when our
    //    AccessibilityService is connected, falling back to StatusBarManager reflection.
    val launcherUtils = mutableClassDefBy("Lcom/miui/launcher/utils/LauncherUtils;")
    launcherUtils.findMethodOrThrow("expandStatusBar", listOf("Landroid/content/Context;"), "V")
        .replaceBody(
            locals = 0,
            smali = """
                invoke-static { p0 }, $COMPAT_HELPER->expandNotificationsPanel(Landroid/content/Context;)V
                return-void
            """.trimIndent(),
        )

    // 4. Skip obsolete READ_EXTERNAL_STORAGE first-run guide prompt (auto-denied silently on API 33+).
    val permUtils = mutableClassDefBy("Lcom/miui/home/launcher/util/PermissionUtils;")
    permUtils.findMethodOrThrow("hasStoragePermission", listOf("Landroid/content/Context;"), "Z")
        .replaceBody(
            locals = 1,
            smali = """
                const/4 v0, 0x1
                return v0
            """.trimIndent(),
        )

    // 5. HideAppsLockUtils.setFingerComponent: fall back to Android Biometric Enroll / Security
    //    Settings when MIUI MiuiSecurityChooseUnlock / NewFingerprintActivity are absent.
    val hideAppsLockUtils = mutableClassDefBy("Lcom/miui/home/launcher/allapps/hideapps/HideAppsLockUtils;")
    hideAppsLockUtils.findMethodOrThrow(
        "setFingerComponent",
        listOf("Ljava/lang/String;", "Ljava/lang/String;"),
        "Landroid/content/Intent;",
    ).replaceBody(
        locals = 1,
        smali = """
            invoke-static { p0, p1 }, $COMPAT_HELPER->createSecurityOrBiometricIntent(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;
            move-result-object v0
            return-object v0
        """.trimIndent(),
    )

    // 6. OpenSearchUtil & InternationalGlobalSearch: fall back to Google Search / ACTION_WEB_SEARCH
    //    when MIUI QuickSearchBox / AppFinder are not installed.
    val openSearchUtil = mutableClassDefBy("Lcom/miui/home/launcher/overlay/search/OpenSearchUtil;")
    openSearchUtil.findMethodOrThrow(
        "startGlobalSearchByActivity",
        listOf(
            "Lcom/miui/home/launcher/BaseLauncher;",
            "Ljava/lang/String;",
            "Ljava/lang/String;",
            "I",
            "I",
            "Landroid/view/View;",
        ),
        "V",
    ).replaceBody(
        locals = 0,
        smali = """
            invoke-static/range { p0 .. p5 }, $COMPAT_HELPER->startGlobalSearchFallback(Landroid/app/Activity;Ljava/lang/String;Ljava/lang/String;IILandroid/view/View;)V
            return-void
        """.trimIndent(),
    )

    val intlSearch = mutableClassDefBy("Lcom/miui/home/launcher/InternationalGlobalSearch;")
    intlSearch.findMethodOrThrow(
        "startGlobalSearchByActivity",
        listOf("Lcom/miui/home/launcher/BaseLauncher;", "Landroid/view/View;", "Z"),
        "V",
    ).replaceBody(
        locals = 0,
        smali = """
            invoke-static { p0, p1, p2 }, $COMPAT_HELPER->startInternationalGlobalSearchFallback(Landroid/app/Activity;Landroid/view/View;Z)V
            return-void
        """.trimIndent(),
    )

    // 7. Unlock native POCO features gated behind absent MIUI version/booster checks:
    //    - "Don't show text" (hide home icon labels, gated on HyperOsVersionUtils.ATLEAST_MIUI_13)
    //    - "Animation speed" (Relaxed / Balanced / Fast, gated on absent MiuiBooster.jar)
    //    - Hide dead MIUI "Effects" button in home edit mode -> clean 3-button edit bar
    val returnTrueSmali = """
        const/4 v0, 0x1
        return v0
    """.trimIndent()

    val noWordHelper = mutableClassDefBy("Lcom/miui/home/launcher/util/noword/NoWordSettingHelperKt;")
    noWordHelper.findMethodOrThrow("isNoWordAvailable", emptyList(), "Z")
        .replaceBody(locals = 1, smali = returnTrueSmali)

    val deviceLevelUtils = mutableClassDefBy("Lcom/miui/home/common/utils/DeviceLevelUtils;")
    deviceLevelUtils.findMethodOrThrow("supportSetAnimationRate", emptyList(), "Z")
        .replaceBody(locals = 1, smali = returnTrueSmali)

    val deviceConfig = mutableClassDefBy("Lcom/miui/home/launcher/DeviceConfig;")
    deviceConfig.findMethodOrThrow("isHideWallpaperEffectBtn", emptyList(), "Z")
        .replaceBody(locals = 1, smali = returnTrueSmali)

    // 8. Restore "Swipe down on Home screen" setting with 3 working actions (Notifications,
    //    Search via Google Search fallback, and None), show "Search" label instead of absent
    //    "App Finder", and dispatch all 3 actions directly in LauncherGestureController.
    homeSettings.findMethodOrThrow("initLauncherPullDown", emptyList(), "V").replaceBody(
        locals = 0,
        smali = """
            invoke-static { p0 }, $COMPAT_HELPER->initNonXiaomiPullDownSetting(Ljava/lang/Object;)V
            return-void
        """.trimIndent(),
    )

    val gestureController = mutableClassDefBy("Lcom/miui/home/settings/LauncherGestureController;")
    val getPullDownValue = gestureController.findMethodOrThrow(
        "getPullDownValue",
        listOf("Landroid/content/Context;"),
        "I",
    )
    val pullDownInsns = getPullDownValue.instructions.toList()
    val intlBuildIdx = pullDownInsns.indexOfFirst { ins ->
        ins.opcode == Opcode.SGET_BOOLEAN &&
            (ins as? ReferenceInstruction)?.reference
                .let { it as? FieldReference }
                ?.let { it.definingClass == "Lmiui/os/Build;" && it.name == "IS_INTERNATIONAL_BUILD" } == true
    }
    check(intlBuildIdx >= 0) {
        "POCO Launcher: sget-boolean Build.IS_INTERNATIONAL_BUILD não encontrado em LauncherGestureController.getPullDownValue"
    }
    val intlReg = (pullDownInsns[intlBuildIdx] as OneRegisterInstruction).registerA
    getPullDownValue.replaceInstruction(intlBuildIdx, "const/4 v$intlReg, 0x0")

    gestureController.findMethodOrThrow("onPullDownAction", emptyList(), "V").replaceBody(
        locals = 0,
        smali = """
            invoke-static { p0 }, $COMPAT_HELPER->handlePullDownAction(Ljava/lang/Object;)V
            return-void
        """.trimIndent(),
    )

    // 9. Unlock "On widgets" ("Em widgets") option inside "Don't show text" (works on all standard
    //    Android AppWidgets; was only hidden because isOnlyWidgetNoWordModelSupport() checked MIUIWidgetUtil).
    val launcherUtilities = mutableClassDefBy("Lcom/miui/home/launcher/common/Utilities;")
    launcherUtilities.findMethodOrThrow("isOnlyWidgetNoWordModelSupport", emptyList(), "Z")
        .replaceBody(locals = 1, smali = returnTrueSmali)

    // 10. Unlock HyperOS 3 "Home screen search" settings page & floating "Search" capsule pill
    //     ("Compact search" / "Pesquisa compacta", type 2 above dock; tapping launches Google Search
    //     fallback). Keep default = 0 (Off) so users opt in cleanly without unexpected layout shifts.
    val searchStrategyFactory = mutableClassDefBy(
        "Lcom/miui/home/settings/DefaultSearchSettingsStrategyFactory;",
    )
    searchStrategyFactory.findMethodOrThrow(
        "createStrategy",
        listOf(
            "Landroid/content/Context;",
            "Lcom/miui/home/settings/preference/ValuePreference;",
            "Lcom/miui/home/settings/preference/CheckBoxPreference;",
            "Landroidx/preference/Preference\$OnPreferenceChangeListener;",
        ),
        "Lcom/miui/home/settings/BaseSearchSettingsStrategy;",
    ).replaceBody(
        locals = 1,
        smali = """
            new-instance v0, Lcom/miui/home/settings/SearchSettingsStrategyOS3;
            invoke-direct { v0, p1, p2 }, Lcom/miui/home/settings/SearchSettingsStrategyOS3;-><init>(Landroid/content/Context;Lcom/miui/home/settings/preference/ValuePreference;)V
            return-object v0
        """.trimIndent(),
    )

    val returnZeroIntSmali = """
        const/4 v0, 0x0
        return v0
    """.trimIndent()
    val searchBarConfig = mutableClassDefBy("Lcom/miui/home/common/searchbarconfig/SearchBarConfig;")
    searchBarConfig.findMethodOrThrow("lambda\$static\$1", emptyList(), "Z")
        .replaceBody(locals = 1, smali = returnTrueSmali)
    searchBarConfig.findMethodOrThrow("getDefaultSearchBarType", emptyList(), "I")
        .replaceBody(locals = 1, smali = returnZeroIntSmali)
    searchBarConfig.findMethodOrThrow("getLegacyDefaultSearchBarType", emptyList(), "I")
        .replaceBody(locals = 1, smali = returnZeroIntSmali)

    // 11. Restore "Open search after opening App drawer" ("Abrir pesquisa após abrir a Gaveta de
    //     apps") backed by POCO's built-in local App Drawer search bar instead of absent AppFinder.
    val baseAllAppsSettings = mutableClassDefBy("Lcom/miui/home/settings/BaseAllAppsSettingsFragment;")
    baseAllAppsSettings.findMethodOrThrow("controlFinderSetting", emptyList(), "V").replaceBody(
        locals = 0,
        smali = """
            invoke-static { p0 }, $COMPAT_HELPER->setupDrawerQuickSearchPreference(Ljava/lang/Object;)V
            return-void
        """.trimIndent(),
    )
    val drawerPrefChange = baseAllAppsSettings.findMethodOrThrow(
        "onPreferenceChange",
        listOf("Landroidx/preference/Preference;", "Ljava/lang/Object;"),
        "Z",
    )
    val firstDrawerPrefInsn = drawerPrefChange.instructions.first()
    drawerPrefChange.addInstructionsWithLabels(
        0,
        """
            invoke-static { p0, p1, p2 }, $COMPAT_HELPER->handleDrawerQuickSearchPreferenceChange(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z
            move-result v0
            if-eqz v0, :continue_drawer_pref_change
            const/4 v0, 0x1
            return v0
        """.trimIndent(),
        ExternalLabel("continue_drawer_pref_change", firstDrawerPrefInsn),
    )

    val allAppsSwipe = mutableClassDefBy("Lcom/miui/home/launcher/uioverrides/AllAppsSwipeController;")
    val onSwipeDone = allAppsSwipe.methods.firstOrNull {
        it.name == "onSwipeInteractionCompleted" &&
            it.parameterTypes.map(CharSequence::toString) == listOf("Lcom/miui/home/launcher/LauncherState;") &&
            it.returnType == "V"
    } ?: allAppsSwipe.findMethodOrThrow(
        "lambda\$onDragEnd\$0",
        listOf("Lcom/miui/home/launcher/LauncherState;"),
        "V",
    )
    val swipeDoneReturns = onSwipeDone.instructions.withIndex()
        .filter { it.value.opcode == Opcode.RETURN_VOID }
        .map { it.index }
        .asReversed()
    check(swipeDoneReturns.isNotEmpty()) {
        "POCO Launcher: return-void não encontrado em AllAppsSwipeController.${onSwipeDone.name}"
    }
    for (retIdx in swipeDoneReturns) {
        onSwipeDone.addInstructionsAtControlFlowLabel(
            retIdx,
            """
                iget-object v0, p0, Lcom/miui/home/launcher/uioverrides/AllAppsSwipeController;->mLauncher:Lcom/miui/home/launcher/BaseLauncher;
                iget-object v1, p0, Lcom/miui/home/launcher/uioverrides/AllAppsSwipeController;->mFromState:Lcom/miui/home/launcher/LauncherState;
                invoke-static { v0, v1 }, $COMPAT_HELPER->maybeOpenDrawerQuickSearch(Ljava/lang/Object;Ljava/lang/Object;)V
            """.trimIndent(),
        )
    }

    // 12. Eliminate 400ms unlock-animation lag & dead BoostRtHelper call caused by
    //     HyperOsVersionUtils.ATLEAST_MIUI_13 == false on non-Xiaomi Android 12+ devices.
    val unlockSm = mutableClassDefBy("Lcom/miui/home/launcher/common/UnlockAnimationStateMachine;")
    val unlockResume = unlockSm.findMethodOrThrow("onResume", emptyList(), "V")
    val unlockInsns = unlockResume.instructions.toList()
    val miui13Idx = unlockInsns.indexOfFirst { ins ->
        ins.opcode == Opcode.SGET_BOOLEAN &&
            ((ins as? ReferenceInstruction)?.reference as? FieldReference)?.let {
                it.definingClass == "Lcom/miui/home/common/utils/HyperOsVersionUtils;" &&
                    it.name == "ATLEAST_MIUI_13"
            } == true
    }
    check(miui13Idx >= 0) {
        "POCO Launcher: sget-boolean HyperOsVersionUtils.ATLEAST_MIUI_13 não encontrado em UnlockAnimationStateMachine.onResume"
    }
    val miui13Reg = (unlockInsns[miui13Idx] as OneRegisterInstruction).registerA
    unlockResume.replaceInstruction(miui13Idx, "const/4 v$miui13Reg, 0x1")

    // 13. Unlock HyperOS 2/3 proportional rounded widget corners (16dp dense / 20dp standard)
    //     gated on HyperOsVersionUtils.ATLEAST_OS2.
    val cornerEnforce = mutableClassDefBy("Lcom/miui/home/common/widget/RoundedCornerEnforcement;")
    val computeRadius = cornerEnforce.findMethodOrThrow(
        "computeEnforcedRadius",
        listOf("Landroid/content/Context;"),
        "F",
    )
    val cornerInsns = computeRadius.instructions.toList()
    val os2Idx = cornerInsns.indexOfFirst { ins ->
        ins.opcode == Opcode.SGET_BOOLEAN &&
            ((ins as? ReferenceInstruction)?.reference as? FieldReference)?.let {
                it.definingClass == "Lcom/miui/home/common/utils/HyperOsVersionUtils;" &&
                    it.name == "ATLEAST_OS2"
            } == true
    }
    check(os2Idx >= 0) {
        "POCO Launcher: sget-boolean HyperOsVersionUtils.ATLEAST_OS2 não encontrado em RoundedCornerEnforcement.computeEnforcedRadius"
    }
    val os2Reg = (cornerInsns[os2Idx] as OneRegisterInstruction).registerA
    computeRadius.replaceInstruction(os2Idx, "const/4 v$os2Reg, 0x1")
}

private fun patchHideAppsFeature() {
    // 1. AllAppsSettingsFragment.onCreatePreferences: POCO hardcoded mHideApp.setVisible(false)
    //    when deprecating the old drawer vault in favor of Xiaomi Security Center; restore it.
    val allAppsSettings = mutableClassDefBy("Lcom/miui/home/settings/AllAppsSettingsFragment;")
    val onCreatePrefs = allAppsSettings.findMethodOrThrow(
        "onCreatePreferences",
        listOf("Landroid/os/Bundle;", "Ljava/lang/String;"),
        "V",
    )
    val allAppsInsns = onCreatePrefs.instructions.toList()
    val setVisibleIdx = allAppsInsns.indexOfFirst { ins ->
        ins.opcode.isVirtualInvoke() &&
            (ins as? ReferenceInstruction)?.reference
                .let { it as? MethodReference }
                ?.let {
                    it.definingClass == "Landroidx/preference/Preference;" &&
                        it.name == "setVisible" &&
                        it.parameterTypes.map(CharSequence::toString) == listOf("Z")
                } == true
    }
    check(setVisibleIdx > 0 && allAppsInsns[setVisibleIdx - 1].opcode == Opcode.CONST_4) {
        "POCO Launcher: const/4 0x0 antes de Preference.setVisible não encontrado em AllAppsSettingsFragment.onCreatePreferences"
    }
    val visReg = (allAppsInsns[setVisibleIdx - 1] as OneRegisterInstruction).registerA
    onCreatePrefs.replaceInstruction(setVisibleIdx - 1, "const/4 v$visReg, 0x1")

    // 2. HideAppsTransferController.getWhiteListToTransfer: prevent Xiaomi SecurityCenter migration
    //    from disabling hide_apps_switch on fresh installs and migrate legacy v11 states once.
    val transferCtrl = mutableClassDefBy(
        "Lcom/miui/home/launcher/allapps/hideapps/HideAppsTransferController;",
    )
    transferCtrl.findMethodOrThrow(
        "getWhiteListToTransfer",
        listOf("Landroid/content/Context;"),
        "V",
    ).replaceBody(
        locals = 0,
        smali = """
            invoke-static { }, $COMPAT_HELPER->ensureHideAppsMigrationChecked()V
            return-void
        """.trimIndent(),
    )

    // 3. HideAppsLockUtils: ensure one-time upgrade migration runs before first isHideAppsOpen()
    //    read, and replace MIUI-only MiuiSettings$Secure.hasCommonPassword with KeyguardManager.
    val hideAppsLockUtils = mutableClassDefBy(
        "Lcom/miui/home/launcher/allapps/hideapps/HideAppsLockUtils;",
    )
    hideAppsLockUtils.findMethodOrThrow("isHideAppsOpen", emptyList(), "Z").addInstructions(
        0,
        "invoke-static { }, $COMPAT_HELPER->ensureHideAppsMigrationChecked()V",
    )
    val deviceLockSmali = """
        invoke-static { p0 }, $COMPAT_HELPER->isDeviceScreenLockOpen(Landroid/content/Context;)Z
        move-result v0
        return v0
    """.trimIndent()
    hideAppsLockUtils.findMethodOrThrow("isScreenLockOpen", listOf("Landroid/content/Context;"), "Z")
        .replaceBody(locals = 1, smali = deviceLockSmali)
    hideAppsLockUtils.findMethodOrThrow("hasCommonPasswordInMiui", listOf("Landroid/content/Context;"), "Z")
        .replaceBody(locals = 1, smali = deviceLockSmali)

    // 4. LauncherDecoupleHelper.setAppListFilter: attach AlphabeticalAppsList.sHideFilter
    //    unconditionally (AlphabeticalAppsList dynamically checks HideAppsLockUtils.isHideAppsOpen())
    //    so toggling Hide Apps ON works immediately without restarting the launcher.
    val decoupleHelper = mutableClassDefBy("Lcom/miui/home/LauncherDecoupleHelper;")
    decoupleHelper.findMethodOrThrow(
        "setAppListFilter",
        listOf("Lcom/miui/home/launcher/allapps/AlphabeticalAppsList;"),
        "V",
    ).replaceBody(
        locals = 1,
        smali = """
            if-eqz p1, :done
            sget-object v0, Lcom/miui/home/launcher/allapps/AlphabeticalAppsList;->sHideFilter:Ljava/util/function/Predicate;
            invoke-virtual { p1, v0 }, Lcom/miui/home/launcher/allapps/AlphabeticalAppsList;->setHideFilter(Ljava/util/function/Predicate;)V
            :done
            return-void
        """.trimIndent(),
    )

    // 5. FingerprintHelper: replace deprecated FingerprintManager (USE_FINGERPRINT) with modern
    //    public SDK BiometricManager + BiometricPrompt (works seamlessly on One UI & Android 14+).
    val fpHelper = mutableClassDefBy(
        "Lcom/miui/home/launcher/allapps/hideapps/FingerprintHelper;",
    )
    fpHelper.findMethodOrThrow("isHardwareDetectedHideAppsLock", emptyList(), "Z").replaceBody(
        locals = 1,
        smali = """
            iget-object v0, p0, Lcom/miui/home/launcher/allapps/hideapps/FingerprintHelper;->context:Landroid/content/Context;
            invoke-static { v0 }, $COMPAT_HELPER->isBiometricHardwareDetected(Landroid/content/Context;)Z
            move-result v0
            return v0
        """.trimIndent(),
    )
    fpHelper.findMethodOrThrow("hasEnrolledFingerprintsHideAppsLock", emptyList(), "Z").replaceBody(
        locals = 1,
        smali = """
            iget-object v0, p0, Lcom/miui/home/launcher/allapps/hideapps/FingerprintHelper;->context:Landroid/content/Context;
            invoke-static { v0 }, $COMPAT_HELPER->hasEnrolledBiometrics(Landroid/content/Context;)Z
            move-result v0
            return v0
        """.trimIndent(),
    )
    fpHelper.findMethodOrThrow(
        "authenticateHideAppsLock",
        listOf("Lcom/miui/home/launcher/allapps/hideapps/AuthenticationCallbackHideAppsLock;"),
        "V",
    ).replaceBody(
        locals = 0,
        smali = """
            invoke-static { p0, p1 }, $COMPAT_HELPER->authenticateHideAppsBiometric(Ljava/lang/Object;Ljava/lang/Object;)V
            return-void
        """.trimIndent(),
    )
    fpHelper.findMethodOrThrow("cancelFingerprint", emptyList(), "V").replaceBody(
        locals = 0,
        smali = """
            invoke-static { p0 }, $COMPAT_HELPER->cancelHideAppsBiometric(Ljava/lang/Object;)V
            return-void
        """.trimIndent(),
    )

    // 6. HideAppDialogManager: show a single clean native BiometricPrompt instead of stacking
    //    a legacy MIUI dialog, and open pattern setup immediately when user cancels the
    //    fingerprint guide dialog (instead of requiring 10 cancels).
    val hideDialogMgr = mutableClassDefBy(
        "Lcom/miui/home/launcher/allapps/hideapps/HideAppDialogManager;",
    )
    hideDialogMgr.findMethodOrThrow("createFingerprintVerityDialog", emptyList(), "V").replaceBody(
        locals = 0,
        smali = """
            invoke-static { p0 }, $COMPAT_HELPER->startHideAppsFingerprintVerify(Ljava/lang/Object;)V
            return-void
        """.trimIndent(),
    )
    val guideCancel = hideDialogMgr.findMethodOrThrow(
        "lambda\$guideFingerprintDialog\$6",
        listOf("Landroid/view/View;"),
        "V",
    )
    val guideCancelReturns = guideCancel.instructions.withIndex()
        .filter { it.value.opcode == Opcode.RETURN_VOID }
        .map { it.index }
        .asReversed()
    check(guideCancelReturns.isNotEmpty()) {
        "POCO Launcher: return-void não encontrado em HideAppDialogManager.lambda\$guideFingerprintDialog\$6"
    }
    for (retIdx in guideCancelReturns) {
        guideCancel.addInstructionsAtControlFlowLabel(
            retIdx,
            "invoke-static { }, $COMPAT_HELPER->openHideAppsPatternSetupIfUnset()V",
        )
    }

    // 7. HideAppsUnlockContainerView: make the fingerprint icon re-open BiometricPrompt on tap,
    //    apply Dark/Light UI colors immediately, and replace Xiaomi Account / email password
    //    reset with native device screen-lock auth.
    val unlockView = mutableClassDefBy(
        "Lcom/miui/home/launcher/allapps/hideapps/HideAppsUnlockContainerView;",
    )
    val onFinishInflate = unlockView.findMethodOrThrow("onFinishInflate", emptyList(), "V")
    val inflateReturns = onFinishInflate.instructions.withIndex()
        .filter { it.value.opcode == Opcode.RETURN_VOID }
        .map { it.index }
        .asReversed()
    check(inflateReturns.isNotEmpty()) {
        "POCO Launcher: return-void não encontrado em HideAppsUnlockContainerView.onFinishInflate"
    }
    for (retIdx in inflateReturns) {
        onFinishInflate.addInstructionsAtControlFlowLabel(
            retIdx,
            "invoke-static { p0 }, $COMPAT_HELPER->wireHideAppsUnlockView(Ljava/lang/Object;)V",
        )
    }
    unlockView.findMethodOrThrow("onForgetPasswordBtnClick", emptyList(), "V").replaceBody(
        locals = 0,
        smali = """
            invoke-static { p0 }, $COMPAT_HELPER->handleHideAppsForgetPassword(Ljava/lang/Object;)V
            return-void
        """.trimIndent(),
    )

    // 8. HideAppsViewPlaceHolderSheet: fix guaranteed InflateException(NullPointerException) in
    //    the 2-arg XML constructor (which called findViewById before children were inflated), and
    //    bind hideAppsViewPlaceHolder safely on handleOpen/setInsets/handleClose.
    val placeHolderSheet = mutableClassDefBy(
        "Lcom/miui/home/launcher/allapps/hideapps/HideAppsViewPlaceHolderSheet;",
    )
    placeHolderSheet.findMethodOrThrow(
        "<init>",
        listOf("Landroid/content/Context;", "Landroid/util/AttributeSet;"),
        "V",
    ).replaceBody(
        locals = 1,
        smali = """
            const/4 v0, 0x0
            invoke-direct { p0, p1, p2, v0 }, Lcom/miui/home/launcher/allapps/hideapps/HideAppsViewPlaceHolderSheet;-><init>(Landroid/content/Context;Landroid/util/AttributeSet;I)V
            return-void
        """.trimIndent(),
    )
    val bindSheetSmali =
        "invoke-static { p0 }, $COMPAT_HELPER->bindHideAppsPlaceHolderSheet(Landroid/view/View;)V"
    placeHolderSheet.findMethodOrThrow("handleOpen", emptyList(), "V")
        .addInstructions(0, bindSheetSmali)
    placeHolderSheet.findMethodOrThrow("setInsets", listOf("Landroid/graphics/Rect;"), "V")
        .addInstructions(0, bindSheetSmali)
    placeHolderSheet.findMethodOrThrow("handleClose", listOf("Z"), "V")
        .addInstructions(0, bindSheetSmali)

    // 9. HideAppsViewPlaceHolder.hide(): fix stock bug where uncleared mEnterAnim caused hide()
    //    to skip mExitAnim and mCurrentView.onExit(), so onPasswordSetSuccess() was NEVER invoked
    //    and the user remained stuck on the "Set password" screen after confirming their pattern.
    val placeHolder = mutableClassDefBy(
        "Lcom/miui/home/launcher/allapps/hideapps/HideAppsViewPlaceHolder;",
    )
    placeHolder.findMethodOrThrow("hide", emptyList(), "V").replaceBody(
        locals = 0,
        smali = """
            invoke-static { p0 }, $COMPAT_HELPER->closeHideAppsViewPlaceHolder(Landroid/view/View;)V
            return-void
        """.trimIndent(),
    )

    // 10. HideAppsSetPasswordContainerView.onFinishInflate(): fix invisible black-on-dark-gray
    //     bottom action buttons & light pattern dots in Dark Mode.
    val setPwdView = mutableClassDefBy(
        "Lcom/miui/home/launcher/allapps/hideapps/HideAppsSetPasswordContainerView;",
    )
    val setPwdInflate = setPwdView.findMethodOrThrow("onFinishInflate", emptyList(), "V")
    val setPwdReturns = setPwdInflate.instructions.withIndex()
        .filter { it.value.opcode == Opcode.RETURN_VOID }
        .map { it.index }
        .asReversed()
    check(setPwdReturns.isNotEmpty()) {
        "POCO Launcher: return-void não encontrado em HideAppsSetPasswordContainerView.onFinishInflate"
    }
    for (retIdx in setPwdReturns) {
        setPwdInflate.addInstructionsAtControlFlowLabel(
            retIdx,
            "invoke-static { p0 }, $COMPAT_HELPER->styleHideAppsSetPasswordButtons(Landroid/view/View;)V",
        )
    }

    // 11. HideAppsPasswordGuideView & HideAppsContainerView: handle tapping "Set password" cleanly
    //     if a pattern already exists, and ensure HideAppsSelectView/ContentView always sync apps
    //     from AllAppsStore on first enable without needing a launcher restart.
    val pwdGuideView = mutableClassDefBy(
        "Lcom/miui/home/launcher/allapps/hideapps/HideAppsPasswordGuideView;",
    )
    pwdGuideView.findMethodOrThrow(
        "lambda\$onFinishInflate\$0",
        listOf("Landroid/view/View;"),
        "V",
    ).replaceBody(
        locals = 0,
        smali = """
            invoke-static { p0 }, $COMPAT_HELPER->onHideAppsPasswordGuideClick(Landroid/view/View;)V
            return-void
        """.trimIndent(),
    )

    val hideContainerView = mutableClassDefBy(
        "Lcom/miui/home/launcher/allapps/hideapps/HideAppsContainerView;",
    )
    hideContainerView.findMethodOrThrow("changeToContentContainerView", emptyList(), "V")
        .addInstructions(
            0,
            "invoke-static { p0 }, $COMPAT_HELPER->ensureHideAppsListSynced(Landroid/view/View;)V",
        )

    // 12. HideAppsGridAdapter: FIX EXACT "Add app" CRASH!
    //     Stock onCreateViewHolder inflated obsolete R$layout.application_all_hide_app which still
    //     nested child views inside <AppIcon> (now a flat TextView, NOT a ViewGroup -> guaranteed
    //     ClassCastException: AppIcon cannot be cast to ViewGroup!). Delegate to super which inflates
    //     R$layout.single_all_apps_application. In onBindViewHolder, toggle Canvas-drawn checkbox via
    //     quickHideOrShowCheckbox(isDefaultCheckBoxShow()) so checkboxes appear in "Add app" even when
    //     "Lock Home screen layout" is enabled AND never leak onto Page 0 (the Hidden Apps vault).
    val hideGridAdapter = mutableClassDefBy(
        "Lcom/miui/home/launcher/allapps/hideapps/HideAppsGridAdapter;",
    )
    hideGridAdapter.findMethodOrThrow(
        "onCreateViewHolder",
        listOf("Landroid/view/ViewGroup;", "I"),
        "Lcom/miui/home/launcher/allapps/AllAppsGridAdapter\$ViewHolder;",
    ).replaceBody(
        locals = 1,
        smali = """
            invoke-super { p0, p1, p2 }, Lcom/miui/home/launcher/allapps/AllAppsGridAdapter;->onCreateViewHolder(Landroid/view/ViewGroup;I)Lcom/miui/home/launcher/allapps/AllAppsGridAdapter${'$'}ViewHolder;
            move-result-object v0
            return-object v0
        """.trimIndent(),
    )
    hideGridAdapter.findMethodOrThrow(
        "onBindViewHolder",
        listOf("Lcom/miui/home/launcher/allapps/AllAppsGridAdapter\$ViewHolder;", "I"),
        "V",
    ).replaceBody(
        locals = 4,
        smali = """
            invoke-super { p0, p1, p2 }, Lcom/miui/home/launcher/allapps/AllAppsGridAdapter;->onBindViewHolder(Lcom/miui/home/launcher/allapps/AllAppsGridAdapter${'$'}ViewHolder;I)V
            invoke-virtual { p1 }, Landroidx/recyclerview/widget/RecyclerView${'$'}ViewHolder;->getItemViewType()I
            move-result v0
            const/4 v1, 0x2
            if-ne v0, v1, :done
            iget-object v0, p1, Landroidx/recyclerview/widget/RecyclerView${'$'}ViewHolder;->itemView:Landroid/view/View;
            instance-of v1, v0, Lcom/miui/home/launcher/ShortcutIcon;
            if-eqz v1, :done
            check-cast v0, Lcom/miui/home/launcher/ShortcutIcon;
            invoke-virtual { p0 }, Lcom/miui/home/launcher/allapps/hideapps/HideAppsGridAdapter;->isDefaultCheckBoxShow()Z
            move-result v1
            invoke-virtual { v0, v1 }, Lcom/miui/home/launcher/ShortcutIcon;->quickHideOrShowCheckbox(Z)V
            const/4 v2, 0x0
            if-eqz v1, :apply_check
            invoke-virtual { p0, p2 }, Lcom/miui/home/launcher/allapps/AllAppsGridAdapter;->getItem(I)Lcom/miui/home/launcher/allapps/BaseAlphabeticalAppsList${'$'}AdapterItem;
            move-result-object v3
            if-eqz v3, :apply_check
            iget-object v3, v3, Lcom/miui/home/launcher/allapps/BaseAlphabeticalAppsList${'$'}AdapterItem;->appInfo:Lcom/miui/home/launcher/AppInfo;
            if-eqz v3, :apply_check
            invoke-virtual { v3 }, Lcom/miui/home/launcher/AppInfo;->isChecked()Z
            move-result v2
            :apply_check
            invoke-virtual { v0, v2 }, Lcom/miui/home/launcher/ShortcutIcon;->checkCheckBox(Z)V
            :done
            return-void
        """.trimIndent(),
    )

    // 13. AllAppsNormalContainerView.updateColorForUiMode: always match HideAppsContentBaseView's
    //     solid system Dark/Light background (AllAppsSheet.COLOR_MODE, 255) instead of drawer theme.
    val hideNormalContainer = mutableClassDefBy(
        "Lcom/miui/home/launcher/allapps/hideapps/AllAppsNormalContainerView;",
    )
    hideNormalContainer.findMethodOrThrow("updateColorForUiMode", emptyList(), "V").replaceBody(
        locals = 3,
        smali = """
            sget-object v0, Lcom/miui/home/launcher/allapps/AllAppsSheet;->COLOR_MODE:Lcom/miui/home/launcher/allapps/AllAppsColorMode;
            const/16 v1, 0xff
            iget-object v2, p0, Lcom/miui/home/launcher/allapps/hideapps/AllAppsNormalContainerView;->mAdapter:Lcom/miui/home/launcher/allapps/hideapps/HideAppsGridAdapter;
            if-eqz v2, :skip_adapter
            invoke-virtual { v2, v0, v1 }, Lcom/miui/home/launcher/allapps/AllAppsGridAdapter;->updateStyle(Lcom/miui/home/launcher/allapps/AllAppsColorMode;I)V
            :skip_adapter
            iget-object v2, p0, Lcom/miui/home/launcher/allapps/hideapps/AllAppsNormalContainerView;->mAllAppsRecyclerView:Lcom/miui/home/launcher/allapps/hideapps/HideAppsRecyclerView;
            if-eqz v2, :done
            invoke-virtual { v2, v0, v1 }, Lcom/miui/home/launcher/allapps/AllAppsRecyclerView;->onColorModeChange(Lcom/miui/home/launcher/allapps/AllAppsColorMode;I)V
            :done
            return-void
        """.trimIndent(),
    )

    // 14. BaseLauncher.removeAppWidget(HashSet) & HideAppsSelectView.onShow(): guard NPEs on
    //     broken/placeholder widgets when hiding an app and on null SearchBarContainer.
    val baseLauncher = mutableClassDefBy("Lcom/miui/home/launcher/BaseLauncher;")
    baseLauncher.findMethodOrThrow("removeAppWidget", listOf("Ljava/util/HashSet;"), "V").replaceBody(
        locals = 0,
        smali = """
            invoke-static { p0, p1 }, $COMPAT_HELPER->safeRemoveAppWidgetsByPackages(Ljava/lang/Object;Ljava/util/HashSet;)V
            return-void
        """.trimIndent(),
    )

    val hideSelectView = mutableClassDefBy(
        "Lcom/miui/home/launcher/allapps/hideapps/HideAppsSelectView;",
    )
    hideSelectView.findMethodOrThrow("onShow", emptyList(), "V").replaceBody(
        locals = 0,
        smali = """
            invoke-static { }, $COMPAT_HELPER->safeShowHideAppsSearchBar()V
            invoke-direct { p0 }, Lcom/miui/home/launcher/allapps/hideapps/HideAppsSelectView;->refreshData()V
            return-void
        """.trimIndent(),
    )
}

private fun patchWallpaperIntegration() {
    // 1. DesktopWallpaperManager.<init>: force SdkVersion.ATLEAST_T branch to register standard
    //    ACTION_WALLPAPER_CHANGED receiver instead of binding absent com.miui.miwallpaper.
    val dwmInit = DesktopWallpaperManagerInitFingerprint.method
    val dwmInsns = dwmInit.instructions.toList()
    val sgetIndex = dwmInsns.indexOfFirst { ins ->
        ins.opcode == Opcode.SGET_BOOLEAN &&
            (ins as? ReferenceInstruction)?.reference
                .let { it as? FieldReference }
                ?.let { it.definingClass == "Lcom/miui/launcher/utils/SdkVersion;" && it.name == "ATLEAST_T" } == true
    }
    check(sgetIndex >= 0) {
        "POCO Launcher: sget-boolean SdkVersion.ATLEAST_T não encontrado em DesktopWallpaperManager.<init>"
    }
    val reg = (dwmInsns[sgetIndex] as OneRegisterInstruction).registerA
    dwmInit.replaceInstruction(sgetIndex, "const/4 v$reg, 0x0")

    // 2. WallpaperManagerCompatVT.isMiuiWallpaperComponentUsing -> false when MIUI wallpaper absent.
    WallpaperManagerCompatVTIsMiuiUsingFingerprint.method.replaceBody(
        locals = 1,
        smali = """
            const/4 v0, 0x0
            return v0
        """.trimIndent(),
    )

    // 3. EditingEntryThumbnailView.jumpThemeWallpaper -> try Xiaomi Themes, fall back to Android wallpaper picker.
    EditingEntryJumpThemeWallpaperFingerprint.method.replaceBody(
        locals = 1,
        smali = """
            invoke-virtual { p0 }, Landroid/view/View;->getContext()Landroid/content/Context;
            move-result-object v0
            invoke-static { v0, p1, p2, p3 }, $COMPAT_HELPER->openThemeOrSystemWallpaperPicker(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V
            return-void
        """.trimIndent(),
    )

    // 4. WallpaperShortcutActivity.onCreate_aroundBody0 -> open Android wallpaper picker on ActivityNotFoundException.
    val wallpaperShortcut = WallpaperShortcutOnCreateAroundFingerprint.method
    val wsInsns = wallpaperShortcut.instructions.toList()
    val printStackIndex = wsInsns.indexOfFirst { ins ->
        ins.opcode == Opcode.INVOKE_VIRTUAL &&
            (ins as? ReferenceInstruction)?.reference
                .let { it as? MethodReference }
                ?.let { it.name == "printStackTrace" } == true
    }
    check(printStackIndex >= 0) {
        "POCO Launcher: printStackTrace não encontrado em WallpaperShortcutActivity.onCreate_aroundBody0"
    }
    wallpaperShortcut.replaceInstruction(
        printStackIndex,
        "invoke-static { p0 }, $COMPAT_HELPER->openWallpaperPickerFallback(Landroid/content/Context;)V",
    )
}

// ==================== Bytecode Helpers ====================

private fun MutableClass.findMethodOrThrow(
    name: String,
    params: List<String>,
    returnType: String,
): MutableMethod = methods.firstOrNull {
    it.name == name && it.returnType == returnType && it.parameterTypes.map(CharSequence::toString) == params
} ?: throw PatchException("POCO Launcher: método $type->$name(${params.joinToString("")})$returnType não encontrado")

private fun Method.paramRegCount(): Int {
    var count = if (AccessFlags.STATIC.isSet(accessFlags)) 0 else 1
    for (type in parameterTypes) {
        count += if (type == "J" || type == "D") 2 else 1
    }
    return count
}

private fun MutableMethod.replaceBody(locals: Int, smali: String) {
    val clazz = mutableClassDefBy(definingClass)
    val registerCount = paramRegCount() + locals
    val replacement = ImmutableMethod(
        definingClass,
        name,
        parameters,
        returnType,
        accessFlags,
        annotations,
        hiddenApiRestrictions,
        ImmutableMethodImplementation(
            registerCount,
            listOf(ImmutableInstruction10x(Opcode.NOP)),
            emptyList(),
            emptyList(),
        ),
    ).toMutable()
    replacement.addInstructions(0, smali)
    replacement.removeInstruction(replacement.instructions.lastIndex)
    check(clazz.methods.remove(this)) {
        "POCO Launcher: falha ao substituir corpo de $definingClass->$name"
    }
    clazz.methods.add(replacement)
}

private fun rewriteMethodCalls(
    method: MutableMethod,
    transform: (Instruction, MethodReference) -> Pair<Opcode?, MethodReference>?,
): Int {
    val impl = method.implementation ?: return 0
    val insns = impl.instructions.toList()
    var count = 0
    for (i in insns.indices) {
        val ins = insns[i]
        val ref = (ins as? ReferenceInstruction)?.reference as? MethodReference ?: continue
        val mapped = transform(ins, ref) ?: continue
        val (newBaseOpcode, newRef) = mapped
        val replacement: BuilderInstruction = when (ins) {
            is FiveRegisterInstruction -> {
                val targetOp = newBaseOpcode ?: ins.opcode
                BuilderInstruction35c(
                    targetOp,
                    ins.registerCount,
                    ins.registerC,
                    ins.registerD,
                    ins.registerE,
                    ins.registerF,
                    ins.registerG,
                    newRef,
                )
            }

            is RegisterRangeInstruction -> {
                val targetOp = when (newBaseOpcode) {
                    Opcode.INVOKE_STATIC -> Opcode.INVOKE_STATIC_RANGE
                    Opcode.INVOKE_VIRTUAL -> Opcode.INVOKE_VIRTUAL_RANGE
                    Opcode.INVOKE_SUPER -> Opcode.INVOKE_SUPER_RANGE
                    Opcode.INVOKE_DIRECT -> Opcode.INVOKE_DIRECT_RANGE
                    Opcode.INVOKE_INTERFACE -> Opcode.INVOKE_INTERFACE_RANGE
                    null -> ins.opcode
                    else -> newBaseOpcode
                }
                BuilderInstruction3rc(
                    targetOp,
                    ins.startRegister,
                    ins.registerCount,
                    newRef,
                )
            }

            else -> throw PatchException("Instrução invoke inesperada: ${ins.opcode} em ${method.definingClass}->${method.name}")
        }
        impl.replaceInstruction(i, replacement)
        count++
    }
    return count
}

private fun Opcode.isStaticInvoke() =
    this == Opcode.INVOKE_STATIC || this == Opcode.INVOKE_STATIC_RANGE

private fun Opcode.isVirtualInvoke() =
    this == Opcode.INVOKE_VIRTUAL || this == Opcode.INVOKE_VIRTUAL_RANGE

private fun Opcode.isSuperInvoke() =
    this == Opcode.INVOKE_SUPER || this == Opcode.INVOKE_SUPER_RANGE
