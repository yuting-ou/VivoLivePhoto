package com.vliveconvert.app

import com.vliveconvert.app.picker.showsAlbumEmptyState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 选择器空态分支：全局搜索的结果不能被「当前相册是空的」盖掉。 */
class PickerEmptyStateTest {

    @Test
    fun albumEmptyStateShowsAfterCompletedScan() {
        assertTrue(showsAlbumEmptyState(resultsEmpty = true, running = false, globalMode = false))
    }

    @Test
    fun albumEmptyStateHidesWhileScanning() {
        assertFalse(showsAlbumEmptyState(resultsEmpty = true, running = true, globalMode = false))
    }

    @Test
    fun albumEmptyStateNeverHidesGlobalSearchResults() {
        assertFalse(showsAlbumEmptyState(resultsEmpty = true, running = false, globalMode = true))
    }
}
