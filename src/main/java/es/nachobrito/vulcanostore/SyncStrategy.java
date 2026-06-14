/*
 *    Copyright 2025 Nacho Brito
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package es.nachobrito.vulcanostore;

/**
 * Defines the synchronization strategy for flushing MappedByteBuffer dirty pages to physical disk.
 */
public enum SyncStrategy {
    /**
     * Call force() after every write operation. Restricts write throughput to disk latency but guarantees absolute durability.
     */
    SYNC_ALWAYS,

    /**
     * Trigger background force() flushes periodically at a configured interval.
     */
    SYNC_INTERVAL,

    /**
     * Let the OS manage dirty page flushing asynchronously. Provides maximum execution speed.
     */
    SYNC_NONE
}
