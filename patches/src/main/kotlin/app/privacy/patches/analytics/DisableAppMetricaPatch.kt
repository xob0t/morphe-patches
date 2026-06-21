package app.privacy.patches.analytics

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element
import app.shared.*

private val disableAppMetricaManifestPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { document ->
            val application = document.documentElement.childrenNamed("application").single() as Element

            val appMetricaComponents = application.childrenNamed("activity", "provider", "service", "receiver")
                .filter { component ->
                    val name = component.getAttribute("android:name")
                    name.startsWith("io.appmetrica.analytics.") ||
                        name.startsWith("com.yandex.metrica.") ||
                        name.startsWith("com.yandex.preinstallsatellite.appmetrica.")
                }

            application.removeChildren(appMetricaComponents)

            val disabledComponents = application.disableComponentsWhere { name ->
                name.startsWith("io.appmetrica.analytics.") ||
                    name.startsWith("com.yandex.metrica.") ||
                    name.startsWith("com.yandex.preinstallsatellite.appmetrica.")
            }

            application.setApplicationMetaData("io.appmetrica.analytics.auto_tracking_enabled", "false")
            application.setApplicationMetaData("io.appmetrica.analytics.location_tracking_enabled", "false")

            println(
                "Disable AppMetrica: removed ${appMetricaComponents.size} and disabled " +
                    "$disabledComponents manifest components.",
            )
        }
    }
}

@Suppress("unused")
val disableAppMetricaPatch = bytecodePatch(
    name = "Disable AppMetrica",
    description = "Disables AppMetrica and legacy Yandex Metrica SDK entry points.",
    default = false,
) {
    dependsOn(disableAppMetricaManifestPatch)

    execute {
        var patchedMethods = 0

        listOf(
            "Lcom/yandex/metrica/YandexMetrica;",
            "Lcom/yandex/metrica/AppMetricaJsInterface;",
            "Lcom/yandex/metrica/AppMetricaInitializerJsInterface;",
        ).forEach { classType ->
            val classDef = mutableClassDefByOrNull(classType) ?: return@forEach

            classDef.methods
                .filter { method ->
                    method.name != "<init>" &&
                        method.returnType == "V" &&
                        method.implementation != null
                }
                .forEach { method ->
                    method.addInstructions(0, "return-void")
                    patchedMethods++
                }
        }

        mutableClassDefByOrNull("Lcom/yandex/metrica/impl/ob/U1;")?.methods?.forEach { method ->
            when {
                method.name in setOf("reportData", "sendCrash") &&
                    method.returnType == "V" &&
                    method.implementation != null -> {
                    method.addInstructions(0, "return-void")
                    patchedMethods++
                }

                method.name in setOf("queuePauseUserSession", "queueReport", "queueResumeUserSession") &&
                    method.returnType == "Ljava/util/concurrent/Future;" &&
                    method.implementation != null -> {
                    method.addInstructions(
                        0,
                        """
                            const/4 p0, 0x0
                            invoke-static {p0}, Ljava/util/concurrent/CompletableFuture;->completedFuture(Ljava/lang/Object;)Ljava/util/concurrent/CompletableFuture;
                            move-result-object p0
                            return-object p0
                        """,
                    )
                    patchedMethods++
                }
            }
        }

        mutableClassDefByOrNull("Lcom/yandex/metrica/impl/ob/U1\$g;")?.methods
            ?.filter { method ->
                method.name == "call" &&
                    method.returnType == "Ljava/lang/Void;" &&
                    method.implementation != null
            }
            ?.forEach { method ->
                method.addInstructions(
                    0,
                    """
                        const/4 p0, 0x0
                        return-object p0
                    """,
                )
                patchedMethods++
            }

        println("Disable AppMetrica: patched $patchedMethods SDK entry point methods.")
    }
}
