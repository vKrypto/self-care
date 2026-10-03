import { test, expect } from "@playwright/test";
import { randomUUID } from "node:crypto";
import { readFile } from "node:fs/promises";
import { signUpMember, removeMember } from "./member.js";

test.beforeEach(async ({ page }) => {
  // These data-flow checks do not depend on external font servers.
  await page.route(/^https:\/\/fonts\.(googleapis|gstatic)\.com\//, (route) =>
    route.abort(),
  );
});

test.afterEach(async ({ page }) => {
  await removeMember(page);
});

const selectedDate = "2026-10-01";
const start = Date.parse(`${selectedDate}T08:00:00Z`);
const end = start + 3_600_000;
const section = (records, extra = {}) => ({
  status: "ok",
  complete: true,
  records,
  ...extra,
});

async function syncedAccount(page) {
  const account = await signUpMember(page);
  const email = account.email;
  const password = "SampleTester123!";
  const login = await page.request.post("/api/native/login", {
    data: { email, password },
  });
  expect(login.ok()).toBe(true);
  const session = await login.json();
  const headers = { Authorization: `Bearer ${session.token}` };
  const deviceId = randomUUID();
  const registration = await page.request.post("/api/native/devices", {
    headers,
    data: {
      device_id: deviceId,
      platform: "android",
      name: "Test Android phone",
      sync_interval_minutes: 60,
      consent_version: "1",
      history_days: 30,
    },
  });
  expect(registration.ok()).toBe(true);
  const payload = {
    schema_version: 1,
    batch_id: randomUUID(),
    device_id: deviceId,
    user_id: session.user.id,
    window: { start_ms: start, end_ms: end },
    collected_at_ms: end,
    permissions: { usage_access: true },
    data: {
      device_snapshot: section(
        [{ timezone: "Asia/Kolkata", model: "Test phone" }],
        { mode: "snapshot" },
      ),
      usage_events: section([
        {
          timestamp_ms: start,
          event_type: 1,
          package_name: "com.example.reader",
          class_name: "Reader",
        },
        { timestamp_ms: start, event_type: 15 },
        { timestamp_ms: start + 1000, event_type: 18 },
        {
          timestamp_ms: start + 1_800_000,
          event_type: 2,
          package_name: "com.example.reader",
          class_name: "Reader",
        },
        { timestamp_ms: start + 1_800_000, event_type: 16 },
      ]),
      visible_apps: section(
        Array.from({ length: 30 }, (_, i) => ({
          package_name: `com.example.app${i}`,
          label: `Example app ${i}`,
          ...(i === 0 ? { sensor_timestamp_nanos: "__exact_nanos__" } : {}),
        })),
        { mode: "snapshot" },
      ),
      health_steps: section([
        {
          _type: "StepsRecord",
          startTime: { epoch_ms: start },
          endTime: { epoch_ms: end },
          count: 2400,
          metadata: {
            id: "steps-for-this-hour",
            dataOrigin: { packageName: "com.example.health" },
            lastModifiedTime: { epoch_ms: end },
          },
        },
      ]),
      source_status: section([
        {
          source: "health_weight",
          status: "denied",
          complete: false,
          collected: false,
        },
        {
          source: "health_steps",
          status: "ok",
          complete: true,
          collected: true,
        },
      ]),
      health_height: section([
        {
          _type: "HeightRecord",
          time: { epoch_ms: start },
          height: { meters: 1.75 },
          metadata: {
            id: "height-reading",
            dataOrigin: { packageName: "com.example.health" },
          },
        },
      ]),
      health_heart_rate_variability_rmssd: section([
        {
          _type: "HeartRateVariabilityRmssdRecord",
          time: { epoch_ms: start },
          heartRateVariabilityMillis: 25.5,
          metadata: {
            id: "hrv-reading",
            dataOrigin: { packageName: "com.example.health" },
          },
        },
      ]),
    },
  };
  const sendExport = (body) =>
    page.request.post("/api/native/batches", {
      headers: { ...headers, "Content-Type": "application/json" },
      data: JSON.stringify(body).replace(
        '"__exact_nanos__"',
        "9007199254740993",
      ),
    });
  const first = await sendExport(payload);
  expect(first.ok()).toBe(true);
  const retry = await sendExport(payload);
  expect((await retry.json()).duplicate).toBe(true);
  const next = await sendExport({
    ...payload,
    batch_id: randomUUID(),
    collected_at_ms: end + 1000,
  });
  expect(next.ok()).toBe(true);
  await page.goto("/");
  await expect(
    page.getByRole("button", { name: "Connected devices", exact: true }),
  ).toBeVisible();
  return { deviceId, payload, headers, email };
}

