package org.workflowsim.cbmw.baselines;

import org.workflowsim.FileItem;
import org.workflowsim.Task;
import org.workflowsim.utils.Parameters;

/** Optional paper edge-transfer model; common-market runs use shared storage. */
final class NOSFTransferModel {
    enum Mode { COMMON_SHARED_STORAGE, PAPER_NETWORK }

    private final Mode mode;
    private final double bandwidthMbps;

    NOSFTransferModel() {
        mode = Mode.valueOf(System.getProperty("nosf.transfer.mode",
                Mode.COMMON_SHARED_STORAGE.name()).trim().toUpperCase());
        bandwidthMbps = Double.parseDouble(System.getProperty(
                "nosf.network.bandwidth.mbps", "100.0"));
        if (!Double.isFinite(bandwidthMbps) || bandwidthMbps <= 0.0) {
            throw new IllegalArgumentException(
                    "nosf.network.bandwidth.mbps must be finite and positive");
        }
    }

    Mode getMode() { return mode; }

    double getBandwidthMbps() { return bandwidthMbps; }

    double delay(Task parent, Task child, int parentVmId, int childVmId) {
        if (mode == Mode.COMMON_SHARED_STORAGE) return 0.0;
        if (parentVmId >= 0 && parentVmId == childVmId) return 0.0;
        double bytes = dependencyBytes(parent, child);
        return bytes * 8.0 / (bandwidthMbps * 1_000_000.0);
    }

    double crossVmDelay(Task parent, Task child) {
        return delay(parent, child, -1, -2);
    }

    private static double dependencyBytes(Task parent, Task child) {
        double bytes = 0.0;
        for (FileItem output : parent.getFileList()) {
            if (output.getType() != Parameters.FileType.OUTPUT) continue;
            for (FileItem input : child.getFileList()) {
                if (input.getType() == Parameters.FileType.INPUT
                        && output.getName().equals(input.getName())) {
                    bytes += Math.max(0.0, output.getSize());
                    break;
                }
            }
        }
        return bytes;
    }
}
