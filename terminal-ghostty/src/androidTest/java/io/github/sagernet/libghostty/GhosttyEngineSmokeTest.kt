package io.github.sagernet.libghostty

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.KeyEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** End-to-end proof of the vendored engine: bytes in, pixels out, keys out. */
@RunWith(AndroidJUnit4::class)
class GhosttyEngineSmokeTest {

    @Test
    fun viewNeverPaintsOutsideItsBounds() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        lateinit var view: GhosttyTerminalView
        instrumentation.runOnMainSync {
            val session = GhosttyTerminalSession(
                context,
                GhosttyTerminalSession.Options(columns = 20, rows = 4),
            )
            view = GhosttyTerminalView(context)
            view.session = session
            view.measure(
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, 400, 200)
            session.feedOutput("hello".toByteArray())
        }
        instrumentation.waitForIdleSync()

        val bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate(0f, 100f)
        instrumentation.runOnMainSync { view.draw(canvas) }
        // Compose's interop handler does not clip children; the view must clip
        // itself or its background paints over the toolbar above (regression).
        assertEquals(0, bitmap.getPixel(10, 20))
        assertEquals(0, bitmap.getPixel(200, 50))
    }

    @Test
    fun cannedOutputRendersAndKeysReachTheTransport() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val sent = mutableListOf<ByteArray>()
        lateinit var session: GhosttyTerminalSession
        lateinit var view: GhosttyTerminalView

        instrumentation.runOnMainSync {
            session = GhosttyTerminalSession(
                context,
                GhosttyTerminalSession.Options(columns = 40, rows = 8),
            )
            session.transport = object : GhosttyTerminalSession.Transport {
                override fun sendInput(data: ByteArray) {
                    sent += data
                }
                override fun sendResize(columns: Int, rows: Int, widthPixels: Int, heightPixels: Int) = Unit
                override fun close() = Unit
            }
            view = GhosttyTerminalView(context)
            view.session = session
            view.measure(
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, 800, 400)
            session.feedOutput(
                "hello \u001b[1;32mghostty\u001b[0m\r\nsecond line\r\n".toByteArray(),
            )
        }
        instrumentation.waitForIdleSync()

        val screen = session.screenText().orEmpty()
        assertTrue("screen was: $screen", screen.contains("hello ghostty"))

        var ink = 0
        val bitmap = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888)
        instrumentation.runOnMainSync {
            view.draw(Canvas(bitmap))
        }
        for (x in 0 until 800 step 2) {
            for (y in 0 until 400 step 2) {
                if (bitmap.getPixel(x, y) != bitmap.getPixel(0, 0)) ink++
            }
        }
        assertTrue("no pixels drawn", ink > 50)

        instrumentation.runOnMainSync {
            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A))
        }
        assertEquals("key produced no transport bytes", 1, sent.size)
        session.close()
    }
}
