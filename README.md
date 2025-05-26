# Jepsen Redis Test

This repository contains a Jepsen test that replicates the Redis partition tolerance analysis described in the [original Jepsen Redis blog post](http://aphyr.com/posts/283-jepsen-redis). The test demonstrates how Redis with Sentinel can lose up to 56% of acknowledged writes during network partitions.

## Overview

Redis is a fantastic data structure server that offers linearizable consistency when running on a single server. However, when configured with asynchronous primary-secondary replication and Redis Sentinel for high availability, it can experience significant data loss during network partitions.

This test reveals two critical failure modes:

1. **Asynchronous replication**: The primary continues accepting writes even when isolated from secondaries
2. **Split-brain scenarios**: Multiple primaries can exist simultaneously during partitions

## Prerequisites

- 5 nodes (n1-n5) with IPs configured in `salticid/redis.rb`
- Leiningen (Clojure build tool)
- Ruby with Salticid gem for cluster management
- Root access on all nodes

## PROJECT SETUP
- Step 1: Run the Setup Script
   ```bash
   # Create the bin directory
   mkdir -p ~/bin
   
   # Install Leiningen
   curl https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein > ~/bin/lein
   chmod +x ~/bin/lein
   
   # Add to PATH
   echo 'export PATH="$HOME/bin:$PATH"' >> ~/.zshrc
   source ~/.zshrc
   ```
- Step 2: Verify Installation
   ```bash
   lein version
   ```
  You should see something like Leiningen 2.x.x on Java...

## LOCAL TESTING WITH DOCKER
- Step 1: Generate Docker Configurations
   ```bash
   chmod +x docker/redis-configs.sh
   ./docker/redis-configs.sh
   ```
- Step 2: Start Redis Cluster
   ```bash
   docker-compose up -d
   ```
- Step 3: Check Cluster Status
   ```bash
   lein local status
   ```
- Step 4: Run Demo Test
   ```bash
   lein local demo
   ```
- Step 5: Clean Up
   ```bash
   docker-compose down
   ```

## EXPECTED DEMO OUTPUT
The local demo will show:
   ```bash
   === JEPSEN REDIS DEMO ===
   Phase 1: Initial writes (no partition)
   ..........
   Phase 2: Simulating network partition  
   Phase 3: Writes during partition
   ..........
   Phase 4: Healing partition
   Phase 5: Final read
   
   === ANALYSIS ===
   150 total writes attempted
   150 writes acknowledged  
   143 writes survived
   7 acknowledged writes lost!
   Lost values: [45 46 47 48 49 50 51]
   Acknowledgment rate: 1.0
   Loss rate: 0.046666667
   ```

## CONNECTION ISSUES
The local demo will show:
   ```bash
   # Test Redis connection manually
   redis-cli -h localhost -p 6380 ping
   
   # Test Sentinel connection  
   redis-cli -h localhost -p 26390 ping
   ```

## DOCKER ISSUES
The local demo will show:
   ```bash
   # Check if containers are running
   docker ps
   
   # View logs
   docker-compose logs redis-n1
   docker-compose logs sentinel-n1
   
   # Restart if needed
   docker-compose restart
   ```

## Setup

1. **Install dependencies:**
   ```bash
   # Install Leiningen
   curl https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein > ~/bin/lein
   chmod +x ~/bin/lein
   
   # Install Salticid
   gem install salticid
   ```

2. **Configure cluster nodes:**
   Edit `salticid/redis.rb` to match your cluster IPs:
   ```ruby
   host 'n1', '10.10.3.242'
   host 'n2', '10.10.3.52'  
   host 'n3', '10.10.3.199'
   host 'n4', '10.10.3.101'
   host 'n5', '10.10.3.95'
   ```

3. **Set up Redis on all nodes:**
   ```bash
   salticid redis.setup
   ```

## Running the Test

1. **Start Redis cluster:**
   ```bash
   # Start Redis servers
   salticid redis.start
   
   # Start Redis Sentinel 
   salticid redis.sentinel
   ```

2. **Check cluster status:**
   ```bash
   salticid redis.replication
   ```

3. **Run the Jepsen test:**
   ```bash
   lein run redis
   ```

4. **In another terminal, create network partition:**
   ```bash
   # This partitions n1,n2 from n3,n4,n5
   salticid jepsen.partition
   ```

5. **After some time, heal the partition:**
   ```bash
   salticid jepsen.heal
   ```

6. **Stop the cluster:**
   ```bash
   salticid redis.stop
   ```

## Expected Results

The test should demonstrate:

- **Write Loss**: ~56% of acknowledged writes disappear
- **Split-brain**: Two primaries accepting writes simultaneously
- **Inconsistent State**: Different clients see different data

Example output:
```
2000 total
1998 acknowledged  
872 survivors
1126 acknowledged writes lost! (╯°□°）╯︵ ┻━┻
0.999 ack rate
0.5635636 loss rate
```

## Architecture

### Components

- **Redis Servers**: Primary-secondary replication (n1 is initial primary)
- **Redis Sentinel**: Monitors Redis servers and handles failover
- **Jepsen Client**: Performs set operations and tracks consistency
- **Network Partitioning**: iptables-based partition simulation

### Test Flow

1. Clients continuously add integers to a Redis set
2. Network partition isolates minority (n1,n2) from majority (n3,n4,n5)
3. Minority primary continues accepting writes
4. Majority elects new primary and accepts different writes
5. Partition heals, old primary demoted
6. Analysis reveals write loss and inconsistency

## Files Structure

```
├── project.clj                 # Leiningen project configuration
├── src/
│   └── jepsen/
│       ├── redis.clj           # Main test implementation
│       └── redis/
│           └── db.clj          # Database setup and management
├── salticid/
│   ├── redis.rb               # Redis cluster management
│   └── jepsen.rb              # Network partitioning tools
└── README.md                  # This file
```

## Key Insights

This test demonstrates why Redis Sentinel is not suitable for:

- **Lock services**: Can issue same lock multiple times
- **Queues**: Can drop or duplicate messages
- **Primary databases**: Violates linearizability guarantees

Redis excels as a cache or for use cases where occasional data loss is acceptable.

## Troubleshooting

### Common Issues

1. **Connection timeouts**: Increase timeout values in client connection specs
2. **Sentinel not electing new primary**: Check quorum configuration (should be > N/2)
3. **Partition not working**: Verify iptables rules with `salticid jepsen.status`
4. **Redis won't start**: Check logs at `/var/log/redis.log`

### Debugging Commands

```bash
# Check Redis status on all nodes
salticid redis.replication

# View current network rules
salticid jepsen.status

# Monitor Redis logs
tail -f /var/log/redis.log

# Monitor Sentinel logs  
tail -f /var/log/sentinel.log
```

## References

- [Original Jepsen Redis analysis](http://aphyr.com/posts/283-jepsen-redis)
- [Redis Sentinel documentation](https://redis.io/topics/sentinel)
- [Jepsen framework](https://github.com/jepsen-io/jepsen)

## License

Eclipse Public License - same as Clojure