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
package com.ebay.spine.vmware;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;
import java.util.Properties;

import org.apache.log4j.Logger;
import org.openqa.grid.common.RegistrationRequest;
import org.openqa.grid.internal.GridException;
import org.openqa.grid.internal.Registry;
import org.openqa.grid.internal.TestSession;
import org.openqa.grid.internal.listeners.TestSessionListener;
import org.openqa.grid.selenium.proxy.WebDriverRemoteProxy;

import com.ebay.spine.vmware.model.VirtualMachineWrapper;
import com.ebay.spine.vmware.services.VirtualMachinePool;

/**
 * Proxy that uses a VMWare virtual machine as a host for the OS where the
 * remote control server lives.
 * 
 * This proxy increases the maintenance/configuration potential of the grid as
 * the grid can now reconfigure, restart the host ( using VSphere API ), launch
 * processes, copy files etc ( VIX ).
 * 
 * 
 * This default version of a VM abled proxy has the ability to revert to a clean
 * snapshot every time a certain number of tests has run of the server.
 * 
 * Configured using the following parameters :
 * 
 * -maxTestBeforeClean=X
 * 
 * -cleanSnapshot=Y
 * 
 * @author freynaud
 * 
 */
public class VirtualMachineRemoteProxy extends WebDriverRemoteProxy implements TestSessionListener {

	private static final Logger log = Logger.getLogger(VirtualMachineRemoteProxy.class);

	// the VM this proxy will control
	private VirtualMachineWrapper vm;

	// the current number of tests this proxy has run since the VM restarted for
	// the last time
	protected int totalTestStarted = 0;
	protected int totalTestFinished = 0;

	// number of test that can be run before the VM has to restart.Default to -1
	// => no revert
	protected int maxTestBeforeClean = -1;

	// name of the VM to revert to when maxTestBeforeClean is reached.
	private String cleanState = null;

	private boolean restarting = false;

