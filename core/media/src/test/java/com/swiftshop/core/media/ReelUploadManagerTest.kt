package com.swiftshop.core.media

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ReelUploadManagerTest {

    @Test
    fun `manager initially has null progress`() = runBlocking {
        val manager = ReelUploadManager()
        assertNull(manager.uploadProgress.value)
    }

    @Test
    fun `manager updates progress correctly`() = runBlocking {
        val manager = ReelUploadManager()
        val inProgress = MediaUploadProgress.InProgress(50, 100)
        
        manager.updateProgress(inProgress)
        assertEquals(inProgress, manager.uploadProgress.value)
        
        val complete = MediaUploadProgress.Complete(MediaAsset(id = "test"))
        manager.updateProgress(complete)
        assertEquals(complete, manager.uploadProgress.value)
    }

    @Test
    fun `manager resets to null when requested`() = runBlocking {
        val manager = ReelUploadManager()
        manager.updateProgress(MediaUploadProgress.InProgress(10, 100))
        
        manager.updateProgress(null)
        assertNull(manager.uploadProgress.value)
    }

    @Test
    fun `clear() resets progress to null`() = runBlocking {
        val manager = ReelUploadManager()
        manager.updateProgress(MediaUploadProgress.InProgress(10, 100))
        
        manager.clear()
        assertNull(manager.uploadProgress.value)
    }
}
