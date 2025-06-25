# Setup
Make sure that all of the .sh scripts have LF (linux) encodings
## Build containers
```bash
docker-compose -f docker-compose-jepsen.yml build
```

## Run containers 
```bash
docker-compose -f docker-compose-jepsen.yml up -d
```

## Shut down
```bash
docker-compose -f docker-compose-jepsen.yml down -v
```

## Monitor startup

Open logs of jepsen-control and wait for the input to look like this:

```
2025-06-17 19:05:12 === Jepsen control node starting ===
2025-06-17 19:05:12 SSH keys generated and shared
2025-06-17 19:05:12 Waiting for nodes to copy SSH keys...
2025-06-17 19:05:42 Testing SSH to n1...
2025-06-17 19:05:42 n1
2025-06-17 19:05:42 ✅ SSH to n1 successful
2025-06-17 19:05:42 ✅ Redis on n1 responding
2025-06-17 19:05:42 Testing SSH to n2...
2025-06-17 19:05:42 n2
2025-06-17 19:05:42 ✅ SSH to n2 successful
2025-06-17 19:05:43 ✅ Redis on n2 responding
2025-06-17 19:05:43 Testing SSH to n3...
2025-06-17 19:05:43 n3
2025-06-17 19:05:43 ✅ SSH to n3 successful
2025-06-17 19:05:43 ✅ Redis on n3 responding
2025-06-17 19:05:43 Testing SSH to n4...
2025-06-17 19:05:43 n4
2025-06-17 19:05:43 ✅ SSH to n4 successful
2025-06-17 19:05:43 ✅ Redis on n4 responding
2025-06-17 19:05:43 Testing SSH to n5...
2025-06-17 19:05:43 n5
2025-06-17 19:05:43 ✅ SSH to n5 successful
2025-06-17 19:05:43 ✅ Redis on n5 responding
2025-06-17 19:05:43 === Jepsen control ready ===
2025-06-17 19:05:43 Available commands:
2025-06-17 19:05:43   cd /jepsen
2025-06-17 19:05:43   lein run test [Test Name]
2025-06-17 19:05:43 
2025-06-17 19:05:43 Debug commands:
2025-06-17 19:05:43   ssh n1 'redis-cli info'
2025-06-17 19:05:43   docker logs n1
2025-06-17 19:05:43 
2025-06-17 19:05:43 Container will stay running. Use 'docker exec -it jepsen-control bash' to interact.
```

## Running Tests

Inside jepsen-control you can run tests like:

# REDIS SENTINEL JEPSEN TESTS

**Usage:** `lein run -m jepsen.redis-sentinel.main [TEST_NAME]`

## Available Tests

### Basic Tests
- `simple` - Basic register test (30s, 3 threads)
- `intensive` - High-concurrency test (3min, 15 threads)  
- `concurrent` - Concurrent test with detailed analysis (3min)

### Partition Tests
- `split-brain` - Network partition test (3min)
- `set-split-brain` - SET split-brain with data divergence (3min)

### Failover Tests
- `majorities-ring` - Ring partition failover test (3min)

### Network Tests
- `flapping-partitions` - Rapid partition/heal cycles (3min)
- `bridge-partitions` - Chain-like connectivity (3min)

### Latency Tests
- `latency-injection` - Network latency injection (3min)
- `extreme-latency` - EXTREME latency injection (3min)

### Bank Tests
- `bank-simple` - Bank Test (x min)

## Examples

```bash
lein run -m jepsen.redis-sentinel.main simple
lein run -m jepsen.redis-sentinel.main split-brain
lein run -m jepsen.redis-sentinel.main majorities-ring
lein run -m jepsen.redis-sentinel.main extreme-latency
lein run -m jepsen.redis-sentinel.main bank-simple
```

## Prerequisites
- Docker containers running: n1, n2, n3, n4, n5
- Redis + Sentinel configured on each node
- SSH access with key: /root/.ssh/id_rsa
- Network: 172.20.0.11-15 (n1-n5)