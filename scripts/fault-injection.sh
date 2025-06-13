#!/bin/bash
# filepath: scripts/fault-injection.sh

# Helper script for manual fault injection during testing
case "$1" in
  "partition")
    echo "Creating network partition..."
    docker network disconnect redis-network jepsen-redis-replica1
    ;;
  "heal")
    echo "Healing network partition..."
    docker network connect redis-network jepsen-redis-replica1
    ;;
  "crash")
    echo "Crashing Redis node..."
    docker stop jepsen-redis-replica1
    ;;
  "recover")
    echo "Recovering Redis node..."
    docker start jepsen-redis-replica1
    ;;
  *)
    echo "Usage: $0 {partition|heal|crash|recover}"
    exit 1
    ;;
esac