# Forma — Exercise planner

React frontend, FastAPI backend, SQLite database, tenant-scoped local media, and Redis with a persistent file-cache fallback.

Phase 2 adds authenticated MCP access, daily progress photos, date-level tracking, and adherence feedback.

Phase 3 adds an Android data collector with optional login, permission onboarding, encrypted offline collection, hourly background sync when connected, and a link to open the website in your browser. See the [Android setup and APK build guide](data_sync/README.md).

## Start

From the repository root:

```sh
python -m venv .venv
.venv/bin/pip install -r backend/requirements.txt
npm install
```

The root `.env` contains the backend key. For another machine, copy `.env.example` to `.env` and set `OPEN_API_KEY`. Secrets are ignored by Git and never bundled into the frontend.

Run in two terminals:

```sh
.venv/bin/python -m backend.server
```

```sh
npm run dev
```

App: http://localhost:5173 · API docs: http://localhost:8000/docs · API health: http://localhost:8000/api/health

Admin credentials are `admin@example.com` / `admin123`, as requested. `ADMIN_EMAIL` and `ADMIN_PASSWORD` seed the administrator on the first database initialization. Subsequent password changes go through Settings. `COOKIE_SECURE=true` is available for HTTPS deployments.

## Phase 1 features

- Create an account with name/email and an optional password. If omitted, a generated password is shown once for future sign-ins. Login, logout, password changes, and sessions survive server restarts.
- Onboarding captures multiple focus areas, multiple body areas plus custom focus, mandatory food planning, dietary preferences, allergies, height, weight, age, fitness level, goals, limitations, and optional skin/hair types. Fitness-specific and care-specific fields follow the selected focus.
- Optional equipment/body images are uploaded to a private tenant folder. Equipment text and images guide the workout role; gym access is assumed when neither is supplied. Image metadata is removed and files are re-encoded to JPEG.
- OpenAI Responses API creates separate workout, meal, and optional care plans. A provider interface allows adding other LLMs. An independent review role checks the combined result; major issues return to the responsible role, with at most three revisions per role. Schema and schedule validation also feed revision requests. Unapproved plans are never published.
- A reviewed seven-day schedule expands to 28 dated days with four weekly progression instructions. Meals have portions, ingredients, steps, estimated calorie targets, and task calories. Workouts have routine steps and estimated duration/burn. Body-area focus does not imply spot fat loss.
- Selected care routines begin on day 15, or on day 1 when early care is selected. Users can start care early and regenerate.
- Planning runs in the background with persisted job status, visible progress, retryable errors, and dashboard notifications. If the process restarts mid-job, the job becomes retryable. Previously published plans remain available when regeneration fails. Successful regeneration archives the previous plan and tracking totals, shown under Previous plans in Progress.
- **Refine current plan** asks for a preference note and a duration of 1–28 days within the upcoming plan. Review-approved changes replace pending activities on those dates; completed and skipped activities remain intact. **Extend Plan** accepts the same inputs and appends 1–28 days after the current end date (or starts today if the plan has expired). Existing days and tracking stay intact. The 28-day limit applies to each request; the calendar supports all accumulated weeks.
- Both actions persist the preference note for future generation, run through the planning and review roles, archive the previous version, and publish the result in the database and dashboard with a notification. Saved preferences are visible in Settings. Failed updates retain the current plan and can be retried without duplicating the preference note. Care timing follows the original journey rather than restarting its two-week delay on extension.
- The daily plan uses cards grouped into workouts, meals, and care. Daily totals show planned workout time, completed workout minutes, meal calories, and care time. Workout cards expose each exercise's sets/reps, timed holds, and any specified exercise duration; meal cards show portions and calories; care cards show practical steps. Completion/skipping, full routine/recipe details, and calendar navigation remain available.
- A **Cards / Minimal** toggle next to the activity tabs switches the daily plan view, and the browser remembers the choice. Minimal view shows two cards. The workout card lists time (and estimated burn) per body area, such as "Legs · 35 min" or "Arms · 1 hr". The focus comes from the session title, or else from the exercises' body areas in the guide library. The meal card lists only each meal's ingredients. Care routines appear only in the card view.
- New OpenAI workout plans provide structured exercise names, sets, reps, holds, duration, rest intervals, and an approximate calorie burn per exercise. Validation checks quantities and session totals, and the exercises' burn cannot exceed the session estimate.
- New meal plans include daily protein, carbohydrate, fat and fiber targets. Each meal gives its grams of each, and every ingredient line states its quantity with approximate kcal and protein. Calories stay primary because the existing daily-calorie checks validate them. The server then makes each meal's macros agree with its calories: protein is the sum of the ingredient-level estimates (when most lines state one), and the remaining calories are split between carbohydrate and fat in the model's ratio. Days whose protein is more than 25% (or 15 g) off target get up to two specific revision rounds. After that the plan publishes anyway, and the cards show planned against target rather than failing the request. Cards and the details dialog show these values. Plans generated before this change have no nutrition fields until they are regenerated, refined or extended.
- The dashboard and calendar also show day-specific calorie and movement totals, water logs, check-ins, weight trends, completion graphs, adherence summaries, and habit feedback. Saved user feedback is passed into subsequent planning.
- Admins can create/list/delete accounts, set passwords, and enter a user’s workspace. Administrators manage the platform; personal plans and MCP connections belong to member accounts. Impersonation has an explicit return-to-admin action. Deletion removes the tenant's database records and local files.
- The sidebar toggle switches between the full menu and an icon rail, remembering the desktop choice in the browser. On mobile, the full menu opens over the page and closes after navigation, an outside click, or Escape. Menu icons retain accessible labels and tooltips.
- Database reads/writes and media endpoints derive ownership from the authenticated session. Different tenants cannot access one another’s data. Passwords use salted PBKDF2 hashes, and sessions use opaque HttpOnly cookies with hashed tokens stored in SQLite.

