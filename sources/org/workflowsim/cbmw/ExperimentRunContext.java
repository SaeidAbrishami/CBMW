package org.workflowsim.cbmw;

/**
 * Per-replicate context shared by every algorithm in the experiment driver.
 * Runtime samples are deterministic functions of the run seed and task identity,
 * so algorithm ordering cannot change the sampled workload.
 */
public final class ExperimentRunContext {

    private static int run;
    private static long seed;
    private static boolean resampleRuntimes;
    private static String profile = "COMMON_MARKET";

    private ExperimentRunContext() {}

    public static void configure(int runIndex, long runSeed,
                                 boolean resample, String profileName) {
        run = runIndex;
        seed = runSeed;
        resampleRuntimes = resample;
        profile = profileName == null || profileName.trim().isEmpty()
                ? "COMMON_MARKET" : profileName;
    }

    public static int getRun() { return run; }

    public static long getSeed() { return seed; }

    public static boolean shouldResampleRuntimes() { return resampleRuntimes; }

    public static String getProfile() { return profile; }

    /** Returns a positive normal sample with sigma/mu shared by all algorithms. */
    public static double sampleRuntime(String workflowPath, int taskId,
                                       double nominalRuntime) {
        if (!(nominalRuntime > 0.0)) return nominalRuntime;
        long identity = stableHash(workflowPath == null ? "" : workflowPath);
        identity ^= mix64(((long) taskId << 32) ^ (taskId & 0xffffffffL));
        long first = mix64(seed ^ identity ^ 0x9E3779B97F4A7C15L);
        long second = mix64(first ^ 0xD1B54A32D192ED03L);
        double u1 = uniformOpen(first);
        double u2 = uniformOpen(second);
        double z = Math.sqrt(-2.0 * Math.log(u1))
                * Math.cos(2.0 * Math.PI * u2);
        double sigma = PaperRuntimeModel.STDDEV_RATIO * nominalRuntime;
        return Math.max(0.1, nominalRuntime + sigma * z);
    }

    /** Stable run seed used for repeatable, independent replicate streams. */
    public static long seedForRun(long baseSeed, int runIndex) {
        return mix64(baseSeed + 0x9E3779B97F4A7C15L * (runIndex + 1L));
    }

    private static double uniformOpen(long value) {
        long bits = (value >>> 11) & ((1L << 53) - 1);
        return (bits + 0.5) * 0x1.0p-53;
    }

    private static long stableHash(String value) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < value.length(); i++) {
            hash ^= value.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }
}
