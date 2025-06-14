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

echo "🟢 Node $(hostname) ready"
exec tail -f /dev/null