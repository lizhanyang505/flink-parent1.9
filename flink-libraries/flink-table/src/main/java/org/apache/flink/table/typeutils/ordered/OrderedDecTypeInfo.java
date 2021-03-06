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

package org.apache.flink.table.typeutils.ordered;

import org.apache.flink.annotation.Internal;
import org.apache.flink.table.dataformat.Decimal;
import org.apache.flink.table.typeutils.DecimalSerializer;

import java.util.Arrays;

/**
 * TypeInformation for Decimal.
 * <p>
 *   This type includes `precision` and `scale`, similar to SQL DECIMAL.
 * </p>
 */
// Note that this class is not a subtype of NumericTypeInfo, which in its current
// semantics only covers Java's numeric primitive types.
@Internal
public class OrderedDecTypeInfo extends OrderedBasicTypeInfo<Decimal> {

	private static final long serialVersionUID = 1L;

	public static OrderedDecTypeInfo of(int precision, int scale, boolean asc) {
		return new OrderedDecTypeInfo(precision, scale, asc);
	}

	private final int precision;

	private final int scale;

	private final boolean asc;

	public OrderedDecTypeInfo(int precision, int scale, boolean asc) {
		super(
			Decimal.class,
			new OrderedDecimalSerializer(precision, scale, asc ? OrderedBytes.Order.ASCENDING : OrderedBytes.Order.DESCENDING),
			new DecimalSerializer(precision, scale));
		this.precision = precision;
		this.scale = scale;
		this.asc = asc;
	}

	@Override
	public String toString() {
		return String.format("Decimal(%d,%d)", precision(), scale());
	}

	@Override
	public boolean equals(Object obj) {
		if (!(obj instanceof OrderedDecTypeInfo)) {
			return false;
		}
		OrderedDecTypeInfo that = (OrderedDecTypeInfo) obj;
		return this.precision() == that.precision() && this.scale() == that.scale() && this.asc == that.asc;
	}

	@Override
	public int hashCode() {
		int h0 = this.getClass().getCanonicalName().hashCode();
		return Arrays.hashCode(new int[]{h0, precision(), scale(), asc ? 0 : 1});
	}

	public int precision() {
		return precision;
	}

	public int scale() {
		return scale;
	}
}
