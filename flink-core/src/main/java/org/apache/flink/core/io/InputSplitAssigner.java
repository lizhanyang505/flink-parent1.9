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

package org.apache.flink.core.io;

import org.apache.flink.annotation.PublicEvolving;

import java.util.List;

/**
 * An input split assigner distributes the {@link InputSplit}s among the instances on which a
 * data source exists.
 */
@PublicEvolving
public interface InputSplitAssigner {

	/**
	 * Returns the next input split that shall be consumed. The consumer's host is passed as a parameter
	 * to allow localized assignments.
	 * 
	 * @param host The host address of split requesting task.
	 * @param taskId The id of the split requesting task.
	 * @return the next input split to be consumed, or <code>null</code> if no more splits remain.
	 */
	InputSplit getNextInputSplit(String host, int taskId);

	/**
	 * Notify that some input splits have already been assigned.
	 * When get next input split, these splits should not be return.
	 * This is usually due to job master failover.
	 *
	 * @param taskId The id of the task which the input splits are assigned to.
	 * @param inputSplits The input splits that have already been assigned.
	 */
	void inputSplitsAssigned(int taskId, List<InputSplit> inputSplits);
}
