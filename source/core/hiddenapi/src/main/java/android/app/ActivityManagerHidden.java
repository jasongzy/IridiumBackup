package android.app;

import dev.rikka.tools.refine.RefineAs;

/**
 * @see <a href="https://cs.android.com/android/platform/superproject/+/android-8.0.0_r51:frameworks/base/core/java/android/app/ActivityManager.java;l=3765">ActivityManager.java</a>
 */
@RefineAs(ActivityManager.class)
public class ActivityManagerHidden {
    public int getPackageImportance(String packageName) {
        throw new RuntimeException("Stub!");
    }

    public int getUidImportance(int uid) {
        throw new RuntimeException("Stub!");
    }

    public void killUid(int uid, String reason) {
        throw new RuntimeException("Stub!");
    }

    public void forceStopPackageAsUser(String packageName, int userId) {
        throw new RuntimeException("Stub!");
    }
}
