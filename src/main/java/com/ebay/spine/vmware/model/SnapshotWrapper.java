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

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.log4j.Logger;
import org.openqa.grid.internal.GridException;

import com.vmware.vim25.mo.Task;
import com.vmware.vim25.mo.VirtualMachineSnapshot;

/**
 * 
 * Wrapper around a VM snapshot. The only reason this wrapper exist is to
 * manually manage resources and make sure only 1 VM is reverted at a time.
 * Ideally this should be done with some config of the VSphere WS but can't find
 * how to do it.
 * 
 * @author Francois Reynaud
 * 
 */
public class SnapshotWrapper {

	private static final Logger log = Logger.getLogger(SnapshotWrapper.class);
	private String name;
	private VirtualMachineWrapper vm;
	private VirtualMachineSnapshot snapshot;

	public SnapshotWrapper(VirtualMachineWrapper vm, VirtualMachineSnapshot snapshot) {
		this.vm = vm;
		this.snapshot = snapshot;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public void delete() {
		try {
			Task t = snapshot.removeSnapshot_Task(false);
			if (Task.SUCCESS != t.waitForTask()) {
				throw new RuntimeException("bug");
			}
		} catch (Throwable e) {
			System.err.println(e.getMessage() + " error delete");
		}
	}

	public String toString() {
		return name;
	}

	/**
	 * Reverting the VM to this snapshot.
	 * 
	 * if there was a connection to VMTools, that connection will be broken.
	 * 
	 * @throws GridException
	 *             if anything bad happens.
	 */

	private static int reverting = 0;
	private static Lock lock = new ReentrantLock();
	private static Condition c = lock.newCondition();
	// slower to revert more than 1 VM at a time with the current hardware.
	// Probably due to low I/O
	private static int MAX_CONCCURENT_REVERT = 1;

	// can we handle another snapshot revert now ?
	private boolean hasResourcesToRevert() {
		return reverting < MAX_CONCCURENT_REVERT;
	}

	/**
	 * waits until the underlying VM architecture can handle another snapshot
	 * revert.
	 * 
	 * @throws InterruptedException
	 */
	private void blockUntilResourceIsFree() throws InterruptedException {

		try {
			lock.lock();
			while (!hasResourcesToRevert()) {
				c.await();
			}
			if (hasResourcesToRevert()) {
				reverting++;
				return;
			}
		} finally {
			lock.unlock();
		}
		blockUntilResourceIsFree();
	}

	/**
	 * ensure that there is enough resources. If not, wait for the resources and
	 * revert.
	 * 
	 * @throws GridException
	 */
	public void revert() throws GridException {
		try {
			blockUntilResourceIsFree();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		try {
			_revert();

		} catch (Throwable t) {
			throw new GridException("error reverting snapshot :" + t.getMessage());
		} finally {
			try {
				lock.lock();
				reverting--;
				c.signalAll();
			} finally {
				lock.unlock();
			}
		}
	}

	private void _revert() throws Exception {
		Task task = snapshot.revertToSnapshot_Task(null);

		if (task.waitForTask() == Task.SUCCESS) {
			log.debug("reverted to snapshot " + getName() + " on VM " + vm.getName());
		} else {
			log.error("error reverting to snapshot" + getName() + task.toString() + ". Retrying once.");
			throw new GridException("Cannot revert to snapshot " + getName() + " on VM " + vm.getName());
		}
	}
}
