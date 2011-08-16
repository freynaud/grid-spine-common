/*
Copyright eBay Inc., Spine authors, and other contributors.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
 */

package com.ebay.spine.vmware.model;

import java.util.ArrayList;
import java.util.List;

import org.apache.log4j.Logger;
import org.openqa.grid.internal.GridException;

import com.ebay.spine.vmware.services.VIX;
import com.vmware.vim25.VirtualMachineConfigInfo;
import com.vmware.vim25.VirtualMachineSnapshotTree;
import com.vmware.vim25.mo.Task;
import com.vmware.vim25.mo.VirtualMachine;
import com.vmware.vim25.mo.VirtualMachineSnapshot;

/**
 * Wrapper around a VMWare VM object that removes the lazy loading for the non
 * mutable field for a grid.
 * 
 * @author Francois Reynaud
 * 
 */
public class VirtualMachineWrapper implements Comparable<VirtualMachineWrapper> {

	private static final Logger log = Logger.getLogger(VirtualMachineWrapper.class);
	// the underlying VMWare object. pretty much everything in it is lazy
	// loaded.
	private VirtualMachine vm;

	private String id;
	private String name;
	// the VMWare vmx file. Useless for VSphere WS but mandatory for VIX.
	private String configPath;
	private String ip;

	private String shortStatus = "undefined.";
	private List<SnapshotWrapper> snapshots = null;

	// access to VMWare tools.
	private VIX VIXService;

	/**
	 * Creating a VM object containing the very basic info about a VM, id, name
	 * Storing that in the object rather than lazy loading it using the vSphere
	 * API as it is not changing.
	 * 
	 * @param vm
	 */
	public VirtualMachineWrapper(VirtualMachine vm) {
		VirtualMachineConfigInfo vminfo = vm.getConfig();
		id = vminfo.getUuid();
		name = vminfo.getName();
		configPath = vminfo.getFiles().getVmPathName();
		this.vm = vm;
	}

	/**
	 * Load the snapshots basic details.
	 * 
	 * @return
	 */
	private void loadSnapshots() {
		snapshots = new ArrayList<SnapshotWrapper>();
		List<VirtualMachineSnapshotTree> all = getAllSnapshots(vm);
		for (VirtualMachineSnapshotTree node : all) {
			SnapshotWrapper ss = new SnapshotWrapper(this, new VirtualMachineSnapshot(vm.getServerConnection(), node.getSnapshot()));
			ss.setName(node.getName());
			snapshots.add(ss);
		}
	}

	/**
	 * By default,the snapshots are in a tree, not a list. Parsing the tree and
	 * dumping the snapshots in a list.
	 * 
	 * @param vm
	 * @return
	 */
	private List<VirtualMachineSnapshotTree> getAllSnapshots(VirtualMachine vm) {
		List<VirtualMachineSnapshotTree> all = new ArrayList<VirtualMachineSnapshotTree>();

		VirtualMachineSnapshotTree[] nodes = vm.getSnapshot().getRootSnapshotList();
		for (int i = 0; i < nodes.length; i++) {
			all.add(nodes[i]);
			addAllDescendant(nodes[i], all);
		}
		return all;
	}

