package miui.process;

import java.util.List;

@SuppressWarnings("unused")
public class ProcessConfig {
    public ProcessConfig(int policy) {
    }

    public ProcessConfig(int policy, String killingPackage, int userId, int taskId) {
    }

    public void setRemoveTaskNeeded(boolean remove) {
    }

    public void setRemovingTaskIdList(List<Integer> taskIds) {
    }

    public void setWhiteList(List<String> whiteList) {
    }
}
