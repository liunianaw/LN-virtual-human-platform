"""Manual local CHAT console page smoke test; browser token comes from environment."""

import os
from pathlib import Path
from urllib.parse import urlparse

from playwright.sync_api import sync_playwright


def main():
    token = os.environ["DEV08_ADMIN_JWT"]
    screenshot = Path(os.environ["DEV08_SCREENSHOT"])
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True)
        context = browser.new_context(viewport={"width": 1440, "height": 900})
        context.add_cookies([{"name": "Admin-Token", "value": token,
                             "url": "http://127.0.0.1"}])
        page = context.new_page()
        errors = []
        debug_requests = []
        failures = []
        page.on("pageerror", lambda error: errors.append(str(error)))
        page.on("requestfailed", lambda request: failures.append((urlparse(request.url).netloc, request.failure)))
        def record(response):
            if "debug-sessions" not in response.url and "/api/v1/runtime/" not in response.url:
                return
            try:
                payload = response.json()
                shape = {"keys": list(payload) if isinstance(payload, dict) else [],
                         "code": payload.get("code") if isinstance(payload, dict) else None,
                         "msg": payload.get("msg") if isinstance(payload, dict) else None,
                         "hasToken": bool(payload.get("token")) if isinstance(payload, dict) else False}
                if "/avatar-package" in response.url and isinstance(payload, dict):
                    shape["dataKeys"] = list(payload.get("data") or {})
                    shape["assetHosts"] = list({urlparse(value).netloc for value in (payload.get("data") or {}).values()
                                                if isinstance(value, str) and value.startswith("https://")})
            except Exception:
                shape = {}
            debug_requests.append((response.status, response.url.split("?")[0], shape))
        page.on("response", record)
        page.goto("http://127.0.0.1/system/applications", wait_until="domcontentloaded")
        page.get_by_text("test", exact=True).first.wait_for(timeout=20000)
        row = page.locator("tr").filter(has=page.get_by_text("test", exact=True)).first
        row.get_by_text("进入调试", exact=True).click()
        try:
            page.get_by_text("应用调试", exact=True).wait_for(timeout=20000)
        except Exception:
            screenshot.parent.mkdir(parents=True, exist_ok=True)
            page.screenshot(path=str(screenshot))
            print(f"debug_requests={debug_requests} browserErrors={errors[:3]} failures={failures[:5]} visible={page.locator('body').inner_text()[-500:]}")
            raise
        page.get_by_placeholder("输入对话内容；点击发送才会调用开发者 Relay。").wait_for(timeout=30000)
        try:
            page.get_by_text("已连接，等待输入", exact=True).wait_for(timeout=30000)
        except Exception:
            screenshot.parent.mkdir(parents=True, exist_ok=True)
            page.screenshot(path=str(screenshot))
            print(f"debug_requests={debug_requests} browserErrors={errors[:3]} failures={failures[:5]} visible={page.locator('body').inner_text()[-500:]}")
            raise
        print("page=connected")
        page.get_by_placeholder("输入对话内容；点击发送才会调用开发者 Relay。").fill("DEV08_FAST_TEST")
        page.get_by_role("button", name="发送", exact=True).click()
        page.get_by_text("对话完成", exact=True).wait_for(timeout=30000)
        answer = page.locator("textarea[readonly]").last.input_value()
        assert "测试流" in answer, answer
        screenshot.parent.mkdir(parents=True, exist_ok=True)
        page.screenshot(path=str(screenshot))
        print(f"page=chat_complete answer={answer} browserErrors={len(errors)} screenshot={screenshot}")
        browser.close()


if __name__ == "__main__":
    main()
