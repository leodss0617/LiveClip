package com.example.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveDownloadStageTest {
    @Test
    fun roundedPercentMeansFragmentsAreStillBeingJoined() {
        val log = "95.9% of ~15.20GiB ETA 00:47 (frag 1993/2079)\n100.0% of ~ 15.25GiB at 63.75MiB/s ETA 00:00 (frag 2078/2079)"
        assertEquals(LiveDownloadStage.ASSEMBLING, LiveDownloadStage.fromLog(log))
    }

    @Test
    fun completedDownloadMovesToFinalization() {
        val log = "100.0% ETA 00:00 (frag 2078/2079)\n100% of   15.25GiB in 00:27:03 at 9.61MiB/s"
        assertEquals(LiveDownloadStage.FINALIZING, LiveDownloadStage.fromLog(log))
    }

    @Test
    fun newDownloadProgressOverridesPreviousCompletion() {
        val log = "100% of 15.25GiB in 00:27:03 at 9.61MiB/s\n15.3% ETA 00:14 (frag 17/110)"
        assertEquals(LiveDownloadStage.DOWNLOADING, LiveDownloadStage.fromLog(log))
    }
}
