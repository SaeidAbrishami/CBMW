package org.workflowsim.cbmw.baselines;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.workflowsim.cbmw.HybridVmPool;

/** Configurable on-demand VM type used only by the NOSF baseline. */
final class NOSFVmType {
    private static final int PAPER_UNCONSTRAINED_RAM_MB = 1_000_000_000;
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
        String configuredCount = System.getProperty("nosf.vm.type.count");
        if (configuredCount == null && NOSFConfiguration.isPaperAligned()) {
            return paperTypes();
        }
        int count = Integer.parseInt(configuredCount == null ? "1" : configuredCount);
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

    /** Table 2 from the NOSF paper; RAM is non-limiting because it is unspecified. */
    static List<NOSFVmType> paperTypes() {
        List<NOSFVmType> result = new ArrayList<>();
        result.add(paperType("m2.4xlarge", 8, 0.980, 1.0));
        result.add(paperType("m2.2xlarge", 4, 0.490, 1.2));
        result.add(paperType("m1.xlarge", 4, 0.350, 1.3));
        result.add(paperType("m2.xlarge", 2, 0.245, 1.4));
        result.add(paperType("m1.large", 2, 0.175, 1.6));
        result.add(paperType("m1.medium", 1, 0.087, 1.8));
        result.add(paperType("m1.small", 1, 0.044, 2.0));
        return Collections.unmodifiableList(result);
    }

    private static NOSFVmType paperType(String name, int cores,
                                        double hourlyPrice, double weight) {
        return new NOSFVmType(name, cores, PAPER_UNCONSTRAINED_RAM_MB,
                HybridVmPool.RESERVED_MIPS / weight, hourlyPrice / 3600.0);
    }
}
