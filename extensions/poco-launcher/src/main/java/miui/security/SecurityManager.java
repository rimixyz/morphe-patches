package miui.security;

import android.content.Context;

import java.util.Collections;
import java.util.List;

@SuppressWarnings("unused")
public class SecurityManager {
    public boolean checkAccessControlPassAsUser(String packageName, int userId) {
        return true;
    }

    public List<String> getAllPrivacyApps(int userId) {
        return Collections.emptyList();
    }

    public boolean getApplicationAccessControlEnabledAsUser(String packageName, int userId) {
        return false;
    }

    public boolean isAccessControlActived(Context context) {
        return false;
    }
}
