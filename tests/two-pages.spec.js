import { test, expect } from "@playwright/test";
import { memberMe } from "./member.js";

test("overview owns reports and calendar owns daily planning", async ({
  page,
}) => {
  const me = memberMe({
    id: "member",
    name: "Sample Tester",
    email: "sample@example.com",
    role: "user",
  });
  const day = me.plan.days[0];
  day.tasks.push({
    id: "care",
    role: "care",
    title: "Morning skin care",
    category: "Skin care",
    time: "06:00",
    minutes: 5,
    calories: 0,
    description: "Daily care",
    steps: ["Apply sunscreen"],
    ingredients: [],
    week_note: "Stay consistent",
  });
  await page.route("**/api/me", (route) => route.fulfill({ json: me }));
  await page.route("**/api/progress", (route) =>
    route.fulfill({
      json: {
        statuses: [
          { date: day.date, task_id: day.tasks[0].id, status: "completed" },
        ],
        checkins: [],
        history: [],
      },
    }),
  );
  await page.route("**/api/media", (route) => route.fulfill({ json: [] }));
  await page.goto("/");
  const nav = page.getByRole("navigation", { name: "Main navigation" });
  await expect(nav.getByRole("button")).toHaveCount(2);
  await expect(
    page.getByRole("heading", { name: "Current plan & targets" }),
  ).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "Weekly progress", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "Weight check-ins" }),
  ).toBeVisible();
  await expect(page.locator("#daily")).toHaveCount(0);
  await page.locator(".weekly-progress-chart button").first().click();
  await expect(
    nav.getByRole("button", { name: "My calendar" }),
  ).toHaveAttribute("aria-current", "page");
  await expect(page.locator("#daily")).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "Day-by-day progress" }),
  ).toBeVisible();
  await page
    .locator("#daily")
    .getByRole("button", { name: "Care routines", exact: true })
    .click();
  await expect(page.locator("#daily")).toContainText("Morning skin care");
  await page.getByRole("button", { name: "Next week" }).click();
  await expect(
    page.locator("#daily").getByText("Week 2 of 4", { exact: true }),
  ).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
});
