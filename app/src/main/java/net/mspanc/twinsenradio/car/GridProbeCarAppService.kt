package net.mspanc.twinsenradio.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
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
 * Experimental Car App Library surface used only to measure the real grid density
 * granted by the Android Auto host.
 */
class GridProbeCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator =
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen =
            SmallGridProbeScreen(carContext)
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
                    .setTitle("Twinsen · mesure grille SMALL")
                    .build()
            )
            .addSection(section.build())
            .build()
    }
}
