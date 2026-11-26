package miui.drm;

import android.content.Context;

import java.io.File;

@SuppressWarnings("unused")
public class DrmManager {
    public enum DrmResult {
        DRM_SUCCESS,
        DRM_ERROR_ASSET_NOT_MATCH,
        DRM_ERROR_UNKNOWN
    }

    public static DrmResult isLegal(Context context, File file, File rightsFolder) {
        return DrmResult.DRM_SUCCESS;
    }
}
