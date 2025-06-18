#!/bin/bash

echo "=== Starting Jepsen node $(hostname) ==="

# Setup SSH
mkdir -p /root/.ssh
chmod 700 /root/.ssh

# Wait for SSH keys from control node
echo "Waiting for SSH keys from control node..."
max_wait=60
wait_time=0

while [ ! -f /shared-ssh/id_rsa.pub ] && [ $wait_time -lt $max_wait ]; do
  sleep 2
  wait_time=$((wait_time + 2))
  echo "Waiting for SSH keys... ${wait_time}s"
done

if [ ! -f /shared-ssh/id_rsa.pub ]; then
  echo "❌ SSH keys not found after ${max_wait}s"
  exit 1
fi

# Copy SSH keys
cp /shared-ssh/id_rsa.pub /root/.ssh/authorized_keys
chmod 600 /root/.ssh/authorized_keys

echo "✅ SSH keys configured for $(hostname)"

# Start SSH daemon
/usr/sbin/sshd -D &
ssh_pid=$!

# Wait for SSH daemon to start
sleep 3

if ps -p $ssh_pid > /dev/null; then
  echo "✅ SSH daemon started on $(hostname)"
else
  echo "❌ SSH daemon failed to start on $(hostname)"
fi

# Start Redis
echo "Starting Redis on $(hostname)..."
redis-server /etc/redis/redis.conf --daemonize yes
sleep 5

# Test Redis
if redis-cli ping 2>/dev/null | grep -q PONG; then
  echo "✅ Redis responding on $(hostname)"
else
  echo "❌ Redis not responding on $(hostname)"
  # Try to restart Redis
  redis-server /etc/redis/redis.conf --daemonize yes
  sleep 3
  if redis-cli ping 2>/dev/null | grep -q PONG; then
    echo "✅ Redis restarted successfully on $(hostname)"
  else
    echo "❌ Redis failed to start on $(hostname)"
  fi
fi

# Start Redis Sentinel
echo "Starting Redis Sentinel on $(hostname)..."

# Create log directory for Sentinel
mkdir -p /var/log/redis
touch /var/log/redis/sentinel-$(hostname).log
chown redis:redis /var/log/redis/sentinel-$(hostname).log 2>/dev/null || true

# Start Sentinel
redis-sentinel /etc/redis/sentinel.conf --daemonize yes
sleep 5

# Test Sentinel
if redis-cli -p 26379 ping 2>/dev/null | grep -q PONG; then
  echo "✅ Redis Sentinel responding on $(hostname)"
else
  echo "❌ Redis Sentinel not responding on $(hostname)"
  # Try to restart Sentinel
  echo "Attempting to restart Sentinel..."
  redis-sentinel /etc/redis/sentinel.conf --daemonize yes
  sleep 3
  if redis-cli -p 26379 ping 2>/dev/null | grep -q PONG; then
    echo "✅ Redis Sentinel restarted successfully on $(hostname)"
  else
    echo "❌ Redis Sentinel failed to start on $(hostname)"
    echo "Checking Sentinel configuration..."
    cat /etc/redis/sentinel.conf
  fi
fi

# Show Sentinel master info (if Sentinel is working)
echo "Checking Sentinel master info..."
redis-cli -p 26379 SENTINEL get-master-addr-by-name mymaster 2>/dev/null || echo "Sentinel master info not available yet"

# Show running processes
echo "Running Redis processes on $(hostname):"
ps aux | grep redis | grep -v grep || echo "No Redis processes found"

# Show listening ports
echo "Listening ports on $(hostname):"
netstat -tulpn 2>/dev/null | grep -E ':(6379|26379|22)' || echo "Port check not available"

echo "🟢 Node $(hostname) ready with Redis and Sentinel"

# Keep container running and show logs
echo "Monitoring logs..."
exec tail -f /var/log/redis/*.log /dev/null 2>/dev/null || exec tail -f /dev/null