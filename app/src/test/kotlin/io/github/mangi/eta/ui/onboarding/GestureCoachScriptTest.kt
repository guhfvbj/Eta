package io.github.mangi.eta.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GestureCoachScriptTest {
    @Test
    fun existingUsersProceedFromHistoryToNavigatorWithoutTheRemovedWorkspaceStep() {
        val progress = mapOf(OnboardingKeys.SwipeRight to 1, "gesture.swipeLeft" to 0)
        assertEquals(GestureDirection.SwipeUp, GestureCoachScript.next(progress)?.direction)
        assertNull(GestureCoachScript.next(progress + (OnboardingKeys.SwipeUp to 1)))
    }
}
