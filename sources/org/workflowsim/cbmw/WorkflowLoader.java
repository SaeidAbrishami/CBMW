package org.workflowsim.cbmw;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Loads workflow arrivals from a pre-computed poisson_distribution.json file.
 *
 * For each entry in the JSON it:
 *   1. Resolves the matching .xml file in the workflow directory
 *   2. Computes the critical path directly from the XML runtime attributes
 *   3. Sets deadline = arrivalTime + criticalPath * tightness
 *
 * The returned list is sorted by arrival time.
 */
public class WorkflowLoader {

    public static List<WorkflowArrivalData> load(String workflowDir, double tightness) throws Exception {
        return load(workflowDir, "poisson_distribution.json", tightness);
    }

    public static List<WorkflowArrivalData> load(String workflowDir, String jsonFile, double tightness) throws Exception {
        String jsonPath = workflowDir + File.separator + jsonFile;
        Map<String, Double> arrivalMap = parseJson(jsonPath);

        List<WorkflowArrivalData> result = new ArrayList<>();
        for (Map.Entry<String, Double> entry : arrivalMap.entrySet()) {
            String name        = entry.getKey();
            double arrivalTime = entry.getValue();
            String xmlPath     = workflowDir + File.separator + name + ".xml";

            if (!new File(xmlPath).exists()) {
                System.out.println("[WorkflowLoader] Skipping missing file: " + xmlPath);
                continue;
            }

            double cp       = computeCriticalPath(xmlPath);
            double deadline = arrivalTime + cp * tightness;
            result.add(new WorkflowArrivalData(xmlPath, arrivalTime, deadline));
        }

        result.sort(Comparator.comparingDouble(WorkflowArrivalData::getArrivalTime));
        System.out.println("[WorkflowLoader] Loaded " + result.size() + " workflow arrivals"
                + " (span: " + String.format("%.0f", result.isEmpty() ? 0
                        : result.get(result.size() - 1).getArrivalTime()) + "s)");
        return result;
    }

    // -----------------------------------------------------------------------
    // JSON parsing — handles the flat "key": value structure without a library
    // -----------------------------------------------------------------------

    private static Map<String, Double> parseJson(String path) throws Exception {
        Map<String, Double> map = new HashMap<>();
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
                map.put(key, Double.parseDouble(valStr));
            }
        }
        return map;
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
