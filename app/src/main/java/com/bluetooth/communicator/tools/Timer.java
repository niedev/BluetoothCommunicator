package com.bluetooth.communicator.tools;

public class Timer {
    private CustomCountDownTimer countDownTimer;
    private boolean isFinished = false;
    private Callback callback;
    private final Object lock = new Object();

    public Timer(long duration) {
        countDownTimer = new CustomCountDownTimer(duration, duration) {
            @Override
            public void onTick(long millisUntilFinished) {
            }

            @Override
            public void onFinish() {
                synchronized (lock) {
                    isFinished = true;
                    if (callback != null) {
                        callback.onFinished();
                    }
                }
            }
        };
    }

    public void start() {
        countDownTimer.start();
    }

    public void cancel() {
        synchronized (lock) {
            countDownTimer.cancel();
            callback = null;
        }
    }

    public boolean isFinished() {
        return isFinished;
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    public static abstract class Callback {
        public abstract void onFinished();
    }
}
