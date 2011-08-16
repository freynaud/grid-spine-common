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
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Properties;

import org.apache.log4j.Logger;
import org.openqa.grid.internal.GridException;

import com.ebay.spine.vmware.model.VirtualMachineWrapper;
import com.vmware.vix.VixConstants;
import com.vmware.vix.VixException;
import com.vmware.vix.VixVSphereHandle;
import com.vmware.vix.VixVmHandle;

/**
 * API to access the OS inside the VM ( vmware tools ), run programs on the
 * guest OS, copy files etc.
 * 
 * @author freynaud
 * 
 */
public class VIX {

	private static final Logger log = Logger.getLogger(VIX.class);

	private static String userId;
	private static String pass;
	private static String url;
	private static String vixPath;
	private VixVSphereHandle hostHandle;
	private VirtualMachineWrapper vm;
	private VixVmHandle vixVm;

	static {
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
			vixPath = properties.getProperty("vixPath");
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		String path = findVIXPath();
		System.setProperty("jna.library.path", path);

	}

	private static String findVIXPath() {
		File f = new File(vixPath);
		if (f.exists()) {
			return f.getAbsolutePath();
		}

		throw new RuntimeException("Cannot find the necessary VIX related files. ( tried " + vixPath + ")");
	}

	private void connect() {
		try {
			hostHandle = new VixVSphereHandle(new URL(url).getHost(), userId, pass);
		} catch (VixException e) {
			log.error("Cannot initialize VIX service.", e);
		} catch (MalformedURLException e) {
			log.error("Cannot initialize VIX service.The URL specified for the entry point " + url + " is wrong.", e);
			throw new RuntimeException(e);
		}
	}

	/**
	 * Creates the VIX instance associated to the VM. retry once if the
	 * connection fails.
	 * 
	 * @param vm
	 */
	public VIX(VirtualMachineWrapper vm) {
		this.vm = vm;
		try {
			connect();
			vixVm = hostHandle.openVm(vm.getConfigPath());
		} catch (VixException e) {
			log.warn(e.getMessage());
			connect();
			try {
				vixVm = hostHandle.openVm(vm.getConfigPath());
			} catch (VixException v) {
				throw new GridException("Cannot initiate the VIX connection to vm " + vm.getName(), v);
			}
		}
	}

	public void release() {
		if (vixVm != null) {
			try {
				vixVm.release();
				hostHandle.disconnect();
			} catch (Throwable t) {
				log.error("Error releasing the resource", t);
			}
		}
	}

	/**
	 * 
	 * @param interpreter
	 *            /bin/bach, cmd.exe
	 * @param script
	 *            setup.exe , ifconfig ...
	 * @param returnImmediately
	 * @return exit code of the command.
	 */
	public int runProgramInGuest(String interpreter, String command, boolean returnImmediately) {

		int res = -1;
		try {
			login();
			res = vixVm.runScriptInGuest(interpreter, command, returnImmediately);
		} catch (VixException e) {
			throw new GridException(e.getMessage(), e);
		}

		if (res != 0) {
			log.warn("error trying to launch " + command + " using " + interpreter);
		}
		return res;
	}

	public void createFolderInGuest(String folder) {
		try {
			login();
			if (!vixVm.directoryExistsInGuest(folder)) {
				vixVm.createDirectoryInGuest(folder);
			}
		} catch (VixException e) {
			throw new GridException(e.getMessage(), e);
		}

	}

	/**
	 * for some reason login crashes with the error : user has to be logged in
	 * to login in .. Retrying until it works.
	 * http://communities.vmware.com/message/1159836
	 * 
	 */
	private void login() {
		boolean loggedIn = false;
		int error = 0;
		while (!loggedIn && error < 10) {
			try {
				vixVm.loginInGuest("euqe", "1234", VixConstants.VIX_LOGIN_IN_GUEST_REQUIRE_INTERACTIVE_ENVIRONMENT);
				loggedIn = true;
			} catch (VixException e) {
				error++;
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e1) {
				}
			}
		}
		if (!loggedIn) {
			log.debug("Error loggin in.Releasing again and reconnecting");
			vixVm.release();
			try {
				log.debug("reconnecting to VIX");
				connect();
				vixVm = hostHandle.openVm(vm.getConfigPath());
				login();
			} catch (VixException e) {
				e.printStackTrace();
			}
		}
	}

	/**
	 * doesn't work great. Try not to use.
	 * @return
	 */
	public String getGuestIP() {
		try {
			return vixVm.getIpAddress();
		} catch (VixException e) {
			throw new GridException(e.getMessage(), e);
		}
	}

	public void copyFileFromHostToGuest(String srcFile, String destFile) throws VixException {
		vixVm.copyFileFromHostToGuest(srcFile, destFile);
	}

	public void copyFileFromGuestToHost(String src, String dest) throws VixException {
		vixVm.copyFileFromGuestToHost(src, dest);

	}

}
