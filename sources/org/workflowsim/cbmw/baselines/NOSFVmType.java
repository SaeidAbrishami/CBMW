package org.workflowsim.cbmw.baselines;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.workflowsim.cbmw.HybridVmPool;

/** Configurable on-demand VM type used only by the NOSF baseline. */
final class NOSFVmType {
    final String name;
    final int cores;
    final int ramMb;
    final double mipsPerCore;
    final double pricePerSecond;

    NOSFVmType(String name, int cores, int ramMb, double mipsPerCore,
               double pricePerSecond) {
        if (name == null || name.trim().isEmpty() || cores <= 0 || ramMb <= 0
                || !Double.isFinite(mipsPerCore) || mipsPerCore <= 0.0
                || !Double.isFinite(pricePerSecond) || pricePerSecond < 0.0) {
            throw new IllegalArgumentException("Invalid NOSF VM type");
        }
        this.name = name;
        this.cores = cores;
        this.ramMb = ramMb;
        this.mipsPerCore = mipsPerCore;
        this.pricePerSecond = pricePerSecond;
    }

    boolean canRun(int requiredCores, int requiredRamMb) {
        return cores >= requiredCores && ramMb >= requiredRamMb;
    }

    double runtime(double baseRuntime) {
        return baseRuntime * HybridVmPool.RESERVED_MIPS / mipsPerCore;
    }

    static List<NOSFVmType> configuredTypes() {
        int count = Integer.parseInt(System.getProperty("nosf.vm.type.count", "1"));
        if (count <= 0) throw new IllegalArgumentException("nosf.vm.type.count must be positive");
        List<NOSFVmType> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String prefix = "nosf.vm.type." + i + ".";
            int cores = Integer.parseInt(System.getProperty(prefix + "cores",
                    Integer.toString(HybridVmPool.TASK_CORES)));
            int ram = Integer.parseInt(System.getProperty(prefix + "ram.mb",
                    Integer.toString(HybridVmPool.TASK_RAM_MB)));
            double mips = Double.parseDouble(System.getProperty(prefix + "mips",
                    Double.toString(HybridVmPool.RESERVED_MIPS)));
            double defaultPrice = HybridVmPool.onDemandPricePerSecond(cores, ram);
            double price = Double.parseDouble(System.getProperty(prefix + "price.per.sec",
                    Double.toString(defaultPrice)));
            result.add(new NOSFVmType(System.getProperty(prefix + "name", "type" + i),
                    cores, ram, mips, price));
        }
        return Collections.unmodifiableList(result);
    }
}
