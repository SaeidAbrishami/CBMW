package org.workflowsim.cbmw;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Loads exact workflow arrivals from a scenario-specific JSON manifest.
 *
 * For each entry in the JSON it:
 *   1. Resolves exactly one matching .xml/.txt pair recursively
 *   2. Computes the critical path directly from the XML runtime attributes
 *   3. Sets deadline = arrivalTime + criticalPath * tightness + on-demand
 *      provisioning delay. The delay is included for every workflow, whether
 *      or not any task is eventually assigned to on-demand capacity.
 *
 * The returned list is sorted by arrival time.
 */
public class WorkflowLoader {

    public static final String DATASET_MODE_PROPERTY = "cbmw.workflow.dataset.mode";

    /** Supported views of the flat 500-workflow dataset. */
    public enum DatasetMode {
        /** All workflows, preserving the manifest arrival times. */
        FULL_500,
        /** 200 workflows with their already-adjusted manifest arrival times. */
        EDGE_200;

        public static DatasetMode parse(String value) {
            if (value == null || value.trim().isEmpty()) return FULL_500;
            try {
                return DatasetMode.valueOf(value.trim().toUpperCase(Locale.US));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(DATASET_MODE_PROPERTY
                        + " must be FULL_500 or EDGE_200, not: " + value, e);
            }
        }

    }

    private static final int FULL_DATASET_SIZE = 500;

    public static List<WorkflowArrivalData> load(String workflowDir, double tightness) throws Exception {
        return load(workflowDir, "poisson_distribution.json", tightness);
    }

    public static List<WorkflowArrivalData> load(String workflowDir, String jsonFile, double tightness) throws Exception {
        DatasetMode mode = DatasetMode.parse(System.getProperty(
                DATASET_MODE_PROPERTY, DatasetMode.FULL_500.name()));
        return load(workflowDir, jsonFile, tightness, mode);
    }

    public static List<WorkflowArrivalData> load(String workflowDir,
                                                  String jsonFile,
                                                  double tightness,
                                                  DatasetMode mode) throws Exception {
        if (!Double.isFinite(tightness) || tightness <= 0.0) {
            throw new IllegalArgumentException("tightness must be finite and positive");
        }
        if (mode == null) throw new IllegalArgumentException("dataset mode is required");

        File root = new File(workflowDir).getCanonicalFile();
        if (!root.isDirectory()) {
            throw new IllegalArgumentException(
                    "Workflow directory does not exist: " + root);
        }
        File manifest = new File(root, jsonFile).getCanonicalFile();
        if (!manifest.isFile()) {
            throw new IllegalArgumentException(
                    "Workflow arrival manifest does not exist: " + manifest);
        }

        Map<String, Double> arrivalMap = parseJson(manifest.getPath());
        DatasetFiles datasetFiles = DatasetFiles.index(root);
        Map<String, File> xmlByName = validateDataset(
                root, arrivalMap.keySet(), datasetFiles, mode);

        List<WorkflowArrivalData> fullDataset = new ArrayList<>();
        for (Map.Entry<String, Double> entry : arrivalMap.entrySet()) {
            String name        = entry.getKey();
            double arrivalTime = entry.getValue();
            File xmlFile       = xmlByName.get(name);
            String xmlPath     = xmlFile.getPath();

            double cp       = computeCriticalPath(xmlPath);
            double deadline = arrivalTime + cp * tightness
                    + HybridVmPool.ON_DEMAND_PROVISIONING_DELAY;
            fullDataset.add(new WorkflowArrivalData(xmlPath, arrivalTime, deadline));
        }

        fullDataset.sort(Comparator.comparingDouble(WorkflowArrivalData::getArrivalTime)
                .thenComparing(WorkflowArrivalData::getDaxPath));
        List<WorkflowArrivalData> result = fullDataset;
        System.out.println("[WorkflowLoader] Loaded " + result.size()
                + " workflow arrivals mode=" + mode
                + " (span: " + String.format("%.0f", result.isEmpty() ? 0
                        : result.get(result.size() - 1).getArrivalTime()) + "s)");
        return result;
    }

