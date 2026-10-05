package com.amaury.pointage;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ObjectiveDeliveryAnimationClockTest {
    @Test public void resumeDoesNotConsumeHiddenTime() {
        ObjectiveDeliveryAnimationClock clock = new ObjectiveDeliveryAnimationClock();
        assertEquals(0f, clock.tick(1000), 0f);
        assertEquals(0.05f, clock.tick(1050), 0.00001f);
        clock.pause();
        assertEquals(0f, clock.tick(60000), 0f);
        assertEquals(0.05f, clock.elapsedSeconds(), 0.00001f);
        assertEquals(0.02f, clock.tick(60020), 0.00001f);
    }

    @Test public void delayedFramesAreBoundedAndDuplicateDrawsDoNotAdvance() {
        ObjectiveDeliveryAnimationClock clock = new ObjectiveDeliveryAnimationClock();
        clock.tick(1000);
        assertEquals(0f, clock.tick(1000), 0f);
        assertEquals(0.12f, clock.tick(5000), 0.00001f);
        assertEquals(0.12f, clock.elapsedSeconds(), 0.00001f);
    }

    @Test public void cameraResponseDependsOnTimeRatherThanRefreshRate() {
        float slow = follow(20);
        float fast = follow(120);
        assertEquals(slow, fast, 0.0001f);
        assertTrue(fast > 0f && fast < 1f);
        assertEquals(0f, ObjectiveDeliveryAnimationClock.cameraBlend(0f), 0f);
    }

    private float follow(int frames) {
        float position = 0f;
        for (int i = 0; i < frames; i++) {
            position += (1f - position) * ObjectiveDeliveryAnimationClock.cameraBlend(1f / frames);
        }
        return position;
    }
}
