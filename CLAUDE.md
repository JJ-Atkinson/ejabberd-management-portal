# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

---

# ⚠️ CRITICAL: Code Editing Guidelines for Claude

**ALWAYS use MCP (Model Context Protocol) tools when working with Clojure code:**

### Reading Clojure Files
- **Tool:** `mcp__clojure-mcp__read_file`
- **Benefits:** Collapsed view, pattern matching, syntax-aware navigation
- **Why:** Prevents misreading indentation and parentheses

### Editing Clojure Code
- **Tool:** `mcp__clojure-mcp__clojure_edit`
- **Benefits:** Structural rewrite-clj editing, automatic linting, balanced parentheses
- **Why:** Prevents syntax errors from string-based editing
- **Never use:** Generic `Edit` tool for .clj files

### Replacing S-Expressions
- **Tool:** `mcp__clojure-mcp__clojure_edit_replace_sexp`
- **Benefits:** Syntax-aware matching, ignores whitespace differences
- **Use case:** Targeted replacements within functions

### Writing New Clojure Files
- **Tool:** `mcp__clojure-mcp__file_write`
- **Benefits:** Automatic linting and formatting
- **Why:** Ensures valid syntax before writing

**String-based editing of Clojure code causes:**
- Unbalanced parentheses
- Incorrect indentation
- Malformed s-expressions
- Namespace/import errors

### REPL Reload Pattern
After making changes, reload affected namespaces using the appropriate restart function:

```clojure
(require '[clj-reload.core :as reload])

;; Reload specific namespace
(reload/reload 'namespace.to.reload)

;; Restart entire system - SOFT (MOST COMMON)
(restart)  ;; Defined in src/dev/user.clj
           ;; Uses suspend/resume - fast, preserves state

;; Hard restart - FULL RELOAD (USE WHEN NEEDED)
(restart-hard!)  ;; Defined in src/dev/user.clj
                 ;; Uses halt-key! + init-key! - slow but complete
                 ;; Required when:
                 ;; - Adding new Integrant components
                 ;; - Changing component initialization logic
                 ;; - OMEMO or other global service initialization
                 ;; - Component lifecycle changes
```

**The `restart` function (soft):**
- Uses `clj-reload.core/restart` under the hood
- Calls `ig/suspend-key!` then `ig/resume-key`
- Reloads all changed namespaces
- Fast and preserves most state
- **Use this for most code changes**

**The `restart-hard!` function (full reload):**
- Calls `ig/halt-key!` then `ig/init-key`
- Complete teardown and reinitialization
- Required for component lifecycle changes
- Slower but ensures clean state
- **Use when soft restart doesn't pick up changes**

---

# Ejabberd Management Portal - Project Guide

## Overview

This is a Clojure web application that provides a management interface for ejabberd (XMPP server). It allows administrators to declaratively manage XMPP users, chat rooms (MUC), and rosters through a web UI and synchronizes this configuration with the ejabberd server via its REST API.

**Key Technologies:**
- **Clojure** 1.12.3 - Primary language
- **Ring/Jetty** - Web framework and HTTP server
- **Integrant** - Component/lifecycle management
- **Malli** - Schema validation
- **SMACK** - XMPP client library for admin bot
- **Hiccup 2** - HTML generation (server-side)
- **HTMX** - Client-side interactivity
- **Tailwind CSS** - Styling
- **JWT** - Authentication tokens (internal + Jitsi integration)

---

## Running the Application

### Development Setup

#### Prerequisites
- **Clojure/JDK:** Use the Nix flake (`nix flake show`, `direnv allow`)
- **Node.js:** For CSS/JS build tools
- **Babashka:** For script execution

#### First-Time Setup
```bash
# Install dependencies
npm install
clojure -M:build

# Build CSS and JS resources
bin/build-resources

# Copy configuration (will be prompted for secrets)
cp resources/config/secrets.edn.example resources/config/secrets.edn
```

### Development Workflow

#### Starting the REPL with Auto-Reload
```bash
# Using mprocs (recommended - runs all watches together)
mprocs

# This runs:
# - bin/launchpad: Starts REPL with nREPL on port 7888 (for MCP tools)
# - bin/watch-resources: Watches CSS/JS and rebuilds on changes
```