    private static Map<String, File> validateDataset(File root,
                                                      Set<String> manifestNames,
                                                      DatasetFiles files, DatasetMode mode)
            throws Exception {
        int expectedCount = mode == DatasetMode.FULL_500 ? FULL_DATASET_SIZE : 200;
        if (manifestNames.size() != expectedCount) {
            throw new IllegalArgumentException("Arrival manifest must contain exactly"
                    + " " + expectedCount + " unique workflows, found "
                    + manifestNames.size());
        }

        Map<String, File> xmlByName = new HashMap<>();
        for (String name : manifestNames) {
            File xml = exactlyOne(files.xmlByName.get(name), name, ".xml");
            File txt = exactlyOne(files.txtByName.get(name), name, ".txt");
            if (!xml.getParentFile().getCanonicalFile().equals(
                    txt.getParentFile().getCanonicalFile())) {
                throw new IllegalArgumentException("Workflow XML/TXT pair must be in"
                        + " the same directory: " + name);
            }
            if (!xml.getParentFile().getCanonicalFile().equals(root)) {
                throw new IllegalArgumentException("Workflow XML/TXT pair must be"
                        + " stored directly in the workflow directory: " + name);
            }
            xmlByName.put(name, xml);
        }

        if (mode == DatasetMode.FULL_500) {
            rejectUnlisted(files.xmlByName.keySet(), manifestNames, ".xml");
            rejectUnlisted(files.txtByName.keySet(), manifestNames, ".txt");
        }
        return xmlByName;
    }

    private static File exactlyOne(List<File> matches, String name,
                                   String extension) {
        int count = matches == null ? 0 : matches.size();
        if (count != 1) {
            throw new IllegalArgumentException("Workflow " + name + " must have"
                    + " exactly one " + extension + " file, found " + count);
        }
        return matches.get(0);
    }

    private static void rejectUnlisted(Set<String> indexedNames,
                                       Set<String> manifestNames,
                                       String extension) {
        Set<String> extras = new HashSet<>(indexedNames);
        extras.removeAll(manifestNames);
        if (!extras.isEmpty()) {
            List<String> sorted = new ArrayList<>(extras);
            Collections.sort(sorted);
            throw new IllegalArgumentException("Found " + extension
                    + " files not listed in the manifest: " + sorted);
        }
    }

    private static final class DatasetFiles {
        final Map<String, List<File>> xmlByName = new HashMap<>();
        final Map<String, List<File>> txtByName = new HashMap<>();

        static DatasetFiles index(File root) {
            DatasetFiles result = new DatasetFiles();
            result.indexRecursively(root);
            return result;
        }

        private void indexRecursively(File directory) {
            File[] children = directory.listFiles();
            if (children == null) {
                throw new IllegalArgumentException(
                        "Could not list workflow directory: " + directory);
            }
            for (File child : children) {
                if (child.isDirectory()) {
                    indexRecursively(child);
                    continue;
                }
                String lower = child.getName().toLowerCase(Locale.US);
                if (lower.endsWith(".xml")) {
                    add(xmlByName, baseName(child.getName()), child);
                } else if (lower.endsWith(".txt")) {
                    add(txtByName, baseName(child.getName()), child);
                }
            }
        }

        private static void add(Map<String, List<File>> index,
                                String name, File file) {
            index.computeIfAbsent(name, unused -> new ArrayList<>()).add(file);
        }

        private static String baseName(String filename) {
            return filename.substring(0, filename.lastIndexOf('.'));
        }
    }

    // -----------------------------------------------------------------------
    // JSON parsing — handles the flat "key": value structure without a library
    // -----------------------------------------------------------------------

