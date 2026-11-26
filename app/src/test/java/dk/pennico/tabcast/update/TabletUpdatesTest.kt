// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.update

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dk.pennico.tabcast.BuildConfig
import dk.pennico.tabcast.ui.SettingsActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 0.5.4.1: the tablet app's own updates, in Settings. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TabletUpdatesTest {
    private fun texts(v: View): List<TextView> =
        if (v is ViewGroup) (0 until v.childCount).flatMap { texts(v.getChildAt(it)) } else listOfNotNull(v as? TextView)

    @Test fun noAutomaticCheckUnderTests() {
        assertFalse("the daily check would go to the network from the tests", TabletUpdates.autoCheck)
    }

    @Test fun settingsShowUpdatesWithTheVersionAndACheckButton() {
        val a = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val all = texts(a.window.decorView)
        assertNotNull("an Updates header", all.firstOrNull { it.text.toString() == "Updates" })
        assertTrue("the installed version", all.any { it.text.toString().startsWith("Tab Cast ${BuildConfig.VERSION_NAME}.") })
        val btn = all.firstOrNull { it.text.toString() == "Check for updates" }
        assertNotNull("a Check for updates button", btn)
        assertTrue(btn!!.isEnabled)
    }

    @Test fun theNotificationOpensUpdates() {
        val i = android.content.Intent(org.robolectric.RuntimeEnvironment.getApplication(), SettingsActivity::class.java)
            .putExtra(SettingsActivity.EXTRA_UPDATES, true)
        val a = Robolectric.buildActivity(SettingsActivity::class.java, i).setup().get()
        assertTrue(texts(a.window.decorView).any { it.text.toString() == "Updates" })
    }
}