#### Manual REPL Commands
```clojure
;; Start the system
(go)  ;; or (start)

;; Reload code and restart
(restart)

;; Stop the system
(stop)

;; Suspend (keep state)
(suspend)

;; Resume from suspension
(resume)

;; Open debugging portal
(portal)

;; Refresh browser after changes
(refresh-pages-after-eval!)
```

#### Development URLs
- **Main App:** http://localhost:3000
- **Quick Login:** http://localhost:3000/dev/login (creates admin JWT)
- **Test Signup:** http://localhost:3000/dev/test-signup (creates signup JWT)
- **Logout:** http://localhost:3000/dev/logout
- **Debug Claims:** http://localhost:3000/debug-claims (shows current JWT claims)

#### Environment
- Dev server runs on **port 3000** (configured in config.edn)
- Dev-mode enables special debug routes, auto-reload CSS/JS, and defaults to test passwords
- Production mode validates against production configuration files

### Building for Production

The build system uses `tools.build` and is located in `src/build/build.clj`.

```bash
# Full build (builds resources + compiles uberjar)
bin/build-resources

# Or build manually:
clojure -T:build uber    # Build uberjar (requires resources built first)
clojure -T:build clean   # Clean build artifacts
clojure -T:build prep    # Check if prod-resources exist
clojure -T:build jar     # Build thin jar
clojure -T:build install # Install to local maven repo
```

**Running the uberjar:**
```bash
# Run with embedded config (from prod-resources)
java -jar target/ejabberd-management-portal-1.0.{version}-standalone.jar

# Production deployment (expects external config files)
# The application will look for:
# - /etc/ejabberd-management-portal.edn
# - /etc/ejabberd-management-portal-secrets.edn
java -jar target/ejabberd-management-portal-1.0.{version}-standalone.jar
```

---

## Project Structure

```
.
├── deps.edn                    # Clojure dependencies (tools.build, SMACK, Ring, etc)
├── package.json                # Node deps for CSS/JS builds
├── flake.nix                   # Nix dev environment
├── mprocs.yaml                 # Process manager config (launchpad + watch-resources)
├── 
├── src/
│   ├── main/dev/freeformsoftware/
│   │   ├── config.clj          # Config loading with custom #n/ref reader
│   │   ├── auth/
│   │   │   └── jwt.clj         # JWT creation/validation (internal + Jitsi)
│   │   ├── server/
│   │   │   ├── core.clj        # Ring server setup (Jetty), Integrant init
│   │   │   ├── routes.clj      # Route definitions (admin, signup, dev-only)
│   │   │   ├── auth_middleware.clj  # JWT validation & role checking
│   │   │   ├── websocket.clj   # Dev mode hot-reload WebSocket
│   │   │   └── route_utils.clj # Route composition utilities
│   │   ├── db/
│   │   │   ├── schema.clj      # Malli schemas for user-db.edn validation
│   │   │   ├── file_interaction.clj  # File I/O with SHA256 change detection
│   │   │   └── util.clj        # DB utilities (password generation, etc)
│   │   ├── ejabberd/
│   │   │   ├── ejabberd_api.clj      # REST API wrapper for ejabberd
│   │   │   ├── admin_bot.clj         # XMPP bot using SMACK
│   │   │   └── sync_state.clj        # State reconciliation engine
│   │   └── ui/
│   │       ├── pages.clj             # Route -> page mappings
│   │       ├── html_fragments.clj    # Reusable UI components
│   │       ├── pages/
│   │       │   ├── user_management.clj
│   │       │   ├── room_management.clj
│   │       │   └── signup.clj
│   │
│   ├── dev/user.clj            # REPL entry point (start/stop/restart/go)
│   └── test/
│       └── *_test.clj          # Test files
│
├── resources/
│   ├── config/
│   │   ├── config.edn          # System configuration (env, urls, ports)
│   │   ├── secrets.edn         # Runtime secrets (jwt-secret)
│   │   ├── default-user-db.edn # Default user/room/group definitions
│   │   └── default-user-db-*.edn # Development copies
│   ├── css/
│   │   └── input.css           # Tailwind imports
│   ├── public/
│   │   ├── css/output.css      # Compiled Tailwind (build artifact)
│   │   └── js/bundle.js        # Compiled HTMX + HyperScript (build artifact)
│
├── bin/
│   ├── launchpad               # Start Babashka REPL with lambdaisland/launchpad
│   ├── build-resources         # Run npm builds for CSS/JS
│   ├── watch-resources         # Watch & rebuild CSS/JS on change
│   ├── build-css               # Tailwind build
│   └── clean-resources         # Clean build artifacts
│
├── tailwind.config.js          # Tailwind configuration
├── rollup.config.js            # JS bundler configuration
└── .zprintrc                   # Code formatting config (zprint)
```

