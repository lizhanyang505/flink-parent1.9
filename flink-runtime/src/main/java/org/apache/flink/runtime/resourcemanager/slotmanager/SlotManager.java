/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.resourcemanager.slotmanager;

import akka.pattern.AskTimeoutException;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.runtime.clusterframework.types.AllocationID;
import org.apache.flink.runtime.clusterframework.types.ResourceID;
import org.apache.flink.runtime.clusterframework.types.ResourceProfile;
import org.apache.flink.runtime.clusterframework.types.SlotID;
import org.apache.flink.runtime.clusterframework.types.TaskManagerSlot;
import org.apache.flink.runtime.concurrent.ScheduledExecutor;
import org.apache.flink.runtime.instance.InstanceID;
import org.apache.flink.runtime.messages.Acknowledge;
import org.apache.flink.runtime.resourcemanager.ResourceManagerId;
import org.apache.flink.runtime.resourcemanager.SlotRequest;
import org.apache.flink.runtime.resourcemanager.exceptions.ResourceManagerException;
import org.apache.flink.runtime.resourcemanager.placementconstraint.PlacementConstraint;
import org.apache.flink.runtime.resourcemanager.placementconstraint.PlacementConstraintManager;
import org.apache.flink.runtime.resourcemanager.placementconstraint.SlotTag;
import org.apache.flink.runtime.resourcemanager.registration.TaskExecutorConnection;
import org.apache.flink.runtime.taskexecutor.SlotReport;
import org.apache.flink.runtime.taskexecutor.SlotStatus;
import org.apache.flink.runtime.taskexecutor.TaskExecutorGateway;
import org.apache.flink.runtime.taskexecutor.exceptions.SlotAllocationException;
import org.apache.flink.runtime.taskexecutor.exceptions.SlotOccupiedException;
import org.apache.flink.util.FlinkException;
import org.apache.flink.util.Preconditions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The slot manager is responsible for maintaining a view on all registered task manager slots,
 * their allocation and all pending slot requests. Whenever a new slot is registered or and
 * allocated slot is freed, then it tries to fulfill another pending slot request. Whenever there
 * are not enough slots available the slot manager will notify the resource manager about it via
 * {@link ResourceActions#allocateResource(ResourceProfile)}.
 *
 * <p>In order to free resources and avoid resource leaks, idling task managers (task managers whose
 * slots are currently not used) and pending slot requests time out triggering their release and
 * failure, respectively.
 */
public class SlotManager implements AutoCloseable {
	private static final Logger LOG = LoggerFactory.getLogger(SlotManager.class);

	/** Scheduled executor for timeouts. */
	private final ScheduledExecutor scheduledExecutor;

	/** Timeout for slot requests to the task manager. */
	private final Time taskManagerRequestTimeout;

	/** Timeout after which an allocation is discarded. */
	private final Time slotRequestTimeout;

	/** Timeout after which an unused TaskManager is released. */
	private final Time taskManagerTimeout;

	/** Timeout after which an unused TaskManager is released when SlotManager starts. */
	private final Time taskManagerCheckerInitialDelay;

	/** Map for all registered slots. */
	protected final HashMap<SlotID, TaskManagerSlot> slots;

	/** Index of all currently free slots. */
	protected final LinkedHashMap<SlotID, TaskManagerSlot> freeSlots;

	/** All currently registered task managers. */
	protected final HashMap<InstanceID, TaskManagerRegistration> taskManagerRegistrations;

	/** Map of fulfilled and active allocations for request deduplication purposes. */
	private final HashMap<AllocationID, SlotID> fulfilledSlotRequests;

	/** Map of pending/unfulfilled slot allocation requests. */
	protected final HashMap<AllocationID, PendingSlotRequest> pendingSlotRequests;

	/** Map of slot requests' tags. */
	protected final HashMap<AllocationID, List<SlotTag>> allocationIdTags;

	/** ResourceManager's id. */
	private ResourceManagerId resourceManagerId;

	/** Executor for future callbacks which have to be "synchronized". */
	private Executor mainThreadExecutor;

	/** Callbacks for resource (de-)allocations. */
	private ResourceActions resourceActions;

	private ScheduledFuture<?> taskManagerTimeoutCheck;

	private ScheduledFuture<?> slotRequestTimeoutCheck;

	/** True iff the component has been started. */
	private boolean started;

	/** Listener for the slot actions. */
	private SlotListener slotListener;

	protected PlacementConstraintManager placementConstraintManager;

	/**
	 * This constructor is used for test only.
	 */
	public SlotManager(
			ScheduledExecutor scheduledExecutor,
			Time taskManagerRequestTimeout,
			Time slotRequestTimeout,
			Time taskManagerTimeout) {
		this(scheduledExecutor, taskManagerRequestTimeout, slotRequestTimeout, taskManagerTimeout, Time.milliseconds(0L));
	}

	public SlotManager(
				ScheduledExecutor scheduledExecutor,
				Time taskManagerRequestTimeout,
				Time slotRequestTimeout,
				Time taskManagerTimeout,
				Time taskManagerCheckerInitialDelay) {
		this.scheduledExecutor = Preconditions.checkNotNull(scheduledExecutor);
		this.taskManagerRequestTimeout = Preconditions.checkNotNull(taskManagerRequestTimeout);
		this.slotRequestTimeout = Preconditions.checkNotNull(slotRequestTimeout);
		this.taskManagerTimeout = Preconditions.checkNotNull(taskManagerTimeout);
		this.taskManagerCheckerInitialDelay = taskManagerCheckerInitialDelay;

		slots = new HashMap<>(16);
		freeSlots = new LinkedHashMap<>(16);
		taskManagerRegistrations = new HashMap<>(4);
		fulfilledSlotRequests = new HashMap<>(16);
		pendingSlotRequests = new HashMap<>(16);
		allocationIdTags = new HashMap<>(16);

		resourceManagerId = null;
		resourceActions = null;
		mainThreadExecutor = null;
		taskManagerTimeoutCheck = null;
		slotRequestTimeoutCheck = null;
		placementConstraintManager = new PlacementConstraintManager();

		started = false;
	}

	public int getNumberRegisteredSlots() {
		return slots.size();
	}

	public int getNumberRegisteredSlotsOf(InstanceID instanceId) {
		TaskManagerRegistration taskManagerRegistration = taskManagerRegistrations.get(instanceId);

		if (taskManagerRegistration != null) {
			return taskManagerRegistration.getNumberRegisteredSlots();
		} else {
			return 0;
		}
	}

	public int getNumberFreeSlots() {
		return freeSlots.size();
	}

	public int getNumberFreeSlotsOf(InstanceID instanceId) {
		TaskManagerRegistration taskManagerRegistration = taskManagerRegistrations.get(instanceId);

		if (taskManagerRegistration != null) {
			return taskManagerRegistration.getNumberFreeSlots();
		} else {
			return 0;
		}
	}

	public ResourceProfile getTotalResource() {
		ResourceProfile totalResources = new ResourceProfile(ResourceProfile.EMTPY);
		for (Map.Entry<SlotID, TaskManagerSlot> entry : slots.entrySet()) {
			if (!entry.getValue().getResourceProfile().equals(ResourceProfile.UNKNOWN)) {
				totalResources.addTo(entry.getValue().getResourceProfile());
			}
		}
		return totalResources;
	}

	public ResourceProfile getAvailableResource() {
		ResourceProfile availableResources = new ResourceProfile(ResourceProfile.EMTPY);
		for (Map.Entry<SlotID, TaskManagerSlot> entry : slots.entrySet()) {
			if (entry.getValue().getState() == TaskManagerSlot.State.FREE &&
				!entry.getValue().getResourceProfile().equals(ResourceProfile.UNKNOWN)) {
				availableResources.addTo(entry.getValue().getResourceProfile());
			}
		}
		return availableResources;
	}

	public ResourceProfile getTotalResourceOf(ResourceID resourceID) {
		ResourceProfile totalResources = new ResourceProfile(ResourceProfile.EMTPY);
		for (Map.Entry<SlotID, TaskManagerSlot> entry : slots.entrySet()) {
			if (entry.getKey().getResourceID().equals(resourceID) &&
				!entry.getValue().getResourceProfile().equals(ResourceProfile.UNKNOWN)) {
				totalResources.addTo(entry.getValue().getResourceProfile());
			}
		}
		return totalResources;
	}

	public ResourceProfile getAvailableResourceOf(ResourceID resourceID) {
		ResourceProfile availableResources = new ResourceProfile(ResourceProfile.EMTPY);
		for (Map.Entry<SlotID, TaskManagerSlot> entry : slots.entrySet()) {
			if (entry.getValue().getState() == TaskManagerSlot.State.FREE &&
				entry.getKey().getResourceID().equals(resourceID) &&
				!entry.getValue().getResourceProfile().equals(ResourceProfile.UNKNOWN)) {
				availableResources.addTo(entry.getValue().getResourceProfile());
			}
		}
		return availableResources;
	}

	public int getNumberPendingSlotRequests() {return pendingSlotRequests.size(); }

	public void setSlotListener(SlotListener slotListener) {
		this.slotListener = slotListener;
	}

	// ---------------------------------------------------------------------------------------------
	// Component lifecycle methods
	// ---------------------------------------------------------------------------------------------

	/**
	 * Starts the slot manager with the given leader id and resource manager actions.
	 *
	 * @param newResourceManagerId to use for communication with the task managers
	 * @param newMainThreadExecutor to use to run code in the ResourceManager's main thread
	 * @param newResourceActions to use for resource (de-)allocations
	 */
	public void start(ResourceManagerId newResourceManagerId, Executor newMainThreadExecutor, ResourceActions newResourceActions) {
		LOG.info("Starting the SlotManager.");

		this.resourceManagerId = Preconditions.checkNotNull(newResourceManagerId);
		mainThreadExecutor = Preconditions.checkNotNull(newMainThreadExecutor);
		resourceActions = Preconditions.checkNotNull(newResourceActions);

		started = true;

		taskManagerTimeoutCheck = scheduledExecutor.scheduleWithFixedDelay(
			() -> mainThreadExecutor.execute(
				() -> checkTaskManagerTimeouts()),
			taskManagerCheckerInitialDelay.toMilliseconds(),
			taskManagerTimeout.toMilliseconds(),
			TimeUnit.MILLISECONDS);

		slotRequestTimeoutCheck = scheduledExecutor.scheduleWithFixedDelay(
			() -> mainThreadExecutor.execute(
				() -> checkSlotRequestTimeouts()),
			0L,
			slotRequestTimeout.toMilliseconds(),
			TimeUnit.MILLISECONDS);
	}

	/**
	 * Suspends the component. This clears the internal state of the slot manager.
	 */
	public void suspend() {
		LOG.info("Suspending the SlotManager.");

		// stop the timeout checks for the TaskManagers and the SlotRequests
		if (taskManagerTimeoutCheck != null) {
			taskManagerTimeoutCheck.cancel(false);
			taskManagerTimeoutCheck = null;
		}

		if (slotRequestTimeoutCheck != null) {
			slotRequestTimeoutCheck.cancel(false);
			slotRequestTimeoutCheck = null;
		}

		for (PendingSlotRequest pendingSlotRequest : pendingSlotRequests.values()) {
			cancelPendingSlotRequest(pendingSlotRequest);
		}

		pendingSlotRequests.clear();

		ArrayList<InstanceID> registeredTaskManagers = new ArrayList<>(taskManagerRegistrations.keySet());

		for (InstanceID registeredTaskManager : registeredTaskManagers) {
			unregisterTaskManager(registeredTaskManager);
		}

		resourceManagerId = null;
		resourceActions = null;
		started = false;
	}

	/**
	 * Closes the slot manager.
	 *
	 * @throws Exception if the close operation fails
	 */
	@Override
	public void close() throws Exception {
		LOG.info("Closing the SlotManager.");

		suspend();
	}

	// ---------------------------------------------------------------------------------------------
	// Public API
	// ---------------------------------------------------------------------------------------------

	/**
	 * Requests a slot with the respective resource profile.
	 *
	 * @param slotRequest specifying the requested slot specs
	 * @return true if the slot request was registered; false if the request is a duplicate
	 * @throws SlotManagerException if the slot request failed (e.g. not enough resources left)
	 */
	public boolean registerSlotRequest(SlotRequest slotRequest) throws SlotManagerException {
		checkInit();

		if (checkDuplicateRequest(slotRequest.getAllocationId())) {
			LOG.debug("Ignoring a duplicate slot request with allocation id {}.", slotRequest.getAllocationId());

			return false;
		} else {
			PendingSlotRequest pendingSlotRequest = new PendingSlotRequest(slotRequest);

			pendingSlotRequests.put(slotRequest.getAllocationId(), pendingSlotRequest);
			allocationIdTags.put(slotRequest.getAllocationId(), slotRequest.getTags());

			try {
				internalRequestSlot(pendingSlotRequest);
			} catch (ResourceManagerException e) {
				// requesting the slot failed --> remove pending slot request
				pendingSlotRequests.remove(slotRequest.getAllocationId());
				allocationIdTags.remove(slotRequest.getAllocationId());

				throw new SlotManagerException("Could not fulfill slot request " + slotRequest.getAllocationId() + '.', e);
			}

			return true;
		}
	}

	/**
	 * Cancels and removes a pending slot request with the given allocation id. If there is no such
	 * pending request, then nothing is done.
	 *
	 * @param allocationId identifying the pending slot request
	 * @return True if a pending slot request was found; otherwise false
	 */
	public boolean unregisterSlotRequest(AllocationID allocationId) {
		checkInit();

		PendingSlotRequest pendingSlotRequest = pendingSlotRequests.remove(allocationId);

		if (null != pendingSlotRequest) {
			LOG.debug("Cancel slot request {}.", allocationId);

			if (pendingSlotRequest.isAssigned()) {
				cancelPendingSlotRequest(pendingSlotRequest);
			} else {
				resourceActions.cancelResourceAllocation(pendingSlotRequest.getResourceProfile());
			}

			return true;
		} else {
			LOG.debug("No pending slot request with allocation id {} found. Ignoring unregistration request.", allocationId);

			return false;
		}
	}

	/**
	 * Registers a new task manager at the slot manager. This will make the task managers slots
	 * known and, thus, available for allocation.
	 *
	 * @param taskExecutorConnection for the new task manager
	 * @param initialSlotReport for the new task manager
	 */
	public void registerTaskManager(final TaskExecutorConnection taskExecutorConnection, SlotReport initialSlotReport) {
		checkInit();

		LOG.info("Registering TaskManager {} under {} at the SlotManager.", taskExecutorConnection.getResourceID(), taskExecutorConnection.getInstanceID());

		// we identify task managers by their instance id
		if (taskManagerRegistrations.containsKey(taskExecutorConnection.getInstanceID())) {
			reportSlotStatus(taskExecutorConnection.getInstanceID(), initialSlotReport);
		} else {
			// first register the TaskManager
			ArrayList<SlotID> reportedSlots = new ArrayList<>();

			for (SlotStatus slotStatus : initialSlotReport) {
				reportedSlots.add(slotStatus.getSlotID());
			}

			TaskManagerRegistration taskManagerRegistration = new TaskManagerRegistration(
				taskExecutorConnection,
				reportedSlots);

			taskManagerRegistrations.put(taskExecutorConnection.getInstanceID(), taskManagerRegistration);

			// next register the new slots
			for (SlotStatus slotStatus : initialSlotReport) {
				registerSlot(
					slotStatus.getSlotID(),
					slotStatus.getAllocationID(),
					slotStatus.getJobID(),
					slotStatus.getAllocationResourceProfile(),
					slotStatus.getResourceProfile(),
					taskExecutorConnection,
					slotStatus.getVersion(),
					slotStatus.getTags());
			}
		}
	}

	public void setJobConstraints(JobID jobId, List<PlacementConstraint> constraints) {
		placementConstraintManager.setJobConstraints(jobId, constraints);
	}

	/**
	 * Unregisters the task manager identified by the given instance id and its associated slots
	 * from the slot manager.
	 *
	 * @param instanceId identifying the task manager to unregister
	 * @return True if there existed a registered task manager with the given instance id
	 */
	public boolean unregisterTaskManager(InstanceID instanceId) {
		checkInit();

		LOG.info("Unregister TaskManager {} from the SlotManager.", instanceId);

		TaskManagerRegistration taskManagerRegistration = taskManagerRegistrations.remove(instanceId);

		if (null != taskManagerRegistration) {
			internalUnregisterTaskManager(taskManagerRegistration);

			return true;
		} else {
			LOG.debug("There is no task manager registered with instance ID {}. Ignoring this message.", instanceId);

			return false;
		}
	}

	/**
	 * Reports the current slot allocations for a task manager identified by the given instance id.
	 *
	 * @param instanceId identifying the task manager for which to report the slot status
	 * @param slotReport containing the status for all of its slots
	 * @return true if the slot status has been updated successfully, otherwise false
	 */
	public boolean reportSlotStatus(InstanceID instanceId, SlotReport slotReport) {
		checkInit();

		LOG.debug("Received slot report from instance {}.", instanceId);

		TaskManagerRegistration taskManagerRegistration = taskManagerRegistrations.get(instanceId);

		if (null != taskManagerRegistration) {

			for (SlotStatus slotStatus : slotReport) {
				TaskManagerSlot taskManagerSlot = slots.get(slotStatus.getSlotID());

				if (slotStatus.getVersion() > taskManagerSlot.getVersion()) {
					LOG.warn("The version of slot {}'s report {} should not exceed that in ResourceManager {}",
							slotStatus.getSlotID(), slotStatus.getVersion(), taskManagerSlot.getVersion());
					continue;
				}

				if (slotStatus.getVersion() < taskManagerSlot.getVersion()) {
					if (taskManagerSlot.getState() == TaskManagerSlot.State.SYNCING) {
						// The initial request timeout and TM send outdated message,
						// re-send the allocation request in case the initial request lose.
						reAllocateSlot(taskManagerSlot, taskManagerSlot.getAssignedSlotRequest());
					} else {
						LOG.debug("Received outdated slot report from task manager"
								+ "with instance id {}. Current state leads. Ignoring this report.", instanceId);
					}
				} else {
					updateSlot(slotStatus.getSlotID(), slotStatus.getAllocationID(), slotStatus.getJobID());
				}
			}

			return true;
		} else {
			LOG.debug("Received slot report for unknown task manager with instance id {}. Ignoring this report.", instanceId);

			return false;
		}
	}

	/**
	 * Free the given slot from the given allocation. If the slot is still allocated by the given
	 * allocation id, then the slot will be marked as free and will be subject to new slot requests.
	 *
	 * @param slotId identifying the slot to free
	 * @param allocationId with which the slot is presumably allocated
	 */
	public void freeSlot(SlotID slotId, AllocationID allocationId) {
		checkInit();

		TaskManagerSlot slot = slots.get(slotId);

		if (null != slot) {
			if (slot.getState() == TaskManagerSlot.State.ALLOCATED) {
				if (Objects.equals(allocationId, slot.getAllocationId())) {

					TaskManagerRegistration taskManagerRegistration = taskManagerRegistrations.get(slot.getInstanceId());

					if (taskManagerRegistration == null) {
						throw new IllegalStateException("Trying to free a slot from a TaskManager " +
							slot.getInstanceId() + " which has not been registered.");
					}

					updateSlotState(slot, taskManagerRegistration, null, null);
					allocationIdTags.remove(allocationId);
				} else {
					LOG.debug("Received request to free slot {} with expected allocation id {}, " +
						"but actual allocation id {} differs. Ignoring the request.", slotId, allocationId, slot.getAllocationId());
				}
			} else {
				LOG.debug("Slot {} has not been allocated.", allocationId);
			}
		} else {
			LOG.debug("Trying to free a slot {} which has not been registered. Ignoring this message.", slotId);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Behaviour methods
	// ---------------------------------------------------------------------------------------------

	/**
	 * Finds a matching slot request for a given resource profile. If there is no such request,
	 * the method returns null.
	 *
	 * <p>Note: If you want to change the behaviour of the slot manager wrt slot allocation and
	 * request fulfillment, then you should override this method.
	 *
	 * @param taskManagerSlot the available slot
	 * @return A matching slot request which can be deployed in a slot with the given resource
	 * profile. Null if there is no such slot request pending.
	 */
	protected PendingSlotRequest findMatchingRequest(TaskManagerSlot taskManagerSlot) {

		for (PendingSlotRequest pendingSlotRequest : pendingSlotRequests.values()) {
			if (!pendingSlotRequest.isAssigned() &&
				taskManagerSlot.getResourceProfile().isMatching(pendingSlotRequest.getResourceProfile()) &&
				placementConstraintManager.check(
					pendingSlotRequest.getJobId(),
					allocationIdTags.get(pendingSlotRequest.getAllocationId()),
					getTaskExecutorSlotTags(taskManagerSlot.getSlotId()))) {
				return pendingSlotRequest;
			}
		}

		return null;
	}

	/**
	 * Finds a matching slot for a given resource profile. A matching slot has at least as many
	 * resources available as the given resource profile. If there is no such slot available, then
	 * the method returns null.
	 *
	 * <p>Note: If you want to change the behaviour of the slot manager wrt slot allocation and
	 * request fulfillment, then you should override this method.
	 *
	 * @param slotRequest the slot request to be matched
	 * @return A matching slot which fulfills the given resource profile. Null if there is no such
	 * slot available.
	 */
	protected TaskManagerSlot findMatchingSlot(SlotRequest slotRequest) {
		Iterator<Map.Entry<SlotID, TaskManagerSlot>> iterator = freeSlots.entrySet().iterator();

		while (iterator.hasNext()) {
			TaskManagerSlot taskManagerSlot = iterator.next().getValue();

			// sanity check
			Preconditions.checkState(
				taskManagerSlot.getState() == TaskManagerSlot.State.FREE,
				"TaskManagerSlot %s is not in state FREE but %s.",
				taskManagerSlot.getSlotId(), taskManagerSlot.getState());

			if (taskManagerSlot.getResourceProfile().isMatching(slotRequest.getResourceProfile()) &&
				placementConstraintManager.check(
					slotRequest.getJobId(),
					allocationIdTags.get(slotRequest.getAllocationId()),
					getTaskExecutorSlotTags(taskManagerSlot.getSlotId()))) {
				iterator.remove();
				return taskManagerSlot;
			}
		}

		return null;
	}

	// ---------------------------------------------------------------------------------------------
	// Internal slot operations
	// ---------------------------------------------------------------------------------------------

	/**
	 * Registers a slot for the given task manager at the slot manager. The slot is identified by
	 * the given slot id. The given resource profile defines the available resources for the slot.
	 * The task manager connection can be used to communicate with the task manager.
	 *
	 * @param slotId identifying the slot on the task manager
	 * @param allocationId which is currently deployed in the slot
	 * @param allocationResourceProfile The actual allocated resource for current deployed task
	 * @param resourceProfile of the slot
	 * @param taskManagerConnection to communicate with the remote task manager
	 * @param initialVersion The version of the slot status in the TaskManager
	 */
	private void registerSlot(
			SlotID slotId,
			AllocationID allocationId,
			JobID jobId,
			ResourceProfile allocationResourceProfile,
			ResourceProfile resourceProfile,
			TaskExecutorConnection taskManagerConnection,
			long initialVersion,
			List<SlotTag> tags) {

		if (slots.containsKey(slotId)) {
			// remove the old slot first
			removeSlot(slotId);
		}

		TaskManagerSlot slot = new TaskManagerSlot(
			slotId,
			resourceProfile,
			taskManagerConnection,
			initialVersion);

		slots.put(slotId, slot);

		updateSlot(slotId, allocationId, jobId);

		if (allocationId != null) {
			if (tags == null) {
				LOG.warn("Slot with SlotID {} is registered with AllocationID {}, slot tags should not be null.", slotId, allocationId);
			} else {
				allocationIdTags.put(allocationId, tags);
			}
		}

		if (slotListener != null && allocationId != null) {
			Preconditions.checkNotNull(allocationResourceProfile,
					"The allocation resource profile should be reported together");

			slotListener.notifySlotRegistered(slotId, allocationResourceProfile);
		}
	}

	/**
	 * Updates a slot with the given allocation id.
	 *
	 * @param slotId to update
	 * @param allocationId specifying the current allocation of the slot
	 * @param jobId specifying the job to which the slot is allocated
	 * @return True if the slot could be updated; otherwise false
	 */
	private boolean updateSlot(SlotID slotId, AllocationID allocationId, JobID jobId) {
		final TaskManagerSlot slot = slots.get(slotId);

		if (slot != null) {
			final TaskManagerRegistration taskManagerRegistration = taskManagerRegistrations.get(slot.getInstanceId());

			if (taskManagerRegistration != null) {
				updateSlotState(slot, taskManagerRegistration, allocationId, jobId);

				return true;
			} else {
				throw new IllegalStateException("Trying to update a slot from a TaskManager " +
					slot.getInstanceId() + " which has not been registered.");
			}
		} else {
			LOG.debug("Trying to update unknown slot with slot id {}.", slotId);

			return false;
		}
	}

	private void updateSlotState(
			TaskManagerSlot slot,
			TaskManagerRegistration taskManagerRegistration,
			@Nullable AllocationID allocationId,
			@Nullable JobID jobId) {
		if (null != allocationId) {
			switch (slot.getState()) {
				case SYNCING:
					slot.syncState(TaskManagerSlot.State.PENDING);
					// No break and continue updating the slot state as PENDING
				case PENDING:
					// we have a pending slot request --> check whether we have to reject it
					PendingSlotRequest pendingSlotRequest = slot.getAssignedSlotRequest();

					if (Objects.equals(pendingSlotRequest.getAllocationId(), allocationId)) {
						// we can cancel the slot request because it has been fulfilled
						cancelPendingSlotRequest(pendingSlotRequest);

						// remove the pending slot request, since it has been completed
						pendingSlotRequests.remove(pendingSlotRequest.getAllocationId());

						slot.completeAllocation(allocationId, jobId);
					} else {
						// we first have to free the slot in order to set a new allocationId
						slot.clearPendingSlotRequest();
						// set the allocation id such that the slot won't be considered for the pending slot request
						slot.updateAllocation(allocationId, jobId);

						// this will try to find a new slot for the request
						rejectPendingSlotRequest(
							pendingSlotRequest,
							new Exception("Task manager reported slot " + slot.getSlotId() + " being already allocated."));
					}

					taskManagerRegistration.occupySlot();
					break;
				case ALLOCATED:
					if (!Objects.equals(allocationId, slot.getAllocationId())) {
						slot.freeSlot();
						slot.updateAllocation(allocationId, jobId);
					}
					break;
				case FREE:
					// the slot is currently free --> it is stored in freeSlots
					freeSlots.remove(slot.getSlotId());
					slot.updateAllocation(allocationId, jobId);
					taskManagerRegistration.occupySlot();
					break;
			}

			fulfilledSlotRequests.put(allocationId, slot.getSlotId());
		} else {
			// no allocation reported
			switch (slot.getState()) {
				case SYNCING:
					slot.clearPendingSlotRequest();
					// No break and continue updating the slot state as FREE
				case FREE:
					// the slot is currently free --> but it may be stored in freeSlots
					freeSlots.remove(slot.getSlotId());
					handleFreeSlot(slot);
					break;
				case PENDING:
					// don't do anything because we still have a pending slot request
					break;
				case ALLOCATED:
					AllocationID oldAllocation = slot.getAllocationId();
					slot.freeSlot();
					fulfilledSlotRequests.remove(oldAllocation);
					taskManagerRegistration.freeSlot();

					handleFreeSlot(slot);
					break;
			}
		}
	}

	/**
	 * Tries to allocate a slot for the given slot request. If there is no slot available, the
	 * resource manager is informed to allocate more resources and a timeout for the request is
	 * registered.
	 *
	 * @param pendingSlotRequest to allocate a slot for
	 * @throws ResourceManagerException if the resource manager cannot allocate more resource
	 */
	private void internalRequestSlot(PendingSlotRequest pendingSlotRequest) throws ResourceManagerException {
		TaskManagerSlot taskManagerSlot = findMatchingSlot(pendingSlotRequest.getSlotRequest());

		if (taskManagerSlot != null) {
			LOG.info("Assigning slot {} to {}", taskManagerSlot.getSlotId(), pendingSlotRequest.getAllocationId());
			allocateSlot(taskManagerSlot, pendingSlotRequest);
		} else {
			resourceActions.allocateResource(pendingSlotRequest.getResourceProfile());
		}
	}

	/**
	 * Allocates the given slot for the given slot request. This entails sending a registration
	 * message to the task manager and treating failures.
	 *
	 * @param taskManagerSlot to allocate for the given slot request
	 * @param pendingSlotRequest to allocate the given slot for
	 */
	private void allocateSlot(TaskManagerSlot taskManagerSlot, PendingSlotRequest pendingSlotRequest) {
		Preconditions.checkState(taskManagerSlot.getState() == TaskManagerSlot.State.FREE);

		taskManagerSlot.increaseVersion();
		taskManagerSlot.assignPendingSlotRequest(pendingSlotRequest);

		CompletableFuture<Acknowledge> completableFuture = sendSlotAllocationRequest(taskManagerSlot, pendingSlotRequest);

		final AllocationID allocationId = pendingSlotRequest.getAllocationId();
		final SlotID slotId = taskManagerSlot.getSlotId();
		completableFuture.whenCompleteAsync(
			(Acknowledge acknowledge, Throwable throwable) -> {
				try {
					if (acknowledge != null) {
						updateSlot(slotId, allocationId, pendingSlotRequest.getJobId());
					} else {
						if (throwable instanceof SlotOccupiedException) {
							SlotOccupiedException exception = (SlotOccupiedException) throwable;
							updateSlot(slotId, exception.getAllocationId(), exception.getJobId());
						} else if (throwable instanceof AskTimeoutException || throwable instanceof CancellationException) {
							syncSlotForSlotRequest(slotId, allocationId);
						} else {
							removeSlotRequestFromSlot(slotId, allocationId);
						}

						if (!(throwable instanceof AskTimeoutException || throwable instanceof CancellationException)) {
							handleFailedSlotRequest(slotId, allocationId, throwable);
						} else {
							LOG.debug("Slot allocation request {} has been cancelled or timeout.", allocationId, throwable);
						}
					}
				} catch (Exception e) {
					LOG.error("Error while completing the slot allocation.", e);
				}
			},
			mainThreadExecutor);
	}

	private void reAllocateSlot(TaskManagerSlot taskManagerSlot, PendingSlotRequest pendingSlotRequest) {
		Preconditions.checkState(taskManagerSlot.getState() == TaskManagerSlot.State.SYNCING,
			String.format("Slot %s is in state %s", taskManagerSlot.getSlotId(), taskManagerSlot.getState()));
		LOG.info("Assigning slot {} to allocation {}", taskManagerSlot.getSlotId(), pendingSlotRequest.getAllocationId());

		CompletableFuture<Acknowledge> completableFuture = sendSlotAllocationRequest(taskManagerSlot, pendingSlotRequest);

		final AllocationID allocationId = pendingSlotRequest.getAllocationId();
		final SlotID slotId = taskManagerSlot.getSlotId();
		completableFuture.whenCompleteAsync(
			(Acknowledge acknowledge, Throwable throwable) -> {
				try {
					if (acknowledge != null) {
						updateSlot(slotId, allocationId, pendingSlotRequest.getJobId());
					} else {
						// If repeat message fail, do nothing
						LOG.debug("Slot allocation request {} has failed.", allocationId, throwable);
					}
				} catch (Exception e) {
					LOG.error("Error while completing the slot allocation.", e);
				}
			},
			mainThreadExecutor);
	}

	private CompletableFuture<Acknowledge> sendSlotAllocationRequest(TaskManagerSlot taskManagerSlot, PendingSlotRequest pendingSlotRequest) {
		TaskExecutorConnection taskExecutorConnection = taskManagerSlot.getTaskManagerConnection();
		TaskExecutorGateway gateway = taskExecutorConnection.getTaskExecutorGateway();

		final CompletableFuture<Acknowledge> completableFuture = new CompletableFuture<>();
		final AllocationID allocationId = pendingSlotRequest.getAllocationId();
		final SlotID slotId = taskManagerSlot.getSlotId();
		final InstanceID instanceID = taskManagerSlot.getInstanceId();

		pendingSlotRequest.setRequestFuture(completableFuture);

		TaskManagerRegistration taskManagerRegistration = taskManagerRegistrations.get(instanceID);

		if (taskManagerRegistration == null) {
			throw new IllegalStateException("Could not find a registered task manager for instance id " +
				instanceID + '.');
		}

		taskManagerRegistration.markUsed();

		// RPC call to the task manager
		CompletableFuture<Acknowledge> requestFuture = gateway.requestSlot(
			slotId,
			pendingSlotRequest.getJobId(),
			allocationId,
			pendingSlotRequest.getResourceProfile(),
			pendingSlotRequest.getTargetAddress(),
			pendingSlotRequest.getSlotRequest().getTags(),
			resourceManagerId,
			taskManagerSlot.getVersion(),
			taskManagerRequestTimeout);

		requestFuture.whenComplete(
			(Acknowledge acknowledge, Throwable throwable) -> {
				if (acknowledge != null) {
					completableFuture.complete(acknowledge);
				} else {
					completableFuture.completeExceptionally(throwable);
				}
			});

		return completableFuture;
	}

	/**
	 * Handles a free slot. It first tries to find a pending slot request which can be fulfilled.
	 * If there is no such request, then it will add the slot to the set of free slots.
	 *
	 * @param freeSlot to find a new slot request for
	 */
	private void handleFreeSlot(TaskManagerSlot freeSlot) {
		Preconditions.checkState(freeSlot.getState() == TaskManagerSlot.State.FREE);

		if (slotListener != null) {
			slotListener.notifySlotFree(freeSlot.getSlotId());
		}

		PendingSlotRequest pendingSlotRequest = findMatchingRequest(freeSlot);

		if (null != pendingSlotRequest) {
			LOG.info("Assigning free slot {} to {}", freeSlot.getSlotId(), pendingSlotRequest.getAllocationId());
			allocateSlot(freeSlot, pendingSlotRequest);
		} else {
			freeSlots.put(freeSlot.getSlotId(), freeSlot);
		}
	}

	/**
	 * Removes the given set of slots from the slot manager.
	 *
	 * @param slotsToRemove identifying the slots to remove from the slot manager
	 */
	private void removeSlots(Iterable<SlotID> slotsToRemove) {
		for (SlotID slotId : slotsToRemove) {
			removeSlot(slotId);
		}
	}

	/**
	 * Removes the given slot from the slot manager.
	 *
	 * @param slotId identifying the slot to remove
	 */
	private void removeSlot(SlotID slotId) {
		TaskManagerSlot slot = slots.remove(slotId);

		if (null != slot) {
			freeSlots.remove(slotId);

			if (slot.getState() == TaskManagerSlot.State.PENDING) {
				// reject the pending slot request --> triggering a new allocation attempt
				rejectPendingSlotRequest(
					slot.getAssignedSlotRequest(),
					new Exception("The assigned slot " + slot.getSlotId() + " was removed."));
			}

			AllocationID oldAllocationId = slot.getAllocationId();

			if (oldAllocationId != null) {
				fulfilledSlotRequests.remove(oldAllocationId);

				resourceActions.notifyAllocationFailure(
					slot.getJobId(),
					oldAllocationId,
					new FlinkException("The assigned slot " + slot.getSlotId() + " was removed."));
			}

			if (slotListener != null) {
				slotListener.notifySlotRemoved(slotId);
			}
		} else {
			LOG.debug("There was no slot registered with slot id {}.", slotId);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Internal request handling methods
	// ---------------------------------------------------------------------------------------------

	/**
	 * Removes a pending slot request identified by the given allocation id from a slot identified
	 * by the given slot id.
	 *
	 * @param slotId identifying the slot
	 * @param allocationId identifying the presumable assigned pending slot request
	 */
	private void removeSlotRequestFromSlot(SlotID slotId, AllocationID allocationId) {
		TaskManagerSlot taskManagerSlot = slots.get(slotId);

		if (null != taskManagerSlot) {
			if (taskManagerSlot.getState() == TaskManagerSlot.State.PENDING && Objects.equals(allocationId, taskManagerSlot.getAssignedSlotRequest().getAllocationId())) {

				TaskManagerRegistration taskManagerRegistration = taskManagerRegistrations.get(taskManagerSlot.getInstanceId());

				if (taskManagerRegistration == null) {
					throw new IllegalStateException("Trying to remove slot request from slot for which there is no TaskManager " + taskManagerSlot.getInstanceId() + " is registered.");
				}

				// clear the pending slot request
				taskManagerSlot.clearPendingSlotRequest();

				updateSlotState(taskManagerSlot, taskManagerRegistration, null, null);
			} else {
				LOG.debug("Ignore slot request removal for slot {}.", slotId);
			}
		} else {
			LOG.debug("There was no slot with {} registered. Probably this slot has been already freed.", slotId);
		}
	}

	/**
	 * Sync a pending slot request identified by the given allocation id from a slot identified
	 * by the given slot id.
	 *
	 * @param slotId identifying the slot
	 * @param allocationId identifying the presumable assigned pending slot request
	 */
	private void syncSlotForSlotRequest(SlotID slotId, AllocationID allocationId) {
		TaskManagerSlot taskManagerSlot = slots.get(slotId);

		if (null != taskManagerSlot) {
			if (taskManagerSlot.getState() == TaskManagerSlot.State.PENDING && Objects.equals(allocationId, taskManagerSlot.getAssignedSlotRequest().getAllocationId())) {

				TaskManagerRegistration taskManagerRegistration = taskManagerRegistrations.get(taskManagerSlot.getInstanceId());

				if (taskManagerRegistration == null) {
					throw new IllegalStateException("Trying to sync slot for request from slot for which there is no TaskManager " + taskManagerSlot.getInstanceId() + " is registered.");
				}

				// sync the pending slot request
				taskManagerSlot.syncPendingSlotRequest();

			} else {
				LOG.debug("Ignore slot {} sync for request.", slotId);
			}
		} else {
			LOG.debug("There was no slot with {} registered. Probably this slot has been already freed.", slotId);
		}
	}

	/**
	 * Handles a failed slot request. The slot manager tries to find a new slot fulfilling
	 * the resource requirements for the failed slot request.
	 *
	 * @param slotId identifying the slot which was assigned to the slot request before
	 * @param allocationId identifying the failed slot request
	 * @param cause of the failure
	 */
	private void handleFailedSlotRequest(SlotID slotId, AllocationID allocationId, Throwable cause) {
		PendingSlotRequest pendingSlotRequest = pendingSlotRequests.get(allocationId);

		LOG.debug("Slot request with allocation id {} failed for slot {}.", allocationId, slotId, cause);

		if (null != pendingSlotRequest) {
			pendingSlotRequest.setRequestFuture(null);

			try {
				internalRequestSlot(pendingSlotRequest);
			} catch (ResourceManagerException e) {
				pendingSlotRequests.remove(allocationId);

				resourceActions.notifyAllocationFailure(
					pendingSlotRequest.getJobId(),
					allocationId,
					e);
			}
		} else {
			LOG.debug("There was not pending slot request with allocation id {}. Probably the request has been fulfilled or cancelled.", allocationId);
		}
	}

	/**
	 * Rejects the pending slot request by failing the request future with a
	 * {@link SlotAllocationException}.
	 *
	 * @param pendingSlotRequest to reject
	 * @param cause of the rejection
	 */
	private void rejectPendingSlotRequest(PendingSlotRequest pendingSlotRequest, Exception cause) {
		CompletableFuture<Acknowledge> request = pendingSlotRequest.getRequestFuture();

		if (null != request) {
			request.completeExceptionally(new SlotAllocationException(cause));
		} else {
			LOG.debug("Cannot reject pending slot request {}, since no request has been sent.", pendingSlotRequest.getAllocationId());
		}
	}

	/**
	 * Cancels the given slot request.
	 *
	 * @param pendingSlotRequest to cancel
	 */
	private void cancelPendingSlotRequest(PendingSlotRequest pendingSlotRequest) {
		CompletableFuture<Acknowledge> request = pendingSlotRequest.getRequestFuture();

		if (null != request) {
			request.cancel(false);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Internal timeout methods
	// ---------------------------------------------------------------------------------------------

	private void checkTaskManagerTimeouts() {
		if (!taskManagerRegistrations.isEmpty()) {
			long currentTime = System.currentTimeMillis();

			ArrayList<InstanceID> timedOutTaskManagerIds = new ArrayList<>(taskManagerRegistrations.size());

			// first retrieve the timed out TaskManagers
			for (TaskManagerRegistration taskManagerRegistration : taskManagerRegistrations.values()) {
				if (currentTime - taskManagerRegistration.getIdleSince() >= taskManagerTimeout.toMilliseconds()) {
					// we collect the instance ids first in order to avoid concurrent modifications by the
					// ResourceActions.releaseResource call
					timedOutTaskManagerIds.add(taskManagerRegistration.getInstanceId());
				}
			}

			// second we trigger the release resource callback which can decide upon the resource release
			for (InstanceID timedOutTaskManagerId : timedOutTaskManagerIds) {
				LOG.debug("Release TaskExecutor {} because it exceeded the idle timeout.", timedOutTaskManagerId);
				resourceActions.releaseResource(timedOutTaskManagerId, new FlinkException("TaskExecutor exceeded the idle timeout."));
			}
		}
	}

	private void checkSlotRequestTimeouts() {
		if (!pendingSlotRequests.isEmpty()) {
			long currentTime = System.currentTimeMillis();

			Iterator<Map.Entry<AllocationID, PendingSlotRequest>> slotRequestIterator = pendingSlotRequests.entrySet().iterator();

			while (slotRequestIterator.hasNext()) {
				PendingSlotRequest slotRequest = slotRequestIterator.next().getValue();

				if (currentTime - slotRequest.getCreationTimestamp() >= slotRequestTimeout.toMilliseconds()) {
					slotRequestIterator.remove();

					if (slotRequest.isAssigned()) {
						cancelPendingSlotRequest(slotRequest);
					}

					resourceActions.notifyAllocationFailure(
						slotRequest.getJobId(),
						slotRequest.getAllocationId(),
						new TimeoutException("The allocation could not be fulfilled in time."));
				}
			}
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Internal utility methods
	// ---------------------------------------------------------------------------------------------

	private void internalUnregisterTaskManager(TaskManagerRegistration taskManagerRegistration) {
		Preconditions.checkNotNull(taskManagerRegistration);

		removeSlots(taskManagerRegistration.getSlots());
	}

	private boolean checkDuplicateRequest(AllocationID allocationId) {
		return pendingSlotRequests.containsKey(allocationId) || fulfilledSlotRequests.containsKey(allocationId);
	}

	private void checkInit() {
		Preconditions.checkState(started, "The slot manager has not been started.");
	}

	protected List<List<SlotTag>> getTaskExecutorSlotTags(SlotID slotID) {
		List<List<SlotTag>> taskExecutorSlotTags = new ArrayList<>();
		InstanceID instanceID = slots.get(slotID).getInstanceId();
		taskManagerRegistrations.get(instanceID).getSlots().forEach(
			taskExecutorSlotId -> {
				TaskManagerSlot slot = slots.get(taskExecutorSlotId);
				if (slot == null) {
					return;
				}
				AllocationID allocationId = slot.getAllocationId();
				if (allocationId == null && slot.getAssignedSlotRequest() != null) {
					allocationId = slot.getAssignedSlotRequest().getAllocationId();
				}
				if (allocationId == null || !allocationIdTags.containsKey(allocationId)) {
					return;
				}
				taskExecutorSlotTags.add(allocationIdTags.get(allocationId));
			}
		);
		return taskExecutorSlotTags;
	}

	// ---------------------------------------------------------------------------------------------
	// Testing methods
	// ---------------------------------------------------------------------------------------------

	@VisibleForTesting
	TaskManagerSlot getSlot(SlotID slotId) {
		return slots.get(slotId);
	}

	@VisibleForTesting
	PendingSlotRequest getSlotRequest(AllocationID allocationId) {
		return pendingSlotRequests.get(allocationId);
	}

	@VisibleForTesting
	boolean isTaskManagerIdle(InstanceID instanceId) {
		TaskManagerRegistration taskManagerRegistration = taskManagerRegistrations.get(instanceId);

		if (null != taskManagerRegistration) {
			return taskManagerRegistration.isIdle();
		} else {
			return false;
		}
	}

	@VisibleForTesting
	public void unregisterTaskManagersAndReleaseResources() {
		Iterator<Map.Entry<InstanceID, TaskManagerRegistration>> taskManagerRegistrationIterator =
				taskManagerRegistrations.entrySet().iterator();

		while (taskManagerRegistrationIterator.hasNext()) {
			TaskManagerRegistration taskManagerRegistration =
					taskManagerRegistrationIterator.next().getValue();

			taskManagerRegistrationIterator.remove();

			internalUnregisterTaskManager(taskManagerRegistration);

			resourceActions.releaseResource(taskManagerRegistration.getInstanceId(), new FlinkException("Triggering of SlotManager#unregisterTaskManagersAndReleaseResources."));
		}
	}

	@VisibleForTesting
	List<SlotTag> getTagsForSlotRequest(SlotRequest slotRequest) {
		return allocationIdTags.get(slotRequest.getAllocationId());
	}

	@VisibleForTesting
	List<List<SlotTag>> getTagsForTaskExecutor(ResourceID resourceId) {
		SlotID slotId = null;
		for (SlotID sid : slots.keySet()) {
			if (sid.getResourceID().equals(resourceId)) {
				slotId = sid;
				break;
			}
		}
		if (slotId == null) {
			return Collections.emptyList();
		}
		return getTaskExecutorSlotTags(slotId);
	}

	/**
	 * An utility interface for listening for the slot action in slot manager.
	 */
	protected interface SlotListener {

		void notifySlotRegistered(SlotID slotId, ResourceProfile allocationResourceProfile);

		void notifySlotFree(SlotID slotId);

		void notifySlotRemoved(SlotID slotId);
	}
}
