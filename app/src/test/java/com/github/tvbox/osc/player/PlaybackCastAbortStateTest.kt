package com.github.tvbox.osc.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackCastAbortStateTest {

    @Test
    fun freshStateAcceptsPlayUrls() {
        val st = PlaybackAttemptState()
        assertFalse(st.castAborted)
    }

    @Test
    fun abortCastSession_setsAbortAndClearsPrepare() {
        val st = PlaybackAttemptState()
        st.castPrepareOnly = true
        st.abortCastSession()
        assertTrue(st.castAborted)
        assertFalse(st.castPrepareOnly)
    }

    @Test
    fun clearCastAbort_reportsTransitionOnce() {
        val st = PlaybackAttemptState()
        st.abortCastSession()
        assertTrue(st.clearCastAbort())
        assertFalse(st.castAborted)
        assertFalse(st.clearCastAbort())
    }

    @Test
    fun beginSessionClearsAbort() {
        val st = PlaybackAttemptState()
        st.abortCastSession()
        st.beginSession()
        assertFalse(st.castAborted)
    }

    @Test
    fun abortSurvivesPlayLevelResets() {
        val st = PlaybackAttemptState()
        st.abortCastSession()
        st.beginNewPlay()
        st.onLineSwitched()
        st.resetAutoRetryLadder()
        st.userSelfRescue()
        st.stoppedForSourceSwitch()
        st.clearSessionFlags()
        assertTrue(st.castAborted)
    }

    @Test
    fun abortDoesNotTouchStartFlags() {
        val st = PlaybackAttemptState()
        st.playbackStarted = true
        st.abortCastSession()
        assertTrue(st.playbackStarted)
        assertTrue(st.clearCastAbort())
        assertTrue(st.playbackStarted)
    }
}
