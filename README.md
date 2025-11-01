# Ejabberd Management Portal

A declarative web-based management interface for ejabberd (XMPP) servers. Define your users, chat rooms, and rosters in configuration files, and the portal automatically synchronizes the state with your ejabberd server.

## Features

### Declarative Configuration
- Define users, groups, and chat rooms in simple EDN configuration files
- Automatic synchronization with ejabberd server via REST API
- Idempotent operations - safe to run sync multiple times
- Change detection prevents accidental overwrites

### User Management
- Create and manage XMPP user accounts
- Organize users into groups (owners, officers, members, etc.)
- Automatic roster management with fully-connected user mesh
- Self-service signup flow with JWT-based authorization

### Room Management
- Multi-User Chat (MUC) room creation and configuration
- Group-based access control (members and admins)
- Moderated rooms (only admins can speak)
- Automatic affiliation management based on group membership

### Administrative Features
- Built-in admin bot for XMPP operations
- JWT-based authentication for web interface
- Jitsi Meet integration with JWT token generation
- Real-time sync reports and change tracking

## Technology Stack

- **Backend**: Clojure 1.12.3, Ring/Jetty
- **XMPP Client**: SMACK library
- **Frontend**: HTMX, HyperScript, Tailwind CSS
- **Authentication**: JWT tokens
- **Configuration**: EDN files with custom reader macros
- **Build**: tools.build, npm/rollup for assets

## Quick Start

### Prerequisites

- Clojure CLI tools
- Node.js and npm
- Java 11+
- Access to an ejabberd server with REST API enabled

### Installation

```bash
# Clone the repository
git clone <repository-url>
cd ejabberd-management-portal

# Install dependencies
npm install

# Copy configuration templates
cp resources/config/secrets.edn.example resources/config/secrets.edn

# Edit secrets.edn with your JWT secret and ejabberd credentials
```

### Development

```bash
# Build frontend resources
bin/watch-resources 

#  and start the REPL. See https://github.com/lambdaisland/launchpad for launchpad options
bin/launchpad

# Or use mprocs for auto-reload during development
mprocs

# Access the application
# - Main app: http://localhost:3000
# - Dev login: http://localhost:3000/dev/login
```

**REPL Commands:**
```clojure
(go)       ; Start the system
(restart)  ; Reload code and restart
(stop)     ; Stop the system
```

### Production Build

```bash
# Build production uberjar (includes resources + compilation)
bin/build-resources

# Or build manually
clojure -T:build clean   # Clean old builds
clojure -T:build uber    # Create uberjar
```

### Running in Production

```bash
# Run the uberjar
java -jar target/ejabberd-management-portal-1.0.{version}-standalone.jar

# The application expects production config files at:
# - /etc/ejabberd-management-portal.edn
# - /etc/ejabberd-management-portal-secrets.edn
```

## Configuration

### User Database (`resources/config/default-user-db.edn`)

Define your users, groups, and rooms declaratively:

```clojure
{:groups {:group/owner "Owner"
          :group/officer "Officer"
          :group/member "Member"}

 :rooms [{:name "Officers"
          :members #{:group/officer}
          :admins #{:group/owner}
          :only-admins-can-speak? false}]

 :members [{:name "Alice Smith"
            :user-id "alice"
            :groups #{:group/owner :group/member}}
           {:name "Bob Jones"
            :user-id "bob"
            :groups #{:group/member}}]

 :initialize-admin? true}
```

### System Configuration (`resources/config/config.edn`)

Configure server URLs, ports, and ejabberd connection:

```clojure
{:host "your-domain.org"
 :env :dev
 :ejabberd-api-url "https://your-ejabberd-server:5443/api"
 :system {:server {:jetty {:port 3000}}}}
```

### Secrets (`resources/config/secrets.edn`)

```clojure
{:jwt-secret "your-secret-key-here"
 :ejabberd-admin-user "admin"
 :ejabberd-admin-password "password"}
```

## Architecture

- **State Sync Engine**: Reconciles declarative config with live ejabberd state
- **File-Based DB**: EDN files with atomic writes and SHA256 change detection
- **Component System**: Integrant manages lifecycle of database, API client, and server
- **Admin Bot**: Persistent XMPP connection for room management operations
- **JWT Auth**: Secure token-based authentication for web interface

## Common Tasks

### Sync User Database
The sync happens automatically when the application starts and when changes are detected.

### Add a New User
1. Edit `resources/config/default-user-db.edn`
2. Add user to `:members` vector
3. Restart application or trigger sync
4. User account is automatically created in ejabberd

### Create a New Room
1. Add room definition to `:rooms` vector
2. Specify member/admin groups
3. Sync automatically creates room with proper affiliations

## Development

For detailed development instructions, see [CLAUDE.md](CLAUDE.md).

### Project Structure

```
src/
├── main/dev/freeformsoftware/
│   ├── server/          # Web server, routes, auth middleware
│   ├── ejabberd/        # API client, sync engine, admin bot
│   ├── db/              # File I/O, schema validation
│   ├── ui/              # Page components, HTML generation
│   └── auth/            # JWT creation and validation
├── dev/user.clj         # REPL entry point
├── build/build.clj      # tools.build configuration
└── test/                # Test files
```

## Build Commands

```bash
clojure -T:build uber     # Build uberjar
clojure -T:build clean    # Clean build artifacts
clojure -T:build prep     # Check prod-resources
clojure -T:build jar      # Build thin jar
clojure -T:build install  # Install to local maven
```

## License

[Your license here]

## Contributing

[Your contribution guidelines here]

## Support

For issues and questions:
- File an issue on GitHub
- See [CLAUDE.md](CLAUDE.md) for detailed documentation
