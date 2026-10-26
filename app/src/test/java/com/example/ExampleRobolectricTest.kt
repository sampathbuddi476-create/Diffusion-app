package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.network.ColabApiService
import com.example.util.AppError
import com.example.util.CompressionQuality
import com.example.util.VideoUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ConnectException
import java.net.SocketTimeoutException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Titan Anime", appName)
  }

  @Test
  fun `verify url sanitization and validation`() {
    val apiService = ColabApiService()
    assertEquals(
      "https://tunnel.trycloudflare.com",
      apiService.sanitizeUrl("tunnel.trycloudflare.com")
    )
    assertEquals(
      "https://tunnel.trycloudflare.com",
      apiService.sanitizeUrl("https://tunnel.trycloudflare.com/")
    )
    assertTrue(apiService.isValidUrl("tunnel.trycloudflare.com"))
    assertFalse(apiService.isValidUrl(""))
  }

  @Test
  fun `verify video duration and size formatting`() {
    assertEquals("00:15", VideoUtils.formatDuration(15000))
    assertEquals("01:30", VideoUtils.formatDuration(90000))
    assertEquals("0 B", VideoUtils.formatFileSize(0))
    assertTrue(VideoUtils.formatFileSize(1048576).contains("MB"))
  }

  @Test
  fun `verify app error mapping`() {
    val timeoutError = AppError.fromThrowable(SocketTimeoutException("Read timed out"))
    assertTrue(timeoutError is AppError.Timeout)

    val connectError = AppError.fromThrowable(ConnectException("Failed to connect"))
    assertTrue(connectError is AppError.ServerUnreachable)
  }

  @Test
  fun `verify compression quality settings`() {
    assertEquals(null, CompressionQuality.ORIGINAL.targetHeight)
    assertEquals(720, CompressionQuality.HIGH.targetHeight)
    assertEquals(540, CompressionQuality.MEDIUM.targetHeight)
    assertEquals(360, CompressionQuality.LOW.targetHeight)
  }
}
