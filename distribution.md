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

Person 1 (Network Partition & Split-Brain Testing)
Dependencies:

Depends on Person 3:
To validate failover behavior during network partitions and split-brain scenarios, Person 1 relies on Person 3's work on failover and recovery testing.
Person 3's insights into failover timing and behavior will help Person 1 understand how Redis and Sentinel handle partitions.
Provides to Person 2:
Operation histories and scenarios from network partitions and split-brain tests are critical for Person 2 to analyze linearizability and consistency under these conditions.

Person 2 (Consistency & Linearizability Testing)
Dependencies:

Depends on Person 1:
Needs operation histories from network partition and split-brain tests to check for linearizability violations during these scenarios.
Depends on Person 3:
Needs operation histories from failover and recovery tests to analyze consistency during and after failover.
Provides to Person 3:
Consistency analysis results (e.g., linearizability violations) can help Person 3 understand the impact of failover and recovery on data correctness.

Person 3 (Failover, Recovery, and Performance Testing)
Dependencies:

Depends on Person 1:
Needs network partition and split-brain scenarios to test failover and recovery behavior under these conditions.
Depends on Person 2:
Consistency analysis from Person 2 helps validate that failover and recovery maintain data correctness.
Provides to Person 1:
Insights into failover behavior and recovery times during network partitions and split-brain scenarios.
Provides to Person 2:
Operation histories from failover and recovery tests for linearizability analysis.
Shared/Integration Work
All three persons depend on a common cluster setup (e.g., Docker Compose or Redis cluster configuration).
Weekly syncs are necessary to share findings, validate results, and ensure consistency across tests.
Final integration tests and the summary report require contributions from all three persons.
Summary of Dependencies
Person 1 → Person 3: Needs failover insights for network partition tests.
Person 1 → Person 2: Provides operation histories for linearizability analysis.
Person 2 → Person 1: Validates consistency during network partitions.
Person 2 → Person 3: Validates consistency during failover and recovery.
Person 3 → Person 1: Provides failover behavior insights for partition tests.
Person 3 → Person 2: Provides operation histories for consistency analysis.