test("device history, source pagination, and daily/weekly totals use real synced exports", async ({
  page,
}) => {
  await syncedAccount(page);
  await page
    .getByRole("button", { name: "Connected devices", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Connected devices", exact: true }),
  ).toBeVisible();
  await expect(page.locator(".device-summary-strip")).toContainText(
    "2 successful syncs",
  );
  await page
    .getByRole("button", { name: "View Test Android phone", exact: true })
    .click();
  await expect(page.locator(".device-facts").first()).toContainText(
    "First synced",
  );
  await expect(page.locator(".device-history-row")).toHaveCount(2);
  await page.locator(".device-history-row").first().click();
  await page
    .getByRole("button", { name: "View App information records", exact: true })
    .click();
  await expect(page.getByText("Record 1", { exact: true })).toBeVisible();
  await page.getByText("Record 1", { exact: true }).click();
  await expect(page.locator(".device-record").first()).toContainText(
    "Example app 0",
  );
  await expect(page.locator(".device-record").first()).toContainText(
    "9007199254740993",
  );
  const downloadPromise = page.waitForEvent("download");
  await page
    .getByRole("button", { name: "Download JSON", exact: true })
    .click();
  const downloaded = await downloadPromise;
  expect(await readFile(await downloaded.path(), "utf8")).toContain(
    '"sensor_timestamp_nanos":9007199254740993',
  );
  await page.getByRole("button", { name: "Next", exact: true }).click();
  await expect(page.getByText("Record 26", { exact: true })).toBeVisible();
  await page.getByText("Record 26", { exact: true }).click();
  await expect(page.locator(".device-record").first()).toContainText(
    "Example app 25",
  );
  await page
    .getByRole("button", { name: "Device history", exact: true })
    .click();
  await page
    .getByRole("button", { name: "Digital wellbeing", exact: true })
    .last()
    .click();
  await page.getByLabel("Wellbeing date").fill(selectedDate);
  await expect(
    page
      .locator(".wellbeing-metric")
      .filter({ hasText: "App foreground time" }),
  ).toContainText("30 min");
  await expect(
    page
      .locator(".wellbeing-metric")
      .filter({ hasText: "Device unlocks" })
      .locator("strong"),
  ).toHaveText("1");
  await expect(
    page
      .locator(".wellbeing-health-item")
      .filter({ hasText: "Reported steps" })
      .locator("strong"),
  ).toHaveText("2,400");
  await expect(
    page
      .locator(".wellbeing-health-item")
      .filter({ hasText: "Recorded weight" }),
  ).toContainText("No data");
  await expect(
    page
      .locator(".wellbeing-health-item")
      .filter({ hasText: "Recorded weight" }),
  ).toContainText("Permission required");
  await expect(
    page
      .locator(".wellbeing-health-item")
      .filter({ hasText: "Recorded height" })
      .locator("strong"),
  ).toHaveText("1.75 m");
  await expect(
    page
      .locator(".wellbeing-health-item")
      .filter({ hasText: "Heart rate variability" })
      .locator("strong"),
  ).toHaveText("25.5 ms");
  await page.getByRole("button", { name: "Weekly", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "Weekly", exact: true }),
  ).toHaveAttribute("aria-pressed", "true");
  await expect(
    page
      .getByRole("group", { name: "Daily app foreground time" })
      .getByRole("button"),
  ).toHaveCount(7);
  await expect(
    page
      .locator(".wellbeing-health-item")
      .filter({ hasText: "Reported steps" })
      .locator("strong"),
  ).toHaveText("2,400");
  await page
    .getByRole("button", { name: `${selectedDate}: 30 min`, exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "Daily", exact: true }),
  ).toHaveAttribute("aria-pressed", "true");
});

