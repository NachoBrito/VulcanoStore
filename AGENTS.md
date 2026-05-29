# VulcanoStore AI Developer Instructions (AGENTS.md)

Welcome to **VulcanoStore**! This file acts as a persistent set of developer guidelines and constraints for any AI coding assistant or agent working on this codebase. Please read and adhere to these guidelines to maintain consistency and architectural integrity.

---

## 🏗️ Core Architecture & Tech Stack

VulcanoStore is a production-quality, ultra-fast, in-process key-value database library:
1.  **Java 25:** Compiled under release 25 targets. Fully utilizes modern record classes and memory mapping.
2.  **Single-Threaded Execution:** The library is designed as a contention-free, thread-unsafe engine. Callers should synchronize externally or run it in a single worker thread context to bypass synchronization locks.
3.  **Log-Structured Storage (Bitcask-like):** All write transactions are sequentially appended to `.data` segments using off-heap zero-copy `MappedByteBuffer` structures. Segment size default is **128 MB**.
4.  **JEP 454 Off-Heap Index (`OffHeapKeyDir`):** Uses Java 25 Foreign Function & Memory (FFM) APIs to keep all key metadata in a flat, contiguous `MemorySegment` managed by a `Confined Arena`.
    *   **Zero-GC pauses:** Entirely invisible to the JVM garbage collector.
    *   **Fixed Size:** Uses open-addressing with linear probing. The capacity is determined on startup using `expectedKeys`.
    *   **Static Sizing Formula:** `Total Slots = expectedKeys / 0.7` (ensures a safe load factor of 70% to prevent collision clustering).
    *   **Over Capacity Safety:** When active key count reaches `expectedKeys`, `put` of a **new key** throws `IllegalStateException`. Overwriting **existing keys** is fully allowed since it reuses their allocated slot.

---

## 🚦 Mandatory Workflow Guidelines

Incoming agents **must** respect the following procedures:

### 1. Strict Test-Driven Development (TDD)
Never write production code before writing a test. For every feature or refactoring task, follow this loop:
*   **RED:** Write a failing JUnit 5 integration or unit test in `src/test/java`. Confirm the failure by running `mvn test`.
*   **GREEN:** Write the minimal production code necessary to pass the test. Re-run `mvn test` to confirm success.
*   **REFACTOR:** Clean up the implementation, optimize for zero heap allocations, ensure L1/L2 cache locality, and verify tests remain green.

### 2. Follow Step Boundaries & Execution Limits
*   Execute **only** the specific tasks requested by the user. 
*   **A Question is Not an Action:** If the user asks a question (e.g., "why is X Y?"), the agent must **only** answer the question. Do NOT proactively perform modifications, file changes, or execute code refactorings in response to a question unless the user explicitly commands or authorizes the change.
*   If requested to write a failing test (TDD RED phase), write only the test and a stub, run it, and pause. Do **not** proceed to write the GREEN implementation code until the user explicitly directs you to.

### 3. Documentation Integrity
*   Keep the design document `/doc/design.md` updated with any technical design choices, mathematical rationales, or binary layout modifications.
*   Keep the checklist `/doc/tasks.md` updated.
*   Document execution outcomes as standard GitHub alerts (`> [!NOTE]`) directly in `/doc/tasks.md` instead of cluttering the bulleted tasks list.

---

## 📂 Project Structure

*   `/doc/design.md` — Complete high-level architecture and binary structures.
*   `/doc/tasks.md` — Living tracking checklist and execution reports.
*   `/src/main/java/es/nachobrito/vulcanodb` — Core library classes (`VulcanoStore`, `VulcanoConfig`, etc.).
*   `/src/main/java/es/nachobrito/vulcanodb/storage` — Storage and index managers (`OffHeapKeyDir`, `BinaryRecord`, etc.).
*   `/src/test/java/es/nachobrito/vulcanodb` — Test suites (`VulcanoStoreTest`, `BinaryRecordTest`, etc.).
