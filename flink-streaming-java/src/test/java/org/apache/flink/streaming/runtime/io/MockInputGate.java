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

package org.apache.flink.streaming.runtime.io;

import org.apache.flink.runtime.event.TaskEvent;
import org.apache.flink.runtime.io.network.api.EndOfPartitionEvent;
import org.apache.flink.runtime.io.network.partition.ResultPartitionType;
import org.apache.flink.runtime.io.network.partition.consumer.BufferOrEvent;
import org.apache.flink.runtime.io.network.partition.consumer.InputChannel;
import org.apache.flink.runtime.io.network.partition.consumer.InputGate;
import org.apache.flink.runtime.io.network.partition.consumer.InputGateListener;
import org.apache.flink.runtime.io.network.partition.consumer.SingleInputGate;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Queue;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Mock {@link InputGate}.
 */
public class MockInputGate implements InputGate {

	private final int pageSize;

	private final int numChannels;

	private final Queue<BufferOrEvent> bufferOrEvents;

	private final boolean[] closed;

	private int closedChannels;

	public MockInputGate(int pageSize, int numChannels, List<BufferOrEvent> bufferOrEvents) {
		this.pageSize = pageSize;
		this.numChannels = numChannels;
		this.bufferOrEvents = new ArrayDeque<BufferOrEvent>(bufferOrEvents);
		this.closed = new boolean[numChannels];
	}

	@Override
	public int getPageSize() {
		return pageSize;
	}

	@Override
	public int getSubInputGateCount() {
		return 0;
	}

	@Override
	public InputGate getSubInputGate(int index) {
		return null;
	}

	@Override
	public int getNumberOfInputChannels() {
		return numChannels;
	}

	@Override
	public boolean isFinished() {
		return bufferOrEvents.isEmpty();
	}

	@Override
	public boolean moreAvailable() {
		return !bufferOrEvents.isEmpty();
	}

	@Override
	public Optional<BufferOrEvent> getNextBufferOrEvent() {
		BufferOrEvent next = bufferOrEvents.poll();
		if (next == null) {
			return Optional.empty();
		}

		int channelIdx = next.getChannelIndex();
		if (closed[channelIdx]) {
			throw new RuntimeException("Inconsistent: Channel " + channelIdx
				+ " has data even though it is already closed.");
		}
		if (next.isEvent() && next.getEvent() instanceof EndOfPartitionEvent) {
			closed[channelIdx] = true;
			closedChannels++;
		}
		return Optional.of(next);
	}

	@Override
	public Optional<BufferOrEvent> getNextBufferOrEvent(InputGate subInputGate) throws IOException, InterruptedException {
		return getNextBufferOrEvent();
	}

	@Override
	public Optional<BufferOrEvent> pollNextBufferOrEvent() {
		return getNextBufferOrEvent();
	}

	@Override
	public Optional<BufferOrEvent> pollNextBufferOrEvent(InputGate subInputGate) {
		return getNextBufferOrEvent();
	}

	@Override
	public void requestPartitions() {
	}

	@Override
	public void sendTaskEvent(TaskEvent event) {
	}

	@Override
	public void registerListener(InputGateListener listener) {
	}

	@Override
	public InputChannel[] getAllInputChannels() {
		SingleInputGate inputGate = mock(SingleInputGate.class);
		when(inputGate.getConsumedPartitionType()).thenReturn(ResultPartitionType.PIPELINED);
		InputChannel[] inputChannels = new InputChannel[numChannels];
		for (int i = 0; i < inputChannels.length; i++) {
			InputChannel inputChannel = mock(InputChannel.class);
			when(inputChannel.getInputGate()).thenReturn(inputGate);
			inputChannels[i] = inputChannel;
		}
		return inputChannels;
	}
}
