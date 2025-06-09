# Redis Sentinel Distributed Systems Testing - Work Distribution

## Project Overview
This project tests Redis Sentinel's distributed behavior under various failure scenarios using Jepsen-style testing approaches with Knossos linearizability checking. The work is divided among 3 team members to comprehensively test different aspects of the distributed system.

---

## Person 1: Network Partition & Split-Brain Testing

### Primary Focus
Network-level failures, Byzantine scenarios, and partition tolerance testing

### Core Responsibilities
- Implement network partition simulation using `iptables`
- Test Redis Sentinel behavior during various network failure scenarios
- Detect and analyze split-brain conditions
- Validate partition healing and recovery mechanisms

### Specific Tasks

#### 1. Network Partition Implementation
- [ ] Create network isolation functions using Docker + iptables
- [ ] Implement asymmetric partitions (primary isolated from subset of replicas)
- [ ] Implement symmetric partitions (clean 50/50 network splits)
- [ ] Test minority/majority partition scenarios
- [ ] Simulate gradual network degradation

#### 2. Split-Brain Detection & Analysis
- [ ] Create scenarios where multiple nodes believe they're primary
- [ ] Test concurrent writes to different "primary" nodes during partition
- [ ] Analyze conflict resolution mechanisms when partition heals
- [ ] Implement Byzantine failure scenarios (nodes sending conflicting information)
- [ ] Test Sentinel quorum behavior under various partition conditions

#### 3. Partition Recovery Testing
- [ ] Test data consistency after partition healing
- [ ] Analyze write conflicts and resolution strategies
- [ ] Verify client behavior during partition recovery
- [ ] Test cascading failures during recovery process

### Deliverables