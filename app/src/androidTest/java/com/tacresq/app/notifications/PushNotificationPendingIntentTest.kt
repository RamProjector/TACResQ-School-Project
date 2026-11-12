package com.tacresq.app.notifications

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tacresq.app.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PushNotificationPendingIntentTest {
    @Test
    fun notificationDataUriSeparatesPendingIntentsEvenWithSameRequestCode() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val first = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).setData(Uri.parse("tacresq://notification/inbox-101")),
            flags,
        )
        val second = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).setData(Uri.parse("tacresq://notification/inbox-102")),
            flags,
        )
        val firstAgain = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).setData(Uri.parse("tacresq://notification/inbox-101"))
                .putExtra("event_type", "updated"),
            flags,
        )

        assertNotEquals(first, second)
        assertEquals(first, firstAgain)

        first.cancel()
        second.cancel()
    }
}
