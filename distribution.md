# Work Distribution for Redis Jepsen/Knossos Assignment

## Overview
The assignment is to extensively test a distributed Redis (with Sentinel) system for linearizability and other distributed systems properties, using Jepsen and Knossos. The work is split among 3 people to maximize coverage and minimize overlap.

---

## Person 1: Network Partition & Split-Brain Testing

**Focus:**  
- Simulate and test network partitions, split-brain, and network-related failures.

**Tasks:**  
- Write Jepsen tests that create various network partitions (using Docker, iptables, or Jepsen's built-in facilities).
- Test Sentinel and Redis behavior under:
  - Majority/minority partitions
  - Isolating the primary
  - Isolating replicas
  - Isolating Sentinels
- Simulate and detect split-brain scenarios (multiple primaries).
- Observe and document Sentinel failover and election behavior during partitions.
- Test system recovery after healing partitions.
- Deliver a report on data loss, consistency, and failover correctness under network faults.

**Deliverables:**  
- `network_partition_test.clj`
- `split_brain_test.clj`
- Documentation/report on findings

---

## Person 2: Consistency & Linearizability Testing (Knossos)

**Focus:**  
- Use Knossos to check linearizability and consistency under normal and failover conditions.

**Tasks:**  
- Instrument Redis Jepsen tests to record operation histories suitable for Knossos.
- Write Knossos models for Redis operations (register, set/get, CAS if possible).
- Run linearizability checks on histories from:
  - Normal operation
  - During failover
  - During/after network partitions
- Analyze and document any linearizability violations (lost writes, stale reads, etc).
- Test different workloads: single-key, multi-key, concurrent clients.
- Deliver a report on Redis's consistency guarantees in your setup.

**Deliverables:**  
- `knossos_linearizability_test.clj`
- `consistency_report.md`
- Example operation histories and Knossos output

---

## Person 3: Failover, Recovery, and Performance Testing

**Focus:**  
- Test Sentinel failover, recovery from failures, and performance under stress.

**Tasks:**  
- Write Jepsen tests that:
  - Kill the primary Redis node and observe Sentinel failover
  - Kill Sentinels and observe system behavior
  - Test data durability and correctness after failover
  - Test recovery from multiple simultaneous failures
- Measure and report:
  - Time to detect and complete failover
  - Data loss or unavailability windows
  - Throughput/latency before, during, and after failover
- Test Redis persistence (AOF/RDB) and recovery from disk
- Deliver a report on failover speed, data safety, and performance impact

**Deliverables:**  
- `failover_test.clj`
- `performance_test.clj`
- `failover_report.md`

---

## Shared/Integration Work

- Agree on a common Docker Compose or cluster setup.
- Share utility code for cluster discovery, metrics, and test data generation.
- Weekly sync to integrate findings and cross-validate results.
- Final integration test suite and summary report.

---