package org.workflowsim.cbmw;

import java.util.AbstractList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import org.cloudbus.cloudsim.Cloudlet;

/** Ready cloudlets in arrival order with constant-time removal by task ID. */
public final class IndexedCloudletQueue extends AbstractList<Cloudlet> {

    private final LinkedHashMap<Integer, Cloudlet> cloudlets = new LinkedHashMap<>();

    @Override
    public int size() {
        return cloudlets.size();
    }

    @Override
    public Cloudlet get(int index) {
        if (index < 0 || index >= size()) throw new IndexOutOfBoundsException();
        Iterator<Cloudlet> it = cloudlets.values().iterator();
        for (int i = 0; i < index; i++) it.next();
        return it.next();
    }

    @Override
    public void add(int index, Cloudlet cloudlet) {
        if (index != size()) {
            throw new UnsupportedOperationException("Ready cloudlets can only be appended");
        }
        cloudlets.put(cloudlet.getCloudletId(), cloudlet);
    }

    @Override
    public boolean remove(Object cloudlet) {
        if (!(cloudlet instanceof Cloudlet)) return false;
        int taskId = ((Cloudlet) cloudlet).getCloudletId();
        if (cloudlets.get(taskId) != cloudlet) return false;
        cloudlets.remove(taskId);
        return true;
    }

    @Override
    public Iterator<Cloudlet> iterator() {
        return cloudlets.values().iterator();
    }
}
