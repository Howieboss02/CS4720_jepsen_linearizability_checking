#!/usr/bin/env ruby

# Salticid configuration for Redis Jepsen test
# This provides cluster management commands

host 'n1', '10.10.3.242'
host 'n2', '10.10.3.52'
host 'n3', '10.10.3.199'
host 'n4', '10.10.3.101'
host 'n5', '10.10.3.95'

# Redis installation and setup
task :setup do
  sudo do
    exec! 'apt-get update'
    exec! 'apt-get install -y build-essential tcl wget'
    exec! 'mkdir -p /opt/redis'

    cd '/opt/redis' do
      exec! 'wget http://download.redis.io/releases/redis-2.6.13.tar.gz'
      exec! 'tar xzf redis-2.6.13.tar.gz --strip-components 1'
      exec! 'make'
    end

    exec! 'mkdir -p /etc/redis /var/lib/redis /var/log'
  end
end

# Generate Redis configuration
def redis_config(is_master, master_host)
  config = <<-EOF
port 6379
bind 0.0.0.0
timeout 0
loglevel notice
logfile /var/log/redis.log
databases 16
save 900 1
save 300 10
save 60 10000
rdbcompression yes
dbfilename dump.rdb
dir /var/lib/redis/
slave-serve-stale-data yes
slave-read-only yes
appendonly no
EOF

  unless is_master
    config += "\nslaveof #{master_host} 6379\n"
  end

  config
end

# Generate Sentinel configuration
def sentinel_config(master_host)
  <<-EOF
port 26379
bind 0.0.0.0
sentinel monitor mymaster #{master_host} 6379 3
sentinel down-after-milliseconds mymaster 5000
sentinel parallel-syncs mymaster 1
sentinel failover-timeout mymaster 10000
logfile /var/log/sentinel.log
EOF
end

# Start Redis servers
task :start do
  master_host = hosts.first.name

  on hosts do
    is_master = (host.name == master_host)

    sudo do
      # Stop any existing processes
      exec 'killall -9 redis-server || true'
      exec 'killall -9 redis-sentinel || true'

      # Write configurations
      File.open('/etc/redis/redis.conf', 'w') do |f|
        f.write(redis_config(is_master, master_host))
      end

      File.open('/etc/redis/sentinel.conf', 'w') do |f|
        f.write(sentinel_config(master_host))
      end

      # Start Redis
      exec! '/opt/redis/src/redis-server /etc/redis/redis.conf &'
      sleep 2
    end
  end
end

# Start Redis Sentinel
task :sentinel do
  on hosts do
    sudo do
      exec! '/opt/redis/src/redis-sentinel /etc/redis/sentinel.conf &'
      sleep 2
    end
  end
end

# Stop Redis and Sentinel
task :stop do
  on hosts do
    sudo do
      exec 'killall -9 redis-server || true'
      exec 'killall -9 redis-sentinel || true'
    end
  end
end

# Check replication status
task :replication do
  on hosts do
    begin
      result = exec('/opt/redis/src/redis-cli INFO replication')
      puts "#{host.name}:"
      puts result
      puts "---"
    rescue
      puts "#{host.name}: Redis not responding"
    end
  end
end

# Clean up data
task :wipe do
  on hosts do
    sudo do
      exec 'rm -f /var/lib/redis/dump.rdb'
      exec 'rm -f /var/log/redis.log'
      exec 'rm -f /var/log/sentinel.log'
    end
  end
end