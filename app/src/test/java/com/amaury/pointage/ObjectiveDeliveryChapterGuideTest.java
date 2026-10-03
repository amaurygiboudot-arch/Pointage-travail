package com.amaury.pointage;

import org.junit.Test;
import static org.junit.Assert.*;

public class ObjectiveDeliveryChapterGuideTest {
    @Test public void everyPlayablePhaseHasAnInstructionInItsOwnChapter() {
        Enum<?>[][] phases = {
            ObjectiveDeliveryPhase.values(), ObjectiveDeliveryChapterTwoPhase.values(),
            ObjectiveDeliveryChapterThreePhase.values(), ObjectiveDeliveryChapterFourPhase.values(),
            ObjectiveDeliveryChapterFivePhase.values(), ObjectiveDeliveryChapterSixPhase.values(),
            ObjectiveDeliveryChapterSevenPhase.values(), ObjectiveDeliveryChapterEightPhase.values(),
            ObjectiveDeliveryChapterNinePhase.values(), ObjectiveDeliveryChapterTenPhase.values()
        };
        for (int i = 0; i < phases.length; i++) {
            ObjectiveDeliveryChapterGuide.Chapter chapter = ObjectiveDeliveryChapterGuide.chapter(i + 1);
            assertEquals(phases[i].length, chapter.stepCount());
            for (Enum<?> phase : phases[i]) {
                int index = chapter.indexOf(phase.name());
                assertTrue(chapter.step(index).instruction.length() > 20);
                if (phase.name().equals("RESULT")) assertEquals(chapter.stepCount() - 1, index);
            }
        }
    }
    @Test public void allTenChaptersHaveDistinctGoalsAndTitles() {
        java.util.Set<String> titles = new java.util.HashSet<>();
        java.util.Set<String> goals = new java.util.HashSet<>();
        for (int i = 1; i <= 10; i++) {
            ObjectiveDeliveryChapterGuide.Chapter chapter = ObjectiveDeliveryChapterGuide.chapter(i);
            assertEquals(i, chapter.number);
            assertTrue(titles.add(chapter.title));
            assertTrue(goals.add(chapter.goal));
            assertFalse(chapter.risk.isEmpty());
        }
    }
    @Test(expected = IllegalArgumentException.class) public void unknownChapterIsRejected() {
        ObjectiveDeliveryChapterGuide.chapter(11);
    }
    @Test(expected = IllegalArgumentException.class) public void phaseFromAnotherChapterIsRejected() {
        ObjectiveDeliveryChapterGuide.chapter(1).indexOf("PRESSURE_PLAN");
    }
}
