.PHONY: build up down test clean logs help

# Default target
help:
	@echo "Available commands:"
	@echo "  build     - Build all Docker images"
	@echo "  up        - Start all services"
	@echo "  down      - Stop all services"
	@echo "  test      - Run Jepsen tests"
	@echo "  test-all  - Run comprehensive test suite"
	@echo "  clean     - Clean up containers and volumes"
	@echo "  logs      - Show logs from all services"
	@echo "  shell     - Open shell in Jepsen container"

# Build Docker images
build:
	docker-compose build

# Start services
up:
	docker-compose up -d
	@echo "Waiting for services to be ready..."
	@sleep 30
	@echo "Services should be ready now"

# Stop services
down:
	docker-compose down

# Run basic linearizability test
test: up
	docker-compose exec jepsen-control lein run test \
		--nodes redis-primary,redis-replica1,redis-replica2 \
		--workload register \
		--nemesis partition \
		--time-limit 60 \
		--concurrency 5 \
		--rate 10

# Run comprehensive test suite
test-all: up
	docker-compose exec jepsen-control lein test

# Run specific test with custom parameters
test-custom: up
	docker-compose exec jepsen-control lein run test \
		--nodes redis-primary,redis-replica1,redis-replica2 \
		--workload $(WORKLOAD) \
		--nemesis $(NEMESIS) \
		--time-limit $(TIME_LIMIT) \
		--concurrency $(CONCURRENCY) \
		--rate $(RATE)

# Clean up everything
clean:
	docker-compose down -v
	docker system prune -f

# Show logs
logs:
	docker-compose logs -f

# Show Redis logs specifically
logs-redis:
	docker-compose logs -f redis-primary redis-replica1 redis-replica2

# Show Sentinel logs
logs-sentinel:
	docker-compose logs -f redis-sentinel1 redis-sentinel2 redis-sentinel3

# Open shell in Jepsen container
shell:
	docker-compose exec jepsen-control /bin/bash

# Check Redis cluster status
status:
	@echo "=== Redis Primary Status ==="
	docker-compose exec redis-primary redis-cli info replication
	@echo "=== Sentinel Status ==="
	docker-compose exec redis-sentinel1 redis-cli -p 26379 sentinel masters

# Copy results out of container
results:
	docker cp jepsen-control:/results ./results/
	@echo "Results copied to ./results/"

# Run with different nemesis types
test-partition: up
	docker-compose exec jepsen-control lein run test \
		--nodes redis-primary,redis-replica1,redis-replica2 \
		--workload register \
		--nemesis partition \
		--time-limit 120

test-kill: up
	docker-compose exec jepsen-control lein run test \
		--nodes redis-primary,redis-replica1,redis-replica2 \
		--workload register \
		--nemesis kill \
		--time-limit 90

test-none: up
	docker-compose exec jepsen-control lein run test \
		--nodes redis-primary,redis-replica1,redis-replica2 \
		--workload register \
		--nemesis none \
		--time-limit 60

# Development helpers
dev-setup: build up
	@echo "Development environment ready!"
	@echo "Run 'make test' to start testing"
	@echo "Run 'make shell' to access the Jepsen container"

restart:
	docker-compose restart

# Validate setup
validate:
	@echo "Validating Redis Sentinel setup..."
	@docker-compose exec redis-primary redis-cli ping
	@docker-compose exec redis-sentinel1 redis-cli -p 26379 ping
	@echo "Setup validation complete!"