test("daily and weekly unlock summaries are labeled as estimates", async ({
  page,
}) => {
  const { payload, headers } = await syncedAccount(page);
  const summaryDate = "2026-09-30";
  const summaryStart = Date.parse(`${summaryDate}T01:00:00Z`);
  const summaryEnd = summaryStart + 3_600_000;
  const upload = await page.request.post("/api/native/batches", {
    headers,
    data: {
      ...payload,
      batch_id: randomUUID(),
      window: { start_ms: summaryStart, end_ms: summaryEnd },
      collected_at_ms: summaryEnd,
      data: {
        usage_event_stats: section([
          {
            event_type: 18,
            first_timestamp_ms: summaryStart,
            last_timestamp_ms: summaryEnd,
            count: 4,
            total_time_ms: 0,
          },
        ]),
      },
    },
  });
  expect(upload.ok()).toBe(true);
  await page
    .getByRole("button", { name: "Digital wellbeing", exact: true })
    .click();
  await page.getByLabel("Wellbeing date").fill(summaryDate);
  const unlocks = page
    .locator(".wellbeing-metric")
    .filter({ hasText: "Device unlocks" });
  await expect(unlocks.locator("strong")).toHaveText("4");
  await expect(unlocks).toContainText(
    "Estimated from Android unlock summaries",
  );
  await page.getByRole("button", { name: "Weekly", exact: true }).click();
  await expect(unlocks.locator("strong")).toHaveText("5");
  await expect(unlocks).toContainText(
    "Estimated from Android unlock summaries",
  );
});

test("connecting explains the account and server; removal deletes only the selected test device", async ({
  page,
}) => {
  const { deviceId, payload, headers, email } = await syncedAccount(page);
  await page
    .getByRole("button", { name: "Connected devices", exact: true })
    .click();
  await page
    .getByRole("button", { name: "Connect new device", exact: true })
    .click();
  const setup = page.getByRole("dialog", {
    name: "Connect an Android device",
    exact: true,
  });
  await expect(setup).toContainText(email);
  await expect(setup.getByLabel("Server address")).toHaveValue(/http:\/\//);
  await setup.getByRole("button", { name: "Done", exact: true }).click();
  await page
    .getByRole("button", { name: "Digital wellbeing", exact: true })
    .click();
  await page.getByLabel("Filter by device").selectOption(deviceId);
  await page
    .getByRole("button", { name: "Remove device", exact: true })
    .click();
  const confirmation = page.getByRole("dialog", {
    name: "Remove Test Android phone?",
    exact: true,
  });
  await confirmation
    .getByRole("button", { name: "Keep device", exact: true })
    .click();
  expect(
    (await (await page.request.get("/api/devices")).json()).devices,
  ).toHaveLength(1);
  await page
    .getByRole("button", { name: "Remove device", exact: true })
    .click();
  await confirmation
    .getByRole("button", { name: "Remove device and history", exact: true })
    .click();
  await expect(
    page.getByRole("heading", {
      name: "Build a picture of your day.",
      exact: true,
    }),
  ).toBeVisible();
  expect(
    (await (await page.request.get("/api/devices")).json()).devices,
  ).toHaveLength(0);
  const retry = await page.request.post("/api/native/batches", {
    headers,
    data: payload,
  });
  expect(retry.status()).toBe(410);
});

test("device pages stay usable on a phone-sized screen", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await syncedAccount(page);
  await page
    .getByRole("button", { name: "Expand sidebar", exact: true })
    .click();
  await page
    .getByRole("button", { name: "Connected devices", exact: true })
    .click();
  await page
    .getByRole("button", { name: "View Test Android phone", exact: true })
    .click();
  await page
    .getByRole("button", { name: "Digital wellbeing", exact: true })
    .last()
    .click();
  await page.getByLabel("Wellbeing date").fill(selectedDate);
  await expect(page.locator(".wellbeing-metric").first()).toContainText(
    "30 min",
  );
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth > window.innerWidth + 1,
  );
  expect(overflow).toBe(false);
});
