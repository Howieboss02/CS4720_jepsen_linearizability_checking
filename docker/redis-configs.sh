#!/bin/bash
# Generate Redis and Sentinel configuration files for Docker

mkdir -p docker

# Redis n1 (master) configuration
cat > docker/redis-n1.conf << 'EOF'
port 6379
bind 0.0.0.0
timeout 0
loglevel notice
logfile ""
databases 16
save 900 1
save 300 10
save 60 10000
rdbcompression yes
dbfilename dump.rdb
dir /data
slave-serve-stale-data yes
slave-read-only yes
appendonly no
EOF

# Redis n2 (slave) configuration
cat > docker/redis-n2.conf << 'EOF'
port 6379
bind 0.0.0.0
timeout 0
loglevel notice
logfile ""
databases 16
save 900 1
save 300 10
save 60 10000
rdbcompression yes
dbfilename dump.rdb
dir /data
slave-serve-stale-data yes
slave-read-only yes
appendonly no
slaveof 172.20.0.10 6379
EOF

# Redis n3 (slave) configuration
cat > docker/redis-n3.conf << 'EOF'
port 6379
bind 0.0.0.0
timeout 0
loglevel notice
logfile ""
databases 16
save 900 1
save 300 10
save 60 10000
rdbcompression yes
dbfilename dump.rdb
dir /data
slave-serve-stale-data yes
slave-read-only yes
appendonly no
slaveof 172.20.0.10 6379
EOF

# Redis n4 (slave) configuration
cat > docker/redis-n4.conf << 'EOF'
port 6379
bind 0.0.0.0
timeout 0
loglevel notice
logfile ""
databases 16
save 900 1
save 300 10
save 60 10000
rdbcompression yes
dbfilename dump.rdb
dir /data
slave-serve-stale-data yes
slave-read-only yes
appendonly no
slaveof 172.20.0.10 6379
EOF

# Redis n5 (slave) configuration
cat > docker/redis-n5.conf << 'EOF'
port 6379
bind 0.0.0.0
timeout 0
loglevel notice
logfile ""
databases 16
save 900 1
save 300 10
save 60 10000
rdbcompression yes
dbfilename dump.rdb
dir /data
slave-serve-stale-data yes
slave-read-only yes
appendonly no
slaveof 172.20.0.10 6379
EOF

# Sentinel configurations for each node
for i in {1..5}; do
cat > docker/sentinel-n${i}.conf << EOF
port 26379
bind 0.0.0.0
sentinel monitor mymaster 172.20.0.10 6379 3
sentinel down-after-milliseconds mymaster 5000
sentinel parallel-syncs mymaster 1
sentinel failover-timeout mymaster 10000
logfile ""
EOF
done

echo "Docker configuration files created in docker/ directory"