---

## Architecture

### Component System (Integrant)

The application uses **Integrant** for lifecycle management. Components are defined in `config.edn` and initialized in a specific order:

```
:dev.freeformsoftware.db.user-db/user-db
    ↓
:dev.freeformsoftware.ejabberd.ejabberd-api/ejabberd-api ← depends on user-db
    ↓
:dev.freeformsoftware.ejabberd.sync-state/sync-state ← depends on ejabberd-api & user-db
    ↓
:dev.freeformsoftware.ejabberd.admin-bot/admin-bot ← depends on user-db & ejabberd-api
    ↓
:dev.freeformsoftware.server.core/server ← depends on user-db
```

**Key Integrant Methods:**
- `ig/init-key` - Initialize component (called during `(go)`)
- `ig/halt-key!` - Clean up (called during `(stop)`)
- `ig/suspend-key!` - Pause without cleanup (called during `(suspend)`)
- `ig/resume-key` - Resume after suspension (called during `(resume)`)

**Reading & using components in REPL:**
```clojure
;; Access initialized system
(def db-component (:dev.freeformsoftware.db.user-db/user-db @!system))

;; Use it
(def user-db (file-db/read-user-db db-component))
```

### Configuration Management

**Config Loading Pipeline:**
1. Read EDN files: `config.edn` → `secrets.edn` → prod-specific files
2. Parse with custom `:dev.freeformsoftware.config/reader-nref` reader
3. Deep-merge with special handling for `#n/ref` (scalar references)
4. Resolve all `#n/ref` references to actual values
5. Extract `:system` key for Integrant

**Custom Readers:**
- `#n/ref :key` - Scalar reference to top-level config key (use for strings, numbers)
- `#ig/ref :component-key` - Reference to another component (use for dependencies)

**Example (from config.edn):**
```clojure
{:host "yourserverhere.org"
 :jwt-secret #n/ref :jwt-secret  ; Points to secrets.edn
 :system
 {:db {:db-folder #n/ref :db-folder}
  :api {:host #n/ref :host
        :user-db #ig/ref :db}}}  ; Component dependency
```

### State Synchronization Engine

**File:** `src/main/dev/freeformsoftware/ejabberd/sync_state.clj`

The core business logic that reconciles declarative configuration with live ejabberd state.

**User Database Structure (user-db.edn):**
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
            :groups #{:group/owner :group/member}}]
 :do-not-edit-state {:managed-members #{"alice"}
                     :managed-rooms #{"officers"}
                     :managed-groups #{:group/owner :group/officer}
                     :admin-credentials {:username "admin-bot" :password "..."}}
 :initialize-admin? true}
