/*******************************************************************************
 * Copyright (c) 2026 Jeongho Nam and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Jeongho Nam - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.ls.core.internal;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Serializes document-buffer mutations against one bulk graph capture. */
public final class GraphSnapshotLock {

	private static final ReentrantReadWriteLock LOCK = new ReentrantReadWriteLock(true);

	private GraphSnapshotLock() {
	}

	public static Lock readLock() {
		return LOCK.readLock();
	}

	public static Lock writeLock() {
		return LOCK.writeLock();
	}

	/** Fail closed if code mutates a default-owner working copy outside the lock. */
	public static void assertWriteLocked() {
		if (!LOCK.isWriteLockedByCurrentThread()) {
			throw new IllegalStateException("A default-owner working copy changed outside the graph snapshot lock");
		}
	}
}
