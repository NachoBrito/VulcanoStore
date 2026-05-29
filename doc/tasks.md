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

* [x] **Task 4.1: Write comprehensive tests for `OffHeapKeyDir`**
  * *Red:* Write tests asserting:
    1. Correct O(1) hash resolution.
    2. Linear probing and hash collision resolution.
    3. Index boundary wrapping (when index wraps around the end of the flat `MemorySegment`).
    4. Safe allocation and immediate native deallocation upon closing the `Arena`.
* [x] **Task 4.2: Implement `OffHeapKeyDir` using JEP 454**
  * *Green:* Allocate contiguous memory using a `Confined Arena` and implement the 48-byte aligned slot layout and open-addressing lookup logic.

> [!NOTE]
> **Phase 4 Execution Report:** Built the custom zero-GC off-heap linear-probing index `OffHeapKeyDir` using Java 25 JEP 454 FFM APIs. Allocated contiguous memory via a confined `Arena`. Padded the open-addressing slot layout to exactly **48 bytes** to guarantee strict 64-bit alignment and satisfy FFM boundary checks. Developed parallel off-heap arrays for key verification. Tested all index operations, updates, tombstones, linear probes, boundary wrapping, and deallocation safety with 100% green tests.

---

## 📋 Phase 5: Multi-Segment Management & Crash Recovery
We will orchestrate multiple data log files and restore state on boot.

* [x] **Task 5.1: Write tests for Segment Rollover**
  * *Red:* Write tests verifying that when the active segment size threshold (e.g. configured to 1MB for testing) is reached, it closes, writes a placeholder, and opens a new active file with incremented `fileId`.
* [x] **Task 5.2: Write tests for Startup Crash Recovery**
  * *Red:* Write tests executing sequential writes, force-closing the engine (simulating a crash), and asserting that a fresh instance restores all keys from the disk logs.
* [x] **Task 5.3: Implement `StorageEngine` Segment Orchestrator**
  * *Green:* Coordinate multiple segments, active file selection, and full directory boot recovery.

> [!NOTE]
> **Phase 5 Execution Report:** Completed both RED and GREEN phases for multi-segment management and crash recovery.
> 1. Implemented a directory-scanning bootstrapper inside `StorageEngine` that automatically detects existing `.data` segment files, sorts them, and determines the correct active `fileId` sequence.
> 2. Added capacity tracking using `writeOffset` to trigger transparent segment rollovers.
> 3. Implemented `recover(OffHeapKeyDir)` to sequentially rebuild the off-heap index on startup by reading headers, payload sizes, and tracking key offsets, handling active updates and tombstone deletions.
> 4. Verified all Phase 5 unit and integration tests are 100% green (`mvn test` runs successfully for `StorageEngineTest` with 0 failures).

---

## 📋 Phase 6: Hint Files for Rapid Startup
We will add companion compact indexes for rapid startup.

* [x] **Task 6.1: Write tests for Hint File generation**
  * *Red:* Write tests verifying that when a segment is closed, a compact `.hint` file is written containing the offsets and sizes but excluding the actual values.
* [x] **Task 6.2: Write tests for Hint-driven Startup**
  * *Red:* Write tests asserting that the store loads the entire keyspace and populates the `OffHeapKeyDir` solely by scanning `.hint` files on reboot.
* [x] **Task 6.3: Implement Hint Serialization and Boot Loading**
  * *Green:* Implement hint generator and bootloader routines.

> [!NOTE]
> **Phase 6 Execution Report:** Successfully completed both TDD RED and GREEN phases for hint-driven rapid startup.
> 1. Implemented automatic `.hint` file serialization upon segment closure (triggered during both active write rollovers and normal database closes).
> 2. Structured `.hint` file records as `Timestamp (8B) + Offset (8B) + Value Size (4B) + Key Size (2B) + Key (Var)` according to binary specifications.
> 3. Enhanced `StorageEngine` constructor and directory scanning to discover unique segment IDs by analyzing both `.data` and `.hint` extensions.
> 4. Integrated hint-driven recovery (`recoverSegmentFromHint`) which sequentially loads compact `.hint` files into `OffHeapKeyDir` in RAM, enabling extremely rapid boot-time index rebuilding even in complete absence of physical `.data` logs.
> 5. Verified all hint-related unit and integration tests are 100% green with 0 failures.

---

## 📋 Phase 7: Background Compaction (Compactor)
We will implement background cleanups to reclaim deleted or obsolete data space.

* [x] **Task 7.1: Write tests for Log Compaction (Merging)**
  * *Red:* Write integration tests simulating multiple updates/deletions of keys and asserting that the merge processor successfully discards stale entries, reclaiming disk space.
* [x] **Task 7.2: Write tests for Atomic Index Swapping**
  * *Red:* Write tests verifying that index references (`fileId` and `offset`) swap atomically in the main index without blocking concurrent lookup commands.
* [x] **Task 7.3: Implement background `Compactor`**
  * *Green:* Implement background thread worker that merges inactive segments and commits updates.

> [!NOTE]
> **Phase 7 Execution Report:** Completed both TDD RED and GREEN phases for background compaction and atomic index swapping.
> 1. Developed `Compactor.java` containing the background worker thread loop, synchronous `compact()` triggers, and atomic coordinate updating.
> 2. Implemented active-record migrations: compacting inactive segments sequentially, checking the index to discard stale/deleted records, and migrating valid records to active segments.
> 3. Implemented safe cleanup in `StorageEngine`: closing memory-mapped file channels and physically deleting `.data` and `.hint` files from disk after compaction.
> 4. Addressed FFM concurrent thread limitations by switching `OffHeapKeyDir` to a `Shared Arena` (`Arena.ofShared()`) and synchronizing reads/swaps on the index block.
> 5. Verified all compaction, rollover, recovery, and concurrency swapping tests are 100% green.

---

## 📋 Phase 8: Benchmarks & Latency Profiling
We will validate that we meet the production-quality and zero-GC goals.

* [ ] **Task 8.1: Create Microbenchmarks**
  * Write programmatic benchmarks measuring point lookup and write throughput.
* [ ] **Task 8.2: GC & Latency Verification**
  * Profile execution using JVM tools (e.g., JFR or visual VM) to verify:
    1. Zero Garbage Collection pauses during read loops.
    2. Sub-millisecond average latency percentiles (99th percentile).