	/**
	 * configure a new proxy for a node. The proxy points to a VM identified by
	 * its VMWare id.
	 * 
	 * @param server
	 * @param registry
	 */
	public VirtualMachineRemoteProxy(RegistrationRequest server, Registry registry) {
		super(server, registry);

		String uuid = (String) server.getConfiguration().get("vm");

		if (uuid != null) {
			vm = VirtualMachinePool.getInstance().getVMById(uuid);
			super.setId(uuid);
		} else {
			throw new RuntimeException("need a id for the VM");
		}
		configureRestoreStrategy();
		
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
			} catch (IOException e) {
			throw new RuntimeException(e);
		}

	}

	/**
	 * analyse the parameter passed to define the restore strategy to be used by
	 * the grid. Each proxy can have its own strategy.
	 * 
	 * If the parameters are invalid or not specified, the defaults will be
	 * loaded = the VM will never be restarted.
	 */
	private void configureRestoreStrategy() {
		try {
			maxTestBeforeClean = findMaxTestBeforeClean();

			if (maxTestBeforeClean >= 1) {
				String cleanSnapshotName = (String) getConfig().get("cleanSnapshot");
				if (cleanSnapshotName == null) {
					throw new Exception("cleanSnapshot param not specified.");
				}
				if (!vm.snapshotExists(cleanSnapshotName)) {
					throw new Exception("cleansnapshot with name " + cleanSnapshotName + " doesn't match any snapshot on this VM");
				}
				this.cleanState = cleanSnapshotName;
				log.debug("the VM will revert to " + cleanState + " every " + maxTestBeforeClean + " tests.");
			}
		} catch (Exception e) {
			log.warn("Failure to configure the restore strategy. Reverting to default (no restore, VM never restarted )" + e.getMessage());
			maxTestBeforeClean = -1;
			cleanState = null;
		}
	}

	/**
	 * Finding the max number of tests to be run on this VM before it is
	 * restarted. Using the value of the maxTestBeforeClean param.
	 * 
	 * @return
	 */
	private int findMaxTestBeforeClean() {
		try {
			return (Integer) getConfig().get("maxTestBeforeClean");
		} catch (NumberFormatException e) {
			throw new GridException(e.getMessage());
		}
	}

	/**
	 * Reverting the VM to the clean state defined at startup. No check for
	 * currently running tests.
	 */
	protected void revertToCleanState() {
		log.debug(getVm().getName() + "REVERTING TO CLEAN STATE");
		getRegistry().add(this);
		log.debug(getVm().getName() + "CLEAN STATE RESTORED");

	};

	protected void hasRestarted() {
		restarting = false;
	}

	/**
	 * check the totalReserved counter and doesn't allow a test to start if the
	 * limit has been reached = the node is due for restart.
	 */
	@Override
	public TestSession getNewSession(Map<String, Object> requestedCapability) {
		synchronized (this) {
			if (restarting) {
				return null;
			}
			if (maxTestBeforeClean > 0 && totalTestStarted >= maxTestBeforeClean) {
				return null;
			}
			TestSession session = super.getNewSession(requestedCapability);
			if (session != null) {
				totalTestStarted++;
			}
			return session;
		}
	}

	@Override
	public void beforeRelease(TestSession session) {
		try {
			super.beforeRelease(session);
		} catch (Throwable e) {
			log.warn("error releasing the session " + session + " that timed out.TODO : Corruption event");
			session.terminate();
		}
	}

	/**
	 * release and restore the clean state if maxTestBeforeClean has been
	 * reached.That's where the tricky bits are.
	 */
	@Override
	public void afterSession(TestSession session) {
		session.put("lastCommand", null);
		totalTestFinished++;
		log.debug(getVm().getName() + " : after session ,total test finished : " + totalTestFinished);
		if (totalTestFinished >= maxTestBeforeClean && maxTestBeforeClean >= 1) {
			// TODO : freynaud : bug here. total used can be >1 if the session
			// if supposed to be finished but crashed during the release
			// process.
			if (totalTestFinished == totalTestStarted) {
				restarting = true;
				log.info("releasing");
				session.getSlot().forceRelease();
				// clean the guest OS.
				log.info("cleaning up the VM");
				revertToCleanState();

				// the resources have been released already, throwing an
				// exception to prevent the Slot from release again.Design issue
				// on grid listener side.
				throw new RuntimeException("Throwing an exception to prevent automatic free of resources. The VM reset should have done that.");
			}
		}
	}

	/**
	 * @return the underlying VM
	 */
	public VirtualMachineWrapper getVm() {
		return vm;
	}

	public String toString() {
		StringBuffer buff = new StringBuffer();
		buff.append(super.toString() + "\n");
		buff.append("VM proxy " + vm.getName() + " , uuid:" + vm.getId() + "\n");
		buff.append("ran " + totalTestStarted + " tests so far.\n");
		if (maxTestBeforeClean >= 1) {
			buff.append("* setup to restore " + cleanState + " every " + maxTestBeforeClean + " tests\n");
		}
		return buff.toString();
	}

	protected String getPath() {
		return "/wd/hub";
	}

	// TODO : freynaud Bug if VM is not started, it freezes everything here
	/**
	 * If the url is not defined yet, it's not yet an issue. Trying to find it
	 * using VMTools.
	 */
	@Override
	public URL getRemoteURL() {
		if (remoteURL == null) {
			String ip = getVm().getIp();
			if (ip != null) {
				try {
					remoteURL = new URL("http://" + ip + ":" + getPort() + getPath());
				} catch (MalformedURLException e) {
					throw new GridException(e.getMessage(), e);
				}
			} else {
				// TODO : start the VM just to get the
				// IP . Should solve the freeze.
				throw new RuntimeException("Cannot find IP");
			}
		}
		return remoteURL;
	}

	public void setRemoteURL(URL url) {
		this.remoteURL = url;
	}

	protected String getPort() {
		return "5555";
	}

	public int getTotalTestStarted() {
		return totalTestStarted;
	}

	public int getMaxTestBeforeClean() {
		return maxTestBeforeClean;
	}

}
