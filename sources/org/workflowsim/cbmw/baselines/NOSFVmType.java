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
        // Rigid CBMW tasks take the same time on every eligible VM.
        return baseRuntime;
    }

    static List<NOSFVmType> configuredTypes() {
        String configuredCount = System.getProperty("nosf.vm.type.count");
        if (configuredCount == null) {
            return ohioProxyTypes();
        }
        int count = Integer.parseInt(configuredCount);
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
            if (Math.abs(mips - HybridVmPool.RESERVED_MIPS) > 1e-9) {
                throw new IllegalArgumentException(prefix + "mips must equal the shared rigid-task MIPS");
            }
            double defaultPrice = HybridVmPool.onDemandPricePerSecond(cores, ram);
            double price = Double.parseDouble(System.getProperty(prefix + "price.per.sec",
                    Double.toString(defaultPrice)));
            result.add(new NOSFVmType(System.getProperty(prefix + "name", "type" + i),
                    cores, ram, mips, price));
        }
        return Collections.unmodifiableList(result);
    }

    /** NOSF Table 2 names and historical capacities, with us-east-2 Linux
     * on-demand proxy prices. The proxy is a price reference, not a replacement
     * for the simulated type's original CPU/RAM capacity.
     */
    static List<NOSFVmType> ohioProxyTypes() {
        List<NOSFVmType> result = new ArrayList<>();
        result.add(proxyType("m2.4xlarge", 8, 68.4, 0.504)); // r5.2xlarge
        result.add(proxyType("m2.2xlarge", 4, 34.2, 0.252)); // r5.xlarge
        result.add(proxyType("m1.xlarge", 4, 15.0, 0.192)); // m5.xlarge
        result.add(proxyType("m2.xlarge", 2, 17.1, 0.126)); // r5.large
        result.add(proxyType("m1.large", 2, 7.5, 0.096)); // m5.large
        result.add(proxyType("m1.medium", 1, 3.7, 0.0464)); // t2.medium
        result.add(proxyType("m1.small", 1, 1.7, 0.023)); // t2.small
        return Collections.unmodifiableList(result);
    }

    private static NOSFVmType proxyType(String name, int cores,
                                        double gib, double hourlyPrice) {
        return new NOSFVmType(name, cores, (int) Math.round(gib * 1024),
                HybridVmPool.RESERVED_MIPS, hourlyPrice / 3600.0);
    }
}
