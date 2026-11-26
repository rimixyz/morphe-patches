package miui.module;

@SuppressWarnings("unused")
public class ModuleManager {
    private static final ModuleManager INSTANCE = new ModuleManager();

    public interface ModuleLoadListener {
    }

    public static ModuleManager getInstance() {
        return INSTANCE;
    }

    public void loadModules(String... names) {
    }

    public void setModuleLoadListener(ModuleLoadListener listener) {
    }
}
