# 排球動作步進（Google TV POC）

在 Google TV 上直接播放 YouTube 上的球賽影片，用遙控器以 0.1 秒之類的小間距逐步檢視動作。

走的是官方 YouTube IFrame Player API（透過 [android-youtube-player](https://github.com/PierfrancescoSoffritti/android-youtube-player) 13.0.0 嵌入），不抽取串流，所以之後要上架也沒有條款問題。

## 功能（第一版）

- 貼上 YouTube 網址或影片 ID 開啟（支援 `watch?v=`、`youtu.be/`、`shorts/`、`live/`）
- 手機上從 YouTube App「分享」到本 App 也能直接開（手機版用）
- 記住最近 10 支影片，以影片標題顯示（透過 YouTube 官方 oEmbed 取得，不需 API key；抓不到時暫時顯示影片 ID），回到首頁按 OK 就能開上一支
- 兩種步距：**小步距**（預設 0.05 秒）和**大步距**（預設 1 秒），都可以從選單挑常用值或自行輸入（0.01–600 秒），設定會記住
- 遙控器操作：

| 按鍵 | 動作 |
| --- | --- |
| ← / → | 後退／前進一個小步距（播放中會先暫停）；按住可連續步進 |
| ↓ / ↑ | 後退／前進一個大步距；按住可連續步進 |
| OK | 播放／暫停 |
| 長按 OK 或選單鍵 | 設定選單：小步距、大步距、速度 |
| ⏪ / ⏩ | 降低／提高速度（遙控器有這兩鍵時） |
| 返回 | 回到首頁 |

手機／平板（觸控裝置）在資訊列會多一排按鈕：⏪ 大｜◀ 小｜▶/❚❚｜小 ▶｜大 ⏩｜小 0.05s｜大 1s｜1x。步進鍵點一下走一步、按住 0.4 秒後連續步進；點「小／大」按鈕開步距選單（常用值或「自訂…」輸入）；速度按鈕點一下輪流切換 0.25x / 0.5x / 1x。電視上不會顯示這排按鈕。

進度條：拖動時時間會即時更新，**手指停住約 0.15 秒**播放器就跳到該位置當預覽，不用放開。移動中刻意不 seek——每次 seek 都會打斷上一次的載入，手指的細微抖動（6dp 內）也會被忽略，否則畫面永遠載不出來。放開時若位置和預覽相同就不再 seek，原本在播放就繼續播。淺色段是已緩衝範圍，拖到這段內預覽最快。電視上進度條只顯示進度。

播放器下方的資訊列顯示：狀態、目前時間 / 總長、步距、速度。資訊列刻意放在播放器「外面」，不疊在畫面上（YouTube API 條款不允許遮擋播放器）。

## 網頁版（手機、平板、電腦）

`web/` 是同樣功能的網頁版，單一個 `index.html`，不需要 build。手機瀏覽器打開網址就能用，iPhone 也可以；也可以「加到主畫面」當 App 用。Android 上加到主畫面後，從 YouTube App「分享」也會出現這個網頁版。

- 網址可以直接帶影片：`…/#v=影片ID`，方便傳給別人
- 觸控：和 Android 版手機畫面一樣的按鈕、按住連續步進、進度條手指停住就預覽；點影片畫面會交給 YouTube 播放器處理（暫停後再播放時，中間殘留的播放／暫停圖示點一下影片就會消失）。電腦上點畫面是播放／暫停
- 版面自動適應螢幕：直式或接近正方形（摺疊機打開）時影片在上、按鈕在下；橫式（直板手機打橫、電腦）時影片填滿左側、按鈕常駐右側，整頁不需要捲動
- 鍵盤：←/→（或 `,` / `.`）小步、↓/↑ 大步、空白鍵播放／暫停、`[` / `]` 調速度、Esc 返回
- 最近看過和步距設定存在各自瀏覽器裡，不會跨裝置同步
- 網頁的時間是播放中每 0.1 秒向播放器查一次（IFrame API 沒有時間回報事件），步進一樣是自己維護游標

### 頻道與我的最愛

- 首頁輸入框也可以貼頻道網址：`youtube.com/@名稱`、直接打 `@名稱`、`/channel/UC…`、舊式 `/user/名稱`（`/c/名稱` 查不到，請改貼 `@名稱`）
- 頻道畫面（`#c=頻道ID`）列出影片：縮圖、標題、上傳日期，一次 50 支，「載入更多」往下；可以用標題篩選已載入的影片
- 分頁：「影片」（不含 Shorts）和「直播」（直播存檔）；頻道沒有直播就不顯示該分頁
- 「☆ 加入最愛」後，頻道會出現在首頁「我的最愛頻道」
- 最愛存在瀏覽器的 localStorage，不會跨裝置同步；首頁最下方「備份／還原最愛頻道」可以匯出成一段文字、在別的裝置匯入
- 影片清單會快取 1 小時，⟳ 重新整理會重新抓

影片清單用 YouTube Data API v3 的 `playlistItems`（每 50 支 1 點額度，每天免費 10,000 點）。「影片」用頻道的 `UULF…` 清單、「直播」用 `UULV…`，這是 YouTube 沒寫進文件的慣例，查不到時「影片」退回 `UU…`（全部上傳，含 Shorts）。

**API 金鑰設定**（只要做一次）：

1. 到 [Google Cloud Console](https://console.cloud.google.com/) 建立專案。
2. 「API 和服務 → 程式庫」搜尋 **YouTube Data API v3**，按「啟用」。
3. 「API 和服務 → 憑證 → 建立憑證 → API 金鑰」。
4. 編輯這把金鑰：
   - 應用程式限制：**網站**，加入 `https://touchout.github.io/*`
   - API 限制：**限制金鑰**，只勾 YouTube Data API v3
5. 把金鑰填進 `web/config.js` 的 `YT_API_KEY`（所有人共用），或在網頁首頁最下方「YouTube API 金鑰」貼上（只存在自己的瀏覽器）。

金鑰會出現在網頁原始碼裡，靠第 4 步的限制防止被拿去別處用。

部署：push 到 `main` 且有改到 `web/` 時，`.github/workflows/pages.yml` 會發布到 GitHub Pages。第一次要先到 repo 的 **Settings → Pages → Build and deployment → Source** 選 **GitHub Actions**。

本機測試：

```bash
cd web && python3 -m http.server 8000
# 開 http://localhost:8000
```

## 實作重點

- **自己維護目前時間**：`PlayerActivity.cursor`。`getCurrentTime()` 是非同步的，快速連按會讀到舊值，所以每次按鍵直接在自己的游標上加減步距再 `seekTo`。seek 後 600ms 內忽略播放器回報的時間，避免被舊值覆蓋。
- **按鍵在 Activity 層攔截**：`dispatchKeyEvent` 先處理 DPAD，WebView 也設成 `blocksDescendants`，不會被它吃掉。
- **按住左右鍵**時 seek 限制在每 120ms 一次，避免播放器跟不上。
- 用 13.0.0 而不是 12.x：13.0.0 改用 App 套件名當 `origin`，處理了新版 YouTube 對嵌入來源（Referer）的要求，舊版在 WebView 可能播不了。

## 建置與安裝

每次 push 到 `main`，GitHub Actions 會自動 build，在 repo 的 **Actions** 頁面點進最新一次執行，下方 Artifacts 的 `app-debug` 就是 APK。

本機建置：

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
