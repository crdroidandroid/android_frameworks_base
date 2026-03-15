package android.content.res;

import android.content.ComponentName;
import android.content.res.IThemeEngineCallback;
import android.graphics.Bitmap;

/** @hide */
interface IThemeEngineManager {
    Bitmap getIconPackIcon(in ComponentName component, int density);
    boolean hasActiveIconPack();
    String getIconPackPackage();

    Bitmap getPerAppIconPackIcon(String packageName, String className, int density);
    void setPerAppIconPack(String packageName, String iconPackPackage);
    void clearPerAppIconPack(String packageName);
    String getPerAppIconPack(String packageName);
    List<String> getInstalledIconPacks();

    Bitmap getSystemThemeIconDrawable(String resourceName, int density);
    boolean isTargetedResource(String resourceName);
    boolean hasActiveSystemThemeIcons();
    String getActiveSystemThemeIcons();

    String getCategoryTheme(String category);

    void registerCallback(IThemeEngineCallback callback);
    void unregisterCallback(IThemeEngineCallback callback);
    void notifyThemeChanged(String category);

    List<String> getAvailableOverlays(String category);
}