	/**
	 * recursive method to parse the tree and get all the snapshots.
	 * 
	 * @param snapshotNode
	 * @param all
	 */
	private void addAllDescendant(VirtualMachineSnapshotTree snapshotNode, List<VirtualMachineSnapshotTree> all) {
		VirtualMachineSnapshotTree[] children = snapshotNode.getChildSnapshotList();
		if (children == null) {
			return;
		}
		for (int i = 0; i < children.length; i++) {
			all.add(children[i]);
			addAllDescendant(children[i], all);
		}
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getConfigPath() {
		return configPath;
	}

	public void setConfigPath(String configPath) {
		this.configPath = configPath;
	}

	public String getShortStatus() {
		return shortStatus;
	}

	public void setShortStatus(String shortStatus) {
		this.shortStatus = shortStatus;
	}

	/**
	 * return the snapshot with the given name. Multiple snapshots can have the
	 * same name. If that's the case, the first one with the name matching is
	 * returned
	 * 
	 * @param name
	 *            exact name for the snapshot. Case sensitive.
	 * @return the snapshot. Throws a SpineException is no snap
	 */
	public SnapshotWrapper getSnapshot(String name) {
		for (SnapshotWrapper current : getSnapshots()) {
			if (current.getName().equals(name)) {
				return current;
			}
		}
		throw new GridException("Cannot find snapshot " + name + " on VM " + getId());
	}

	/**
	 * 
	 * @param name
	 * @return true if the snapshot exists at least once. False otherwise.
	 */
	public boolean snapshotExists(String name) {
		for (SnapshotWrapper current : getSnapshots()) {
			if (current.getName().equals(name)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * get a list of snapshots.The list of snapshots is cached.
	 * 
	 * @return
	 */
	public List<SnapshotWrapper> getSnapshots() {
		if (snapshots == null) {
			synchronized (this) {
				if (snapshots == null) {
					loadSnapshots();
				}
			}
		}
		return snapshots;
	}

	public VIX getVIXService() {
		if (VIXService == null) {
			VIXService = new VIX(this);
		}
		return VIXService;
	}

	/**
	 * get the IP of the guest using VIX API. Not great, often return the wrong
	 * IP. Try to use something else if possible.
	 * 
	 * @return
	 */
	public String getIp() {
		if (ip == null || "".equalsIgnoreCase(ip.trim())) {
			VIX vix = getVIXService();
			if (vix != null) {
				ip = vix.getGuestIP();
			}
		}
		return ip;
	}

	public void setIp(String ip) {
		this.ip = ip;
	}

	@Override
	public String toString() {
		return name + "(" + id + " , " + configPath + ")" + getSnapshots().size() + " snapshots\n";
	}

	/**
	 * Create a snapshot with with the given name. Snapshots the memory.
	 * 
	 * @param snapshotName
	 */
	public void createSnapshot(String snapshotName) {
		try {
			Task task = vm.createSnapshot_Task(snapshotName, "snapshot by EUQE Spine", true, false);
			String s = task.waitForTask();
			if (!Task.SUCCESS.equals(s)) {
				log.error("Error creating the snapshot " + snapshotName + " -> " + s);
				throw new GridException("Error creating the snapshot " + snapshotName + " -> " + s);
			}
			// new snapshot created. Forcing a reload.
			snapshots = null;
		} catch (Throwable e) {
			log.error("Exception creating the snapshot " + snapshotName + " : " + e.getMessage());
			throw new GridException("Exception creating the snapshot " + snapshotName + " : " + e.getMessage());
		}
	}

	/**
	 * Revert to the first snapshot with the given name. Retries several times
	 * before giving up and throwing a GridException.
	 * 
	 * @param name
	 *            case sensitive.
	 * 
	 */
	public void revertToSnapshot(String name) {
		// the VIX connection becomes invalid after a snapshot is restored.
		if (VIXService != null) {
			VIXService.release();

		}
		VIXService = null;

		SnapshotWrapper s = getSnapshot(name);
		boolean reverted = false;
		int tries = 0;
		int maxTries = 5;
		while (!reverted && tries <= maxTries) {
			try {
				s.revert();
				reverted = true;
			} catch (GridException e) {
				tries++;
				try {
					Thread.sleep(5000);
				} catch (InterruptedException e1) {
					e1.printStackTrace();
				}
			}
		}
		if (!reverted) {
			throw new GridException("Failed to revert to snapshot " + name + " after " + tries + " tries.");
		}
	}

	public int compareTo(VirtualMachineWrapper o) {
		if (o != null) {
			return this.getName().compareTo(o.getName());
		} else {
			return 0;
		}
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((id == null) ? 0 : id.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		VirtualMachineWrapper other = (VirtualMachineWrapper) obj;
		if (id == null) {
			if (other.id != null)
				return false;
		} else if (!id.equals(other.id))
			return false;
		return true;
	}

}
