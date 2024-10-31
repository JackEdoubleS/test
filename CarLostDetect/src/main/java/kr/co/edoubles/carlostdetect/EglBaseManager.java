package kr.co.edoubles.carlostdetect;

import org.webrtc.EglBase;

public class EglBaseManager {
    private static EglBase eglBaseInstance;
    private static boolean isMainActivityAlive = false;
    private static boolean isServiceAlive = false;

    public static synchronized EglBase getEglBaseInstance() {
        if (eglBaseInstance == null) {
            eglBaseInstance = EglBase.create();
        }
        return eglBaseInstance;
    }

    public static synchronized void setMainActivityState(boolean alive) {
        isMainActivityAlive = alive;
        checkAndRelease();
    }

    public static synchronized void setServiceState(boolean alive) {
        isServiceAlive = alive;
        checkAndRelease();
    }

    private static void checkAndRelease() {
        if (!isMainActivityAlive && !isServiceAlive && eglBaseInstance != null) {
            eglBaseInstance.release();
            eglBaseInstance = null;
        }
    }
}
