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
 * First Car App Library prototype.
 *
 * It intentionally uses numbered cells instead of real stations so we can
 * measure the host's native SMALL grid density before migrating Twinsen's
 * complete browse tree and playback controls.
 */
class TwinsenCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator =
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(sessionInfo: SessionInfo): Session =
        TwinsenCarSession(sessionInfo)
}

private class TwinsenCarSession(
    private val sessionInfo: SessionInfo
) : Session() {

    override fun onCreateScreen(intent: Intent): Screen {
        val apiLevel = carContext.carAppApiLevel
        val host = carContext.hostInfo
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

        return SmallGridProbeScreen(carContext)
    }
}

private class SmallGridProbeScreen(
    carContext: CarContext
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val apiLevel = carContext.carAppApiLevel
        val hostPackage = carContext.hostInfo?.packageName

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