```

**Sync Phases:**
1. **Ghost-Include Admin Bot** - Add bot to members for sync
2. **Compute Diffs** - Compare current state vs. tracked state
3. **Delete** - Remove deleted users/rooms
4. **Register Users** - Create new XMPP accounts
5. **Create Rooms** - Create MUC rooms with options
6. **Sync Rosters** - Create fully-connected mesh of users
7. **Sync Affiliations** - Set user room roles based on group membership
8. **Update Tracking** - Store managed entity set in `do-not-edit-state`
9. **Ghost-Remove Admin Bot** - Remove from returned state

**Key Design Decisions:**
- **Declarative:** Config describes desired state, not actions
- **Idempotent:** Safe to run sync multiple times
- **Immutable Deletes:** Deletion requires config change (no accidental deletions)
- **Ghost Bot:** Admin bot synced into all rooms/rosters but hidden from UI
- **Room ID Generation:** Random 10-char lowercase ID, stored in config

### Database (File-Based)

**File:** `src/main/dev/freeformsoftware/db/file_interaction.clj`

**Features:**
- **Schema Validation:** Malli schemas prevent invalid configs
- **Change Detection:** SHA256 of file stored with read data
- **Atomic Writes:** Use temp file + atomic rename
- **Concurrency:** Detects concurrent modifications via SHA mismatch
- **Initialization:** Copies `default-user-db.edn` on first read

**Usage:**
```clojure
;; Read with validation and SHA
(def db (file-db/read-user-db db-component))
;; => {:groups {...} :rooms [...] :members [...] :_file-sha256 "abc123..."}

;; Modify
(def updated-db (assoc-in db [:members 0 :name] "New Name"))

;; Write with change detection
(file-db/write-user-db db-component updated-db)
;; Throws ex-info if file was modified since read
```

### Authentication & Authorization

**File:** `src/main/dev/freeformsoftware/server/auth_middleware.clj`

**JWT Flow:**
1. User receives JWT (either from query param or cookie)
2. Middleware validates JWT using secret from config
3. JWT claims added to request as `:jwt-claims`
4. Role-based middleware checks for `:admin` or `:signup` role
5. Unauthorized access returns 401/403 page

**JWT Structure (Internal):**
```clojure
{:iss "ejabberd-management-portal"
 :aud "ejabberd-management-portal"
 :iat 1728000000      ; Issued at (Unix seconds)
 :exp 1728086400      ; Expiration (Unix seconds)
 :role :admin         ; Custom claim
 :user "admin-user"}  ; Custom claim
```

**JWT Structure (Jitsi Integration):**
```clojure
{:iss "dev.freeformsoftware"
 :aud "jitsi"
 :sub "meet.jitsi"
 :room "meeting-name"
 :exp 1728000000
 :iat 1728000000
 :context {:user {:name "User Name"
                   :email "user@example.com"
                   :avatar "https://example.com/avatar.jpg"  ; optional
                   :moderator true}}}                        ; optional
```

**Development Helpers:**
- `/dev/login` - Creates admin JWT for testing
- `/dev/test-signup` - Creates signup JWT for testing
- `/debug-claims` - Shows current JWT claims

### Ejabberd API Integration

**File:** `src/main/dev/freeformsoftware/ejabberd/ejabberd_api.clj`

Wraps the ejabberd REST API for:
- **User Management:** register, change_password, unregister, registered_users
- **MUC Rooms:** create_room, create_room_with_opts, destroy_room, muc_online_rooms, get_room_options
- **Affiliations:** set_room_affiliation, get_room_affiliations
- **Rosters:** get_roster, add_rosteritem, delete_rosteritem

**Key Concepts:**
- **JID (Jabber ID):** `user@host` or `room@service`
- **Affiliation:** User's permanent role in room (owner/admin/member/outcast/none)
- **Subscription:** Presence sharing in roster (both/to/from/none)
- **Roster Groups:** Folders for organizing contacts

### XMPP Admin Bot

**File:** `src/main/dev/freeformsoftware/ejabberd/admin_bot.clj`

**Features:**
- Maintains persistent XMPP connection using SMACK library
- Automatically created if `:initialize-admin?` is true in user-db.edn
- Credentials stored in `do-not-edit-state` after first initialization
- Added to all rooms and rosters as part of sync process
- Uses dynamic call to avoid circular dependencies with ejabberd-api

**Initialization:**
1. Check if credentials exist in `do-not-edit-state`
2. If not, and `:initialize-admin?` is true, create new user
3. Register user with ejabberd API
4. Save credentials to `do-not-edit-state`
5. Connect via XMPP TCP connection

### Web Server & Routing

**File:** `src/main/dev/freeformsoftware/server/core.clj`

- Uses **Ring** with **Jetty** adapter
- Single-page app architecture (all routes render through handler)
- Routes created fresh per request using `clj-simple-router`
- Development mode includes static cache headers
- Security headers: CSRF disabled (using JWT instead), frame-options: deny

**Route Structure (from routes.clj):**
```clojure
prod-routes      ; Admin + signup routes wrapped in auth middleware
└─ admin-routes  ; Pages accessible to admins
   └─ signup-routes ; Pages accessible to signup users
   └─ etc-routes  ; Public routes (favicon, etc)
