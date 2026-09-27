"""Real browser SDK chat probe using a short BUSINESS token; no paid model call."""

import os
from pathlib import Path

from playwright.sync_api import sync_playwright


def main():
    token = os.environ["DEV08_BUSINESS_TOKEN"]
    root = Path(__file__).resolve().parents[3]
    module_url = "/@fs/" + (root / "avatar-sdk/dist/index.js").as_posix()
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True)
        page = browser.new_page(viewport={"width": 1000, "height": 700})
        errors = []
        page.on("pageerror", lambda error: errors.append(str(error)))
        page.goto("http://127.0.0.1/", wait_until="domcontentloaded")
        result = page.evaluate("""async ({ token, moduleUrl }) => {
            const { createAvatar, SessionClient } = await import(moduleUrl);
            const host = document.createElement('div');
            document.body.append(host);
            const player = createAvatar({ container: host });
            const client = new SessionClient({
                baseUrl: 'http://127.0.0.1:8080', player,
                getToken: async () => ({ token, expiresAt: new Date(Date.now() + 600000).toISOString() })
            });
            const events = [], text = [];
            client.on(event => {
                events.push(event.type);
                if (event.type === 'text.delta') text.push(event.data?.text ?? '');
            });
            try {
                const session = await client.connect();
                const finished = new Promise((resolve, reject) => {
                    const timer = setTimeout(() => reject(new Error('SDK chat timed out')), 15000);
                    const off = client.on(event => {
                        if (event.type === 'turn.completed' || event.type === 'turn.failed') {
                            clearTimeout(timer); off(); resolve(event.type);
                        }
                    });
                });
                client.chat('DEV08_FAST_TEST');
                return { sessionId: session.sessionId, terminal: await finished,
                         answer: text.join(''), events };
            } finally { client.destroy(); }
        }""", {"token": token, "moduleUrl": module_url})
        assert result["terminal"] == "turn.completed", result
        assert "测试流" in result["answer"], result
        assert not errors, errors
        print(f"sdk=connected session={result['sessionId']} terminal={result['terminal']} answer={result['answer']} events={result['events']}")
        browser.close()


if __name__ == "__main__":
    main()
