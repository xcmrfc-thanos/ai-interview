const { test, expect } = require("@playwright/test");

const baseURL = process.env.E2E_BASE_URL || "http://127.0.0.1:5000";
const viewports = [
  { name: "desktop", width: 1440, height: 900 },
  { name: "laptop", width: 1024, height: 768 },
  { name: "tablet", width: 768, height: 1024 },
  { name: "mobile", width: 375, height: 812 },
];
const publicPages = [
  ["home", "/"],
  ["login", "/loginView"],
  ["register", "/registerView"],
];
const applicantPages = [
  ["workspace", "/applicant/workspace"],
  ["plans", "/applicant/interview-plans"],
  ["plan-detail", "/applicant/interview-plans/1"],
  ["copilot", "/applicant/copilot?plan_id=1"],
  ["mock", "/applicant/mock-interview?plan_id=1"],
  ["resumes", "/applicant/resume_manage"],
  ["reviews", "/applicant/reviews"],
  ["review-detail", "/applicant/reviews/1"],
  ["knowledge", "/applicant/knowledge"],
  ["settings", "/applicant/personal_center"],
];

async function assertLayout(page, pageName) {
  const audit = await page.evaluate(() => {
    const root = document.documentElement;
    const clippedControls = [...document.querySelectorAll("button, a")]
      .filter((element) => {
        const style = getComputedStyle(element);
        const rect = element.getBoundingClientRect();
        return style.display !== "none" && rect.width > 0 && element.scrollWidth > element.clientWidth + 1;
      })
      .map((element) => (element.textContent || element.getAttribute("aria-label") || element.tagName).trim().slice(0, 80));
    return {
      scrollWidth: root.scrollWidth,
      viewportWidth: window.innerWidth,
      clippedControls,
    };
  });
  expect(audit.scrollWidth, `${pageName} has horizontal overflow`).toBeLessThanOrEqual(audit.viewportWidth + 1);
  expect(audit.clippedControls, `${pageName} has clipped controls`).toEqual([]);
}

async function assertTouchTargets(page, pageName) {
  const undersized = await page.locator(".button, .icon-button, [data-nav-toggle]").evaluateAll((elements) =>
    elements.filter((element) => {
      const style = getComputedStyle(element);
      const rect = element.getBoundingClientRect();
      return style.display !== "none" && rect.width > 0 && (rect.width < 44 || rect.height < 44);
    }).map((element) => (element.textContent || element.getAttribute("aria-label") || element.tagName).trim().slice(0, 80))
  );
  expect(undersized, `${pageName} has touch targets smaller than 44px`).toEqual([]);
}

async function assertDynamicTextResilience(page, pageName) {
  const selector = ".plan-card h2, .knowledge-item h2, .page-header h1";
  const target = page.locator(selector).first();
  if (!(await target.count())) return;
  const original = await target.textContent();
  await target.evaluate((element) => { element.textContent = "超长动态内容".repeat(30); });
  await assertLayout(page, `${pageName}-long-text`);
  await target.evaluate((element, text) => { element.textContent = text; }, original);
}

async function assertReducedMotion(page) {
  await page.emulateMedia({ reducedMotion: "reduce" });
  const durations = await page.locator(".app-sidebar").evaluate((element) => {
    const style = getComputedStyle(element);
    return [style.transitionDuration, style.animationDuration];
  });
  const milliseconds = durations.flatMap((value) => value.split(",")).map((value) => {
    const duration = value.trim();
    return duration.endsWith("ms") ? Number.parseFloat(duration) : Number.parseFloat(duration) * 1000;
  });
  expect(Math.max(...milliseconds)).toBeLessThanOrEqual(0.1);
  await page.emulateMedia({ reducedMotion: "no-preference" });
}

async function login(page) {
  await page.goto(`${baseURL}/loginView`, { waitUntil: "networkidle" });
  await page.locator("#login-email").fill("e2e.applicant@example.com");
  await page.locator("#login-password").fill("E2e-Interview-2026");
  await Promise.all([
    page.waitForURL("**/applicant/workspace"),
    page.locator('#auth-form button[type="submit"]').click(),
  ]);
}

