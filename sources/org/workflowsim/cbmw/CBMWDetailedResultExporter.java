package org.workflowsim.cbmw;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.workflowsim.utils.Parameters;

/** Writes .rar-style detailed outputs from the in-memory accounting records. */
public class CBMWDetailedResultExporter {

    private final List<WorkflowRecord> workflows;
    private final CBMWAccounting accounting;
    private final HybridVmPool vmPool;
    private final String algorithm;
    private final String scenario;
    private final double alpha;
    private final double simDuration;

    public CBMWDetailedResultExporter(List<WorkflowRecord> workflows,
                                      CBMWAccounting accounting,
                                      HybridVmPool vmPool,
                                      String algorithm,
                                      String scenario,
                                      double alpha,
                                      double simDuration) {
        this.workflows = workflows;
        this.accounting = accounting;
        this.vmPool = vmPool;
        this.algorithm = algorithm;
        this.scenario = scenario;
        this.alpha = alpha;
        this.simDuration = simDuration;
    }

    public void export(File outputDir) throws IOException {
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw new IOException("Could not create output directory: " + outputDir);
        }
        writeResultsTxt(new File(outputDir, "results.txt"));
        writeWorkflowSummary(new File(outputDir, "WORKFLOW_COMPLETION_SUMMARY.xlsx"));
        writeTaskSummary(new File(outputDir, "TASK_EXECUTION_SUMMARY.xlsx"));
        writeTaskCsv(new File(outputDir, "TASK_EXECUTION_SUMMARY.csv"), false);
        writeOnDemandUsage(new File(outputDir, "ON_DEMAND_INSTANCE_USAGE.xlsx"));
    }

    /** Appends this scenario's task rows to the algorithm-level CSV. */
    public void appendTaskCsv(File file) throws IOException {
        writeTaskCsv(file, true);
    }

    private void writeResultsTxt(File file) throws IOException {
        long totalWorkflows = workflows.size();
        long completedWorkflows = accounting.getWorkflowRecords().size();
        long successfulWorkflows = accounting.getWorkflowRecords().stream()
                .filter(WorkflowCompletionRecord::isMetDeadline).count();
        long totalTasks = accounting.getTaskRecords().size();
        double successRate = completedWorkflows == 0 ? 0.0
                : (100.0 * successfulWorkflows / completedWorkflows);

        List<UtilizationSnapshot> snapshots = accounting.getUtilizationSnapshots();
        Stat available = Stat.fromSnapshots(snapshots, s ->
                s.getTotalReservedCores() + s.getTotalOnDemandCores());
        Stat utilized = Stat.fromSnapshots(snapshots, UtilizationSnapshot::getRunningCores);
        Stat overallUtil = Stat.fromSnapshots(snapshots, s -> s.getOverallUtilization() * 100.0);
        Stat reservedUtil = Stat.fromSnapshots(snapshots, s -> s.getReservedUtilization() * 100.0);
        Stat reservedRamUtil = Stat.fromSnapshots(snapshots,
                s -> s.getReservedRamUtilization() * 100.0);

        List<OnDemandInstanceRecord> onDemand = accounting.getOnDemandRecords();
        Stat uptime = Stat.fromValues(finiteUptimes(onDemand));
        double totalUptime = finiteUptimes(onDemand).stream().mapToDouble(Double::doubleValue).sum();
        Map<Integer, TaskExecutionRecord> onDemandTasks = new HashMap<>();
        List<Double> onDemandCoreSizes = new ArrayList<>();
        List<Double> onDemandRamSizes = new ArrayList<>();
        for (TaskExecutionRecord record : accounting.getTaskRecords()) {
            if ("On-Demand".equals(record.getVmType()) && record.getVmId() >= 0) {
                onDemandTasks.put(record.getVmId(), record);
                onDemandCoreSizes.add((double) record.getTaskCores());
                onDemandRamSizes.add((double) record.getTaskRamMb());
            }
        }
        Stat onDemandCores = Stat.fromValues(onDemandCoreSizes);
        Stat onDemandRam = Stat.fromValues(onDemandRamSizes);

        double onDemandCost = 0.0;
        for (OnDemandInstanceRecord instance : onDemand) {
            TaskExecutionRecord task = onDemandTasks.get(instance.getVmId());
            if (task != null && Double.isFinite(instance.getUptime())) {
                onDemandCost += instance.getUptime()
                        * HybridVmPool.onDemandPricePerSecond(
                                task.getTaskCores(), task.getTaskRamMb());
            }
        }
        double reservedCost = HybridVmPool.NUM_RESERVED
                * HybridVmPool.RESERVED_HOURLY_COST
                * Math.ceil(simDuration / 3600.0);
        double reservedCoresWithTime = HybridVmPool.NUM_RESERVED
                * HybridVmPool.RESERVED_CORES * simDuration;
        double onDemandCoresWithTime = 0.0;
        for (OnDemandInstanceRecord instance : onDemand) {
            TaskExecutionRecord task = onDemandTasks.get(instance.getVmId());
            if (task != null && Double.isFinite(instance.getUptime())) {
                onDemandCoresWithTime += task.getTaskCores() * instance.getUptime();
            }
        }
        double makespan = workflows.stream()
                .mapToDouble(WorkflowRecord::getCompletionTime)
                .filter(t -> t < Double.MAX_VALUE)
                .max().orElse(0.0);
        double meanDelay = workflows.stream()
                .filter(w -> w.getCompletionTime() < Double.MAX_VALUE)
                .mapToDouble(w -> Math.max(0.0, w.getCompletionTime() - w.getDeadline()))
                .filter(d -> d > 0.0)
                .average().orElse(0.0);

        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8))) {
            writer.write("========== WORKFLOW SUCCESS RATE ==========\n");
            writer.write(String.format(Locale.US, "Total Workflows: %d%n", totalWorkflows));
            writer.write(String.format(Locale.US, "Completed Workflows: %d%n", completedWorkflows));
            writer.write(String.format(Locale.US,
                    "Successful Workflows (met deadline): %d%n", successfulWorkflows));
            writer.write(String.format(Locale.US, "Success Rate: %.2f%%%n%n", successRate));

            writer.write("========== CORE UTILIZATION STATISTICS ==========\n");
            writer.write(String.format(Locale.US, "Total Snapshots: %d%n%n", snapshots.size()));
            writer.write("Total Available Cores (Reserved + On-Demand):\n");
            writer.write(String.format(Locale.US, "  Mean: %.2f cores%n", available.mean));
            writer.write(String.format(Locale.US, "  Min: %.0f cores%n", available.min));
            writer.write(String.format(Locale.US, "  Max: %.0f cores%n%n", available.max));
            writer.write("Utilized Cores (Cores Running Tasks):\n");
            writer.write(String.format(Locale.US, "  Mean: %.2f cores%n", utilized.mean));
            writer.write(String.format(Locale.US, "  Min: %.0f cores%n", utilized.min));
            writer.write(String.format(Locale.US, "  Max: %.0f cores%n%n", utilized.max));
            writer.write(String.format(Locale.US,
                    "Average Core Utilization: %.2f%%%n%n", overallUtil.mean));

            writer.write("========== ON-DEMAND INSTANCE USAGE ==========\n");
            writer.write(String.format(Locale.US,
                    "Total On-Demand Instances Launched: %d%n%n", onDemand.size()));
            writer.write(String.format(Locale.US,
                    "Average On-Demand Instance Uptime: %.1f seconds%n", uptime.mean));
            writer.write(String.format(Locale.US,
                    "Total On-Demand Instance Time: %.1f seconds%n%n", totalUptime));

            writer.write("========== INSTANCE SPECIFICATIONS ==========\n");
            writer.write("Reserved Instances:\n");
            writer.write(String.format(Locale.US, "  Total Count: %d%n", HybridVmPool.NUM_RESERVED));
            writer.write(String.format(Locale.US,
                    "  Cores per Instance: %d%n", HybridVmPool.RESERVED_CORES));
            writer.write(String.format(Locale.US,
                    "  RAM per Instance: %d MB%n", HybridVmPool.RESERVED_RAM_MB));
            writer.write(String.format(Locale.US,
                    "  Total Reserved Cores: %d%n",
                    HybridVmPool.NUM_RESERVED * HybridVmPool.RESERVED_CORES));
            writer.write(String.format(Locale.US,
                    "  Total Reserved RAM: %d MB%n%n",
                    HybridVmPool.NUM_RESERVED * HybridVmPool.RESERVED_RAM_MB));
            writer.write(String.format(Locale.US,
                    "  Total Reserved Cores With Time: %.6E%n%n",
                    reservedCoresWithTime));
            writer.write("On-Demand Instances:\n");
            writer.write(String.format(Locale.US, "  Total Count: %d%n", onDemand.size()));
            writer.write(String.format(Locale.US,
                    "  Cores per Instance (mean/min/max): %.2f / %.0f / %.0f%n",
                    onDemandCores.mean, onDemandCores.min, onDemandCores.max));
            writer.write(String.format(Locale.US,
                    "  RAM per Instance MB (mean/min/max): %.2f / %.0f / %.0f%n",
                    onDemandRam.mean, onDemandRam.min, onDemandRam.max));
            writer.write(String.format(Locale.US,
                    "  Total On-Demand Cores: %.0f%n",
                    onDemandCoreSizes.stream().mapToDouble(Double::doubleValue).sum()));
            writer.write(String.format(Locale.US,
                    "  Total On-Demand RAM: %.0f MB%n%n",
                    onDemandRamSizes.stream().mapToDouble(Double::doubleValue).sum()));
            writer.write(String.format(Locale.US,
                    "  Total On-Demand Cores With Time: %.6E%n%n",
                    onDemandCoresWithTime));

            writer.write("========== RESERVED INSTANCE RESOURCE UTILIZATION ==========\n");
            writer.write(String.format(Locale.US,
                    "Total Utilization Snapshots: %d%n%n", snapshots.size()));
            writer.write("CPU Utilization Statistics:\n");
            writer.write(String.format(Locale.US,
                    "  Mean CPU Utilization: %.2f%%%n", reservedUtil.mean));
            writer.write(String.format(Locale.US,
                    "  Min CPU Utilization: %.2f%%%n", reservedUtil.min));
            writer.write(String.format(Locale.US,
                    "  Max CPU Utilization: %.2f%%%n%n", reservedUtil.max));
            writer.write("RAM Utilization Statistics:\n");
            writer.write(String.format(Locale.US,
                    "  Mean RAM Utilization: %.2f%%%n", reservedRamUtil.mean));
            writer.write(String.format(Locale.US,
                    "  Min RAM Utilization: %.2f%%%n", reservedRamUtil.min));
            writer.write(String.format(Locale.US,
                    "  Max RAM Utilization: %.2f%%%n", reservedRamUtil.max));

            writer.write("========== SIMULATION SUMMARY ==========\n");
            writer.write(String.format(Locale.US, "Scheduling Algorithm: %s%n", algorithm));
            if ("CBMW".equals(algorithm)) {
                writer.write(String.format(Locale.US,
                        "Planning Runtime Quantile (alpha): %.3f%n",
                        PaperRuntimeModel.QUANTILE));
                writer.write(String.format(Locale.US,
                        "Runtime Stddev Ratio (sigma/mu): %.3f%n",
                        PaperRuntimeModel.STDDEV_RATIO));
                writer.write(String.format(Locale.US,
                        "Conservative Runtime Multiplier (cet/mu): %.4f%n",
                        PaperRuntimeModel.conservativeEstimate(1.0)));
                writer.write(String.format(Locale.US,
                        "Negotiation Safety Factor (beta): %.3f%n",
                        PaperRuntimeModel.NEGOTIATION_BETA));
            }
            writer.write(String.format(Locale.US, "Total Tasks: %d%n", totalTasks));
            writer.write(String.format(Locale.US, "Total Workflows: %d%n", totalWorkflows));
            writer.write(String.format(Locale.US,
                    "Scheduling Period: %.1f seconds%n", HybridVmPool.SCHEDULING_PERIOD));
            writer.write(String.format(Locale.US,
                    "Provisioner Period: %.1f seconds%n", HybridVmPool.PROVISIONER_PERIOD));
            writer.write(String.format(Locale.US,
                    "On-demand instances launched: %d%n", onDemand.size()));
            writer.write(String.format(Locale.US,
                    "Time For Start First Workflow to End Last Workflow: %.1f seconds%n",
                    makespan));
            writer.write(String.format(Locale.US,
                    "Simulation End Time: %.1f seconds%n%n", Parameters.getSimDuration()));
            writer.write(String.format(Locale.US,
                    "Mean workflow delay (only positive delays averaged): %.1f seconds%n",
                    meanDelay));
            writer.write(String.format(Locale.US,
                    "Price:  %.4f + %.4f = %.4f%n",
                    reservedCost, onDemandCost, reservedCost + onDemandCost));
        }
    }

    private void writeWorkflowSummary(File file) throws IOException {
        List<List<Object>> rows = new ArrayList<>();
        rows.add(row("Workflow Name", "Status", "Start Time (s)", "Finish Time (s)",
                "Critical Path Time (s)", "Deadline (s)", "Met Deadline", "Alpha"));
        List<WorkflowCompletionRecord> records = new ArrayList<>(accounting.getWorkflowRecords());
        records.sort(Comparator.comparingInt(WorkflowCompletionRecord::getWorkflowId));
        for (WorkflowCompletionRecord record : records) {
            rows.add(row(normalizePath(record.getWorkflowPath()), record.getStatus(),
                    fmt(record.getStartTime()), fmt(record.getFinishTime()),
                    fmt(record.getCriticalPathTime()), fmt(record.getDeadline()),
                    record.isMetDeadline() ? "YES" : "NO", fmt(record.getAlpha())));
        }
        SimpleXlsxWriter.write(file, "Workflow Completion Summary", rows);
    }

    private void writeTaskSummary(File file) throws IOException {
        List<List<Object>> rows = new ArrayList<>();
        rows.add(taskHeader());
        for (TaskExecutionRecord record : sortedTaskRecords()) rows.add(taskRow(record));
        SimpleXlsxWriter.write(file, "Task Execution Summary", rows);
    }

    private void writeTaskCsv(File file, boolean append) throws IOException {
        boolean writeHeader = !append || !file.exists() || file.length() == 0;
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(file, append), StandardCharsets.UTF_8))) {
            if (writeHeader) writeCsvRow(writer, taskHeader());
            for (TaskExecutionRecord record : sortedTaskRecords()) {
                writeCsvRow(writer, taskRow(record));
            }
        }
    }

    private List<TaskExecutionRecord> sortedTaskRecords() {
        List<TaskExecutionRecord> records = new ArrayList<>(accounting.getTaskRecords());
        records.sort(Comparator
                .comparingInt(TaskExecutionRecord::getWorkflowId)
                .thenComparingInt(TaskExecutionRecord::getTaskId));
        return records;
    }

    private List<Object> taskHeader() {
        return row("Algorithm", "Scenario", "Workflow ID", "Workflow",
                "Workflow Disposition", "Task ID", "Task Name", "Parents ID",
                "Task Cores", "Task RAM (MB)", "Resource Requirement Source",
                "On-Demand Price Per Second",
                "Nominal Runtime mu (s)",
                "Runtime Stddev sigma (s)", "Conservative Runtime cet (s)",
                "Planning Runtime Used (s)", "Actual Runtime Sample (s)",
                "EST (s)", "EFT (s)", "LST (s)", "LFT (s)",
                "Scheduled Start SST (s)", "SubDeadline (s)",
                "Planned VM ID", "Planned VM Type", "Actual VM ID",
                "Actual VM Type", "Provision Order Time (s)",
                "Container Ready Time (s)", "Provisioning Delay (s)",
                "Ready Time (s)", "Submit Time (s)", "Start Time (s)",
                "Finish Time (s)", "Waiting Time (s)", "Queue Delay (s)",
                "Start Deviation from SST (s)",
                "Finish Deviation from SubDeadline (s)", "Rescheduled",
                "Scheduling Reason", "Status", "Deadline Tightness");
    }

    private List<Object> taskRow(TaskExecutionRecord record) {
        boolean submitted = Double.isFinite(record.getSubmitTime());
        return row(algorithm, scenario, record.getWorkflowId(),
                normalizePath(record.getWorkflowPath()),
                record.getWorkflowDisposition(), formatTaskId(record.getTaskId()),
                emptyToUnknown(record.getTaskName()),
                formatParentIds(record.getParentIds()), record.getTaskCores(),
                record.getTaskRamMb(), record.getResourceRequirementSource(),
                fmtRate(HybridVmPool.onDemandPricePerSecond(
                        record.getTaskCores(), record.getTaskRamMb())),
                fmtPrecise(record.getNominalRuntime()),
                fmtPrecise(record.getRuntimeStddev()),
                fmtPrecise(record.getConservativeRuntime()),
                fmtPrecise(record.getPlanningRuntime()),
                fmtPrecise(record.getActualRuntime()),
                fmtPrecise(record.getEarliestStartTime()),
                fmtPrecise(record.getEarliestFinishTime()),
                fmtPrecise(record.getLatestStartTime()),
                fmtPrecise(record.getLatestFinishTime()),
                fmtPrecise(record.getScheduledStartTime()),
                fmtPrecise(record.getSubDeadlineTime()),
                record.getPlannedVmId() == null ? "" : record.getPlannedVmId(),
                record.getPlannedVmType(), submitted ? record.getVmId() : "",
                submitted ? record.getVmType() : "",
                fmtPrecise(record.getProvisioningOrderTime()),
                fmtPrecise(record.getContainerReadyTime()),
                fmtPrecise(record.getProvisioningDelay()),
                fmtPrecise(record.getReadyTime()), fmtPrecise(record.getSubmitTime()),
                fmtPrecise(record.getStartTime()), fmtPrecise(record.getFinishTime()),
                fmtPrecise(record.getWaitingTime()), fmtPrecise(record.getQueueDelay()),
                fmtPrecise(record.getStartDeviation()),
                fmtPrecise(record.getSubDeadlineDeviation()),
                submitted && record.isRescheduled() ? "YES" : "NO",
                record.getSchedulingReason().isEmpty()
                        ? "NOT_DISPATCHED" : record.getSchedulingReason(),
                record.getStatus().isEmpty() ? "PENDING" : record.getStatus(),
                fmtPrecise(record.getDeadlineTightness()));
    }

    private void writeCsvRow(BufferedWriter writer, List<Object> values)
            throws IOException {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) writer.write(',');
            writer.write(csv(String.valueOf(values.get(i) == null ? "" : values.get(i))));
        }
        writer.newLine();
    }

    private String csv(String value) {
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0
                && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) return value;
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private void writeOnDemandUsage(File file) throws IOException {
        List<List<Object>> rows = new ArrayList<>();
        rows.add(row("VM ID", "Launch Time (s)", "Destroy Time (s)", "Uptime (s)"));
        List<OnDemandInstanceRecord> records = new ArrayList<>(accounting.getOnDemandRecords());
        records.sort(Comparator.comparingInt(OnDemandInstanceRecord::getVmId));
        for (OnDemandInstanceRecord record : records) {
            rows.add(row(record.getVmId(),
                    fmt(record.getLaunchTime()),
                    fmt(record.getDestroyTime()),
                    fmt(record.getUptime())));
        }
        List<Double> uptimes = finiteUptimes(records);
        double total = uptimes.stream().mapToDouble(Double::doubleValue).sum();
        double average = uptimes.isEmpty() ? 0.0 : total / uptimes.size();
        rows.add(row("", "", "", ""));
        rows.add(row("SUMMARY", "", "", ""));
        rows.add(row("Average Uptime", "", "", fmt(average)));
        rows.add(row("Total Uptime", "", "", fmt(total)));
        SimpleXlsxWriter.write(file, "On-Demand Instance Usage", rows);
    }

    private List<Double> finiteUptimes(List<OnDemandInstanceRecord> records) {
        List<Double> values = new ArrayList<>();
        for (OnDemandInstanceRecord record : records) {
            if (Double.isFinite(record.getUptime())) values.add(record.getUptime());
        }
        return values;
    }

    private String formatParentIds(List<Integer> parentIds) {
        if (parentIds.isEmpty()) return "[]";
        List<String> ids = new ArrayList<>();
        for (Integer id : parentIds) ids.add(formatTaskId(id));
        return ids.toString();
    }

    private static String formatTaskId(int taskId) {
        return String.format(Locale.US, "ID%05d", taskId);
    }

    private static String normalizePath(String path) {
        return path == null ? "" : path.replace('\\', '/');
    }

    private static String emptyToUnknown(String value) {
        return value == null || value.isEmpty() ? "UNKNOWN" : value;
    }

    private static double safeTime(double value) {
        return Double.isFinite(value) ? value : Double.MAX_VALUE;
    }

    private static String fmt(double value) {
        if (!Double.isFinite(value)) return "";
        return String.format(Locale.US, "%.1f", value);
    }

    private static String fmtPrecise(double value) {
        if (!Double.isFinite(value)) return "";
        return String.format(Locale.US, "%.4f", value);
    }

    private static String fmtRate(double value) {
        if (!Double.isFinite(value)) return "";
        return String.format(Locale.US, "%.8f", value);
    }

    private static List<Object> row(Object... values) {
        List<Object> row = new ArrayList<>();
        for (Object value : values) row.add(value);
        return row;
    }

    private interface SnapshotValue {
        double value(UtilizationSnapshot snapshot);
    }

    private static class Stat {
        final double mean;
        final double min;
        final double max;

        Stat(double mean, double min, double max) {
            this.mean = mean;
            this.min = min;
            this.max = max;
        }

        static Stat fromSnapshots(List<UtilizationSnapshot> snapshots, SnapshotValue value) {
            List<Double> values = new ArrayList<>();
            for (UtilizationSnapshot snapshot : snapshots) values.add(value.value(snapshot));
            return fromValues(values);
        }

        static Stat fromValues(List<Double> values) {
            if (values.isEmpty()) return new Stat(0.0, 0.0, 0.0);
            double sum = 0.0;
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            for (double value : values) {
                sum += value;
                min = Math.min(min, value);
                max = Math.max(max, value);
            }
            return new Stat(sum / values.size(), min, max);
        }
    }

    /** Minimal single-sheet XLSX writer using inline strings and no styling. */
    private static class SimpleXlsxWriter {
        static void write(File file, String sheetName, List<List<Object>> rows) throws IOException {
            try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
                entry(zip, "[Content_Types].xml", contentTypes());
                entry(zip, "_rels/.rels", packageRels());
                entry(zip, "xl/workbook.xml", workbook(sheetName));
                entry(zip, "xl/_rels/workbook.xml.rels", workbookRels());
                entry(zip, "xl/worksheets/sheet1.xml", worksheet(rows));
            }
        }

        private static String contentTypes() {
            return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                    + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                    + "</Types>";
        }

        private static String packageRels() {
            return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                    + "</Relationships>";
        }

        private static String workbook(String sheetName) {
            return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                    + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                    + "<sheets><sheet name=\"" + xml(sheetName)
                    + "\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>";
        }

        private static String workbookRels() {
            return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                    + "</Relationships>";
        }

        private static String worksheet(List<List<Object>> rows) {
            StringBuilder sb = new StringBuilder();
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
            sb.append("<sheetData>");
            for (int r = 0; r < rows.size(); r++) {
                sb.append("<row r=\"").append(r + 1).append("\">");
                List<Object> row = rows.get(r);
                for (int c = 0; c < row.size(); c++) {
                    Object value = row.get(c);
                    String cell = columnName(c + 1) + (r + 1);
                    if (value instanceof Number) {
                        sb.append("<c r=\"").append(cell).append("\"><v>")
                                .append(value).append("</v></c>");
                    } else {
                        sb.append("<c r=\"").append(cell).append("\" t=\"inlineStr\"><is><t>")
                                .append(xml(String.valueOf(value == null ? "" : value)))
                                .append("</t></is></c>");
                    }
                }
                sb.append("</row>");
            }
            sb.append("</sheetData></worksheet>");
            return sb.toString();
        }

        private static void entry(ZipOutputStream zip, String name, String content)
                throws IOException {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        private static String columnName(int index) {
            StringBuilder sb = new StringBuilder();
            while (index > 0) {
                index--;
                sb.insert(0, (char) ('A' + (index % 26)));
                index /= 26;
            }
            return sb.toString();
        }

        private static String xml(String value) {
            return value.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("'", "&apos;");
        }
    }
}
