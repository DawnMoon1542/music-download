package com.dawnmoon.music_download.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WakeLockControllerTest {

    private lateinit var controller: AndroidWakeLockController

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        controller = AndroidWakeLockController(context)
    }

    @After
    fun tearDown() {
        controller.releaseAll()
    }

    @Test
    fun acquireIsReferenceCountedAndLockIsReleasedOnlyAfterLastReference() {
        controller.acquire()
        controller.acquire()
        assertEquals("两次 acquire 应形成两个引用", 2, controller.refCount)
        assertTrue("存在引用时应持有锁", controller.isHeld)

        controller.release()
        assertEquals("释放一次引用后应剩余一个引用", 1, controller.refCount)
        assertTrue("仍有引用时不应释放锁", controller.isHeld)

        controller.release()
        assertEquals("最后一个引用释放后计数应归零", 0, controller.refCount)
        assertFalse("最后一个引用释放后应释放锁", controller.isHeld)
    }

    @Test
    fun releaseAllReleasesLockImmediately() {
        controller.acquire()
        controller.acquire()

        controller.releaseAll()

        assertEquals("releaseAll 后引用计数应归零", 0, controller.refCount)
        assertFalse("releaseAll 应立即释放锁", controller.isHeld)
    }
}