for (const viewport of viewports) {
  test(`visual and responsive audit at ${viewport.width}x${viewport.height}`, async ({ browser }) => {
    test.setTimeout(90000);
    const context = await browser.newContext({ viewport });
    const page = await context.newPage();
    const pageErrors = [];
    page.on("pageerror", (error) => pageErrors.push(error.message));

    for (const [name, path] of publicPages) {
      await page.goto(`${baseURL}${path}`, { waitUntil: "networkidle" });
      await assertLayout(page, name);
      await assertTouchTargets(page, name);
      if (name === "home") {
        const heroBackground = await page.locator(".hero-copy").evaluate((element) => getComputedStyle(element).backgroundColor);
        const expectedHeroBackground = viewport.width <= 768 ? "rgb(8, 10, 15)" : "rgba(0, 0, 0, 0)";
        expect(heroBackground, "hero copy must remain readable without desktop overlap").toBe(expectedHeroBackground);
      }
      await page.screenshot({ path: `tests/e2e/screenshots/${viewport.name}-${name}.png`, fullPage: true });
    }

    await login(page);
    for (const [name, path] of applicantPages) {
      await page.goto(`${baseURL}${path}`, { waitUntil: "networkidle" });
      await assertLayout(page, name);
      await assertTouchTargets(page, name);
      await assertDynamicTextResilience(page, name);
      await page.screenshot({ path: `tests/e2e/screenshots/${viewport.name}-${name}.png`, fullPage: true });
    }

    await page.goto(`${baseURL}/applicant/workspace`, { waitUntil: "networkidle" });
    await page.keyboard.press("Tab");
    const focusedOutline = await page.locator(":focus").evaluate((element) => {
      const style = getComputedStyle(element);
      return { style: style.outlineStyle, width: Number.parseFloat(style.outlineWidth) };
    });
    expect(focusedOutline.style).not.toBe("none");
    expect(focusedOutline.width).toBeGreaterThan(0);
    await assertReducedMotion(page);

    await page.goto(`${baseURL}/applicant/interview-plans?create=1`, { waitUntil: "networkidle" });
    if (!(await page.locator("#plan-dialog").isVisible())) await page.locator("[data-open-plan]").click();
    await expect(page.locator("#plan-dialog")).toBeVisible();
    await assertLayout(page, "plan-dialog");
    await assertTouchTargets(page, "plan-dialog");
    await page.screenshot({ path: `tests/e2e/screenshots/${viewport.name}-plan-dialog.png`, fullPage: true });
    await page.keyboard.press("Escape");
    await expect(page.locator("#plan-dialog")).not.toBeVisible();

    await page.goto(`${baseURL}/applicant/knowledge`, { waitUntil: "networkidle" });
    await page.locator("[data-open-knowledge]").click();
    await expect(page.locator("#knowledge-dialog")).toBeVisible();
    await assertLayout(page, "knowledge-dialog");
    await page.locator("[data-close-knowledge]").first().click();
    await page.locator("[data-open-import]").click();
    await expect(page.locator("#knowledge-import-dialog")).toBeVisible();
    await assertLayout(page, "knowledge-import-dialog");
    await assertTouchTargets(page, "knowledge-import-dialog");
    await page.screenshot({ path: `tests/e2e/screenshots/${viewport.name}-knowledge-import.png`, fullPage: true });
    await page.keyboard.press("Escape");
    await expect(page.locator("#knowledge-import-dialog")).not.toBeVisible();

    if (viewport.width <= 768) {
      await page.goto(`${baseURL}/applicant/workspace`, { waitUntil: "networkidle" });
      await page.locator("[data-nav-toggle]").click();
      await expect(page.locator("body")).toHaveClass(/nav-open/);
      await page.screenshot({ path: `tests/e2e/screenshots/${viewport.name}-navigation-open.png`, fullPage: true });
      await page.keyboard.press("Escape");
      await expect(page.locator("body")).not.toHaveClass(/nav-open/);
    }
    expect(pageErrors).toEqual([]);
    await context.close();
  });
}
