# Forma — Exercise planner

React frontend, FastAPI backend, SQLite database, tenant-scoped local media, and Redis with a persistent file-cache fallback.

Phase 2 adds authenticated MCP access, daily progress photos, date-level tracking, and adherence feedback.

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
- Admins can create/list/delete accounts, set passwords, and enter a user’s workspace. They also have their own profile and wellness pages alongside the Users screen. Impersonation has an explicit return-to-admin action. Deletion removes the tenant's database records and local files.
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

### Local agent connection

Sign in to a member account, open **Settings → Agent connections**, and create a personal access token. Copy it immediately; only its hash is stored. Tokens expire and can be revoked in Settings. Credentials remain outside frontend bundles.

For Claude Code using HTTP, run the backend and add it as a project-local MCP server:

```sh
read -rsp 'Forma token: ' FORMA_MCP_TOKEN; export FORMA_MCP_TOKEN
claude mcp add --transport http forma http://127.0.0.1:8000/mcp \
  --header "Authorization: Bearer $FORMA_MCP_TOKEN"
```

Claude stores configured headers in its MCP configuration; keep that file private. Agents supporting stdio can instead launch the included proxy with credentials supplied through the process environment:

```json
{
  "mcpServers": {
    "forma": {
      "command": "/absolute/path/to/exercise_planner/.venv/bin/python",
      "args": ["-m", "backend.mcp_server", "--stdio"],
      "cwd": "/absolute/path/to/exercise_planner",
      "env": {
        "FORMA_MCP_URL": "http://127.0.0.1:8000/mcp",
        "FORMA_MCP_TOKEN": "YOUR_PERSONAL_ACCESS_TOKEN"
      }
    }
  }
}
```

For clients without a `cwd` configuration option, add `PYTHONPATH` set to the repository's absolute path in `env`. The stdio process forwards requests to the running backend; start the backend first. It creates no separate database, and stdout contains only MCP messages.

### Remote ChatGPT / Claude connection

Remote hosted agents require a reachable HTTPS backend; a localhost URL is only usable by local agents. Deploy behind HTTPS and set `FORMA_PUBLIC_URL=https://your-backend.example` and `COOKIE_SECURE=true`. Configure your reverse proxy to route `/mcp`, `/oauth/*`, and `/.well-known/*` to FastAPI. Use `https://your-backend.example/mcp` as the remote MCP URL in a client that supports custom MCP connectors.

The service advertises protected-resource and authorization-server discovery, supports dynamic client registration, and serves a Forma login/consent page. Sign in as a member and explicitly approve the client. Authorization uses exact registered redirects, authorization-code flow with S256 PKCE, and resource-bound credentials. Access tokens last one hour. Refresh tokens rotate and expire after 30 days; reuse revokes the connection. Settings lists OAuth connections and can revoke them immediately. Client registration supports public clients (`none`) and confidential clients (`client_secret_post` / `client_secret_basic`). Feature availability and connector setup depend on the agent product and account; the server does not deploy itself or enable remote account features.

Session-authenticated management endpoints are `GET/POST /api/mcp/tokens`, `DELETE /api/mcp/tokens/{id}`, `GET /api/mcp/connections`, and `DELETE /api/mcp/connections/{id}`. Token creation accepts `{"name":"My agent","expires_days":90}` with a 1–365 day expiry and shows the secret once. MCP requests always use `Authorization: Bearer <token>`.

The transport follows the official [Python SDK v1 documentation](https://py.sdk.modelcontextprotocol.io/v1/) and [MCP authorization specification](https://modelcontextprotocol.io/specification/2025-06-18/basic/authorization). Local Claude configuration follows the [Claude Code MCP guide](https://code.claude.com/docs/en/mcp).

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

Plan-adjustment tests also cover request limits, stored preferences reaching future generation, preservation of completed/skipped tasks, extension dates, archived versions, retries, concurrent-job rejection, and tenant isolation. Browser tests exercise both dialogs, saved notes, failure recovery, mobile layout, and navigation through extended weeks using intercepted planning responses; they leave the administrator's real plan unchanged.

View tests cover nutrition and burn on cards, the Minimal view and its saved preference, and the Static pages tab. Backend tests check that the nutrition and burn fields are required by the structured-output schema, rejected when inconsistent, and kept in generated plans. Guide tests check that every saved meal title maps to a food guide, that every exercise has at least two photos on disk with credits, alias precedence, `?q=` resolution, focus mode, links to and back from guide pages, direct guide URLs, and phone layout.

Quantity tests cover legacy sets/reps/holds, hour/minute formatting, structured-data precedence, and absent quantities. Backend checks exercise-field schema compatibility and validates durations. Card browser tests verify daily totals, portions, persisted completion/skipping, details, filters, and responsive layout with the saved admin plan.

Authenticated adjustment endpoints are `POST /api/plans/refine` and `POST /api/plans/extend`, with JSON such as `{"days": 7, "preferences": "Keep workouts under 30 minutes."}`. Both return a queued job; poll `GET /api/jobs/{id}` and refresh `GET /api/me` after completion. `GET /api/preferences` returns the tenant's saved notes, and `POST /api/jobs/{id}/retry` retries a failed request.

A live OpenAI run was also verified: 28 days, all three planning roles, approved independent review, and delayed care. Its real saved plan was exercised in the browser for task completion/skipping, all four calendar weeks, routine details, water logging, check-ins, photos, and progress charts. `tests/live-plan.spec.js` runs when a temporary live-verification account descriptor is available under `backend/data/verification.json`; otherwise it is skipped.

Implementation follows the official [OpenAI structured outputs guide](https://developers.openai.com/api/docs/guides/structured-outputs) and FastAPI documentation for [file uploads](https://fastapi.tiangolo.com/tutorial/request-files/).

## Administrator sample onboarding

A fictional sample profile is saved for `admin@example.com`: age 29, 175 cm, 75 kg, beginner, vegetarian, gym access, and optional skin/hair care beginning in week 3. Its account keeps the admin role. Sign in, then select **Overview** for the personal plan or **Users** for administration.

The seed helper preserves existing profiles and plans and resumes polling an active planning job:

```sh
.venv/bin/python scripts/seed_admin_sample.py
```

The login dialog prefills `admin@example.com` / `admin123` for the local sample workspace. New onboarding forms start with clearly labeled sample defaults; existing saved values take precedence. Photo inputs remain optional and require selecting actual files.
