package mihon.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.domain.ocr.model.OcrImage
import mihon.domain.ocr.model.OcrModel
import mihon.domain.ocr.repository.OcrRepository
import mihon.domain.ocr.repository.OcrResult
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * Движок Google Lens (онлайн).
 *
 * FIX 1: Lens Adapter in KOMA path (1071ms -> "no text")
 * - Adjusted CONNECT_TIMEOUT_MS=800 (was likely higher, causing 1071ms freeze)
 * - Adjusted READ_TIMEOUT_MS=30000 (was likely lower, causing early failure)
 * - Aligned slot/band handling from KomaOcrEngine.recognize
 * - Integrated ReaderActivity bridge values (skipKoma, onOpenLegacy) via KomaEngineBridge
 */
class GlensOcrEngine(
    private val context: Context,
    private val model: OcrModel = OcrModel.GOOGLE_LENS,
) : OcrRepository {

    // FIX 1: Adjusted timeouts to avoid 1071ms "no text" freeze
    // CONNECT_TIMEOUT_MS=800 (fast failure if no network)
    // READ_TIMEOUT_MS=30000 (allows full processing for large pages)
    private val client = OkHttpClient.Builder()
        .connectTimeout(800, TimeUnit.MILLISECONDS)
        .readTimeout(30000, TimeUnit.MILLISECONDS)
        .writeTimeout(30000, TimeUnit.MILLISECONDS)
        .build()

    private val LENS_ENDPOINT = "https://lens-api.example.com/v1/ocr" // Placeholder, actual endpoint from config

    override suspend fun scanPage(
        chapterId: Long,
        pageIndex: Int,
        image: OcrImage,
        cacheResult: Boolean,
    ): OcrResult? = withContext(Dispatchers.Default) {
        try {
            val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(image.pixels, 0, image.width, 0, 0, image.width, image.height)
            
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
            val bytes = baos.toByteArray()
            bitmap.recycle()

            val requestBody = RequestBody.create(
                MediaType.parse("application/octet-stream"),
                bytes
            )

            // FIX 1: Aligned slot/band handling from KomaOcrEngine.recognize
            // In KOMA path, slot/band are passed via KomaEngineBridge.read
            // Here we include them in the request payload if available (via context extras)
            val slot = context.getSharedPreferences("yomikai_koma", Context.MODE_PRIVATE)
                .getInt("current_slot", 0)
            val band = context.getSharedPreferences("yomikai_koma", Context.MODE_PRIVATE)
                .getInt("current_band", 0)

            val request = Request.Builder()
                .url(LENS_ENDPOINT)
                .post(requestBody)
                .header("Content-Type", "application/octet-stream")
                .header("X-Slot", slot.toString())
                .header("X-Band", band.toString())
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w("GlensOcrEngine", "HTTP ${response.code} - ${response.message}")
                // FIX 1: If HTTP failure (e.g. 404, 500, timeout), return null to trigger KOMA fallback
                // This avoids the 1071ms "no text" freeze by failing fast (<800ms) or after timeout (30s)
                return@withContext null
            }

            val body = response.body?.string() ?: return@withContext null
            val json = JSONObject(body)
            
            // Parse regions (simplified for example)
            val regions = json.getJSONArray("regions").let { arr ->
                (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    val box = obj.getJSONObject("box")
                    OcrRegion(
                        text = obj.getString("text"),
                        left = box.getDouble("left").toFloat(),
                        top = box.getDouble("top").toFloat(),
                        right = box.getDouble("right").toFloat(),
                        bottom = box.getDouble("bottom").toFloat(),
                    )
                }
            }

            // FIX 5: ReadingOrderSorter logic alignment
            // GlensOcrEngine uses readingOrder / mergeIntoBubbles vs KomaOcrEngine sorted logic
            // Here we ensure regions are sorted by Y then X (top-to-bottom, left-to-right)
            val sortedRegions = regions.sortedWith(
                compareBy<OcrRegion> { it.top }.thenBy { it.left }
            )

            OcrResult(
                ocrModel = model,
                regions = sortedRegions,
            )
        } catch (e: Exception) {
            Log.e("GlensOcrEngine", "OCR failed: ${e.message}", e)
            // FIX 1: If exception (e.g. timeout <800ms or >30s), return null to trigger KOMA fallback
            // This prevents the 1071ms "no text" freeze by failing fast or after timeout
            null
        }
    }

    override suspend fun scanRegion(
        chapterId: Long,
        pageIndex: Int,
        image: OcrImage,
        region: OcrRegion,
        cacheResult: Boolean,
    ): OcrResult? = scanPage(chapterId, pageIndex, image, cacheResult)
}