dev-routes       ; Development-only (login, logout, WebSocket reload)
```

### UI Layer

**Technology Stack:**
- **Hiccup 2:** Server-side HTML generation as Clojure data structures
- **HTMX:** Progressive enhancement for forms/interactions
- **HyperScript:** Embedded scripting for client-side behavior
- **Tailwind CSS:** Utility-first CSS framework

**Key Patterns:**
- All HTML generated on server (no client JS framework)
- HTMX handles form submission and partial page updates
- Responsive design: mobile-first with Tailwind breakpoints
- Accessibility: semantic HTML, ARIA where needed

**Main Pages:**
- User Management (`/pages/users`) - CRUD for members
- Room Management (`/pages/rooms`) - CRUD for rooms (WIP)
- Signup Flow (`/signup`) - Self-service user setup

---

## Development Patterns

### REPL-Driven Development

**Key Files & Macros:**
- `src/dev/user.clj` - Entry point with lifecycle functions
- `clj-reload` - Auto-reload support (watch src/dev, src/main, src/test)
- `portal` - Tap debugging (open with `(portal)`)

**Common Workflows:**

#### Testing Sync Engine
```clojure
;; Get all components
(def db-comp (:dev.freeformsoftware.db.user-db/user-db @!system))
(def api-comp (:dev.freeformsoftware.ejabberd.ejabberd-api/ejabberd-api @!system))
(def sync-comp (:dev.freeformsoftware.ejabberd.sync-state/sync-state @!system))

;; Read current DB
(def db (file-db/read-user-db db-comp))

;; Make changes
(def updated-db (assoc-in db [:members 0 :name] "New Name"))

;; Sync with ejabberd
(def result (sync-state/sync-state! sync-comp updated-db))

;; Save changes
(file-db/write-user-db db-comp (:state result))

;; Inspect changes
(tap> (:report result))
```

#### Testing API Calls
```clojure
(def api-comp (:dev.freeformsoftware.ejabberd.ejabberd-api/ejabberd-api @!system))

;; List users
(api/registered-users api-comp)

;; Register user
(api/register api-comp "testuser" "password123")

;; List rooms
(api/muc-online-rooms api-comp)

;; Create room
(api/create-room api-comp "testroom")
```

#### Testing DB Validation
```clojure
(def db (edn/read-string (slurp "resources/config/default-user-db.edn")))

;; Validate entire database
(schema/validate-user-db db)
;; => {:valid? true}

