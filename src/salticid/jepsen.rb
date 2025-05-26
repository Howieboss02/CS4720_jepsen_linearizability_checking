#!/usr/bin/env ruby

# Network partitioning tools for Jepsen testing

namespace :jepsen do

  # Create a network partition isolating n1,n2 from n3,n4,n5
  task :partition do
    # Block traffic from n1,n2 to n3,n4,n5
    on ['n1', 'n2'] do
      sudo do
        exec! 'iptables -A INPUT -s 10.10.3.199 -j DROP'   # n3
        exec! 'iptables -A INPUT -s 10.10.3.101 -j DROP'   # n4
        exec! 'iptables -A INPUT -s 10.10.3.95 -j DROP'    # n5
        exec! 'iptables -A OUTPUT -d 10.10.3.199 -j DROP'  # n3
        exec! 'iptables -A OUTPUT -d 10.10.3.101 -j DROP'  # n4
        exec! 'iptables -A OUTPUT -d 10.10.3.95 -j DROP'   # n5
      end
      puts "#{host.name}: Partitioned from majority component"
    end

    # Block traffic from n3,n4,n5 to n1,n2
    on ['n3', 'n4', 'n5'] do
      sudo do
        exec! 'iptables -A INPUT -s 10.10.3.242 -j DROP'   # n1
        exec! 'iptables -A INPUT -s 10.10.3.52 -j DROP'    # n2
        exec! 'iptables -A OUTPUT -d 10.10.3.242 -j DROP'  # n1
        exec! 'iptables -A OUTPUT -d 10.10.3.52 -j DROP'   # n2
      end
      puts "#{host.name}: Partitioned from minority component"
    end

    puts "Network partition created: {n1,n2} | {n3,n4,n5}"
  end

  # Heal the network partition
  task :heal do
    on hosts do
      sudo do
        exec! 'iptables -F'  # Flush all rules
        exec! 'iptables -X'  # Delete all user-defined chains
      end
      puts "#{host.name}: Network partition healed"
    end

    puts "Network partition healed"
  end

  # Show current iptables rules
  task :status do
    on hosts do
      puts "#{host.name}:"
      sudo { puts exec('iptables -L -n') }
      puts "---"
    end
  end

  # Create a more complex partition pattern
  task :isolate_master do
    # Find current master by asking sentinel
    master = nil
    on 'n3' do
      begin
        result = exec('/opt/redis/src/redis-cli -p 26379 SENTINEL get-master-addr-by-name mymaster')
        master = result.split("\n")[0] if result
      rescue
        # Fallback to n1 if sentinel not responding
        master = '10.10.3.242'
      end
    end

    if master
      puts "Isolating master at #{master}"

      # Block all traffic to/from the master
      on hosts do
        next if host.ip == master

        sudo do
          exec! "iptables -A INPUT -s #{master} -j DROP"
          exec! "iptables -A OUTPUT -d #{master} -j DROP"
        end
        puts "#{host.name}: Isolated from master #{master}"
      end
    else
      puts "Could not determine master node"
    end
  end

end