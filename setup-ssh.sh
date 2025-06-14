#!/bin/bash
set -e

echo "Setting up SSH keys..."

# Generate SSH key if it doesn't exist
if [ ! -f /root/.ssh/id_rsa ]; then
    ssh-keygen -t rsa -b 2048 -f /root/.ssh/id_rsa -N ""
fi

# Copy keys to shared volume
mkdir -p /shared-ssh
cp /root/.ssh/id_rsa /shared-ssh/
cp /root/.ssh/id_rsa.pub /shared-ssh/
cp /root/.ssh/id_rsa.pub /shared-ssh/authorized_keys

# Set up SSH config
cat > /root/.ssh/config << 'SSH_CONFIG'
Host n1 n2 n3 n4 n5
    StrictHostKeyChecking no
    UserKnownHostsFile /dev/null
    PasswordAuthentication no
    PubkeyAuthentication yes
    IdentityFile /root/.ssh/id_rsa
SSH_CONFIG

chmod 600 /root/.ssh/config
chmod 600 /root/.ssh/id_rsa
chmod 644 /root/.ssh/id_rsa.pub

echo "SSH keys setup complete"

# Wait for nodes to be ready
sleep 10

# Test SSH connections
for node in n1 n2 n3 n4 n5; do
    echo "Testing SSH to $node..."
    for i in {1..30}; do
        if ssh -o ConnectTimeout=5 $node "echo 'SSH to $node successful'" 2>/dev/null; then
            echo "✓ SSH to $node working"
            break
        else
            echo "Waiting for $node... attempt $i/30"
            sleep 2
        fi
    done
done

echo "All SSH connections ready!"