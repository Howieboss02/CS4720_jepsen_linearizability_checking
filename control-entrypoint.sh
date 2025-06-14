#!/bin/bash

echo "=== Jepsen control node starting ==="

# Setup SSH key
mkdir -p /root/.ssh
chmod 700 /root/.ssh

# Force overwrite existing keys
rm -f /root/.ssh/id_rsa /root/.ssh/id_rsa.pub /root/.ssh/authorized_keys

ssh-keygen -t rsa -b 2048 -f /root/.ssh/id_rsa -N "" -q
cat /root/.ssh/id_rsa.pub > /root/.ssh/authorized_keys
chmod 600 /root/.ssh/id_rsa /root/.ssh/authorized_keys
chmod 644 /root/.ssh/id_rsa.pub

# SSH config
cat <<EOF > /root/.ssh/config
Host n1 n2 n3 n4 n5
    StrictHostKeyChecking no
    UserKnownHostsFile /dev/null
    IdentityFile /root/.ssh/id_rsa
    ConnectTimeout 5
    PasswordAuthentication no
    PubkeyAuthentication yes
EOF
chmod 600 /root/.ssh/config

# Share SSH keys
mkdir -p /shared-ssh
cp /root/.ssh/id_rsa* /shared-ssh/
cp /root/.ssh/config /shared-ssh/
chmod 644 /shared-ssh/id_rsa.pub
chmod 600 /shared-ssh/id_rsa

echo "SSH keys generated and shared"

# Wait for nodes to start and copy keys
echo "Waiting for nodes to copy SSH keys..."
sleep 30

# Test SSH and Redis with retry logic
for node in n1 n2 n3 n4 n5; do
  echo "Testing SSH to $node..."
  max_attempts=10
  attempt=1
  
  while [ $attempt -le $max_attempts ]; do
    if ssh -o ConnectTimeout=5 -o BatchMode=yes $node "hostname" 2>/dev/null; then
      echo "✅ SSH to $node successful"
      
      # Test Redis
      if ssh $node "redis-cli ping" 2>/dev/null | grep -q PONG; then
        echo "✅ Redis on $node responding"
      else
        echo "⚠️ Redis on $node not responding"
      fi
      break
    else
      echo "⏳ SSH to $node failed, attempt $attempt/$max_attempts"
      sleep 3
      attempt=$((attempt + 1))
    fi
  done
  
  if [ $attempt -gt $max_attempts ]; then
    echo "❌ Failed to connect to $node after $max_attempts attempts"
  fi
done

echo "=== Jepsen control ready ==="
echo "Available commands:"
echo "  cd /jepsen"
echo "  lein run test --test-name linearizability --time-limit 60"
echo "  lein run test --test-name split-brain --time-limit 120"
echo ""
echo "Debug commands:"
echo "  ssh n1 'redis-cli info'"
echo "  docker logs n1"
echo ""
echo "Container will stay running. Use 'docker exec -it jepsen-control bash' to interact."
echo ""

# Keep container running indefinitely
tail -f /dev/null