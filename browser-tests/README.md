# Browser workflow verification

With Compose running at :8086:

```sh
npm install
npm test
```

The script uses installed Chrome by default; set BROWSER_CHANNEL=chromium after `npx playwright install chromium` to use
Playwright Chromium. BASE_URL and SCREENSHOT can override the URL and capture location. Tests require the default demo
credentials and mutate only demo data. The script verifies customer checkout/ticket/refund, organizer draft creation,
admin approval and published discovery. The test screenshot is an execution artifact, not the UI implementation.
