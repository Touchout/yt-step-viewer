# 排球動作步進（Google TV POC）

在 Google TV 上直接播放 YouTube 上的球賽影片，用遙控器以 0.1 秒之類的小間距逐步檢視動作。

走的是官方 YouTube IFrame Player API（透過 [android-youtube-player](https://github.com/PierfrancescoSoffritti/android-youtube-player) 13.0.0 嵌入），不抽取串流，所以之後要上架也沒有條款問題。

## 功能（第一版）

- 貼上 YouTube 網址或影片 ID 開啟（支援 `watch?v=`、`youtu.be/`、`shorts/`、`live/`）
- 手機上從 YouTube App「分享」到本 App 也能直接開（手機版用）
- 記住最近 10 支影片，回到首頁按 OK 就能開上一支
- 遙控器操作：

| 按鍵 | 動作 |
| --- | --- |
| ← / → | 後退／前進一個步距（播放中會先暫停）；按住可連續步進 |
| ↑ / ↓ | 切換步距：1 格(1/30s) / 0.05s / 0.1s（預設）/ 0.2s / 0.5s / 1s |
| OK | 播放／暫停 |
| 長按 OK | 循環切換速度 0.25x → 0.5x → 1x |
| ⏪ / ⏩ | 降低／提高速度（遙控器有這兩鍵時） |
| 返回 | 回到首頁 |

播放器下方的資訊列顯示：狀態、目前時間 / 總長、步距、速度。資訊列刻意放在播放器「外面」，不疊在畫面上（YouTube API 條款不允許遮擋播放器）。

## 實作重點

- **自己維護目前時間**：`PlayerActivity.cursor`。`getCurrentTime()` 是非同步的，快速連按會讀到舊值，所以每次按鍵直接在自己的游標上加減步距再 `seekTo`。seek 後 600ms 內忽略播放器回報的時間，避免被舊值覆蓋。
- **按鍵在 Activity 層攔截**：`dispatchKeyEvent` 先處理 DPAD，WebView 也設成 `blocksDescendants`，不會被它吃掉。
- **按住左右鍵**時 seek 限制在每 120ms 一次，避免播放器跟不上。
- 用 13.0.0 而不是 12.x：13.0.0 改用 App 套件名當 `origin`，處理了新版 YouTube 對嵌入來源（Referer）的要求，舊版在 WebView 可能播不了。

## 建置與安裝

需求：Android Studio（Ladybug 以上）或 Android SDK（compileSdk 36）+ JDK 17/21。

```bash
./gradlew assembleDebug
# 產出：app/build/outputs/apk/debug/app-debug.apk
```

側載到 Google TV：

1. 電視：設定 → 系統 → 關於 → 連按「Android TV 作業系統版本」7 次開啟開發者選項。
2. 設定 → 系統 → 開發人員選項 → 開啟「USB 偵錯」（或「無線偵錯」）。
3. 電腦上：

```bash
adb connect <電視IP>:5555      # 無線偵錯則依畫面配對
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

在電視上輸入網址很痛苦的話，可以用手機開 Google TV App 的遙控器鍵盤貼上，或用 adb 直接打開：

```bash
adb shell am start -n com.example.vbstepper/.MainActivity \
  -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT "https://youtu.be/XXXXXXXXXXX"
```

## POC 要驗證的事

1. 在電視上 seek 的反應速度（按一下到畫面更新要多久）。
2. 0.1 秒步進的精度是否足夠看動作；1 格模式是否真的有差。
3. 長片（1–2 小時的整場比賽）seek 是否穩定。
4. 自己頻道的公開影片嵌入是否正常（若出現「不允許嵌入」，檢查影片的「允許嵌入」設定）。

## 下一步（還沒做）

- 標記時間點（起跳、擊球…）並快速跳回
- 影片清單：直接讀自己頻道或播放清單，不用貼網址
- AB 區段循環慢放
- 截圖後在另一個畫面畫線、量角度（避開疊在播放器上）
- 上架前：橫幅圖、TV App 品質規範、YouTube API 條款檢查
