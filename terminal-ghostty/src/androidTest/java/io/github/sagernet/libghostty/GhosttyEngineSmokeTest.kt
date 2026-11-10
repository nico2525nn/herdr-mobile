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
    fun alternateScrollModeIsReported() {
        val handle = GhosttyVt.nativeCreate(20, 6, 1000, "x", null, 1, true, null)
        assertTrue(handle != 0L)
        val enterAlt = "\u001b[?1049h\u001b[?1007h".toByteArray()
        GhosttyVt.nativeWrite(handle, enterAlt, 0, enterAlt.size)
        val enabled = GhosttyVt.nativeViewportState(handle)
        assertTrue("alt screen bit", enabled and GhosttyVt.VIEWPORT_ALTERNATE_SCREEN != 0)
        assertTrue("alternate scroll bit", enabled and GhosttyVt.VIEWPORT_ALTERNATE_SCROLL != 0)
        val disable = "\u001b[?1007l".toByteArray()
        GhosttyVt.nativeWrite(handle, disable, 0, disable.size)
        val disabled = GhosttyVt.nativeViewportState(handle)
        assertTrue("alternate scroll off", disabled and GhosttyVt.VIEWPORT_ALTERNATE_SCROLL == 0)
        GhosttyVt.nativeFree(handle)
    }

    @Test
    fun kittyPlacementsExistNatively() {
        val handle = GhosttyVt.nativeCreate(20, 6, 1000, "x", null, 1, true, null)
        assertTrue(handle != 0L)
        GhosttyVt.nativeResize(handle, 20, 6, 19, 38)
        val kitty = byteArrayOf(0x1b) +
            "_Ga=T,f=32,s=2,v=2,c=2,r=1,i=7;/wAA/wD/AP8AAP///////w==".toByteArray() +
            byteArrayOf(0x1b, 0x5c)
        GhosttyVt.nativeWrite(handle, kitty, 0, kitty.size)
        val placements = GhosttyVt.nativeKittyPlacements(handle)
        GhosttyVt.nativeFree(handle)
        assertTrue("placements=${placements?.size ?: -1}", (placements?.size ?: 0) > 0)
    }

    @Test
    fun kittyImagesAreDecodedAndPlaced() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        lateinit var session: GhosttyTerminalSession
        lateinit var view: GhosttyTerminalView
        instrumentation.runOnMainSync {
            session = GhosttyTerminalSession(
                context,
                GhosttyTerminalSession.Options(columns = 20, rows = 6),
            )
            view = GhosttyTerminalView(context)
            view.session = session
            view.measure(
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, 600, 300)
            val kitty = byteArrayOf(0x1b) +
                "_Ga=T,f=32,s=2,v=2,c=2,r=1,i=7;/wAA/wD/AP8AAP///////w==".toByteArray() +
                byteArrayOf(0x1b, 0x5c)
            session.feedOutput(kitty)
        }
        Thread.sleep(250)
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { view.refreshKittyForTest() }
        val drawn = Bitmap.createBitmap(600, 300, Bitmap.Config.ARGB_8888)
        instrumentation.runOnMainSync { view.draw(Canvas(drawn)) }
        assertTrue(
            "placement count=${view.kittyPlacementCount}",
            view.kittyPlacementCount > 0,
        )
        session.close()
    }

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
