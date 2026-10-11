package net.mspanc.twinsenradio.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridSection
import androidx.car.app.model.Header
import androidx.car.app.model.SectionedItemTemplate
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.core.graphics.drawable.IconCompat
import net.mspanc.twinsenradio.R
import net.mspanc.twinsenradio.playback.VehicleDiagnosticManager

/**
 * Car App Library density probe.
 *
 * The screen deliberately uses numbered cells so we can first prove that the
 * host binds the CarAppService and measure its SMALL grid density.
 */
class TwinsenCarAppService : CarAppService() {

    override fun onCreate() {
        super.onCreate()
        VehicleDiagnosticManager.recordCarAppLifecycle("service_onCreate")
    }

    override fun createHostValidator(): HostValidator {
        VehicleDiagnosticManager.recordCarAppLifecycle(
            "createHostValidator",
            getHostInfo()?.packageName
        )
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    /**
     * Kept as a diagnostic fallback. A media host supporting Car API 8 should
     * call the SessionInfo overload below, but logging both paths tells us
     * exactly which protocol the installed Android Auto host is using.
     */
    override fun onCreateSession(): Session {
        VehicleDiagnosticManager.recordCarAppLifecycle(
            "onCreateSession_legacy",
            getHostInfo()?.packageName
        )
        return TwinsenCarSession(null)
    }

    override fun onCreateSession(sessionInfo: SessionInfo): Session {
        VehicleDiagnosticManager.recordCarAppLifecycle(
            "onCreateSession_sessionInfo",
            getHostInfo()?.packageName,
            linkedMapOf(
                "sessionId" to sessionInfo.sessionId,
                "displayTypeRaw" to sessionInfo.displayType.toString()
            )
        )
        return TwinsenCarSession(sessionInfo)
    }
}

private class TwinsenCarSession(
    private val sessionInfo: SessionInfo?
) : Session() {

    override fun onCreateScreen(intent: Intent): Screen {
        val apiLevel = carContext.carAppApiLevel
        val host = carContext.hostInfo

        if (sessionInfo == null) {
            VehicleDiagnosticManager.recordCarAppSession(
                hostPackageName = host?.packageName,
                hostUid = host?.uid,
                carAppApiLevel = apiLevel,
                sessionId = "legacy-main",
                displayType = "MAIN_LEGACY",
                supportedTemplates = null
            )
        } else {
            val displayType = when (sessionInfo.displayType) {
                SessionInfo.DISPLAY_TYPE_CLUSTER -> "CLUSTER"
                SessionInfo.DISPLAY_TYPE_MAIN -> "MAIN"
                else -> "UNKNOWN(${sessionInfo.displayType})"
            }
            val templates = sessionInfo
                .getSupportedTemplates(apiLevel)
                ?.map { it.simpleName }

            VehicleDiagnosticManager.recordCarAppSession(
                hostPackageName = host?.packageName,
                hostUid = host?.uid,
                carAppApiLevel = apiLevel,
                sessionId = sessionInfo.sessionId,
                displayType = displayType,
                supportedTemplates = templates
            )
        }

        VehicleDiagnosticManager.recordCarAppLifecycle(
            "onCreateScreen",
            host?.packageName,
            linkedMapOf(
                "intentAction" to (intent.action ?: "(null)"),
                "carAppApiLevel" to apiLevel.toString()
            )
        )

        return SmallGridProbeScreen(carContext)
    }
}

private class SmallGridProbeScreen(
    carContext: CarContext
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val apiLevel = carContext.carAppApiLevel
        val hostPackage = carContext.hostInfo?.packageName

        VehicleDiagnosticManager.recordCarAppLifecycle(
            "onGetTemplate",
            hostPackage,
            linkedMapOf("carAppApiLevel" to apiLevel.toString())
        )

        val icon = CarIcon.Builder(
            IconCompat.createWithResource(carContext, R.drawable.ic_radio)
        ).build()

        val section = GridSection.Builder()
            .setTitle("30 cases · taille SMALL")
            .setItemSize(GridSection.ITEM_SIZE_SMALL)
            .setOnItemVisibilityChangedListener { startIndex, endIndexExclusive ->
                VehicleDiagnosticManager.recordCarAppGridVisibility(
                    itemSize = "SMALL",
                    startIndex = startIndex,
                    endIndexExclusive = endIndexExclusive,
                    carApiLevel = apiLevel,
                    hostPackage = hostPackage
                )
            }

        repeat(30) { index ->
            section.addItem(
                GridItem.Builder()
                    .setTitle((index + 1).toString())
                    .setImage(icon)
                    .build()
            )
        }

        return SectionedItemTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle("Twinsen · grille CarApp SMALL")
                    .build()
            )
            .addSection(section.build())
            .build()
    }
}
