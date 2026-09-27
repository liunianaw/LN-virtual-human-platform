"""Local CHAT console browser probe using a short temporary fake microphone WAV."""

import os
from pathlib import Path

from playwright.sync_api import sync_playwright


def main():
    token = os.environ["DEV09_ADMIN_JWT"]
    microphone = Path(os.environ["DEV09_MICROPHONE_WAV"]).resolve() if os.environ.get("DEV09_MICROPHONE_WAV") else None
    blocked_playback = os.environ.get("DEV09_BLOCK_AUTOPLAY") == "1"
    screenshot = Path(os.environ["DEV09_SCREENSHOT"])
    errors = []
    media = []
    asr = []
    runtime = []
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True, args=[
            "--use-fake-ui-for-media-stream", "--use-fake-device-for-media-stream",
            *([f"--use-file-for-fake-audio-capture={microphone}"] if microphone else []),
            "--autoplay-policy=no-user-gesture-required",
        ])
        context = browser.new_context(viewport={"width": 1440, "height": 900})
        if blocked_playback:
            context.add_init_script("""(() => { const original = HTMLMediaElement.prototype.play;
                let blocked = false; HTMLMediaElement.prototype.play = function(...args) {
                  if (!blocked) { blocked = true; return Promise.reject(new DOMException('Playback blocked', 'NotAllowedError')); }
                  return original.apply(this, args);
                }; })();""")
        context.add_cookies([{"name": "Admin-Token", "value": token, "url": "http://127.0.0.1"}])
        page = context.new_page()
        page.on("pageerror", lambda error: errors.append(str(error)))
        page.on("response", lambda response: media.append(response.status) if "/api/v1/runtime/media/" in response.url
                else asr.append(response.status) if "/api/v1/runtime/asr" in response.url
                else runtime.append((response.status, response.url.split("?")[0])) if "/api/v1/runtime/" in response.url else None)
        page.goto("http://127.0.0.1/system/applications", wait_until="domcontentloaded")
        for attempt in range(2):
            page.get_by_text("test", exact=True).first.wait_for(timeout=20000)
            page.locator("tr").filter(has=page.get_by_text("test", exact=True)).first.get_by_text("进入调试", exact=True).click()
            page.get_by_placeholder("输入对话内容；点击发送才会调用开发者 Relay。").wait_for(timeout=30000)
            try:
                page.get_by_text("已连接，等待输入", exact=True).wait_for(timeout=30000)
                break
            except Exception:
                if attempt == 1:
                    print(f"runtime={runtime[-8:]} errors={errors[:3]} visible={page.locator('body').inner_text()[-500:]}")
                    raise
                page.goto("http://127.0.0.1/system/applications", wait_until="domcontentloaded")
        recognized = "skipped"
        if microphone:
            page.get_by_role("button", name="录音", exact=True).click()
            page.get_by_text("正在录音", exact=True).wait_for(timeout=10000)
            page.wait_for_timeout(2300)
            page.get_by_role("button", name="结束识别", exact=True).click()
            try:
                page.get_by_text("识别完成，请确认文字后发送", exact=True).wait_for(timeout=30000)
            except Exception:
                print(f"asr={asr} runtime={runtime[-8:]} errors={errors[:3]} visible={page.locator('body').inner_text()[-500:]}")
                raise
            recognized = page.get_by_placeholder("输入对话内容；点击发送才会调用开发者 Relay。").input_value()
            assert "识别" in recognized, recognized
        page.get_by_placeholder("输入对话内容；点击发送才会调用开发者 Relay。").fill("DEV09_SENTENCES_TEST")
        page.get_by_role("button", name="发送", exact=True).click()
        if blocked_playback:
            page.get_by_role("button", name="点击继续播放", exact=True).wait_for(timeout=30000)
            page.get_by_role("button", name="点击继续播放", exact=True).click()
        page.get_by_text("播放完成", exact=True).wait_for(timeout=45000)
        for _ in range(20):
            if len(media) >= 2:
                break
            page.wait_for_timeout(500)
        answer = page.locator("textarea[readonly]").last.input_value()
        assert answer == "你好。测试完毕。", answer
        assert asr == ([200] if microphone else []) and len(media) == 2 and all(status == 200 for status in media), (asr, media)
        assert not errors, errors[:3]
        screenshot.parent.mkdir(parents=True, exist_ok=True)
        page.screenshot(path=str(screenshot))
        print(f"page=DEV09_PASS recognized={recognized} answer={answer} asr={asr} media={media} browserErrors={len(errors)}")
        browser.close()


if __name__ == "__main__":
    main()
