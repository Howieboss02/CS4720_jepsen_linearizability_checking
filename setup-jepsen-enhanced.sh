#!/bin/bash
# filepath: c:\Users\rafal\RubymineProjects\CS4720_jepsen_linearizability_checking\setup-jepsen-enhanced.sh

echo "=== Building Enhanced Jepsen Environment ==="

# Stop existing containers
echo "Stopping existing containers..."
docker-compose -f docker-compose-jepsen.yml down 2>/dev/null || true

# Build images
echo "Building Jepsen control image..."
docker build -f Dockerfile.jepsen-control -t jepsen-control .

echo "Building Jepsen node image..."
docker build -f Dockerfile.jepsen-node -t jepsen-node .

# Create directories
echo "Creating directories..."
mkdir -p docker logs ssh-keys
chmod 755 logs ssh-keys

# Start environment
echo "Starting enhanced Jepsen environment..."
docker-compose -f docker-compose-jepsen.yml up -d

# Wait for startup
echo "Waiting for services to start..."
sleep 60

echo "=== Environment Ready! ==="
echo "Access control node: docker exec -it jepsen-control bash"
echo "Run tests: docker exec -it jepsen-control bash -c 'cd /jepsen && lein run help'"