package com.amaury.pointage;

/** Visual time only: never advances campaign dates or business state. */
final class ObjectiveDeliveryAnimationClock {
    private long previousMillis = -1;
    private float elapsedSeconds;

    void pause() {
        previousMillis = -1;
    }

    float tick(long nowMillis) {
        float delta = previousMillis < 0 ? 0f :
                Math.max(0f, Math.min(0.12f, (nowMillis - previousMillis) / 1000f));
        previousMillis = nowMillis;
        elapsedSeconds += delta;
        return delta;
    }

    float elapsedSeconds() {
        return elapsedSeconds;
    }

    static float cameraBlend(float deltaSeconds) {
        // Same response as the original 0.16 blend at 55 ms, at every refresh rate.
        return deltaSeconds <= 0f ? 0f :
                (float) (1d - Math.pow(0.84d, deltaSeconds / 0.055d));
    }
}