;; Validate individual sections
(schema/validate-groups (:groups db))
(schema/validate-rooms (:rooms db) (keys (:groups db)))
(schema/validate-members (:members db) (keys (:groups db)))
```

### MCP (Model Context Protocol) Integration

**Entry Point:** `src/dev/user.clj` line 8

```clojure
[clojure-mcp.main]  ; Imported
```

**MCP Server:**
- Starts automatically on port **7888** when system starts
- Provides Clojure tools for Claude/other AI models
- Allows remote code execution, file reading, etc.

**Usage with Claude CLI:**
```bash
claude_code --tool clojure-mcp:localhost:7888
```

**In REPL:**
```clojure
(clojure-mcp.main/start-mcp-server {:port 7888})
```

### Testing

**Test Files:**
- `src/test/dev/freeformsoftware/db/schema_test.clj` - Schema validation tests
- `src/test/dev/freeformsoftware/db/file_interaction_test.clj` - File I/O tests

**Running Tests:**
```bash
clojure -M:test  ; Uses fulcro-spec
```

---

## Key Data Structures

### User Database (user-db.edn)

```clojure
{:groups              ; Map of group keyword → display name
 :rooms               ; Vector of room definitions
 :members             ; Vector of user definitions
 :initialize-admin?   ; Boolean - create admin bot on init
 :do-not-edit-state   ; Managed by system (don't edit directly)
 }
```

**Group Schema:**
```clojure
{:group/owner "Owner"
 :group/officer "Officer"}
```

**Room Schema:**
```clojure
{:name "Room Name"
 :room-id "auto-generated-id"  ; Optional, assigned by sync
 :members #{:group/officer}    ; Groups allowed to join
 :admins #{:group/owner}       ; Groups with admin privileges
 :only-admins-can-speak? false  ; If true, room is moderated
}
```

**Member Schema:**
```clojure
{:name "Alice Smith"            ; Display name
 :user-id "alice"               ; XMPP account (no @host)
 :groups #{:group/owner :group/member}}  ; Group memberships
```

### HTTP Request/Response

**Request (Ring):**
```clojure
{:request-method :get
 :uri "/pages/users"
 :params {:jwt "eyJ0eXAi..."}
 :cookies {"jwt" {:value "eyJ0eXAi..."}}
 :jwt-claims {:role :admin :user "admin-user"}}
```

**Response (Ring):**
```clojure
{:status 200
 :headers {"Content-Type" "text/html"
           "Set-Cookie" "jwt=...; HttpOnly; Path=/; Max-Age=86400"}
 :body "<html>...</html>"}
```

---

## Configuration Files

### config.edn
Server URLs, ports, host configuration. Merged with secrets.edn.

### secrets.edn
Sensitive values: JWT secret (required for auth to work).

### default-user-db.edn
Template user database. Copied to `$db-folder/userdb.edn` on first run.

### Environment Variables & Overrides
- **Development:** Uses `config.edn` + `secrets.edn`
- **Production:** Uses `/etc/ejabberd-management-portal.edn` + `/etc/ejabberd-management-portal-secrets.edn`

---

## Common Tasks

### Add a New Page
1. Create page component: `src/main/dev/freeformsoftware/ui/pages/new_page.clj`
2. Add page definition to `ui/pages.clj`:
   ```clojure
   {:id :new-page
    :route "/pages/new"
    :title "New Page"
    :body-fn #'new-page/body
    :action-fn nil}
   ```
3. Add routes: `(new-page/routes conf)` in `routes.clj`

### Modify Schema
1. Edit `src/main/dev/freeformsoftware/db/schema.clj`
2. Add validation function
3. Add tests
4. Update `default-user-db.edn` to match

### Add API Feature
1. Add function to `ejabberd_api.clj` wrapping ejabberd API
2. Use in `sync_state.clj` or UI handlers
3. Add REPL test examples in comment block

### Deploy to Production
1. Build resources: `bin/build-resources`
2. Create uberjar: `clojure -T:build <command>`
3. Copy to server, configure `/etc/ejabberd-management-portal.edn`
4. Start with `java -jar ejabberd-management-portal.jar`

---

## Troubleshooting

### "Concurrent modification detected"
File was edited outside the application. Manually restore backup and retry.

### JWT validation fails
- Check `:jwt-secret` in `secrets.edn`
- Ensure secret matches between creation and validation
- Check token hasn't expired

### XMPP connection fails
- Verify ejabberd REST API URL in config
- Check admin bot credentials in `do-not-edit-state`
- Verify network connectivity to XMPP server

### Schema validation errors
Run validator in REPL to get detailed errors:
```clojure
(schema/validate-user-db (edn/read-string (slurp "...edn")))
```

### Sync not completing
Check logs with: `(tap> :some-data)` in REPL or `tail -f logs/*`

---

## References

- **Clojure:** https://clojure.org
- **Integrant:** https://github.com/weavejester/integrant
- **Malli:** https://github.com/metosin/malli
- **Ring:** https://github.com/ring-clojure/ring
- **SMACK:** https://igniterealtime.org/projects/smack/
- **Ejabberd API:** https://docs.ejabberd.im/developer/ejabberd-api/admin-api/
- **XMPP RFC:** https://xmpp.org/rfcs/
- **HTMX:** https://htmx.org
- **Tailwind:** https://tailwindcss.com

