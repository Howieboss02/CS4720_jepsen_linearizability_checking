#!/bin/bash
# Setup script for Jepsen Redis test environment

set -e

echo "Setting up Jepsen Redis test environment..."

# Create bin directory if it doesn't exist
mkdir -p ~/bin

# Install Leiningen
echo "Installing Leiningen..."
curl https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein > ~/bin/lein
chmod +x ~/bin/lein

# Add ~/bin to PATH if not already there
if [[ ":$PATH:" != *":$HOME/bin:"* ]]; then
    echo 'export PATH="$HOME/bin:$PATH"' >> ~/.zshrc
    echo 'export PATH="$HOME/bin:$PATH"' >> ~/.bashrc
    echo "Added ~/bin to PATH. Please run 'source ~/.zshrc' or restart your terminal."
fi

# Check if we're on macOS and install dependencies
if [[ "$OSTYPE" == "darwin"* ]]; then
    echo "Detected macOS. Installing dependencies with Homebrew..."

    # Install Homebrew if not present
    if ! command -v brew &> /dev/null; then
        echo "Installing Homebrew..."
        /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
    fi

    # Install Ruby and other dependencies
    brew install ruby
    brew install redis  # For local testing

    # Install Salticid gem
    gem install salticid
else
    echo "Non-macOS system detected. Please install Ruby manually and run 'gem install salticid'"
fi

# Create Docker configuration directory
mkdir -p docker

echo "Setup complete! Next steps:"
echo "1. Run 'source ~/.zshrc' to update your PATH"
echo "2. Test with 'lein version'"
echo "3. For local testing, use 'docker-compose up' to start Redis cluster"
echo "4. For distributed testing, configure your cluster IPs in salticid/redis.rb"