    private static Map<String, Double> parseJson(String path) throws Exception {
        String json = new String(Files.readAllBytes(new File(path).toPath()),
                StandardCharsets.UTF_8).trim();
        if (json.startsWith("[")) return parseArrivalArray(json);
        Map<String, Double> map = new LinkedHashMap<>();
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.startsWith("\"")) continue;
                int keyEnd = line.indexOf('"', 1);
                if (keyEnd < 0) continue;
                String key = line.substring(1, keyEnd);
                int colon = line.indexOf(':', keyEnd);
                if (colon < 0) continue;
                String valStr = line.substring(colon + 1).trim().replaceAll("[,}\\s]", "");
                if (valStr.isEmpty()) continue;
                double value = Double.parseDouble(valStr);
                if (!Double.isFinite(value) || value < 0.0) {
                    throw new IllegalArgumentException(
                            "Arrival time must be finite and non-negative for " + key);
                }
                if (map.put(key, value) != null) {
                    throw new IllegalArgumentException(
                            "Duplicate workflow in arrival manifest: " + key);
                }
            }
        }
        if (map.isEmpty()) {
            throw new IllegalArgumentException("Arrival manifest is empty: " + path);
        }
        return map;
    }

    /** Strict parser for arrays of workflow_name/arrival_time_seconds records.
     * Filenames are local basenames; escapes and nested paths are intentionally rejected.
     */
    private static Map<String, Double> parseArrivalArray(String json) {
        if (!json.endsWith("]")) throw new IllegalArgumentException("Malformed arrival array");
        String body = json.substring(1, json.length() - 1).trim();
        Map<String, Double> arrivals = new LinkedHashMap<>();
        Pattern object = Pattern.compile("\\{([^{}]*)\\}");
        Pattern field = Pattern.compile("\\s*\"(workflow_name|arrival_time_seconds)\"\\s*:\\s*(\"[^\"\\\\]*\"|-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)\\s*");
        Matcher objects = object.matcher(body);
        int end = 0;
        while (objects.find()) {
            String separator = body.substring(end, objects.start()).trim();
            if (!separator.equals(arrivals.isEmpty() ? "" : ","))
                throw new IllegalArgumentException("Malformed arrival array separator");
            String[] fields = objects.group(1).split(",", -1);
            if (fields.length != 2) throw new IllegalArgumentException("Expected two arrival fields");
            String name = null;
            Double time = null;
            for (String item : fields) {
                Matcher match = field.matcher(item);
                if (!match.matches()) throw new IllegalArgumentException("Invalid arrival field: " + item);
                String value = match.group(2);
                if (match.group(1).equals("workflow_name")) {
                    if (name != null || !value.startsWith("\""))
                        throw new IllegalArgumentException("Invalid workflow_name");
                    name = value.substring(1, value.length() - 1);
                } else {
                    if (time != null || value.startsWith("\""))
                        throw new IllegalArgumentException("Invalid arrival_time_seconds");
                    time = Double.parseDouble(value);
                }
            }
            if (name == null || !name.endsWith(".xml") || name.contains("/")
                    || name.contains("\\") || name.contains(":") || name.length() <= 4)
                throw new IllegalArgumentException("Expected local XML workflow_name: " + name);
            if (time == null || !Double.isFinite(time) || time < 0.0)
                throw new IllegalArgumentException("Arrival time must be finite and non-negative for " + name);
            String key = name.substring(0, name.length() - 4);
            if (arrivals.put(key, time) != null)
                throw new IllegalArgumentException("Duplicate workflow in arrival manifest: " + name);
            end = objects.end();
        }
        if (arrivals.isEmpty() || !body.substring(end).trim().isEmpty())
            throw new IllegalArgumentException("Empty or malformed arrival array");
        return arrivals;
    }

    // -----------------------------------------------------------------------
    // Critical path computation from raw XML runtime attributes
    //
    // Matches NegotiationModule.computeCriticalPath():
    //   WorkflowParser sets cloudletLength = runtime * 1000
    //   NegotiationModule computes execTime = cloudletLength / RESERVED_MIPS (1000)
    //   => execTime == runtime (seconds)
    // So using the raw runtime attribute values here gives the same CP.
    // -----------------------------------------------------------------------

    private static double computeCriticalPath(String xmlPath) throws Exception {
        Document doc = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder().parse(new File(xmlPath));
        doc.getDocumentElement().normalize();

        // Collect runtimes per job id
        Map<String, Double> runtimes = new HashMap<>();
        NodeList jobs = doc.getElementsByTagName("job");
        for (int i = 0; i < jobs.getLength(); i++) {
            Element job = (Element) jobs.item(i);
            String id           = job.getAttribute("id");
            String runtimeAttr  = job.getAttribute("runtime");
            // Mirror WorkflowParser's 100 MI floor (100 / 1000 MIPS = 0.1s)
            double runtime = runtimeAttr.isEmpty()
                    ? 0.1 : Math.max(Double.parseDouble(runtimeAttr), 0.1);
            runtimes.put(id, runtime);
        }

        // Build parent -> children adjacency from <child ref> / <parent ref> elements
        Map<String, List<String>> children = new HashMap<>();
        for (String id : runtimes.keySet()) children.put(id, new ArrayList<>());

        NodeList childNodes = doc.getElementsByTagName("child");
        for (int i = 0; i < childNodes.getLength(); i++) {
            Element childElem = (Element) childNodes.item(i);
            String childId    = childElem.getAttribute("ref");
            NodeList parents  = childElem.getElementsByTagName("parent");
            for (int j = 0; j < parents.getLength(); j++) {
                String parentId = ((Element) parents.item(j)).getAttribute("ref");
                List<String> list = children.get(parentId);
                if (list != null) list.add(childId);
            }
        }

        // Bottom-up DP: cp(node) = runtime(node) + max(cp(child))
        Map<String, Double> memo = new HashMap<>();
        double cp = 0.0;
        for (String id : runtimes.keySet()) {
            double rank = cpFrom(id, runtimes, children, memo);
            if (rank > cp) cp = rank;
        }
        return cp;
    }

    private static double cpFrom(String id, Map<String, Double> runtimes,
                                   Map<String, List<String>> children,
                                   Map<String, Double> memo) {
        if (memo.containsKey(id)) return memo.get(id);
        double maxChild = 0.0;
        for (String childId : children.getOrDefault(id, Collections.emptyList())) {
            double v = cpFrom(childId, runtimes, children, memo);
            if (v > maxChild) maxChild = v;
        }
        double result = runtimes.get(id) + maxChild;
        memo.put(id, result);
        return result;
    }
}
