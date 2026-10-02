import { test, expect } from "@playwright/test";
import { memberMe } from "./member.js";
import { localDate, shiftDate } from "../src/api.js";

const member = {
  id: "phase2-member",
  name: "Progress Tester",
  email: "progress@example.com",
  role: "member",
};
const activities = [
  {
    id: "walk",
    role: "workout",
    category: "Workout",
    title: "Morning walk",
    time: "07:00",
    description: "Walk at a comfortable pace.",
    minutes: 20,
    calories: 90,
    ingredients: [],
    steps: ["Walk for 20 minutes."],
    week_note: "A steady pace.",
  },
  {
    id: "breakfast",
    role: "meal",
    category: "Breakfast",
    title: "Oat breakfast",
    time: "08:00",
    description: "A balanced breakfast.",
    minutes: 10,
    calories: 400,
    ingredients: ["1 cup oats"],
    steps: ["Prepare the oats."],
    week_note: "A steady pace.",
  },
];
const pixel =
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jB9kAAAAASUVORK5CYII=";
const photo = (date, id) => ({
  id,
  kind: "progress",
  date,
  created: date + "T09:00:00Z",
  url: "data:image/png;base64," + pixel,
});
function deferred() {
  let resolve;
  const promise = new Promise((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

async function workspace(page, options = {}) {
  const today = localDate();
  const me = memberMe(options.admin ? { ...member, role: "admin" } : member, {
    tasks: activities,
  });
  const state = {
    statuses: [],
    photos: options.photos || [],
    checkins: [],
    tokens: [],
    connections: [
      {
        id: "oauth-1",
        name: "ChatGPT",
        created: today + "T09:00:00Z",
        revoked: false,
      },
    ],
    analyses: 0,
    uploads: [],
    reviews: {},
  };
  function tracking(date) {
    const tasks = activities.map((task) => ({
      ...task,
      status:
        state.statuses.find(
          (record) => record.date === date && record.task_id === task.id,
        )?.status || "pending",
    }));
    const completed = tasks.filter(
      (task) => task.status === "completed",
    ).length;
    const skipped = tasks.filter((task) => task.status === "skipped").length;
    return {
      date,
      today,
      has_plan: true,
      tasks,
      counts: {
        completed,
        skipped,
        pending: tasks.length - completed - skipped,
        total: tasks.length,
      },
      adherence: {
        completed_percent: Math.round((completed / tasks.length) * 100),
        recorded_percent: Math.round(
          ((completed + skipped) / tasks.length) * 100,
        ),
      },
      checkin: state.checkins.find((checkin) => checkin.date === date) || null,
      photos: state.photos.filter((item) => item.date === date),
      feedback: {
        summary: `Saved record for ${date}: ${completed} completed and ${skipped} skipped.`,
        observations: [
          `${state.photos.filter((item) => item.date === date).length} progress photos saved for this date.`,
        ],
        next_steps: ["Choose one manageable activity next."],
      },
      photo_review: state.reviews[date] || null,
    };
  }
  await page.route("**/api/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname.slice(4);
    const method = request.method();
    let json;
    if (path === "/me") json = me;
    else if (path === "/admin/users") json = [];
    else if (path === "/progress")
      json = {
        statuses: state.statuses,
        checkins: state.checkins,
        history: [],
      };
    else if (path.startsWith("/tracking/")) {
      const date = path.split("/").at(-1);
      if (options.trackingGate && date === today)
        await options.trackingGate.promise;
      json = tracking(date);
    } else if (path === "/media" && method === "GET") json = state.photos;
    else if (path === "/media" && method === "POST") {
      const data = request.postDataBuffer().toString("utf8");
      const date = data.match(/name="selected_date"\r\n\r\n([^\r]+)/)?.[1];
      state.uploads.push(date);
      json = photo(date, `upload-${state.uploads.length}`);
      state.photos.push(json);
      if (state.reviews[date]) state.reviews[date].stale = true;
    } else if (path.startsWith("/media/") && method === "DELETE") {
      state.photos = state.photos.filter(
        (item) => item.id !== path.split("/").at(-1),
      );
      json = { saved: true };
    } else if (path === "/tasks/status") {
      json = request.postDataJSON();
      state.statuses = state.statuses.filter(
        (record) =>
          record.date !== json.date || record.task_id !== json.task_id,
      );
      state.statuses.push(json);
      if (state.reviews[json.date]) state.reviews[json.date].stale = true;
    } else if (path === "/checkins") {
      json = request.postDataJSON();
      state.checkins.push(json);
    } else if (path === "/progress/photos/analyze") {
      state.analyses++;
      if (options.analysisGate) await options.analysisGate.promise;
      if (options.providerError && state.analyses === 1) {
        await route.fulfill({
          status: 503,
          json: {
            detail: "Photo analysis provider is unavailable. Try again later.",
          },
        });
        return;
      }
      const { date } = request.postDataJSON();
      const review = {
        date,
        created: today + "T10:00:00Z",
        model: "vision-test",
        stale: false,
        assessment: {
          summary: `Photo review for ${date}.`,
          observations: ["The photos use similar lighting."],
          next_steps: ["Keep a consistent camera position."],
          limitations: [
            "These photos cannot measure body fat or establish a medical diagnosis.",
          ],
        },
      };
      state.reviews[date] = review;
      json = { photo_review: review, tracking: tracking(date) };
    } else if (path === "/mcp/info")
      json = { url: "http://127.0.0.1:8000/mcp", oauth: true };
    else if (path === "/mcp/tokens" && method === "GET") json = state.tokens;
    else if (path === "/mcp/tokens" && method === "POST") {
      const { name } = request.postDataJSON();
      json = {
        id: `token-${state.tokens.length + 1}`,
        name,
        token: "forma_test_once_only_secret",
        created: today + "T10:00:00Z",
        expires: 1800000000,
      };
      state.tokens.push({
        ...json,
        token: undefined,
        last_used: null,
        revoked: false,
      });
    } else if (path.startsWith("/mcp/tokens/") && method === "DELETE") {
      state.tokens.find(
        (token) => token.id === path.split("/").at(-1),
      ).revoked = true;
      json = { saved: true };
    } else if (path === "/mcp/connections") json = state.connections;
    else if (path.startsWith("/mcp/connections/") && method === "DELETE") {
      state.connections.find(
        (connection) => connection.id === path.split("/").at(-1),
      ).revoked = true;
      json = { saved: true };
    } else json = {};
    await route.fulfill({ json });
  });
  await page.setViewportSize({ width: 1440, height: 1100 });
  await page.goto("/");
  if (!options.admin) {
    await page
      .getByRole("button", { name: "My calendar", exact: true })
      .click();
    await page.locator("#daily").waitFor();
  } else
    await page.getByRole("heading", { name: "Users", exact: true }).waitFor();
  return { state, tracking, today };
}

test("completed and skipped activities refresh evidence-based feedback", async ({
  page,
}) => {
  await workspace(page);
  const feedback = page.getByRole("region", {
    name: "Daily progress feedback",
  });
  await expect(feedback).toContainText("0 completed and 0 skipped");
  await expect(
    feedback.getByRole("button", { name: "Analyze photos", exact: true }),
  ).toBeDisabled();
  await page
    .getByRole("button", { name: "Mark Morning walk completed" })
    .click();
  await expect(feedback).toContainText("1 completed and 0 skipped");
  await expect(feedback).toContainText("50% complete");
  await page
    .locator(".meal-card")
    .getByRole("button", { name: "Skip", exact: true })
    .click();
  await expect(feedback).toContainText("1 completed and 1 skipped");
  await expect(feedback).toContainText("0 pending");
});

test("multiple today's photos update progress and explicit analysis exposes provider errors and review", async ({
  page,
}) => {
  const { state, today } = await workspace(page, { providerError: true });
  await page.getByRole("button", { name: "Overview", exact: true }).click();
  await page.getByRole("button", { name: "Add today’s photos" }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByLabel("Progress photos (optional)").setInputFiles([
    {
      name: "front.png",
      mimeType: "image/png",
      buffer: Buffer.from(pixel, "base64"),
    },
    {
      name: "side.png",
      mimeType: "image/png",
      buffer: Buffer.from(pixel, "base64"),
    },
  ]);
  await dialog.getByRole("button", { name: "Save check-in" }).click();
  await expect(dialog).not.toBeVisible();
  expect(state.uploads).toEqual([today, today]);
  const feedback = page.getByRole("region", {
    name: "Daily progress feedback",
  });
  await expect(feedback).toContainText("2 progress photos saved");
  expect(state.analyses).toBe(0);
  await feedback
    .getByRole("button", { name: "Analyze photos", exact: true })
    .click();
  await expect(feedback.getByRole("alert")).toContainText(
    "provider is unavailable",
  );
  await feedback
    .getByRole("button", { name: "Analyze photos", exact: true })
    .click();
  await expect(feedback).toContainText(`Photo review for ${today}`);
  await expect(feedback).toContainText("The photos use similar lighting");
  await expect(feedback).toContainText("cannot measure body fat");
  await page.getByRole("button", { name: "My calendar", exact: true }).click();
  await page
    .getByRole("button", { name: "Mark Morning walk completed" })
    .click();
  await expect(feedback).toContainText("activity record have changed");
});

test("selected-date photos stay on that date and refresh its feedback", async ({
  page,
}) => {
  const { state, today } = await workspace(page);
  const selectedDate = shiftDate(today, 2);
  await page.locator(".date-strip button").nth(2).click();
  await page.getByRole("button", { name: "Add check-in", exact: true }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByLabel("Progress photos (optional)").setInputFiles({
    name: "progress.png",
    mimeType: "image/png",
    buffer: Buffer.from(pixel, "base64"),
  });
  await dialog.getByRole("button", { name: "Save check-in" }).click();
  await expect(dialog).not.toBeVisible();
  expect(state.uploads).toEqual([selectedDate]);
  const feedback = page.getByRole("region", {
    name: "Daily progress feedback",
  });
  await expect(feedback).toContainText(`Saved record for ${selectedDate}`);
  await expect(feedback).toContainText("1 progress photos saved");
  await expect(
    feedback.getByRole("button", { name: "Analyze photos", exact: true }),
  ).toBeEnabled();
  await page.locator(".date-strip button").first().click();
  await expect(feedback).toContainText(`Saved record for ${today}`);
  await expect(
    feedback.getByRole("button", { name: "Analyze photos", exact: true }),
  ).toBeDisabled();
});

test("late tracking responses cannot replace the selected date's progress", async ({
  page,
}) => {
  const gate = deferred();
  const { today } = await workspace(page, { trackingGate: gate });
  const tomorrow = shiftDate(today, 1);
  await page.locator(".date-strip button").nth(1).click();
  const feedback = page.getByRole("region", {
    name: "Daily progress feedback",
  });
  await expect(feedback).toContainText(`Saved record for ${tomorrow}`);
  gate.resolve();
  await expect(feedback).not.toContainText(`Saved record for ${today}`);
  await expect(feedback).toContainText(`Saved record for ${tomorrow}`);
});

test("analysis shows loading and a previous day's result cannot appear on a new date", async ({
  page,
}) => {
  const gate = deferred();
  const today = localDate(),
    tomorrow = shiftDate(today, 1);
  await workspace(page, {
    photos: [photo(today, "today-photo"), photo(tomorrow, "tomorrow-photo")],
    analysisGate: gate,
  });
  const feedback = page.getByRole("region", {
    name: "Daily progress feedback",
  });
  await feedback
    .getByRole("button", { name: "Analyze photos", exact: true })
    .click();
  await expect(
    feedback.getByRole("button", { name: "Analyzing photos…" }),
  ).toBeDisabled();
  await expect(feedback).toContainText("Reviewing your photos");
  await page.locator(".date-strip button").nth(1).click();
  await expect(feedback).toContainText(`Saved record for ${tomorrow}`);
  gate.resolve();
  await expect(feedback).not.toContainText(`Photo review for ${today}`);
  await expect(
    feedback.getByRole("button", { name: "Analyze photos", exact: true }),
  ).toBeEnabled();
});

test("MCP settings create once-only tokens, explain remote access, and revoke tokens and apps", async ({
  page,
}) => {
  await workspace(page);
  await page.getByRole("button", { name: "Settings", exact: true }).click();
  const panel = page.getByRole("region", { name: "MCP connections" });
  await expect(panel.getByLabel("MCP server URL", { exact: true })).toHaveValue(
    "http://127.0.0.1:8000/mcp",
  );
  await expect(panel).toContainText("public HTTPS address");
  await panel.getByLabel("Connection name").fill("Claude laptop");
  await panel.getByRole("button", { name: "Create connection token" }).click();
  await expect(panel.getByLabel("New connection token")).toHaveValue(
    "forma_test_once_only_secret",
  );
  expect(await page.evaluate(() => JSON.stringify(localStorage))).not.toContain(
    "forma_test_once_only_secret",
  );
  await panel.getByText("Claude CLI setup", { exact: true }).click();
  await expect(panel.locator("pre")).toContainText(
    "claude mcp add --transport http forma",
  );
  await panel
    .getByRole("button", { name: "Revoke Claude laptop", exact: true })
    .click();
  await expect(panel.getByLabel("New connection token")).toHaveCount(0);
  await expect(panel.locator(".mcp-list").first()).toContainText("Revoked");
  await panel
    .getByRole("button", { name: "Revoke ChatGPT", exact: true })
    .click();
  await expect(panel.locator(".mcp-list").nth(1)).toContainText("Revoked");
  await panel.getByLabel("Connection name").fill("Second agent");
  await panel.getByRole("button", { name: "Create connection token" }).click();
  await expect(panel.getByLabel("New connection token")).toBeVisible();
  await page.getByRole("button", { name: "Close dialog" }).click();
  await page.getByRole("button", { name: "Settings", exact: true }).click();
  await expect(page.getByLabel("New connection token")).toHaveCount(0);
});

test("administrator settings do not expose member MCP connections", async ({
  page,
}) => {
  await workspace(page, { admin: true });
  await page.getByRole("button", { name: "Settings", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Administrator settings." }),
  ).toBeVisible();
  await expect(
    page.getByRole("region", { name: "MCP connections" }),
  ).toHaveCount(0);
});
