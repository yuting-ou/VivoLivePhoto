package com.vliveconvert.app

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.vliveconvert.app.picker.ConvertedIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「已转换」索引的判定口径（Robolectric 真实 MediaStore）：
 * - load：目标目录（大小写不敏感）内 名称→id；其它目录不收
 * - isConverted：同名（不同 id）或 基名(n).jpg（n≥1）存在 → 已转换；
 *   **自己不算自己的既往产物**——源对的主图本身就占着原名，绝不能因此标灰
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConvertedIndexTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val resolver get() = context.contentResolver
    private val images get() =
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private fun insert(name: String, relPath: String = "DCIM/Camera/"): Long {
        val uri = resolver.insert(
            images,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            }
        )!!
        return ContentUris.parseId(uri)
    }

    @Test
    fun loadMapsLowercaseNameToId() {
        val id = insert("IMG_index_probe.jpg")
        val index = ConvertedIndex.load(resolver, "DCIM/Camera")
        assertEquals(id, index["img_index_probe.jpg"])
    }

    @Test
    fun relPathMatchIsCaseInsensitive() {
        insert("IMG_case_probe.jpg", relPath = "dcim/camera/")
        assertTrue(ConvertedIndex.load(resolver, "DCIM/Camera").containsKey("img_case_probe.jpg"))
    }

    @Test
    fun otherAlbumsAreExcluded() {
        insert("IMG_elsewhere_probe.jpg", relPath = "Pictures/VLiveConvert/")
        assertFalse(
            ConvertedIndex.load(resolver, "DCIM/Camera").containsKey("img_elsewhere_probe.jpg"))
    }

    @Test
    fun sequenceOutputMarksConverted() {
        val ownId = insert("IMG_seq.jpg")  // 源对主图占据原名
        insert("IMG_seq(1).jpg")           // 既往产物（删原图关 + 移到相机相册时的序号形态）
        val index = ConvertedIndex.load(resolver, "DCIM/Camera")
        assertTrue(ConvertedIndex.isConverted("IMG_seq", ownId, index))
    }

    @Test
    fun sameNameElsewhereMarksConverted() {
        insert("IMG_same.jpg") // 源对在别的相册，产物以原名落在目标目录
        val index = ConvertedIndex.load(resolver, "DCIM/Camera")
        assertTrue(ConvertedIndex.isConverted("IMG_same", ownImageId = 999L, index))
    }

    @Test
    fun ownRowDoesNotCountAsPreviousOutput() {
        val ownId = insert("IMG_own.jpg") // 只有源对自身，没有任何产物
        val index = ConvertedIndex.load(resolver, "DCIM/Camera")
        assertFalse(ConvertedIndex.isConverted("IMG_own", ownId, index))
    }

    @Test
    fun unrelatedStemIsNotConverted() {
        insert("IMG_unrelated.jpg")
        val index = ConvertedIndex.load(resolver, "DCIM/Camera")
        assertFalse(ConvertedIndex.isConverted("IMG_absent", 999L, index))
    }

    @Test
    fun nestedParenthesisNameBelongsToAnotherSource() {
        val ownId = insert("IMG_nest.jpg")
        insert("IMG_nest(1)(1).jpg") // IMG_nest(1) 这个源的产物，不是 IMG_nest 的
        val index = ConvertedIndex.load(resolver, "DCIM/Camera")
        assertFalse(ConvertedIndex.isConverted("IMG_nest", ownId, index))
    }
}