## Phase 2: MCP and photo progress

Members can view tracking for any date, upload several progress photos for today or a selected date, and mark specific meals/workouts completed or skipped. Task and photo updates return current tracking and adherence feedback. **Analyze photos** explicitly requests saved visual feedback from the configured AI provider, using the selected photos, earlier photos when available, and plan adherence. Analysis can take a moment and uses the provider API; ordinary task updates do not call the AI provider. Feedback describes visible observations with uncertainty rather than inferring body measurements or medical diagnoses.

The backend serves the official Python MCP SDK over Streamable HTTP at `http://127.0.0.1:8000/mcp`. All tools act on the authenticated member's own data. Browser login cookies and administrator accounts cannot authenticate MCP requests.

| Tool | Inputs and result |
| --- | --- |
| `regenerate_plan` | Integer `days` from 1 through 28; starts today and returns a queued planning job. |
| `get_current_day_plan` | Today's plan, task IDs/statuses, photos, and feedback in the profile timezone. |
| `get_progress_summary` | Optional inclusive `start_date` / `end_date`; adherence totals and tracking summary. |
| `mark_task` | `task_id`, `status` (`completed` or `skipped`), optional `selected_date`; updated tracking. |
| `get_date_tracking` | `selected_date` in `YYYY-MM-DD`; detailed dated tracking. |
| `upload_progress_photo` | Plain `image_base64`, optional `selected_date`; saves an image up to 10 MiB and returns tracking. |
| `analyze_progress_photos` | Optional `selected_date`; requests and saves visual progress feedback. |
| `get_planning_job` | `job_id`; polls the member's asynchronous planning job. |

Dates use `YYYY-MM-DD`; omitted dates use the member profile timezone. Regeneration replaces the published plan only after review succeeds and archives the previous version. Poll the returned job until it completes, then fetch the current plan.

### Connect MCP to your agent

Choose the instructions for your client:

