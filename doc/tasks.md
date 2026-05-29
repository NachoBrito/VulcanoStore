# VulcanoStore: TDD Implementation Plan & Tasks Checklist

This document details the tasks required to implement VulcanoStore in a strict **Test-Driven Development (TDD)** fashion. For every functional task, we will follow the TDD lifecycle:
1. **RED:** Write a failing unit or integration test asserting the desired behavior.
2. **GREEN:** Write the minimal production code necessary to pass the test.
3. **REFACTOR:** Clean up, optimize for memory allocation, and verify the tests remain green.

---

## 📋 Phase 1: Maven Setup & Environment Prep
* [x] **Task 1.1: Modernize `pom.xml` (TDD Infrastructure)**
  * Update compile and target settings to **Java 25**.
  * Add modern **JUnit Jupiter (JUnit 5)** dependencies.
  * *Verification:* Run `mvn clean test` and verify the build succeeds with a green empty suite.

> [!NOTE]
> **Phase 1 Execution Report:** Updated `pom.xml` to compile using Java 25 compiler rules and integrated JUnit Jupiter 5.10.2 dependencies. Migrated the legacy unit test template `AppTest.java` from JUnit 3 to JUnit 5 assertions. Verified compile and execution via `mvn clean test` which successfully passed with 1 test executed, 0 failures, and 0 errors.

---

## 📋 Phase 2: Configuration & API Interface
We will define the core programmatic contract and configuration variables.

* [x] **Task 2.1: Write failing tests for Configuration Validation**
  * *Red:* Write tests asserting `VulcanoConfig` rejects invalid paths, negative segment sizes, or invalid load factors.
* [x] **Task 2.2: Implement `VulcanoConfig`**
  * *Green:* Implement parameter validation and defaults (default segment size: 128 MB, expected capacity: 10,000,000 keys).
* [x] **Task 2.3: Define `VulcanoStore` API Interface**
  * Create the programmatic interface (`put`, `get`, `delete`, `exists`, `close`).
* [x] **Task 2.4: Write failing API lifecycle tests**
  * *Red:* Write integration tests against a mock/stub `VulcanoStore` verifying the fundamental API behavior and String wrappers.

> [!NOTE]
> **Phase 2 Execution Report:** Defined the programmatic `VulcanoStore` interface exposing modern byte array APIs and custom UTF-8 String defaults. Implemented `VulcanoConfig` with complete validations and defaults, fully documented with Javadocs. Wrote `VulcanoStoreTest` lifecycle integration tests and verified they successfully fail on our `VulcanoStoreImpl` skeleton stubs (entering the TDD RED phase).

---

## 📋 Phase 3: Binary Storage & Single-Segment Operations
We will establish serialization and single-file memory-mapped access.

* [x] **Task 3.1: Write tests for Binary Record Serialization**
  * *Red:* Write tests verifying that key-value records (with timestamps, sizes, and raw bytes) serialize into byte structures and deserialize back with absolute fidelity.
* [x] **Task 3.2: Implement Serialization Utils**
  * *Green:* Implement low-overhead serializers without excessive intermediate object allocations.
* [x] **Task 3.3: Write tests for Single-Segment Reading and Writing**
  * *Red:* Write tests asserting that an active segment file writes records via `MappedByteBuffer` and reads them back directly from specific file offsets.
* [x] **Task 3.4: Implement Single-Segment Append Engine**
  * *Green:* Implement active log append operations using off-heap `MappedByteBuffer`.

> [!NOTE]
> **Phase 3 Execution Report:** Implemented sequential low-overhead binary serialization of records directly to ByteBuffers. Created `FileSegment` managing native off-heap memory mapping of segment files via `MappedByteBuffer`. Developed offset-isolated slice reading through stateless buffer duplicates. All Phase 3 unit tests (`BinaryRecordTest` and `FileSegmentTest`) compile under Java 25 targets and pass successfully with 0 failures.

---

## 📋 Phase 4: Zero-GC Off-Heap FFM Index (`OffHeapKeyDir`)
We will build the custom off-heap linear-probing index using Java 25 Foreign Function & Memory APIs.

* [ ] **Task 4.1: Write comprehensive tests for `OffHeapKeyDir`**
  * *Red:* Write tests asserting:
    1. Correct O(1) hash resolution.
    2. Linear probing and hash collision resolution.
    3. Index boundary wrapping (when index wraps around the end of the flat `MemorySegment`).
    4. Safe allocation and immediate native deallocation upon closing the `Arena`.
* [ ] **Task 4.2: Implement `OffHeapKeyDir` using JEP 454**
  * *Green:* Allocate contiguous memory using a `Confined Arena` and implement the 42-byte slot layout and open-addressing lookup logic.

---

## 📋 Phase 5: Multi-Segment Management & Crash Recovery
We will orchestrate multiple data log files and restore state on boot.

* [ ] **Task 5.1: Write tests for Segment Rollover**
  * *Red:* Write tests verifying that when the active segment size threshold (e.g. configured to 1MB for testing) is reached, it closes, writes a placeholder, and opens a new active file with incremented `fileId`.
* [ ] **Task 5.2: Write tests for Startup Crash Recovery**
  * *Red:* Write tests executing sequential writes, force-closing the engine (simulating a crash), and asserting that a fresh instance restores all keys from the disk logs.
* [ ] **Task 5.3: Implement `StorageEngine` Segment Orchestrator**
  * *Green:* Coordinate multiple segments, active file selection, and full directory boot recovery.

---

## 📋 Phase 6: Hint Files for Rapid Startup
We will add companion compact indexes for rapid startup.

* [ ] **Task 6.1: Write tests for Hint File generation**
  * *Red:* Write tests verifying that when a segment is closed, a compact `.hint` file is written containing the offsets and sizes but excluding the actual values.
* [ ] **Task 6.2: Write tests for Hint-driven Startup**
  * *Red:* Write tests asserting that the store loads the entire keyspace and populates the `OffHeapKeyDir` solely by scanning `.hint` files on reboot.
* [ ] **Task 6.3: Implement Hint Serialization and Boot Loading**
  * *Green:* Implement hint generator and bootloader routines.

---

## 📋 Phase 7: Background Compaction (Compactor)
We will implement background cleanups to reclaim deleted or obsolete data space.

* [ ] **Task 7.1: Write tests for Log Compaction (Merging)**
  * *Red:* Write integration tests simulating multiple updates/deletions of keys and asserting that the merge processor successfully discards stale entries, reclaiming disk space.
* [ ] **Task 7.2: Write tests for Atomic Index Swapping**
  * *Red:* Write tests verifying that index references (`fileId` and `offset`) swap atomically in the main index without blocking concurrent lookup commands.
* [ ] **Task 7.3: Implement background `Compactor`**
  * *Green:* Implement background thread worker that merges inactive segments and commits updates.

---

## 📋 Phase 8: Benchmarks & Latency Profiling
We will validate that we meet the production-quality and zero-GC goals.

* [ ] **Task 8.1: Create Microbenchmarks**
  * Write programmatic benchmarks measuring point lookup and write throughput.
* [ ] **Task 8.2: GC & Latency Verification**
  * Profile execution using JVM tools (e.g., JFR or visual VM) to verify:
    1. Zero Garbage Collection pauses during read loops.
    2. Sub-millisecond average latency percentiles (99th percentile).
