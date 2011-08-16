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
package com.ebay.spine.vmware.services;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.net.URL;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.apache.log4j.Logger;

import com.ebay.spine.vmware.model.VirtualMachineWrapper;
import com.vmware.vim25.RuntimeFault;
import com.vmware.vim25.mo.Folder;
import com.vmware.vim25.mo.InventoryNavigator;
import com.vmware.vim25.mo.ManagedEntity;
import com.vmware.vim25.mo.ServiceInstance;
import com.vmware.vim25.mo.VirtualMachine;

/**
 * Keeping the state of the underlying VCenter server using VSphere WS. Assume
 * this is the only process accessing the VMs = there is no locks on the VMs,
 * and it will not prevent someone from powering off a VM while a snapshot runs
 * on it.
 * 
 * The 
 * 
 */
public class VirtualMachinePool {

	private static final Logger log = Logger.getLogger(VirtualMachinePool.class);

	// credential to access VCenter
	private static String userId;
	private static String pass;
	private static String url;
	private static ServiceInstance si = null;
	private static VirtualMachinePool instance = null;
	// VMs do not change = restart the grid to pickup newly added VMs
	private static boolean cacheVms = true;

	public static synchronized VirtualMachinePool getInstance() {
		if (instance == null) {
			instance = new VirtualMachinePool();
		}
		return instance;
	}

	private VirtualMachinePool() {
		createConnection();
	}

	

	/**
	 * Return the VM with the given uuid.
	 * 
	 * @param uuid
	 * @return the VM with the given id. Throws a RTE if the uuid doesn't exist.
	 */
	public VirtualMachineWrapper getVMById(String uuid) {
		for (VirtualMachineWrapper vm : getAllVM()) {
			if (vm.getId().equals(uuid)) {
				return vm;
			}
		}
		throw new RuntimeException("Cannot find the VM with id " + uuid);
	}

	List<VirtualMachineWrapper> vmsCache = null;

	/**
	 * gets all the VM that will be usable as grid nodes.
	 * 
	 * @return
	 */
	public List<VirtualMachineWrapper> getAllNodeVMs() {
		List<VirtualMachineWrapper> res = new ArrayList<VirtualMachineWrapper>();
		List<VirtualMachineWrapper> all = getAllVM();
		// convention = nodes name starts with spine-linux or spine-win
		for (VirtualMachineWrapper vm : all) {
			if (vm.getName() != null && (vm.getName().startsWith("spine-linux") || vm.getName().startsWith("spine-win"))) {
				res.add(vm);
			}
		}
		return res;

	}

	public List<VirtualMachineWrapper> getAllVM() {
		if (cacheVms) {
			if (vmsCache == null) {
				synchronized (this) {
					if (vmsCache == null) {
						vmsCache = loadAllVms();
					}
				}
			}
			return vmsCache;
		} else {
			return loadAllVms();
		}
	}

	/**
	 * create the connection to VCenter and keeps it open.
	 */
	private static void createConnection() {
		Properties properties = new Properties();

		try {
			File f = new File("credentials.properties");
			if (!f.exists()) {
				String s = "You need a valid ESX login pass specified in credentials.properties";
				log.error(s);
				throw new RuntimeException(s);
			}
			Reader rdr = new FileReader(f);
			properties.load(rdr);
			userId = properties.getProperty("login");
			pass = properties.getProperty("pass");
			url = properties.getProperty("url");
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		try {
			si = new ServiceInstance(new URL(url), userId, pass, true);
			// horrible way to avoid timeout.
			new Thread(new Runnable() {
				public void run() {
					try {
						while (true) {
							Thread.sleep(60 * 1000);
							si.currentTime();
						}
					} catch (RuntimeFault e) {
						e.printStackTrace();
					} catch (RemoteException e) {
						e.printStackTrace();
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
			}).start();

		} catch (Throwable e) {
			throw new RuntimeException("error connecting to the ESX server" + e.getMessage(), e);
		}
	}

	/**
	 * uses VSphere WS to get the VM list.
	 * 
	 * @return all the VMs on the server. Filtering = starts with "spine-win"
	 */
	private ArrayList<VirtualMachineWrapper> loadAllVms() {
		ArrayList<VirtualMachineWrapper> res = new ArrayList<VirtualMachineWrapper>();
		Folder rootFolder = si.getRootFolder();
		long start = System.currentTimeMillis();
		ManagedEntity[] mes;
		try {
			mes = new InventoryNavigator(rootFolder).searchManagedEntities("VirtualMachine");
		} catch (Throwable e) {
			throw new RuntimeException("Error", e);
		}
		if (mes != null && mes.length != 0) {
			for (int i = 0; i < mes.length; i++) {
				VirtualMachine vm = (VirtualMachine) mes[i];

				try {
					String name = vm.getConfig().getName();
					if (!(name.contains("repo") || name.contains("dderwael"))) {
						res.add(new VirtualMachineWrapper(vm));
					} else {
						log.debug("VM " + vm.getConfig().getName() + " " + vm.getConfig().getUuid() + " filtered out.");
					}
				} catch (Throwable t) {
					// TODO : freynaud case if the VM is being cloned.
				}

			}
		}
		log.info("Loading VMs :" + (System.currentTimeMillis() - start) / 1000 + "sec.");
		return res;
	}

}
