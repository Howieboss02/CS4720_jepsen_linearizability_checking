# Setup
## Build containers
`docker-compose -f docker-compose-jepsen.yml build`
## Run containers 
`docker-compose -f docker-compose-jepsen.yml up -d`
## Shut down
`docker-compose -f docker-compose-jepsen.yml down -v`

Open logs of jepsen-control

Wait for the input to look like this:

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
2025-06-17 19:05:43

inside jepsen-control you can run tests like:

lein run -m jepsen.redis-sentinel.test-ssh simple
lein run -m jepsen.redis-sentinel.test-ssh intensive  
lein run -m jepsen.redis-sentinel.test-ssh concurrent
lein run -m jepsen.redis-sentinel.test-ssh split-brain
lein run -m jepsen.redis-sentinel.test-ssh set-split-brain