| Agent | Connection | Authentication | Setup |
| --- | --- | --- | --- |
| Claude Code / Claude CLI | Local or hosted Streamable HTTP | Personal access token or OAuth | [Claude Code](#claude-code--claude-cli) |
| Claude Desktop, local server | Included stdio proxy to the running backend | Personal access token | [Claude Desktop](#claude-desktop-local-connection) |
| ChatGPT | Hosted HTTPS MCP endpoint | OAuth with automatic client registration | [ChatGPT](#chatgpt-connection) |
| Claude.ai / Claude Desktop, remote connector | Hosted HTTPS MCP endpoint | OAuth with automatic client registration | [Remote Claude](#claudeai--claude-desktop-remote-connection) |
| Other agents with stdio support | Included stdio proxy | Personal access token | Use the [Desktop JSON configuration](#claude-desktop-local-connection) in your client's MCP settings |

#### Before connecting

1. Install the backend dependencies and start Forma from the repository root:

   ```sh
   .venv/bin/pip install -r backend/requirements.txt
   .venv/bin/python -m backend.server
   ```

2. Sign in to Forma as a **member** and complete onboarding. Administrators and administrator impersonation sessions cannot create MCP credentials.
3. For token-based connections, open **Settings → Connect your agent → Create connection token**. Copy the token immediately; it is shown once. Use a separate named token for each client. OAuth connections use Forma's sign-in and consent page instead.

The local server URL is `http://127.0.0.1:8000/mcp`. The client process must be able to reach that address. For an agent running in another container or machine, provide a reachable HTTPS backend URL. The frontend at port 5173 is not required for MCP tool calls.

#### Claude Code / Claude CLI

**Local HTTP with a personal access token**

Run these commands in your project directory using Bash. Paste the token created in Forma when prompted:

```sh
read -rsp 'Forma token: ' FORMA_MCP_TOKEN
export FORMA_MCP_TOKEN
claude mcp add --scope local --transport http forma http://127.0.0.1:8000/mcp \
  --header "Authorization: Bearer $FORMA_MCP_TOKEN"
claude mcp list
```

Start `claude`, enter `/mcp`, and confirm that `forma` is connected. Ask: "Use Forma to show my plan for today."

`--scope local` keeps the connection specific to this project in your local Claude configuration. Claude saves the configured header, so keep that configuration private. For a hosted backend, replace the loopback URL with its HTTPS `/mcp` URL. See the official [Claude Code MCP guide](https://code.claude.com/docs/en/mcp).

**OAuth alternative**

For a browser sign-in connection, add an HTTP server without a token header. This example uses a separate connection name:

```sh
claude mcp add --scope local --transport http forma-oauth https://your-backend.example/mcp
claude mcp login forma-oauth
```

Sign in as a Forma member and approve access. You can also start authentication from `/mcp` inside Claude Code. Local Claude Code can use `http://127.0.0.1:8000/mcp` with OAuth as well. Follow the [remote backend setup](#prepare-a-hosted-backend) when using HTTPS.

#### Claude Desktop: local connection

Claude Desktop can launch Forma's stdio proxy on your computer. Keep the FastAPI backend running separately.

1. In Claude Desktop, open **Settings → Developer → Edit Config**.
2. Merge the following `forma` entry into the existing `mcpServers` object. Replace both absolute paths and `YOUR_PERSONAL_ACCESS_TOKEN` with your own values:

   ```json
   {
     "mcpServers": {
       "forma": {
         "command": "/absolute/path/to/exercise_planner/.venv/bin/python",
         "args": ["-m", "backend.mcp_server", "--stdio"],
         "env": {
           "PYTHONPATH": "/absolute/path/to/exercise_planner",
           "FORMA_MCP_URL": "http://127.0.0.1:8000/mcp",
           "FORMA_MCP_TOKEN": "YOUR_PERSONAL_ACCESS_TOKEN"
         }
       }
     }
   }
   ```

3. Save the configuration, fully quit Claude Desktop, and reopen it.
4. Open the chat's **+ → Connectors → Manage connectors** menu and confirm Forma is available. Enable it and ask for today's plan.

The configuration file is `~/Library/Application Support/Claude/claude_desktop_config.json` on macOS and `%APPDATA%\Claude\claude_desktop_config.json` on Windows. On Windows, use the virtual environment's `.venv\Scripts\python.exe` and escape backslashes in JSON, for example `"C:\\Projects\\exercise_planner\\.venv\\Scripts\\python.exe"`.

`PYTHONPATH` lets Python find `backend.mcp_server` regardless of the client's working directory. Set the credentials in the client's `env` configuration; the proxy does not load them from Forma's `.env` file. Keep the configuration containing the token private. The proxy forwards calls to the running backend and creates no separate database. See the official [local MCP server connection guide](https://modelcontextprotocol.io/docs/develop/connect-local-servers).

#### Prepare a hosted backend

Use this setup for the public HTTPS connections below:

1. Serve the FastAPI backend through HTTPS at an origin such as `https://your-backend.example`.
2. Set these backend environment values, then restart the backend:

   ```dotenv
   FORMA_PUBLIC_URL=https://your-backend.example
   COOKIE_SECURE=true
   FRONTEND_URL=https://your-frontend.example
   ```

   `FORMA_PUBLIC_URL` is the backend origin, without `/mcp` or another path. Set `FRONTEND_URL` to the actual frontend origin; it may be the same as the backend origin.

3. Route `/mcp`, `/oauth/*`, and `/.well-known/*` through the reverse proxy to FastAPI, preserving the host, authorization header, request methods, and bodies.
4. Check discovery from outside your local network:

   ```sh
   curl --fail https://your-backend.example/.well-known/oauth-protected-resource/mcp
   curl --fail https://your-backend.example/.well-known/oauth-authorization-server
   ```

   The metadata must advertise `https://your-backend.example/mcp` as the resource and the same public backend origin for its OAuth endpoints.

The client URL is **`https://your-backend.example/mcp`**. Forma supports dynamic client registration (DCR) and OAuth authorization-code flow with S256 PKCE. Choose automatic registration when the client offers registration choices. Its OAuth scope is `forma:mcp`; client IDs and secrets are created during registration.

#### ChatGPT connection

1. Complete the [hosted backend setup](#prepare-a-hosted-backend).
2. In ChatGPT on the web, open **Settings → Security and login** and enable **Developer mode**. Availability depends on your account and workspace policy.
3. Open [ChatGPT Plugins](https://chatgpt.com/plugins) and select the **plus** button to add a developer-mode MCP connection.
4. Enter a name such as **Forma**, a description, and the public server URL `https://your-backend.example/mcp`.
5. Select **OAuth** authentication. If registration options are shown, choose **DCR / dynamic client registration**. Forma advertises its registration endpoint; you do not need to invent a client ID or paste a Forma personal token into an OAuth client-secret field.
6. Create the connection, sign in on Forma's consent page as a member, and approve access. Review the eight discovered tools.
7. Start a new conversation, add Forma from the tools menu, and ask: "Use Forma to summarize my progress and show today's plan."

Follow the official [ChatGPT connection instructions](https://developers.openai.com/plugins/deploy/connect-chatgpt) and [OAuth registration guidance](https://developers.openai.com/plugins/build/auth). For private-network access, OpenAI also documents [Secure MCP Tunnel](https://developers.openai.com/api/docs/guides/secure-mcp-tunnels); that requires a separately configured tunnel.

#### Claude.ai / Claude Desktop: remote connection

This connection reaches Forma from Claude's hosted service, including when you add it from Desktop. Use the public HTTPS URL from the [hosted backend setup](#prepare-a-hosted-backend).

1. Open **Customize → Connectors → + Add → Add custom connector**.
2. Set the name to **Forma** and the remote MCP URL to `https://your-backend.example/mcp`.
3. Under authentication, choose **Sign in now** and select **Register automatically** as the OAuth client identity. Forma supports DCR; the **Use Claude's published identity** option requires client metadata support that this server does not implement.
4. Finish adding the connector, sign in as a Forma member, and approve access.
5. Enable Forma in a conversation through **+ → Connectors**, then ask for today's plan.

Team and Enterprise workspaces may require an owner to add or enable the connector before members connect their own accounts. See the official [Claude remote connector guide](https://support.claude.com/en/articles/11175166-get-started-with-custom-connectors-using-remote-mcp).

#### Verify the connection and use the tools

After connecting, try these requests:

- "Show my workout and meal plan for today, including task IDs."
- "Mark task `<task_id>` completed for `<YYYY-MM-DD>` and show my updated progress."
- "Show detailed tracking for `<YYYY-MM-DD>`."
- "Regenerate my next 14 days, wait for the planning job to finish, and show the reviewed plan."
- "Analyze the progress photos I saved in Forma for today together with my adherence."

Regeneration returns a queued job; the agent should poll `get_planning_job` before fetching the new plan. Uploading a photo through MCP requires the agent to supply the image bytes as plain base64; a chat attachment is saved in Forma only when the agent calls `upload_progress_photo`. Uploads return tracking feedback, and `analyze_progress_photos` requests the separate visual review.

#### Troubleshooting and disconnecting

| Symptom | What to check |
| --- | --- |
| Connection refused | Keep the backend running. For the local proxy, verify `FORMA_MCP_URL` and that the client can reach port 8000. |
| ChatGPT or remote Claude cannot reach the server | Use the public HTTPS `/mcp` URL and check external access to the OAuth discovery routes. Loopback URLs refer to the remote service's own machine. |
| `401 Unauthorized` | For a token connection, provide `Authorization: Bearer <token>` and check expiry/revocation. For OAuth, reconnect and complete consent. |
| `403 Forbidden` | Sign in directly as a member. For an origin error, verify the backend's `FORMA_PUBLIC_URL` and `FRONTEND_URL` values. |
| `421 Misdirected Request` | Set `FORMA_PUBLIC_URL` to the hostname actually used by the client, preserve the host at the proxy, and restart the backend. |
| OAuth client or registration error | Select automatic registration / DCR. For remote Claude, use **Register automatically**. |
| Desktop proxy immediately exits or cannot find `backend` | Check the absolute Python path, `PYTHONPATH`, token in `env`, and separately running backend. |
| Tools are missing after an update | Refresh the connection's tool metadata or reconnect, then start a new conversation. |
| Credentials stop working after changing the public URL | Tokens are bound to the MCP resource URL. Create a new token or authorize the OAuth connection again. |

Revoke a token or authorized app in **Forma Settings → Connect your agent**. Remove the Claude Code configuration with `claude mcp remove forma` (or `forma-oauth`); for Desktop, remove only the Forma entry and restart. Removing client configuration alone does not revoke its credential in Forma.

Session-authenticated management endpoints are `GET/POST /api/mcp/tokens`, `DELETE /api/mcp/tokens/{id}`, `GET /api/mcp/connections`, and `DELETE /api/mcp/connections/{id}`. Token creation accepts `{"name":"My agent","expires_days":90}` with a 1–365 day expiry and shows the secret once. OAuth access tokens last one hour; refresh tokens rotate and expire after 30 days, and reuse revokes the connection.

The transport follows the official [Python SDK v1 documentation](https://py.sdk.modelcontextprotocol.io/v1/) and [MCP authorization specification](https://modelcontextprotocol.io/specification/2025-06-18/basic/authorization).

## Phase 3: Android data export and APK

The Android app and companion server are in [`data_sync/native_app`](data_sync/native_app). Choose **Skip login** to collect permitted usage and health records locally without a server. Hourly collection runs on the device through WorkManager, including while offline. Sign in with an existing Forma account and connect a server to enable periodic uploads. **Open website** opens your configured server in the phone's browser. Uploads use a session token; the password is used only at login. Signing out stops uploads while local collection continues. Collection and upload schedules can be delayed by Android power management.

The encrypted local queue is limited to 64 MiB. If it fills, collection reports an error and waits for space rather than deleting unuploaded records. After connecting, acknowledged batches leave the queue. Data already assigned to one account stays separate from other accounts.

Open **Sync history** to review collection jobs, their sources and actual records, and each batch's upload status. Collection details work offline. Confirmed uploads are retained in a separate encrypted local archive for up to 30 days / 64 MiB so they can still be inspected after syncing.

To generate an installable standalone preview APK after configuring Node, JDK 17 and the Android SDK:

```sh
# From the repository root
cd data_sync/native_app
npm ci
npm run build:apk
```

The generated file is `data_sync/native_app/android/app/build/outputs/apk/preview/app-preview.apk` relative to the repository root. It includes its JavaScript bundle, runs without Metro, and is signed with a local debug key for testing. Local collection needs no server; enter a reachable **HTTPS** server URL when connecting this preview app. Production release builds need your own signing configuration.

Start the companion server instead of the regular backend entry point when using Android exports. Run these commands from the repository root:

```sh
.venv/bin/python -m pip install -r data_sync/native_app/server/requirements.txt
npm run build
.venv/bin/python -m data_sync.native_app.server
```

The companion serves `/api/native/*` and the built dashboard from one origin, using the existing database and accounts. Expose it over HTTPS for the preview app and set `COOKIE_SECURE=true` for that deployment. The development build supports local HTTP and uses Metro.

For a phone on the same trusted LAN, `npm run build:apk:lan` from `data_sync/native_app` creates a standalone LAN test APK at `android/app/build/outputs/apk/lan/app-lan.apk`. This variant accepts HTTP to private IPv4 addresses such as `http://192.168.1.50:8000`, runs without Metro, and updates the preview app while preserving local records. HTTP does not encrypt credentials or uploaded data; regular preview and release builds still require HTTPS. Keep `COOKIE_SECURE=false` for local HTTP.

See [`data_sync/README.md`](data_sync/README.md) for prerequisites, SDK setup, Windows commands, APK installation, and troubleshooting. See the [native app README](data_sync/native_app/README.md) for available data sources, permissions, API details, and verification commands.

## Exercise and food guides

Workout and meal cards show small photos from a frontend-only guide library. Selecting a photo opens it in focus mode, with the arrow keys moving between photos. Exercise and meal names link to guide pages with photos, step-by-step instructions and tips:

- `/exercise/?q=<id>`, for example `/exercise/?q=childs-pose`. Free text such as `?q=child's-pose` or `?q=Dumbbell Bicep Curls` resolves to the same guide.
- `/food/?q=<id>`, for example `/food/?q=vegetable-biryani`.
- `/exercise/` and `/food/` list every guide. Unknown items show the list with a notice.

The mappings live in `public/library/exercise.json` (109 exercises, 2–3 images each, each with the body area it works) and `public/library/food.json` (78 dishes). Together they cover every exercise and meal in the saved plans, plus common exercises (push-ups, machines, cardio equipment, yoga) and dishes (Indian breakfasts and curries, vegan, egg, chicken and fish options) that future plans are likely to include. Images are WebP files in `public/library/exercise/` and `public/library/food/`, each with a `.thumb.webp` for cards. Each item's `aliases` are matched against plan text: case and punctuation are ignored, whole words only, and plurals are allowed. When aliases overlap, the longest one wins. To support a new exercise or dish, add an item with its photos and aliases. The guide pages need no backend. A static host must serve `index.html` for `/exercise/` and `/food/`, as the Vite dev and preview servers already do.

Each exercise guide also has one short how-to video (all under 3 minutes, median about 1 minute 15 seconds). They come from YouTube channels such as NASM, Howcast, PureGym, Bupa, Nuffield Health and ScottHermanFitness, and every pick was reviewed for form-focused content. The page shows a thumbnail and loads the privacy-enhanced `youtube-nocookie.com` player only when the viewer presses play. If a video is later removed from YouTube, its player shows YouTube's unavailable notice; update `video` in `exercise.json` to replace it.

Administrators can find every guide under **Static pages** in the sidebar. It has Exercise and Food tabs with a filter, and each page's link, photo count, video (exercises), matching aliases, photo licences, and Open and Copy link actions.

Exercise photos come from the public-domain [free-exercise-db](https://github.com/yuhonas/free-exercise-db), Wikimedia Commons and Flickr (via Openverse). Food photos come from Wikimedia Commons and Flickr. High knees, wall sit and clamshell had no openly licensed photos, so they use original two-step SVG illustrations instead. Each photo records its author, licence and source page, and these are shown in focus mode and on the guide page.

## Configuration

`OPEN_API_KEY` is supported exactly as requested; `OPENAI_API_KEY` also works. `OPENAI_MODEL` defaults to `gpt-4.1-mini`, and `LLM_PROVIDER` defaults to `openai`. There is no fabricated or sample-plan fallback on provider errors.

Leave `REDIS_URL` empty for an atomic JSON file cache. Set it to a Redis connection URL to use Redis; the file cache handles unavailable Redis connections. SQLite remains authoritative. Data lives under `backend/data/` (override with `FORMA_DATA_DIR`).

Dashboard notifications work immediately. Email delivery needs `SMTP_HOST`, `SMTP_PORT`, `SMTP_FROM`, and, where required, `SMTP_USER`/`SMTP_PASSWORD`. SMTP is not configured in the supplied environment. Notification records show whether email was sent, disabled, not configured, or failed; they never claim delivery when SMTP is unavailable.

Calendar dates default to `Asia/Kolkata`. The current planner supports adults aged 18–100. Calorie and burn values are estimates, with planning assumptions available in the UI.

## Validation

```sh
.venv/bin/python -m pytest backend/tests -q
npm run test:unit
npm run build
npx playwright install chromium
npm run test:e2e
```

Run the API and frontend before browser tests. The backend tests use isolated temporary databases and a controlled provider, so they do not incur API charges or send email. They cover persistent authentication, tenant isolation, uploads, task validation, admin CRUD/impersonation, background jobs, care timing, bounded review revisions, and preservation of previous plans after failures.

Phase 2 tests exercise actual MCP initialization, tool discovery and calls, strict 1–28 day validation, token hashing/revocation/expiry, member isolation, browser consent and CSRF, exact redirect matching, PKCE verification, resource binding, confidential-client authentication, refresh rotation and reuse rejection, and OAuth revocation. Progress tests cover dated task totals, private photos, bounded visual inputs, persisted reviews, provider failures, and stale-review detection when tracking or photos change.

Plan-adjustment tests also cover request limits, stored preferences reaching future generation, preservation of completed/skipped tasks, extension dates, archived versions, retries, concurrent-job rejection, and tenant isolation. Browser tests exercise both dialogs, saved notes, failure recovery, mobile layout, and navigation through extended weeks using intercepted planning responses; they leave the administrator's real plan unchanged.

View tests cover nutrition and burn on cards, the Minimal view and its saved preference, and the Static pages tab. Backend tests check that the nutrition and burn fields are required by the structured-output schema, rejected when inconsistent, and kept in generated plans. Guide tests check that every saved meal title maps to a food guide, that every exercise has at least two photos on disk with credits, alias precedence, `?q=` resolution, focus mode, links to and back from guide pages, direct guide URLs, and phone layout.

Quantity tests cover legacy sets/reps/holds, hour/minute formatting, structured-data precedence, and absent quantities. Backend checks exercise-field schema compatibility and validates durations. Card browser tests verify daily totals, portions, persisted completion/skipping, details, filters, and responsive layout with the saved admin plan.

Authenticated adjustment endpoints are `POST /api/plans/refine` and `POST /api/plans/extend`, with JSON such as `{"days": 7, "preferences": "Keep workouts under 30 minutes."}`. Both return a queued job; poll `GET /api/jobs/{id}` and refresh `GET /api/me` after completion. `GET /api/preferences` returns the tenant's saved notes, and `POST /api/jobs/{id}/retry` retries a failed request.

A live OpenAI run was also verified: 28 days, all three planning roles, approved independent review, and delayed care. Its real saved plan was exercised in the browser for task completion/skipping, all four calendar weeks, routine details, water logging, check-ins, photos, and progress charts. `tests/live-plan.spec.js` runs when a temporary live-verification account descriptor is available under `backend/data/verification.json`; otherwise it is skipped.

Implementation follows the official [OpenAI structured outputs guide](https://developers.openai.com/api/docs/guides/structured-outputs) and FastAPI documentation for [file uploads](https://fastapi.tiangolo.com/tutorial/request-files/).

## Sample member onboarding

The optional seed helper creates a regular member at `sample@example.com` with password `sample123`, unless `SAMPLE_EMAIL` or `SAMPLE_PASSWORD` overrides them. It uses a fictional profile: age 29, 175 cm, 75 kg, beginner, vegetarian, gym access, and optional skin/hair care beginning in week 3. Member accounts can use personal plans and MCP connections; `admin@example.com` manages users and guides.

The seed helper preserves existing profiles and plans and resumes polling an active planning job:

```sh
.venv/bin/python scripts/seed_sample_member.py
```

The login dialog prefills `admin@example.com` / `admin123` for the local sample workspace. New onboarding forms start with clearly labeled sample defaults; existing saved values take precedence. Photo inputs remain optional and require selecting actual files.
